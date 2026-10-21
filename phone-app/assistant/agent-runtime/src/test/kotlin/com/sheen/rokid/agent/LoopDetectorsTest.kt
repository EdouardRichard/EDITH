package com.sheen.rokid.agent

import com.sheen.rokid.plugin.ToolExecution
import com.sheen.rokid.plugin.ToolOutcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Task 12 (R55/R56, D3 §1.1–§1.4): the four pre-execution detectors and the error-streak tracker, as a pure state
 * machine. The loop-level wiring (synthetic tool results, termination codes, no extra model round trip) is
 * `AgentLoopGuardTest`'s; this file pins the thresholds, the identity rules and the exact reset behaviour.
 *
 * Every test drives [LoopDetectors] the way `AgentLoop` does: `beforeCall` before a call (which is where a warning or a
 * critical is decided), then `afterCall` only when that call really executed. A skipped warning is therefore expressed
 * by simply not calling `afterCall` — which is also what proves a skipped call cannot feed the global counter.
 */
class LoopDetectorsTest {

    private val observeArgs = """{"action":"observe"}"""

    private fun LoopDetectors.allow(tool: String, args: String = "{}", observation: Boolean = false): LoopDecision =
        beforeCall(tool, args, observation)

    private fun LoopDetectors.run(
        tool: String,
        args: String = "{}",
        observation: Boolean = false,
        write: Boolean = !observation,
        fingerprint: String? = null,
    ): LoopDecision {
        val decision = beforeCall(tool, args, observation)
        if (decision == LoopDecision.Allow) afterCall(executed = true, write = write, fingerprint = fingerprint)
        return decision
    }

    /**
     * Poll tests drive the same tool+args repeatedly, which is also a generic repeat by construction. The generic
     * thresholds are lifted so the test isolates the poll streak it names instead of passing for the generic pattern.
     */
    private fun pollOnly() = LoopDetectors(genericWarn = 100, genericStop = 101)

    // ---------------------------------------------------------------- generic_repeat

    @Test
    fun `the third identical attempt warns and the fifth is critical`() {
        val detectors = LoopDetectors()

        assertEquals(LoopDecision.Allow, detectors.run("tap"))
        assertEquals(LoopDecision.Allow, detectors.run("tap"))
        // The 3rd occurrence itself is skipped with the warning (R55: "同身份连续 3 次 → Warning（跳过该次执行）").
        assertEquals(LoopDecision.Warn(LoopPattern.GENERIC_REPEAT), detectors.allow("tap"))
        // The skipped call is still an attempt: the 4th warns again and the 5th terminates.
        assertEquals(LoopDecision.Warn(LoopPattern.GENERIC_REPEAT), detectors.allow("tap"))
        assertEquals(LoopDecision.Critical(LoopPattern.GENERIC_REPEAT), detectors.allow("tap"))
    }

    @Test
    fun `a different identity resets the generic streak`() {
        val detectors = LoopDetectors()

        detectors.run("tap", """{"where":"a"}""")
        detectors.run("tap", """{"where":"a"}""")
        detectors.run("tap", """{"where":"b"}""")

        assertEquals(LoopDecision.Allow, detectors.run("tap", """{"where":"a"}"""))
        assertEquals(LoopDecision.Allow, detectors.run("tap", """{"where":"a"}"""))
        assertEquals(
            LoopDecision.Warn(LoopPattern.GENERIC_REPEAT),
            detectors.allow("tap", """{"where":"a"}"""),
        )
    }

    @Test
    fun `canonical json key order does not change the identity`() {
        val detectors = LoopDetectors()

        detectors.run("tap", """{"a":1,"b":2}""")
        detectors.run("tap", """{"b":2,"a":1}""")

        assertEquals(
            LoopDecision.Warn(LoopPattern.GENERIC_REPEAT),
            detectors.allow("tap", """{"a":1,"b":2}"""),
        )
    }

