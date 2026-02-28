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
import java.nio.FloatBuffer
import java.nio.LongBuffer
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Offline translator using NLLB-200-distilled-600M via ONNX Runtime.
 *
 * Implements TextTranslator so it can be used as a drop-in replacement
 * for FreeTranslator when offline mode is enabled.
 *
 * Uses **two separate decoder** ONNX models:
 * - `decoder_model_quantized.onnx` for the first decoding step (no KV cache).
 *   Computes cross-attention from encoder_hidden_states and outputs both
 *   decoder self-attention and encoder cross-attention present KV tensors.
 * - `decoder_with_past_model_quantized.onnx` for subsequent steps (with KV cache).
 *   Receives both decoder and encoder past_key_values as input, only recomputes
 *   decoder self-attention, and passes encoder cross-attention KV through unchanged.
 *
 * This two-model approach avoids the ONNX Runtime crash caused by the merged
 * decoder's If-node Reshape failure on Android:
 *   "Non-zero status code returned while running Reshape node
 *    '/model/decoder/layers.0/encoder_attn/Reshape_4'"
 *
 * The merged decoder (`decoder_model_merged_quantized.onnx`) combines both models
 * into a single file with a `use_cache_branch` boolean controlling an If node.
 * While this works in Transformers.js (WebAssembly/WebGPU ONNX Runtime), it fails
 * on Android's ONNX Runtime due to the If-node producing zero-length encoder
 * cross-attention KV tensors on the first step, which then cause Reshape errors
 * on subsequent steps. The separate model approach is also 3-4x faster per step
 * since ONNX Runtime's If-node has significant overhead (microsoft/onnxruntime#11367).
 *
 * Inference pipeline:
 * 1. Tokenize input with SentencePiece (source language prefix + EOS)
 * 2. Run encoder -> hidden states
 * 3. First decoder step (no cache) -> logits + initial KV cache
 * 4. Autoregressive decoder loop with KV cache -> output tokens
 * 5. Detokenize output
 */
