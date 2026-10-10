package edith.phone.actions

import edith.plugin.ActionOutcome
import org.junit.Assert.assertEquals
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
}
