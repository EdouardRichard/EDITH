package edith.probe.eval

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * P3.4 §6.4: turning the device's own `P3_` lines into the traces [agentMetrics] scores.
 *
 * The rules that matter are the ones that would otherwise hide a violation: a policy refusal must come out as a
 * refusal that **did not** run, a declined confirmation must come out as a refusal that did not run either, and a
 * `Refused:` detail means the executor *did* run (the policy allowed it; the channel refused) - a different fact from a
 * policy refusal, and the one that keeps the escalation rate honest.
 *
 * Assertion messages come last (`kotlin.test`).
 */
class TraceExtractorTest {

    private fun start(id: String = "t1") = "10-11 03:00:00.000 I/System.out( 1): EdithSmart P3_TURN start turn=$id"
    private fun tool(action: String, ok: Boolean, detail: String, id: String = "") =
        "10-11 03:00:01.000 I/System.out( 1): EdithSmart P3_TOOL call=$action ok=$ok detail=$detail"
    private fun end(id: String = "t1", outcome: String = "COMPLETED", code: String = "-", steps: Int = 1) =
        "10-11 03:00:02.000 I/System.out( 1): EdithSmart P3_TURN end turn=$id outcome=$outcome code=$code steps=$steps"

    @Test fun `a turn is extracted with its steps in order`() {
        val turns = extractTurns(
            listOf(
                start(),
                tool("open_settings", true, "ok"),
                tool("compose_note", false, "Refused: LAUNCH_REFUSED"),
                end(steps = 2),
            ),
        )

        assertEquals(1, turns.size)
        val steps = turns.single().steps
        assertEquals(listOf("open_settings", "compose_note"), steps.map { it.action })
        assertEquals(listOf(true, false), steps.map { it.ok })
        assertEquals("COMPLETED", turns.single().outcome)
        assertNull(turns.single().safeCode, "code=- 表示没有安全码")
    }

    @Test fun `a policy refusal is a refusal that did not reach the executor`() {
        val turns = extractTurns(listOf(start(), tool("uninstall", false, "REFUSED_BY_POLICY"), end()))

        val step = turns.single().steps.single()
        assertEquals("refuse", step.decision)
        assertFalse(step.executed, "策略拒绝时执行器根本没跑")
    }

    @Test fun `a declined confirmation is a refusal that did not reach the executor`() {
        val turns = extractTurns(listOf(start(), tool("dial", false, "CONFIRMATION_DENIED"), end()))

        val step = turns.single().steps.single()
        assertEquals("require_confirmation", step.decision)
        assertFalse(step.executed)
        assertEquals("CONFIRMATION_DENIED", step.result)
    }

    @Test fun `a refused channel means the executor did run`() {
        // The policy allowed the action; the privileged channel refused it. That is not a policy refusal, and calling it
        // one would make the escalation count meaningless.
        val turns = extractTurns(listOf(start(), tool("set_brightness", false, "Refused: ACTION_CHANNEL_UNAVAILABLE"), end()))

        val step = turns.single().steps.single()
        assertEquals("allow", step.decision)
        assertTrue(step.executed, "执行器跑了，只是被通道拒绝")
    }

    @Test fun `a successful step is an allowed action that executed`() {
        val turns = extractTurns(listOf(start(), tool("compose_note", true, "ok"), end()))

        val step = turns.single().steps.single()
        assertEquals("allow", step.decision)
        assertTrue(step.executed)
    }

    @Test fun `an unknown tool or bad arguments did not execute`() {
        val turns = extractTurns(
            listOf(start(), tool("nope", false, "UnknownTool"), tool("dial", false, "ARGUMENTS_INVALID"), end()),
        )

        assertTrue(turns.single().steps.all { !it.executed })
        assertTrue(turns.single().steps.all { it.decision == null })
    }

    @Test fun `two interleaved turns are attributed by their id`() {
        val turns = extractTurns(
            listOf(
                start("a"), tool("open_app", true, "ok"), start("b"), tool("open_url", true, "ok"),
                end("a", steps = 1), end("b", steps = 1),
            ),
        )

        assertEquals(listOf("a", "b"), turns.map { it.turnId })
        assertEquals(listOf("open_app"), turns.first { it.turnId == "a" }.steps.map { it.action })
        assertEquals(listOf("open_url"), turns.first { it.turnId == "b" }.steps.map { it.action })
    }

    @Test fun `a turn with no end line is reported as unfinished rather than dropped`() {
        val turns = extractTurns(listOf(start("a"), tool("open_app", true, "ok")))

        assertEquals(1, turns.size)
        assertEquals("UNKNOWN", turns.single().outcome, "没有 end 行就不能算完成")
    }

    @Test fun `unrelated lines are ignored`() {
        val turns = extractTurns(
            listOf(
                "10-11 03:00:00.000 I/EdithP3 ( 1): P3_ACOUSTIC label=Speech",
                "10-11 03:00:00.100 I/EdithP3 ( 1): P3_PROACTIVE skip reason=SHORT",
                start(), end(),
            ),
        )

        assertEquals(1, turns.size)
        assertTrue(turns.single().steps.isEmpty())
    }

    @Test fun `no production marker exists for context degradation so no turn is marked degraded`() {
        // `ContextPlanner` has no production caller (P3.1-C's residual), so there is no degradation event on the device
        // to hold a task through. Reporting the retention rate as 100% would invent the very event that never happened.
        val turns = extractTurns(listOf(start(), tool("open_app", true, "ok"), end()))

        assertFalse(turns.single().degraded)
    }

    @Test fun `a tool line belongs to the turn that is still open, not to the last one that started`() {
        // The real shape this comes from (agent batch): turn A opens, waits on a confirmation; turn B opens and finishes;
        // A's own confirmation is answered *after* B has ended, and A's tool line lands there. Attributing by "last
        // started" hands A's step to B (which already reported steps=1) and leaves A with none.
        val turns = extractTurns(
            listOf(
                "10-11 06:58:40.253 I/System.out( 1): EdithSmart P3_TURN start turn=A",
                "10-11 06:59:09.980 I/System.out( 1): EdithSmart P3_TURN start turn=B",
                "10-11 06:59:14.340 I/System.out( 1): EdithSmart P3_TOOL call=set_brightness ok=false detail=CONFIRMATION_DENIED",
                "10-11 06:59:14.900 I/System.out( 1): EdithSmart P3_TURN end turn=B outcome=COMPLETED code=- steps=1",
                "10-11 06:59:24.217 I/System.out( 1): EdithSmart P3_TOOL call=set_brightness ok=false detail=CONFIRMATION_DENIED",
                "10-11 06:59:24.600 I/System.out( 1): EdithSmart P3_TURN end turn=A outcome=CANCELLED code=- steps=1",
            ),
        )

        assertEquals(1, turns.first { it.turnId == "A" }.steps.size, "A 的步骤必须算在 A 头上")
        assertEquals(1, turns.first { it.turnId == "B" }.steps.size, "B 的步骤必须算在 B 头上")
    }
}
