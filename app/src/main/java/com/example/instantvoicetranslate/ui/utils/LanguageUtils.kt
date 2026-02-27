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

    /** Target languages available with online translation (Yandex API). */
    val onlineTargetLanguages: List<Pair<String, String>> = sourceLanguages + listOf(
        "ja" to "Japanese",
        "pt" to "Portuguese",
        "it" to "Italian",
        "tr" to "Turkish",
        "ar" to "Arabic",
        "hi" to "Hindi",
    )

    /**
     * Target languages available with offline translation (NLLB-200).
     * NLLB supports 200+ languages; here we include the most popular ones
     * beyond the standard online set.
     */
    val offlineTargetLanguages: List<Pair<String, String>> = onlineTargetLanguages + listOf(
        "uk" to "Ukrainian",
        "pl" to "Polish",
        "nl" to "Dutch",
        "sv" to "Swedish",
        "cs" to "Czech",
        "ro" to "Romanian",
        "hu" to "Hungarian",
        "el" to "Greek",
        "da" to "Danish",
        "fi" to "Finnish",
        "no" to "Norwegian",
        "th" to "Thai",
        "vi" to "Vietnamese",
        "id" to "Indonesian",
        "ms" to "Malay",
        "he" to "Hebrew",
        "bg" to "Bulgarian",
        "ka" to "Georgian",
    )

    /** Backward-compatible alias — returns online target languages. */
    val targetLanguages: List<Pair<String, String>> get() = onlineTargetLanguages

    /** Returns the appropriate target language list based on the current mode. */
    fun targetLanguagesForMode(offlineMode: Boolean): List<Pair<String, String>> =
        if (offlineMode) offlineTargetLanguages else onlineTargetLanguages

    private val allNames: Map<String, String> =
        (offlineTargetLanguages + sourceLanguages).toMap()

    fun displayName(code: String): String = allNames[code] ?: code.uppercase()
}
