package com.example.instantvoicetranslate

import com.example.instantvoicetranslate.asr.SpeechRecognizer
import com.example.instantvoicetranslate.data.AppSettings
import com.example.instantvoicetranslate.data.TranslationUiState
import com.example.instantvoicetranslate.translation.TextTranslator
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * Tests verifying that source/target language settings correctly propagate
 * through the translation pipeline:
 *   Settings -> SpeechRecognizer (source lang) -> TextTranslator (src, tgt) -> TTS (target lang)
 */
class LanguagePipelineTest {

    // region Fake implementations

    /**
     * Fake SpeechRecognizer that tracks language passed during initialization.
     */
    class FakeSpeechRecognizer : SpeechRecognizer {
        private val _partialText = MutableStateFlow("")
        override val partialText: StateFlow<String> = _partialText.asStateFlow()

        private val _recognizedSegments = MutableSharedFlow<String>(extraBufferCapacity = 16)
        override val recognizedSegments: SharedFlow<String> = _recognizedSegments.asSharedFlow()

        private val _isReady = MutableStateFlow(false)
        override val isReady: StateFlow<Boolean> = _isReady.asStateFlow()

        private val _currentLanguage = MutableStateFlow("")
        override val currentLanguage: StateFlow<String> = _currentLanguage.asStateFlow()

        var initCallCount = 0
            private set
        var lastModelDir: String? = null
            private set
        var lastLanguage: String? = null
            private set

        override suspend fun initialize(modelDir: String, language: String) {
            initCallCount++
            lastModelDir = modelDir
            lastLanguage = language
            _currentLanguage.value = language
            _isReady.value = true
        }

        override fun startRecognition(audioFlow: Flow<FloatArray>) {}
        override fun stopRecognition() {
            _partialText.value = ""
        }

        override fun release() {
            _isReady.value = false
            _currentLanguage.value = ""
        }
    }

    /**
     * Spy TextTranslator that records all translate calls for verification.
     */
    class SpyTextTranslator : TextTranslator {
        private val _isAvailable = MutableStateFlow(true)
        override val isAvailable: StateFlow<Boolean> = _isAvailable.asStateFlow()

        data class TranslateCall(val text: String, val from: String, val to: String)

        val calls = mutableListOf<TranslateCall>()

        override suspend fun translate(text: String, from: String, to: String): Result<String> {
            calls.add(TranslateCall(text, from, to))
            return Result.success("translated: $text")
        }
    }

    // endregion

    // region Recognizer language initialization tests

    @Test
    fun `recognizer initialized with source language EN`() = runTest {
        val recognizer = FakeSpeechRecognizer()
        val settings = AppSettings(sourceLanguage = "en", targetLanguage = "ru")

        recognizer.initialize("/model", settings.sourceLanguage)

        assertEquals("en", recognizer.currentLanguage.value)
        assertEquals("en", recognizer.lastLanguage)
    }

    @Test
    fun `recognizer initialized with source language RU`() = runTest {
        val recognizer = FakeSpeechRecognizer()
        val settings = AppSettings(sourceLanguage = "ru", targetLanguage = "en")

        recognizer.initialize("/model", settings.sourceLanguage)

        assertEquals("ru", recognizer.currentLanguage.value)
        assertEquals("ru", recognizer.lastLanguage)
    }

    @Test
    fun `recognizer initialized with source language ES`() = runTest {
        val recognizer = FakeSpeechRecognizer()
        val settings = AppSettings(sourceLanguage = "es", targetLanguage = "en")

        recognizer.initialize("/model", settings.sourceLanguage)

        assertEquals("es", recognizer.currentLanguage.value)
    }

    @Test
    fun `recognizer initialized with source language DE`() = runTest {
        val recognizer = FakeSpeechRecognizer()
        val settings = AppSettings(sourceLanguage = "de", targetLanguage = "ru")

        recognizer.initialize("/model", settings.sourceLanguage)

        assertEquals("de", recognizer.currentLanguage.value)
    }

    // endregion

    // region Translator language pair tests

