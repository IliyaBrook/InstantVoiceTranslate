package com.example.instantvoicetranslate.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.example.instantvoicetranslate.data.TranslationUiState
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.time.Duration.Companion.milliseconds
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
    @param:ApplicationContext private val context: Context,
    private val uiState: TranslationUiState
) {
    companion object {
        private const val TAG = "TtsEngine"
        private const val UTTERANCE_TIMEOUT_MS = 30_000L

        /**
         * How long to keep [isSpeaking] true after an utterance finishes.
         * Covers the acoustic tail (speaker decay/room echo) that would
         * otherwise still be picked up by the microphone right after
         * playback ends, causing the ASR to transcribe TTS's own trailing
         * audio and feed it back into translation (a feedback loop).
         */
        private const val SPEECH_TAIL_COOLDOWN_MS = 400L

        /**
         * Delay before retrying a locale that initially came back unsupported.
         * Covers external TTS engine apps (e.g. a separate neural-voice app)
         * that can themselves be killed by the system under memory pressure
         * and take a moment to reload their voice data after an automatic
         * restart -- during that window setLanguage() reports the voice
         * missing even though it's actually installed.
         */
        private const val LOCALE_RETRY_DELAY_MS = 1_500L

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
    private var speakingCooldownJob: Job? = null

    // Lives for the singleton's lifetime, independent of [queueScope] (which
    // is cancelled/replaced on every initialize() call) so a scheduled
    // locale retry can't be cancelled out from under it by a later re-init.
    private val engineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // --- Volume & audio ducking ---
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var volume = 1.0f
    private var duckingEnabled = true
    private var audioFocusRequest: AudioFocusRequest? = null

    /**
     * Unlimited channel: all segments are queued and spoken in order.
     * Nothing is dropped — every translated segment will be read aloud.
     */
    private val speechQueue = Channel<String>(Channel.UNLIMITED)
    private var queueScope: CoroutineScope? = null

    fun initialize(locale: Locale = Locale.forLanguageTag("ru"), onReady: (() -> Unit)? = null) {
        // If already initialized with a working engine, just update locale
        if (_isInitialized.value && tts != null) {
            applyLocale(locale)
            onReady?.invoke()
            return
        }

        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                applyLocale(locale)

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
                        speakingCooldownJob?.cancel()
                        _isSpeaking.value = true
                        requestAudioFocus()
                    }

                    override fun onDone(utteranceId: String?) {
                        releaseAudioFocus()
                        currentDeferred?.complete(Unit)
                        scheduleSpeakingCooldown()
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        releaseAudioFocus()
                        currentDeferred?.complete(Unit)
                        scheduleSpeakingCooldown()
                    }

                    override fun onError(utteranceId: String?, errorCode: Int) {
                        Log.e(TAG, "TTS error: $errorCode")
                        releaseAudioFocus()
                        currentDeferred?.complete(Unit)
                        scheduleSpeakingCooldown()
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
     * Applies [locale] to the active TTS engine and returns whether it was
     * actually applied (false if the engine fell back to English).
     *
     * Sets both the legacy [TextToSpeech.setLanguage] locale AND an explicit
     * [android.speech.tts.Voice] match. Some TTS engines (e.g. custom
     * offline engines) only fully honor the modern Voice API — their
     * setLanguage() compatibility shim can report success without actually
     * switching the active synthesis voice, which is what caused this app to
     * speak translated text in the wrong language even though setLanguage()
     * looked like it succeeded.
     */
    private fun applyLocale(locale: Locale, allowRetry: Boolean = true): Boolean {
        val engine = tts ?: return false

        val result = engine.setLanguage(locale)
        val requestedLocaleSupported = result != TextToSpeech.LANG_MISSING_DATA &&
                result != TextToSpeech.LANG_NOT_SUPPORTED
        var effectiveLocale = locale

        if (!requestedLocaleSupported) {
            if (allowRetry) {
                Log.w(TAG, "Language $locale not supported (result=$result) -- retrying once shortly")
                scheduleLocaleRetry(locale)
            } else {
                Log.w(TAG, "Language $locale still not supported after retry (result=$result), falling back to English")
            }
            engine.language = Locale.US
            effectiveLocale = Locale.US
            val languageName = locale.getDisplayLanguage(Locale.ENGLISH)
            uiState.setError("TTS voice for $languageName not available, using English instead")
        }

        val matchedVoice = runCatching { engine.voices }.getOrNull()
            ?.filter { !it.isNetworkConnectionRequired && it.locale.language == effectiveLocale.language }
            ?.let { candidates ->
                candidates.firstOrNull { it.locale.country == effectiveLocale.country }
                    ?: candidates.firstOrNull()
            }
        if (matchedVoice != null) {
            engine.voice = matchedVoice
        }

        Log.i(TAG, "TTS active voice: ${engine.voice?.name} (${engine.voice?.locale}), requested=$locale")
        return requestedLocaleSupported
    }

    /**
     * Retries [locale] once after a short delay -- covers an external TTS
     * engine app that was itself killed by the system under memory pressure
     * and is still reloading its voice data right after an automatic
     * restart. Uses [engineScope] (not [queueScope], which gets cancelled
     * and replaced on every initialize() call) so this survives a
     * conversation-direction swap that happens to land in the same window.
     */
    private fun scheduleLocaleRetry(locale: Locale) {
        engineScope.launch {
            delay(LOCALE_RETRY_DELAY_MS)
            if (applyLocale(locale, allowRetry = false)) {
                Log.i(TAG, "Language $locale became available on retry")
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
        speakingCooldownJob?.cancel()
        _isSpeaking.value = false
        releaseAudioFocus()
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

    fun setVolume(vol: Float) {
        volume = vol.coerceIn(0.0f, 1.0f)
    }

    fun setDuckingEnabled(enabled: Boolean) {
        duckingEnabled = enabled
        if (!enabled) releaseAudioFocus()
    }

    private fun requestAudioFocus() {
        if (!duckingEnabled) return
        if (audioFocusRequest != null) return // already held

        val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .build()

        val result = audioManager.requestAudioFocus(focusRequest)
        if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            audioFocusRequest = focusRequest
            Log.d(TAG, "Audio focus acquired (ducking)")
        } else {
            Log.w(TAG, "Audio focus request denied: $result")
        }
    }

    private fun releaseAudioFocus() {
        audioFocusRequest?.let { request ->
            audioManager.abandonAudioFocusRequest(request)
            audioFocusRequest = null
            Log.d(TAG, "Audio focus released")
        }
    }

    /**
     * Keeps [isSpeaking] true for [SPEECH_TAIL_COOLDOWN_MS] after an utterance
     * ends, so callers muting the mic during TTS also cover the acoustic tail.
     * Cancelled by [onStart] if a new utterance begins before it fires.
     */
    private fun scheduleSpeakingCooldown() {
        speakingCooldownJob?.cancel()
        val scope = queueScope ?: return
        speakingCooldownJob = scope.launch {
            delay(SPEECH_TAIL_COOLDOWN_MS)
            _isSpeaking.value = false
        }
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
        val params = Bundle().apply {
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, volume)
        }
        engine.speak(text, TextToSpeech.QUEUE_ADD, params, id)

        withTimeoutOrNull(UTTERANCE_TIMEOUT_MS.milliseconds) {
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
