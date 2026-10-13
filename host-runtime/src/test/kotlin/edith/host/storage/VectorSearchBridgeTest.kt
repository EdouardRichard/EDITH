package edith.host.storage

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VectorSearchBridgeTest {
    @Test
    fun `insert and search returns closest vector`() {
        val bridge = VectorSearchBridge(dimensions = 4)
        bridge.insert("id1", floatArrayOf(1f, 0f, 0f, 0f))
        bridge.insert("id2", floatArrayOf(0f, 1f, 0f, 0f))
        bridge.insert("id3", floatArrayOf(0f, 0f, 1f, 0f))

        val results = bridge.search(floatArrayOf(1f, 0f, 0f, 0f), limit = 1)
        assertEquals(listOf("id1"), results)
    }

    @Test
    fun `search returns empty list on empty store`() {
        val bridge = VectorSearchBridge(dimensions = 4)
        val results = bridge.search(floatArrayOf(1f, 0f, 0f, 0f), limit = 5)
        assertTrue(results.isEmpty())
    }

    @Test
    fun `search respects limit`() {
        val bridge = VectorSearchBridge(dimensions = 4)
        bridge.insert("a", floatArrayOf(1f, 0f, 0f, 0f))
        bridge.insert("b", floatArrayOf(0.9f, 0.1f, 0f, 0f))
        bridge.insert("c", floatArrayOf(0f, 1f, 0f, 0f))

        val results = bridge.search(floatArrayOf(1f, 0f, 0f, 0f), limit = 2)
        assertEquals(2, results.size)
        assertEquals("a", results[0])
    }

    @Test
    fun `delete removes entry from search results`() {
        val bridge = VectorSearchBridge(dimensions = 4)
        bridge.insert("id1", floatArrayOf(1f, 0f, 0f, 0f))
        bridge.insert("id2", floatArrayOf(0f, 1f, 0f, 0f))
        bridge.delete("id1")

        val results = bridge.search(floatArrayOf(1f, 0f, 0f, 0f), limit = 2)
        assertEquals(listOf("id2"), results)
    }
}
