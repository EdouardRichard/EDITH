package edith.phone.actions

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
 * The predicate is weaker than [isAStateReply] **on purpose** and this file pins both sides:
 * - `isCallComplete()` asks about the **channel**: the binder returned and the payload and its marker parsed. A
 *   non-empty `stderr` does not enter into it - the reply did cross and was understood, and the command's own
 *   diagnostics are the command's business;
 * - [isAStateReply] asks the **stronger** question a stdout parser needs: *and* nothing was written to `stderr`, because
 *   `installed`/`disabled`/`permissionGranted` read an absence as "the target is not there". Weakening that half here
 *   would reopen the hole `ShellStateReadabilityTest` closes.
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
    fun `the completion question is strictly weaker than the state-reply question`() {
        // The two are nested, and in this direction only: a state reply is a completed call, a completed call is not
        // necessarily a state reply (the noisy one above). Stating the nesting pins that neither predicate was folded
        // into the other by a later edit.
        val noisy = ShellResult("pm list packages com.example", 0, "", "Warning: activity not started")
        assertTrue(noisy.isCallComplete())
        assertFalse("the empty-stderr half still refuses a state read", noisy.isAStateReply())

        val carrier = ShellResult("pm list packages com.example", -1, "", SHELL_REPLY_TOO_LARGE, failure = ShellFailure.CARRIER)
        assertFalse(carrier.isCallComplete())
        assertFalse(carrier.isAStateReply())
    }
}
