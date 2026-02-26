package com.example.instantvoicetranslate.ui.utils

object LanguageUtils {

    val sourceLanguages: List<Pair<String, String>> = listOf(
        "en" to "English",
        "ru" to "Russian",
        "es" to "Spanish",
        "de" to "German",
        "fr" to "French",
        "zh" to "Chinese",
        "ko" to "Korean",
    )

    val targetLanguages: List<Pair<String, String>> = sourceLanguages + listOf(
        "ja" to "Japanese",
        "pt" to "Portuguese",
        "it" to "Italian",
        "tr" to "Turkish",
        "ar" to "Arabic",
        "hi" to "Hindi",
    )

    private val allNames: Map<String, String> = targetLanguages.toMap()

    fun displayName(code: String): String = allNames[code] ?: code.uppercase()
}
