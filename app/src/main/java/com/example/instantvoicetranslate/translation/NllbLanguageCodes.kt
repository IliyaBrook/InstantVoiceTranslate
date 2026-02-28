package com.example.instantvoicetranslate.translation

/**
 * Maps ISO 639-1 language codes used throughout the app to NLLB-200 language codes.
 * NLLB uses BCP-47-like codes with script suffix (e.g. "eng_Latn", "rus_Cyrl").
 */
object NllbLanguageCodes {

    private val ISO_TO_NLLB = mapOf(
        // Source + online target languages
        "en" to "eng_Latn",
        "ru" to "rus_Cyrl",
        "es" to "spa_Latn",
        "de" to "deu_Latn",
        "fr" to "fra_Latn",
        "zh" to "zho_Hans",
        "ko" to "kor_Hang",
        "ja" to "jpn_Jpan",
        "pt" to "por_Latn",
        "it" to "ita_Latn",
        "tr" to "tur_Latn",
        "ar" to "arb_Arab",
        "hi" to "hin_Deva",
        // Additional offline-only target languages
        "uk" to "ukr_Cyrl",
        "pl" to "pol_Latn",
        "nl" to "nld_Latn",
        "sv" to "swe_Latn",
        "cs" to "ces_Latn",
        "ro" to "ron_Latn",
        "hu" to "hun_Latn",
        "el" to "ell_Grek",
        "da" to "dan_Latn",
        "fi" to "fin_Latn",
        "no" to "nob_Latn",
        "th" to "tha_Thai",
        "vi" to "vie_Latn",
        "id" to "ind_Latn",
        "ms" to "zsm_Latn",
        "he" to "heb_Hebr",
        "bg" to "bul_Cyrl",
        "ka" to "kat_Geor",
    )

    fun toNllb(isoCode: String): String =
        ISO_TO_NLLB[isoCode] ?: error("Unsupported language code: $isoCode")

}
