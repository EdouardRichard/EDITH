package com.sheen.rokid.phone.plugin.smarthelper.context
import com.sheen.rokid.phone.plugin.smarthelper.SmartAssistantPlugin

import com.sheen.rokid.plugin.MessageRole
import com.sheen.rokid.plugin.ModelHistoryItem
import com.sheen.rokid.plugin.StorageHandle
import com.sheen.rokid.phone.plugin.smarthelper.memory.MemoryLog
import com.sheen.rokid.phone.plugin.smarthelper.memory.MemoryModule
import com.sheen.rokid.phone.plugin.smarthelper.memory.SemanticRetriever
import com.sheen.rokid.phone.plugin.smarthelper.memory.SmartMemoryRetrieval
import com.sheen.rokid.phone.plugin.smarthelper.memory.VectorIndex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The `ConversationContext` path's eviction criteria, frozen (§4.9/Task 5 hard constraint).
 *
 * `compressIfNeeded` is production code on the **no-store path only** (`SmartAssistantPlugin` calls it over a
 * `ConversationContext` when no `conversationStores` was injected), and its behaviour must not drift while the store
 * path lives beside it. That path is **not** the shipped composition: the phone host's `SmartWiring` always
 * constructs the plugin with `conversationStores` (one `ConversationStore` per route), so the running app never
 * reaches `compressIfNeeded`. This class is kept for a different reason - it pins the **frozen
 * semantics** R3 protects, so the shared eviction routine cannot rot behind a path that is still reachable from a
 * caller that wires no store (and from every test that does the same). The failure this class pins is concrete: a
 * shared body that charged a turn only its opening message but then passed a **count** to a removal that means
 * "drop until `size <= n`" left a dangling assistant head — `[u1, a1, u2]` came back as `[a1, u2]` with `truncated`
 * 1 and only `u1` in memory — and stopped long before the budget was met.
 *
 * `CompressionTest` cannot catch it: its eviction assertions are containment-only ("the recent message is still
 * there, the oldest is gone"), which the over- and under-dropped shapes both satisfy. This class asserts the exact
 * transcript instead, and lives in its own file so `CompressionTest`'s 16 tests stay untouched.
 *
 * The fixtures are a local copy of `CompressionTest`'s for the same reason.
 */
class ContextEvictionSemanticsTest {

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

    private fun memory(): MemoryModule {
        val log = MemoryLog(FakeStorage(), now = { 1L }, log = {})
        return MemoryModule(
            log = log,
            embeddings = null,
            lexicon = SmartMemoryRetrieval(log, logger = {}),
            retriever = SemanticRetriever(null, VectorIndex(4)),
        )
    }

    /** Three messages that cost 3, 2 and 2 tokens: 7 in total, so a budget of 3 needs two of them gone. */
    private fun context(): ConversationContext = ConversationContext(ContextRoute.WAKE).also {
        it.appendUser("一一一")
        it.appendAssistant("二二")
        it.appendUser("三三")
    }

    private fun compressor(budgetTokens: Int, memory: MemoryModule? = null) = ContextCompressor(
        historyBudgetTokens = budgetTokens,
        summaryMaxTokens = 64,
        memory = memory,
    )

    private fun ConversationContext.texts(): List<String> =
        history().map { (it as ModelHistoryItem.Text).text }

    @Test
    fun `the context path drops one message at a time until the budget is met`() {
        val memory = memory()
        val ctx = context()

        val report = compressor(budgetTokens = 3, memory = memory).compressIfNeeded(ctx)

        // Message at a time: u1 and a1 both went, and exactly the last user turn survived. A body that charged a
        // turn one message but removed a count of them left `[a1, u2]` here - a dangling assistant head.
        assertEquals(listOf("三三"), ctx.texts())
        assertEquals(MessageRole.USER, (ctx.history().first() as ModelHistoryItem.Text).role)
        assertEquals(2, report.truncated)
    }

    @Test
    fun `everything the context path dropped reached memory before it left`() {
        val memory = memory()

        compressor(budgetTokens = 3, memory = memory).compressIfNeeded(context())

        val stored = memory.entries().map { it.text }
        assertTrue("一一一" in stored, stored.toString())
        assertTrue("二二" in stored, stored.toString())
    }

    @Test
    fun `the context path stops dropping as soon as it fits`() {
        // The other half of "until it fits or the keepline is reached": one message is enough here, so only one
        // may go. Over-dropping is the store-path bug; under-dropping is the shared-body bug.
        val ctx = context()

        val report = compressor(budgetTokens = 6).compressIfNeeded(ctx)

        assertEquals(listOf("二二", "三三"), ctx.texts())
        assertEquals(1, report.truncated)
    }

    @Test
    fun `the context path counts messages dropped and the turns they came from separately`() {
        val report = compressor(budgetTokens = 3).compressIfNeeded(context())

        // Two messages went, and both came out of the first turn - the two units of the report really differ.
        assertEquals(2, report.truncated)
        assertEquals(1, report.droppedTurns)
    }
}
