package com.sheen.rokid.phone.plugin.smarthelper
import com.sheen.rokid.phone.plugin.smarthelper.context.Summarizer
import com.sheen.rokid.phone.plugin.smarthelper.memory.VectorIndex
import com.sheen.rokid.phone.plugin.smarthelper.audit.P3Log

import com.sheen.rokid.plugin.CancellationHandle
import com.sheen.rokid.plugin.Capability
import com.sheen.rokid.plugin.EmbedResult
import com.sheen.rokid.plugin.EmbeddingService
import com.sheen.rokid.plugin.MessageRole
import com.sheen.rokid.plugin.ModelHistoryItem
import com.sheen.rokid.plugin.ModelInfo
import com.sheen.rokid.plugin.ModelStreamEvent
import com.sheen.rokid.plugin.ModelUsage
import com.sheen.rokid.plugin.TaskScope
import com.sheen.rokid.phone.plugin.smarthelper.context.ContextCompressor
import com.sheen.rokid.phone.plugin.smarthelper.context.ContextManager
import com.sheen.rokid.phone.plugin.smarthelper.context.ContextRoute
import com.sheen.rokid.phone.plugin.smarthelper.context.ConversationContext
import com.sheen.rokid.phone.plugin.smarthelper.context.ModelSummarizer
import com.sheen.rokid.phone.plugin.smarthelper.prompt.PromptCatalog
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * edith-0.2 Phase B (B3): the **production** `Summarizer` — spec §4.4's asynchronous half, which until now had no
 * implementation at all and left the compressor in its legal-but-unverified "permanently plain truncation" state.
 *
 * The contract has three load-bearing pieces, and each has a test here:
 * 1. it assembles a real request through the plugin's own configured profile (no tools: a summary executes nothing);
 * 2. it answers one sentence or `null`, and **never throws** — a cloud that is down is a normal degradation;
 * 3. the summary body never reaches the log.
 *
 * "Runs on the task pool rather than the caller's thread" is pinned through the compressor that uses it, because that
 * is where the thread choice lives: a `Summarizer` is a plain blocking function and cannot choose its own thread.
 */
class ModelSummarizerTest {

    /** Runs each `launch` on a real worker, which is what makes "the caller was not held" observable at all. */
    private class ThreadingTasks : TaskScope {
        private val workers = java.util.Collections.synchronizedList(mutableListOf<Thread>())
        override fun launch(block: () -> Unit): CancellationHandle {
            val worker = Thread({ block() }, "summarizer-test")
            workers += worker
            worker.start()
            return object : CancellationHandle { override fun cancel() = Unit }
        }
        override fun cancelAll() = Unit
        fun joinAll(millis: Long = 5_000) { workers.toList().forEach { it.join(millis) } }
    }

    /** One 256-dim vector per text; retrieval is not the subject of these tests, so every query matches. */
    private class ScriptedEmbeddings : EmbeddingService {
        override fun embed(text: String): EmbedResult = EmbedResult.Success(FloatArray(DIM) { 1f })
        override val isAvailable = true
        override val modelInfo: ModelInfo? = null
        override val dimensions = DIM
    }

    private lateinit var model: SmartFakeModel
    private lateinit var tasks: ThreadingTasks
    private lateinit var clock: SmartFakeClock

    private fun newSummarizer(
        profile: com.sheen.rokid.plugin.ModelProfile? = smartProfile(id = "profile-x", revision = 9),
    ): ModelSummarizer {
        clock = SmartFakeClock()
        model = SmartFakeModel()
        tasks = ThreadingTasks()
        return ModelSummarizer(
            client = model,
            profileLookup = { profile },
            profileId = "profile-x",
            prompt = PromptCatalog.summarizer(),
            clock = clock,
            scheduler = SmartFakeScheduler(clock),
            envelopeFor = { requestId, startNanos, endNanos ->
                com.sheen.rokid.contracts.EventEnvelope(
                    com.sheen.rokid.contracts.SessionId("s"), com.sheen.rokid.contracts.Generation(1),
                    requestId, startNanos, endNanos,
                )
            },
            deadlineNanos = { clock.nowNanos() + SUMMARY_DEADLINE_NANOS },
        )
    }

