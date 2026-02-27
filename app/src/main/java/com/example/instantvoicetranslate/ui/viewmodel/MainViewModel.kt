package com.example.instantvoicetranslate.ui.viewmodel

import android.app.Application
import android.content.Intent
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.instantvoicetranslate.asr.SpeechRecognizer
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
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
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
    private val speechRecognizer: SpeechRecognizer,
    private val ttsEngine: TtsEngine,
    private val nllbModelManager: NllbModelManager,
    private val nllbTranslator: NllbTranslator,
) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "MainViewModel"
    }

    val isRunning: StateFlow<Boolean> = translationUiState.isRunning
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

            if (!speechRecognizer.isReady.value || speechRecognizer.currentLanguage.value != srcLang) {
                try {
                    if (speechRecognizer.isReady.value) {
                        speechRecognizer.release()
                    }
                    Log.i(TAG, "Pre-loading ASR model for '$srcLang'...")
                    speechRecognizer.initialize(
                        modelDownloader.getModelDir(srcLang).absolutePath,
                        srcLang
                    )
                    Log.i(TAG, "ASR model pre-loaded successfully")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to pre-load ASR model", e)
                    modelDownloader.updateStatus(ModelStatus.Error("ASR init failed: ${e.message}"))
                    punctJob?.cancel()
                    return@launch
                }
            }

            // 3. Wait for punctuation download (started in parallel) and initialize
            if (loadPunct) {
                modelDownloader.updateStatus(ModelStatus.Initializing("Loading punctuation model..."))
                try {
                    punctJob?.await()
                    if (modelDownloader.isPunctModelReady()) {
                        speechRecognizer.initializePunctuation(
                            modelDownloader.getPunctModelDir().absolutePath
                        )
                        Log.i(TAG, "Punctuation model pre-loaded")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Punctuation model not available, continuing without it", e)
                }
            }

            // 4. Pre-initialize TTS engine
            modelDownloader.updateStatus(ModelStatus.Initializing("Starting TTS engine..."))
            val ttsLocale = Locale.forLanguageTag(currentSettings.targetLanguage)
            if (!ttsEngine.isInitialized.value) {
                Log.i(TAG, "Pre-initializing TTS for locale: $ttsLocale")
                ttsEngine.initialize(ttsLocale)
            }

            // 5. If offline mode is enabled and NLLB model is downloaded, pre-load it
            if (currentSettings.offlineMode && nllbModelManager.isModelReady()) {
                modelDownloader.updateStatus(ModelStatus.Initializing("Loading offline translation model..."))
                try {
                    if (!nllbTranslator.isInitialized) {
                        nllbTranslator.initialize()
                    }
                    Log.i(TAG, "NLLB translator pre-loaded for offline mode")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to pre-load NLLB translator", e)
                    // Not fatal — offline mode can still try to initialize at translate time
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

    fun startTranslation() {
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
        val intent = Intent(application, TranslationService::class.java).apply {
            action = TranslationService.ACTION_START
            putExtra(TranslationService.EXTRA_AUDIO_SOURCE, AudioCaptureManager.Source.SYSTEM_AUDIO.name)
            putExtra(TranslationService.EXTRA_PROJECTION_RESULT_CODE, resultCode)
            putExtra(TranslationService.EXTRA_PROJECTION_DATA, data)
        }
        application.startForegroundService(intent)
    }

    fun stopTranslation() {
        val intent = Intent(application, TranslationService::class.java).apply {
            action = TranslationService.ACTION_STOP
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
