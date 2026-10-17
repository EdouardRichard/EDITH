package edith.plugins.smart

import com.sheen.rokid.plugin.CancellationHandle
import com.sheen.rokid.plugin.Capability
import com.sheen.rokid.plugin.EmbedResult
import com.sheen.rokid.plugin.EmbeddingService
import com.sheen.rokid.plugin.ModelInfo
import com.sheen.rokid.plugin.ModelRequest
import com.sheen.rokid.plugin.ModelStreamEvent
import com.sheen.rokid.plugin.TaskScope
import edith.plugins.smart.context.TokenBudget
import edith.plugins.smart.memory.MemoryLog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * edith-0.2 Phase B (B3): the **production budget path** is drivable.
 *
 * Until now the compressor's suites injected a `ContextCompressor` and the plugin's first production-shape test
 * injected a `ContextManager`, so in the shipped shape (neither injected) what was pinned is only "the compressor is
 * built and called". Exceeding history's real allowance of 1638 tokens takes roughly 200 turns at the wake cooldown,
 * which is why that path had no test. This closes it with a **budget seam**: the plugin's own `TokenBudget` is
 * injectable, so a handful of turns can overflow it without touching the default.
 *
 * The default is pinned too, by number: with no injected budget the session keeps the spec's own split.
 */
class ProductionBudgetCompressionTest {

    /** One 256-dim vector per text; retrieval is not the subject here, so every query matches. */
    private class ScriptedEmbeddings : EmbeddingService {
        override fun embed(text: String): EmbedResult = EmbedResult.Success(FloatArray(DIM) { 1f })
        override val isAvailable = true
        override val modelInfo: ModelInfo? = null
        override val dimensions = DIM
    }

    /**
     * Records each `launch` and runs it there and then: the summary must go through the plugin's task pool (the
     * compressor's own thread policy is the thing being exercised), while the test still needs its result to have
     * landed before it asserts.
     */
    private class RecordingTasks : TaskScope {
        var launches = 0
        override fun launch(block: () -> Unit): CancellationHandle {
            launches++
            block()
            return object : CancellationHandle { override fun cancel() = Unit }
        }
        override fun cancelAll() = Unit
    }

    /**
     * Answers both shapes the plugin's client serves: a turn (`tools` present) with a short reply, and a summary (no
     * tools) with one distinctive sentence. The handler is installed **once**, before the turns run — installing it
     * inside the turn loop is how the first draft lost the summary branch and had the turns answered by the default
     * no-op handler.
     */
    private fun answerTurnsAndSummaries(rig: SmartTestRig, summary: String) {
        rig.model.onRequest = { request: ModelRequest, listener: (ModelStreamEvent) -> Unit ->
            if (request.tools.isEmpty()) streamText(request, listener, summary) else streamText(request, listener, "好。")
        }
    }

    /**
     * The turns that overflow a 60-token total budget (history's own share is 24). Each utterance is long enough that
     * the second turn's history already exceeds the allowance, so the production compressor truncates immediately
     * instead of after ~200 turns.
     */
    private fun runTurns(rig: SmartTestRig, turns: Int = 3) {
        repeat(turns) { index ->
            rig.wakeAndFinal("第${index}次请问现在到底是几点了呢", turnId = "asr-$index")
            rig.tick(4_000)
        }
    }

    private fun activateSmall(rig: SmartTestRig, storage: SmartFakeStorage, tasks: TaskScope? = null) {
        rig.activate(
            overrides = mapOf(
                Capability.Storage to storage,
                Capability.Embedding to ScriptedEmbeddings(),
            ),
            tasks = tasks,
        )
    }

    @Test
    fun `the activated session compresses and summarises when the injected budget is exceeded`() {
        val storage = SmartFakeStorage()
        val tasks = RecordingTasks()
        val rig = SmartTestRig(budget = TokenBudget(total = 60))
        activateSmall(rig, storage, tasks)
        answerTurnsAndSummaries(rig, summary = "SUMMARK")

        runTurns(rig)

        // ① the production session reached the summarizer at all; ② it went through the plugin's task pool; ③ its answer
        // landed in the store; ④ the injected budget is the one the session's compressor measured against.
        val summaryRequests = rig.model.requests.count { it.tools.isEmpty() }
        assertTrue(summaryRequests > 0, "the production session never reached the summarizer: ${rig.model.requests.size} requests")
        assertTrue(tasks.launches > 0, "the summary must be handed to the task pool, not run inline from the turn")
        val stored = MemoryLog(storage).entries().map { it.text }
        assertTrue("SUMMARK" in stored, "the summary never reached the store: $stored")
        assertEquals(
            // A 60-token total splits 6/15/6/24/3/6; with no SKILL and no memory the history allowance is its own 24
            // plus the whole of skill's 15 and memory's 6.
            45,
            rig.plugin.historyBudgetTokensForTest(),
            "the injected budget did not reach the session's compressor",
        )
    }

    @Test
    fun `a discarded message reaches the store before it leaves the transcript`() {
        // §4.4 step 2 / §4.9 ordering, on the **production** path: the plugin's own compressor, its own memory store,
        // and the budget seam that makes a truncation happen at all. A message discarded without being stored first is
        // the one failure this ordering exists to prevent.
        val storage = SmartFakeStorage()
        val rig = SmartTestRig(budget = TokenBudget(total = 60))
        activateSmall(rig, storage)
        answerTurnsAndSummaries(rig, summary = "SUMMARK")

        runTurns(rig)

        val stored = MemoryLog(storage).entries().map { it.text }
        assertTrue(
            stored.any { it.contains("第0次") },
            "a message the production compression discarded was never stored: $stored",
        )
    }

    @Test
    fun `without an injected budget the session keeps the spec's allowance`() {
        // The seam must not move the default. §4.3 with no SKILL and no memory grants history everything except the
        // system prompt, the in-flight input and the output reserve: 4096 * (1 - 0.10 - 0.05 - 0.10) = 3072.
        val rig = SmartTestRig()
        val plugin = rig.activate(
            overrides = mapOf(
                Capability.Storage to SmartFakeStorage(),
                Capability.Embedding to ScriptedEmbeddings(),
            ),
        )
        answerTurnsAndSummaries(rig, summary = "SUMMARK")
        runTurns(rig, turns = 1)

        assertEquals(
            // The asserted value is read off the manager that actually served a turn, so a plugin that ignored the
            // default budget could not satisfy it by coincidence.
            3072,
            plugin.historyBudgetTokensForTest(),
            "the production default must still be the spec's own six-way split",
        )
    }

    private companion object {
        /** The index is 256-dim in production; a narrower vector is rejected by `VectorIndex`. */
        const val DIM = 256
    }
}
