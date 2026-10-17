package com.sheen.rokid.phone.actions.shizuku
import com.sheen.rokid.phone.actions.ExecutionRouter
import com.sheen.rokid.phone.storage.unavailable

import com.sheen.rokid.plugin.ActionOutcome
import com.sheen.rokid.plugin.RiskLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The privileged channel's L3/L4 actions (spec §17.3).
 *
 * The rule P0's shell matrix taught, and the reason this test exists at all: **an exit code cannot answer whether the
 * action happened.** `pm grant` returns 0 for a permission that was already granted, and `cmd statusbar` returns 1 for a
 * capability that works. So every action here is judged by re-reading the target's state, and a zero exit code with an
 * unchanged state is a [ActionOutcome.Failed] with `STATE_UNCHANGED` - never a success.
 */
class ShizukuExecutionBackendTest {

    private class FakeShell(var result: ShellResult = ShellResult("", 0, "", "")) : PrivilegedShell {
        val commands = mutableListOf<String>()
        override fun run(command: String): ShellResult {
            commands += command
            return result.copy(command = command)
        }
    }

    private class FakeState(
        private val installed: MutableSet<String> = mutableSetOf(),
        private val granted: MutableSet<String> = mutableSetOf(),
        private val disabled: MutableSet<String> = mutableSetOf(),
        private val settings: MutableMap<String, String> = mutableMapOf(),
    ) : TargetState {
        fun markInstalled(pkg: String) { installed += pkg }
        fun markGranted(pkg: String, permission: String) { granted += "$pkg/$permission" }
        fun markRevoked(pkg: String, permission: String) { granted -= "$pkg/$permission" }
        fun markDisabled(pkg: String) { disabled += pkg }
        fun markSetting(name: String, value: String) { settings[name] = value }
        override fun installed(pkg: String) = pkg in installed
        override fun permissionGranted(pkg: String, permission: String) = "$pkg/$permission" in granted
        override fun disabled(pkg: String) = pkg in disabled
        override fun setting(name: String) = settings[name]
    }

    private val probe = "edith.phone.a11yprobe"

    private fun backend(shell: FakeShell, state: FakeState, channelUp: Boolean = true) =
        ShizukuExecutionBackend(shell = shell, state = state, channelUp = { channelUp })

    @Test fun `uninstall runs exactly the expected command and needs the package gone`() {
        val shell = FakeShell(ShellResult("", 0, "Success", ""))
        val state = FakeState()
        state.markInstalled(probe)

        val result = backend(shell, state).execute("uninstall", """{"package":"$probe"}""")

        assertEquals(listOf("pm uninstall --user 0 $probe"), shell.commands)
        assertEquals(ActionOutcome.Failed, result.outcome)
        assertEquals("STATE_UNCHANGED", result.detail)
    }

    @Test fun `uninstall succeeds when the package is actually gone`() {
        val shell = FakeShell(ShellResult("", 0, "Success", ""))
        val state = FakeState()

        val result = backend(shell, state).execute("uninstall", """{"package":"$probe"}""")

        assertEquals(ActionOutcome.Done, result.outcome)
        assertEquals("uninstalled $probe", result.detail)
    }

    @Test fun `clear data runs the expected command and requires the OS's own Success marker`() {
        val state = FakeState()
        state.markInstalled(probe)
        val shell = FakeShell(ShellResult("", 0, "Success", ""))
        val backend = backend(shell, state)

        assertEquals(ActionOutcome.Done, backend.execute("clear_data", """{"package":"$probe"}""").outcome)
        assertEquals(listOf("pm clear $probe"), shell.commands)

        shell.result = ShellResult("", 0, "", "")
        assertEquals(ActionOutcome.Failed, backend.execute("clear_data", """{"package":"$probe"}""").outcome)
    }

    @Test fun `disable runs the expected command and needs the package to read as disabled`() {
        val shell = FakeShell(ShellResult("", 0, "", ""))
        val state = FakeState()
        state.markInstalled(probe)
        state.markDisabled(probe)

        val result = backend(shell, state).execute("disable_app", """{"package":"$probe"}""")

        assertEquals(listOf("pm disable-user --user 0 $probe"), shell.commands)
        assertEquals(ActionOutcome.Done, result.outcome)
    }

    @Test fun `grant and revoke run the expected commands and are checked against the re-read permission`() {
        val shell = FakeShell(ShellResult("", 0, "", ""))
        val state = FakeState()
        val permission = "android.permission.POST_NOTIFICATIONS"
        val backend = backend(shell, state)

        // A grant the OS did not actually apply: exit 0, state unchanged ⇒ Failed. This is P0's `pm grant` false
        // positive, and it is exactly why the exit code is not the verdict.
        val notApplied = backend.execute(
            "set_permission", """{"package":"$probe","permission":"$permission","granted":true}""",
        )
        assertEquals(listOf("pm grant $probe $permission"), shell.commands)
        assertEquals(ActionOutcome.Failed, notApplied.outcome)
        assertEquals("STATE_UNCHANGED", notApplied.detail)

        // The same intent, now matching what the device reports ⇒ Done.
        state.markGranted(probe, permission)
        assertEquals(ActionOutcome.Done, backend.execute(
            "set_permission", """{"package":"$probe","permission":"$permission","granted":true}""").outcome)

        // And the other direction is judged by the same re-read: a revoke is only done once the permission is gone.
        state.markRevoked(probe, permission)
        val revoked = backend.execute(
            "set_permission", """{"package":"$probe","permission":"$permission","granted":false}""",
        )
        assertEquals("pm revoke $probe $permission", shell.commands.last())
        assertEquals(ActionOutcome.Done, revoked.outcome)
    }

