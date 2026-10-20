package com.sheen.rokid.phone.plugin.smarthelper

import com.sheen.rokid.agent.ContextPolicy
import com.sheen.rokid.agent.ContextTuning
import com.sheen.rokid.plugin.CancellationHandle
import com.sheen.rokid.plugin.Capability
import com.sheen.rokid.plugin.EmbedResult
import com.sheen.rokid.plugin.EmbeddingService
import com.sheen.rokid.plugin.ModelInfo
import com.sheen.rokid.plugin.ModelRequest
import com.sheen.rokid.plugin.ModelStreamEvent
import com.sheen.rokid.plugin.TaskScope
import com.sheen.rokid.phone.plugin.smarthelper.memory.MemoryLog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * edith-0.2 Phase B (B3), migrated to 0.3's profile-driven budget (Task 11): the **production budget path** is
 * drivable.
 *
 * Until now the compressor's suites injected a `ContextCompressor` and the plugin's production-shape test injected a
 * `ContextManager`, so in the shipped shape (neither injected) what was pinned is only "the compressor is built and
 * called". Exceeding a real allowance takes roughly 200 turns at the wake cooldown, which is why that path had no
 * test. This closes it with a **policy seam**: the plugin derives its `ContextPolicy` from the session profile, and
 * the test lowers only `ContextTuning.compressionThreshold`, so a handful of turns overflow the history allowance
 * while the model still receives the profile's full frame window.
 *
 * The policy — not a fixed 4096 split — is pinned too, by derivation: the asserted number is read from
 * `ContextPolicy.from(smartProfile())`, and a second profile is shown to produce a different one.
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
     * The turns that overflow the fixture's allowance. `smartProfile`'s hard input is 2 000 tokens; a 0.01
     * compression threshold gives a 20-token end-of-turn allowance, so the second turn's history already exceeds it
     * and the production compressor truncates immediately instead of after ~200 turns.
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
    fun `the activated session compresses and summarises when the policy allowance is exceeded`() {
        val storage = SmartFakeStorage()
        val tasks = RecordingTasks()
        val tuning = ContextTuning(compressionThreshold = 0.01)
        val rig = SmartTestRig(contextTuning = tuning)
        activateSmall(rig, storage, tasks)
        answerTurnsAndSummaries(rig, summary = "SUMMARK")

        runTurns(rig)

        // ① the production session reached the summarizer at all; ② it went through the plugin's task pool; ③ its answer
        // landed in the store; ④ the profile-derived policy is the one the session's compressor measured against.
        val summaryRequests = rig.model.requests.count { it.tools.isEmpty() }
        assertTrue(summaryRequests > 0, "the production session never reached the summarizer: ${rig.model.requests.size} requests")
        assertTrue(tasks.launches > 0, "the summary must be handed to the task pool, not run inline from the turn")
        val stored = MemoryLog(storage).entries().map { it.text }
        assertTrue("SUMMARK" in stored, "the summary never reached the store: $stored")
        assertEquals(
            // The profile's 2 000-token hard input with the fixture's 0.01 threshold: floor(2 000 * 0.01) = 20.
            ContextPolicy.from(smartProfile(), tuning).softLimit,
            rig.plugin.historyBudgetTokensForTest(),
            "the profile-derived policy did not reach the session's compressor",
        )
        assertEquals(20, rig.plugin.historyBudgetTokensForTest(), "the tuning seam must produce a small, exact allowance")
    }

    @Test
    fun `a discarded message reaches the store before it leaves the transcript`() {
        // §4.4 step 2 / §4.9 ordering, on the **production** path: the plugin's own compressor, its own memory store,
        // and the policy seam that makes a truncation happen at all. A message discarded without being stored first is
        // the one failure this ordering exists to prevent.
        val storage = SmartFakeStorage()
        val rig = SmartTestRig(contextTuning = ContextTuning(compressionThreshold = 0.01))
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
    fun `the profile policy decides the session's allowance, never a fixed 4096`() {
        // The seam must not move production's derivation: the number is what `ContextPolicy.from(profile)` yields, and
        // a differently sized profile yields a different one. Neither is the old fixed split.
        val default = SmartTestRig()
        val defaultPlugin = default.activate(
            overrides = mapOf(
                Capability.Storage to SmartFakeStorage(),
                Capability.Embedding to ScriptedEmbeddings(),
            ),
        )
        answerTurnsAndSummaries(default, summary = "SUMMARK")
        runTurns(default, turns = 1)

        val wide = SmartTestRig()
        // Set before activation: the policy is built once per session from the profile that exists then.
        val wideProfile = smartProfile(inputBudgetTokens = 20_000, usableWindowTokens = 40_000)
        wide.profile = wideProfile
        val widePlugin = wide.activate(
            overrides = mapOf(
                Capability.Storage to SmartFakeStorage(),
                Capability.Embedding to ScriptedEmbeddings(),
            ),
        )
        answerTurnsAndSummaries(wide, summary = "SUMMARK")
        runTurns(wide, turns = 1)

        val defaultAllowance = defaultPlugin.historyBudgetTokensForTest()
        val wideAllowance = widePlugin.historyBudgetTokensForTest()

        assertEquals(ContextPolicy.from(smartProfile()).softLimit, defaultAllowance)
        assertEquals(ContextPolicy.from(wideProfile).softLimit, wideAllowance)
        assertEquals(1_500, defaultAllowance, "a 2 000-token hard input leaves a 1 500-token compression allowance")
        assertEquals(15_000, wideAllowance)
        assertTrue(
            wideAllowance > defaultAllowance,
            "the profile, not a constant, must decide the budget",
        )
        assertTrue(defaultAllowance != 4_096 && wideAllowance != 4_096, "the fixed 4096 split is gone")
    }

    @Test
    fun `a session without a profile fails closed instead of inventing a budget`() {
        // 0.3 design §5.2: a missing profile is a configuration error, not a reason to fall back to a fixed budget.
        // No context manager is built, and the model gateway refuses the turn with `PROFILE_MISSING` before any
        // request reaches the transport.
        val rig = SmartTestRig().also { it.profile = null }
        rig.activate(
            overrides = mapOf(
                Capability.Storage to SmartFakeStorage(),
                Capability.Embedding to ScriptedEmbeddings(),
            ),
        )
        rig.model.onRequest = { request, listener -> streamText(request, listener, "不该出现") }

        rig.wakeAndFinal("你好")

        assertTrue(rig.model.requests.isEmpty(), "no profile means no budget, so no request may be sent")
        assertFailsWith<IllegalStateException> {
            rig.plugin.historyBudgetTokensForTest()
        }
    }

    private companion object {
        /** The index is 256-dim in production; a narrower vector is rejected by `VectorIndex`. */
        const val DIM = 256
    }
}