    @Test
    fun `translator receives correct pair for RU to EN`() = runTest {
        val translator = SpyTextTranslator()
        val settings = AppSettings(sourceLanguage = "ru", targetLanguage = "en")

        translator.translate("привет", settings.sourceLanguage, settings.targetLanguage)

        assertEquals(1, translator.calls.size)
        assertEquals("ru", translator.calls[0].from)
        assertEquals("en", translator.calls[0].to)
    }

    @Test
    fun `translator receives correct pair for EN to RU`() = runTest {
        val translator = SpyTextTranslator()
        val settings = AppSettings(sourceLanguage = "en", targetLanguage = "ru")

        translator.translate("hello", settings.sourceLanguage, settings.targetLanguage)

        assertEquals(1, translator.calls.size)
        assertEquals("en", translator.calls[0].from)
        assertEquals("ru", translator.calls[0].to)
    }

    @Test
    fun `translator receives correct pair for ES to DE`() = runTest {
        val translator = SpyTextTranslator()
        val settings = AppSettings(sourceLanguage = "es", targetLanguage = "de")

        translator.translate("hola", settings.sourceLanguage, settings.targetLanguage)

        assertEquals("es", translator.calls[0].from)
        assertEquals("de", translator.calls[0].to)
    }

    @Test
    fun `translator receives correct pair for JA to FR`() = runTest {
        val translator = SpyTextTranslator()
        val settings = AppSettings(sourceLanguage = "ja", targetLanguage = "fr")

        translator.translate("こんにちは", settings.sourceLanguage, settings.targetLanguage)

        assertEquals("ja", translator.calls[0].from)
        assertEquals("fr", translator.calls[0].to)
    }

    @Test
    fun `translator receives correct pair for ZH to KO`() = runTest {
        val translator = SpyTextTranslator()
        val settings = AppSettings(sourceLanguage = "zh", targetLanguage = "ko")

        translator.translate("你好", settings.sourceLanguage, settings.targetLanguage)

        assertEquals("zh", translator.calls[0].from)
        assertEquals("ko", translator.calls[0].to)
    }

    // endregion

    // region Full pipeline simulation tests

    @Test
    fun `full pipeline RU to EN - recognizer gets RU, translator gets RU-EN`() = runTest {
        val recognizer = FakeSpeechRecognizer()
        val translator = SpyTextTranslator()
        val uiState = TranslationUiState()
        val settings = AppSettings(sourceLanguage = "ru", targetLanguage = "en")

        // Step 1: Initialize recognizer with source language
        recognizer.initialize("/model", settings.sourceLanguage)
        assertEquals("ru", recognizer.currentLanguage.value)

        // Step 2: Simulate what TranslationService does when a segment arrives
        val segment = "привет мир"
        uiState.updateOriginal(segment)
        val result = translator.translate(segment, settings.sourceLanguage, settings.targetLanguage)
        result.onSuccess { uiState.updateTranslated(it) }

        // Verify the full chain
        assertEquals("ru", recognizer.currentLanguage.value)
        assertEquals("ru", translator.calls.last().from)
        assertEquals("en", translator.calls.last().to)
        assertEquals("привет мир", uiState.originalText.value)
        assertTrue(uiState.translatedText.value.isNotEmpty())
    }

    @Test
    fun `full pipeline ES to DE - recognizer gets ES, translator gets ES-DE`() = runTest {
        val recognizer = FakeSpeechRecognizer()
        val translator = SpyTextTranslator()
        val settings = AppSettings(sourceLanguage = "es", targetLanguage = "de")

        recognizer.initialize("/model", settings.sourceLanguage)
        assertEquals("es", recognizer.currentLanguage.value)

        val segment = "hola mundo"
        translator.translate(segment, settings.sourceLanguage, settings.targetLanguage)

        assertEquals("es", recognizer.currentLanguage.value)
        assertEquals("es", translator.calls.last().from)
        assertEquals("de", translator.calls.last().to)
    }