    @Test fun `a package argument carrying shell metacharacters is refused without touching the shell`() {
        val shell = FakeShell()
        val backend = backend(shell, FakeState())

        listOf("$probe; rm -rf /data", "$probe && reboot", "\$(id)", "../etc", "") .forEach { injected ->
            val result = backend.execute("uninstall", """{"package":"$injected"}""")
            assertEquals("refused: $injected", ActionOutcome.Refused, result.outcome)
            assertEquals("refused: $injected", "ARGUMENTS_INVALID", result.detail)
        }
        assertTrue("no command may be built from a rejected argument", shell.commands.isEmpty())
    }

    @Test fun `a permission argument outside the safe alphabet is refused`() {
        val shell = FakeShell()
        val backend = backend(shell, FakeState())

        val result = backend.execute(
            "set_permission",
            """{"package":"$probe","permission":"android.permission.X; id","granted":true}""",
        )

        assertEquals(ActionOutcome.Refused, result.outcome)
        assertEquals("ARGUMENTS_INVALID", result.detail)
        assertTrue(shell.commands.isEmpty())
    }

    @Test fun `an unavailable channel refuses without touching the shell`() {
        val shell = FakeShell()
        val backend = backend(shell, FakeState(), channelUp = false)

        val result = backend.execute("uninstall", """{"package":"$probe"}""")

        assertEquals(ActionOutcome.Refused, result.outcome)
        assertEquals("ACTION_CHANNEL_UNAVAILABLE", result.detail)
        assertTrue(shell.commands.isEmpty())
        assertTrue("a down channel must advertise nothing through the router",
            ExecutionRouter(listOf(backend)).descriptors().isEmpty())
    }

    @Test fun `an unparsable arguments payload is refused`() {
        val shell = FakeShell()
        val backend = backend(shell, FakeState())

        val result = backend.execute("uninstall", "not json")

        assertEquals(ActionOutcome.Refused, result.outcome)
        assertEquals("ARGUMENTS_INVALID", result.detail)
        assertTrue(shell.commands.isEmpty())
    }

    @Test fun `set brightness writes the setting and is judged by the value read back`() {
        val shell = FakeShell(ShellResult("", 0, "", ""))
        val state = FakeState()
        val backend = backend(shell, state)

        // Exit 0 but the setting did not move: a failure, not a success.
        val notApplied = backend.execute("set_brightness", """{"value":42}""")
        assertEquals(listOf("settings put system screen_brightness 42"), shell.commands)
        assertEquals(ActionOutcome.Failed, notApplied.outcome)
        assertEquals("STATE_UNCHANGED", notApplied.detail)

        state.markSetting("screen_brightness", "42")
        assertEquals(ActionOutcome.Done, backend.execute("set_brightness", """{"value":42}""").outcome)
    }

    @Test fun `set brightness refuses a value outside 0 to 255 and a non integer`() {
        val shell = FakeShell()
        val backend = backend(shell, FakeState())

        listOf("""{"value":256}""", """{"value":-1}""", """{"value":"42"}""", """{}""").forEach { args ->
            val result = backend.execute("set_brightness", args)
            assertEquals("refused: $args", ActionOutcome.Refused, result.outcome)
            assertEquals("refused: $args", "ARGUMENTS_INVALID", result.detail)
        }
        assertTrue(shell.commands.isEmpty())
    }

    @Test fun `every action is L3 or L4 and declares the argument that may be audited`() {
        val backend = backend(FakeShell(), FakeState())

        val byName = backend.descriptors().associateBy { it.name }
        assertEquals(setOf("clear_data", "uninstall", "disable_app", "set_permission", "set_brightness"), byName.keys)
        assertEquals(RiskLevel.L3, byName.getValue("clear_data").risk)
        assertEquals(RiskLevel.L3, byName.getValue("uninstall").risk)
        assertEquals(RiskLevel.L4, byName.getValue("disable_app").risk)
        assertEquals(RiskLevel.L4, byName.getValue("set_permission").risk)
        assertEquals(RiskLevel.L2, byName.getValue("set_brightness").risk)
        assertEquals("package", byName.getValue("uninstall").auditTargetArgument)
        assertEquals("permission", byName.getValue("set_permission").auditTargetArgument)
        // The executor does not claim to know best: `requiresConfirmation` stays false and the *policy* forces the
        // question for L3/L4 (spec §7.2), which `WhitelistActionPolicyTest` pins. What must hold here is that the
        // privileged four really are above the免确认 line, so the policy's L3/L4 branch is the one that judges them.
        val privileged = byName.filterKeys { it != "set_brightness" }.values
        assertTrue("every privileged action must be L3 or L4", privileged.all { it.risk >= RiskLevel.L3 })
        assertEquals("app", byName.getValue("uninstall").scope)
        assertEquals("app.permission", byName.getValue("set_permission").scope)
        assertEquals("device", byName.getValue("set_brightness").scope)
    }

    @Test fun `the reply codec round trips the exit code and both streams`() {
        val encoded = ShellReply.encode(exitCode = 7, stdout = "out\ntwo", stderr = "err")

        val decoded = ShellReply.decode(encoded)

        assertEquals(ShellResult("<remote>", 7, "out\ntwo", "err"), decoded)
    }

    @Test fun `a reply that is not the documented shape decodes to null rather than a silent success`() {
        assertNull(ShellReply.decode("not json"))
        assertNull(ShellReply.decode("""{"exit":0}"""))
        assertNull(ShellReply.decode("""{"stdout":"x","stderr":"y"}"""))
    }
}