    @Test
    fun `a write whose observation changed resets the generic streak`() {
        val detectors = LoopDetectors()

        // The first write lands on screen A; the second one changes the screen, which is real progress: the signature
        // is no longer a "consecutive repeat" even though the same tool+args comes back.
        detectors.run("tap", fingerprint = "screen-a")
        detectors.run("tap", fingerprint = "screen-b")

        assertEquals(LoopDecision.Allow, detectors.run("tap", fingerprint = "screen-b"))
    }

    // ---------------------------------------------------------------- poll_no_progress

    @Test
    fun `three unchanged observations warn and five are critical`() {
        val detectors = pollOnly()

        assertEquals(LoopDecision.Allow, detectors.run("device", observeArgs, observation = true, fingerprint = "screen"))
        assertEquals(LoopDecision.Allow, detectors.run("device", observeArgs, observation = true, fingerprint = "screen"))
        assertEquals(
            LoopDecision.Warn(LoopPattern.POLL_NO_PROGRESS),
            detectors.allow("device", observeArgs, observation = true),
        )
        assertEquals(
            LoopDecision.Warn(LoopPattern.POLL_NO_PROGRESS),
            detectors.allow("device", observeArgs, observation = true),
        )
        assertEquals(
            LoopDecision.Critical(LoopPattern.POLL_NO_PROGRESS),
            detectors.allow("device", observeArgs, observation = true),
        )
    }

    @Test
    fun `a changed observation fingerprint resets the poll streak`() {
        val detectors = pollOnly()

        detectors.run("device", observeArgs, observation = true, fingerprint = "screen-a")
        // The second observation changed the screen, so the next identical observation is only the second attempt.
        detectors.run("device", observeArgs, observation = true, fingerprint = "screen-b")

        assertEquals(
            LoopDecision.Allow,
            detectors.run("device", observeArgs, observation = true, fingerprint = "screen-b"),
        )
    }

    @Test
    fun `an observation without a fingerprint is never counted`() {
        val detectors = pollOnly()

        repeat(5) {
            assertEquals(
                LoopDecision.Allow,
                detectors.run("device", observeArgs, observation = true, fingerprint = null),
                "attempt ${it + 1}",
            )
        }
    }

    @Test
    fun `a write interrupts the poll chain`() {
        val detectors = pollOnly()

        detectors.run("device", observeArgs, observation = true, fingerprint = "screen")
        detectors.run("device", """{"action":"act","kind":"tap"}""", observation = false, fingerprint = "screen")

        assertEquals(
            LoopDecision.Allow,
            detectors.run("device", observeArgs, observation = true, fingerprint = "screen"),
        )
    }

    // ---------------------------------------------------------------- ping_pong

    @Test
    fun `two alternating cycles warn and three are critical when nothing changes`() {
        val detectors = LoopDetectors()

        assertEquals(LoopDecision.Allow, detectors.run("a", "{}", observation = true, fingerprint = "same"))
        assertEquals(LoopDecision.Allow, detectors.run("b", "{}", observation = true, fingerprint = "same"))
        assertEquals(LoopDecision.Allow, detectors.run("a", "{}", observation = true, fingerprint = "same"))
        // The fourth attempt completes the second A,B cycle: warn and skip it (the skipped attempt still counts).
        assertEquals(LoopDecision.Warn(LoopPattern.PING_PONG), detectors.allow("b", "{}", observation = true))
        assertEquals(LoopDecision.Warn(LoopPattern.PING_PONG), detectors.allow("a", "{}", observation = true))
        // The sixth completes the third cycle; neither signature changed the screen, so this is a real ping-pong.
        assertEquals(LoopDecision.Critical(LoopPattern.PING_PONG), detectors.allow("b", "{}", observation = true))
    }

    @Test
    fun `a ping pong with a real observation change is not critical`() {
        val detectors = LoopDetectors()

        detectors.run("a", "{}", observation = true, fingerprint = "same")
        // B really changed the screen once: the model may genuinely be advancing, so critical is forbidden.
        detectors.run("b", "{}", observation = true, fingerprint = "changed")
        detectors.run("a", "{}", observation = true, fingerprint = "same")
        assertEquals(LoopDecision.Warn(LoopPattern.PING_PONG), detectors.allow("b", "{}", observation = true))
        assertEquals(LoopDecision.Warn(LoopPattern.PING_PONG), detectors.allow("a", "{}", observation = true))
        assertEquals(
            LoopDecision.Warn(LoopPattern.PING_PONG),
            detectors.allow("b", "{}", observation = true),
            "with a changed observation in the alternating segment it must warn, never terminate",
        )
    }