    /** Answers the request with one assistant sentence, as a transport delivers it. */
    private fun answers(text: String): (com.sheen.rokid.plugin.ModelRequest, (ModelStreamEvent) -> Unit) -> Unit =
        { request, listener -> streamText(request, listener, text) }

    /** The transcript a compression is about to discard: long enough that a small budget forces a truncation. */
    private fun overflowing(): ConversationContext {
        val context = ConversationContext(ContextRoute.WAKE)
        context.appendUser("很早以前的一句")
        context.appendAssistant("很早以前的回答")
        context.appendUser("最近一句")
        return context
    }

    @Test
    fun `the summary is one request built from the plugin's own profile, with no tool offered`() {
        val summarizer = newSummarizer()
        model.onRequest = answers("（此前对话摘要）")

        val summary = summarizer.summarize("用户问了几点。助手回答了时间。", SUMMARY_MAX_TOKENS)

        assertEquals("（此前对话摘要）", summary)
        val request = model.requests.single()
        assertEquals("profile-x", request.profileId)
        assertEquals(9L, request.profileRevision)
        // 0.3 design §5.2/§6.4: the summary's own output cap is the policy-derived allowance, not the profile's
        // window reservation.
        assertEquals(SUMMARY_MAX_TOKENS, request.maxOutputTokens, "the summary must declare its own output cap")
        // The gateway lifts a one-item transcript into `input` (that is what makes it last on the wire), so the
        // transcript is asserted where it actually travels rather than where a hand-built history would have put it.
        assertEquals(
            listOf("用户问了几点。助手回答了时间。"),
            request.input.filterIsInstance<com.sheen.rokid.plugin.InputPart.Text>().map { it.text },
            "the transcript to summarise has to be what the request carries",
        )
        assertTrue(request.tools.isEmpty(), "a summary must never be able to execute anything: ${request.tools}")
    }

    @Test
    fun `the summary request carries the six-part structured template`() {
        // 0.3 design §6.4: the summarizer's instruction is a structured six-part template authored in
        // `prompts/summarizer.md`, not the one-sentence compression instruction of the first migration.
        val summarizer = newSummarizer()
        model.onRequest = answers("（摘要）")

        assertEquals("（摘要）", summarizer.summarize("要摘要的对话", SUMMARY_MAX_TOKENS))

        val instructions = model.requests.single().instructions
        assertEquals(PromptCatalog.summarizer(), instructions, "the request must carry the catalog's authored prompt")
        listOf(
            "当前目标与约束",
            "已确认事实与偏好",
            "已执行动作与结果",
            "关键实体",
            "未完成事项与下一步",
            "禁止",
        ).forEach { section ->
            assertTrue(section in instructions, "design §6.4's six-part template must name 「$section」: $instructions")
        }
        assertTrue(
            "编造" in instructions && "补充建议" in instructions && "提问" in instructions,
            "the template must keep the no-invention/no-advice/no-question prohibition: $instructions",
        )
    }

