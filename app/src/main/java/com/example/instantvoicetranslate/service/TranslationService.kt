package com.example.instantvoicetranslate.service

import android.app.Activity
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.instantvoicetranslate.MainActivity
import com.example.instantvoicetranslate.R
import com.example.instantvoicetranslate.InstantVoiceTranslateApp
import com.example.instantvoicetranslate.asr.SpeechRecognizer
import com.example.instantvoicetranslate.audio.AudioCaptureManager
import com.example.instantvoicetranslate.audio.AudioDiagnostics
import com.example.instantvoicetranslate.data.ModelDownloader
import com.example.instantvoicetranslate.data.SettingsRepository
import com.example.instantvoicetranslate.data.TranslationUiState
import com.example.instantvoicetranslate.translation.NllbTranslator
import com.example.instantvoicetranslate.translation.TextTranslator
import com.example.instantvoicetranslate.tts.TtsEngine
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject

@AndroidEntryPoint
class TranslationService : Service() {

    companion object {
        private const val TAG = "TranslationService"
        const val ACTION_START = "com.example.instantvoicetranslate.START"
        const val ACTION_STOP = "com.example.instantvoicetranslate.STOP"
        const val ACTION_PAUSE = "com.example.instantvoicetranslate.PAUSE"
        const val ACTION_RESUME = "com.example.instantvoicetranslate.RESUME"
        const val EXTRA_AUDIO_SOURCE = "audio_source"
        const val EXTRA_PROJECTION_RESULT_CODE = "projection_result_code"
        const val EXTRA_PROJECTION_DATA = "projection_data"
        private const val NOTIFICATION_ID = 1
        /** Maximum number of concurrent translation requests. */
        private const val MAX_CONCURRENT_TRANSLATIONS = 3
    }

    @Inject lateinit var audioCaptureManager: AudioCaptureManager
    @Inject lateinit var speechRecognizer: SpeechRecognizer
    @Inject lateinit var translator: TextTranslator
    @Inject lateinit var nllbTranslator: NllbTranslator
    @Inject lateinit var ttsEngine: TtsEngine
    @Inject lateinit var uiState: TranslationUiState
    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var modelDownloader: ModelDownloader

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var pipelineJob: Job? = null
    private var isPaused = false
    private var currentAudioSource = AudioCaptureManager.Source.MICROPHONE

    /**
     * Monotonically increasing segment counter.
     * Used to ensure translations are sent to TTS in the correct order
     * even when translated in parallel.
     */
    private val segmentCounter = AtomicLong(0)

    /**
     * Ordered delivery buffer for TTS.
     *
     * Translations complete out-of-order (parallel HTTP), but TTS must
     * receive them sequentially. Completed translations are stored here
     * keyed by their sequence number. A flush routine sends all
     * consecutive ready segments to TTS starting from [nextTtsSeqNum].
     */
    private val translationBuffer = ConcurrentHashMap<Long, String>()
    private val nextTtsSeqNum = AtomicLong(0)
    private val ttsFlushLock = Any()

