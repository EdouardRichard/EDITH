package com.sheen.rokid.phone.plugin.smarthelper.memory

import com.sheen.rokid.plugin.EmbedResult
import com.sheen.rokid.plugin.EmbeddingService
import com.sheen.rokid.plugin.ModelInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A fake embedding service that records every text it was asked to embed and returns deterministic vectors.
 *
 * The vector is derived from the text's hash so identical texts produce identical vectors and different texts
 * produce different directions. This lets tests assert on call counts and ranking without a real model.
 */
private class FakeEmbeddingService : EmbeddingService {
    val embeddedTexts = mutableListOf<String>()

    override fun embed(text: String): EmbedResult {
        embeddedTexts += text
        // Deterministic vector from text hash — same text → same vector, different text → different direction.
        val hash = text.hashCode()
        val vector = FloatArray(256) { i -> ((hash * (i + 1)) % 1000) / 1000f }
        return EmbedResult.Success(vector)
    }

    override val isAvailable: Boolean = true
    override val modelInfo: ModelInfo? = null
    override val dimensions: Int = 256
}

private fun entry(
    id: String,
    text: String,
    normalizedText: String = "",
): MemoryEntry = MemoryEntry(
    id = id,
    kind = MemoryKind.PREFERENCE,
    text = text,
    createdAtMillis = 0L,
    lastUsedAtMillis = 0L,
    useCount = 0,
    source = MemorySource.AUTO,
    normalizedText = normalizedText,
)

class SemanticRetrieverTest {

    @Test
    fun `index embeds both original and normalized text when normalized differs`() {
        val fake = FakeEmbeddingService()
        val retriever = SemanticRetriever(fake)
        val e = entry("1", "我喜欢喝茶", "用户曾表示喜欢喝茶")

        retriever.index(e)

        assertEquals(2, fake.embeddedTexts.size)
        assertTrue("我喜欢喝茶" in fake.embeddedTexts)
        assertTrue("用户曾表示喜欢喝茶" in fake.embeddedTexts)
    }

    @Test
    fun `index embeds only original when normalizedText is blank`() {
        val fake = FakeEmbeddingService()
        val retriever = SemanticRetriever(fake)
        val e = entry("1", "我喜欢喝茶", "")

        retriever.index(e)

        assertEquals(1, fake.embeddedTexts.size)
        assertEquals("我喜欢喝茶", fake.embeddedTexts[0])
    }

    @Test
    fun `index embeds only original when normalizedText equals text`() {
        val fake = FakeEmbeddingService()
        val retriever = SemanticRetriever(fake)
        val e = entry("1", "我喜欢喝茶", "我喜欢喝茶")

        retriever.index(e)

        assertEquals(1, fake.embeddedTexts.size)
        assertEquals("我喜欢喝茶", fake.embeddedTexts[0])
    }

    @Test
    fun `indexedCount counts entries not vectors`() {
        val fake = FakeEmbeddingService()
        val retriever = SemanticRetriever(fake)
        val e = entry("1", "我喜欢喝茶", "用户曾表示喜欢喝茶")

        retriever.index(e)

        assertEquals(1, retriever.indexedCount)
    }

    @Test
    fun `rankScored uses max of original and normalized similarity`() {
        // We need a controlled setup where we can verify the max-score behavior.
        // Use a custom embedding service that returns known vectors.
        val vectors = mutableMapOf<String, FloatArray>()
        val service = object : EmbeddingService {
            override fun embed(text: String): EmbedResult {
                // Return pre-assigned vectors for specific texts
                val v = vectors[text] ?: FloatArray(256) { 0.1f }
                return EmbedResult.Success(v)
            }
            override val isAvailable = true
            override val modelInfo: ModelInfo? = null
            override val dimensions = 256
        }

        // Entry A: original matches query poorly, normalized matches well
        // Entry B: original matches query moderately, normalized matches poorly
        // With max-score, A should win because its normalized score beats B's best score.

        // Query vector: unit vector in dimension 0
        val queryVec = FloatArray(256) { if (it == 0) 1f else 0f }
        vectors["用户有什么偏好"] = queryVec

        // Entry A original: orthogonal to query (score ≈ 0)
        val aOrigVec = FloatArray(256) { if (it == 1) 1f else 0f }
        vectors["我喜欢喝茶"] = aOrigVec

        // Entry A normalized: aligned with query (score ≈ 1)
        val aNormVec = FloatArray(256) { if (it == 0) 1f else 0f }
        vectors["用户曾表示喜欢喝茶"] = aNormVec

        // Entry B original: partially aligned (score ≈ 0.5-ish)
        val bOrigVec = FloatArray(256) { if (it == 0) 0.5f else if (it == 1) 0.5f else 0f }
        vectors["我七点起床"] = bOrigVec

        // Entry B normalized: orthogonal to query (score ≈ 0)
        val bNormVec = FloatArray(256) { if (it == 2) 1f else 0f }
        vectors["用户曾告知通常七点起床"] = bNormVec

        val retriever = SemanticRetriever(service)
        val entryA = entry("a", "我喜欢喝茶", "用户曾表示喜欢喝茶")
        val entryB = entry("b", "我七点起床", "用户曾告知通常七点起床")

        retriever.index(entryA)
        retriever.index(entryB)

        val ranked = retriever.rankScored("用户有什么偏好", listOf(entryA, entryB))
        assertNotNull(ranked)
        assertEquals(2, ranked.size)
        // Entry A should rank first because its normalized form aligns perfectly with the query
        assertEquals("a", ranked[0].first.id)
        // And A's score should be higher than B's
        assertTrue(ranked[0].second > ranked[1].second,
            "A's max score (${ranked[0].second}) should exceed B's max score (${ranked[1].second})")
    }

    @Test
    fun `entry with empty normalizedText ranks by original only`() {
        val fake = FakeEmbeddingService()
        val retriever = SemanticRetriever(fake)
        val e = entry("1", "我喜欢喝茶", "")

        retriever.index(e)

        val ranked = retriever.rankScored("茶", listOf(e))
        assertNotNull(ranked)
        assertEquals(1, ranked.size)
        assertEquals("1", ranked[0].first.id)
    }

    @Test
    fun `forget removes entry from index`() {
        val fake = FakeEmbeddingService()
        val retriever = SemanticRetriever(fake)
        val e = entry("1", "我喜欢喝茶", "用户曾表示喜欢喝茶")

        retriever.index(e)
        retriever.forget("1")

        assertEquals(0, retriever.indexedCount)
        val ranked = retriever.rankScored("茶", listOf(e))
        assertNotNull(ranked)
        assertTrue(ranked.isEmpty())
    }

    @Test
    fun `null embedding service returns null from rankScored`() {
        val retriever = SemanticRetriever(null)
        val e = entry("1", "我喜欢喝茶")

        assertEquals(null, retriever.rankScored("茶", listOf(e)))
    }

    @Test
    fun `index returns false when embedding service is null`() {
        val retriever = SemanticRetriever(null)
        val e = entry("1", "我喜欢喝茶")

        assertEquals(false, retriever.index(e))
    }
}
