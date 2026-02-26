package com.example.instantvoicetranslate.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TranslationUiState @Inject constructor() {

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _partialText = MutableStateFlow("")
    val partialText: StateFlow<String> = _partialText.asStateFlow()

    private val _originalText = MutableStateFlow("")
    val originalText: StateFlow<String> = _originalText.asStateFlow()

    private val _translatedText = MutableStateFlow("")
    val translatedText: StateFlow<String> = _translatedText.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _modelStatus = MutableStateFlow<ModelStatus>(ModelStatus.NotDownloaded)
    val modelStatus: StateFlow<ModelStatus> = _modelStatus.asStateFlow()

    fun updatePartial(text: String) {
        _partialText.value = text
    }

    fun updateOriginal(text: String) {
        _originalText.value = text
    }

    fun updateTranslated(text: String) {
        _translatedText.value = text
    }

    fun setRunning(running: Boolean) {
        _isRunning.value = running
        if (!running) {
            _partialText.value = ""
        }
    }

    fun setError(message: String?) {
        _error.value = message
    }

    fun updateModelStatus(status: ModelStatus) {
        _modelStatus.value = status
    }

    fun clearAll() {
        _isRunning.value = false
        _partialText.value = ""
        _originalText.value = ""
        _translatedText.value = ""
        _error.value = null
    }
}
