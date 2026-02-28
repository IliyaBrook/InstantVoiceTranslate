package com.example.instantvoicetranslate.translation

import android.util.Log
import org.json.JSONObject
import java.io.File

/**
 * SentencePiece tokenizer wrapper for NLLB-200 model.
 *
 * Handles tokenization with NLLB-specific language code tokens.
 * Language tokens (e.g. "eng_Latn", "rus_Cyrl") are parsed from
 * tokenizer_config.json at initialization time.
 */
class NllbTokenizer {

    companion object {
        private const val TAG = "NllbTokenizer"

        /** EOS / decoder-start token. */
        const val EOS_TOKEN_ID = 2L
    }

    private var processor: SentencePieceBpe? = null

    /** NLLB language code → token ID, parsed from tokenizer_config.json. */
    private val langTokenIds = mutableMapOf<String, Long>()

    val isInitialized: Boolean get() = processor != null

    /**
     * Load the SentencePiece model and parse language token IDs.
     * @param modelDir directory containing sentencepiece.bpe.model and tokenizer_config.json
     */
    fun initialize(modelDir: File) {
        val spmFile = File(modelDir, "sentencepiece.bpe.model")
        require(spmFile.exists()) { "SentencePiece model not found: ${spmFile.absolutePath}" }

        // Pure Kotlin BPE tokenizer — no native libraries needed
        val sp = SentencePieceBpe()
        sp.loadModel(spmFile)
        processor = sp
        Log.i(TAG, "SentencePiece model loaded")

        // Parse language token IDs from tokenizer_config.json
        val configFile = File(modelDir, "tokenizer_config.json")
        if (configFile.exists()) {
            parseLangTokenIds(configFile)
        } else {
            Log.w(TAG, "tokenizer_config.json not found, using fallback language token IDs")
            loadFallbackLangTokenIds()
        }

        Log.i(TAG, "Initialized with ${langTokenIds.size} language tokens")
    }

    /**
     * Encode text for NLLB model input.
     * Format: source_lang_token_id + tokenized_text_ids + EOS_TOKEN_ID
     */
    fun encode(text: String, sourceLang: String): LongArray {
        val sp = processor ?: error("Tokenizer not initialized")
        val nllbCode = NllbLanguageCodes.toNllb(sourceLang)
        val langTokenId = langTokenIds[nllbCode]
            ?: error("Language token not found for: $nllbCode")

        val tokenIds = sp.encode(text)
        // Prepend source language token, append EOS
        return longArrayOf(langTokenId) +
                tokenIds.map { it.toLong() }.toLongArray() +
                longArrayOf(EOS_TOKEN_ID)
    }

    /**
     * Decode token IDs back to text, stripping special tokens.
     */
    fun decode(tokenIds: LongArray): String {
        val sp = processor ?: error("Tokenizer not initialized")
        // Filter out special tokens (EOS, language tokens, etc.)
        val filtered = tokenIds.filter { id ->
            id != EOS_TOKEN_ID && id !in langTokenIds.values
        }
        if (filtered.isEmpty()) return ""
        return sp.decode(filtered.map { it.toInt() }.toIntArray())
    }

    /**
     * Get the token ID for a target language (used as forced BOS in decoder).
     */
    fun getTargetLangTokenId(targetLang: String): Long {
        val nllbCode = NllbLanguageCodes.toNllb(targetLang)
        return langTokenIds[nllbCode]
            ?: error("Language token not found for: $nllbCode")
    }

    fun release() {
        processor?.close()
        processor = null
        langTokenIds.clear()
    }

    /**
     * Parse the added_tokens_decoder section of tokenizer_config.json
     * to extract language code → token ID mappings.
     */
    private fun parseLangTokenIds(configFile: File) {
        try {
            val json = JSONObject(configFile.readText())
            val addedTokens = json.optJSONObject("added_tokens_decoder")

            if (addedTokens != null) {
                val langCodePattern = Regex("^[a-z]{3}_[A-Z][a-z]{3}$")
                val keys = addedTokens.keys()
                while (keys.hasNext()) {
                    val idStr = keys.next()
                    val tokenObj = addedTokens.getJSONObject(idStr)
                    val content = tokenObj.getString("content")
                    if (langCodePattern.matches(content)) {
                        langTokenIds[content] = idStr.toLong()
                    }
                }
            }

            if (langTokenIds.isEmpty()) {
                Log.w(TAG, "No language tokens parsed from tokenizer_config.json, using fallback")
                loadFallbackLangTokenIds()
            } else {
                Log.i(TAG, "Parsed ${langTokenIds.size} language tokens from tokenizer_config.json")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse tokenizer_config.json, using fallback", e)
            loadFallbackLangTokenIds()
        }
    }

    /**
     * Hardcoded fallback IDs for the 13 languages used in the app.
     * These are for NLLB-200-distilled-600M (Xenova conversion).
     */
    private fun loadFallbackLangTokenIds() {
        langTokenIds.clear()
        langTokenIds.putAll(
            mapOf(
                "arb_Arab" to 256001L,
                "deu_Latn" to 256019L,
                "eng_Latn" to 256047L,
                "fra_Latn" to 256057L,
                "hin_Deva" to 256075L,
                "ita_Latn" to 256090L,
                "jpn_Jpan" to 256096L,
                "kor_Hang" to 256102L,
                "por_Latn" to 256139L,
                "rus_Cyrl" to 256145L,
                "spa_Latn" to 256161L,
                "tur_Latn" to 256170L,
                "zho_Hans" to 256200L,
            )
        )
    }
}
