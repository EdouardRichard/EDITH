package com.sheen.rokid.phone.plugin.smarthelper.memory

import com.sheen.rokid.plugin.EmbedResult
import com.sheen.rokid.plugin.EmbeddingService
import com.sheen.rokid.plugin.ModelInfo
import com.sheen.rokid.phone.plugin.smarthelper.SmartFakeStorage
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

        // Phase 4 Task 5: all entries are wrapped in a single <reference_context> block.
        assertEquals(1, parts.size, "one segment wrapping all entries")
        val text = parts[0].text
        assertTrue(text.startsWith("<reference_context>"))
        assertTrue(text.endsWith("</reference_context>"))
        assertTrue(text.contains("我不吃香菜"), "original text present (no normalizer configured)")
        assertTrue(parts.all { it.origin == com.sheen.rokid.agent.SegmentOrigin.MEMORY_EVIDENCE })
    }

    @Test
    fun `the log line carries counts and ids and never the remembered text`() {
        val module = moduleWith("我不吃香菜", "我喜欢那个绿色的菜")
        val lines = mutableListOf<String>()

        MemoryModuleRetrieval(module, logger = { lines += it }).retrieve("香菜", 5)

        val line = lines.single()
        // Phase 4 Task 5: parts is now one segment wrapping all entries in a <reference_context> block.
        // n= reflects segment count (1), chars= includes header/tag overhead — both correct per task brief.
        assertTrue(line.startsWith("P3_MEMORY inject=n=1"), "one segment wrapping all entries: $line")
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
