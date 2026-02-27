package com.example.instantvoicetranslate.translation

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.util.Log
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.LongBuffer
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Offline translator using NLLB-200-distilled-600M via ONNX Runtime.
 *
 * Implements TextTranslator so it can be used as a drop-in replacement
 * for FreeTranslator when offline mode is enabled.
 *
 * Inference pipeline:
 * 1. Tokenize input with SentencePiece (source language prefix + EOS)
 * 2. Run encoder → hidden states
 * 3. Autoregressive decoder loop with KV cache → output tokens
 * 4. Detokenize output
 */
@Singleton
class NllbTranslator @Inject constructor(
    private val nllbModelManager: NllbModelManager,
) : TextTranslator {

    companion object {
        private const val TAG = "NllbTranslator"
        private const val MAX_OUTPUT_TOKENS = 256
        private const val CACHE_SIZE = 200
    }

    private val _isAvailable = MutableStateFlow(false)
    override val isAvailable: StateFlow<Boolean> = _isAvailable.asStateFlow()

    private var ortEnv: OrtEnvironment? = null
    private var encoderSession: OrtSession? = null
    private var decoderSession: OrtSession? = null
    private var decoderWithPastSession: OrtSession? = null
    private var tokenizer: NllbTokenizer? = null

    private val cache = LruCache<String, String>(CACHE_SIZE)

    val isInitialized: Boolean get() = encoderSession != null && tokenizer?.isInitialized == true

    /**
     * Load ONNX sessions and tokenizer. Call before first translation.
     * This is resource-intensive (~1.3 GB RAM) and should only be called
     * when offline mode is enabled.
     */
    suspend fun initialize(): Unit = withContext(Dispatchers.IO) {
        if (isInitialized) return@withContext

        val modelDir = nllbModelManager.getModelDir()
        require(nllbModelManager.isModelReady()) { "NLLB model not downloaded" }

        try {
            Log.i(TAG, "Initializing ONNX sessions from ${modelDir.absolutePath}")

            val env = OrtEnvironment.getEnvironment()
            ortEnv = env

            val sessionOptions = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(4)
            }

            encoderSession = env.createSession(
                File(modelDir, "encoder_model_quantized.onnx").absolutePath,
                sessionOptions
            )
            Log.i(TAG, "Encoder session loaded")

            decoderSession = env.createSession(
                File(modelDir, "decoder_model_quantized.onnx").absolutePath,
                sessionOptions
            )
            Log.i(TAG, "Decoder session loaded")

            decoderWithPastSession = env.createSession(
                File(modelDir, "decoder_with_past_model_quantized.onnx").absolutePath,
                sessionOptions
            )
            Log.i(TAG, "Decoder-with-past session loaded")

            val tok = NllbTokenizer()
            tok.initialize(modelDir)
            tokenizer = tok

            _isAvailable.value = true
            Log.i(TAG, "NLLB translator initialized successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize NLLB translator", e)
            release()
            throw e
        }
    }

    /**
     * Release all ONNX sessions and tokenizer to free RAM (~1.3 GB).
     * Call when offline mode is disabled.
     */
    fun release() {
        encoderSession?.close()
        encoderSession = null
        decoderSession?.close()
        decoderSession = null
        decoderWithPastSession?.close()
        decoderWithPastSession = null
        tokenizer?.release()
        tokenizer = null
        _isAvailable.value = false
        Log.i(TAG, "NLLB translator released")
    }

    override suspend fun translate(
        text: String,
        from: String,
        to: String,
        previousTranslation: String?,
    ): Result<String> {
        if (text.isBlank()) return Result.success("")

        val cacheKey = "$from|$to|$text"
        cache.get(cacheKey)?.let { return Result.success(it) }

        return withContext(Dispatchers.IO) {
            try {
                val result = runInference(text, from, to)
                cache.put(cacheKey, result)
                _isAvailable.value = true
                Result.success(result)
            } catch (e: Exception) {
                Log.e(TAG, "Translation failed", e)
                _isAvailable.value = false
                Result.failure(e)
            }
        }
    }

    private fun runInference(text: String, from: String, to: String): String {
        val env = ortEnv ?: error("ORT environment not initialized")
        val encoder = encoderSession ?: error("Encoder session not initialized")
        val decoder = decoderSession ?: error("Decoder session not initialized")
        val decoderWP = decoderWithPastSession ?: error("Decoder-with-past session not initialized")
        val tok = tokenizer ?: error("Tokenizer not initialized")

        // 1. Tokenize input
        val inputIds = tok.encode(text, from)
        val seqLen = inputIds.size.toLong()

        // 2. Run encoder
        val inputIdsTensor = OnnxTensor.createTensor(
            env, LongBuffer.wrap(inputIds), longArrayOf(1, seqLen)
        )
        val attentionMask = LongArray(inputIds.size) { 1L }
        val attentionMaskTensor = OnnxTensor.createTensor(
            env, LongBuffer.wrap(attentionMask), longArrayOf(1, seqLen)
        )

        val encoderInputs = mapOf(
            "input_ids" to inputIdsTensor,
            "attention_mask" to attentionMaskTensor,
        )

        val encoderOutputs = encoder.run(encoderInputs)
        val encoderHiddenStates = encoderOutputs[0] as OnnxTensor

        // 3. Autoregressive decoder loop
        val targetLangTokenId = tok.getTargetLangTokenId(to)
        val outputTokens = mutableListOf<Long>()

        // First decoder step: input = [EOS], forced output = target_lang_token
        var decoderInputIds = longArrayOf(NllbTokenizer.EOS_TOKEN_ID)

        // Run first decoder step (no KV cache)
        val firstStepInputs = mutableMapOf<String, OnnxTensor>()
        firstStepInputs["input_ids"] = OnnxTensor.createTensor(
            env, LongBuffer.wrap(decoderInputIds), longArrayOf(1, 1)
        )
        firstStepInputs["encoder_hidden_states"] = encoderHiddenStates
        firstStepInputs["encoder_attention_mask"] = attentionMaskTensor

        val firstStepOutputs = decoder.run(firstStepInputs)

        // Force first token to be target language token
        outputTokens.add(targetLangTokenId)
        decoderInputIds = longArrayOf(targetLangTokenId)

        // Extract KV cache from first step outputs
        var kvCache = extractKvCache(firstStepOutputs, env)

        // Close first step tensors
        firstStepOutputs.close()
        firstStepInputs.values.forEach { tensor ->
            if (tensor !== encoderHiddenStates && tensor !== attentionMaskTensor) {
                tensor.close()
            }
        }

        // Continue decoding with decoder_with_past
        var decodedTokens = 0
        while (decodedTokens < MAX_OUTPUT_TOKENS) {
            decodedTokens++
            val stepInputs = mutableMapOf<String, OnnxTensor>()
            stepInputs["input_ids"] = OnnxTensor.createTensor(
                env, LongBuffer.wrap(decoderInputIds), longArrayOf(1, 1)
            )
            stepInputs["encoder_hidden_states"] = encoderHiddenStates
            stepInputs["encoder_attention_mask"] = attentionMaskTensor

            // Add KV cache tensors
            stepInputs.putAll(kvCache)

            val stepOutputs = decoderWP.run(stepInputs)

            // Get logits and find the best next token
            val logits = stepOutputs[0] as OnnxTensor
            val logitsData = logits.floatBuffer
            val vocabSize = logits.info.shape.last().toInt()

            // Greedy decoding: argmax over last dimension
            var maxVal = Float.NEGATIVE_INFINITY
            var maxIdx = 0L
            for (i in 0 until vocabSize) {
                val v = logitsData.get(i)
                if (v > maxVal) {
                    maxVal = v
                    maxIdx = i.toLong()
                }
            }

            // Close old KV cache tensors (they are not shared with other maps)
            kvCache.values.forEach { it.close() }

            if (maxIdx == NllbTokenizer.EOS_TOKEN_ID) {
                stepOutputs.close()
                stepInputs["input_ids"]?.close()
                break
            }

            outputTokens.add(maxIdx)
            decoderInputIds = longArrayOf(maxIdx)

            // Extract updated KV cache for next step
            kvCache = extractKvCache(stepOutputs, env)

            stepOutputs.close()
            stepInputs["input_ids"]?.close()
        }

        // Clean up remaining KV cache
        kvCache.values.forEach { it.close() }

        // 4. Clean up encoder tensors
        encoderOutputs.close()
        inputIdsTensor.close()
        attentionMaskTensor.close()

        // 5. Detokenize
        val result = tok.decode(outputTokens.toLongArray())
        Log.d(TAG, "Translated '$text' → '$result' ($from → $to, ${outputTokens.size} tokens)")
        return result
    }

    /**
     * Extract KV cache tensors from decoder outputs and prepare them as inputs
     * for the next decoder step.
     *
     * Output names use "present.X.Y.Z" prefix, input names use "past_key_values.X.Y.Z".
     */
    private fun extractKvCache(
        outputs: OrtSession.Result,
        env: OrtEnvironment,
    ): Map<String, OnnxTensor> {
        val cache = mutableMapOf<String, OnnxTensor>()

        // Get output info to find KV cache tensor names
        val outputNames = outputs.map { it.key }

        for (name in outputNames) {
            if (name.startsWith("present")) {
                val inputName = name.replace("present", "past_key_values")
                val tensor = outputs.get(name).orElse(null) as? OnnxTensor ?: continue

                // Clone tensor data for use in next step (outputs will be closed)
                val shape = tensor.info.shape
                val buffer = tensor.floatBuffer
                val data = FloatArray(buffer.remaining())
                buffer.get(data)

                val cloned = OnnxTensor.createTensor(
                    env,
                    java.nio.FloatBuffer.wrap(data),
                    shape
                )
                cache[inputName] = cloned
            }
        }

        return cache
    }
}
