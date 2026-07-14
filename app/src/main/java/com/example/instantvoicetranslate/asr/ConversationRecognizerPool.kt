package com.example.instantvoicetranslate.asr

import android.util.Log
import javax.inject.Singleton

/**
 * Keeps up to [MAX_WARM] ASR recognizers loaded simultaneously, keyed by
 * language, so swapping the conversation direction between two languages
 * doesn't pay the multi-second model-load cost every time.
 *
 * [SherpaOnnxRecognizer] has no shared/static native state — each instance
 * owns its own native handles — so holding several concurrently is safe;
 * this pool exists purely to bound memory by evicting the least-recently
 * acquired language once the cap is reached.
 */
@Singleton
class ConversationRecognizerPool @javax.inject.Inject constructor() {

    companion object {
        private const val TAG = "ConversationRecognizerPool"
        private const val MAX_WARM = 2
    }

    // Insertion order == recency: re-inserting a key on cache hit moves it to
    // the end, so the front entry is always the least-recently-used one.
    private val pool = LinkedHashMap<String, SherpaOnnxRecognizer>()

    suspend fun acquire(language: String, modelDir: String): SpeechRecognizer {
        pool[language]?.let { existing ->
            if (existing.isReady.value) {
                // Move to most-recently-used position.
                pool.remove(language)
                pool[language] = existing
                return existing
            }
            pool.remove(language)
        }

        if (pool.size >= MAX_WARM) {
            val lruLanguage = pool.keys.first()
            pool.remove(lruLanguage)?.release()
            Log.i(TAG, "Evicted warm recognizer for '$lruLanguage' to make room for '$language'")
        }

        val recognizer = SherpaOnnxRecognizer()
        recognizer.initialize(modelDir, language)
        pool[language] = recognizer
        return recognizer
    }

    fun isWarm(language: String): Boolean = pool[language]?.isReady?.value == true

    /**
     * Releases every warm recognizer except the most-recently-used one, in
     * response to a system memory-pressure signal (see
     * [InstantVoiceTranslateApp.onTrimMemory]). Keeping two ~30-190MB ASR
     * models plus NLLB (~1GB) resident can otherwise push a loaded device
     * over its memory watermark and get the whole process killed by the
     * Android low-memory killer instead of just this pool shrinking back to
     * one warm language.
     */
    fun trimToMostRecentlyUsed() {
        while (pool.size > 1) {
            val lruLanguage = pool.keys.first()
            pool.remove(lruLanguage)?.release()
            Log.i(TAG, "Trimmed warm recognizer for '$lruLanguage' due to memory pressure")
        }
    }

    fun releaseAll() {
        pool.values.forEach { it.release() }
        pool.clear()
    }
}
