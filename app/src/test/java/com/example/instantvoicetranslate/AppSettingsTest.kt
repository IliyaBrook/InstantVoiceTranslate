package com.example.instantvoicetranslate

import com.example.instantvoicetranslate.data.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Tests verifying AppSettings default values and language code handling.
 */
class AppSettingsTest {

    @Test
    fun `default source language is EN`() {
        val settings = AppSettings()
        assertEquals("en", settings.sourceLanguage)
    }

    @Test
    fun `default target language is RU`() {
        val settings = AppSettings()
        assertEquals("ru", settings.targetLanguage)
    }

    @Test
    fun `custom source language is preserved`() {
        val settings = AppSettings(sourceLanguage = "ja")
        assertEquals("ja", settings.sourceLanguage)
    }

    @Test
    fun `custom target language is preserved`() {
        val settings = AppSettings(targetLanguage = "fr")
        assertEquals("fr", settings.targetLanguage)
    }

    @Test
    fun `source and target can be different non-default languages`() {
        val settings = AppSettings(sourceLanguage = "zh", targetLanguage = "ko")
        assertEquals("zh", settings.sourceLanguage)
        assertEquals("ko", settings.targetLanguage)
    }

    @Test
    fun `source and target languages are independent`() {
        val settings = AppSettings(sourceLanguage = "es", targetLanguage = "de")
        assertNotEquals(settings.sourceLanguage, settings.targetLanguage)
        assertEquals("es", settings.sourceLanguage)
        assertEquals("de", settings.targetLanguage)
    }

    @Test
    fun `all supported language codes are valid ISO 639-1`() {
        val supportedLanguages = listOf("en", "ru", "es", "de", "fr", "zh", "ja", "ko")
        supportedLanguages.forEach { code ->
            assertEquals(
                "Language code '$code' should be 2 characters",
                2,
                code.length
            )
        }
    }

    @Test
    fun `changing only source language preserves target language`() {
        val original = AppSettings(sourceLanguage = "en", targetLanguage = "ru")
        val modified = original.copy(sourceLanguage = "de")
        assertEquals("de", modified.sourceLanguage)
        assertEquals("ru", modified.targetLanguage)
    }

    @Test
    fun `changing only target language preserves source language`() {
        val original = AppSettings(sourceLanguage = "en", targetLanguage = "ru")
        val modified = original.copy(targetLanguage = "ja")
        assertEquals("en", modified.sourceLanguage)
        assertEquals("ja", modified.targetLanguage)
    }
}
