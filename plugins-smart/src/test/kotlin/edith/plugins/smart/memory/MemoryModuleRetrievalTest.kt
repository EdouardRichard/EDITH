package edith.plugins.smart.memory

import edith.plugin.EmbedResult
import edith.plugin.EmbeddingService
import edith.plugin.ModelInfo
import edith.plugins.smart.SmartFakeStorage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The adapter that puts the Phase B memory facade on the agent runtime's retrieval port, and the one log line it owes
 * the device.
 *
 * The privacy assertion is the point of the second test: `P3_MEMORY` is the only evidence the device log has that
 * memory was injected, and it must stay a line about **counts and ids**. The remembered sentences themselves only ever
 * travel to the model, on a request the session's upload rule already allowed (spec §5.3).
 */
class MemoryModuleRetrievalTest {

    private class ScriptedEmbeddings : EmbeddingService {
        override fun embed(text: String): EmbedResult = EmbedResult.Success(
            FloatArray(DIM).also { v ->
                if (text.contains("绿色的菜") || text == "香菜") v[0] = 1f else v[1] = 1f
            },
        )

        override val isAvailable = true
        override val modelInfo: ModelInfo? = null
        override val dimensions = DIM
    }

    private fun moduleWith(vararg texts: String): MemoryModule {
        val log = MemoryLog(SmartFakeStorage(), now = { 1L }, log = {})
        val embeddings = ScriptedEmbeddings()
        val module = MemoryModule(log, embeddings, SmartMemoryRetrieval(log, logger = {}))
        texts.forEach { module.remember(it, MemoryKind.FACT, MemorySource.EXPLICIT) }
        return module
    }

    @Test
    fun `a remembered sentence comes back as a context part under the memory namespace`() {
        val module = moduleWith("我不吃香菜")

        val parts = MemoryModuleRetrieval(module, logger = {}).retrieve("香菜", 5)

        assertEquals(listOf("我不吃香菜"), parts.map { it.text })
        // Phase 3: Segment has no id field; namespace verification moved to MemoryModule internals
        assertTrue(parts.all { it.origin == edith.agent.SegmentOrigin.MEMORY_EVIDENCE })
    }

    @Test
    fun `the log line carries counts and ids and never the remembered text`() {
        val module = moduleWith("我不吃香菜", "我喜欢那个绿色的菜")
        val lines = mutableListOf<String>()

        MemoryModuleRetrieval(module, logger = { lines += it }).retrieve("香菜", 5)

        val line = lines.single()
        assertTrue(line.startsWith("P3_MEMORY inject=n=2"), "expected an injection count: $line")
        assertTrue(line.contains("chars="), "expected a character total: $line")
        assertTrue(line.contains("ids="), "expected the entry ids: $line")
        assertFalse(line.contains("香菜"), "the log must not carry remembered content: $line")
        assertFalse(line.contains("绿色的菜"), "the log must not carry remembered content: $line")
    }

    @Test
    fun `nothing injected is nothing logged`() {
        val lines = mutableListOf<String>()

        val parts = MemoryModuleRetrieval(moduleWith("我不吃香菜"), logger = { lines += it }).retrieve("   ", 5)

        assertTrue(parts.isEmpty())
        assertTrue(lines.isEmpty(), "a blank query injected nothing, so it must say nothing: $lines")
    }

    private companion object {
        /** The index is 256-dim in production; a narrower vector is rejected by `VectorIndex`. */
        const val DIM = 256
    }
}
