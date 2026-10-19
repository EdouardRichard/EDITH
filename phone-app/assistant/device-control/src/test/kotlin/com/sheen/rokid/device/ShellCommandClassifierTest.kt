package com.sheen.rokid.device

import com.sheen.rokid.plugin.PrecheckDecision
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * R50: the shell command classifier is a pure table, and every entry is fail-closed.
 *
 * The table has three outcomes only: [PrecheckDecision.Allow] means **no human confirmation is needed** (not "the
 * command is safe"), [PrecheckDecision.RequireConfirmation] means the wearer must be asked, and
 * [PrecheckDecision.Reject] means the argument is not a command at all and must never reach a shell.
 *
 * Two properties are asserted on every required case: the reason is a **category** (it is carried to the audit trail,
 * which never records command text), and a `summary` exists for the wearer's question. The classifier itself must not
 * execute anything - the production `ShellTool` test proves that separately with a recording shell.
 */
class ShellCommandClassifierTest {

    @Test
    fun `read only and explicitly reversible commands are allowed`() {
        val commands = listOf(
            "dumpsys window",
            "pm list packages",
            "settings get system screen_brightness",
            "getprop ro.product.model",
            "ls /sdcard",
            "stat /sdcard/x",
            "am start -a android.intent.action.VIEW -d https://x",
            // Reversible, so R50 keeps them out of the confirmation path even though they change state.
            "am force-stop com.x",
            "pm disable com.x",
            "settings put system screen_brightness 10",
            "cmd package resolve-activity --brief com.x",
            "pm path com.x",
        )

        commands.forEach { command ->
            assertEquals(PrecheckDecision.Allow, ShellCommandClassifier.classify(command).decision,
                "expected Allow for `$command`")
        }
    }

    @Test
    fun `irreversible data and spending commands require confirmation`() {
        val commands = listOf(
            "pm clear com.x",
            "pm uninstall com.x",
            "rm -rf /sdcard/x",
            "mv a b",
            "dd if=a of=b",
            "content delete --uri x",
            "settings delete global screen_brightness",
            "pm grant com.x android.permission.READ_CONTACTS",
            "pm revoke com.x android.permission.READ_CONTACTS",
            "am kill com.x",
            "kill 1234",
            "chmod 777 /sdcard/x",
            "mkfs.ext4 /dev/block/x",
            "format userdata",
        )

        commands.forEach(::assertRequiresConfirmation)
    }

    @Test
    fun `an unknown command requires confirmation instead of being rejected`() {
        val result = ShellCommandClassifier.classify("unknown_command --x")

        assertEquals(PrecheckDecision.RequireConfirmation, result.decision)
        assertEquals("UNKNOWN_COMMAND", result.reason, "an unknown command must be a category, never the command body")
        assertFalse(result.reason!!.contains("unknown_command"))
    }

    @Test
    fun `a compound command is classified segment by segment`() {
        val requiring = listOf(
            "pm list packages; pm clear com.x",
            "pm list packages && pm clear com.x",
            "pm list packages || pm clear com.x",
            "pm list packages | tee /sdcard/x",
            "pm list packages\npm clear com.x",
        )
        requiring.forEach(::assertRequiresConfirmation)

        // Both segments are read-only/reversible, so the compound itself needs no confirmation.
        assertEquals(
            PrecheckDecision.Allow,
            ShellCommandClassifier.classify("pm list packages; dumpsys window").decision,
        )
        assertEquals(
            PrecheckDecision.Allow,
            ShellCommandClassifier.classify("getprop ro.product.model && ls /sdcard").decision,
        )
    }

    @Test
    fun `shell wrappers and dynamic expansion require confirmation because the real command cannot be read`() {
        val commands = listOf(
            "sh -c 'pm clear com.x'",
            "bash -c 'pm clear com.x'",
            "eval pm clear com.x",
            "env sh -c 'pm clear com.x'",
            "xargs sh -c 'pm clear com.x'",
            "echo cm0g | base64 -d | sh",
            "A=com.x; pm clear \$A",
            "\$(pm clear com.x)",
            "echo `pm clear com.x`",
            "echo \$(getprop ro.product.model)",
        )

        commands.forEach(::assertRequiresConfirmation)
    }

    @Test
    fun `shell syntax that hides a second command requires confirmation`() {
        val commands = listOf(
            "ls & pm clear com.x",
            "ls > /sdcard/overwritten",
            "(pm clear com.x)",
        )

        commands.forEach(::assertRequiresConfirmation)
    }

    @Test
    fun `an empty blank NUL or overlong command is rejected as invalid arguments`() {
        val overlong = "ls " + "a".repeat(ShellCommandClassifier.MAX_COMMAND_CHARS)

        listOf(
            "",
            "   ",
            "\t\n",
            "ls\u0000/sdcard",
            overlong,
        ).forEach { command ->
            val result = ShellCommandClassifier.classify(command)
            assertEquals(PrecheckDecision.Reject, result.decision, "expected Reject for a command of length ${command.length}")
            assertEquals("INVALID_ARGUMENTS", result.reason)
        }

        // The bound is inclusive: a command of exactly the maximum length is still a command, not a rejection.
        val exact = "ls " + "a".repeat(ShellCommandClassifier.MAX_COMMAND_CHARS - 3)
        assertEquals(ShellCommandClassifier.MAX_COMMAND_CHARS, exact.length)
        assertEquals(PrecheckDecision.Allow, ShellCommandClassifier.classify(exact).decision)
    }

    private fun assertRequiresConfirmation(command: String) {
        val result = ShellCommandClassifier.classify(command)

        assertEquals(PrecheckDecision.RequireConfirmation, result.decision, "expected RequireConfirmation for `$command`")
        val reason = assertNotNull(result.reason, "a required command must say why: `$command`")
        assertTrue(reason.isNotBlank(), "the reason must not be blank: `$command`")
        assertFalse(reason.contains(command), "the reason is a category and must never carry the command body: `$reason`")
        assertTrue(!result.summary.isNullOrBlank(), "the wearer needs a summary to decide on `$command`")
    }
}
