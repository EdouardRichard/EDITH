package com.sheen.rokid.phone.plugin.smarthelper.context

import com.sheen.rokid.plugin.MessageRole
import com.sheen.rokid.plugin.ModelHistoryItem
import com.sheen.rokid.plugin.TaskScope
import com.sheen.rokid.plugin.CancellationHandle
import com.sheen.rokid.phone.plugin.smarthelper.memory.MemoryKind
import com.sheen.rokid.phone.plugin.smarthelper.memory.MemoryLog
import com.sheen.rokid.phone.plugin.smarthelper.memory.MemoryModule
import com.sheen.rokid.phone.plugin.smarthelper.memory.MemorySource
import com.sheen.rokid.phone.plugin.smarthelper.memory.SemanticRetriever
import com.sheen.rokid.phone.plugin.smarthelper.memory.SmartMemoryRetrieval
import com.sheen.rokid.phone.plugin.smarthelper.memory.VectorIndex
import com.sheen.rokid.plugin.StorageHandle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Spec §4.4 (方案 E): synchronous rule-based truncation, plus an **asynchronous** cloud summary that is a pure
 * improvement — when it is unavailable the compression degrades to truncation and says nothing.
 */
class CompressionTest {

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

    private fun context(vararg texts: String): ConversationContext {
        val ctx = ConversationContext(ContextRoute.WAKE)
        texts.forEachIndexed { index, text ->
            if (index % 2 == 0) ctx.appendUser(text) else ctx.appendAssistant(text)
        }
        return ctx
    }

    /**
     * The compressor under test: the policy-derived allowances are constructor state (0.3 Task 11), so a test picks
     * the history allowance it needs and the summary cap is one fixed, small value.
     */
    private fun compressor(
        memory: MemoryModule? = null,
        summarize: ((String, Int) -> String?)? = null,
        tasks: TaskScope? = null,
        budgetTokens: Int = 3,
    ) = ContextCompressor(
        historyBudgetTokens = budgetTokens,
        summaryMaxTokens = SUMMARY_MAX_TOKENS,
        memory = memory,
        summarizer = summarize,
        scope = tasks,
    )

    @Test
    fun `a history inside the budget is not touched and starts no summary`() {
        val ctx = context("一", "二")
        val report = compressor(summarize = { _, _ -> "不该调用" }, budgetTokens = 10_000).compressIfNeeded(ctx)

        assertEquals(2, ctx.size())
        assertEquals(0, report.truncated)
        assertEquals(false, report.summaryStarted)
    }

    @Test
    fun `truncation drops the oldest messages first`() {
        val ctx = context("最早的一句", "回答一", "第二句", "回答二", "最近一句", "回答三")

        val report = compressor(budgetTokens = 12).compressIfNeeded(ctx)

        assertTrue(report.truncated > 0)
        val kept = ctx.history().map { (it as ModelHistoryItem.Text).text }
        assertTrue("最近一句" in kept, kept.toString())
        assertTrue("最早的一句" !in kept, kept.toString())
    }

    @Test
    fun `the most recent user message is never truncated`() {
        // §4.4's first anchor: whatever the wearer just asked is the one thing the turn cannot lose.
        val ctx = context("很久以前", "回答", "刚刚这句")

        compressor(budgetTokens = 1).compressIfNeeded(ctx)

        val kept = ctx.history().map { (it as ModelHistoryItem.Text).text }
        assertTrue("刚刚这句" in kept, kept.toString())
    }

    @Test
    fun `an unfinished tool call and its output are never truncated`() {
        val ctx = ConversationContext(ContextRoute.WAKE)
        ctx.appendUser("帮我记一条")
        ctx.appendAssistant("好")
        ctx.appendToolCall("c1", "compose_note", "{}")
        ctx.appendToolOutput("c1", "ok")

        compressor(budgetTokens = 1).compressIfNeeded(ctx)

        val transcript = ctx.history()
        assertTrue(transcript.any { it is ModelHistoryItem.ToolCall }, transcript.toString())
        assertTrue(transcript.any { it is ModelHistoryItem.ToolOutput }, transcript.toString())
    }

    @Test
    fun `a truncation writes what it dropped to memory`() {
        // §4.4 step 2: the raw messages reach the store before they leave the transcript, so nothing is lost.
        val memory = memory()
        val ctx = context("值得记住的一句", "回答", "最近一句")

        compressor(memory = memory).compressIfNeeded(ctx)

        assertTrue(memory.entries().any { it.text == "值得记住的一句" }, memory.entries().map { it.text }.toString())
    }

    @Test
    fun `a summary is deferred to the task scope rather than performed inline`() {
        val ctx = context("最早的一句", "回答", "最近一句")
        val tasks = CapturingTasks()
        val asked = mutableListOf<String>()

        val report = compressor(memory = memory(), summarize = { text, _ -> asked += text; "（摘要）" }, tasks = tasks)
            .compressIfNeeded(ctx)

        assertEquals(true, report.summaryStarted)
        assertEquals(emptyList(), asked, "the summary must not have run on the caller's thread")
        assertEquals(1, tasks.launched.size)

        tasks.runAll()
        assertEquals(1, asked.size)
    }

