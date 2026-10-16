package edith.plugins.smart.memory

import edith.plugin.EmbedResult
import edith.plugin.EmbeddingService
import edith.plugin.FailureReason
import edith.plugin.ModelInfo
import edith.plugin.StorageHandle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Spec §5.4/§5.7: semantic retrieval over the memory store, with the lexical order as the degradation.
 *
 * **The cost rule this class is built around.** Embedding the whole store to answer one query is not affordable: at
 * the Phase A measurement of ~190 ms per forward pass, a 1000-entry store would spend three minutes on a single
 * lookup. So a retrieval embeds the **query** and nothing else, and it reranks only entries that were already
 * indexed — which is what writing a memory does. A cold index therefore degrades to the lexical order rather than
 * stalling, and it warms as memories are written.
 */
class MemoryModuleTest {

    private class FakeEmbeddings(
        private val failWith: FailureReason? = null,
        /** Maps a text to the vector it should produce; unknown texts get a vector pointing at `fallback`. */
        private val vectors: Map<String, FloatArray> = emptyMap(),
        private val fallback: FloatArray = FloatArray(DIM) { 0f },
    ) : EmbeddingService {
        val embedded = mutableListOf<String>()

        override fun embed(text: String): EmbedResult {
            embedded += text
            if (failWith != null) return EmbedResult.Error(failWith, "synthetic")
            return EmbedResult.Success(vectors[text] ?: fallback)
        }

        override val isAvailable = failWith == null
        override val modelInfo: ModelInfo? = null
        override val dimensions = DIM
    }

    private class FakeStorage : StorageHandle {
        val values = mutableMapOf<String, ByteArray>()
        override fun read(key: String): ByteArray? = values[key]
        override fun write(key: String, value: ByteArray) {
            values[key] = value
        }
        override fun delete(key: String) {
            values.remove(key)
        }
        override fun clear() = values.clear()
    }

    private fun unit(vararg first: Float): FloatArray {
        val v = FloatArray(DIM)
        first.forEachIndexed { index, value -> v[index] = value }
        return v
    }

    private fun module(
        embeddings: EmbeddingService?,
        log: MemoryLog,
    ) = MemoryModule(
        log = log,
        embeddings = embeddings,
        lexicon = SmartMemoryRetrieval(log, logger = {}),
        // The production index is 256-dim (EmbeddingGemma truncated); the fixture uses DIM so a vector literal in a
        // test stays readable.
        retriever = SemanticRetriever(embeddings, VectorIndex(DIM)),
    )

    private fun store(cap: Int = 1000) = MemoryLog(FakeStorage(), now = { 1L }, cap = cap, log = {})

    @Test
    fun `a query is embedded exactly once however large the store is`() {
        val log = store()
        repeat(50) { log.record(MemoryKind.FACT, "第 $it 条记忆", MemorySource.AUTO) }
        val embeddings = FakeEmbeddings()
        val module = module(embeddings, log)

        module.retrieve("随便问一句")

        assertEquals(
            listOf("随便问一句"),
            embeddings.embedded,
            "one query must cost one forward pass, not one per stored entry",
        )
    }

    @Test
    fun `a memory written this session is found by meaning rather than by shared words`() {
        val log = store()
        val embeddings = FakeEmbeddings(
            vectors = mapOf(
                "我不吃香菜" to unit(1f, 0f),
                "有哪些饮食禁忌" to unit(1f, 0f),
                "明天下午三点开会" to unit(0f, 1f),
            ),
        )
        val module = module(embeddings, log)
        module.remember("我不吃香菜", MemoryKind.PREFERENCE, MemorySource.EXPLICIT)
        module.remember("明天下午三点开会", MemoryKind.FACT, MemorySource.AUTO)

        // No lexical overlap with either memory: the ranking can only come from the vectors.
        val ranked = module.retrieve("有哪些饮食禁忌")

        assertEquals("我不吃香菜", ranked.first().text)
    }

