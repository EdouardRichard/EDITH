package com.sheen.rokid.phone.plugin.smarthelper.context

import com.sheen.rokid.agent.ConversationStore
import com.sheen.rokid.agent.SegmentOrigin
import com.sheen.rokid.agent.SummaryBoundary
import com.sheen.rokid.plugin.CancellationHandle
import com.sheen.rokid.plugin.MessageRole
import com.sheen.rokid.plugin.ModelHistoryItem
import com.sheen.rokid.plugin.StorageHandle
import com.sheen.rokid.plugin.TaskScope
import com.sheen.rokid.phone.plugin.smarthelper.memory.MemoryLog
import com.sheen.rokid.phone.plugin.smarthelper.memory.MemoryModule
import com.sheen.rokid.phone.plugin.smarthelper.memory.SemanticRetriever
import com.sheen.rokid.phone.plugin.smarthelper.memory.SmartMemoryRetrieval
import com.sheen.rokid.phone.plugin.smarthelper.memory.VectorIndex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Cut ③ over the production session owner (`ConversationStore`), spec §4.4 / D-4.
 *
 * B3's criteria are unchanged and only the apparatus moves (design §7): the synchronous half still drops the
 * oldest content until the transcript fits and still stops at the anchored region, the write-to-memory ordering
 * is unchanged, and the asynchronous half is still a pure improvement. The one **stronger** semantic the store
 * adds is whole-turn eviction: a half turn can no longer be left behind.
 *
 * The fixtures are a local copy of `CompressionTest`'s on purpose. Reusing them would mean widening those
 * `private` members, which would modify a test this task must not touch.
 */
class StoreCompressionTest {

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

    /** Runs nothing: the summary is captured so a test can prove the call was deferred, not performed. */
    private class CapturingTasks : TaskScope {
        val launched = mutableListOf<() -> Unit>()
        override fun launch(block: () -> Unit): CancellationHandle {
            launched += block
            return object : CancellationHandle { override fun cancel() = Unit }
        }
        override fun cancelAll() {
            launched.clear()
        }
        fun runAll() {
            launched.toList().forEach { it() }
        }
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

    private fun compressor(
        memory: MemoryModule? = null,
        summarize: ((String, Int) -> String?)? = null,
        tasks: TaskScope? = null,
        shareTokens: Int = 3,
    ) = ContextCompressor(
        historyBudgetTokens = shareTokens,
        summaryMaxTokens = SUMMARY_MAX_TOKENS,
        memory = memory,
        summarizer = summarize,
        scope = tasks,
    )

    /** Two finished turns plus a pending user turn: the store's shape at a turn's end. */
    private fun threeTurnStore(): ConversationStore = ConversationStore().also {
        it.appendUser("u1")
        it.appendAssistant("a1")
        it.appendUser("u2")
        it.appendAssistant("a2")
        it.appendUser("u3")
    }

    private fun ConversationStore.texts(): List<String> =
        history().map { (it as ModelHistoryItem.Text).text }

    @Test
    fun `a store inside its share is untouched and starts no summary`() {
        val store = ConversationStore()
        store.appendUser("一")
        store.appendAssistant("二")
        val asked = mutableListOf<String>()

        val report = compressor(summarize = { _, _ -> asked += "不该出现"; "不该调用" }, shareTokens = 10_000)
            .compressStore(store, compressionOwed = true)

        assertEquals(2, store.size())
        assertEquals(0, report.truncated)
        assertEquals(0, report.droppedTurns)
        assertEquals(false, report.summaryStarted)
        assertEquals(emptyList(), asked)
    }

    @Test
    fun `an over-share store drops whole turns and keeps the last one`() {
        val store = threeTurnStore()

        val report = compressor().compressStore(store, compressionOwed = true)

        assertTrue(report.droppedTurns > 0)
        // §4.4's first anchor: the most recent user turn must stay.
        assertEquals("u3", store.texts().last())
        assertTrue("u1" !in store.texts(), store.texts().toString())
        // Whole-turn eviction: no half turn is left behind (this is the semantic the store adds over the context),
        // and exactly one turn went. The fixture costs 5 tokens against a share of 3, so turn 0 is what pays for it
        // and the correct result is the two messages of turn 1 plus the anchored turn - not `[u3]`.
        assertEquals(listOf("u2", "a2", "u3"), store.texts())
        assertEquals(2, report.truncated, "the two messages of the one turn that went")
        assertEquals(1, report.droppedTurns)
    }

