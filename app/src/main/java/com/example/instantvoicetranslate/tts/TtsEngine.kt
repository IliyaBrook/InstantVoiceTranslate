package com.example.instantvoicetranslate.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TtsEngine @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "TtsEngine"
        private const val UTTERANCE_TIMEOUT_MS = 30_000L
    }

    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    private val _isInitialized = MutableStateFlow(false)
    val isInitialized: StateFlow<Boolean> = _isInitialized.asStateFlow()

    private var tts: TextToSpeech? = null
    private val utteranceId = AtomicInteger(0)
    private var currentDeferred: CompletableDeferred<Unit>? = null

    private val speechQueue = Channel<String>(Channel.UNLIMITED)
    private var queueScope: CoroutineScope? = null

    fun initialize(locale: Locale = Locale.forLanguageTag("ru"), onReady: (() -> Unit)? = null) {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val result = tts?.setLanguage(locale)
                if (result == TextToSpeech.LANG_MISSING_DATA ||
                    result == TextToSpeech.LANG_NOT_SUPPORTED
                ) {
                    Log.w(TAG, "Language $locale not supported, trying English")
                    tts?.setLanguage(Locale.US)
                }

                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        _isSpeaking.value = true
                    }

                    override fun onDone(utteranceId: String?) {
                        _isSpeaking.value = false
                        currentDeferred?.complete(Unit)
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        _isSpeaking.value = false
                        currentDeferred?.complete(Unit)
                    }

                    override fun onError(utteranceId: String?, errorCode: Int) {
                        Log.e(TAG, "TTS error: $errorCode")
                        _isSpeaking.value = false
                        currentDeferred?.complete(Unit)
                    }
                })

                _isInitialized.value = true
                startQueueProcessor()
                onReady?.invoke()
                Log.i(TAG, "TTS initialized with locale: $locale")
            } else {
                Log.e(TAG, "TTS initialization failed with status: $status")
            }
        }
    }

    fun speak(text: String) {
        if (text.isBlank()) return
        speechQueue.trySend(text)
    }

    fun stop() {
        tts?.stop()
        _isSpeaking.value = false
        currentDeferred?.complete(Unit)
        // Drain the queue
        while (speechQueue.tryReceive().isSuccess) { /* discard */ }
    }

    fun setSpeechRate(rate: Float) {
        tts?.setSpeechRate(rate.coerceIn(0.5f, 2.0f))
    }

    fun setPitch(pitch: Float) {
        tts?.setPitch(pitch.coerceIn(0.5f, 2.0f))
    }

    fun setLanguage(locale: Locale) {
        tts?.setLanguage(locale)
    }

    fun release() {
        stop()
        queueScope?.cancel()
        queueScope = null
        tts?.shutdown()
        tts = null
        _isInitialized.value = false
    }

    private fun startQueueProcessor() {
        queueScope?.cancel()
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        queueScope = scope

        scope.launch {
            for (text in speechQueue) {
                speakAndWait(text)
            }
        }
    }

    private suspend fun speakAndWait(text: String) {
        val engine = tts ?: return
        val id = "utterance_${utteranceId.getAndIncrement()}"
        val deferred = CompletableDeferred<Unit>()
        currentDeferred = deferred

        engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)

        withTimeoutOrNull(UTTERANCE_TIMEOUT_MS) {
            deferred.await()
        } ?: run {
            Log.w(TAG, "TTS utterance timed out: $id")
            _isSpeaking.value = false
        }
    }
}