    @Test
    fun `three distinct signatures are not a ping pong`() {
        val detectors = LoopDetectors()

        detectors.run("a", "{}", observation = true, fingerprint = "same")
        detectors.run("b", "{}", observation = true, fingerprint = "same")
        detectors.run("c", "{}", observation = true, fingerprint = "same")
        detectors.run("a", "{}", observation = true, fingerprint = "same")
        detectors.run("b", "{}", observation = true, fingerprint = "same")

        assertEquals(LoopDecision.Allow, detectors.allow("c", "{}", observation = true))
    }

    // ---------------------------------------------------------------- global_circuit_breaker

    @Test
    fun `eight no progress results inject one notice without skipping the call and twelve terminate`() {
        val detectors = LoopDetectors()
        // A baseline observation: "no progress" is measured against the last observed state.
        detectors.run("baseline", "{}", observation = true, fingerprint = "screen")

        var noticeAt = -1
        repeat(12) { index ->
            val attempt = index + 1
            // Every call has a distinct identity, so only the global breaker can fire.
            assertEquals(
                LoopDecision.Allow,
                detectors.beforeCall("tool$attempt", "{}", observation = true),
                "the global notice must not skip the call",
            )
            val after = detectors.afterCall(executed = true, write = false, fingerprint = "screen")
            if (after.notice) noticeAt = attempt
            assertEquals(attempt >= 12, after.critical, "attempt $attempt")
        }

        assertEquals(8, noticeAt, "the notice is injected exactly once, on the eighth no-progress result")
        assertEquals(12, detectors.noProgressResults)
    }

    @Test
    fun `the global counter is never reset by a later success`() {
        val detectors = LoopDetectors()
        detectors.run("baseline", "{}", observation = true, fingerprint = "screen")

        repeat(4) { detectors.run("stuck$it", "{}", observation = true, fingerprint = "screen") }
        assertEquals(4, detectors.noProgressResults)

        // A real change does not reset the run-wide waste counter (D3 §1.3: "不重置").
        detectors.run("progress", "{}", observation = true, fingerprint = "screen-2")
        assertEquals(4, detectors.noProgressResults)

        repeat(4) { detectors.run("stuck-again$it", "{}", observation = true, fingerprint = "screen-2") }
        assertEquals(8, detectors.noProgressResults, "4 + 4 no-progress results reach the notice threshold")
    }

    @Test
    fun `a warning skipped call never feeds the global counter`() {
        val detectors = LoopDetectors()
        detectors.run("baseline", "{}", observation = true, fingerprint = "screen")

        assertEquals(LoopDecision.Allow, detectors.run("tap", fingerprint = "screen"))
        assertEquals(LoopDecision.Allow, detectors.run("tap", fingerprint = "screen"))
        assertEquals(2, detectors.noProgressResults)

        // The third identical attempt is warned and skipped: no result, so no global increment.
        assertEquals(LoopDecision.Warn(LoopPattern.GENERIC_REPEAT), detectors.allow("tap"))
        assertEquals(2, detectors.noProgressResults)
    }

    // ---------------------------------------------------------------- error tracking (R56)

    @Test
    fun `a stable code is read from a refused or failed prefix`() {
        assertEquals("NODE_STALE", stableCodeOf("Refused: NODE_STALE the node is gone"))
        assertEquals("CHANNEL_UNAVAILABLE", stableCodeOf("Failed: CHANNEL_UNAVAILABLE"))
        assertEquals("ACTION_TIMEOUT", stableCodeOf("ACTION_TIMEOUT: may or may not have taken effect"))
        assertEquals("UNKNOWN_TOOL", stableCodeOf("UNKNOWN_TOOL"))
        assertEquals("NODE_NOT_FOUND", stableCodeOf("""{"ok":false,"error":{"code":"NODE_NOT_FOUND"}}"""))
    }

