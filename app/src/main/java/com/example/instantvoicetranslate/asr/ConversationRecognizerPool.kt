package com.example.instantvoicetranslate.asr

import android.util.Log
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

    // Guards `pool` against concurrent acquire()/reconcile() calls. Without
    // this, a swap (which pre-warms the new source directly from
    // MainViewModel while the pipeline restart's own acquire() call races it
    // from TranslationService) could both see the language missing and each
    // build+initialize their own SherpaOnnxRecognizer, with the second
    // write silently orphaning the first -- observed as a crashed pipeline
    // (JobCancellationException + a subsequent AudioRecord IllegalStateException
    // from the colliding native lifecycles).
    private val mutex = Mutex()

    suspend fun acquire(language: String, modelDir: String): SpeechRecognizer = mutex.withLock {
        pool[language]?.let { existing ->
            if (existing.isReady.value) {
                // Move to most-recently-used position.
                pool.remove(language)
                pool[language] = existing
                return@withLock existing
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
        recognizer
    }

    fun isWarm(language: String): Boolean = pool[language]?.isReady?.value == true

    /**
     * Releases any warm recognizer whose language is not in [desiredLanguages].
     *
     * Settings can change the active source/target language (via the
     * Settings screen or the swap button) without ever releasing whatever
     * was warmed under the old settings, since neither of those call sites
     * knows what's still relevant. Left unchecked, a stale recognizer from
     * before the change stays resident indefinitely alongside the new one --
     * on top of NLLB (~1GB in offline mode) this was observed pushing a
     * loaded device over its memory watermark and getting the whole process
     * killed. Call this right before a translation session actually starts,
     * when the true set of languages worth keeping warm is known.
     */
    suspend fun reconcile(desiredLanguages: Set<String>) = mutex.withLock {
        val stale = pool.keys.filterNot { it in desiredLanguages }
        stale.forEach { language ->
            pool.remove(language)?.release()
            Log.i(TAG, "Released stale warm recognizer for '$language', no longer relevant to current settings")
        }
    }

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
