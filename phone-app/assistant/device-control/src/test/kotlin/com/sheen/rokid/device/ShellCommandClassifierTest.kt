package com.sheen.rokid.device

import com.sheen.rokid.plugin.PrecheckDecision
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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
    fun `benign queries and read only commands are allowed`() {
        val commands = listOf(
            "dumpsys window",
            "pm list packages",
            "pm path com.x",
            "settings get system screen_brightness",
            "settings list global",
            "getprop ro.product.model",
            "ls /sdcard",
            "stat /sdcard/x",
            "content query --uri content://com.x/items",
            "am start -a android.intent.action.VIEW -d https://x",
            // B6: `cmd package` queries are read-only; they must not be treated as an unknown command.
            "cmd package list packages",
            "cmd package list packages -3",
            "cmd package path com.x",
            "cmd package resolve-activity --brief com.x",
            // The same benign words with extra whitespace, an absolute path and quoting are still benign.
            "  cmd   package   list   packages  ",
            "\tcmd\tpackage\tlist\tpackages\t",
            "/system/bin/cmd package list packages",
            "'cmd' 'package' 'list' 'packages'",
            "cmd \"package\" list 'packages'",
            "cmd package list packages 2>/dev/null",
        )

        commands.forEach { command ->
            val result = ShellCommandClassifier.classify(command)
            assertEquals(PrecheckDecision.Allow, result.decision, "expected Allow for `$command`")
            assertNull(result.reason, "Allow is 'no question needed', so it carries no risk category: `$command`")
        }
    }

    @Test
    fun `high risk device state commands require confirmation with a category reason`() {
        val cases = listOf(
            // B6: these used to be allowed. They change device state, so the wearer must be asked.
            "pm disable com.x" to "DEVICE_STATE",
            "  pm   disable   com.x  " to "DEVICE_STATE",
            "'pm' 'disable' com.x" to "DEVICE_STATE",
            "/system/bin/pm disable com.x" to "DEVICE_STATE",
            "pm disable-user --user 0 com.x" to "DEVICE_STATE",
            "pm enable com.x" to "DEVICE_STATE",
            "settings put system screen_brightness 10" to "DEVICE_STATE",
            "am force-stop com.x" to "PROCESS_KILL",
            // The rest of the dangerous table, pinned by category so a reclassification cannot pass silently.
            "input tap 100 200" to "DEVICE_STATE",
            "reboot" to "DEVICE_STATE",
            "su -c id" to "SECURITY",
            "settings delete global screen_brightness" to "IRREVERSIBLE",
            "settings reset global" to "IRREVERSIBLE",
            "pm clear com.x" to "IRREVERSIBLE",
            "pm uninstall com.x" to "IRREVERSIBLE",
            "content insert --uri content://x" to "DATA_WRITE",
            "content delete --uri content://x" to "IRREVERSIBLE",
            "am kill com.x" to "PROCESS_KILL",
            "kill 1234" to "PROCESS_KILL",
        )

        cases.forEach { (command, reason) ->
            val result = ShellCommandClassifier.classify(command)
            assertEquals(PrecheckDecision.RequireConfirmation, result.decision,
                "expected RequireConfirmation for `$command`")
            assertEquals(reason, result.reason, "unexpected reason category for `$command`")
            assertTrue(!result.summary.isNullOrBlank(), "the wearer needs a summary to decide on `$command`")
            assertFalse(result.summary!!.contains(command), "the question must not carry the command body")
        }
    }

    @Test
    fun `unknown and ambiguous commands fail closed with a confirmation`() {
        // Fail-closed is the contract: a command the table does not know is asked about, never allowed.
        listOf(
            "unknown_command --x",
            "pm frobnicate com.x",
            "settings frobnicate global",
            "am frobnicate",
            "cmd package frobnicate",
            "cmd wifi set-wifi-enabled disabled",
            "busybox rm -rf /sdcard/x",
            "pm",
            "settings",
            "am",
            "cmd",
            "pm DISABLE com.x",
            "timeout 5 pm frobnicate",
        ).forEach { command ->
            val result = ShellCommandClassifier.classify(command)
            assertEquals(PrecheckDecision.RequireConfirmation, result.decision,
                "an unknown command must ask, never allow: `$command`")
            assertEquals("UNKNOWN_COMMAND", result.reason, "expected the unknown-command category: `$command`")
            assertFalse(result.reason!!.contains(command), "the reason must never carry the command body")
        }
    }

    @Test
    fun `compound wrappers variables and redirects cannot launder a command into Allow`() {
        val cases = listOf(
            "cmd package list packages; pm disable com.x" to "DEVICE_STATE",
            "cmd package list packages && settings put system screen_brightness 10" to "DEVICE_STATE",
            "cmd package list packages || am force-stop com.x" to "PROCESS_KILL",
            "cmd package list packages\npm disable com.x" to "DEVICE_STATE",
            "cmd package list packages; rm -rf /sdcard/x" to "IRREVERSIBLE",
            "cmd package list packages && su -c id" to "SECURITY",
            "cmd package list packages; unknown_command --x" to "UNKNOWN_COMMAND",
            "cmd package list packages | sh" to "SHELL_WRAPPER",
            "pm list packages | sh" to "SHELL_WRAPPER",
            "cmd package list packages | tee /sdcard/x" to "DATA_WRITE",
            "sh -c 'cmd package list packages; pm disable com.x'" to "SHELL_WRAPPER",
            "eval settings put system screen_brightness 10" to "SHELL_WRAPPER",
            "env pm disable com.x" to "DEVICE_STATE",
            "timeout 5 pm disable com.x" to "DEVICE_STATE",
            "cmd package list packages > /sdcard/x" to "SHELL_SYNTAX",
            "cmd package list packages >>/dev/null" to "SHELL_SYNTAX",
            "echo \$(pm disable com.x)" to "OBFUSCATED",
            "echo `pm disable com.x`" to "OBFUSCATED",
            "A=com.x pm disable \$A" to "OBFUSCATED",
            "cmd package list packages | base64 -d | sh" to "OBFUSCATED",
            // A harmless redirect is stripped, but it must not launder the base command.
            "pm disable com.x >/dev/null" to "DEVICE_STATE",
            "settings put system screen_brightness 10 2>/dev/null" to "DEVICE_STATE",
            "pm clear com.x 2>/dev/null" to "IRREVERSIBLE",
        )

        cases.forEach { (command, reason) ->
            val result = ShellCommandClassifier.classify(command)
            assertEquals(PrecheckDecision.RequireConfirmation, result.decision,
                "a compound/wrapped command must ask as a whole: `$command`")
            assertEquals(reason, result.reason, "unexpected reason category for `$command`")
            assertTrue(!result.summary.isNullOrBlank(), "the wearer needs a summary to decide on `$command`")
            assertFalse(result.summary!!.contains(command), "the question must not carry the command body")
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
    fun `command substitution and variables inside double quotes require confirmation`() {
        // The production shell runs `sh -c`: double quotes do **not** stop `$(...)`, backticks or `$VAR` from being
        // expanded before the command runs, so allowing them would execute text this classifier never saw.
        val commands = listOf(
            "dumpsys \"\$(rm -rf /sdcard/x)\"",
            "echo \"\$(pm clear com.x)\"",
            "echo \"`id`\"",
            "A=\"\$(reboot)\" dumpsys",
            "echo \"\$HOME\"",
        )

        commands.forEach(::assertRequiresConfirmation)
    }

    @Test
    fun `escaped and single quoted dollar signs stay literal and allowed`() {
        // In the shell a backslash-escaped `$` inside double quotes and every `$` inside single quotes are literal
        // text, not expansion; a fix for the quoted bypass must not turn these into false positives.
        listOf(
            "echo \"\\\$HOME\"",
            "echo '\$HOME'",
        ).forEach { command ->
            assertEquals(PrecheckDecision.Allow, ShellCommandClassifier.classify(command).decision,
                "a literal dollar must not be treated as expansion: `$command`")
        }
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
    fun `harmless dev null redirections are stripped before classification`() {
        // R130: `2>/dev/null` is the idiom every diagnostic command uses. Treating it as unreadable shell syntax made
        // `dumpsys window 2>/dev/null` ask the wearer for 120 s on every round, while still not letting the redirect
        // launder a dangerous command.
        listOf(
            "dumpsys window 2>/dev/null",
            "dumpsys window >/dev/null",
            "dumpsys window 1>/dev/null",
            "dumpsys window > /dev/null",
            "dumpsys window 2>&1",
            "dumpsys window 1>&2",
            "dumpsys window >/dev/null 2>&1",
        ).forEach { command ->
            assertEquals(
                PrecheckDecision.Allow,
                ShellCommandClassifier.classify(command).decision,
                "a redirect to /dev/null (or an fd duplication) must not force confirmation: `$command`",
            )
        }

        // The redirect is removed, then the remaining command is classified by the normal table: it cannot wash a
        // dangerous command clean.
        val clear = ShellCommandClassifier.classify("pm clear com.x >/dev/null")
        assertEquals(PrecheckDecision.RequireConfirmation, clear.decision)
        assertEquals("IRREVERSIBLE", clear.reason, "the base command, not the redirect, must decide")
        assertRequiresConfirmation("rm -rf /sdcard/x 2>/dev/null")

        // Every other redirect target is still unreadable shell syntax.
        listOf(
            "echo x >/sdcard/x",
            "echo x > /sdcard/x",
            "dumpsys window >>/dev/null",
            "dumpsys window >/dev/nullx",
        ).forEach { command ->
            val result = ShellCommandClassifier.classify(command)
            assertEquals(PrecheckDecision.RequireConfirmation, result.decision, "expected RequireConfirmation for `$command`")
            assertEquals("SHELL_SYNTAX", result.reason, "an unsafe redirect is a syntax category: `$command`")
        }
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
