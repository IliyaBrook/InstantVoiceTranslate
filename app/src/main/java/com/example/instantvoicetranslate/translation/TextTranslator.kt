package com.example.instantvoicetranslate.translation

import kotlinx.coroutines.flow.StateFlow

interface TextTranslator {
    /**
     * Translates [text] from [from] language to [to] language.
     * @param previousTranslation optional previous translated segment for context continuity.
     */
    suspend fun translate(
        text: String,
        from: String,
        to: String,
        previousTranslation: String? = null
    ): Result<String>

    val isAvailable: StateFlow<Boolean>
}
