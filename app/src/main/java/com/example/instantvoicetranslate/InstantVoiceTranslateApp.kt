package com.example.instantvoicetranslate

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.util.Log
import com.example.instantvoicetranslate.asr.ConversationRecognizerPool
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class InstantVoiceTranslateApp : Application() {

    companion object {
        const val CHANNEL_ID = "translation_service"
        private const val TAG = "InstantVoiceTranslateApp"
    }

    @Inject lateinit var recognizerPool: ConversationRecognizerPool

    override fun onCreate() {
        super.onCreate()

        createNotificationChannel()
    }

    /**
     * Under severe memory pressure, shrink the ASR warm-pool back to one
     * language before the system decides to kill this whole process instead.
     *
     * Reacts only to RUNNING_CRITICAL, not the much more frequent
     * RUNNING_LOW/MODERATE levels: on a device that's chronically close to
     * its memory watermark (many background apps, a memory-hungry external
     * TTS engine also competing for RAM), those fire on essentially every
     * turn of a conversation, which was thrashing the pool -- discarding the
     * other direction's warm recognizer right after it was loaded and
     * forcing a reload on the very next swap.
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // Must be == not >=: TRIM_MEMORY_UI_HIDDEN (20) and everything above
        // it fire on ordinary backgrounding (task switch, screen lock) with
        // no memory pressure implied, unlike RUNNING_CRITICAL (15) which is
        // specifically the foreground-process pressure signal. `>=` matched
        // UI_HIDDEN too, so the pool was being trimmed and reloaded on every
        // simple app switch instead of only under real pressure.
        if (level == TRIM_MEMORY_RUNNING_CRITICAL) {
            Log.w(TAG, "onTrimMemory(level=$level): trimming ASR recognizer pool")
            recognizerPool.trimToMostRecentlyUsed()
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Translation Service",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Real-time translation service"
            setShowBadge(false)
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }
}
