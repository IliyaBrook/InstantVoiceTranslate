package com.example.instantvoicetranslate.asr

import android.util.Log
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
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
    }

    private val _partialText = MutableStateFlow("")
    override val partialText: StateFlow<String> = _partialText.asStateFlow()

    private val _recognizedSegments = MutableSharedFlow<String>(extraBufferCapacity = 16)
    override val recognizedSegments: SharedFlow<String> = _recognizedSegments.asSharedFlow()

    private val _isReady = MutableStateFlow(false)
    override val isReady: StateFlow<Boolean> = _isReady.asStateFlow()

    private var recognizer: OnlineRecognizer? = null
    private var recognitionScope: CoroutineScope? = null
    private var recognitionJob: Job? = null

    override suspend fun initialize(modelDir: String): Unit = withContext(Dispatchers.IO) {
        try {
            Log.i(TAG, "Initializing with model dir: $modelDir")

            val config = OnlineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
                modelConfig = OnlineModelConfig(
                    transducer = OnlineTransducerModelConfig(
                        encoder = "$modelDir/encoder-epoch-99-avg-1.int8.onnx",
                        decoder = "$modelDir/decoder-epoch-99-avg-1.int8.onnx",
                        joiner = "$modelDir/joiner-epoch-99-avg-1.int8.onnx",
                    ),
                    tokens = "$modelDir/tokens.txt",
                    modelType = "zipformer",
                    numThreads = 4,
                    debug = false,
                    provider = "cpu",
                ),
                endpointConfig = EndpointConfig(
                    rule1 = EndpointRule(
                        mustContainNonSilence = false,
                        minTrailingSilence = 2.4f,
                        minUtteranceLength = 0.0f
                    ),
                    rule2 = EndpointRule(
                        mustContainNonSilence = true,
                        minTrailingSilence = 1.2f,
                        minUtteranceLength = 0.0f
                    ),
                    rule3 = EndpointRule(
                        mustContainNonSilence = false,
                        minTrailingSilence = 0.0f,
                        minUtteranceLength = 20.0f
                    ),
                ),
                enableEndpoint = true,
                decodingMethod = "greedy_search",
            )

            recognizer = OnlineRecognizer(config = config)
            _isReady.value = true
            Log.i(TAG, "Recognizer initialized successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize recognizer", e)
            _isReady.value = false
            throw e
        }
    }

    override fun startRecognition(audioFlow: Flow<FloatArray>) {
        val rec = recognizer ?: run {
            Log.e(TAG, "Recognizer not initialized")
            return
        }

        stopRecognition()

        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        recognitionScope = scope

        recognitionJob = scope.launch {
            val stream = rec.createStream()
            var accumulatedText = ""

            try {
                audioFlow.collect { samples ->
                    // Feed audio to stream
                    stream.acceptWaveform(samples, sampleRate = SAMPLE_RATE)

                    // Decode all available frames
                    while (rec.isReady(stream)) {
                        rec.decode(stream)
                    }

                    // Get current result
                    val result = rec.getResult(stream)
                    val currentText = result.text.trim()

                    // Update partial text (accumulated + current partial)
                    if (currentText.isNotBlank()) {
                        _partialText.value = if (accumulatedText.isBlank()) {
                            currentText
                        } else {
                            "$accumulatedText $currentText"
                        }
                    }

                    // Check for endpoint
                    if (rec.isEndpoint(stream)) {
                        if (currentText.isNotBlank()) {
                            val finalSegment = if (accumulatedText.isBlank()) {
                                currentText
                            } else {
                                "$accumulatedText $currentText"
                            }
                            Log.i(TAG, "Endpoint detected, segment: $finalSegment")
                            _recognizedSegments.emit(finalSegment)
                            accumulatedText = ""
                            _partialText.value = ""
                        }
                        rec.reset(stream)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Recognition error", e)
            } finally {
                stream.release()
            }
        }
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
        _isReady.value = false
    }
}
