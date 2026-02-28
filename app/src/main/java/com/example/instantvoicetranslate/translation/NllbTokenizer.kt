package com.example.instantvoicetranslate.translation

import android.util.Log
import java.io.File

/**
 * SentencePiece tokenizer wrapper for NLLB-200 model.
 *
 * Handles tokenization with NLLB-specific language code tokens.
 * Language token IDs are computed dynamically from the SentencePiece vocabulary
 * size and the canonical FAIRSEQ_LANGUAGE_CODES ordering used by NLLB-200.
 *
 * Token ID formula: sp_model_size + FAIRSEQ_OFFSET + language_index
 * where language_index is the 0-based position in FAIRSEQ_LANGUAGE_CODES.
 */
class NllbTokenizer {

    companion object {
        private const val TAG = "NllbTokenizer"

        /** EOS / decoder-start token (in model's fairseq vocabulary). */
        const val EOS_TOKEN_ID = 2L

        /**
         * Fairseq vocabulary offset for NLLB models.
         *
         * NLLB uses fairseq vocabulary ordering where:
         *   model_token_id = sentencepiece_id + FAIRSEQ_OFFSET
         *
         * This is because the fairseq vocabulary inserts `<pad>` (id=1) which
         * shifts all BPE tokens by 1 compared to the raw SentencePiece model.
         *
         * Special tokens are handled separately:
         *   <s>=0, <pad>=1, </s>=2, <unk>=3 (model IDs)
         *   <unk>=0, <s>=1, </s>=2 (SentencePiece IDs)
         */
        private const val FAIRSEQ_OFFSET = 1

        /** Model token ID for unknown token. */
        private const val UNK_MODEL_ID = 3L

        /**
         * Complete ordered list of 202 NLLB language codes from fairseq.
         * Source: facebook/nllb-200-distilled-600M tokenizer (FAIRSEQ_LANGUAGE_CODES).
         *
         * Language token IDs are assigned sequentially starting after the
         * SentencePiece vocabulary: id = sp_model_size + FAIRSEQ_OFFSET + index.
         *
         * DO NOT reorder — the index determines the token ID.
         */
        private val FAIRSEQ_LANGUAGE_CODES = arrayOf(
            "ace_Arab", "ace_Latn", "acm_Arab", "acq_Arab", "aeb_Arab",
            "afr_Latn", "ajp_Arab", "aka_Latn", "amh_Ethi", "apc_Arab",
            "arb_Arab", "ars_Arab", "ary_Arab", "arz_Arab", "asm_Beng",  // 10: arb_Arab
            "ast_Latn", "awa_Deva", "ayr_Latn", "azb_Arab", "azj_Latn",
            "bak_Cyrl", "bam_Latn", "ban_Latn", "bel_Cyrl", "bem_Latn",
            "ben_Beng", "bho_Deva", "bjn_Arab", "bjn_Latn", "bod_Tibt",
            "bos_Latn", "bug_Latn", "bul_Cyrl", "cat_Latn", "ceb_Latn",  // 32: bul_Cyrl
            "ces_Latn", "cjk_Latn", "ckb_Arab", "crh_Latn", "cym_Latn",  // 35: ces_Latn
            "dan_Latn", "deu_Latn", "dik_Latn", "dyu_Latn", "dzo_Tibt",  // 40: dan_Latn, 41: deu_Latn
            "ell_Grek", "eng_Latn", "epo_Latn", "est_Latn", "eus_Latn",  // 45: ell_Grek, 46: eng_Latn
            "ewe_Latn", "fao_Latn", "pes_Arab", "fij_Latn", "fin_Latn",  // 54: fin_Latn
            "fon_Latn", "fra_Latn", "fur_Latn", "fuv_Latn", "gla_Latn",  // 56: fra_Latn
            "gle_Latn", "glg_Latn", "grn_Latn", "guj_Gujr", "hat_Latn",
            "hau_Latn", "heb_Hebr", "hin_Deva", "hne_Deva", "hrv_Latn",  // 66: heb_Hebr, 67: hin_Deva
            "hun_Latn", "hye_Armn", "ibo_Latn", "ilo_Latn", "ind_Latn",  // 70: hun_Latn, 74: ind_Latn
            "isl_Latn", "ita_Latn", "jav_Latn", "jpn_Jpan", "kab_Latn",  // 76: ita_Latn, 78: jpn_Jpan
            "kac_Latn", "kam_Latn", "kan_Knda", "kas_Arab", "kas_Deva",
            "kat_Geor", "knc_Arab", "knc_Latn", "kaz_Cyrl", "kbp_Latn",  // 85: kat_Geor
            "kea_Latn", "khm_Khmr", "kik_Latn", "kin_Latn", "kir_Cyrl",
            "kmb_Latn", "kon_Latn", "kor_Hang", "kmr_Latn", "lao_Laoo", "lvs_Latn",  // 97: kor_Hang, 98: kmr_Latn
            "lij_Latn", "lim_Latn", "lin_Latn", "lit_Latn", "lmo_Latn",
            "ltg_Latn", "ltz_Latn", "lua_Latn", "lug_Latn", "luo_Latn",
            "lus_Latn", "mag_Deva", "mai_Deva", "mal_Mlym", "mar_Deva",
            "min_Latn", "mkd_Cyrl", "plt_Latn", "mlt_Latn", "mni_Beng",
            "khk_Cyrl", "mos_Latn", "mri_Latn", "zsm_Latn", "mya_Mymr",  // 124: zsm_Latn
            "nld_Latn", "nno_Latn", "nob_Latn", "npi_Deva", "nso_Latn",  // 126: nld_Latn, 128: nob_Latn
            "nus_Latn", "nya_Latn", "oci_Latn", "gaz_Latn", "ory_Orya",
            "pag_Latn", "pan_Guru", "pap_Latn", "pol_Latn", "por_Latn",  // 139: pol_Latn, 140: por_Latn
            "prs_Arab", "pbt_Arab", "quy_Latn", "ron_Latn", "run_Latn",  // 144: ron_Latn
            "rus_Cyrl", "sag_Latn", "san_Deva", "sat_Beng", "scn_Latn",  // 146: rus_Cyrl
            "shn_Mymr", "sin_Sinh", "slk_Latn", "slv_Latn", "smo_Latn",
            "sna_Latn", "snd_Arab", "som_Latn", "sot_Latn", "spa_Latn",  // 160: spa_Latn
            "als_Latn", "srd_Latn", "srp_Cyrl", "ssw_Latn", "sun_Latn",
            "swe_Latn", "swh_Latn", "szl_Latn", "tam_Taml", "tat_Cyrl",  // 166: swe_Latn
            "tel_Telu", "tgk_Cyrl", "tgl_Latn", "tha_Thai", "tir_Ethi",  // 174: tha_Thai
            "taq_Latn", "taq_Tfng", "tpi_Latn", "tsn_Latn", "tso_Latn",
            "tuk_Latn", "tum_Latn", "tur_Latn", "twi_Latn", "tzm_Tfng",  // 183: tur_Latn
            "uig_Arab", "ukr_Cyrl", "umb_Latn", "urd_Arab", "uzn_Latn",  // 187: ukr_Cyrl
            "vec_Latn", "vie_Latn", "war_Latn", "wol_Latn", "xho_Latn",  // 192: vie_Latn
            "ydd_Hebr", "yor_Latn", "yue_Hant", "zho_Hans", "zho_Hant",  // 199: zho_Hans
            "zul_Latn",
        )
    }

