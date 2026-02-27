package com.example.instantvoicetranslate.tts

import android.content.Context
import android.media.AudioAttributes
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

/**
 * TTS engine with a sequential queue for streaming translation.
 *
 * All segments are spoken in order without skipping. New utterances are
 * appended to the Android TTS internal queue (QUEUE_ADD) so nothing is lost.
 * The queue processor waits for each utterance to finish before dequeuing
 * the next one, keeping [isSpeaking] accurate for feedback-loop prevention.
 *
 * Long translated segments are automatically split into individual sentences
 * so that TTS can begin speaking the first sentence while synthesizing the rest.
 */
@Singleton
class TtsEngine @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "TtsEngine"
        private const val UTTERANCE_TIMEOUT_MS = 30_000L

        /**
         * Matches sentence-ending punctuation (.!?) followed by whitespace.
         * Uses a fixed-length lookbehind (single char) which is safe for Java regex.
         */
        private val SENTENCE_SPLIT_REGEX =
            Regex("""(?<=[.!?])\s+""")
    }

    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    private val _isInitialized = MutableStateFlow(false)
    val isInitialized: StateFlow<Boolean> = _isInitialized.asStateFlow()

    private var tts: TextToSpeech? = null
    private val utteranceId = AtomicInteger(0)
    private var currentDeferred: CompletableDeferred<Unit>? = null

    /**
     * Unlimited channel: all segments are queued and spoken in order.
     * Nothing is dropped — every translated segment will be read aloud.
     */
    private val speechQueue = Channel<String>(Channel.UNLIMITED)
    private var queueScope: CoroutineScope? = null

    fun initialize(locale: Locale = Locale.forLanguageTag("ru"), onReady: (() -> Unit)? = null) {
        // If already initialized with a working engine, just update locale
        if (_isInitialized.value && tts != null) {
            tts?.language = locale
            onReady?.invoke()
            return
        }

        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val result = tts?.setLanguage(locale)
                if (result == TextToSpeech.LANG_MISSING_DATA ||
                    result == TextToSpeech.LANG_NOT_SUPPORTED
                ) {
                    Log.w(TAG, "Language $locale not supported, trying English")
                    tts?.language = Locale.US
                }

                // Use USAGE_ASSISTANT so that TTS audio is NOT captured by
                // MediaProjection (AudioPlaybackCapture). Android automatically
                // excludes USAGE_ASSISTANT from playback capture, preventing
                // the ASR model from hearing its own translated speech output.
                tts?.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )

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

    /**
     * Splits text into sentences and enqueues each one separately.
     * This allows Android TTS to begin speaking the first sentence
     * while synthesizing subsequent ones, reducing perceived latency.
     */
    fun speak(text: String) {
        if (text.isBlank()) return
        val sentences = splitIntoSentences(text)
        for (sentence in sentences) {
            speechQueue.trySend(sentence)
        }
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

        // QUEUE_ADD appends to the TTS queue — nothing is interrupted or lost.
        engine.speak(text, TextToSpeech.QUEUE_ADD, null, id)

        withTimeoutOrNull(UTTERANCE_TIMEOUT_MS) {
            deferred.await()
        } ?: run {
            Log.w(TAG, "TTS utterance timed out: $id")
            _isSpeaking.value = false
        }
    }

    /**
     * Splits text into individual sentences by sentence-ending punctuation.
     * Returns a list with at least one element (the original text if no split points found).
     */
    private fun splitIntoSentences(text: String): List<String> {
        val sentences = SENTENCE_SPLIT_REGEX.split(text)
            .map { it.trim() }
            .filter { it.isNotBlank() }

        return sentences.ifEmpty { listOf(text) }
    }
}
