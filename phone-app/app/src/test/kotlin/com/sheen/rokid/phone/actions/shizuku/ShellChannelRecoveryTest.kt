package com.sheen.rokid.phone.actions.shizuku
import com.sheen.rokid.phone.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R-9 follow-up, open item #2: after a carrier failure the channel may stay dead, and **nothing brought it back**.
 *
 * What the device established (`evidence/2026-10-17-shizuku-reply-ceiling.md`, and re-measured while this work was
 * done): an oversized reply raises `android.os.DeadObjectException`, the binder connection for that call dies, and
 * recovery arrives only when Shizuku's framework redelivers `onServiceConnected`. In the measured run the
 * **immediately next call** already succeeded on the same remote pid, so the framework does redeliver *here* - but the
 * app's own code contained no path that would bring the channel back if it did not: `bind()` is called exactly once at
 * composition time, and `onServiceDisconnected` does nothing but clear the remote. "The framework is expected to
 * redeliver" is not a guarantee this code can lean on, which is what these tests are about.
 *
 * The seam is [RelinkGate] rather than "rebind on every call": a plain object with an injected clock, so the three
 * refusals and the bounded retry are pinned on the JVM. It is deliberately **not** a second connection - Shizuku allows
 * only one `UserService` binding per app, so the only recovery available to this code is asking Shizuku to bind
 * **the same** args and connection again.
 *
 * The shell-level half is driven through the injected binder seam (`ShizukuUserServiceShell.forTest`), which exists so
 * "the retry answered" can be told apart from "the original failure was reported" without a device: the answer path is
 * a value on the result rather than something a test would have to infer from timing.
 */
class ShellChannelRecoveryTest {

    /** A clock the test advances by hand; the gate takes it as a lambda, so no scheduler is involved. */
    private class FakeClock(var nowMillis: Long = 0L) {
        fun advanceBy(deltaMillis: Long) {
            nowMillis += deltaMillis
        }
    }

    private fun gate(clock: FakeClock, windowMillis: Long = 3_000L) =
        RelinkGate(windowMillis = windowMillis, elapsedMillis = { clock.nowMillis })

    @Test
    fun `a missing remote with Shizuku alive and permission granted is rebound`() {
        val clock = FakeClock()
        val gate = gate(clock)

        assertEquals("an already-connected remote needs no rebind", RelinkDecision.READY, gate.decide(remoteReady = true, shizukuUsable = true))
        assertEquals(RelinkDecision.REBIND, gate.decide(remoteReady = false, shizukuUsable = true))
    }

    @Test
    fun `a channel that is down while Shizuku itself is unusable is never rebound`() {
        // R-9's three-honest-answers rule: when Shizuku is not installed, not running, or the user revoked the grant,
        // there is nothing to re-arm. Retrying the bind there would be noise dressed up as recovery - and it is the
        // case a "just bind again on every call" patch gets wrong.
        val clock = FakeClock()
        val gate = gate(clock)

        assertEquals(RelinkDecision.NOT_USABLE, gate.decide(remoteReady = false, shizukuUsable = false))
        // Calling again cannot spend the window either: a refusal is not an attempt, and the gate records attempts
        // itself precisely so a caller cannot record one on a refusal's behalf.
        assertEquals(RelinkDecision.NOT_USABLE, gate.decide(remoteReady = false, shizukuUsable = false))
        assertEquals("an unusable Shizuku never triggers a bind", 0, gate.attempts)
        assertEquals("and the caller is told why rather than left guessing", RelinkDecision.NOT_USABLE, gate.lastDecision)
    }

    @Test
    fun `a remote that is already back is not rebound`() {
        // The framework redelivered `onServiceConnected` (measured on the K40), so the call can simply go through; a
        // bind here would be a needless round trip on a channel that is already usable.
        val clock = FakeClock()
        val gate = gate(clock)

        assertEquals(
            "an already-connected remote needs no rebind",
            RelinkDecision.READY,
            gate.decide(remoteReady = true, shizukuUsable = true),
        )
        assertEquals(0, gate.attempts)
        assertEquals(RelinkDecision.READY, gate.lastDecision)
    }

    @Test
    fun `rebinds are rate limited rather than attempted on every call`() {
        // The requirement is explicit: bounded, not once per call. A failed rebind must not be retried in a loop, and
        // the window is the same one the production field defaults to.
        val clock = FakeClock()
        val gate = gate(clock, windowMillis = 3_000L)

        assertEquals("the first miss may ask for a bind", RelinkDecision.REBIND, gate.decide(remoteReady = false, shizukuUsable = true))
        assertEquals("and the ask is recorded as it is answered", 1, gate.attempts)

        clock.advanceBy(2_999L)
        assertEquals(
            "inside the window the channel waits for the framework instead of hammering the bind",
            RelinkDecision.RATE_LIMITED,
            gate.decide(remoteReady = false, shizukuUsable = true),
        )
        assertEquals("a refused ask spends nothing", 1, gate.attempts)

        clock.advanceBy(1L)
        assertEquals(
            "the window is a boundary, not a heuristic: exactly at it the channel tries again",
            RelinkDecision.REBIND,
            gate.decide(remoteReady = false, shizukuUsable = true),
        )
        assertEquals("one more attempt, and still only one per window", 2, gate.attempts)
    }

