// SPDX-License-Identifier: Apache-2.0
package edith.phone.litert

import java.io.File

/**
 * The part of SentencePiece this model needs: a **BPE** encoder with **identity** normalization.
 *
 * Read from the publisher's own `sentencepiece.model`, which is a protobuf, so this parses the three fields it needs
 * rather than shipping a second copy of the vocabulary in a friendlier format — the model file is already required at
 * runtime, and a derived asset would be another 4.7 MB to keep in step with it.
 *
 * ## This model is BPE, not unigram
 *
 * `TrainerSpec.model_type == 2` = **BPE**, so sentencepiece never runs its `unigram_model.cc` on this checkpoint; it
 * runs `bpe_model.cc`. The evidence is `docs/project/evidence/2026-10-13-embeddinggemma-tokenizer-is-bpe.md`. That is
 * the whole reason this class is called `...Bpe` and not `...Unigram`: the earlier name described an algorithm the
 * publisher does not use, and the mismatch is not cosmetic — a Viterbi over these scores reproduces only 19 of 68
 * strings, while this encoder reproduces 68 of 69.
 *
 * The consequence is visible in the score table: **`score` is not a log-probability, it is the merge rank.** For
 * 236249 of the 262144 pieces `score == 494 - id` holds exactly (`494 → -0.0`, `500 → -6.0`, `9259 → -8765`,
 * `41856 → -41362`), which is why ranking beats summing. `EDIT` stays one piece although `ED` + `IT` "score better"
 * — under BPE that comparison is not the question.
 *
 * ## The algorithm, as `bpe_model.cc` defines it
 *
 * 1. one symbol per character (this model has no `UNUSED` pieces, so no longer pre-match is possible)
 * 2. queue every adjacent pair whose concatenation is a real piece; a pair's priority is that piece's score, and a
 *    tie goes to the **smaller left index**
 * 3. pop the best pair, merge it, and queue the two pairs that become adjacent
 * 4. emit the survivors in order
 *
 * ## The normalization was measured, not assumed
 *
 * | input | publisher's pieces | so |
 * |---|---|---|
 * | `Hello World` | `Hello`, `▁World` | **no dummy prefix** |
 * | `  a   b  ` | `▁▁`, `a`, `▁▁▁`, `b`, `▁▁` | whitespace is **not** collapsed |
 * | `AＡ` | `A`, `Ａ` | full-width survives — **no NFKC** |
 *
 * So normalization is exactly "replace U+0020 with U+2581", and everything else is the merge lattice.
 *
 * ## Byte fallback
 *
 * `byte_fallback = 1` and the byte pieces exist as the six-character surface `<0xNN>` (ids 238..493, score 0.0), so
 * a byte is its own piece name rather than a raw byte — which is why the earlier `raw.size == 1` test never fired.
 * The fallback is **per UTF-8 byte and only for a character with no piece at all**: `\u00a0` becomes `<0xC2>`,
 * `<0xA0>`, while `\U0001F600` is a real piece and is never exploded.
 */
