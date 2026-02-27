package com.example.instantvoicetranslate.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.instantvoicetranslate.data.AppSettings
import com.example.instantvoicetranslate.data.SettingsRepository
import com.example.instantvoicetranslate.ui.theme.ThemeMode
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    val settings: StateFlow<AppSettings> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    fun updateTtsSpeed(speed: Float) {
        viewModelScope.launch { settingsRepository.updateTtsSpeed(speed) }
    }

    fun updateTtsPitch(pitch: Float) {
        viewModelScope.launch { settingsRepository.updateTtsPitch(pitch) }
    }

    fun updateThemeMode(mode: ThemeMode) {
        viewModelScope.launch { settingsRepository.updateThemeMode(mode) }
    }

    fun updateShowOriginalText(show: Boolean) {
        viewModelScope.launch { settingsRepository.updateShowOriginalText(show) }
    }

    fun updateShowPartialText(show: Boolean) {
        viewModelScope.launch { settingsRepository.updateShowPartialText(show) }
    }

    fun updateAutoSpeak(auto: Boolean) {
        viewModelScope.launch { settingsRepository.updateAutoSpeak(auto) }
    }

    fun updateSourceLanguage(lang: String) {
        viewModelScope.launch { settingsRepository.updateSourceLanguage(lang) }
    }

    fun updateTargetLanguage(lang: String) {
        viewModelScope.launch { settingsRepository.updateTargetLanguage(lang) }
    }

    fun updateMuteMicDuringTts(mute: Boolean) {
        viewModelScope.launch { settingsRepository.updateMuteMicDuringTts(mute) }
    }

    fun updateAudioDiagnostics(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.updateAudioDiagnostics(enabled) }
    }

    fun updateDiagOutputDir(dir: String) {
        viewModelScope.launch { settingsRepository.updateDiagOutputDir(dir) }
    }
}
