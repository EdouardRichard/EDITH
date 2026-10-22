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
 * refusal that **did not** run, a declined confirmation must come out as a refusal that did not run either, and any
 * other failed bare stable code means the executor *did* run (the policy allowed it; the tool or channel then
 * refused/failed) - a different fact from a policy refusal, and the one that keeps the escalation rate honest.
 *
 * Since 0.3 Phase 2.2 `P3_TOOL detail=` is the envelope-derived `execution.code` (or `ok`), never the old
 * `"Refused: "`/`"Failed: "` prefixed detail; the pre-dispatch codes are the only ones that did not execute.
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
                tool("compose_note", false, "LAUNCH_REFUSED"),
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
        // one would make the escalation count meaningless. The envelope's stable code is bare - no legacy prefix.
        val turns = extractTurns(listOf(start(), tool("set_brightness", false, "ACTION_CHANNEL_UNAVAILABLE"), end()))

        val step = turns.single().steps.single()
        assertEquals("allow", step.decision)
        assertTrue(step.executed, "执行器跑了，只是被通道拒绝")
    }

    @Test fun `a dispatched tool failure's bare code means the executor did run`() {
        // `STATE_UNCHANGED` is a post-dispatch verdict: the call really reached the tool. The old parser only trusted
        // the `Refused:` prefix, so a prefix-free failure looked like a pre-dispatch error and biased the metrics.
        val turns = extractTurns(listOf(start(), tool("open", false, "STATE_UNCHANGED"), end()))

        val step = turns.single().steps.single()
        assertEquals("allow", step.decision)
        assertTrue(step.executed, "动作已派发并执行，只是结果未生效")
    }

    @Test fun `a throwing dispatched tool still counts as executed`() {
        val turns = extractTurns(listOf(start(), tool("device", false, "TOOL_THREW"), end()))

        val step = turns.single().steps.single()
        assertEquals("allow", step.decision)
        assertTrue(step.executed, "TOOL_THREW 发生在执行之后")
    }

    @Test fun `a confirmed timeout was dispatched and therefore executed`() {
        // ACTION_TIMEOUT is the loop's post-dispatch verdict: the call reached the executor and then overran its bound.
        // However failed it reads, it is not a pre-dispatch rejection and must not deflate the execution count.
        val turns = extractTurns(listOf(start(), tool("device", false, "ACTION_TIMEOUT"), end()))

        val step = turns.single().steps.single()
        assertEquals("allow", step.decision)
        assertTrue(step.executed, "超时发生在派发之后")
    }

    @Test fun `an uncertain timeout was dispatched and therefore executed`() {
        // ACTION_TIMEOUT_UNCERTAIN escalates the whole run, but the call it describes did reach the executor. Only the
        // outcome is unknown, not whether it ran.
        val turns = extractTurns(listOf(start(), tool("device", false, "ACTION_TIMEOUT_UNCERTAIN"), end()))

        val step = turns.single().steps.single()
        assertEquals("allow", step.decision)
        assertTrue(step.executed, "uncertain 只说明结果不确定，不说明没有执行")
    }

    @Test fun `the loop's own repeat code never counts as an executed step`() {
        // `LOOP_REPEAT` is the loop's hang-guard termination code, not a tool result: the repeated call it names was
        // stopped before dispatch. It must stay in the "did not run" set exactly like a detector's LOOP_WARNING label.
        val turns = extractTurns(listOf(start(), tool("device", false, "LOOP_REPEAT"), end()))

        val step = turns.single().steps.single()
        assertNull(step.decision)
        assertFalse(step.executed, "被循环检测器拦下的调用没有执行")
    }

    @Test fun `a legacy prefixed line from an old transcript still reads as dispatched`() {
        // Pre-0.3 `P3_TOOL` lines carried the tools' old `Refused: `/`Failed: ` detail prefixes. The extractor no longer
        // uses a prefix as dispatch evidence, but replaying those lines must not turn a dispatched failure into a
        // pre-dispatch rejection. `Failed: STATE_UNCHANGED` also pins the under-counting fix: the old parser only
        // trusted `Refused:`, so this dispatched post-dispatch failure used to be scored as not executed.
        val turns = extractTurns(
            listOf(
                start(),
                tool("open_app", false, "Refused: APP_NOT_VISIBLE"),
                tool("set_brightness", false, "Failed: STATE_UNCHANGED"),
                end(),
            ),
        )

        val steps = turns.single().steps
        assertEquals(listOf("allow", "allow"), steps.map { it.decision })
        assertTrue(steps.all { it.executed }, "旧前缀行仍然表示已派发")
        assertEquals(listOf("Refused: APP_NOT_VISIBLE", "Failed: STATE_UNCHANGED"), steps.map { it.result })
    }

    @Test fun `a detector warning never counts as an executed step`() {
        // A repeated-call warning is synthesized before dispatch, so its `LOOP_*` payload must keep the old
        // "did not run" reading even though it is not one of the registry's pre-dispatch codes.
        val turns = extractTurns(
            listOf(start(), tool("device", false, "LOOP_WARNING:GENERIC_REPEAT: the same step has repeated."), end()),
        )

        val step = turns.single().steps.single()
        assertNull(step.decision)
        assertFalse(step.executed, "被检测器拦下的调用没有执行")
    }

    @Test fun `a successful step is an allowed action that executed`() {
        val turns = extractTurns(listOf(start(), tool("compose_note", true, "ok"), end()))

        val step = turns.single().steps.single()
        assertEquals("allow", step.decision)
        assertTrue(step.executed)
    }

    @Test fun `an unknown tool or bad arguments did not execute`() {
        val turns = extractTurns(
            listOf(start(), tool("nope", false, "UNKNOWN_TOOL"), tool("dial", false, "ARGUMENTS_INVALID"), end()),
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