    @Test
    fun `an over-share store keeps more than one turn when that is what fits`() {
        // The "until it fits" shape, and the one that exposes a count passed where a target size is expected: four
        // turns cost 7 tokens against a share of 3, so two turns (4 tokens) must go and the store must keep the
        // remaining two turns (3 tokens - exactly the share). Treating `droppedTurns` as a target size would leave
        // one message and would have removed a turn's messages that were never written to memory.
        val memory = memory()
        val store = ConversationStore().also {
            it.appendUser("u1"); it.appendAssistant("a1")
            it.appendUser("u2"); it.appendAssistant("a2")
            it.appendUser("u3"); it.appendAssistant("a3")
            it.appendUser("u4")
        }
        val before = store.texts()

        val report = compressor(memory = memory).compressStore(store, compressionOwed = true)

        assertEquals(listOf("u3", "a3", "u4"), store.texts())
        assertEquals(2, report.droppedTurns)
        assertEquals(4, report.truncated)
        // A target size of 2 would leave at most two messages; a count of two turns leaves three.
        assertTrue(store.size() > report.droppedTurns, store.texts().toString())
        assertEquals(
            store.texts().sumOf { estimateTokens(it) },
            3,
            "the surviving transcript must be exactly the share it was dropped to fit",
        )
        val left = before.toMutableList()
        store.texts().forEach { left.remove(it) }
        assertEquals(listOf("u1", "a1", "u2", "a2"), left, "the fixture must really have lost two whole turns")
        val stored = memory.entries().map { it.text }
        left.forEach { assertTrue(it in stored, "$it left the store but is not in memory: $stored") }
    }

    @Test
    fun `the dropped messages reach memory before they leave the store`() {
        // B6's store version: the store hands **every** evicted message to memory, so the raw content survives the
        // eviction. (In the wired turn the caller has already written this turn's memory; this half is the one the
        // compressor itself owns - §4.4 step 2.) Asserting only `u1`/`a1` let a removal that swept `u2`/`a2` stay
        // green, so this compares the store before and after instead of naming messages.
        val memory = memory()
        val store = threeTurnStore()
        val before = store.texts()

        compressor(memory = memory).compressStore(store, compressionOwed = true)

        val left = before.toMutableList()
        store.texts().forEach { left.remove(it) }
        assertTrue(left.isNotEmpty(), "the fixture must really have dropped something: $before")
        val stored = memory.entries().map { it.text }
        left.forEach { assertTrue(it in stored, "$it left the store but is not in memory: $stored") }
    }

    @Test
    fun `a landed summary is stored with SUMMARY origin and the boundary`() {
        val store = threeTurnStore()
        val tasks = CapturingTasks()
        val body = "（此前对话已摘要）"

        compressor(summarize = { _, _ -> body }, tasks = tasks).compressStore(store, compressionOwed = true)
        tasks.runAll()

        // The summary lands at the **head** (index 0) — `ConversationStore.appendSummary` inserts it there so it is
        // a non-superseded head summary and the next turn really starts compacted — so it is located by its origin,
        // not by a position. `appendSummary` is the only producer of SUMMARY origin on this path.
        val landed = store.snapshot().single { it.origin == SegmentOrigin.SUMMARY }
        val wire = store.history().single { it is ModelHistoryItem.Text && it.text == landed.text }
            as ModelHistoryItem.Text
        assertEquals(MessageRole.ASSISTANT, wire.role)
        // Exactly one layer: `startsWith`/`endsWith`/`contains` all hold for OPEN+OPEN+body+CLOSE+CLOSE, so the
        // rendered string is pinned whole and the boundary tokens are counted.
        assertEquals(SummaryBoundary.render(body), wire.text)
        assertEquals(1, wire.text.split(SummaryBoundary.OPEN).size - 1, wire.text)
        assertEquals(1, wire.text.split(SummaryBoundary.CLOSE).size - 1, wire.text)
        // It is the head, beside the anchored turn the compression kept.
        assertEquals(SegmentOrigin.SUMMARY, store.snapshot().first().origin)
        assertEquals("u3", store.texts().last())
    }

    @Test
    fun `the summary is written to memory beside the originals rather than instead of them`() {
        val memory = memory()
        val store = threeTurnStore()
        val tasks = CapturingTasks()

        compressor(memory = memory, summarize = { _, _ -> "（摘要）" }, tasks = tasks)
            .compressStore(store, compressionOwed = true)
        tasks.runAll()

        val stored = memory.entries().map { it.text }
        assertTrue("u1" in stored, stored.toString())
        assertTrue("（摘要）" in stored, stored.toString())
    }