    /** Last translated segment, used as context for the next translation. */
    @Volatile
    private var lastTranslatedSegment: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val sourceName = intent.getStringExtra(EXTRA_AUDIO_SOURCE)
                currentAudioSource = try {
                    AudioCaptureManager.Source.valueOf(sourceName ?: "MICROPHONE")
                } catch (_: Exception) {
                    AudioCaptureManager.Source.MICROPHONE
                }
                startPipeline(intent)
            }
            ACTION_STOP -> stopPipeline()
            ACTION_PAUSE -> pausePipeline()
            ACTION_RESUME -> resumePipeline()
        }
        return START_STICKY
    }

    private fun startPipeline(intent: Intent) {
        startForegroundNotification()

        if (currentAudioSource == AudioCaptureManager.Source.SYSTEM_AUDIO) {
            // IMPORTANT: Activity.RESULT_OK == -1, so we must NOT use -1 as the
            // default/sentinel value for getIntExtra(). Use Int.MIN_VALUE instead.
            val resultCode = intent.getIntExtra(EXTRA_PROJECTION_RESULT_CODE, Int.MIN_VALUE)
            @Suppress("DEPRECATION")
            val data = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(EXTRA_PROJECTION_DATA, Intent::class.java)
            } else {
                intent.getParcelableExtra(EXTRA_PROJECTION_DATA)
            }
            Log.d(TAG, "MediaProjection consent: resultCode=$resultCode, data=${data != null}")
            if (resultCode != Activity.RESULT_OK || data == null) {
                Log.e(TAG, "Missing or denied MediaProjection consent: resultCode=$resultCode, hasData=${data != null}")
                uiState.setError("System audio: permission not granted")
                stopPipeline()
                return
            }
            val projectionManager =
                getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val projection = try {
                projectionManager.getMediaProjection(resultCode, data)
            } catch (e: Exception) {
                Log.e(TAG, "getMediaProjection() threw an exception", e)
                null
            }
            if (projection == null) {
                Log.e(TAG, "Failed to create MediaProjection")
                uiState.setError("System audio: failed to start capture")
                stopPipeline()
                return
            }
            // Register callback to handle projection being revoked by the system
            projection.registerCallback(object : android.media.projection.MediaProjection.Callback() {
                override fun onStop() {
                    Log.i(TAG, "MediaProjection stopped by system")
                    stopPipeline()
                }
            }, android.os.Handler(mainLooper))
            audioCaptureManager.setMediaProjection(projection)
        }

        // Reset ordering state and clear previous results for this session
        segmentCounter.set(0)
        translationBuffer.clear()
        nextTtsSeqNum.set(0)
        lastTranslatedSegment = null
        uiState.clearTexts()
        uiState.setError(null)

        pipelineJob?.cancel()
        pipelineJob = serviceScope.launch {
            try {
                val settings = settingsRepository.settings.first()

                // Ensure model for source language is ready
                val srcLang = settings.sourceLanguage
                if (!modelDownloader.isModelReady(srcLang)) {
                    Log.i(TAG, "Model for '$srcLang' not ready, downloading...")
                    modelDownloader.ensureModelAvailable(srcLang)
                    if (!modelDownloader.isModelReady(srcLang)) {
                        uiState.setError("Model for $srcLang not downloaded")
                        stopSelf()
                        return@launch
                    }
                }

                // Initialize recognizer if needed, or reinitialize if language changed
                val needsInit = !speechRecognizer.isReady.value ||
                        speechRecognizer.currentLanguage.value != srcLang
                if (needsInit) {
                    if (speechRecognizer.isReady.value) {
                        speechRecognizer.release()
                    }
                    speechRecognizer.initialize(
                        modelDownloader.getModelDir(srcLang).absolutePath,
                        srcLang
                    )
                    // Initialize punctuation model if available
                    if (srcLang == "en" && modelDownloader.isPunctModelReady()) {
                        speechRecognizer.initializePunctuation(
                            modelDownloader.getPunctModelDir().absolutePath
                        )
                    }
                }

                // Select translator based on offline mode
                val activeTranslator: TextTranslator = if (settings.offlineMode) {
                    try {
                        if (!nllbTranslator.isInitialized) {
                            nllbTranslator.initialize()
                        }
                        nllbTranslator
                    } catch (e: Throwable) {
                        // UnsatisfiedLinkError is an Error, not Exception.
                        // Do NOT fall back to online translator — user explicitly
                        // chose offline mode, so failing with a clear error is
                        // better than silently using an online service.
                        Log.e(TAG, "NLLB init failed, cannot proceed in offline mode", e)
                        uiState.setError("Offline translation unavailable: ${e.message}")
                        stopPipeline()
                        return@launch
                    }
                } else {
                    translator
                }

                // Initialize TTS
                val ttsLocale = Locale.forLanguageTag(settings.targetLanguage)
                ttsEngine.initialize(ttsLocale)
                ttsEngine.setSpeechRate(settings.ttsSpeed)
                ttsEngine.setPitch(settings.ttsPitch)

                uiState.setRunning(true)
                uiState.setError(null)

                // Start audio capture using the source passed via intent
                val rawAudioFlow = audioCaptureManager.startCapture(currentAudioSource)

                // Filter out audio while TTS is speaking to prevent feedback loop
                val audioFlow = if (settings.muteMicDuringTts) {
                    rawAudioFlow.filter { !ttsEngine.isSpeaking.value }
                } else {
                    rawAudioFlow
                }

                // Attach audio diagnostics if enabled in settings.
                // Recordings saved to: /sdcard/Android/data/<pkg>/files/audio_diag/
                audioCaptureManager.diagnostics = if (settings.audioDiagnostics) {
                    AudioDiagnostics(this@TranslationService, settings.diagOutputDir)
                } else {
                    null
                }

                // Start recognition (energy-based silence detection inside recognizer)
                speechRecognizer.startRecognition(audioFlow)

                // Collect partial results -> UI
                launch {
                    speechRecognizer.partialText.collect { text ->
                        uiState.updatePartial(text)
                    }
                }

                // Collect final segments -> translate in PARALLEL -> TTS -> UI
                // Each segment gets its own coroutine for translation,
                // so a slow network call does not block subsequent segments.
                launch {
                    // Limit concurrent HTTP translation requests
                    val translationSemaphore = Semaphore(MAX_CONCURRENT_TRANSLATIONS)

                    speechRecognizer.recognizedSegments.collect { segment ->
                        if (isPaused) return@collect

                        // Assign a monotonic sequence number to preserve ordering
                        val seqNum = segmentCounter.getAndIncrement()

                        // Show original text immediately (no waiting for translation)
                        uiState.updateOriginal(segment)

                        val srcLang = settings.sourceLanguage
                        val tgtLang = settings.targetLanguage

                        // Capture context snapshot before launching parallel coroutine
                        val contextSegment = lastTranslatedSegment

                        // Launch translation in a separate coroutine (parallel)
                        launch {
                            translationSemaphore.acquire()
                            try {
                                val result = activeTranslator.translate(
                                    segment, srcLang, tgtLang, contextSegment
                                )
                                result.onSuccess { translated ->
                                    lastTranslatedSegment = translated
                                    uiState.updateTranslated(translated)
                                    if (settings.autoSpeak) {
                                        // Buffer translation and flush in order.
                                        // Parallel translations finish out-of-order,
                                        // but TTS must receive them sequentially.
                                        translationBuffer[seqNum] = translated
                                        flushTtsBuffer()
                                    }
                                }.onFailure { error ->
                                    Log.e(TAG, "Translation failed for segment #$seqNum", error)
                                    uiState.setError("Translation failed: ${error.message}")
                                }
                            } finally {
                                translationSemaphore.release()
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Pipeline error", e)
                uiState.setError("Pipeline error: ${e.message}")
                stopPipeline()
            }
        }
    }

    /**
     * Send all consecutive ready translations to TTS in order.
     *
     * Example: if nextTtsSeqNum=0 and buffer has {0→"a", 2→"c"},
     * this sends "a" to TTS, advances to 1, then stops (1 not ready yet).
     * When segment 1 arrives later, it sends "b" then "c" in one flush.
     */
    private fun flushTtsBuffer() {
        synchronized(ttsFlushLock) {
            while (true) {
                val next = nextTtsSeqNum.get()
                val text = translationBuffer.remove(next) ?: break
                ttsEngine.speak(text)
                nextTtsSeqNum.incrementAndGet()
            }
        }
    }

    private fun stopPipeline() {
        pipelineJob?.cancel()
        pipelineJob = null
        speechRecognizer.stopRecognition()
        audioCaptureManager.stopCapture()
        audioCaptureManager.releaseMediaProjection()
        audioCaptureManager.diagnostics = null
        ttsEngine.stop()
        translationBuffer.clear()
        uiState.setRunning(false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun pausePipeline() {
        isPaused = true
        ttsEngine.stop()
        updateNotification(paused = true)
    }

    private fun resumePipeline() {
        isPaused = false
        updateNotification(paused = false)
    }

    private fun startForegroundNotification() {
        val notification = buildNotification(paused = false)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val serviceType = when (currentAudioSource) {
                AudioCaptureManager.Source.MICROPHONE ->
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                AudioCaptureManager.Source.SYSTEM_AUDIO ->
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            }
            startForeground(NOTIFICATION_ID, notification, serviceType)
        } else {
            // API 29: FOREGROUND_SERVICE_TYPE_MICROPHONE not available
            if (currentAudioSource == AudioCaptureManager.Source.SYSTEM_AUDIO) {
                startForeground(
                    NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        }
    }

    private fun updateNotification(paused: Boolean) {
        val notification = buildNotification(paused)
        val manager = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun buildNotification(paused: Boolean): android.app.Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, TranslationService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE
        )

        // Use FLAG_UPDATE_CURRENT so the PendingIntent action is replaced correctly
        // when toggling between Pause and Resume.
        val pauseResumeIntent = PendingIntent.getService(
            this, 2,
            Intent(this, TranslationService::class.java).apply {
                action = if (paused) ACTION_RESUME else ACTION_PAUSE
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val sourceLabel = when (currentAudioSource) {
            AudioCaptureManager.Source.MICROPHONE -> getString(R.string.notification_source_microphone)
            AudioCaptureManager.Source.SYSTEM_AUDIO -> getString(R.string.notification_source_system_audio)
        }
        val title = if (paused) getString(R.string.notification_title_paused) else getString(R.string.notification_title_active)
        val text = if (paused) getString(R.string.notification_text_paused) else getString(R.string.notification_text_active, sourceLabel)

        return NotificationCompat.Builder(this, InstantVoiceTranslateApp.CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_mic_notification)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setUsesChronometer(!paused) // show elapsed time while recording
            .addAction(
                0,
                if (paused) getString(R.string.notification_action_resume) else getString(R.string.notification_action_pause),
                pauseResumeIntent
            )
            .addAction(R.drawable.ic_stop_notification, getString(R.string.notification_action_stop), stopIntent)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        pipelineJob?.cancel()
        serviceScope.cancel()
        uiState.setRunning(false)
    }
}