    @Test
    fun `a summary request reports its own P3_CACHE line when usage is reported`() {
        // Final review I4: `P3_CACHE` is the gateway's per-request line, so it must cover the summary request too. A
        // summary has no assembled RequestFrame and never goes through AgentLoop.onRequestBuilt, so it has no
        // P3_PROMPT line - that asymmetry is documented in CONTRACTS.md §P3_PROMPT.
        val summarizer = newSummarizer()
        model.onRequest = { request, listener ->
            listener(ModelStreamEvent.TextDelta(request.requestId, "（此前对话摘要）"))
            listener(
                ModelStreamEvent.Terminal(
                    request.requestId,
                    com.sheen.rokid.plugin.ModelEnd.COMPLETED,
                    ModelUsage(inputTokens = 41, outputTokens = 3),
                    null,
                ),
            )
        }
        val lines = mutableListOf<String>()
        val previous = P3Log.sink
        P3Log.sink = { lines += it }
        try {
            assertEquals("（此前对话摘要）", summarizer.summarize("要摘要的对话", SUMMARY_MAX_TOKENS))
        } finally {
            P3Log.sink = previous
        }

        val cache = lines.single { it.startsWith("P3_CACHE ") }
        assertTrue(cache.contains("inputTokens=41"), "the summary's usage must reach P3_CACHE: $cache")
        assertTrue(cache.contains("outputTokens=3"), "the summary's usage must reach P3_CACHE: $cache")
        assertTrue(
            lines.none { it.startsWith("P3_PROMPT ") },
            "a summary has no assembled RequestFrame, so it emits no P3_PROMPT line: $lines",
        )
    }

    @Test
    fun `an unavailable cloud, an empty answer or a throw all answer null and never throw`() {
        val silent = newSummarizer()
        // A transport that never answers can only end at the request's own deadline. `ModelClientGateway` polls the
        // clock while it waits, so the clock has to be able to **reach** the deadline on its own: a fake clock that
        // only moves when the test says so turns this case into an infinite loop rather than a `null`.
        model.onRequest = { _, _ -> /* no terminal at all: the transport never answered */ }
        clock.autoAdvanceNanos = 1_000_000_000L
        assertNull(silent.summarize("要摘要的对话", SUMMARY_MAX_TOKENS), "a stream with no verdict is not a summary")

        val empty = newSummarizer()
        model.onRequest = answers("   ")
        assertNull(empty.summarize("要摘要的对话", SUMMARY_MAX_TOKENS), "a blank answer is not a summary")

        val throwing = newSummarizer()
        model.onRequest = { _, _ -> throw IllegalStateException("cloud down") }
        assertNull(throwing.summarize("要摘要的对话", SUMMARY_MAX_TOKENS), "a failing transport must degrade, not escape")
    }

    @Test
    fun `the summary body never reaches the log`() {
        val marker = "摘要正文标记-7788"
        val summarizer = newSummarizer()
        model.onRequest = answers(marker)

        val lines = mutableListOf<String>()
        val previous = P3Log.sink
        P3Log.sink = { lines += it }
        val context: ConversationContext
        try {
            context = overflowing()
            ContextCompressor(historyBudgetTokens = 3, summaryMaxTokens = SUMMARY_MAX_TOKENS, summarizer = summarizer, scope = tasks)
                .compressIfNeeded(context)
            tasks.joinAll()
        } finally {
            P3Log.sink = previous
        }

        // The marker's arrival in the transcript is the proof the summary ran; without it the log assertion below
        // would pass for the wrong reason (nothing summarised, nothing leaked).
        assertEquals(
            1,
            context.history().count { it is ModelHistoryItem.Text && it.text == marker },
            "the summary never landed, so this test would pass for the wrong reason: ${context.history()}",
        )
        assertTrue(lines.none { it.contains(marker) }, "the summary's text must never reach the log: $lines")
        assertTrue(lines.any { it.startsWith("P3_CONTEXT summary=ok chars=") }, lines.toString())
    }