    @Test
    fun `an unclassifiable payload is hashed without keeping its body`() {
        val first = stableCodeOf("the screen is off; please turn it on")
        val second = stableCodeOf("a different failure body")

        assertTrue(first.matches(Regex("[0-9a-f]{16}")), first)
        assertNotEquals(first, second)
        assertEquals(first, stableCodeOf("the screen is off; please turn it on"), "the identity must be stable")
        assertFalse(first.contains("screen"), "the body must never survive in the identity")
    }

    @Test
    fun `three identical errors terminate while a different code resets the streak`() {
        val tracker = ErrorTracker()

        assertFalse(tracker.assess("device", ToolResult.error("c", "NODE_STALE"), false).repeatTerminated)
        assertFalse(tracker.assess("device", ToolResult.error("c", "NODE_STALE"), false).repeatTerminated)
        assertFalse(tracker.assess("device", ToolResult.error("c", "NODE_NOT_FOUND"), false).repeatTerminated)
        assertFalse(tracker.assess("device", ToolResult.error("c", "NODE_NOT_FOUND"), false).repeatTerminated)
        assertTrue(tracker.assess("device", ToolResult.error("c", "NODE_NOT_FOUND"), false).repeatTerminated)
    }

    @Test
    fun `a successful write resets the error streak and a successful observation does not`() {
        val tracker = ErrorTracker()
        tracker.assess("device", ToolResult.error("c", "NODE_STALE"), false)
        tracker.assess("device", ToolResult.error("c", "NODE_STALE"), false)

        // An observation succeeding is not progress for the error streak: the third error still terminates.
        tracker.assess("device", successResult("c", "screen"), writeSucceeded = false)
        assertTrue(
            tracker.assess("device", ToolResult.error("c", "NODE_STALE"), false).repeatTerminated,
            "a successful observation must not reset the streak",
        )

        // A successful write is progress: the next two errors are a fresh streak.
        tracker.assess("device", successResult("c", "done"), writeSucceeded = true)
        assertFalse(tracker.assess("device", ToolResult.error("c", "NODE_STALE"), false).repeatTerminated)
        assertFalse(tracker.assess("device", ToolResult.error("c", "NODE_STALE"), false).repeatTerminated)
        assertTrue(tracker.assess("device", ToolResult.error("c", "NODE_STALE"), false).repeatTerminated)
    }

    @Test
    fun `confirmation decisions are user decisions and are never counted`() {
        val tracker = ErrorTracker()
        val denied = ToolResult.error("c", "CONFIRMATION_DENIED")
        val timedOut = ToolResult.error("c", "CONFIRMATION_TIMEOUT")

        repeat(5) {
            val assessment = tracker.assess("app.uninstall", denied, false)
            assertFalse(assessment.repeatTerminated, "attempt ${it + 1}")
            assertFalse(assessment.counted)
            assertTrue(assessment.hint?.contains("CONFIRMATION_DENIED") == true, "a do-not-retry hint is required")
        }
        val timeout = tracker.assess("app.uninstall", timedOut, false)
        assertFalse(timeout.counted)
        assertTrue(timeout.hint?.contains("CONFIRMATION_TIMEOUT") == true)
    }

    @Test
    fun `a policy refusal does count toward the error streak`() {
        val tracker = ErrorTracker()
        val refused = ToolResult.error("c", "REFUSED_BY_POLICY")

        assertTrue(tracker.assess("shell", refused, false).counted)
        assertTrue(tracker.assess("shell", refused, false).counted)
        assertTrue(tracker.assess("shell", refused, false).repeatTerminated)
    }

    @Test
    fun `a classification hint is injected once per stable code`() {
        val tracker = ErrorTracker()

        val first = tracker.assess("device", ToolResult.error("c", "NODE_STALE"), false)
        val second = tracker.assess("device", ToolResult.error("c", "NODE_STALE"), false)

        assertTrue(first.hint?.startsWith("LOOP_HINT:NODE_STALE") == true, "hint: ${first.hint}")
        assertNull(second.hint, "the same code is hinted once per run")
    }

