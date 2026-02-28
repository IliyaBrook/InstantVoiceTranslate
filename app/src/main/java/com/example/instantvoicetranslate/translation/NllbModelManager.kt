package com.example.instantvoicetranslate.translation

import android.content.Context
import android.util.Log
import com.example.instantvoicetranslate.data.ModelStatus
import com.example.instantvoicetranslate.data.downloadToFile
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages download, storage and status of the NLLB-200-distilled-600M ONNX model
 * used for offline translation.
 */
@Singleton
class NllbModelManager @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "NllbModelManager"
        private const val MODEL_DIR_NAME = "nllb-200-distilled-600M"

        private const val ONNX_BASE_URL =
            "https://huggingface.co/Xenova/nllb-200-distilled-600M/resolve/main/onnx"
        private const val TOKENIZER_BASE_URL =
            "https://huggingface.co/Xenova/nllb-200-distilled-600M/resolve/main"

        data class ModelFile(val name: String, val approxSize: Long)

        val MODEL_FILES: List<ModelFile> = listOf(
            ModelFile("encoder_model_quantized.onnx", 419_000_000L),
            // Two separate decoder models for reliable inference on Android:
            // 1) decoder_model: first step (no KV cache), computes cross-attention
            //    from encoder_hidden_states and outputs both decoder + encoder KV.
            // 2) decoder_with_past_model: subsequent steps (with KV cache), receives
            //    both decoder and encoder past_key_values as input.
            //
            // The merged decoder (decoder_model_merged_quantized.onnx) crashes on
            // Android ONNX Runtime due to the If-node's Reshape failure with
            // zero-length encoder cross-attention KV tensors:
            //   "Non-zero status code while running Reshape node
            //    '/model/decoder/layers.0/encoder_attn/Reshape_4'"
            // The two-model approach also avoids the 3-4x overhead of ONNX If nodes.
            ModelFile("decoder_model_quantized.onnx", 471_000_000L),
            ModelFile("decoder_with_past_model_quantized.onnx", 445_000_000L),
        )

        val TOKENIZER_FILES: List<ModelFile> = listOf(
            ModelFile("sentencepiece.bpe.model", 5_000_000L),
            ModelFile("tokenizer_config.json", 30_000L),
        )

        val ALL_FILES: List<ModelFile> = MODEL_FILES + TOKENIZER_FILES
    }

    private val _status = MutableStateFlow<ModelStatus>(ModelStatus.NotDownloaded)
    val status: StateFlow<ModelStatus> = _status.asStateFlow()

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    fun getModelDir(): File = File(context.filesDir, MODEL_DIR_NAME)

    fun isModelReady(): Boolean {
        val dir = getModelDir()
        if (!dir.exists()) return false
        return ALL_FILES.all { File(dir, it.name).exists() }
    }

    /**
     * Downloads the NLLB model if not already present.
     * Updates [status] with download progress.
     */
    suspend fun ensureModelAvailable() {
        if (isModelReady()) {
            _status.value = ModelStatus.Ready
            return
        }
        downloadModel()
    }

    /**
     * Deletes all downloaded NLLB model files to free storage.
     */
    suspend fun deleteModel(): Unit = withContext(Dispatchers.IO) {
        val dir = getModelDir()
        if (dir.exists()) {
            dir.deleteRecursively()
            Log.i(TAG, "NLLB model deleted from ${dir.absolutePath}")
        }
        _status.value = ModelStatus.NotDownloaded
    }

    fun updateStatus(status: ModelStatus) {
        _status.value = status
    }

    /**
     * Remove obsolete model files from previous approaches:
     * - decoder_model_merged_quantized.onnx: merged decoder that crashed on Android
     *   ONNX Runtime due to If-node Reshape errors with encoder cross-attention KV.
     *   Replaced by separate decoder_model + decoder_with_past_model pair.
     */
    private fun cleanupObsoleteFiles(dir: File) {
        val obsoleteFiles = listOf(
            "decoder_model_merged_quantized.onnx",
        )
        for (fileName in obsoleteFiles) {
            val file = File(dir, fileName)
            if (file.exists()) {
                val sizeBytes = file.length()
                val deleted = file.delete()
                Log.i(TAG, "Cleaned up obsolete $fileName: deleted=$deleted ($sizeBytes bytes freed)")
            }
        }
    }

    private fun resolveUrl(file: ModelFile): String =
        if (file in MODEL_FILES) "$ONNX_BASE_URL/${file.name}" else "$TOKENIZER_BASE_URL/${file.name}"

    /**
     * Downloads a single file if it doesn't already exist.
     * Returns `true` if the file was already present (skipped), `false` if downloaded.
     */
    private fun downloadFileIfNeeded(
        file: ModelFile,
        dir: File,
        downloadedSoFar: Long,
        totalSize: Long,
    ): Boolean {
        val target = File(dir, file.name)
        if (target.exists() && target.length() > 0) return true

        val url = resolveUrl(file)
        Log.i(TAG, "Downloading ${file.name} from $url")

        _status.value = ModelStatus.Downloading(
            progress = downloadedSoFar.toFloat() / totalSize,
            currentFile = file.name
        )

        downloadToFile(client, url, target) { fileDownloaded ->
            val totalProgress = (downloadedSoFar + fileDownloaded).toFloat() / totalSize
            _status.value = ModelStatus.Downloading(
                progress = totalProgress.coerceIn(0f, 1f),
                currentFile = file.name
            )
        }

        Log.i(TAG, "Downloaded ${file.name} (${target.length()} bytes)")
        return false
    }

    private suspend fun downloadModel() = withContext(Dispatchers.IO) {
        try {
            val dir = getModelDir()
            dir.mkdirs()

            cleanupObsoleteFiles(dir)

            val totalSize = ALL_FILES.sumOf { it.approxSize }
            var downloadedSoFar = 0L

            for (file in ALL_FILES) {
                downloadFileIfNeeded(file, dir, downloadedSoFar, totalSize)
                downloadedSoFar += file.approxSize
            }

            _status.value = ModelStatus.Ready
            Log.i(TAG, "All NLLB model files ready in ${dir.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "NLLB model download failed", e)
            _status.value = ModelStatus.Error(e.message ?: "Unknown error")
        }
    }
}
