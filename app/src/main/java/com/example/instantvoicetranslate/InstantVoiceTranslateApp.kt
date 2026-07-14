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
     * Under memory pressure, shrink the ASR warm-pool back to one language
     * before the system decides to kill this whole process instead — losing
     * one pre-warmed recognizer is far cheaper than a full restart.
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_RUNNING_LOW) {
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