    @Test
    fun `an explicit execution code wins over the legacy detail identity`() {
        val tracker = ErrorTracker()
        // A migrated tool names its code; the detail's prose must not become the identity (or leak into it).
        val explicit = ToolResult(
            id = "c",
            ok = false,
            execution = ToolExecution(outcome = ToolOutcome.Failed, code = "CHANNEL_UNAVAILABLE"),
            observation = null,
            data = "a different failure body",
        )

        val assessment = tracker.assess("device", explicit, false)

        assertEquals("CHANNEL_UNAVAILABLE", assessment.stableCode)
        assertTrue(assessment.hint?.startsWith("LOOP_HINT:CHANNEL_UNAVAILABLE") == true, "hint: ${assessment.hint}")
    }

    // ---------------------------------------------------------------- production error identity (review R56)

    /**
     * The exact detail an unmigrated `device` call still returns: `DeviceTool` renders the whole envelope (error code
     * and timing included) as one JSON object. The timing values differ on every call; the failure is the same.
     * Until the tools carry an explicit `ToolExecution`, `ErrorTracker` falls back to this legacy detail identity.
     */
    private fun deviceFailure(totalMs: Long): String =
        """{"ok":false,"tool":"device","action":"act","channel":"accessibility","accepted":false,""" +
            """"applied":null,"verified":null,"data":{"screen":null,"diagnostic":"accepted the action but could not """ +
            """observe"},"images":[],"timing":{"actMs":1,"observeMs":2,"totalMs":$totalMs},"error":{"code":"NODE_STALE",""" +
            """"message":"the node reference is stale","recoverable":true}}"""

    @Test
    fun `a production device failure keeps its json error code across timing values`() {
        val first = stableCodeOf(deviceFailure(totalMs = 7L))
        val second = stableCodeOf(deviceFailure(totalMs = 913L))

        assertEquals("NODE_STALE", first, "the JSON error.code must win over the legacy detail")
        assertEquals(first, second, "identical failures with different timing must share one identity")

        // The per-code hint and the 3-in-a-row streak are both reachable only if the identity is stable.
        val tracker = ErrorTracker()
        val hinted = tracker.assess("device", legacyFailure("c1", deviceFailure(totalMs = 7L)), false)
        assertTrue(hinted.hint?.startsWith("LOOP_HINT:NODE_STALE") == true, "hint: ${hinted.hint}")
        tracker.assess("device", legacyFailure("c2", deviceFailure(totalMs = 8L)), false)
        assertTrue(
            tracker.assess("device", legacyFailure("c3", deviceFailure(totalMs = 9L)), false).repeatTerminated,
            "three NODE_STALE failures must terminate the run as a repeated error",
        )
    }

    @Test
    fun `an unclassifiable prefixed json payload hashes without its timing or key order`() {
        val first = stableCodeOf(
            """Refused: {"ok":false,"data":{"screen":null,"diagnostic":"no code here"},"error":null,""" +
                """"timing":{"actMs":1,"observeMs":2,"totalMs":3}}""",
        )
        val second = stableCodeOf(
            """Refused: {"error":null,"timing":{"totalMs":999,"observeMs":4,"actMs":5},"data":""" +
                """{"screen":null,"diagnostic":"no code here"},"ok":false}""",
        )

        assertTrue(first.matches(Regex("[0-9a-f]{16}")), first)
        assertEquals(first, second, "the fallback hash must ignore timing and object key order")
    }

    private fun successResult(id: String, data: String) = ToolResult(
        id = id,
        ok = true,
        execution = ToolExecution(outcome = ToolOutcome.Done),
        observation = null,
        data = data,
    )

    /** An unmigrated tool result: no explicit execution code, only the legacy detail that the fallback parses. */
    private fun legacyFailure(id: String, detail: String) = ToolResult(
        id = id,
        ok = false,
        execution = ToolExecution(outcome = ToolOutcome.Failed),
        observation = null,
        data = detail,
    )
}
