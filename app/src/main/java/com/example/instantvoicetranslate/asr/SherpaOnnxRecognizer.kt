package com.example.instantvoicetranslate.asr

import android.util.Log
import com.example.instantvoicetranslate.data.ModelDownloader
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlinePunctuation
import com.k2fsa.sherpa.onnx.OnlinePunctuationConfig
import com.k2fsa.sherpa.onnx.OnlinePunctuationModelConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SherpaOnnxRecognizer @Inject constructor() : SpeechRecognizer {

    companion object {
        private const val TAG = "SherpaOnnxRecognizer"
        private const val SAMPLE_RATE = 16000

        /**
         * RMS energy threshold for silence detection.
         *
         * When RMS of an audio chunk falls below this value, it is considered
         * silence. The state machine stops feeding audio to the recognizer
         * and resets the encoder stream to prevent hallucinations.
         *
         * This works for both sources:
         * - System audio: noise gate in AudioCaptureManager zeros out silence
         *   → RMS = 0.0, well below threshold
         * - Microphone: ambient noise in a quiet room has RMS ~0.002-0.01,
         *   quiet speech has RMS ~0.01+. Threshold is set low enough to
         *   avoid cutting off real audio.
         */
        private const val ENERGY_SILENCE_THRESHOLD = 0.002f

        /**
         * Number of consecutive silent chunks before triggering silence state.
         * At ~100ms per chunk, 3 chunks = ~300ms of silence needed.
         * This prevents brief dips (e.g. between words) from triggering
         * premature stream resets.
         */
        private const val SILENCE_CHUNKS_REQUIRED = 3

        /** Log energy diagnostics every N chunks (~N × 100ms). */
        private const val DIAG_LOG_INTERVAL = 30

        /** Emit a segment early when partial text exceeds this word count. */
        private const val MAX_WORDS_BEFORE_SPLIT = 18

        /** Minimum word count before we consider mid-sentence splitting by punctuation. */
        private const val MIN_WORDS_FOR_PUNCT_SPLIT = 8

        /** Regex matching sentence-ending punctuation (possibly followed by quotes). */
        private val SENTENCE_END_REGEX = Regex("""[.!?]["')\]]*\s""")

        /** Punctuation characters suitable for mid-sentence splitting (comma, semicolon, colon, dash). */
        private val MID_PUNCT_CHARS = charArrayOf(',', ';', ':', '\u2014', '\u2013')
    }

    private val _partialText = MutableStateFlow("")
    override val partialText: StateFlow<String> = _partialText.asStateFlow()

    private val _recognizedSegments = MutableSharedFlow<String>(extraBufferCapacity = 16)
    override val recognizedSegments: SharedFlow<String> = _recognizedSegments.asSharedFlow()

    private val _isReady = MutableStateFlow(false)
    override val isReady: StateFlow<Boolean> = _isReady.asStateFlow()

    private val _currentLanguage = MutableStateFlow("")
    override val currentLanguage: StateFlow<String> = _currentLanguage.asStateFlow()

    private var recognizer: OnlineRecognizer? = null
    private var punctuation: OnlinePunctuation? = null
    private var recognitionScope: CoroutineScope? = null
    private var recognitionJob: Job? = null

    override suspend fun initialize(modelDir: String, language: String): Unit = withContext(Dispatchers.IO) {
        try {
            val langConfig = ModelDownloader.getLanguageConfig(language)
                ?: throw IllegalArgumentException("No ASR model config for language: $language")

            Log.i(TAG, "Initializing for language '$language' with model dir: $modelDir")

            val config = OnlineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
                modelConfig = OnlineModelConfig(
                    transducer = OnlineTransducerModelConfig(
                        encoder = "$modelDir/${langConfig.encoderFile}",
                        decoder = "$modelDir/${langConfig.decoderFile}",
                        joiner = "$modelDir/${langConfig.joinerFile}",
                    ),
                    tokens = "$modelDir/tokens.txt",
                    modelType = langConfig.modelType,
                    numThreads = 4,
                    debug = false,
                    provider = "cpu",
                ),
                // Tuned for low-latency real-time translation:
                // - Rule1: pure silence endpoint (no speech detected) — 1.5s
                // - Rule2: speech followed by pause — 0.6s (was 1.2s)
                // - Rule3: force endpoint after 15s continuous speech
                endpointConfig = EndpointConfig(
                    rule1 = EndpointRule(
                        mustContainNonSilence = false,
                        minTrailingSilence = 1.5f,
                        minUtteranceLength = 0.0f
                    ),
                    rule2 = EndpointRule(
                        mustContainNonSilence = true,
                        minTrailingSilence = 0.6f,
                        minUtteranceLength = 0.0f
                    ),
                    rule3 = EndpointRule(
                        mustContainNonSilence = false,
                        minTrailingSilence = 0.0f,
                        minUtteranceLength = 15.0f
                    ),
                ),
                enableEndpoint = true,
                decodingMethod = "greedy_search",
            )

            recognizer = OnlineRecognizer(config = config)
            _currentLanguage.value = language
            _isReady.value = true
            Log.i(TAG, "Recognizer initialized successfully for language: $language")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize recognizer for language: $language", e)
            _isReady.value = false
            throw e
        }
    }

    override fun initializePunctuation(modelDir: String) {
        punctuation?.release()
        val onnxFile = java.io.File("$modelDir/model.int8.onnx")
        val bpeFile = java.io.File("$modelDir/bpe.vocab")
        Log.i(TAG, "PUNCT: init from $modelDir | model.int8.onnx=${onnxFile.exists()} (${onnxFile.length()}) | bpe.vocab=${bpeFile.exists()} (${bpeFile.length()})")
        if (!onnxFile.exists() || !bpeFile.exists()) {
            Log.e(TAG, "PUNCT: Required files missing, skipping init")
            punctuation = null
            return
        }
        try {
            val config = OnlinePunctuationConfig(
                model = OnlinePunctuationModelConfig(
                    cnnBilstm = onnxFile.absolutePath,
                    bpeVocab = bpeFile.absolutePath,
                    numThreads = 2,
                )
            )
            punctuation = OnlinePunctuation(config = config)
            Log.i(TAG, "PUNCT: Initialized OK")
        } catch (e: Exception) {
            Log.e(TAG, "PUNCT: Init FAILED", e)
            punctuation = null
        }
    }

    private fun applyPunctuation(text: String): String {
        return punctuation?.addPunctuation(text) ?: text
    }

    /**
     * Recognition loop with energy-based silence detection state machine.
     *
     * Uses RMS energy of each audio chunk to detect speech/silence transitions.
     * This works for both audio sources:
     * - System audio: noise gate in AudioCaptureManager zeros out silence,
     *   so RMS = 0.0 during pauses → clearly detected as silence.
     * - Microphone: ambient noise is typically above the threshold,
     *   so the model receives all audio (same as original behavior).
     *
     * State machine:
     * - SPEECH: feed audio to recognizer, decode, emit partial text
     * - SPEECH→SILENCE transition (after N consecutive silent chunks):
     *   flush pending text, release and recreate stream (clears encoder state)
     * - SILENCE: skip feeding to recognizer (prevents hallucinations)
     * - SILENCE→SPEECH transition: stream is already clean from prior reset
     */
    override fun startRecognition(audioFlow: Flow<FloatArray>) {
        val rec = recognizer ?: run {
            Log.e(TAG, "Recognizer not initialized")
            return
        }

        stopRecognition()

        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        recognitionScope = scope

        recognitionJob = scope.launch {
            var stream = rec.createStream()
            var emittedText = ""
            var isSpeaking = false
            var consecutiveSilentChunks = 0
            var chunkCount = 0L

            Log.i(TAG, "Recognition started (energy threshold=$ENERGY_SILENCE_THRESHOLD, " +
                    "silenceChunks=$SILENCE_CHUNKS_REQUIRED)")

            try {
                audioFlow.collect { samples ->
                    chunkCount++

                    // Calculate RMS energy of this chunk
                    val rms = calculateRms(samples)
                    val chunkHasSpeech = rms > ENERGY_SILENCE_THRESHOLD

                    // Diagnostic logging
                    if (chunkCount % DIAG_LOG_INTERVAL == 0L) {
                        Log.d(TAG, "REC #$chunkCount: rms=%.6f speech=$chunkHasSpeech " +
                                "speaking=$isSpeaking silentRun=$consecutiveSilentChunks"
                                    .format(rms))
                    }

                    if (chunkHasSpeech) {
                        // === SPEECH ===
                        consecutiveSilentChunks = 0

                        if (!isSpeaking) {
                            isSpeaking = true
                            Log.d(TAG, "Energy: silence → speech (rms=%.6f)".format(rms))
                        }

                        processAudioChunk(rec, stream, samples, emittedText).let { (newEmitted, newStream) ->
                            emittedText = newEmitted
                            if (newStream !== stream) stream = newStream
                        }

                    } else {
                        // === SILENCE (this chunk) ===
                        consecutiveSilentChunks++

                        if (isSpeaking) {
                            if (consecutiveSilentChunks < SILENCE_CHUNKS_REQUIRED) {
                                // Brief silence — still feed to recognizer
                                // (could be a natural pause between words)
                                processAudioChunk(rec, stream, samples, emittedText).let { (newEmitted, newStream) ->
                                    emittedText = newEmitted
                                    if (newStream !== stream) stream = newStream
                                }
                            } else {
                                // Sustained silence → flush and reset
                                isSpeaking = false
                                Log.d(TAG, "Energy: speech → silence after " +
                                        "$consecutiveSilentChunks silent chunks, flushing")

                                // Feed one trailing silent chunk for finalization
                                feedAndDecode(rec, stream, samples)

                                // Emit any accumulated text
                                val currentText = rec.getResult(stream).text.trim()
                                val fullText = combineText(emittedText, currentText)
                                if (fullText.isNotBlank()) {
                                    val punctuated = applyPunctuation(fullText)
                                    Log.i(TAG, "Flush segment: $punctuated")
                                    _recognizedSegments.emit(punctuated)
                                }

                                // Recreate stream to fully clear encoder hidden state
                                stream.release()
                                stream = rec.createStream()
                                emittedText = ""
                                _partialText.value = ""
                            }
                        }
                        // During sustained silence with no prior speech: do nothing
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Recognition error", e)
            } finally {
                stream.release()
            }
        }
    }

    /** Calculate RMS (root-mean-square) amplitude of audio samples. */
    private fun calculateRms(samples: FloatArray): Float {
        var sumSquares = 0.0
        for (sample in samples) {
            sumSquares += sample * sample
        }
        return kotlin.math.sqrt(sumSquares / samples.size).toFloat()
    }

    /**
     * Feed audio to the recognizer stream, decode, and handle segmentation.
     * Returns updated (emittedText, stream) — stream may be replaced on early segment reset.
     */
    private suspend fun processAudioChunk(
        rec: OnlineRecognizer,
        stream: com.k2fsa.sherpa.onnx.OnlineStream,
        samples: FloatArray,
        emittedText: String,
    ): Pair<String, com.k2fsa.sherpa.onnx.OnlineStream> {
        feedAndDecode(rec, stream, samples)

        val currentText = rec.getResult(stream).text.trim()
        val fullText = combineText(emittedText, currentText)

        if (currentText.isNotBlank()) {
            _partialText.value = fullText
        }

        // --- Smart segmentation: try to emit early before Sherpa endpoint ---
        val earlySegment = tryExtractEarlySegment(fullText)
        if (earlySegment != null) {
            val punctuated = applyPunctuation(earlySegment.emitted)
            Log.i(TAG, "Early segment emitted: $punctuated")
            _recognizedSegments.emit(punctuated)
            _partialText.value = earlySegment.remainder
            rec.reset(stream)
            return Pair(earlySegment.remainder, stream)
        } else if (rec.isEndpoint(stream)) {
            if (fullText.isNotBlank()) {
                val punctuated = applyPunctuation(fullText)
                Log.i(TAG, "Endpoint detected, segment: $punctuated")
                _recognizedSegments.emit(punctuated)
                _partialText.value = ""
            }
            rec.reset(stream)
            return Pair("", stream)
        }

        return Pair(emittedText, stream)
    }

    override fun stopRecognition() {
        recognitionJob?.cancel()
        recognitionJob = null
        recognitionScope?.cancel()
        recognitionScope = null
        _partialText.value = ""
    }

    override fun release() {
        stopRecognition()
        recognizer?.release()
        recognizer = null
        punctuation?.release()
        punctuation = null
        _isReady.value = false
        _currentLanguage.value = ""
    }

    /** Feed audio samples into the stream and run all available decode steps. */
    private fun feedAndDecode(
        rec: OnlineRecognizer,
        stream: com.k2fsa.sherpa.onnx.OnlineStream,
        samples: FloatArray,
    ) {
        stream.acceptWaveform(samples, sampleRate = SAMPLE_RATE)
        while (rec.isReady(stream)) {
            rec.decode(stream)
        }
    }

    private data class SegmentSplit(val emitted: String, val remainder: String)

    private fun combineText(accumulated: String, current: String): String {
        return if (accumulated.isBlank()) current else "$accumulated $current"
    }

    /**
     * Attempts to extract a segment from [fullText] before Sherpa endpoint fires.
     *
     * Strategies (in priority order):
     * 1. Sentence boundary: if text contains a completed sentence (ends with .!?),
     *    emit everything up to and including that sentence.
     * 2. Long text with mid-punctuation: if word count exceeds [MAX_WORDS_BEFORE_SPLIT],
     *    split at the last comma/semicolon/colon that is past [MIN_WORDS_FOR_PUNCT_SPLIT] words.
     *
     * Returns null if no early split is warranted.
     */
    private fun tryExtractEarlySegment(fullText: String): SegmentSplit? {
        if (fullText.isBlank()) return null

        val words = fullText.split(' ').filter { it.isNotBlank() }
        if (words.size < MIN_WORDS_FOR_PUNCT_SPLIT) return null

        // Strategy 1: sentence boundary — look for sentence-ending punctuation followed by space
        val sentenceMatch = SENTENCE_END_REGEX.find(fullText)
        if (sentenceMatch != null) {
            val splitIndex = sentenceMatch.range.first + 1
            val emitted = fullText.substring(0, splitIndex).trim()
            val remainder = fullText.substring(splitIndex).trim()
            if (emitted.isNotBlank()) {
                return SegmentSplit(emitted, remainder)
            }
        }

        // Also check if the entire text ends with sentence punctuation (no trailing space needed)
        val trimmed = fullText.trimEnd()
        if (trimmed.isNotEmpty() && trimmed.last() in ".!?" && words.size >= MIN_WORDS_FOR_PUNCT_SPLIT) {
            return SegmentSplit(trimmed, "")
        }

        // Strategy 2: text too long — split at last mid-sentence punctuation
        if (words.size >= MAX_WORDS_BEFORE_SPLIT) {
            val lastPunctIndex = fullText.indexOfLast { it in MID_PUNCT_CHARS }
            if (lastPunctIndex > 0) {
                val candidateEmitted = fullText.substring(0, lastPunctIndex + 1).trim()
                val candidateWords = candidateEmitted.split(' ').filter { it.isNotBlank() }
                if (candidateWords.size >= MIN_WORDS_FOR_PUNCT_SPLIT) {
                    val remainder = fullText.substring(lastPunctIndex + 1).trim()
                    return SegmentSplit(candidateEmitted, remainder)
                }
            }
        }

        return null
    }
}