    @Test
    fun `no summarizer degrades to pure truncation with no error`() {
        val store = threeTurnStore()

        val report = compressor().compressStore(store, compressionOwed = true)

        assertEquals(false, report.summaryStarted)
        assertEquals("NO_SUMMARIZER", report.summaryReason)
        assertTrue(report.truncated > 0, "the synchronous half must still have done its job")
        assertTrue(store.size() > 0)
    }

    @Test
    fun `a summary that throws or answers nothing adds no placeholder`() {
        val store = threeTurnStore()
        val tasks = CapturingTasks()

        compressor(summarize = { _, _ -> error("cloud down") }, tasks = tasks).compressStore(store, compressionOwed = true)
        val afterCompression = store.size()
        val compacted = store.history()
        tasks.runAll()
        assertEquals(afterCompression, store.size(), "a throwing summary must change nothing")
        assertEquals(compacted, store.history())

        val emptyTasks = CapturingTasks()
        val store2 = threeTurnStore()
        compressor(summarize = { _, _ -> null }, tasks = emptyTasks).compressStore(store2, compressionOwed = true)
        val afterSecondCompression = store2.size()
        emptyTasks.runAll()
        assertEquals(afterSecondCompression, store2.size(), "an empty answer must add no placeholder")
        assertFalse(store2.snapshot().any { it.origin == SegmentOrigin.SUMMARY })
    }

    @Test
    fun `the summary is deferred to the task scope rather than performed inline`() {
        val store = threeTurnStore()
        val tasks = CapturingTasks()
        val asked = mutableListOf<String>()

        val report = compressor(summarize = { text, _ -> asked += text; "（摘要）" }, tasks = tasks)
            .compressStore(store, compressionOwed = true)

        assertEquals(true, report.summaryStarted)
        assertEquals(emptyList(), asked, "the summary must not have run on the caller's thread")
        assertEquals(1, tasks.launched.size)

        tasks.runAll()
        assertEquals(1, asked.size)
    }

    @Test
    fun `compression never empties the store`() {
        val store = ConversationStore()
        store.appendUser("u1")
        store.appendAssistant("a1")

        compressor(shareTokens = 0).compressStore(store, compressionOwed = true)

        assertTrue(store.size() > 0, "a compressed store must still say something")
        // The anchored region is the last user turn and everything it opened, so this turn stays whole.
        assertEquals(listOf("u1", "a1"), store.texts())
    }

    /**
     * The ③ gate, no-summary case (D-4): `compressionOwed` can be true with nothing to drop - an over-budget frame
     * whose flow holds only this turn's input and memory (Task 3 found this shape). Cut ③ is allowed to start
     * **only after cut ① actually happened**, so with `droppedTurns == 0` no summary may be started: there is no
     * turn to summarise and a summary would be invented content.
     *
     * The store's own version of that shape is "the only turn is the anchored one, and it alone overruns the share".
     * The summarizer is the observable: it must never be entered.
     */
    @Test
    fun `an anchored-only store over its share starts no summary`() {
        val store = ConversationStore()
        store.appendUser("只有本轮一句")
        val asked = mutableListOf<String>()

        val report = compressor(summarize = { _, _ -> "不该调用" }, shareTokens = 1)
            .compressStore(store, compressionOwed = true)

        assertTrue(report.truncated == 0 && report.droppedTurns == 0)
        assertTrue(estimateTokens("只有本轮一句") > 1, "the fixture must really be over its share")
        assertEquals(emptyList(), asked, "droppedTurns == 0: no summary may be requested")
        assertEquals(false, report.summaryStarted)
        assertEquals(listOf("只有本轮一句"), store.texts())
    }

    /**
     * The other half of the same gate, through the one public entry point the wiring calls: `droppedTurns > 0` but
     * the caller reports nothing owed ⇒ the transcript was already restored by cut ① ⇒ no summary. This is the
     * `compressionOwed == false` half of `droppedTurns > 0 && compressionOwed`; the other half
     * (`droppedTurns == 0`) is the test above and the `droppable` clamp it exercises.
     */
    @Test
    fun `a turn dropped with nothing owed starts no summary`() {
        val store = threeTurnStore()
        val asked = mutableListOf<String>()

        val report = compressor(summarize = { _, _ -> "不该调用" })
            .compressStore(store, compressionOwed = false)

        assertTrue(report.droppedTurns > 0, "the fixture must really have dropped a turn")
        assertEquals(emptyList(), asked, "compressionOwed == false: no summary may be requested")
        assertEquals(false, report.summaryStarted)
    }

    private companion object {
        /** The policy's summary cap as this fixture models it; the production value comes from `ContextPolicy`. */
        const val SUMMARY_MAX_TOKENS = 64
    }
}
