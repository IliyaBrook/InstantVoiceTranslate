package com.example.instantvoicetranslate.audio

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.util.Log
import androidx.annotation.RequiresPermission
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AudioCaptureManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "AudioCaptureManager"
        const val SAMPLE_RATE = 16000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        // ~100ms chunks: 16000 * 0.1 = 1600 samples
        private const val CHUNK_SIZE_SAMPLES = 1600
    }

    enum class Source { MICROPHONE, SYSTEM_AUDIO }

    private val _isCapturing = MutableStateFlow(false)
    val isCapturing: StateFlow<Boolean> = _isCapturing.asStateFlow()

    private var mediaProjection: MediaProjection? = null
    private var currentRecord: AudioRecord? = null

    fun setMediaProjection(projection: MediaProjection) {
        mediaProjection = projection
    }

    /**
     * Start capturing audio from the given source.
     * Returns a Flow of FloatArray chunks (16kHz, mono, normalized -1.0..1.0).
     * Each chunk is ~100ms (1600 samples).
     */
    @SuppressLint("MissingPermission")
    fun startCapture(source: Source): Flow<FloatArray> = callbackFlow {
        val bufferSize = maxOf(
            AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT),
            CHUNK_SIZE_SAMPLES * 2 // 2 bytes per PCM_16BIT sample
        )

        val audioRecord = when (source) {
            Source.MICROPHONE -> createMicrophoneRecord(bufferSize)
            Source.SYSTEM_AUDIO -> createSystemAudioRecord(bufferSize)
        }

        if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
            close(IllegalStateException("AudioRecord failed to initialize for $source"))
            return@callbackFlow
        }

        currentRecord = audioRecord
        audioRecord.startRecording()
        _isCapturing.value = true
        Log.i(TAG, "Started capturing from $source")

        val shortBuffer = ShortArray(CHUNK_SIZE_SAMPLES)

        withContext(Dispatchers.IO) {
            try {
                while (isActive) {
                    val read = audioRecord.read(shortBuffer, 0, CHUNK_SIZE_SAMPLES)
                    if (read > 0) {
                        val floatChunk = FloatArray(read) { i ->
                            shortBuffer[i] / 32768f
                        }
                        trySend(floatChunk)
                    } else if (read < 0) {
                        Log.e(TAG, "AudioRecord.read() error: $read")
                        break
                    }
                }
            } finally {
                audioRecord.stop()
                audioRecord.release()
                currentRecord = null
                _isCapturing.value = false
                Log.i(TAG, "Stopped capturing from $source")
            }
        }

        awaitClose {
            try {
                if (audioRecord.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    audioRecord.stop()
                }
                audioRecord.release()
            } catch (e: Exception) {
                Log.w(TAG, "Error releasing AudioRecord", e)
            }
            currentRecord = null
            _isCapturing.value = false
        }
    }

    fun stopCapture() {
        try {
            currentRecord?.let { record ->
                if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    record.stop()
                }
                record.release()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping capture", e)
        }
        currentRecord = null
        _isCapturing.value = false
    }

    fun releaseMediaProjection() {
        mediaProjection?.stop()
        mediaProjection = null
    }

    @SuppressLint("MissingPermission")
    private fun createMicrophoneRecord(bufferSize: Int): AudioRecord {
        return AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT,
            bufferSize
        )
    }

    @SuppressLint("MissingPermission")
    private fun createSystemAudioRecord(bufferSize: Int): AudioRecord {
        val projection = mediaProjection
            ?: throw IllegalStateException("MediaProjection not set. Call setMediaProjection() first.")

        val captureConfig = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()

        val format = AudioFormat.Builder()
            .setEncoding(AUDIO_FORMAT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(CHANNEL_CONFIG)
            .build()

        return AudioRecord.Builder()
            .setAudioPlaybackCaptureConfig(captureConfig)
            .setAudioFormat(format)
            .setBufferSizeInBytes(bufferSize)
            .build()
    }
}
