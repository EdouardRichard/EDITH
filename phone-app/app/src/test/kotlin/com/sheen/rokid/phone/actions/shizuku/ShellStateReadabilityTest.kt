package com.sheen.rokid.phone.actions.shizuku
import com.sheen.rokid.phone.R
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R-9's *other* half: a carrier-level failure must make a state read unreadable, so a stdout parser
 * (`installed` / `disabled` / `permissionGranted`) can never judge an action from a reply that never arrived.
 *
 * The known hole in the same family: `SHELL_NOT_BOUND` (the channel died between `available()` and the read) is neither
 * of the two codes the old `isAStateReply()` named, so it read as a *successful* state reply with empty stdout - and
 * `installed()` then answered `false`, which the backend reports as "the action did not take" for an action that was
 * never even attempted.
 *
 * Each case is built by hand here, which is the point: the rule must hold for a code the fake invented, so the
 * implementation cannot be a test for the codes someone remembered to list.
 *
 * The predicate these cases drive is `isReadableState()` (open item #4), which asks the completion half *and* the exit
 * code *and* the truncation flag. The completion half alone (`isCallComplete()`) deliberately says nothing about a
 * target, so it is never the predicate a state probe may judge from - and the `isAStateReply()` name that used to sit on
 * top of it has been deleted, because it promised a state answer while only reporting completion.
 */
class ShellStateReadabilityTest {

    private class FixedShell(private val result: ShellResult) : PrivilegedShell {
        override fun run(command: String): ShellResult = result
    }

    @Test
    fun `SHELL_NOT_BOUND is not a state reply, so a dead channel is not read as an answer`() {
        val notBound = ShellResult("pm list packages com.example", -1, "", "SHELL_NOT_BOUND")
        assertFalse("a channel that is not bound did not answer anything", notBound.isReadableState())
    }

    @Test
    fun `a carrier failure is not a state reply and leaves the state unreadable`() {
        val state = ShizukuTargetState(
            FixedShell(ShellResult("pm list packages com.example", -1, "", SHELL_REPLY_TOO_LARGE)),
        )

        assertFalse("a reply the channel could not carry answers no question about the target", state.installed("com.example"))
        assertFalse("so the read must report itself unreadable", state.readable())
    }

    @Test
    fun `an unparseable reply is not a state reply`() {
        val state = ShizukuTargetState(FixedShell(ShellResult("pm list packages com.example", -1, "", SHELL_REPLY_INVALID)))

        assertFalse(state.installed("com.example"))
        assertFalse(state.readable())
    }

    @Test
    fun `any unrecognised failure code is excluded rather than trusted`() {
        // The rule has to be "the channel reported nothing" rather than "the channel reported one of the codes this
        // build happens to know": otherwise a future code - or a code from a newer shell - is silently read as a state
        // reply with empty stdout, which is the same defect in a new costume.
        val unknown = ShellResult("pm list packages com.example", -1, "", "SHELL_FROM_A_NEWER_BUILD")
        assertFalse(unknown.isReadableState())

        val state = ShizukuTargetState(FixedShell(unknown))
        assertFalse(state.installed("com.example"))
        assertFalse(state.readable())
    }

    @Test
    fun `a genuinely empty stderr is still a state reply`() {
        // The inversion's other side: a command whose stderr is empty did answer, even when it answered nothing at all.
        val empty = ShellResult("pm list packages com.example", 0, "", "")
        assertTrue(empty.isReadableState())
    }
}
