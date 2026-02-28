package com.example.instantvoicetranslate.data

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream

/**
 * Downloads a file from [url] to [targetFile] via a temporary file,
 * reporting progress through [onProgress] with bytes downloaded so far.
 */
fun downloadToFile(
    client: OkHttpClient,
    url: String,
    targetFile: File,
    onProgress: (bytesDownloaded: Long) -> Unit = {},
) {
    val request = Request.Builder().url(url).build()
    val response = client.newCall(request).execute()

    if (!response.isSuccessful) {
        throw Exception("Failed to download ${targetFile.name}: HTTP ${response.code}")
    }

    val tempFile = File(targetFile.parentFile, "${targetFile.name}.tmp")
    FileOutputStream(tempFile).use { output ->
        response.body.byteStream().use { input ->
            val buffer = ByteArray(8192)
            var bytesRead: Int
            var totalRead = 0L
            while (input.read(buffer).also { bytesRead = it } != -1) {
                output.write(buffer, 0, bytesRead)
                totalRead += bytesRead
                onProgress(totalRead)
            }
        }
    }

    tempFile.renameTo(targetFile)
}
