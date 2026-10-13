package edith.phone.litert

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * edith-0.2 Phase B, task 6b: the tokenizer half of `EmbeddingGemmaLiteRTEngine`, provable without the 179 MB graph.
 *
 * **The oracle is the publisher's own tokenizer.** Every expectation below was produced by running
 * `sentencepiece.SentencePieceProcessor` (python-sentencepiece 0.2.2) over `sentencepiece.model`, and is pasted in as
 * a literal. A hand-written expectation would only prove the author and the implementation agree.
 *
 * The model file is gitignored, so these tests skip — loudly, naming the path — when it is absent. That is the honest
 * structure: the alternative is committing 4.7 MB of vendor data to keep a test that proves nothing extra.
 */
class EmbeddingTokenizerTest {

    private val modelFile: File = sequenceOf(
        File("../.local/models/embeddinggemma/sentencepiece.model"),
        File(".local/models/embeddinggemma/sentencepiece.model"),
    ).firstOrNull { it.isFile } ?: File("../.local/models/embeddinggemma/sentencepiece.model")

    private fun tokenizer(): SentencePieceUnigram {
        assumeTrue(
            "sentencepiece.model not found at ${modelFile.absolutePath} — the vendor artifact is gitignored",
            modelFile.isFile,
        )
        return SentencePieceUnigram.load(modelFile)
    }

    // ---------------------------------------------------------------- the proto parses

    @Test
    fun `the model proto parses into the vocabulary the publisher shipped`() {
        val sp = tokenizer()

        assertEquals(262144, sp.size)
        assertEquals("<pad>", sp.piece(0))
        assertEquals("<eos>", sp.piece(1))
        assertEquals("<bos>", sp.piece(2))
        assertEquals("<unk>", sp.piece(3))
        assertEquals("<mask>", sp.piece(4))
    }

    @Test
    fun `the byte pieces are the ones SentencePiece puts at a fixed offset`() {
        // `<0xC2>` is id 432 and `<0xA0>` is 398, so the offset is 238 for every byte. Pinning it here means the
        // fallback path cannot silently drift to a different piece.
        val sp = tokenizer()

        assertEquals(432, sp.bytePiece(0xC2))
        assertEquals(398, sp.bytePiece(0xA0))
        assertEquals(238, sp.bytePiece(0x00))
        assertEquals(493, sp.bytePiece(0xFF))
    }

    // ---------------------------------------------------------------- tokenization matches the oracle

    @Test
    fun `the parse keeps the publisher's own scores`() {
        // Scores are the whole Viterbi objective, so a silent error here changes every segmentation while leaving
        // the vocabulary looking perfect. These four are the ones that decide the cases below: `▁▁` scores 0.0 --
        // better than any ordinary piece -- which is exactly why the publisher emits it as one piece.
        val sp = tokenizer()

        assertEquals(-8765.0f, sp.score(9259), 0.01f)      // 'Hello'
        assertEquals(0.0f, sp.score(138), 0.01f)           // '▁▁'
        assertEquals(-155.0f, sp.score(649), 0.01f)        // '▁W'
        assertEquals(-236280.0f, sp.score(236743), 0.01f)  // '▁'
    }

    @Test
    fun `tokenization reproduces the publisher's own pieces`() {
        // Asserted on pieces rather than ids when something is wrong: an id mismatch tells you nothing about what
        // the tokenizer thought it saw.
        val sp = tokenizer()

        assertEquals(listOf("Hello", "\u2581World"), sp.encode("Hello World").map { sp.piece(it) })
    }

    @Test
    fun `tokenization reproduces the publisher's own ids`() {
        val sp = tokenizer()

        assertArrayEquals(intArrayOf(9259, 4109), sp.encode("Hello World"))
        assertArrayEquals(intArrayOf(144626, 236900, 12811), sp.encode("你好，世界"))
        assertArrayEquals(intArrayOf(123125, 236859), sp.encode("café"))
        assertArrayEquals(intArrayOf(236781, 255968, 236762), sp.encode("x\ty"))
        assertArrayEquals(intArrayOf(2413, 13655, 236743, 210555, 163601), sp.encode("EDITH 语义检索"))
    }

    @Test
    fun `normalization escapes spaces and does nothing else`() {
        // Three facts from the oracle, all of which a "reasonable" NFKC normalizer would get wrong:
        // no dummy prefix (`Hello` keeps its id, it is not `▁Hello`), whitespace is NOT collapsed, and full-width
        // characters survive untouched.
        val sp = tokenizer()

        assertArrayEquals(intArrayOf(138, 236746, 139, 236763, 138), sp.encode("  a   b  "))
        assertArrayEquals(intArrayOf(236776, 239549), sp.encode("AＡ"))
        assertNotEquals(
            "collapsing whitespace would make these equal",
            sp.encode("a  b").toList(),
            sp.encode("a b").toList(),
        )
    }

    @Test
    fun `a character outside the vocabulary falls back to its utf-8 bytes`() {
        val sp = tokenizer()

        // U+00A0 is not a piece; NBSP is two UTF-8 bytes and both become byte pieces.
        assertArrayEquals(intArrayOf(432, 398, 236746), sp.encode("\u00a0a"))
    }

    @Test
    fun `an empty text produces no pieces`() {
        val sp = tokenizer()

        assertArrayEquals(intArrayOf(), sp.encode(""))
    }

    @Test
    fun `the same text twice produces the same ids`() {
        val sp = tokenizer()

        assertArrayEquals(sp.encode("EDITH 语义检索"), sp.encode("EDITH 语义检索"))
    }

    // ---------------------------------------------------------------- the model's frame

    @Test
    fun `the frame wraps the text in bos and eos and pads to the sequence length`() {
        val sp = tokenizer()

        val ids = sp.encodeForModel("Hello World", sequenceLength = 256)

        assertEquals(256, ids.size)
        assertEquals(2, ids[0])
        assertArrayEquals(intArrayOf(9259, 4109), ids.copyOfRange(1, 3))
        assertEquals(1, ids[3])
        assertTrue("everything after eos is pad", ids.copyOfRange(4, 256).all { it == 0 })
    }

    @Test
    fun `a long text is truncated to the sequence length and keeps its frame`() {
        val sp = tokenizer()
        val long = "语义检索 " .repeat(200)

        val ids = sp.encodeForModel(long, sequenceLength = 256)

        assertEquals(256, ids.size)
        assertEquals(2, ids[0])
        assertEquals("the closing token must survive truncation, or the frame is not a frame", 1, ids[255])
    }

    @Test
    fun `an empty text still produces a complete frame`() {
        val sp = tokenizer()

        val ids = sp.encodeForModel("", sequenceLength = 256)

        assertEquals(256, ids.size)
        assertEquals(2, ids[0])
        assertEquals(1, ids[1])
        assertTrue(ids.copyOfRange(2, 256).all { it == 0 })
    }
}