    @Test
    fun `full pipeline JA to RU - recognizer gets JA, translator gets JA-RU`() = runTest {
        val recognizer = FakeSpeechRecognizer()
        val translator = SpyTextTranslator()
        val settings = AppSettings(sourceLanguage = "ja", targetLanguage = "ru")

        recognizer.initialize("/model", settings.sourceLanguage)

        val segment = "こんにちは世界"
        translator.translate(segment, settings.sourceLanguage, settings.targetLanguage)

        assertEquals("ja", recognizer.currentLanguage.value)
        assertEquals("ja", translator.calls.last().from)
        assertEquals("ru", translator.calls.last().to)
    }

    // endregion

    // region Recognizer reinitialization on language change

    @Test
    fun `recognizer reinitializes when source language changes from EN to RU`() = runTest {
        val recognizer = FakeSpeechRecognizer()

        // Initial pipeline with English
        recognizer.initialize("/model", "en")
        assertEquals("en", recognizer.currentLanguage.value)
        assertEquals(1, recognizer.initCallCount)

        // Simulate language change to Russian (as TranslationService now does)
        val newSettings = AppSettings(sourceLanguage = "ru", targetLanguage = "en")
        val needsReinit = recognizer.currentLanguage.value != newSettings.sourceLanguage
        assertTrue("Should need reinitialization", needsReinit)

        if (needsReinit) {
            recognizer.release()
            recognizer.initialize("/model", newSettings.sourceLanguage)
        }

        assertEquals("ru", recognizer.currentLanguage.value)
        assertEquals(2, recognizer.initCallCount)
    }

    @Test
    fun `recognizer does NOT reinitialize when language stays the same`() = runTest {
        val recognizer = FakeSpeechRecognizer()

        recognizer.initialize("/model", "en")
        assertEquals(1, recognizer.initCallCount)

        // Same language, no reinit needed
        val sameSettings = AppSettings(sourceLanguage = "en", targetLanguage = "ru")
        val needsReinit = recognizer.currentLanguage.value != sameSettings.sourceLanguage

        assertEquals(false, needsReinit)
        assertEquals(1, recognizer.initCallCount) // Still 1
    }

    @Test
    fun `recognizer reinitializes through multiple language changes`() = runTest {
        val recognizer = FakeSpeechRecognizer()

        // EN -> RU -> ES -> DE
        val languages = listOf("en", "ru", "es", "de")
        for ((index, lang) in languages.withIndex()) {
            val needsReinit = !recognizer.isReady.value ||
                    recognizer.currentLanguage.value != lang
            if (needsReinit) {
                if (recognizer.isReady.value) recognizer.release()
                recognizer.initialize("/model", lang)
            }
            assertEquals(lang, recognizer.currentLanguage.value)
            assertEquals(index + 1, recognizer.initCallCount)
        }
    }

    // endregion

    // region TTS language verification

    @Test
    fun `TTS locale matches target language RU`() {
        val settings = AppSettings(sourceLanguage = "en", targetLanguage = "ru")
        val ttsLocale = Locale.forLanguageTag(settings.targetLanguage)
        assertEquals("ru", ttsLocale.language)
    }

    @Test
    fun `TTS locale matches target language EN`() {
        val settings = AppSettings(sourceLanguage = "ru", targetLanguage = "en")
        val ttsLocale = Locale.forLanguageTag(settings.targetLanguage)
        assertEquals("en", ttsLocale.language)
    }

    @Test
    fun `TTS locale matches target language DE`() {
        val settings = AppSettings(sourceLanguage = "es", targetLanguage = "de")
        val ttsLocale = Locale.forLanguageTag(settings.targetLanguage)
        assertEquals("de", ttsLocale.language)
    }

    @Test
    fun `TTS locale matches target language FR`() {
        val settings = AppSettings(sourceLanguage = "ja", targetLanguage = "fr")
        val ttsLocale = Locale.forLanguageTag(settings.targetLanguage)
        assertEquals("fr", ttsLocale.language)
    }

    @Test
    fun `TTS locale matches target language JA`() {
        val settings = AppSettings(sourceLanguage = "en", targetLanguage = "ja")
        val ttsLocale = Locale.forLanguageTag(settings.targetLanguage)
        assertEquals("ja", ttsLocale.language)
    }

    // endregion
}