    @Test
    fun `the context is fully usable before the summary lands`() {
        val ctx = context("最早的一句", "回答", "最近一句")
        val tasks = CapturingTasks()

        compressor(summarize = { _, _ -> "（摘要）" }, tasks = tasks).compressIfNeeded(ctx)

        // Nothing about the transcript is half-written: a prompt built now is a complete one.
        assertTrue(ctx.history().isNotEmpty())
        assertTrue(ctx.history().none { it is ModelHistoryItem.Text && it.text.isBlank() })
    }

    @Test
    fun `a summary that lands is inserted as exactly one placeholder message`() {
        val ctx = context("最早的一句", "回答", "最近一句")
        val tasks = CapturingTasks()

        compressor(summarize = { _, _ -> "（此前对话已摘要）" }, tasks = tasks).compressIfNeeded(ctx)
        val afterCompression = ctx.size()
        tasks.runAll()

        assertEquals(afterCompression + 1, ctx.size())
        val last = ctx.history().last() as ModelHistoryItem.Text
        assertEquals(MessageRole.ASSISTANT, last.role)
        assertEquals("（此前对话已摘要）", last.text)
    }

    @Test
    fun `the summary is written to memory beside the originals rather than instead of them`() {
        // §4.4 step 4b, and C4: raw messages are what is stored; the summary is an addition.
        val memory = memory()
        val ctx = context("原始的一句", "回答", "最近一句")
        val tasks = CapturingTasks()

        compressor(memory = memory, summarize = { _, _ -> "（摘要）" }, tasks = tasks)
            .compressIfNeeded(ctx)
        tasks.runAll()

        val stored = memory.entries().map { it.text }
        assertTrue("原始的一句" in stored, stored.toString())
        assertTrue("（摘要）" in stored, stored.toString())
    }

    @Test
    fun `the summary call carries the policy's output allowance`() {
        // 0.3 design §6.4: the summary declares its own cap; it never inherits the profile's window reservation.
        val ctx = context("最早的一句", "回答", "最近一句")
        val tasks = CapturingTasks()
        var askedMax: Int? = null

        compressor(summarize = { _, maxOutputTokens -> askedMax = maxOutputTokens; "（摘要）" }, tasks = tasks)
            .compressIfNeeded(ctx)
        tasks.runAll()

        assertEquals(SUMMARY_MAX_TOKENS, askedMax)
    }

    @Test
    fun `an unavailable summarizer degrades to pure truncation and reports no error`() {
        // B3's acceptance line: 云端不可用时退化为纯截断且不报错. No summarizer at all is that case.
        val ctx = context("最早的一句", "回答", "最近一句")

        val report = compressor().compressIfNeeded(ctx)

        assertEquals(false, report.summaryStarted)
        assertEquals("NO_SUMMARIZER", report.summaryReason)
        assertTrue(report.truncated > 0, "the synchronous half must still have done its job")
    }

    @Test
    fun `a summarizer that throws degrades to pure truncation`() {
        val ctx = context("最早的一句", "回答", "最近一句")
        val tasks = CapturingTasks()

        compressor(summarize = { _, _ -> error("cloud down") }, tasks = tasks).compressIfNeeded(ctx)
        // The throw is on the task scope, so it must not escape into the caller either.
        runCatching { tasks.runAll() }

        assertTrue(ctx.history().isNotEmpty())
    }

    @Test
    fun `a summarizer that answers nothing adds no placeholder`() {
        val ctx = context("最早的一句", "回答", "最近一句")
        val tasks = CapturingTasks()

        compressor(summarize = { _, _ -> null }, tasks = tasks).compressIfNeeded(ctx)
        val afterCompression = ctx.size()
        tasks.runAll()

        assertEquals(afterCompression, ctx.size())
    }

    @Test
    fun `the report carries counts and a reason code but never text`() {
        val ctx = context("秘密正文标记", "回答", "最近一句")

        val report = compressor().compressIfNeeded(ctx)

        val rendered = report.toString()
        assertTrue("秘密正文标记" !in rendered, rendered)
        assertTrue(report.truncated > 0)
    }

    @Test
    fun `an empty history compresses to nothing`() {
        val ctx = ConversationContext(ContextRoute.WAKE)

        val report = compressor(budgetTokens = 10).compressIfNeeded(ctx)

        assertEquals(0, report.truncated)
        assertEquals(false, report.summaryStarted)
    }

    @Test
    fun `compression never drops the whole transcript`() {
        // A budget of zero is a misconfiguration; emptying the conversation would be a worse answer than
        // overrunning it, because the prompt would then have no user turn at all.
        val ctx = context("一", "二")

        compressor(budgetTokens = 0).compressIfNeeded(ctx)

        assertTrue(ctx.history().isNotEmpty(), "a compressed transcript must still say something")
    }

    @Test
    fun `a memory write that fails does not stop the truncation`() {
        // A store that refuses answers null and logs; the turn still has to get a compressed transcript.
        val refused = MemoryModule(
            log = MemoryLog(storage = null, now = { 1L }, log = {}),
            embeddings = null,
            lexicon = SmartMemoryRetrieval(MemoryLog(storage = null, now = { 1L }, log = {}), logger = {}),
            retriever = SemanticRetriever(null, VectorIndex(4)),
        )
        val ctx = context("最早的一句", "回答", "最近一句")

        val report = compressor(memory = refused).compressIfNeeded(ctx)

        assertTrue(report.truncated > 0)
    }

    private companion object {
        /** The policy's summary cap as this fixture models it; the production value comes from `ContextPolicy`. */
        const val SUMMARY_MAX_TOKENS = 64
    }
}
