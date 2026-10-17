package com.sheen.rokid.phone.actions.shizuku
import com.sheen.rokid.phone.R
import com.sheen.rokid.phone.debug.DebugShellCapProbe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R-9 follow-up, open item #4: the state-read strictness, **measured in both directions**.
 *
 * `isAStateReply()` used to be `failure == null && stderr.isEmpty()`. The empty-`stderr` half was a whitelist added to
 * close a real hole (a two-code blacklist let `SHELL_NOT_BOUND` through), and its *safe* direction is real: no path may
 * ever report a state it could not read as a confident answer. What was wrong was the **reason** - stderr emptiness is
 * not what makes a read a read, and a benign warning on an `exit 0` command would make
 * `installed()`/`disabled()`/`permissionGranted()` answer `false`, which the backend reports as "the action did not take
 * effect" rather than "could not read".
 *
 * The device round measured the unmeasured direction (K40 `2696813b`, this build, `DebugShellCapProbe`):
 * **52 `exit 0` reads of the three state commands across 3 turns and five targets produced non-empty `stderr` 0 times**
 * (`stdoutBytes` 30 … 328 983, `code=none`, `truncated=false` on every one), so the old whitelist was not *refuted* on
 * this device - but it was also never *needed*, and the only non-empty-`stderr` rows anywhere were `exit -1` carrier
 * failures whose 21-byte `stderr` is the marker string itself. The predicate here therefore keys on the two signals the
 * OS actually owns - **the channel's failure kind and the exit code** - instead of on `stderr` emptiness, and keeps the
 * one property that must never lapse: a read that did not complete is never readable.
 */
class ShellStateReadPredicateTest {

    /** A shell that answers every command with one fixed reply, so the predicate is what decides. */
    private class FixedShell(private val result: ShellResult) : PrivilegedShell {
        override fun run(command: String): ShellResult = result
    }

    private fun state(result: ShellResult): ShizukuTargetState = ShizukuTargetState(FixedShell(result))

    @Test
    fun `a state command that exited non-zero is not readable even with an empty stderr`() {
        // The device's own completion signal. A `dumpsys`/`pm`/`settings` that exited non-zero did not complete, and
        // its `stdout` is therefore not an answer about the target - the old predicate, keyed on `stderr` alone, would
        // have read this `exit 1` reply as a confident answer (`installed` would even have said `true` from the stdout
        // the command managed to write before failing).
        val failed = ShellResult("pm list packages com.example", 1, "package:com.example\n", "")
        assertTrue("no channel failure and an empty stderr: only the exit code can refuse this", failed.isCallComplete())
        assertFalse("a command that did not complete is not a readable state read", failed.isReadableState())

        val target = state(failed)
        assertFalse("a failed command must not be judged as 'the package is there'", target.installed("com.example"))
        assertFalse("and the read must report itself unreadable", target.readable())
    }

    @Test
    fun `a non-zero exit on any of the three command shapes is unreadable`() {
        // The predicate is one function for all four parsers, so the refusal cannot be true for `pm list` and quietly
        // false for `dumpsys`/`settings get`. Each shape is exercised with a reply that *would* satisfy its parser.
        val shapes = listOf(
            "pm list packages -d com.example" to "package:com.example\n",
            "dumpsys package com.example" to "android.permission.CAMERA: granted=true\n",
            "settings get system screen_brightness" to "128\n",
        )
        for ((command, stdout) in shapes) {
            val target = state(ShellResult(command, 1, stdout, ""))
            target.installed("com.example")
            assertFalse("$command exited non-zero, so its reply is not readable", target.readable())
        }
    }

    @Test
    fun `an exit 0 reply that wrote to stderr is still readable, and its parser still answers`() {
        // The direction the device left unmeasured, decided here: the command completed (exit 0) and the bytes crossed
        // and parsed, so a warning on its error stream is the command's diagnostics - not a channel failure and not an
        // incomplete read. Reading it as unreadable made the backend say "did not take effect" for an action whose
        // state it had in fact just read.
        val noisy = ShellResult("pm list packages com.example", 0, "package:com.example\n", "Warning: deprecated flag\n")
        assertNull("the bytes crossed and were understood, so there is no failure kind", noisy.failure)
        assertTrue("a successful command's diagnostics do not unmake its answer", noisy.isReadableState())

        val target = state(noisy)
        assertTrue("the parser reads the state it actually got", target.installed("com.example"))
        assertTrue("and the read is reported readable rather than refused", target.readable())
    }

    @Test
    fun `a truncated read is never readable, whatever else it says`() {
        // The other direction the requirement names explicitly: the carrier half was already handled, but a *decoded*
        // reply that hit the single-reply cap is a successful parse of a **prefix**. The clipped tail is exactly where a
        // `package:` line goes, so an unread prefix would answer `false` and be judged "the action did not take".
        val clipped = ShellResult("pm list packages com.example", 0, "package:com.exampl", "", truncated = true)
        assertNull("a truncated reply still crossed the channel", clipped.failure)
        assertFalse("but its stdout is a prefix, so it is not a readable read", clipped.isReadableState())

        val target = state(clipped)
        assertFalse(target.installed("com.example"))
        assertFalse(target.readable())
    }

    @Test
    fun `a failure kind refuses the read even when the reply looks complete in every other way`() {
        // The nesting, stated so a later edit cannot fold the completion half away: the reply below is `exit 0`, has a
        // `stdout` its parser would happily read, is not truncated, and carries no marker in `stderr` - so **only** the
        // failure kind can refuse it. A reply that were also `exit -1` would be refused by the exit-code half instead
        // and this test would stay green with `isCallComplete()` deleted, which is exactly the kind of test this
        // project's reviews keep catching.
        val failures = listOf(ShellFailure.NOT_BOUND, ShellFailure.CARRIER, ShellFailure.PARSE)
        for (kind in failures) {
            val reply = ShellResult("pm list packages com.example", 0, "package:com.example\n", "", failure = kind)
            assertFalse("$kind must never be readable", reply.isReadableState())
        }

        // The same shape with no failure kind is readable, so the refusal above is about the failure and nothing else.
        val clean = ShellResult("pm list packages com.example", 0, "package:com.example\n", "")
        assertTrue("a completed call with no failure kind is readable", clean.isReadableState())
    }
}
