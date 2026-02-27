package com.example.instantvoicetranslate.translation

import android.content.Context
import android.util.Log
import com.example.instantvoicetranslate.data.ModelStatus
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
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

    private suspend fun downloadModel() = withContext(Dispatchers.IO) {
        try {
            val dir = getModelDir()
            dir.mkdirs()

            val totalSize = ALL_FILES.sumOf { it.approxSize }
            var downloadedSoFar = 0L

            for (file in ALL_FILES) {
                val target = File(dir, file.name)
                if (target.exists() && target.length() > 0) {
                    downloadedSoFar += file.approxSize
                    continue
                }

                _status.value = ModelStatus.Downloading(
                    progress = downloadedSoFar.toFloat() / totalSize,
                    currentFile = file.name
                )

                val url = if (file in MODEL_FILES) {
                    "$ONNX_BASE_URL/${file.name}"
                } else {
                    "$TOKENIZER_BASE_URL/${file.name}"
                }

                Log.i(TAG, "Downloading ${file.name} from $url")

                val request = Request.Builder().url(url).build()
                val response = client.newCall(request).execute()

                if (!response.isSuccessful) {
                    throw Exception("Failed to download ${file.name}: HTTP ${response.code}")
                }

                val body = response.body
                val tempFile = File(dir, "${file.name}.tmp")

                FileOutputStream(tempFile).use { output ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(8192)
                        var bytesRead: Int
                        var fileDownloaded = 0L
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            fileDownloaded += bytesRead
                            val totalProgress =
                                (downloadedSoFar + fileDownloaded).toFloat() / totalSize
                            _status.value = ModelStatus.Downloading(
                                progress = totalProgress.coerceIn(0f, 1f),
                                currentFile = file.name
                            )
                        }
                    }
                }

                tempFile.renameTo(target)
                downloadedSoFar += file.approxSize
                Log.i(TAG, "Downloaded ${file.name} (${target.length()} bytes)")
            }

            _status.value = ModelStatus.Ready
            Log.i(TAG, "All NLLB model files ready in ${dir.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "NLLB model download failed", e)
            _status.value = ModelStatus.Error(e.message ?: "Unknown error")
        }
    }
}
