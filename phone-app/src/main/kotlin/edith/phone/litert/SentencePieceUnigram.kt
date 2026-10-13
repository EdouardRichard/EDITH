// SPDX-License-Identifier: Apache-2.0
package edith.phone.litert

import java.io.File

/**
 * The part of SentencePiece this model needs: a **unigram** model with **identity** normalization.
 *
 * Read from the publisher's own `sentencepiece.model`, which is a protobuf, so this parses the three fields it needs
 * rather than shipping a second copy of the vocabulary in a friendlier format — the model file is already required at
 * runtime, and a derived asset would be another 4.7 MB to keep in step with it.
 *
 * **The normalization was measured, not assumed.** The obvious guess is NFKC with collapsed whitespace and a dummy
 * prefix; the oracle says otherwise, and all three differences are pinned by `EmbeddingTokenizerTest`:
 *
 * | input | publisher's pieces | so |
 * |---|---|---|
 * | `Hello World` | `Hello`, `▁World` | **no dummy prefix** |
 * | `  a   b  ` | `▁▁`, `a`, `▁▁▁`, `b`, `▁▁` | whitespace is **not** collapsed |
 * | `AＡ` | `A`, `Ａ` | full-width survives — **no NFKC** |
 *
 * So normalization is exactly "replace U+0020 with U+2581", and everything else is the unigram lattice.
 *
 * **Matching is byte-level.** SentencePiece's byte pieces are single raw bytes, and a lone `0xC2` is not a valid
 * UTF-8 `String`, so pieces are keyed by a **latin-1 rendering of their UTF-8 bytes**: one `Char` per byte, which is
 * lossless and collision-free for lookup, and lets the input be sliced at arbitrary byte offsets without ever
 * producing a replacement character mid-codepoint. Byte pieces are kept in their own table so that, say, the byte
 * `0x61` cannot shadow the real piece `a`.
 */
