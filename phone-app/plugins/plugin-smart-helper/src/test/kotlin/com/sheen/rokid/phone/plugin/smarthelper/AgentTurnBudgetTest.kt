package com.sheen.rokid.phone.plugin.smarthelper

import kotlin.test.Test
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * 2026-10-22 (D197): an agent turn is **not** bounded by a total wall-clock budget.
 *
 * The device evidence that triggered this: a working 22-step proactive follow-up ended as `DEADLINE` at exactly 180 s
 * (`modelMs=120997` + `toolMs=56056`) and the app told the wearer "回答超时了，稍后再试". Mainstream harnesses bound a
 * run with a turn/step count and per-request timeouts, not a total duration. The plugin therefore passes a 24 h sanity
 * ceiling (effectively unbounded) and keeps `AgentLoop.maxSteps` (24), `ProgressGuard`, the error streak and the
 * profile's per-request timeout as the real guards. The proactive hint turn keeps its own 15 s because it is one model
 * call, not an execution loop.
 */
class AgentTurnBudgetTest {

    @Test
    fun `an agent turn carries the effectively unbounded budget, not the old 180 s cap`() {
        val rig = SmartTestRig()
        rig.activate()

        val budgetNanos = rig.plugin.agentTurnBudgetNanosForTest()

        assertTrue(
            budgetNanos > 23L * 60L * 60L * 1_000_000_000L,
            "a working multi-step turn must not be capped at 180 s; was $budgetNanos ns",
        )
        assertNotEquals(180_000_000_000L, budgetNanos, "the old 180 s product cap must be gone")
    }
}
