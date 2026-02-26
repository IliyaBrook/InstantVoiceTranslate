package com.example.instantvoicetranslate.translation

import kotlinx.coroutines.flow.StateFlow

interface TextTranslator {
    suspend fun translate(text: String, from: String, to: String): Result<String>
    val isAvailable: StateFlow<Boolean>
}