    private var processor: SentencePieceBpe? = null

    /** NLLB language code → model token ID, computed from SPM vocab size. */
    private val langTokenIds = mutableMapOf<String, Long>()

    val isInitialized: Boolean get() = processor != null

    /**
     * Load the SentencePiece model and compute language token IDs.
     * @param modelDir directory containing sentencepiece.bpe.model
     */
    fun initialize(modelDir: File) {
        val spmFile = File(modelDir, "sentencepiece.bpe.model")
        require(spmFile.exists()) { "SentencePiece model not found: ${spmFile.absolutePath}" }

        // Pure Kotlin BPE tokenizer — no native libraries needed
        val sp = SentencePieceBpe()
        sp.loadModel(spmFile)
        processor = sp
        Log.i(TAG, "SentencePiece model loaded, vocab size: ${sp.vocabSize}")

        // Compute language token IDs from SPM vocabulary size.
        // Formula: lang_token_id = sp_vocab_size + FAIRSEQ_OFFSET + index_in_FAIRSEQ_LANGUAGE_CODES
        // For NLLB-200-distilled-600M: sp_vocab_size=256003, so first lang token = 256004.
        computeLangTokenIds(sp.vocabSize)

        Log.i(TAG, "Initialized with ${langTokenIds.size} language tokens " +
                "(first lang token base: ${sp.vocabSize.toLong() + FAIRSEQ_OFFSET})")
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

        val spmIds = sp.encode(text)
        // Convert SentencePiece IDs → model token IDs with fairseq offset.
        // SPM <unk> (id=0) → model <unk> (id=3); all others: spm_id + 1
        val modelIds = LongArray(spmIds.size) { i ->
            val spmId = spmIds[i]
            if (spmId == 0) UNK_MODEL_ID else (spmId + FAIRSEQ_OFFSET).toLong()
        }
        // Prepend source language token, append EOS
        return longArrayOf(langTokenId) + modelIds + longArrayOf(EOS_TOKEN_ID)
    }

    /**
     * Decode token IDs back to text, stripping special tokens.
     */
    fun decode(tokenIds: LongArray): String {
        val sp = processor ?: error("Tokenizer not initialized")
        // Filter out special tokens: EOS, BOS/PAD/UNK (ids 0-3), language tokens
        val filtered = tokenIds.filter { id ->
            id > UNK_MODEL_ID && id !in langTokenIds.values
        }
        if (filtered.isEmpty()) return ""
        // Convert model token IDs → SentencePiece IDs (reverse fairseq offset)
        return sp.decode(filtered.map { (it - FAIRSEQ_OFFSET).toInt() }.toIntArray())
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
     * Compute language token IDs from the SentencePiece vocabulary size
     * and the canonical FAIRSEQ_LANGUAGE_CODES ordering.
     *
     * In NLLB's fairseq vocabulary, language tokens are appended after all
     * BPE tokens (shifted by FAIRSEQ_OFFSET for the extra <pad> token):
     *   lang_token_id = sp_vocab_size + FAIRSEQ_OFFSET + index
     *
     * For NLLB-200-distilled-600M (sp_vocab_size=256003):
     *   arb_Arab (index 10) → 256003 + 1 + 10 = 256014
     *   eng_Latn (index 46) → 256003 + 1 + 46 = 256050
     *   rus_Cyrl (index 146) → 256003 + 1 + 146 = 256150
     */
    private fun computeLangTokenIds(spVocabSize: Int) {
        langTokenIds.clear()
        val base = spVocabSize.toLong() + FAIRSEQ_OFFSET

        FAIRSEQ_LANGUAGE_CODES.forEachIndexed { index, langCode ->
            langTokenIds[langCode] = base + index
        }

        Log.d(TAG, "Computed language token IDs: " +
                "eng_Latn=${langTokenIds["eng_Latn"]}, " +
                "rus_Cyrl=${langTokenIds["rus_Cyrl"]}, " +
                "zho_Hans=${langTokenIds["zho_Hans"]}")
    }
}