@Singleton
class NllbTranslator @Inject constructor(
    private val nllbModelManager: NllbModelManager,
) : TextTranslator {

    companion object {
        private const val TAG = "NllbTranslator"
        private const val MAX_INPUT_TOKENS = 50
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

    val isInitialized: Boolean
        get() = encoderSession != null
                && decoderSession != null
                && decoderWithPastSession != null
                && tokenizer?.isInitialized == true

    /**
     * Load ONNX sessions and tokenizer. Call before first translation.
     * This is resource-intensive (~900 MB RAM) and should only be called
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
            Log.i(TAG, "Decoder session loaded (no-cache, first step)")

            decoderWithPastSession = env.createSession(
                File(modelDir, "decoder_with_past_model_quantized.onnx").absolutePath,
                sessionOptions
            )
            Log.i(TAG, "Decoder-with-past session loaded (with KV cache)")

            // Log model input/output names for debugging
            decoderSession?.let { session ->
                Log.d(TAG, "Decoder inputs: ${session.inputNames.sorted()}")
                Log.d(TAG, "Decoder outputs: ${session.outputNames.sorted()}")
            }
            decoderWithPastSession?.let { session ->
                Log.d(TAG, "Decoder-with-past inputs: ${session.inputNames.sorted()}")
                Log.d(TAG, "Decoder-with-past outputs: ${session.outputNames.sorted()}")
            }

            val tok = NllbTokenizer()
            tok.initialize(modelDir)
            tokenizer = tok

            _isAvailable.value = true
            Log.i(TAG, "NLLB translator initialized successfully")
        } catch (e: Throwable) {
            // Catch Throwable to handle UnsatisfiedLinkError when ONNX Runtime
            // native library version doesn't match the Java bindings.
            Log.e(TAG, "Failed to initialize NLLB translator", e)
            release()
            throw e
        }
    }

    /**
     * Release all ONNX sessions and tokenizer to free RAM.
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
        val decoderWithPast = decoderWithPastSession
            ?: error("Decoder-with-past session not initialized")
        val tok = tokenizer ?: error("Tokenizer not initialized")

        // 1. Tokenize input (truncate to model's max sequence length)
        var inputIds = tok.encode(text, from)
        if (inputIds.size > MAX_INPUT_TOKENS) {
            // Keep: [lang_token] + text_tokens[..truncated] + [EOS]
            inputIds = inputIds.copyOfRange(0, MAX_INPUT_TOKENS - 1) +
                    longArrayOf(NllbTokenizer.EOS_TOKEN_ID)
        }
        val seqLen = inputIds.size.toLong()

        // 2. Run encoder
        val inputIdsTensor = OnnxTensor.createTensor(
            env, LongBuffer.wrap(inputIds), longArrayOf(1, seqLen)
        )
        val attentionMask = LongArray(inputIds.size) { 1L }
        val attentionMaskTensor = OnnxTensor.createTensor(
            env, LongBuffer.wrap(attentionMask), longArrayOf(1, seqLen)
        )

        val encoderOutputs = encoder.run(
            mapOf(
                "input_ids" to inputIdsTensor,
                "attention_mask" to attentionMaskTensor,
            )
        )
        val encoderHiddenStates = encoderOutputs[0] as OnnxTensor

        // 3. First decoder step using decoder_model (no KV cache)
        //    This model computes cross-attention from encoder_hidden_states
        //    and outputs present.*.decoder.* and present.*.encoder.* KV tensors.
        val targetLangTokenId = tok.getTargetLangTokenId(to)
        val outputTokens = mutableListOf<Long>()

        var decoderInputIds = longArrayOf(NllbTokenizer.EOS_TOKEN_ID)

        val firstStepInputs = mutableMapOf<String, OnnxTensor>(
            "input_ids" to OnnxTensor.createTensor(
                env, LongBuffer.wrap(decoderInputIds), longArrayOf(1, 1)
            ),
            "encoder_hidden_states" to encoderHiddenStates,
            "encoder_attention_mask" to attentionMaskTensor,
        )

        val firstStepOutputs = decoder.run(firstStepInputs)

        // Force first token to be target language token
        outputTokens.add(targetLangTokenId)
        decoderInputIds = longArrayOf(targetLangTokenId)

        // Extract KV cache from first step.
        // The decoder_model outputs present.*.decoder.* and present.*.encoder.*
        // Both are needed as inputs for decoder_with_past_model.
        val decoderWithPastInputNames = decoderWithPast.inputNames
        val kvCache = extractKvCache(firstStepOutputs, env, decoderWithPastInputNames)

        Log.d(TAG, "First step: kvCache has ${kvCache.size} entries")
        // Log shapes for debugging
        for ((name, tensor) in kvCache) {
            Log.d(TAG, "  $name -> shape ${tensor.info.shape.toList()}")
        }

        // Separate encoder KV cache (constant across all steps) from decoder KV cache
        val encoderKvCache = mutableMapOf<String, OnnxTensor>()
        val decoderKvCache = mutableMapOf<String, OnnxTensor>()
        for ((name, tensor) in kvCache) {
            if (name.contains(".encoder.")) {
                encoderKvCache[name] = tensor
            } else {
                decoderKvCache[name] = tensor
            }
        }
        Log.d(TAG, "Encoder KV cache: ${encoderKvCache.size} tensors, " +
                "Decoder KV cache: ${decoderKvCache.size} tensors")

        // Close first step resources
        firstStepOutputs.close()
        firstStepInputs["input_ids"]?.close()

        // 4. Continue decoding with decoder_with_past_model (has KV cache)
        var currentDecoderKvCache = decoderKvCache
        var decodedTokens = 0
        while (decodedTokens < MAX_OUTPUT_TOKENS) {
            decodedTokens++
            val stepInputs = mutableMapOf<String, OnnxTensor>()
            stepInputs["input_ids"] = OnnxTensor.createTensor(
                env, LongBuffer.wrap(decoderInputIds), longArrayOf(1, 1)
            )

            // Pass encoder context (needed for cross-attention mask)
            if ("encoder_hidden_states" in decoderWithPastInputNames) {
                stepInputs["encoder_hidden_states"] = encoderHiddenStates
            }
            if ("encoder_attention_mask" in decoderWithPastInputNames) {
                stepInputs["encoder_attention_mask"] = attentionMaskTensor
            }

            // Add decoder self-attention KV cache (changes every step)
            stepInputs.putAll(currentDecoderKvCache)

            // Add encoder cross-attention KV cache (constant, computed once in step 1)
            stepInputs.putAll(encoderKvCache)

            val stepOutputs = decoderWithPast.run(stepInputs)

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

            // Close old decoder KV cache tensors (not encoder -- those are reused!)
            currentDecoderKvCache.values.forEach { it.close() }

            // Clean up step-local tensor
            stepInputs["input_ids"]?.close()

            if (maxIdx == NllbTokenizer.EOS_TOKEN_ID) {
                stepOutputs.close()
                break
            }

            outputTokens.add(maxIdx)
            decoderInputIds = longArrayOf(maxIdx)

            // Extract ONLY decoder self-attention KV from this step's output.
            // Encoder cross-attention KV is constant and was saved from step 1.
            currentDecoderKvCache = extractDecoderOnlyKvCache(
                stepOutputs, env, decoderWithPastInputNames
            )

            stepOutputs.close()
        }

        // 5. Clean up
        currentDecoderKvCache.values.forEach { it.close() }
        encoderKvCache.values.forEach { it.close() }
        encoderOutputs.close()
        inputIdsTensor.close()
        attentionMaskTensor.close()

        // 6. Detokenize
        val result = tok.decode(outputTokens.toLongArray())
        Log.d(TAG, "Translated '$text' -> '$result' ($from -> $to, ${outputTokens.size} tokens)")
        return result
    }

    /**
     * Extract ALL KV cache tensors (both decoder self-attention and encoder cross-attention)
     * from decoder outputs. Used after the first decoder step to get the initial KV cache.
     *
     * Output names use "present.X.Y.Z" prefix, input names use "past_key_values.X.Y.Z".
     */
    private fun extractKvCache(
        outputs: OrtSession.Result,
        env: OrtEnvironment,
        expectedInputNames: Set<String>,
    ): Map<String, OnnxTensor> {
        val cache = mutableMapOf<String, OnnxTensor>()

        for ((name, _) in outputs) {
            if (!name.startsWith("present")) continue

            val inputName = name.replace("present", "past_key_values")
            // Only extract KV tensors that the decoder-with-past model expects
            if (inputName !in expectedInputNames) continue

            val tensor = outputs.get(name).orElse(null) as? OnnxTensor ?: continue

            // Clone tensor data for use in next step (outputs will be closed)
            cache[inputName] = cloneTensor(tensor, env)
        }

        return cache
    }

    /**
     * Extract ONLY decoder self-attention KV cache from step outputs.
     * Encoder cross-attention KV is constant and reused from the first step,
     * so we skip any present.*.encoder.* tensors.
     */
    private fun extractDecoderOnlyKvCache(
        outputs: OrtSession.Result,
        env: OrtEnvironment,
        expectedInputNames: Set<String>,
    ): MutableMap<String, OnnxTensor> {
        val cache = mutableMapOf<String, OnnxTensor>()

        for ((name, _) in outputs) {
            if (!name.startsWith("present")) continue
            // Skip encoder cross-attention KV -- it's constant
            if (name.contains(".encoder.")) continue

            val inputName = name.replace("present", "past_key_values")
            if (inputName !in expectedInputNames) continue

            val tensor = outputs.get(name).orElse(null) as? OnnxTensor ?: continue
            cache[inputName] = cloneTensor(tensor, env)
        }

        return cache
    }

    /**
     * Clone an OnnxTensor by copying its float data into a new tensor.
     * Necessary because the source tensor's data becomes invalid after
     * the OrtSession.Result is closed.
     */
    private fun cloneTensor(tensor: OnnxTensor, env: OrtEnvironment): OnnxTensor {
        val shape = tensor.info.shape
        val buffer = tensor.floatBuffer
        val data = FloatArray(buffer.remaining())
        buffer.get(data)
        return OnnxTensor.createTensor(env, FloatBuffer.wrap(data), shape)
    }
}