class SentencePieceUnigram private constructor(
    /** Each piece's UTF-8 bytes as one `Char` per byte; see the class comment. */
    private val keys: Array<String>,
    private val scores: FloatArray,
    private val byteIds: IntArray,
    /** `byteOf[id]` is the byte a byte-fallback piece stands for, or `-1` — the reverse of [byteIds]. */
    private val byteOf: IntArray,
    private val maxPieceBytes: Int,
) {

    /**
     * First occurrence wins, which is what "the vocabulary is a function" means: a duplicated surface form must
     * resolve to the same id on every run. `putIfAbsent` is deliberately avoided — it is a Java 8 default method and
     * this file also compiles for API 28 without relying on desugaring for it.
     */
    private val index: MutableMap<String, Int> = HashMap<String, Int>(keys.size * 2).also { map ->
        keys.forEachIndexed { id, key -> if (!map.containsKey(key)) map[key] = id }
    }

    val size: Int get() = keys.size

    /** The piece as text, for a human. Byte pieces render the way the publisher's own API renders them. */
    fun piece(id: Int): String =
        if (byteOf[id] >= 0) {
            "<0x%02X>".format(byteOf[id])
        } else {
            String(keys[id].toByteArray(Charsets.ISO_8859_1), Charsets.UTF_8)
        }

    /** The id of the byte-fallback piece for [value] (0..255), or `-1` if the model has none. */
    fun bytePiece(value: Int): Int = byteIds[value and 0xFF]

    /**
     * The piece's own unigram score, as the publisher wrote it.
     *
     * Note what these are **not**: they are not log-probabilities in any familiar range. Measured against the
     * publisher's API they run to −236280 for `▁` and exactly `0.0` for `▁▁`, the control pieces and `\t`. That is
     * the publisher's data and the Viterbi maximises it as given — reproducing their segmentation means reproducing
     * their scores, not replacing them with a nicer-looking distribution.
     */
    fun score(id: Int): Float = scores[id]

    /** Unigram Viterbi over the UTF-8 bytes, byte fallback included — the publisher's segmentation. */
    fun encode(text: String): IntArray {
        val bytes = text.replace(' ', '\u2581').toByteArray(Charsets.UTF_8)
        val n = bytes.size
        if (n == 0) return IntArray(0)

        val best = FloatArray(n + 1) { Float.NEGATIVE_INFINITY }
        val from = IntArray(n + 1)
        val via = IntArray(n + 1) { -1 }
        best[0] = 0f

        for (i in 0 until n) {
            if (best[i] == Float.NEGATIVE_INFINITY) continue
            val limit = minOf(maxPieceBytes, n - i)
            for (len in 1..limit) {
                val id = index[String(bytes, i, len, Charsets.ISO_8859_1)] ?: continue
                relax(best, from, via, i, i + len, id, scores[id])
            }
            // The lattice always contains the single-byte option; its own (low) score is what lets a longer piece
            // win whenever one exists, exactly as SentencePiece's does.
            val byteId = byteIds[bytes[i].toInt() and 0xFF]
            if (byteId >= 0) relax(best, from, via, i, i + 1, byteId, scores[byteId])
        }

        val out = IntArray(0)
        var at = n
        val reversed = ArrayList<Int>()
        while (at > 0 && via[at] >= 0) {
            reversed.add(via[at])
            at = from[at]
        }
        reversed.reverse()
        return if (reversed.isEmpty()) out else reversed.toIntArray()
    }

    private fun relax(best: FloatArray, from: IntArray, via: IntArray, i: Int, to: Int, id: Int, score: Float) {
        val candidate = best[i] + score
        if (candidate > best[to]) {
            best[to] = candidate
            from[to] = i
            via[to] = id
        }
    }

    /**
     * The ids the graph is actually fed: `<bos>`, the text, `<eos>`, padded to [sequenceLength].
     *
     * Truncation keeps the closing token — a frame with no `<eos>` is not a frame, and the graph was exported for a
     * fixed 256.
     */
    fun encodeForModel(text: String, sequenceLength: Int = 256): IntArray {
        val body = encode(text)
        val room = (sequenceLength - 2).coerceAtLeast(0)
        val kept = minOf(body.size, room)
        val out = IntArray(sequenceLength)
        if (sequenceLength == 0) return out
        out[0] = BOS
        for (k in 0 until kept) out[1 + k] = body[k]
        if (sequenceLength > 1) out[kept + 1] = EOS
        return out
    }

    companion object {
        const val PAD = 0
        const val EOS = 1
        const val BOS = 2

        /** SentencePiece's `SentencePiece.Type`: NORMAL=1, UNKNOWN=2, CONTROL=3, USER_DEFINED=4, UNUSED=5, BYTE=6. */
        private const val TYPE_BYTE = 6

        fun load(file: File): SentencePieceUnigram = fromModelProto(file.readBytes())

        fun fromModelProto(bytes: ByteArray): SentencePieceUnigram {
            val pieces = ArrayList<ByteArray>()
            val pieceScores = ArrayList<Float>()
            val pieceTypes = ArrayList<Int>()

            val reader = Proto(bytes, 0, bytes.size)
            while (reader.hasMore) {
                val tag = reader.varint()
                val field = (tag ushr 3).toInt()
                when (tag and 7L) {
                    0L -> reader.skipVarint()
                    1L -> reader.skip(8)
                    5L -> reader.skip(4)
                    2L -> {
                        val len = reader.varint().toInt()
                        val start = reader.position
                        if (field == 1) {
                            var piece = ByteArray(0)
                            var score = 0f
                            var type = 1
                            val sub = Proto(bytes, start, start + len)
                            while (sub.hasMore) {
                                val subTag = sub.varint()
                                when (subTag ushr 3) {
                                    1L -> piece = sub.lengthDelimited()
                                    2L -> score = sub.float32()
                                    3L -> type = sub.varint().toInt()
                                    else -> when (subTag and 7L) {
                                        0L -> sub.skipVarint()
                                        5L -> sub.skip(4)
                                        1L -> sub.skip(8)
                                        2L -> sub.skip(sub.varint().toInt())
                                        else -> error("unsupported wire type ${subTag and 7L}")
                                    }
                                }
                            }
                            pieces.add(piece)
                            pieceScores.add(score)
                            pieceTypes.add(type)
                        }
                        reader.position = start + len
                    }

                    else -> error("unsupported wire type ${tag and 7L}")
                }
            }

            require(pieces.isNotEmpty()) { "not a SentencePiece model: no pieces" }

            val byteIds = IntArray(256) { -1 }
            val byteOf = IntArray(pieces.size) { -1 }
            var maxBytes = 1
            pieces.forEachIndexed { id, raw ->
                if (pieceTypes[id] == TYPE_BYTE && raw.size == 1) {
                    val value = raw[0].toInt() and 0xFF
                    byteIds[value] = id
                    byteOf[id] = value
                } else if (raw.size > maxBytes) {
                    maxBytes = raw.size
                }
            }

            return SentencePieceUnigram(
                keys = Array(pieces.size) { String(pieces[it], Charsets.ISO_8859_1) },
                scores = FloatArray(pieceScores.size) { pieceScores[it] },
                byteIds = byteIds,
                byteOf = byteOf,
                maxPieceBytes = maxBytes,
            )
        }
    }

    /**
     * A protobuf reader for the two messages this needs.
     *
     * Hand-rolled because the alternative is a code generator for three fields: `ModelProto.pieces` (field 1) and
     * `SentencePiece.{piece,score,type}` (fields 1, 2, 3). The wire format is the published one.
     */
    private class Proto(val bytes: ByteArray, var position: Int, val end: Int) {
        val hasMore: Boolean get() = position < end

        fun varint(): Long {
            var result = 0L
            var shift = 0
            while (shift < 64) {
                val b = bytes[position++].toInt()
                result = result or ((b and 0x7F).toLong() shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
            }
            error("varint too long")
        }

        fun skipVarint() = varint()

        fun skip(count: Int) {
            position += count
        }

        fun float32(): Float {
            val bits = (bytes[position].toInt() and 0xFF) or
                ((bytes[position + 1].toInt() and 0xFF) shl 8) or
                ((bytes[position + 2].toInt() and 0xFF) shl 16) or
                ((bytes[position + 3].toInt() and 0xFF) shl 24)
            position += 4
            return Float.fromBits(bits)
        }

        fun lengthDelimited(): ByteArray {
            val len = varint().toInt()
            val out = bytes.copyOfRange(position, position + len)
            position += len
            return out
        }
    }
}
