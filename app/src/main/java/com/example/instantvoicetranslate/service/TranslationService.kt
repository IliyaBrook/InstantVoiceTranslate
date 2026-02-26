package com.example.instantvoicetranslate.service

import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.instantvoicetranslate.MainActivity
import com.example.instantvoicetranslate.R
import com.example.instantvoicetranslate.InstantVoiceTranslateApp
import com.example.instantvoicetranslate.asr.SpeechRecognizer
import com.example.instantvoicetranslate.audio.AudioCaptureManager
import com.example.instantvoicetranslate.data.ModelDownloader
import com.example.instantvoicetranslate.data.SettingsRepository
import com.example.instantvoicetranslate.data.TranslationUiState
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
import java.util.Locale
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
        private const val NOTIFICATION_ID = 1
    }

    @Inject lateinit var audioCaptureManager: AudioCaptureManager
    @Inject lateinit var speechRecognizer: SpeechRecognizer
    @Inject lateinit var translator: TextTranslator
    @Inject lateinit var ttsEngine: TtsEngine
    @Inject lateinit var uiState: TranslationUiState
    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var modelDownloader: ModelDownloader

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var pipelineJob: Job? = null
    private var isPaused = false
    private var currentAudioSource = AudioCaptureManager.Source.MICROPHONE

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
                startPipeline()
            }
            ACTION_STOP -> stopPipeline()
            ACTION_PAUSE -> pausePipeline()
            ACTION_RESUME -> resumePipeline()
        }
        return START_STICKY
    }

    private fun startPipeline() {
        startForegroundNotification()

        pipelineJob?.cancel()
        pipelineJob = serviceScope.launch {
            try {
                val settings = settingsRepository.settings.first()

                // Ensure model is ready
                if (!modelDownloader.isModelReady()) {
                    uiState.setError("Model not downloaded")
                    stopSelf()
                    return@launch
                }

                // Initialize recognizer if needed
                if (!speechRecognizer.isReady.value) {
                    speechRecognizer.initialize(modelDownloader.modelDir.absolutePath)
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

                // Start recognition
                speechRecognizer.startRecognition(audioFlow)

                // Collect partial results → UI
                launch {
                    speechRecognizer.partialText.collect { text ->
                        uiState.updatePartial(text)
                    }
                }

                // Collect final segments → translate → TTS → UI
                launch {
                    speechRecognizer.recognizedSegments.collect { segment ->
                        if (isPaused) return@collect

                        uiState.updateOriginal(segment)
                        val srcLang = settings.sourceLanguage
                        val tgtLang = settings.targetLanguage

                        val result = translator.translate(segment, srcLang, tgtLang)
                        result.onSuccess { translated ->
                            uiState.updateTranslated(translated)
                            if (settings.autoSpeak) {
                                ttsEngine.speak(translated)
                            }
                        }.onFailure { error ->
                            Log.e(TAG, "Translation failed", error)
                            uiState.setError("Translation failed: ${error.message}")
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

    private fun stopPipeline() {
        pipelineJob?.cancel()
        pipelineJob = null
        speechRecognizer.stopRecognition()
        audioCaptureManager.stopCapture()
        ttsEngine.stop()
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

        val pauseResumeIntent = PendingIntent.getService(
            this, 2,
            Intent(this, TranslationService::class.java).apply {
                action = if (paused) ACTION_RESUME else ACTION_PAUSE
            },
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, InstantVoiceTranslateApp.CHANNEL_ID)
            .setContentTitle(if (paused) "Translation paused" else "Translating...")
            .setContentText("Tap to open app")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .addAction(
                0,
                if (paused) "Resume" else "Pause",
                pauseResumeIntent
            )
            .addAction(0, "Stop", stopIntent)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        pipelineJob?.cancel()
        serviceScope.cancel()
        uiState.setRunning(false)
    }
}
