package edith.phone.actions

import edith.plugin.ActionOutcome
import edith.plugin.RiskLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Shizuku-backed system key action tool (T2.4).
 *
 * Each action maps to exactly one shell command; the executor is a thin dispatcher and the tests pin both the command
 * shape and the refusal codes so a future refactor cannot silently change what reaches the device.
 */
class SystemActionExecutorTest {

    private class FakeShell(var result: ShellResult = ShellResult("", 0, "", "")) : PrivilegedShell {
        val commands = mutableListOf<String>()
        override fun run(command: String): ShellResult {
            commands += command
            return result.copy(command = command)
        }
    }

    private fun backend(shell: FakeShell = FakeShell(), channelUp: Boolean = true) =
        SystemActionExecutor(shell = shell, channelUp = { channelUp })

    // ── Each action sends the correct command → Done ───────────────────────────

    @Test
    fun `back sends KEYCODE_BACK`() {
        val shell = FakeShell()
        val result = backend(shell).execute("system_action", """{"action":"back"}""")

        assertEquals(listOf("input keyevent KEYCODE_BACK"), shell.commands)
        assertEquals(ActionOutcome.Done, result.outcome)
        assertEquals("executed back", result.detail)
    }

    @Test
    fun `home sends KEYCODE_HOME`() {
        val shell = FakeShell()
        val result = backend(shell).execute("system_action", """{"action":"home"}""")

        assertEquals(listOf("input keyevent KEYCODE_HOME"), shell.commands)
        assertEquals(ActionOutcome.Done, result.outcome)
        assertEquals("executed home", result.detail)
    }

    @Test
    fun `recents sends KEYCODE_APP_SWITCH`() {
        val shell = FakeShell()
        val result = backend(shell).execute("system_action", """{"action":"recents"}""")

        assertEquals(listOf("input keyevent KEYCODE_APP_SWITCH"), shell.commands)
        assertEquals(ActionOutcome.Done, result.outcome)
        assertEquals("executed recents", result.detail)
    }

    @Test
    fun `notifications sends expand-notifications`() {
        val shell = FakeShell()
        val result = backend(shell).execute("system_action", """{"action":"notifications"}""")

        assertEquals(listOf("cmd statusbar expand-notifications"), shell.commands)
        assertEquals(ActionOutcome.Done, result.outcome)
        assertEquals("executed notifications", result.detail)
    }

    // ── Shell unavailable → Refused ────────────────────────────────────────────

    @Test
    fun `channel down returns Refused ACTION_CHANNEL_UNAVAILABLE`() {
        val shell = FakeShell()
        val result = backend(shell, channelUp = false).execute("system_action", """{"action":"back"}""")

        assertEquals(ActionOutcome.Refused, result.outcome)
        assertEquals("ACTION_CHANNEL_UNAVAILABLE", result.detail)
        assertTrue(shell.commands.isEmpty())
    }

    // ── Unknown action → Refused ───────────────────────────────────────────────

    @Test
    fun `unknown action value returns Refused UNKNOWN_ACTION`() {
        val shell = FakeShell()
        val result = backend(shell).execute("system_action", """{"action":"screenshot"}""")

        assertEquals(ActionOutcome.Refused, result.outcome)
        assertEquals("UNKNOWN_ACTION", result.detail)
        assertTrue(shell.commands.isEmpty())
    }

    // ── Invalid args → Refused ─────────────────────────────────────────────────

    @Test
    fun `invalid arguments returns Refused ARGUMENTS_INVALID`() {
        val shell = FakeShell()
        val executor = backend(shell)

        listOf("not json", """{}""", """{"action":123}""", """{"action":""}""").forEach { args ->
            val result = executor.execute("system_action", args)
            assertEquals("refused: $args", ActionOutcome.Refused, result.outcome)
            assertEquals("refused: $args", "ARGUMENTS_INVALID", result.detail)
        }
        assertTrue(shell.commands.isEmpty())
    }

    // ── Command fails (exit != 0) → Failed ────────────────────────────────────

    @Test
    fun `shell failure returns Failed COMMAND_FAILED`() {
        val shell = FakeShell(ShellResult("", 1, "", "error"))
        val result = backend(shell).execute("system_action", """{"action":"back"}""")

        assertEquals(ActionOutcome.Failed, result.outcome)
        assertEquals("COMMAND_FAILED", result.detail)
    }

    // ── Descriptor shape ───────────────────────────────────────────────────────

    @Test
    fun `descriptor declares system_action with L1 risk and device scope`() {
        val executor = backend()
        val descriptors = executor.descriptors()

        assertEquals(1, descriptors.size)
        val desc = descriptors.single()
        assertEquals("system_action", desc.name)
        assertEquals(RiskLevel.L1, desc.risk)
        assertEquals("device", desc.scope)
        assertEquals("action", desc.auditTargetArgument)
        assertEquals(false, desc.requiresConfirmation)
        assertEquals(true, desc.idempotent)
        assertEquals(false, desc.reversible)
    }

    @Test
    fun `backend id is shizuku_system`() {
        assertEquals("shizuku_system", backend().id)
    }

    // ── Unknown action name (not system_action) → Refused UNKNOWN_ACTION ──────

    @Test
    fun `unknown action name returns Refused UNKNOWN_ACTION`() {
        val result = backend().execute("something_else", """{}""")

        assertEquals(ActionOutcome.Refused, result.outcome)
        assertEquals("UNKNOWN_ACTION", result.detail)
    }
}
