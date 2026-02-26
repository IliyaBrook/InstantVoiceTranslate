package com.example.instantvoicetranslate

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class InstantVoiceTranslateApp : Application() {

    companion object {
        const val CHANNEL_ID = "translation_service"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
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