    @Test
    fun `when the embedding service fails retrieval falls back to the lexical order`() {
        val log = store()
        val module = module(FakeEmbeddings(failWith = FailureReason.MODEL_NOT_LOADED), log)
        // Deliberately no negation marker in either the query or the entries: the lexical score penalises an entry
        // whose negation differs from the query's, which would make the two scores tie at zero and leave the order
        // to the tie-break. This test is about the fallback happening at all, so the fixture keeps the lexical signal
        // unambiguous.
        module.remember("我喜欢喝茶", MemoryKind.PREFERENCE, MemorySource.EXPLICIT)
        module.remember("明天下午三点开会", MemoryKind.FACT, MemorySource.AUTO)

        val ranked = module.retrieve("喝茶")

        assertEquals("我喜欢喝茶", ranked.first().text)
    }

    @Test
    fun `a manager with no embedding service at all still retrieves lexically`() {
        val log = store()
        val module = module(embeddings = null, log = log)
        module.remember("我住在三楼", MemoryKind.FACT, MemorySource.EXPLICIT)

        assertEquals("我住在三楼", module.retrieve("三楼").first().text)
    }

    @Test
    fun `an unwritten memory is not embedded by a retrieval`() {
        // The cold-index rule: a retrieval reranks what is indexed and leaves the rest in lexical order. It must
        // never decide to embed the store, however tempting the ranking would look.
        val log = store()
        log.record(MemoryKind.FACT, "旧记忆", MemorySource.AUTO)
        val embeddings = FakeEmbeddings()
        val module = module(embeddings, log)

        module.retrieve("旧记忆")

        assertEquals(listOf("旧记忆"), embeddings.embedded)
    }

    @Test
    fun `remember writes the entry and indexes it`() {
        val log = store()
        val embeddings = FakeEmbeddings(vectors = mapOf("新记忆" to unit(1f, 0f)))
        val module = module(embeddings, log)

        val entry = module.remember("新记忆", MemoryKind.FACT, MemorySource.EXPLICIT)

        assertEquals("新记忆", entry?.text)
        assertEquals(listOf("新记忆"), embeddings.embedded)
        assertEquals(1, log.entries().size)
    }

    @Test
    fun `a write that the store refuses is reported as a null and indexes nothing`() {
        val log = MemoryLog(storage = null, now = { 1L }, log = {})
        val embeddings = FakeEmbeddings()
        val module = module(embeddings, log)

        assertNull(module.remember("写不进去", MemoryKind.FACT, MemorySource.EXPLICIT))
        assertEquals(emptyList(), embeddings.embedded)
    }

    @Test
    fun `an empty or blank query retrieves nothing`() {
        val log = store()
        val embeddings = FakeEmbeddings()
        val module = module(embeddings, log)
        module.remember("我不吃香菜", MemoryKind.PREFERENCE, MemorySource.EXPLICIT)
        val afterWrite = embeddings.embedded.toList()

        assertEquals(emptyList(), module.retrieve("   "))
        // A blank query is not embedded: there is nothing to embed, and a forward pass on whitespace would be a
        // wasted ~190 ms on every idle turn.
        assertEquals(afterWrite, embeddings.embedded)
    }

    @Test
    fun `the injected form is the documented one and carries no vector`() {
        val log = store()
        val module = module(FakeEmbeddings(), log)
        module.remember("我不吃香菜", MemoryKind.PREFERENCE, MemorySource.EXPLICIT)

        val parts = module.injectionFor("香菜")

        val part = parts.single()
        assertEquals(edith.agent.SegmentOrigin.MEMORY_EVIDENCE, part.origin)
        assertEquals("我不吃香菜", part.text)
        assertTrue(!part.trusted, "memory evidence must be untrusted")
    }

    @Test
    fun `a retrieval marks the entries it returned as used`() {
        // What makes the usage-based eviction of §5.6 mean anything.
        val log = store()
        val module = module(FakeEmbeddings(), log)
        module.remember("我不吃香菜", MemoryKind.PREFERENCE, MemorySource.EXPLICIT)
        val before = log.entries().single().useCount

        module.retrieve("香菜")

        assertTrue(log.entries().single().useCount > before)
    }

    @Test
    fun `the store is read through its snapshot so a read failure is distinguishable`() {
        val module = module(FakeEmbeddings(), MemoryLog(storage = null, now = { 1L }, log = {}))

        // A namespace that refuses to be read answers nothing rather than throwing; the module must not invent a
        // memory to fill the gap.
        assertEquals(emptyList(), module.retrieve("任何问题"))
    }

    private companion object {
        const val DIM = 8
    }
}
