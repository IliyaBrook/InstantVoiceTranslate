package com.example.instantvoicetranslate.audio

import android.content.Context
import android.util.Log
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Records raw PCM audio to WAV files for diagnostic comparison
 * between microphone and system audio sources.
 *
 * Default output: /sdcard/Android/data/<package>/files/audio_diag/
 * Custom output: any directory chosen via the system file picker (SAF).
 *
 * Each recording creates a file like:
 *   mic_2026-02-27_14-30-15.wav
 *   sys_2026-02-27_14-30-15.wav
 *
 * WAV format: 16kHz, mono, 16-bit PCM (playable in any audio player).
 *
 * When a SAF URI is provided as [customOutputDir], the WAV is first
 * written to a temporary cache file (for RandomAccessFile header update),
 * then copied to the chosen directory on [stopRecording].
 */
class AudioDiagnostics(
    private val context: Context,
    private val customOutputDir: String = "",
) {

    companion object {
        private const val TAG = "AudioDiag"
        private const val SAMPLE_RATE = 16000
        private const val BITS_PER_SAMPLE = 16
        private const val NUM_CHANNELS = 1
        /** Maximum recording duration in seconds (auto-stop to save space). */
        private const val MAX_DURATION_SEC = 30
        private const val MAX_SAMPLES = SAMPLE_RATE * MAX_DURATION_SEC

        /** RMS logging interval in chunks (~N × 100ms). */
        private const val RMS_LOG_INTERVAL = 10  // every ~1 second
    }

    /**
     * When true, audio is recorded to WAV files and RMS is logged.
     * The [AudioDiagnostics] instance is only created when the user
     * enables diagnostics in Settings, so this is true by default.
     */
    var enabled = true

    private var outputStream: FileOutputStream? = null
    private var filePath: String? = null
    private var fileName: String? = null
    private var totalSamplesWritten = 0L
    private var chunkCount = 0L
    private var sourceName = ""

    /** Whether [customOutputDir] is a SAF content:// URI. */
    private val isSafUri: Boolean
        get() = customOutputDir.startsWith("content://")

    /**
     * Start a new WAV recording file for the given audio source.
     */
    fun startRecording(source: AudioCaptureManager.Source) {
        if (!enabled) return
        stopRecording() // close any previous file

        sourceName = when (source) {
            AudioCaptureManager.Source.MICROPHONE -> "mic"
            AudioCaptureManager.Source.SYSTEM_AUDIO -> "sys"
        }

        val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
        fileName = "${sourceName}_$timestamp.wav"

        // SAF URIs can't use RandomAccessFile, so always write to a local
        // temp/direct file first. If SAF is used, we'll copy on stop.
        val file = if (isSafUri) {
            File(context.cacheDir, fileName!!)
        } else {
            val dir = if (customOutputDir.isNotBlank()) {
                File(customOutputDir)
            } else {
                File(context.getExternalFilesDir(null), "audio_diag")
            }
            if (!dir.exists()) dir.mkdirs()
            File(dir, fileName!!)
        }

        filePath = file.absolutePath

        try {
            outputStream = FileOutputStream(file)
            // Write placeholder WAV header (44 bytes), will be updated on stop
            outputStream!!.write(ByteArray(44))
            totalSamplesWritten = 0
            chunkCount = 0
            Log.i(TAG, "Started recording to: ${file.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start recording", e)
            outputStream = null
            filePath = null
            fileName = null
        }
    }

    /**
     * Write float PCM samples to the WAV file and log RMS periodically.
     *
     * @param samples Float32 PCM, range -1.0..1.0
     */
    fun writeSamples(samples: FloatArray) {
        chunkCount++
        if (chunkCount % RMS_LOG_INTERVAL == 0L) {
            var sumSq = 0.0
            var peak = 0f
            var zeroCount = 0
            for (s in samples) {
                sumSq += s * s
                val abs = kotlin.math.abs(s)
                if (abs > peak) peak = abs
                if (s == 0f) zeroCount++
            }
            val rms = kotlin.math.sqrt(sumSq / samples.size)
            val zeroPct = zeroCount * 100.0 / samples.size
            Log.d(TAG, "[$sourceName] #$chunkCount: rms=%.7f peak=%.7f zeros=%.1f%%"
                .format(rms, peak, zeroPct))
        }

        val os = outputStream ?: return
        if (totalSamplesWritten >= MAX_SAMPLES) return // auto-stop

        try {
            // Convert float [-1.0, 1.0] to 16-bit PCM
            val buffer = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            for (sample in samples) {
                val clamped = sample.coerceIn(-1f, 1f)
                val pcm16 = (clamped * 32767f).toInt().toShort()
                buffer.putShort(pcm16)
            }
            os.write(buffer.array())
            totalSamplesWritten += samples.size
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write samples", e)
        }
    }

    /**
     * Stop recording, finalize WAV header, and copy to SAF directory if needed.
     */
    fun stopRecording() {
        outputStream?.let { os ->
            try {
                os.flush()
                os.close()
            } catch (_: Exception) {}
        }
        outputStream = null

        val path = filePath ?: return
        val name = fileName ?: return
        filePath = null
        fileName = null

        if (totalSamplesWritten == 0L) {
            File(path).delete()
            return
        }

        // Update WAV header with actual data size
        try {
            val dataSize = totalSamplesWritten * (BITS_PER_SAMPLE / 8) * NUM_CHANNELS
            val raf = RandomAccessFile(path, "rw")
            raf.seek(0)
            raf.write(buildWavHeader(dataSize))
            raf.close()

            val durationSec = totalSamplesWritten.toFloat() / SAMPLE_RATE

            // If using SAF, copy the finalized file to the chosen directory
            if (isSafUri) {
                val copied = copyToSafDirectory(File(path), name)
                File(path).delete() // remove temp file
                if (copied) {
                    Log.i(TAG, "Recording saved to SAF: $name (%.1fs, %d samples)"
                        .format(durationSec, totalSamplesWritten))
                } else {
                    Log.e(TAG, "Failed to copy recording to SAF directory")
                }
            } else {
                Log.i(TAG, "Recording saved: $path (%.1fs, %d samples)"
                    .format(durationSec, totalSamplesWritten))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to finalize WAV recording", e)
        }
    }

    /**
     * Copy a completed WAV file to the SAF-picked directory.
     */
    private fun copyToSafDirectory(srcFile: File, destName: String): Boolean {
        return try {
            val treeUri = customOutputDir.toUri()
            val treeDoc = DocumentFile.fromTreeUri(context, treeUri) ?: return false
            val docFile = treeDoc.createFile("audio/x-wav", destName) ?: return false
            context.contentResolver.openOutputStream(docFile.uri)?.use { out ->
                srcFile.inputStream().use { inp -> inp.copyTo(out) }
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "SAF copy failed", e)
            false
        }
    }

    private fun buildWavHeader(dataSize: Long): ByteArray {
        val totalSize = 36 + dataSize
        val byteRate = SAMPLE_RATE * NUM_CHANNELS * BITS_PER_SAMPLE / 8
        val blockAlign = NUM_CHANNELS * BITS_PER_SAMPLE / 8

        val buffer = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        // RIFF header
        buffer.put("RIFF".toByteArray())
        buffer.putInt(totalSize.toInt())
        buffer.put("WAVE".toByteArray())
        // fmt subchunk
        buffer.put("fmt ".toByteArray())
        buffer.putInt(16)                    // subchunk size
        buffer.putShort(1)                   // PCM format
        buffer.putShort(NUM_CHANNELS.toShort())
        buffer.putInt(SAMPLE_RATE)
        buffer.putInt(byteRate)
        buffer.putShort(blockAlign.toShort())
        buffer.putShort(BITS_PER_SAMPLE.toShort())
        // data subchunk
        buffer.put("data".toByteArray())
        buffer.putInt(dataSize.toInt())
        return buffer.array()
    }
}
