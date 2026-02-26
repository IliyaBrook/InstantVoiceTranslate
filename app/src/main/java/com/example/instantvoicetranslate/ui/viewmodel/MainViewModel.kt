package com.example.instantvoicetranslate.ui.viewmodel

import android.app.Application
import android.content.Intent
import android.media.projection.MediaProjection
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.instantvoicetranslate.audio.AudioCaptureManager
import com.example.instantvoicetranslate.data.AppSettings
import com.example.instantvoicetranslate.data.ModelDownloader
import com.example.instantvoicetranslate.data.ModelStatus
import com.example.instantvoicetranslate.data.SettingsRepository
import com.example.instantvoicetranslate.data.TranslationUiState
import com.example.instantvoicetranslate.service.TranslationService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    private val application: Application,
    private val translationUiState: TranslationUiState,
    private val settingsRepository: SettingsRepository,
    private val modelDownloader: ModelDownloader,
    private val audioCaptureManager: AudioCaptureManager,
) : AndroidViewModel(application) {

    val isRunning: StateFlow<Boolean> = translationUiState.isRunning
    val partialText: StateFlow<String> = translationUiState.partialText
    val originalText: StateFlow<String> = translationUiState.originalText
    val translatedText: StateFlow<String> = translationUiState.translatedText
    val error: StateFlow<String?> = translationUiState.error
    val modelStatus: StateFlow<ModelStatus> = modelDownloader.status

    val settings: StateFlow<AppSettings> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    init {
        checkModelStatus()
    }

    private fun checkModelStatus() {
        viewModelScope.launch {
            val lang = settingsRepository.settings.first().sourceLanguage
            modelDownloader.ensureModelAvailable(lang)
        }
    }

    fun downloadModel() {
        viewModelScope.launch {
            val lang = settings.value.sourceLanguage
            modelDownloader.ensureModelAvailable(lang)
        }
    }

    fun startTranslation() {
        val intent = Intent(application, TranslationService::class.java).apply {
            action = TranslationService.ACTION_START
            putExtra(
                TranslationService.EXTRA_AUDIO_SOURCE,
                settings.value.audioSource.name
            )
        }
        application.startForegroundService(intent)
    }

    fun stopTranslation() {
        val intent = Intent(application, TranslationService::class.java).apply {
            action = TranslationService.ACTION_STOP
        }
        application.startService(intent)
    }

    fun setMediaProjection(projection: MediaProjection) {
        audioCaptureManager.setMediaProjection(projection)
    }

    fun updateAudioSource(source: AudioCaptureManager.Source) {
        viewModelScope.launch {
            settingsRepository.updateAudioSource(source)
        }
    }

    fun clearError() {
        translationUiState.setError(null)
    }
}
