package com.example.instantvoicetranslate.ui.viewmodel

import android.app.Application
import android.content.Intent
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.instantvoicetranslate.asr.ConversationRecognizerPool
import com.example.instantvoicetranslate.audio.AudioCaptureManager
import com.example.instantvoicetranslate.data.AppSettings
import com.example.instantvoicetranslate.data.ModelDownloader
import com.example.instantvoicetranslate.data.ModelStatus
import com.example.instantvoicetranslate.data.SettingsRepository
import com.example.instantvoicetranslate.data.TranslationUiState
import com.example.instantvoicetranslate.service.TranslationService
import com.example.instantvoicetranslate.translation.NllbModelManager
import com.example.instantvoicetranslate.translation.NllbTranslator
import com.example.instantvoicetranslate.tts.TtsEngine
import com.example.instantvoicetranslate.ui.utils.LanguageUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import javax.inject.Inject

/**
 * Main ViewModel that also performs eager pre-initialization of ASR and TTS
 * so that pressing "Record" starts capturing audio immediately without
 * waiting for model loading or TTS engine init.
 */
@HiltViewModel
class MainViewModel @Inject constructor(
    private val application: Application,
    private val translationUiState: TranslationUiState,
    private val settingsRepository: SettingsRepository,
    private val modelDownloader: ModelDownloader,
    private val recognizerPool: ConversationRecognizerPool,
    private val ttsEngine: TtsEngine,
    private val nllbModelManager: NllbModelManager,
    private val nllbTranslator: NllbTranslator,
) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "MainViewModel"
        /** Safety timeout: auto-clear isStarting if pipeline never reaches running state. */
        private const val STARTING_TIMEOUT_MS = 30_000L
    }

    val isStarting: StateFlow<Boolean> = translationUiState.isStarting
    val isRunning: StateFlow<Boolean> = translationUiState.isRunning
    val isPaused: StateFlow<Boolean> = translationUiState.isPaused
    val partialText: StateFlow<String> = translationUiState.partialText
    val originalText: StateFlow<String> = translationUiState.originalText
    val translatedText: StateFlow<String> = translationUiState.translatedText
    val error: StateFlow<String?> = translationUiState.error
    val modelStatus: StateFlow<ModelStatus> = modelDownloader.status

    val settings: StateFlow<AppSettings> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    init {
        preloadPipelineComponents()
    }

    /**
     * Eagerly download ALL models (ASR + punctuation), load them into the
     * recognizer, and initialize the TTS engine -- all in the background.
     * The record button (FAB) only appears when status reaches ModelStatus.Ready,
     * which happens AFTER every step completes.
     *
     * Punctuation model is downloaded in PARALLEL with the ASR model to save time.
     */
    private fun preloadPipelineComponents() {
        viewModelScope.launch {
            val currentSettings = settingsRepository.settings.first()
            val srcLang = currentSettings.sourceLanguage

            // If model files are already cached, show "Initializing" instead of
            // "Not Downloaded" to avoid a confusing flash of the download card.
            if (modelDownloader.isModelReady(srcLang)) {
                modelDownloader.updateStatus(ModelStatus.Initializing("Loading speech model..."))
            }

            // 1a. Start punctuation model download in parallel (no dependency on ASR)
            val loadPunct = srcLang == "en"
            val punctJob = if (loadPunct) {
                async {
                    try {
                        modelDownloader.ensurePunctModelAvailable()
                    } catch (e: Exception) {
                        Log.w(TAG, "Punctuation model download failed", e)
                    }
                }
            } else null

            // 1b. Ensure ASR model files are downloaded
            // ensureModelAvailable() sets status to Ready internally after download,
            // but we override it below -- the FAB must NOT appear yet.
            modelDownloader.ensureModelAvailable(srcLang)
            if (!modelDownloader.isModelReady(srcLang)) {
                Log.w(TAG, "ASR model for '$srcLang' not ready after ensureModelAvailable")
                punctJob?.cancel()
                return@launch
            }

            // 2. Pre-initialize ASR recognizer (loads ONNX model into memory)
            modelDownloader.updateStatus(ModelStatus.Initializing("Loading speech model..."))

            val recognizer = try {
                Log.i(TAG, "Pre-loading ASR model for '$srcLang'...")
                val r = recognizerPool.acquire(srcLang, modelDownloader.getModelDir(srcLang).absolutePath)
                Log.i(TAG, "ASR model pre-loaded successfully")
                r
            } catch (e: Exception) {
                Log.e(TAG, "Failed to pre-load ASR model", e)
                modelDownloader.updateStatus(ModelStatus.Error("ASR init failed: ${e.message}"))
                punctJob?.cancel()
                return@launch
            }

            // 3. Wire punctuation into the now-ready recognizer once its
            // download (started in parallel above) finishes -- fire-and-
            // forget, NOT awaited here. Punctuation is optional (English
            // sentence-casing only); its download/extraction has been
            // observed stalling for minutes under memory/CPU pressure from
            // the ASR+NLLB loads happening alongside it, and blocking Ready
            // on it meant the whole app appeared hung. Users can start
            // translating immediately now; punctuation kicks in whenever it
            // lands, if it lands.
            if (loadPunct) {
                viewModelScope.launch {
                    try {
                        punctJob?.await()
                        if (modelDownloader.isPunctModelReady()) {
                            recognizer.initializePunctuation(
                                modelDownloader.getPunctModelDir().absolutePath
                            )
                            Log.i(TAG, "Punctuation model pre-loaded")
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Punctuation model not available, continuing without it", e)
                    }
                }
            }

            // Note: the target language's ASR model is intentionally NOT
            // pre-warmed here. Keeping two ASR models resident for the
            // entire app lifetime (on top of NLLB's ~1GB in offline mode)
            // pushed a loaded device over its memory watermark and got the
            // whole process killed by the Android low-memory killer. Instead
            // the pool builds up lazily: the first swapLanguages() call pays
            // a normal cold-load for the new direction, and only
            // conversations that actually swap back and forth keep both
            // directions warm (see ConversationRecognizerPool).

            // 4. Pre-initialize TTS engine
            modelDownloader.updateStatus(ModelStatus.Initializing("Starting TTS engine..."))
            val ttsLocale = Locale.forLanguageTag(currentSettings.targetLanguage)
            if (!ttsEngine.isInitialized.value) {
                Log.i(TAG, "Pre-initializing TTS for locale: $ttsLocale")
                ttsEngine.initialize(ttsLocale)
            }

            // 5. If offline mode is enabled, provision (bundled assets or
            // download) and pre-load the NLLB model. No isModelReady() gate
            // here: with bundled assets this is a fast local copy, not a
            // multi-hundred-MB network download, so it's safe to call
            // unconditionally whenever offline mode is on.
            if (currentSettings.offlineMode) {
                modelDownloader.updateStatus(ModelStatus.Initializing("Loading offline translation model..."))
                try {
                    nllbModelManager.ensureModelAvailable(warmup = {
                        if (!nllbTranslator.isInitialized) {
                            nllbTranslator.initialize()
                        }
                    })
                    Log.i(TAG, "NLLB translator pre-loaded for offline mode")
                } catch (e: Throwable) {
                    // Catch Throwable (not just Exception) because UnsatisfiedLinkError
                    // is an Error, not an Exception, and can occur if ONNX Runtime
                    // native libraries are incompatible.
                    Log.e(TAG, "Failed to pre-load NLLB translator", e)
                }
            }

            // ALL components ready -- NOW the FAB can appear
            modelDownloader.updateStatus(ModelStatus.Ready)
            Log.i(TAG, "Pipeline fully initialized for '$srcLang'")
        }
    }

    fun downloadModel() {
        viewModelScope.launch {
            val lang = settings.value.sourceLanguage
            modelDownloader.ensureModelAvailable(lang)
        }
    }

    /**
     * Releases every warm ASR recognizer before running [action] -- trades
     * away the pool's "keep both swap directions warm" convenience (instant
     * swap-back) for a lower peak memory footprint, so the upcoming model
     * load never has to coexist with whatever's already resident. Called
     * unconditionally before every start/swap (not just when memory happens
     * to be low): on memory-constrained devices, loading a new ASR model on
     * top of an already-warm one was observed reliably tipping the whole
     * system into a low-memory-killer thrashing cascade, and the full-reload
     * cost this trades in for (a few seconds) was confirmed an acceptable
     * default rather than something to ask about every time.
     */
    fun releaseWarmRecognizersThen(action: () -> Unit) {
        recognizerPool.releaseAll()
        action()
    }

    /**
     * Flips source <-> target language for a conversation-direction swap.
     * Only enabled when the current target is itself ASR-capable (otherwise
     * there'd be no recognizer model to hear the reply) -- callers should
     * gate the UI control on that same condition.
     *
     * If a translation session is running on the microphone source, restarts
     * it so the new pairing takes effect immediately -- the restart pays a
     * full model reload (a few seconds), since [releaseWarmRecognizersThen]'s
     * reasoning applies here too: the previous direction's model is released
     * before the new one loads, rather than kept warm alongside it. System-
     * audio sessions are left running with the old pairing (restarting would
     * require re-requesting MediaProjection consent via an Activity), taking
     * effect on the next manual start.
     */
    fun swapLanguages() {
        val current = settings.value
        if (current.targetLanguage !in LanguageUtils.sourceLanguages.map { it.first }) {
            Log.w(TAG, "Swap ignored: target '${current.targetLanguage}' has no ASR model")
            return
        }

        val wasRunning = isRunning.value
        val canAutoRestart = wasRunning && current.audioSource == AudioCaptureManager.Source.MICROPHONE
        if (wasRunning && !canAutoRestart) {
            Log.i(TAG, "Swap applied to settings; system-audio session keeps running with the old pairing")
        }

        viewModelScope.launch {
            if (canAutoRestart) {
                stopTranslation()
                // stopTranslation()/startTranslation() just fire Android
                // Intents at the service (fire-and-forget) -- wait for the
                // stop to actually land so the START intent below can't race
                // a still-in-flight stopSelf() from the previous session.
                // This also guarantees the recognizer released just below
                // isn't still actively processing audio when released.
                withTimeoutOrNull(2_000) { isRunning.first { !it } }
            }

            settingsRepository.swapLanguages()

            // Release the previous direction's warm recognizer before
            // loading the new one instead of keeping both resident -- see
            // releaseWarmRecognizersThen's doc for why. Safe here: any
            // running session was already stopped and waited on above.
            recognizerPool.releaseAll()

            val newSrcLang = current.targetLanguage
            try {
                modelDownloader.ensureModelAvailable(newSrcLang)
                if (modelDownloader.isModelReady(newSrcLang)) {
                    recognizerPool.acquire(newSrcLang, modelDownloader.getModelDir(newSrcLang).absolutePath)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to pre-warm swapped source language '$newSrcLang'", e)
            }

            if (canAutoRestart) startTranslation()
        }
    }

    fun startTranslation() {
        translationUiState.setStarting(true)
        launchStartingTimeout()
        val intent = Intent(application, TranslationService::class.java).apply {
            action = TranslationService.ACTION_START
            putExtra(
                TranslationService.EXTRA_AUDIO_SOURCE,
                settings.value.audioSource.name
            )
        }
        application.startForegroundService(intent)
    }

    /**
     * Start translation with system audio capture.
     * The MediaProjection consent result (resultCode + data) is forwarded
     * to the foreground service, which creates the MediaProjection after
     * calling startForeground(). This is required on Android 14+ (API 34).
     */
    fun startTranslationWithProjection(resultCode: Int, data: Intent) {
        translationUiState.setStarting(true)
        launchStartingTimeout()
        val intent = Intent(application, TranslationService::class.java).apply {
            action = TranslationService.ACTION_START
            putExtra(TranslationService.EXTRA_AUDIO_SOURCE, AudioCaptureManager.Source.SYSTEM_AUDIO.name)
            putExtra(TranslationService.EXTRA_PROJECTION_RESULT_CODE, resultCode)
            putExtra(TranslationService.EXTRA_PROJECTION_DATA, data)
        }
        application.startForegroundService(intent)
    }

    /**
     * Safety net: if the pipeline never reaches running state within the timeout,
     * clear isStarting to unblock the FAB.
     */
    private fun launchStartingTimeout() {
        viewModelScope.launch {
            delay(STARTING_TIMEOUT_MS)
            if (translationUiState.isStarting.value) {
                Log.w(TAG, "Starting timeout reached, clearing isStarting")
                translationUiState.setStarting(false)
            }
        }
    }

    fun stopTranslation() {
        val intent = Intent(application, TranslationService::class.java).apply {
            action = TranslationService.ACTION_STOP
        }
        application.startService(intent)
    }

    /**
     * Pauses recognition/TTS without tearing down the pipeline (models,
     * audio capture, and the foreground session all stay alive) so a quick
     * tap can resume exactly where it left off -- unlike [stopTranslation],
     * which ends the session.
     */
    fun pauseTranslation() {
        val intent = Intent(application, TranslationService::class.java).apply {
            action = TranslationService.ACTION_PAUSE
        }
        application.startService(intent)
    }

    fun resumeTranslation() {
        val intent = Intent(application, TranslationService::class.java).apply {
            action = TranslationService.ACTION_RESUME
        }
        application.startService(intent)
    }

    fun updateAudioSource(source: AudioCaptureManager.Source) {
        viewModelScope.launch {
            settingsRepository.updateAudioSource(source)
        }
    }

    fun clearError() {
        translationUiState.setError(null)
    }
}