class SentencePieceBpe private constructor(
    private val pieces: Array<String>,
    private val scores: FloatArray,
    /** `byteOf[id]` is the byte a byte-fallback piece stands for, or `-1`. */
    private val byteOf: IntArray,
) {

    /**
     * First occurrence wins, which is what "the vocabulary is a function" means: a duplicated surface form must
     * resolve to the same id on every run. `putIfAbsent` is deliberately avoided — it is a Java 8 default method and
     * this file also compiles for API 28 without relying on desugaring for it.
     */
    private val index: MutableMap<String, Int> = HashMap<String, Int>(pieces.size * 2).also { map ->
        pieces.forEachIndexed { id, key -> if (!map.containsKey(key)) map[key] = id }
    }

    val size: Int get() = pieces.size

    /** The piece as text. Byte pieces render the way the publisher's own API renders them. */
    fun piece(id: Int): String = pieces[id]

    /** The id of the byte-fallback piece for [value] (0..255), or `-1` if the model has none. */
    fun bytePiece(value: Int): Int {
        val wanted = value and 0xFF
        for (id in byteOf.indices) {
            if (byteOf[id] == wanted) return id
        }
        return -1
    }

    /** The piece's own score, as the publisher wrote it — a merge rank, not a probability. See the class comment. */
    fun score(id: Int): Float = scores[id]

    /** BPE segmentation of [text] — the publisher's own. */
    fun encode(text: String): IntArray {
        val normalized = text.replace(' ', '\u2581')
        if (normalized.isEmpty()) return IntArray(0)

        val symbol = ArrayList<String>(normalized.length)
        // A character with no piece at all becomes its UTF-8 bytes, one byte piece each.
        for (ch in normalized) {
            val c = ch.toString()
            if (index.containsKey(c)) {
                symbol.add(c)
            } else {
                val bytes = c.toByteArray(Charsets.UTF_8)
                if (bytes.size <= 1) {
                    symbol.add(c)
                } else {
                    for (b in bytes) symbol.add(bytePieceName(b.toInt() and 0xFF))
                }
            }
        }

        val n = symbol.size
        if (n == 0) return IntArray(0)

        // Linked list over the symbols: `next[i] == -1` marks a merged-away symbol's removal.
        val next = IntArray(n) { if (it + 1 < n) it + 1 else -1 }
        val prev = IntArray(n) { it - 1 }

        // Max-heap keyed by (score, -left) so the comparator is a plain Comparable pair: higher score wins, and on a
        // tie the SMALLER left index wins, which is bpe_model.cc's `h1.left > h2.left` rule.
        val agenda = java.util.PriorityQueue<Candidate>(compareByDescending<Candidate> { it.score }.thenBy { it.left })

        fun push(left: Int) {
            if (left < 0) return
            val right = next[left]
            if (right < 0) return
            val merged = symbol[left] + symbol[right]
            val id = index[merged] ?: return
            if (id in RESERVED) return
            agenda.add(Candidate(scores[id], left, right, merged, id))
        }

        for (i in 0 until n - 1) push(i)

        while (agenda.isNotEmpty()) {
            val top = agenda.poll()
            // Stale entries are skipped the way the publisher does: the recorded span must still be the live one.
            if (symbol[top.left].isEmpty() || symbol[top.right].isEmpty()) continue
            if (next[top.left] != top.right) continue
            if (symbol[top.left] + symbol[top.right] != top.merged) continue

            symbol[top.left] = top.merged
            next[top.left] = next[top.right]
            if (next[top.right] >= 0) prev[next[top.right]] = top.left
            symbol[top.right] = ""

            push(prev[top.left])
            push(top.left)
        }

        val out = ArrayList<Int>(n)
        var at = 0
        while (at >= 0 && at < n) {
            if (symbol[at].isNotEmpty()) out.add(index[symbol[at]] ?: UNK)
            at = next[at]
        }
        return out.toIntArray()
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

    private data class Candidate(
        val score: Float,
        val left: Int,
        val right: Int,
        val merged: String,
        val id: Int,
    )

    companion object {
        const val PAD = 0
        const val EOS = 1
        const val BOS = 2
        const val UNK = 3

        /** SentencePiece's `SentencePiece.Type`: NORMAL=1, UNKNOWN=2, CONTROL=3, USER_DEFINED=4, UNUSED=5, BYTE=6. */
        private const val TYPE_BYTE = 6

        /**
         * Pieces that are never a merge *result*: the special tokens. `bpe_model.cc` keeps these out of the merge
         * agenda through its reserved-id map.
         */
        private val RESERVED = setOf(0, 1, 2, 3, 4)

        /** `<0xC2>` — how sentencepiece spells the byte piece for 0xC2. */
        internal fun bytePieceName(value: Int): String = "<0x%02X>".format(value and 0xFF)

        fun load(file: File): SentencePieceBpe = fromModelProto(file.readBytes())

        fun fromModelProto(bytes: ByteArray): SentencePieceBpe {
            val raw = ArrayList<ByteArray>()
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
                            raw.add(piece)
                            pieceScores.add(score)
                            pieceTypes.add(type)
                        }
                        reader.position = start + len
                    }

                    else -> error("unsupported wire type ${tag and 7L}")
                }
            }

            require(raw.isNotEmpty()) { "not a SentencePiece model: no pieces" }

            // Byte pieces are the six-character surface `<0xNN>` with type BYTE; the value is in the name, not the
            // raw field, which is why a `raw.size == 1` test never matched one.
            val byteOf = IntArray(raw.size) { -1 }
            raw.forEachIndexed { id, surface ->
                if (pieceTypes[id] == TYPE_BYTE) {
                    byteOf[id] = parseBytePieceName(surface) ?: -1
                }
            }

            return SentencePieceBpe(
                pieces = Array(raw.size) { String(raw[it], Charsets.UTF_8) },
                scores = FloatArray(pieceScores.size) { pieceScores[it] },
                byteOf = byteOf,
            )
        }

        /** `<0xC2>` → 0xC2, or `null` for anything that is not that shape. */
        private fun parseBytePieceName(surface: ByteArray): Int? {
            if (surface.size != 6) return null
            val s = String(surface, Charsets.US_ASCII)
            if (!s.startsWith("<0x") || !s.endsWith(">")) return null
            val hex = s.substring(3, 5)
            val value = hex.toIntOrNull(16) ?: return null
            return if (value in 0..255) value else null
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
