package com.sheen.rokid.phone.actions.shizuku

import com.sheen.rokid.plugin.ActionOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Spec §7.1/§17.2's honesty rule at the point where it is easiest to break: **the state decides, not the exit code** -
 * and a state that could not be read is not a state.
 *
 * `TargetState` answers `false` for "the package is not installed" and also for "I could not read the package list".
 * Judging an action by that collapsed answer turns a command that never ran into `Done`, which reaches the model, the
 * `P3_TOOL` line and the audit as a success. The channel probe is the discriminator available at that point: if it went
 * down between the `available()` gate and the read-back, nothing about the outcome may be claimed.
 */
class ShizukuProbeFidelityTest {

    /** A shell whose command "succeeds" and then lets the channel die, exactly as a killed UserService does. */
    private class VanishingShell(private val afterCommand: () -> Unit) : PrivilegedShell {
        override fun run(command: String): ShellResult {
            afterCommand()
            return ShellResult(command, 0, "Success", "")
        }
    }

    /** The collapsing state fake: a probe that could not be read answers `false`. That collapse is the defect. */
    private object UnreadableState : TargetState {
        override fun installed(pkg: String) = false
        override fun disabled(pkg: String) = false
        override fun permissionGranted(pkg: String, permission: String) = false
        override fun setting(name: String): String? = null
    }

    @Test fun `uninstall is not reported as done when the channel died before the state was read back`() {
        var up = true
        val backend = ShizukuExecutionBackend(VanishingShell { up = false }, UnreadableState) { up }

        val result = backend.execute("uninstall", """{"package":"com.example.app"}""")

        assertEquals("an unreadable state must never be a completed action", ActionOutcome.Refused, result.outcome)
        assertEquals("ACTION_CHANNEL_UNAVAILABLE", result.detail)
    }

    @Test fun `revoking a permission is not reported as done when the channel died before the read-back`() {
        var up = true
        val backend = ShizukuExecutionBackend(VanishingShell { up = false }, UnreadableState) { up }

        val result = backend.execute(
            "set_permission",
            """{"package":"com.example.app","permission":"android.permission.CAMERA","granted":false}""",
        )

        assertEquals("an unreadable state must never be a completed action", ActionOutcome.Refused, result.outcome)
        assertEquals("ACTION_CHANNEL_UNAVAILABLE", result.detail)
    }

    @Test fun `disabling an app is not reported as done when the channel died before the read-back`() {
        var up = true
        val backend = ShizukuExecutionBackend(VanishingShell { up = false }, UnreadableState) { up }

        val result = backend.execute("disable_app", """{"package":"com.example.app"}""")

        assertEquals("an unreadable state must never be a completed action", ActionOutcome.Refused, result.outcome)
        assertEquals("ACTION_CHANNEL_UNAVAILABLE", result.detail)
    }

    /** The channel is up, but every reply this build gets back is one it cannot parse (`SHELL_REPLY_INVALID`). */
    private class GarbledShell : PrivilegedShell {
        override fun run(command: String): ShellResult = ShellResult(command, -1, "", SHELL_REPLY_INVALID)
    }

    /** A shell that answers properly, so the readable path is pinned next to the unreadable one. */
    private class PackageListShell(private val stdout: String) : PrivilegedShell {
        override fun run(command: String): ShellResult = ShellResult(command, 0, stdout, "")
    }

    @Test fun `the target state says whether its last read could be parsed at all`() {
        val state = ShizukuTargetState(GarbledShell())
        assertFalse("nothing has been read yet, so nothing may be claimed", state.readable())

        state.installed("com.example.app")

        assertFalse("a garbled reply is a failed read, not an answer about the target", state.readable())

        val readable = ShizukuTargetState(PackageListShell("package:com.example.app\n"))
        readable.installed("com.example.app")
        assertTrue("a parseable read is readable", readable.readable())
    }

    @Test fun `a channel that is up but whose state reply cannot be parsed is not reported as done`() {
        // The residual the P3.3 review left open: the channel probe still answers "up" here, so the channel cannot be
        // the discriminator - the state read has to say whether it read anything at all.
        val backend = ShizukuExecutionBackend(GarbledShell(), ShizukuTargetState(GarbledShell())) { true }

        val result = backend.execute("uninstall", """{"package":"com.example.app"}""")

        assertEquals("a state that could not be read must never be a completed action", ActionOutcome.Refused, result.outcome)
        assertEquals("ACTION_CHANNEL_UNAVAILABLE", result.detail)
    }

    @Test fun `the same rule covers the brightness write`() {
        val backend = ShizukuExecutionBackend(GarbledShell(), ShizukuTargetState(GarbledShell())) { true }

        val result = backend.execute("set_brightness", """{"value":150}""")

        assertEquals(ActionOutcome.Refused, result.outcome)
        assertEquals("ACTION_CHANNEL_UNAVAILABLE", result.detail)
    }

    /** A command that fails outright, so a `&&` chain would stop before ever reading the state. */
    private class FailingShell : PrivilegedShell {
        override fun run(command: String): ShellResult = ShellResult(command, 1, "", "Error: not cleared")
    }

    /** A state that can never be read, and counts how many times it was asked. */
    private class CountingUnreadableState : TargetState {
        var reads = 0
        override fun installed(pkg: String): Boolean { reads++; return false }
        override fun disabled(pkg: String) = false
        override fun permissionGranted(pkg: String, permission: String) = false
        override fun setting(name: String): String? = null
        override fun readable() = false
    }

    @Test fun `a failed clear still reads the state before it judges`() {
        // `readable()` answers about the read that just happened, so the read has to happen even when the command failed
        // and the `&&` chain would otherwise stop: a short-circuit leaves it describing an earlier read, and the same
        // failure is then reported as STATE_UNCHANGED on the first call of a session and as
        // ACTION_CHANNEL_UNAVAILABLE on later ones (leftover review of this round, Minor 1).
        val state = CountingUnreadableState()
        val backend = ShizukuExecutionBackend(FailingShell(), state) { true }

        val result = backend.execute("clear_data", """{"package":"com.example.app"}""")

        assertEquals("the state read must happen even when the command itself failed", 1, state.reads)
        assertEquals(ActionOutcome.Refused, result.outcome)
        assertEquals("ACTION_CHANNEL_UNAVAILABLE", result.detail)
    }
}
