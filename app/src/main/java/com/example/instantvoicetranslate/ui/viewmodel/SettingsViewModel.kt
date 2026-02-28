package com.example.instantvoicetranslate.ui.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.instantvoicetranslate.data.AppSettings
import com.example.instantvoicetranslate.data.ModelStatus
import com.example.instantvoicetranslate.data.SettingsRepository
import com.example.instantvoicetranslate.translation.NllbModelManager
import com.example.instantvoicetranslate.translation.NllbTranslator
import com.example.instantvoicetranslate.ui.theme.ThemeMode
import com.example.instantvoicetranslate.ui.utils.LanguageUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val nllbModelManager: NllbModelManager,
    private val nllbTranslator: NllbTranslator,
) : ViewModel() {

    companion object {
        private const val TAG = "SettingsViewModel"
    }

    val settings: StateFlow<AppSettings> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    val nllbModelStatus: StateFlow<ModelStatus> = nllbModelManager.status

    init {
        // Set initial NLLB status based on whether model is already downloaded
        if (nllbModelManager.isModelReady()) {
            nllbModelManager.updateStatus(ModelStatus.Ready)
        }
    }

    fun downloadNllbModel() {
        viewModelScope.launch {
            nllbModelManager.ensureModelAvailable()

            // Warmup: initialize NLLB translator to force DJL native lib
            // extraction/caching while we still have internet (if needed).
            // This ensures offline mode works later without any network calls.
            if (nllbModelManager.isModelReady()) {
                nllbModelManager.updateStatus(
                    ModelStatus.Initializing("Warming up offline translation...")
                )
                try {
                    nllbTranslator.initialize()
                    Log.i(TAG, "NLLB warmup completed, releasing to free RAM")
                    nllbTranslator.release()
                    nllbModelManager.updateStatus(ModelStatus.Ready)
                } catch (e: Throwable) {
                    Log.e(TAG, "NLLB warmup failed", e)
                    nllbModelManager.updateStatus(
                        ModelStatus.Error("Warmup failed: ${e.message}")
                    )
                }
            }
        }
    }

    fun deleteNllbModel() {
        viewModelScope.launch {
            // Disable offline mode before deleting model
            settingsRepository.updateOfflineMode(false)
            nllbModelManager.deleteModel()
        }
    }

    fun updateOfflineMode(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.updateOfflineMode(enabled)
            // If user switches from offline to online and the selected target language
            // is not available in online mode, reset to default "ru"
            if (!enabled) {
                val currentTarget = settings.value.targetLanguage
                val onlineCodes = LanguageUtils.onlineTargetLanguages.map { it.first }
                if (currentTarget !in onlineCodes) {
                    settingsRepository.updateTargetLanguage("ru")
                }
            }
        }
    }

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