    @Test
    fun `many calls inside one window produce exactly one bind`() {
        // A turn can issue several privileged commands back to back. Without the window each one would ask Shizuku to
        // bind again; with it, the whole burst costs a single binding request.
        val clock = FakeClock()
        val gate = gate(clock, windowMillis = 3_000L)

        val decisions = (1..50).map { gate.decide(remoteReady = false, shizukuUsable = true) }

        assertEquals(
            "the first call asks for a bind and every one after it in the window is deliberately held back",
            List(50) { if (it == 0) RelinkDecision.REBIND else RelinkDecision.RATE_LIMITED },
            decisions,
        )
        assertEquals("a burst of calls is one bind, not fifty", 1, gate.attempts)
    }

    @Test
    fun `the call that could not reach the remote is answered by a rebind and says so`() {
        // The end of the path the requirement asks to be observable: `run` finds no remote, the gate allows a rebind,
        // the rebind brings the connection back, and the **retry** is what answers - not the original `SHELL_NOT_BOUND`.
        val shell = ShizukuUserServiceShell.forTest(relinkSucceeds = true)

        val result = shell.run("pm list packages edith.phone")

        assertEquals(
            "the retry's reply is the answer (path=${shell.lastAnswerPath}, binds=${shell.bindCalls}, " +
                "kind=${result.failure}, threw=${shell.lastCarrierThrowable}, code=${result.stderr})",
            0,
            result.exitCode,
        )
        assertNull("the retry crossed the channel, so it carries no failure kind", result.failure)
        assertTrue("and the caller can see that a rebind is what answered", result.relinked)
        assertEquals("the retry's stdout is the reply that was parsed", "package:edith.phone", result.stdout)
        assertEquals("exactly one rebind was attempted: Shizuku allows only one connection", 1, shell.bindCalls)
        assertEquals("and the path is visible without reading timing", "rebound", shell.lastAnswerPath)
    }

    @Test
    fun `a rebind that does not restore the channel leaves the original failure and says it was not answered`() {
        // The dangerous direction: if Shizuku could not rebind, or Shizuku itself went away between the check and the
        // bind, the call must keep reporting the channel failure it actually had - never an empty success, and never a
        // second bind attempt inside the same window.
        val shell = ShizukuUserServiceShell.forTest(relinkSucceeds = false)

        val result = shell.run("pm list packages edith.phone")

        // The **original** failure is kept verbatim: this call did reach a remote and the binder refused to carry the
        // reply (`CARRIER` / `SHELL_REPLY_TOO_LARGE`), which is what killed the connection and what a failed rebind
        // must not dress up as something else - neither an empty success nor the `NOT_BOUND` of a *different* shape.
        assertEquals("nothing was read, so the failure stands", -1, result.exitCode)
        assertEquals(ShellFailure.CARRIER, result.failure)
        assertEquals("the original channel code is kept, not replaced by the retry", SHELL_REPLY_TOO_LARGE, result.stderr)
        assertFalse("no retry answered this call", result.relinked)
        assertEquals(1, shell.bindCalls)
        assertEquals("failed", shell.lastAnswerPath)

        val second = shell.run("pm list packages edith.phone")
        // The failed bind left the channel with no remote, so the next call is the other shape of failure - a plain
        // `NOT_BOUND` that never even enters the recovery branch, because there is no carrier failure to recover from.
        // What matters either way is the bound and the honesty: no second bind, and still no answer about the target.
        assertEquals(SHELL_NOT_BOUND, second.stderr)
        assertEquals(ShellFailure.NOT_BOUND, second.failure)
        assertFalse("a second call in the same window must not bind again", second.relinked)
        assertEquals("the next call must not ask Shizuku for another bind", 1, shell.bindCalls)
        // `failed`, still, and never `rebound`: the one bind that was allowed did not restore the channel, and the call
        // after it finds no remote at all (`NOT_BOUND`, asserted above), so it never even reaches the recovery branch.
        // The value that must never appear here is `rebound` - a retry is the only thing that may claim to have
        // answered, and no retry answered either call.
        assertEquals("failed", shell.lastAnswerPath)
    }

    @Test
    fun `a second dead call inside the window is not rebound again, and the refusal is visible`() {
        // Boundedness at the shell, not just at the gate: the gate is pre-spent (its window was used by an earlier
        // attempt in the same window), so a carrier failure must be answered by the original failure plus a refusal -
        // never by a second bind. The remote is left in place on purpose, which is the shape the device produced: a
        // dead binder does not clear the reference until `onServiceDisconnected` arrives, so the **only** thing that
        // can stop a second bind here is the window.
        val clock = RelinkGateTestClock()
        val gate = RelinkGate(windowMillis = 3_000L, elapsedMillis = { clock.now })
        gate.decide(remoteReady = false, shizukuUsable = true) // spends the window as an earlier call would
        assertEquals("the window is pre-spent by exactly one attempt", 1, gate.attempts)

        val shell = ShizukuUserServiceShell.forTest(relinkSucceeds = true, gate = gate)

        val result = shell.run("pm list packages edith.phone")

        assertEquals("nothing was read, so the failure stands", -1, result.exitCode)
        assertEquals(ShellFailure.CARRIER, result.failure)
        assertFalse("a rate-limited call is not answered by a retry", result.relinked)
        assertEquals("and it must not ask Shizuku for a second bind", 1, shell.bindCalls)
        assertEquals("the refusal is readable without timing the call", "rate_limited", shell.lastAnswerPath)
    }

    /** A hand-advanced clock, so "inside the window" is a value rather than a sleep. */
    private class RelinkGateTestClock(var now: Long = 0L)
}
