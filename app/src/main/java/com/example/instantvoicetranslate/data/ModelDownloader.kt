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
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

sealed class ModelStatus {
    data object NotDownloaded : ModelStatus()
    data class Downloading(val progress: Float, val currentFile: String) : ModelStatus()
    data class Initializing(val step: String = "") : ModelStatus()
    data object Ready : ModelStatus()
    data class Error(val message: String) : ModelStatus()
}

data class LanguageModelConfig(
    val dirName: String,
    val baseUrl: String,
    val files: List<ModelDownloader.ModelFile>,
    val modelType: String,
    val encoderFile: String,
    val decoderFile: String,
    val joinerFile: String,
)

@Singleton
class ModelDownloader @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "ModelDownloader"
        private const val OLD_MODEL_DIR_NAME = "sherpa-onnx-zipformer-en-20M"

        val LANGUAGE_MODELS: Map<String, LanguageModelConfig> = mapOf(
            "en" to LanguageModelConfig(
                dirName = "sherpa-onnx-streaming-zipformer-en-2023-06-21",
                baseUrl = "https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-en-2023-06-21/resolve/main",
                files = listOf(
                    ModelFile("encoder-epoch-99-avg-1.int8.onnx", 188_000_000L),
                    ModelFile("decoder-epoch-99-avg-1.int8.onnx", 539_000L),
                    ModelFile("joiner-epoch-99-avg-1.int8.onnx", 259_000L),
                    ModelFile("tokens.txt", 5_050L),
                ),
                modelType = "zipformer",
                encoderFile = "encoder-epoch-99-avg-1.int8.onnx",
                decoderFile = "decoder-epoch-99-avg-1.int8.onnx",
                joinerFile = "joiner-epoch-99-avg-1.int8.onnx",
            ),
            "ru" to LanguageModelConfig(
                dirName = "sherpa-onnx-streaming-zipformer-small-ru-vosk-int8-2025-08-16",
                baseUrl = "https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-small-ru-vosk-int8-2025-08-16/resolve/main",
                files = listOf(
                    ModelFile("encoder.int8.onnx", 26_200_000L),
                    ModelFile("decoder.onnx", 2_090_000L),
                    ModelFile("joiner.int8.onnx", 259_000L),
                    ModelFile("tokens.txt", 6_390L),
                ),
                modelType = "zipformer2",
                encoderFile = "encoder.int8.onnx",
                decoderFile = "decoder.onnx",
                joinerFile = "joiner.int8.onnx",
            ),
            "es" to LanguageModelConfig(
                dirName = "sherpa-onnx-streaming-zipformer-es-kroko-2025-08-06",
                baseUrl = "https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-es-kroko-2025-08-06/resolve/main",
                files = listOf(
                    ModelFile("encoder.onnx", 155_000_000L),
                    ModelFile("decoder.onnx", 617_000L),
                    ModelFile("joiner.onnx", 337_000L),
                    ModelFile("tokens.txt", 6_390L),
                ),
                modelType = "zipformer2",
                encoderFile = "encoder.onnx",
                decoderFile = "decoder.onnx",
                joinerFile = "joiner.onnx",
            ),
            "de" to LanguageModelConfig(
                dirName = "sherpa-onnx-streaming-zipformer-de-kroko-2025-08-06",
                baseUrl = "https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-de-kroko-2025-08-06/resolve/main",
                files = listOf(
                    ModelFile("encoder.onnx", 70_100_000L),
                    ModelFile("decoder.onnx", 617_000L),
                    ModelFile("joiner.onnx", 337_000L),
                    ModelFile("tokens.txt", 5_610L),
                ),
                modelType = "zipformer2",
                encoderFile = "encoder.onnx",
                decoderFile = "decoder.onnx",
                joinerFile = "joiner.onnx",
            ),
            "fr" to LanguageModelConfig(
                dirName = "sherpa-onnx-streaming-zipformer-fr-kroko-2025-08-06",
                baseUrl = "https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-fr-kroko-2025-08-06/resolve/main",
                files = listOf(
                    ModelFile("encoder.onnx", 70_100_000L),
                    ModelFile("decoder.onnx", 617_000L),
                    ModelFile("joiner.onnx", 337_000L),
                    ModelFile("tokens.txt", 5_420L),
                ),
                modelType = "zipformer2",
                encoderFile = "encoder.onnx",
                decoderFile = "decoder.onnx",
                joinerFile = "joiner.onnx",
            ),
            "zh" to LanguageModelConfig(
                dirName = "sherpa-onnx-streaming-zipformer-zh-int8-2025-06-30",
                baseUrl = "https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-zh-int8-2025-06-30/resolve/main",
                files = listOf(
                    ModelFile("encoder.int8.onnx", 161_000_000L),
                    ModelFile("decoder.onnx", 5_170_000L),
                    ModelFile("joiner.int8.onnx", 1_030_000L),
                    ModelFile("tokens.txt", 20_600L),
                ),
                modelType = "zipformer2",
                encoderFile = "encoder.int8.onnx",
                decoderFile = "decoder.onnx",
                joinerFile = "joiner.int8.onnx",
            ),
            "ko" to LanguageModelConfig(
                dirName = "sherpa-onnx-streaming-zipformer-korean-2024-06-16",
                baseUrl = "https://huggingface.co/k2-fsa/sherpa-onnx-streaming-zipformer-korean-2024-06-16/resolve/main",
                files = listOf(
                    ModelFile("encoder-epoch-99-avg-1.int8.onnx", 127_000_000L),
                    ModelFile("decoder-epoch-99-avg-1.int8.onnx", 2_840_000L),
                    ModelFile("joiner-epoch-99-avg-1.int8.onnx", 2_580_000L),
                    ModelFile("tokens.txt", 60_200L),
                ),
                modelType = "zipformer",
                encoderFile = "encoder-epoch-99-avg-1.int8.onnx",
                decoderFile = "decoder-epoch-99-avg-1.int8.onnx",
                joinerFile = "joiner-epoch-99-avg-1.int8.onnx",
            ),
        )

        fun getLanguageConfig(language: String): LanguageModelConfig? = LANGUAGE_MODELS[language]

        const val PUNCT_MODEL_DIR = "sherpa-onnx-online-punct-en-2024-08-06"
        private const val PUNCT_MODEL_URL =
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/punctuation-models/sherpa-onnx-online-punct-en-2024-08-06.tar.bz2"
        private val PUNCT_MODEL_FILES = setOf("model.int8.onnx", "bpe.vocab")
    }

    data class ModelFile(val name: String, val approxSize: Long)

    private val _status = MutableStateFlow<ModelStatus>(ModelStatus.NotDownloaded)
    val status: StateFlow<ModelStatus> = _status.asStateFlow()

    /** Allow external callers (e.g. ViewModel) to override the status
     *  for tracking pipeline initialization beyond just file downloads. */
    fun updateStatus(status: ModelStatus) {
        _status.value = status
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    fun getModelDir(language: String): File {
        val config = LANGUAGE_MODELS[language]
            ?: LANGUAGE_MODELS["en"]!!
        return File(context.filesDir, config.dirName)
    }

    /** Backward-compatible: checks English model. */
    fun isModelReady(): Boolean = isModelReady("en")

    fun isModelReady(language: String): Boolean {
        val config = LANGUAGE_MODELS[language] ?: return false
        val dir = File(context.filesDir, config.dirName)
        if (!dir.exists()) return false
        return config.files.all { File(dir, it.name).exists() }
    }

    suspend fun ensureModelAvailable(language: String = "en") {
        cleanupOldModel()
        if (isModelReady(language)) {
            _status.value = ModelStatus.Ready
            return
        }
        downloadModel(language)
    }

    fun getPunctModelDir(): File = File(context.filesDir, PUNCT_MODEL_DIR)

    fun isPunctModelReady(): Boolean {
        val dir = getPunctModelDir()
        return dir.exists() && PUNCT_MODEL_FILES.all { File(dir, it).exists() }
    }

    suspend fun ensurePunctModelAvailable() {
        if (isPunctModelReady()) return
        downloadPunctModel()
    }


    private suspend fun downloadPunctModel() = withContext(Dispatchers.IO) {
        try {
            val dir = getPunctModelDir()
            dir.mkdirs()

            Log.i(TAG, "PUNCT: Starting download from $PUNCT_MODEL_URL")
            val request = Request.Builder().url(PUNCT_MODEL_URL).build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.e(TAG, "PUNCT: Download HTTP error: ${response.code}")
                throw Exception("Punct model download failed: HTTP ${response.code}")
            }

            val tempFile = File(context.cacheDir, "punct-model.tar.bz2")
            FileOutputStream(tempFile).use { out ->
                response.body.byteStream().use { it.copyTo(out) }
            }
            Log.i(TAG, "PUNCT: tar.bz2 downloaded OK (${tempFile.length()} bytes)")

            extractTarBz2(tempFile, dir)
            tempFile.delete()

            // Verify all files exist after extraction
            val missing = PUNCT_MODEL_FILES.filter { !File(dir, it).exists() }
            if (missing.isEmpty()) {
                Log.i(TAG, "PUNCT: ALL files extracted OK: ${PUNCT_MODEL_FILES.joinToString()}")
            } else {
                Log.e(TAG, "PUNCT: MISSING files after extraction: $missing")
            }
        } catch (e: Exception) {
            Log.e(TAG, "PUNCT: Download/extraction FAILED", e)
        }
    }

    private fun extractTarBz2(archive: File, targetDir: File) {
        val neededFiles = PUNCT_MODEL_FILES
        Log.i(TAG, "PUNCT: Extracting tar.bz2, looking for: $neededFiles")
        FileInputStream(archive).use { fileInput ->
            BZip2CompressorInputStream(fileInput).use { bzInput ->
                TarArchiveInputStream(bzInput).use { tarInput ->
                    var extracted = 0
                    var entryIndex = 0
                    var entry = tarInput.nextEntry
                    while (entry != null && extracted < neededFiles.size) {
                        val fileName = File(entry.name).name
                        Log.d(TAG, "PUNCT: tar entry #$entryIndex: ${entry.name} (${entry.size} bytes, dir=${entry.isDirectory})")
                        if (fileName in neededFiles && !entry.isDirectory) {
                            val outFile = File(targetDir, fileName)
                            FileOutputStream(outFile).use { out ->
                                tarInput.copyTo(out)
                            }
                            extracted++
                            Log.i(TAG, "PUNCT: Extracted $fileName (${outFile.length()} bytes) [$extracted/${neededFiles.size}]")
                        }
                        entryIndex++
                        entry = tarInput.nextEntry
                    }
                    Log.i(TAG, "PUNCT: Extraction done. Extracted $extracted/${neededFiles.size} files")
                }
            }
        }
    }

    private fun cleanupOldModel() {
        val oldDir = File(context.filesDir, OLD_MODEL_DIR_NAME)
        if (oldDir.exists()) {
            Log.i(TAG, "Removing old model: $OLD_MODEL_DIR_NAME")
            oldDir.deleteRecursively()
        }
    }

    private suspend fun downloadModel(language: String) = withContext(Dispatchers.IO) {
        val config = LANGUAGE_MODELS[language]
            ?: throw IllegalArgumentException("No model available for language: $language")

        try {
            val dir = File(context.filesDir, config.dirName)
            dir.mkdirs()

            val totalSize = config.files.sumOf { it.approxSize }
            var downloadedSoFar = 0L

            for (file in config.files) {
                val target = File(dir, file.name)
                if (target.exists() && target.length() > 0) {
                    downloadedSoFar += file.approxSize
                    continue
                }

                _status.value = ModelStatus.Downloading(
                    progress = downloadedSoFar.toFloat() / totalSize,
                    currentFile = file.name
                )

                val url = "${config.baseUrl}/${file.name}"
                Log.i(TAG, "Downloading ${file.name} from $url")

                downloadToFile(client, url, target) { fileDownloaded ->
                    val totalProgress = (downloadedSoFar + fileDownloaded).toFloat() / totalSize
                    _status.value = ModelStatus.Downloading(
                        progress = totalProgress.coerceIn(0f, 1f),
                        currentFile = file.name
                    )
                }

                downloadedSoFar += file.approxSize
                Log.i(TAG, "Downloaded ${file.name} (${target.length()} bytes)")
            }

            _status.value = ModelStatus.Ready
            Log.i(TAG, "All model files ready for '$language' in ${dir.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Model download failed for '$language'", e)
            _status.value = ModelStatus.Error(e.message ?: "Unknown error")
        }
    }

}
