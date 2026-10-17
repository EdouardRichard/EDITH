package com.sheen.rokid.phone.actions.shizuku
import com.sheen.rokid.phone.R
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R-9's follow-up, open item #3: the shared answer to **"did this call actually come back?"**.
 *
 * Three executors route the same [PrivilegedShell], and before this predicate existed only
 * `ShizukuExecutionBackend` consulted `ShellResult.failure`: a call whose reply never crossed the binder was reported by
 * the other two as *a failed action* ("the app did not open" / "the command failed") instead of *a channel failure*.
 * That is the wrong invitation - the first asks the model to retry or to pick a different app, the second asks it to
 * ask again on a channel that just proved it cannot carry an answer.
 *
 * The predicate is weaker than the state-read question **on purpose**, and this file pins the nesting in both directions:
 * - `isCallComplete()` asks about the **channel**: the binder returned and the payload and its marker parsed. A
 *   non-empty `stderr` does not enter into it - the reply did cross and was understood, and the command's own
 *   diagnostics are the command's business;
 * - [isReadableState] is the question a stdout parser must ask before it judges a target: the call came back **and** the
 *   command completed (`exitCode == 0`) **and** the payload was not cut short (`!truncated`). It is strictly stronger
 *   than `isCallComplete()`, so a reply that did not complete, or whose `stdout` is a prefix, can never be judged as an
 *   answer about the target.
 *
 * Reconciled with open item #4: the empty-`stderr` half this file used to require of a state reply is **gone**. It was an
 * unmeasured assumption, and the device round measured it at **0 of 52** `exit 0` state reads across 3 turns and five
 * targets, while the exit code is the OS's own completion signal. The `isAStateReply()` alias that briefly stood in for
 * the completion half has been **deleted** (its name promised a state answer while it only reported completion), so
 * [isCallComplete] is the completion question and is **not** what a parser may judge from. The judgement the old half
 * protected - never judge an action from a read that did not fully arrive - is preserved by [isReadableState], not
 * weakened: a non-completed or clipped read is still refused.
 */
class ShellCallCompletionTest {

    @Test
    fun `a failure-carrying reply is not a completed call, whatever kind it is`() {
        // Each failure kind names a different mechanism - never bound, the binder could not carry the reply, the reply
        // arrived and was garbage - and all three mean the same thing to a caller that is about to judge an action from
        // the reply: there is no statement from the command to judge. A test that listed only CARRIER would let the next
        // caller read `SHELL_NOT_BOUND` as an answer, which is the exact costume this family of defects wears.
        val notBound = ShellResult("am start -W -n com.tencent.mm/.ui.LauncherUI", -1, "", SHELL_NOT_BOUND, failure = ShellFailure.NOT_BOUND)
        val carrier = ShellResult("am start -W -n com.tencent.mm/.ui.LauncherUI", -1, "", SHELL_REPLY_TOO_LARGE, failure = ShellFailure.CARRIER)
        val parse = ShellResult("am start -W -n com.tencent.mm/.ui.LauncherUI", -1, "", SHELL_REPLY_INVALID, failure = ShellFailure.PARSE)

        for ((label, reply) in listOf("NOT_BOUND" to notBound, "CARRIER" to carrier, "PARSE" to parse)) {
            assertFalse("$label did not deliver a reply the caller may judge", reply.isCallComplete())
            assertFalse("$label is not a readable state read either: a state probe may judge nothing from it", reply.isReadableState())
        }
    }

    @Test
    fun `a reply that crossed the channel is a completed call even when the command wrote to stderr`() {
        // The device reading (`evidence/2026-10-17-shizuku-reply-ceiling.md` §7.5) has stderr empty for the three state
        // commands, but exit codes and stderr are the OS's own report and a command is allowed to write diagnostics:
        // "the carrier could not deliver it" and "the command had something to say" are different facts, and only the
        // first is a channel failure.
        val ok = ShellResult("am start -W -n com.tencent.mm/.ui.LauncherUI", 0, "Status: ok", "")
        val noisy = ShellResult("am start -W -n com.tencent.mm/.ui.LauncherUI", 0, "Status: ok", "Warning: activity not started, its current task has been brought to the front")

        assertTrue(ok.isCallComplete())
        assertTrue("stderr is the command's diagnostics, not a channel failure", noisy.isCallComplete())
    }

    @Test
    fun `the completion question is strictly weaker than the readable-state question`() {
        // The two are nested, and in this direction only: a readable state read is always a completed call, and a
        // completed call need not be readable. Stating the nesting pins that neither predicate was folded into the
        // other - and that the state-read refusal now comes from the OS's own completion signal and the truncation flag
        // rather than from `stderr` emptiness. `ShellStateReadPredicateTest` owns the exhaustive direction; the two
        // witnesses below are what makes the nesting **strict**.
        val noisy = ShellResult("pm list packages com.example", 0, "package:com.example\n", "Warning: deprecated flag")
        assertTrue("the reply crossed the channel", noisy.isCallComplete())
        assertTrue("an exit 0 command's diagnostics do not unmake its answer", noisy.isReadableState())

        val exitedNonZero = ShellResult("pm list packages com.example", 1, "package:com.example\n", "")
        assertTrue("the bytes crossed, so the call completed", exitedNonZero.isCallComplete())
        assertFalse("but a command that did not complete is not a readable state read", exitedNonZero.isReadableState())

        val clipped = ShellResult("pm list packages com.example", 0, "package:com.exampl", "", truncated = true)
        assertTrue("a decoded reply is still a completed call", clipped.isCallComplete())
        assertFalse("a clipped stdout is a prefix, not an answer about the target", clipped.isReadableState())

        val carrier = ShellResult("pm list packages com.example", -1, "", SHELL_REPLY_TOO_LARGE, failure = ShellFailure.CARRIER)
        assertFalse(carrier.isCallComplete())
        assertFalse("a reply the channel could not carry is not a readable read either", carrier.isReadableState())
    }
}
