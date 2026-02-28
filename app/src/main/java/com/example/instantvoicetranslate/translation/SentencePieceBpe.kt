package com.example.instantvoicetranslate.translation

import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Pure Kotlin SentencePiece BPE tokenizer.
 *
 * Parses a sentencepiece.bpe.model file (protobuf format) and implements
 * BPE encode/decode without any native libraries. Replaces DJL's SpProcessor
 * which doesn't include Android native binaries.
 */
class SentencePieceBpe {

    companion object {
        private const val TAG = "SentencePieceBpe"

        /** SentencePiece whitespace marker (U+2581 LOWER ONE EIGHTH BLOCK). */
        private const val SPACE_MARKER = '\u2581'
        private const val SPACE_MARKER_STR = "\u2581"
    }

    private data class VocabPiece(
        val piece: String,
        val score: Float,
        val type: Int, // 1=NORMAL, 2=UNKNOWN, 3=CONTROL, 4=USER_DEFINED, 5=UNUSED, 6=BYTE
    )

    private var pieces = emptyList<VocabPiece>()
    private var pieceToId = emptyMap<String, Int>()
    private var idToPiece = emptyMap<Int, String>()
    private var unkId = 0


    /**
     * Load vocabulary from a sentencepiece.bpe.model file.
     */
    fun loadModel(modelFile: File) {
        val bytes = modelFile.readBytes()
        pieces = parseModelProto(bytes)
        pieceToId = HashMap<String, Int>(pieces.size * 2).also { map ->
            pieces.forEachIndexed { index, p -> map[p.piece] = index }
        }
        idToPiece = HashMap<Int, String>(pieces.size * 2).also { map ->
            pieces.forEachIndexed { index, p -> map[index] = p.piece }
        }
        unkId = pieceToId["<unk>"] ?: 0
        Log.i(TAG, "Loaded ${pieces.size} pieces from ${modelFile.name}")
    }

    /**
     * Encode text to token IDs using BPE.
     */
    fun encode(text: String): IntArray {
        if (text.isEmpty()) return intArrayOf()

        // SentencePiece normalization: prepend ▁ and replace spaces with ▁
        val normalized = SPACE_MARKER_STR + text.replace(" ", SPACE_MARKER_STR)

        // Start with individual characters
        val symbols = ArrayList<String>(normalized.length)
        for (ch in normalized) {
            symbols.add(ch.toString())
        }

        // BPE: iteratively merge the highest-scoring adjacent pair
        while (symbols.size > 1) {
            var bestScore = Float.NEGATIVE_INFINITY
            var bestIdx = -1

            for (i in 0 until symbols.size - 1) {
                val merged = symbols[i] + symbols[i + 1]
                val id = pieceToId[merged]
                if (id != null) {
                    val score = pieces[id].score
                    if (score > bestScore) {
                        bestScore = score
                        bestIdx = i
                    }
                }
            }

            if (bestIdx == -1) break

            symbols[bestIdx] = symbols[bestIdx] + symbols[bestIdx + 1]
            symbols.removeAt(bestIdx + 1)
        }

        return IntArray(symbols.size) { i -> pieceToId[symbols[i]] ?: unkId }
    }

    /**
     * Decode token IDs back to text.
     */
    fun decode(ids: IntArray): String {
        val sb = StringBuilder()
        for (id in ids) {
            sb.append(idToPiece[id] ?: "")
        }
        return sb.toString()
            .replace(SPACE_MARKER, ' ')
            .trimStart()
    }

    fun close() {
        pieces = emptyList()
        pieceToId = emptyMap()
        idToPiece = emptyMap()
    }

    // ── Protobuf parsing ────────────────────────────────────────────────

    /**
     * Parse the top-level ModelProto message.
     * We only care about field 1 (repeated SentencePiece pieces).
     */
    private fun parseModelProto(data: ByteArray): List<VocabPiece> {
        val result = mutableListOf<VocabPiece>()
        val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)

        while (buf.hasRemaining()) {
            val tag = readVarint(buf)
            val fieldNumber = (tag shr 3).toInt()
            val wireType = (tag and 0x7).toInt()

            if (fieldNumber == 1 && wireType == 2) {
                // pieces: repeated SentencePiece (embedded message)
                val len = readVarint(buf).toInt()
                val pieceData = ByteArray(len)
                buf.get(pieceData)
                result.add(parseSentencePiece(pieceData))
            } else {
                skipField(buf, wireType)
            }
        }

        return result
    }

    /**
     * Parse a single SentencePiece sub-message.
     * Fields: 1=piece(string), 2=score(float), 3=type(enum).
     */
    private fun parseSentencePiece(data: ByteArray): VocabPiece {
        var piece = ""
        var score = 0f
        var type = 1 // NORMAL

        val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        while (buf.hasRemaining()) {
            val tag = readVarint(buf)
            val fieldNumber = (tag shr 3).toInt()
            val wireType = (tag and 0x7).toInt()

            when {
                fieldNumber == 1 && wireType == 2 -> {
                    val len = readVarint(buf).toInt()
                    val strBytes = ByteArray(len)
                    buf.get(strBytes)
                    piece = String(strBytes, Charsets.UTF_8)
                }
                fieldNumber == 2 && wireType == 5 -> {
                    score = buf.float
                }
                fieldNumber == 3 && wireType == 0 -> {
                    type = readVarint(buf).toInt()
                }
                else -> skipField(buf, wireType)
            }
        }

        return VocabPiece(piece, score, type)
    }

    private fun readVarint(buf: ByteBuffer): Long {
        var result = 0L
        var shift = 0
        while (buf.hasRemaining()) {
            val b = buf.get().toLong() and 0xFF
            result = result or ((b and 0x7F) shl shift)
            if (b and 0x80 == 0L) break
            shift += 7
        }
        return result
    }

    private fun skipField(buf: ByteBuffer, wireType: Int) {
        when (wireType) {
            0 -> readVarint(buf)
            1 -> buf.position(buf.position() + 8)
            2 -> {
                val len = readVarint(buf).toInt()
                buf.position(buf.position() + len)
            }
            5 -> buf.position(buf.position() + 4)
        }
    }
}
