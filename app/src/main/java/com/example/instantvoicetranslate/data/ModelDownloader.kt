package com.example.instantvoicetranslate.data

import android.content.Context
import android.util.Log
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

sealed class ModelStatus {
    data object NotDownloaded : ModelStatus()
    data class Downloading(val progress: Float, val currentFile: String) : ModelStatus()
    data object Ready : ModelStatus()
    data class Error(val message: String) : ModelStatus()
}

@Singleton
class ModelDownloader @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "ModelDownloader"
        private const val MODEL_DIR_NAME = "sherpa-onnx-streaming-zipformer-en-2023-06-21"
        private const val OLD_MODEL_DIR_NAME = "sherpa-onnx-zipformer-en-20M"
        private const val BASE_URL =
            "https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-en-2023-06-21/resolve/main"

        val MODEL_FILES = listOf(
            ModelFile("encoder-epoch-99-avg-1.int8.onnx", 188_000_000L),
            ModelFile("decoder-epoch-99-avg-1.int8.onnx", 539_000L),
            ModelFile("joiner-epoch-99-avg-1.int8.onnx", 259_000L),
            ModelFile("tokens.txt", 5_050L),
        )
    }

    data class ModelFile(val name: String, val approxSize: Long)

    private val _status = MutableStateFlow<ModelStatus>(ModelStatus.NotDownloaded)
    val status: StateFlow<ModelStatus> = _status.asStateFlow()

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    val modelDir: File
        get() = File(context.filesDir, MODEL_DIR_NAME)

    fun isModelReady(): Boolean {
        val dir = modelDir
        if (!dir.exists()) return false
        return MODEL_FILES.all { File(dir, it.name).exists() }
    }

    suspend fun ensureModelAvailable() {
        cleanupOldModel()
        if (isModelReady()) {
            _status.value = ModelStatus.Ready
            return
        }
        downloadModel()
    }

    private fun cleanupOldModel() {
        val oldDir = File(context.filesDir, OLD_MODEL_DIR_NAME)
        if (oldDir.exists()) {
            Log.i(TAG, "Removing old model: $OLD_MODEL_DIR_NAME")
            oldDir.deleteRecursively()
        }
    }

    private suspend fun downloadModel() = withContext(Dispatchers.IO) {
        try {
            val dir = modelDir
            dir.mkdirs()

            val totalSize = MODEL_FILES.sumOf { it.approxSize }
            var downloadedSoFar = 0L

            for (file in MODEL_FILES) {
                val target = File(dir, file.name)
                if (target.exists() && target.length() > 0) {
                    downloadedSoFar += file.approxSize
                    continue
                }

                _status.value = ModelStatus.Downloading(
                    progress = downloadedSoFar.toFloat() / totalSize,
                    currentFile = file.name
                )

                val url = "$BASE_URL/${file.name}"
                Log.i(TAG, "Downloading ${file.name} from $url")

                val request = Request.Builder().url(url).build()
                val response = client.newCall(request).execute()

                if (!response.isSuccessful) {
                    throw Exception("Failed to download ${file.name}: HTTP ${response.code}")
                }

                val body = response.body ?: throw Exception("Empty response for ${file.name}")
                val tempFile = File(dir, "${file.name}.tmp")

                FileOutputStream(tempFile).use { output ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(8192)
                        var bytesRead: Int
                        var fileDownloaded = 0L
                        val contentLength = body.contentLength().takeIf { it > 0 } ?: file.approxSize

                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            fileDownloaded += bytesRead
                            val totalProgress = (downloadedSoFar + fileDownloaded).toFloat() / totalSize
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
            Log.i(TAG, "All model files ready in ${dir.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Model download failed", e)
            _status.value = ModelStatus.Error(e.message ?: "Unknown error")
        }
    }

    fun deleteModel() {
        modelDir.deleteRecursively()
        _status.value = ModelStatus.NotDownloaded
    }
}
