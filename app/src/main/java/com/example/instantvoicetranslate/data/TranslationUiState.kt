package com.example.instantvoicetranslate.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Shared UI state for the translation pipeline.
 *
 * Text fields accumulate history (last [MAX_HISTORY_LINES] segments)
 * so the user can see recent context instead of just the latest segment.
 */
@Singleton
class TranslationUiState @Inject constructor() {

    companion object {
        /** Maximum number of recent segments to keep visible. */
        private const val MAX_HISTORY_LINES = 10
    }

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

    /** Recent original segments for history display. */
    private val originalHistory = mutableListOf<String>()
    /** Recent translated segments for history display. */
    private val translatedHistory = mutableListOf<String>()

    fun updatePartial(text: String) {
        _partialText.value = text
    }

    /**
     * Append a finalized original segment to the accumulated history.
     */
    fun updateOriginal(text: String) {
        synchronized(originalHistory) {
            originalHistory.add(text)
            if (originalHistory.size > MAX_HISTORY_LINES) {
                originalHistory.removeAt(0)
            }
            _originalText.value = originalHistory.joinToString("\n")
        }
    }

    /**
     * Append a finalized translated segment to the accumulated history.
     */
    fun updateTranslated(text: String) {
        synchronized(translatedHistory) {
            translatedHistory.add(text)
            if (translatedHistory.size > MAX_HISTORY_LINES) {
                translatedHistory.removeAt(0)
            }
            _translatedText.value = translatedHistory.joinToString("\n")
        }
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

}