    @Test
    fun `the compressor hands the summary to the task pool and gives the caller its thread back`() {
        val summarizer = newSummarizer()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        model.onRequest = { request, listener ->
            entered.countDown()
            release.await(5, TimeUnit.SECONDS)
            answers("（摘要）")(request, listener)
        }
        val context = overflowing()
        context.appendUser("刚刚这句")

        // `compressIfNeeded` must return while the summary is still blocked inside the transport: that is the property
        // "the wearer's turn does not pay for a cloud round trip".
        val report = ContextCompressor(historyBudgetTokens = 3, summaryMaxTokens = SUMMARY_MAX_TOKENS, summarizer = summarizer, scope = tasks)
            .compressIfNeeded(context)
        assertEquals(true, report.summaryStarted)
        val beforeRunning = context.size()

        assertTrue(entered.await(5, TimeUnit.SECONDS), "the transport must be reached from the task pool")
        assertEquals(
            beforeRunning,
            context.size(),
            "nothing may be written back before the summary answers",
        )
        release.countDown()
        tasks.joinAll()
        assertEquals(beforeRunning + 1, context.size(), "the summary lands as one placeholder once it answers")
    }

    @Test
    fun `the activated plugin's own compressor carries the production summarizer's answer into the transcript`() {
        // The seam under test is `activate()`: it builds the compressor with `summarizer = ModelSummarizer(...)`, and
        // this proves the **production** object (not a constructor-injected fake) is the one that ran. A small history
        // allowance is injected so a handful of turns reach the compression the module-level tests above cover.
        val contexts = ContextManager(
            policy = smartContextPolicy(
                smartProfile(inputBudgetTokens = 60, usableWindowTokens = 10_000, maxOutputTokens = 1, safetyTokens = 1),
            ),
            promptPlan = smartPromptPlan("测试用指令"),
        )
        val rig = SmartTestRig(contextForTurn = contexts)
        rig.activate(overrides = mapOf(Capability.Embedding to ScriptedEmbeddings()))
        val marker = "（生产摘要标记）"
        rig.model.onRequest = { request, listener ->
            // The plugin's model client serves two shapes: the turn (tools present) and the summary (no tools).
            if (request.tools.isEmpty()) streamText(request, listener, marker) else streamText(request, listener, "好。")
        }

        // The `P3_CONTEXT` lines are read as well: they carry how many compressions ran, which is what makes "one
        // placeholder per compression" checkable instead of a guess at how often a long session compresses.
        val lines = mutableListOf<String>()
        val previous = P3Log.sink
        P3Log.sink = { lines += it }
        try {
            repeat(8) { index ->
                rig.wakeAndFinal("第${index}次请问现在几点了", turnId = "asr-$index")
                rig.tick(4_000)
            }
        } finally {
            P3Log.sink = previous
        }

        val summaryRequests = rig.model.requests.count { it.tools.isEmpty() }
        assertTrue(summaryRequests > 0, "the production compressor never asked the summarizer: ${rig.model.requests.size} requests")
        val summaryRequest = rig.model.requests.first { it.tools.isEmpty() }
        assertEquals(
            PromptCatalog.summarizer(),
            summaryRequest.instructions,
            "the activated plugin must pass the catalog's summarizer prompt, not a compiled-in copy",
        )
        // "Lands in exactly one place" is one placeholder **per truncating compression**, and never more: a long
        // session compresses repeatedly, and a compression whose whole transcript is anchored (`truncated=0`) starts no
        // summary at all. The count is therefore bounded by the log lines rather than assumed equal to them — an
        // earlier placeholder is itself an ordinary message and a later truncation may legitimately discard it.
        val truncations = lines.count { line ->
            val count = line.substringAfter("truncated=", "").takeWhile { it.isDigit() }
            count.isNotEmpty() && count.toInt() > 0
        }
        assertTrue(truncations > 0, "no compression discarded anything, so this test would pass for the wrong reason")
        val wake = contexts.context(ContextRoute.WAKE).history()
        val placeholders = wake.count { it is ModelHistoryItem.Text && it.text == marker }
        assertTrue(
            placeholders in 1..truncations,
            "a summary must land as one message per truncating compression and never more: " +
                "placeholders=$placeholders truncations=$truncations $wake",
        )
        assertTrue(
            wake.any { it is ModelHistoryItem.Text && it.role == MessageRole.ASSISTANT && it.text == marker },
            "the summary never reached the transcript: $wake",
        )
        assertTrue(lines.none { it.contains(marker) }, "the summary's text must never reach the log: $lines")
    }

