package com.example.instantvoicetranslate.data

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Copies model files bundled as APK assets into real filesystem storage
 * (native ASR/ONNX code requires real file paths, not zipped asset streams).
 */
@Singleton
class AssetModelProvisioner @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "AssetModelProvisioner"
        private const val BUFFER_SIZE = 16 * 1024
    }

    /**
     * Copies [expectedFiles] from `assets/$assetSubdir/` into [targetDir] on
     * the real filesystem. Returns false if the assets subdirectory is absent
     * or doesn't contain every expected file — callers should then fall back
     * to a network download, keeping the app functional for any language
     * added later without a matching bundled-assets rebuild.
     */
    suspend fun installFromAssets(
        assetSubdir: String,
        targetDir: File,
        expectedFiles: List<String>,
        onProgress: (ModelStatus.Downloading) -> Unit = {},
    ): Boolean = withContext(Dispatchers.IO) {
        val available = runCatching { context.assets.list(assetSubdir)?.toSet() }.getOrNull()
        if (available.isNullOrEmpty() || !expectedFiles.all { it in available }) {
            Log.i(TAG, "Assets not bundled for '$assetSubdir' (or incomplete); will use network fallback")
            return@withContext false
        }

        targetDir.mkdirs()

        // Best-effort byte sizes via openFd() (only works for uncompressed/
        // noCompress entries). Falls back to equal-weight-per-file progress
        // if unavailable — extraction itself works either way since
        // assets.open() transparently inflates compressed entries too.
        val sizes = expectedFiles.associateWith { name ->
            runCatching { context.assets.openFd("$assetSubdir/$name").use { it.length } }.getOrDefault(-1L)
        }
        val useByteProgress = sizes.values.all { it > 0 }
        val totalUnits = if (useByteProgress) sizes.values.sum() else expectedFiles.size.toLong()
        var doneUnits = 0L

        try {
            for (name in expectedFiles) {
                val target = File(targetDir, name)
                if (target.exists() && target.length() > 0) {
                    doneUnits += if (useByteProgress) sizes.getValue(name) else 1L
                    continue
                }

                onProgress(ModelStatus.Downloading((doneUnits.toFloat() / totalUnits).coerceIn(0f, 1f), name))

                val tmp = File(targetDir, "$name.tmp")
                context.assets.open("$assetSubdir/$name").use { input ->
                    FileOutputStream(tmp).use { output ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        var read: Int
                        var fileDone = 0L
                        while (input.read(buffer).also { read = it } != -1) {
                            output.write(buffer, 0, read)
                            fileDone += read
                            if (useByteProgress) {
                                val units = (doneUnits + fileDone).toFloat() / totalUnits
                                onProgress(ModelStatus.Downloading(units.coerceIn(0f, 1f), name))
                            }
                        }
                        output.fd.sync()
                    }
                }
                if (!tmp.renameTo(target)) {
                    Log.e(TAG, "Rename failed for $name")
                    tmp.delete()
                    return@withContext false
                }
                doneUnits += if (useByteProgress) sizes.getValue(name) else 1L
            }
            val complete = expectedFiles.all { File(targetDir, it).let { f -> f.exists() && f.length() > 0 } }
            Log.i(TAG, "Asset extraction for '$assetSubdir' complete=$complete")
            complete
        } catch (e: Exception) {
            Log.e(TAG, "Asset extraction failed for '$assetSubdir'", e)
            false
        }
    }
}
