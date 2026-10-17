package com.sheen.rokid.phone.plugin.smarthelper.memory

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The cosine index behind semantic retrieval. Moved here from `host-runtime`'s `VectorSearchBridgeTest`, which is
 * where the class lived while it had no production consumer.
 */
class VectorIndexTest {
    @Test
    fun `insert and search returns closest vector`() {
        val index = VectorIndex(dimensions = 4)
        index.insert("id1", floatArrayOf(1f, 0f, 0f, 0f))
        index.insert("id2", floatArrayOf(0f, 1f, 0f, 0f))
        index.insert("id3", floatArrayOf(0f, 0f, 1f, 0f))

        assertEquals(listOf("id1"), index.search(floatArrayOf(1f, 0f, 0f, 0f), limit = 1))
    }

    @Test
    fun `search returns empty list on empty store`() {
        val index = VectorIndex(dimensions = 4)
        assertTrue(index.search(floatArrayOf(1f, 0f, 0f, 0f), limit = 5).isEmpty())
    }

    @Test
    fun `search respects limit`() {
        val index = VectorIndex(dimensions = 4)
        index.insert("a", floatArrayOf(1f, 0f, 0f, 0f))
        index.insert("b", floatArrayOf(0.9f, 0.1f, 0f, 0f))
        index.insert("c", floatArrayOf(0f, 1f, 0f, 0f))

        val results = index.search(floatArrayOf(1f, 0f, 0f, 0f), limit = 2)
        assertEquals(2, results.size)
        assertEquals("a", results[0])
    }

    @Test
    fun `delete removes entry from search results`() {
        val index = VectorIndex(dimensions = 4)
        index.insert("id1", floatArrayOf(1f, 0f, 0f, 0f))
        index.insert("id2", floatArrayOf(0f, 1f, 0f, 0f))
        index.delete("id1")

        assertEquals(listOf("id2"), index.search(floatArrayOf(1f, 0f, 0f, 0f), limit = 2))
    }

    @Test
    fun `a dimension mismatch is refused on both write and read`() {
        val index = VectorIndex(dimensions = 4)

        assertFailsWith<IllegalArgumentException> { index.insert("bad", floatArrayOf(1f, 2f)) }
        assertFailsWith<IllegalArgumentException> { index.search(floatArrayOf(1f, 2f), limit = 1) }
    }

    @Test
    fun `a zero query answers nothing rather than an arbitrary ranking`() {
        // A zero vector has no direction, so every cosine against it is meaningless rather than merely unhelpful.
        val index = VectorIndex(dimensions = 4)
        index.insert("id1", floatArrayOf(1f, 0f, 0f, 0f))

        assertTrue(index.search(floatArrayOf(0f, 0f, 0f, 0f), limit = 5).isEmpty())
    }

    @Test
    fun `a stored vector is copied so a reused caller buffer cannot corrupt it`() {
        val index = VectorIndex(dimensions = 2)
        val buffer = floatArrayOf(1f, 0f)
        index.insert("id", buffer)

        buffer[0] = 0f
        buffer[1] = 1f

        // Still the original direction: the query that matched at insert time still matches.
        assertEquals(listOf("id"), index.search(floatArrayOf(1f, 0f), limit = 1))
    }

    @Test
    fun `a zero vector stored in the index ranks last rather than dividing by zero`() {
        val index = VectorIndex(dimensions = 2)
        index.insert("zero", floatArrayOf(0f, 0f))
        index.insert("real", floatArrayOf(1f, 0f))

        // The zero vector has no direction, so its similarity is defined as 0 and it sorts last - what must not
        // happen is a NaN, which would make the whole ordering undefined.
        assertEquals(listOf("real", "zero"), index.search(floatArrayOf(1f, 0f), limit = 2))
    }

    @Test
    fun `size tracks inserts and deletes`() {
        val index = VectorIndex(dimensions = 2)
        assertEquals(0, index.size)
        index.insert("a", floatArrayOf(1f, 0f))
        assertEquals(1, index.size)
        index.delete("a")
        assertEquals(0, index.size)
    }
}