    @Test
    fun `the outcome of a summary is observable as counts and codes, while its body never is`() {
        // B3's device gap was not "the summary is untested" but "the summary's failure had four possible causes and
        // no line to tell them apart": a profile that is not configured, an answer that came back empty, a request
        // the model path refuses by shape, and a deadline. The JVM fixture fakes the transport, so the third cause is
        // invisible here - which is exactly why the line has to name the cause rather than just say "none".
        val marker = "摘要正文标记-9911"

        val succeeded = newSummarizer()
        model.onRequest = answers(marker)
        val successLines = mutableListOf<String>()
        var previous = P3Log.sink
        P3Log.sink = { successLines += it }
        try {
            assertEquals(marker, succeeded.summarize("要摘要的对话", SUMMARY_MAX_TOKENS))
        } finally {
            P3Log.sink = previous
        }
        val success = successLines.single { it.startsWith("P3_SUMMARY ") }
        assertTrue(success.contains("ok=true"), success)
        assertTrue(success.contains("chars=${marker.length}"), success)
        assertTrue(successLines.none { it.contains(marker) }, "the summary body must never reach the log: $successLines")

        // An answer that arrived empty: the transport *worked*, so the cause is the model's answer, not the wiring.
        val blank = newSummarizer()
        model.onRequest = answers("   ")
        val blankLines = mutableListOf<String>()
        previous = P3Log.sink
        P3Log.sink = { blankLines += it }
        try {
            assertNull(blank.summarize("要摘要的对话", SUMMARY_MAX_TOKENS))
        } finally {
            P3Log.sink = previous
        }
        val blankLine = blankLines.single { it.startsWith("P3_SUMMARY ") }
        assertTrue(blankLine.contains("ok=false"), blankLine)
        assertTrue(blankLine.contains("terminal=COMPLETED"), "an empty answer is a completed-but-empty stream: $blankLine")

        // A profile the plugin is configured with but the host cannot resolve: refused before any request is built.
        val unconfigured = newSummarizer(profile = null)
        val unconfiguredLines = mutableListOf<String>()
        previous = P3Log.sink
        P3Log.sink = { unconfiguredLines += it }
        try {
            assertNull(unconfigured.summarize("要摘要的对话", SUMMARY_MAX_TOKENS))
        } finally {
            P3Log.sink = previous
        }
        val unconfiguredLine = unconfiguredLines.single { it.startsWith("P3_SUMMARY ") }
        assertTrue(unconfiguredLine.contains("code=PROFILE_MISSING"), unconfiguredLine)

        // A request the model path refuses *by throwing* (an over-budget explicit context, a revision mismatch, an
        // invalid endpoint): the sanitized code is the only thing that may be logged about it.
        val refused = newSummarizer()
        model.onRequest = { _, _ -> throw IllegalStateException("Explicit context exceeds safe budget") }
        val refusedLines = mutableListOf<String>()
        previous = P3Log.sink
        P3Log.sink = { refusedLines += it }
        try {
            assertNull(refused.summarize("要摘要的对话", SUMMARY_MAX_TOKENS))
        } finally {
            P3Log.sink = previous
        }
        val refusedLine = refusedLines.single { it.startsWith("P3_SUMMARY ") }
        assertTrue(refusedLine.contains("error=Explicit context exceeds safe budget"), refusedLine)
    }

    private companion object {
        /** The summary output cap the fixture hands the summarizer; the production value comes from `ContextPolicy`. */
        const val SUMMARY_MAX_TOKENS = 64

        /** The request bound the fixture hands the summarizer; unrelated to the plugin's own constant. */
        const val SUMMARY_DEADLINE_NANOS = 8_000_000_000L

        /** The index is 256-dim in production; a narrower vector is rejected by `VectorIndex`. */
        const val DIM = 256
    }
}
