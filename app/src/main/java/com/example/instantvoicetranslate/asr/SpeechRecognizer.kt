package com.example.instantvoicetranslate.asr

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

interface SpeechRecognizer {
    /** Live partial (unstable) recognition text. */
    val partialText: StateFlow<String>

    /** Emits finalized text segments when endpoint is detected. */
    val recognizedSegments: SharedFlow<String>

    /** Whether the recognizer model is loaded and ready. */
    val isReady: StateFlow<Boolean>

    /** The language the recognizer is currently configured for (ISO 639-1). */
    val currentLanguage: StateFlow<String>

    /** Load the model from the given directory for the specified source language. */
    suspend fun initialize(modelDir: String, language: String = "en")

    /** Start recognition, consuming audio from the given Flow. */
    fun startRecognition(audioFlow: Flow<FloatArray>)

    /** Stop ongoing recognition. */
    fun stopRecognition()

    /** Release all native resources. */
    fun release()
}
