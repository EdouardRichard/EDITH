package com.sheen.rokid.phone.actions.device

import com.sheen.rokid.phone.actions.shizuku.PrivilegedShell
import com.sheen.rokid.phone.actions.shizuku.ShellFailure
import com.sheen.rokid.phone.actions.shizuku.ShellResult
import com.sheen.rokid.plugin.ActionOutcome
import com.sheen.rokid.plugin.ActionResult
import com.sheen.rokid.plugin.PrecheckDecision
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R45: the raw-command tool. `max_bytes` is the tool's own UTF-8 byte cut (a multibyte character is
 * never split), `stdin` is unsupported this version, and a timeout is not faked because the
 * privileged shell has no timeout API - the channel's own facts survive into the envelope.
 */
class ShellToolTest {

    private val json = Json

    @Test
    fun `a successful command reports stdout stderr and exit code`() {
        val shell = FakeShell(ShellResult("echo hi", 0, "hi\n", "", false))

        val result = ShellTool(shell).execute("""{"command":"echo hi"}""")

        assertEquals(ActionOutcome.Done, result.outcome)
        val body = body(result)
        assertEquals(true, body["ok"]!!.jsonPrimitive.boolean)
        assertEquals(0, body["exitCode"]!!.jsonPrimitive.int)
        assertEquals("hi\n", body["stdout"]!!.jsonPrimitive.content)
        assertEquals("", body["stderr"]!!.jsonPrimitive.content)
        assertFalse(body["truncated"]!!.jsonPrimitive.boolean)
        assertEquals("shell", body["channel"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, body["error"])
        assertEquals(listOf("echo hi"), shell.commands)
    }

    @Test
    fun `a non-zero exit keeps the exit code and the output but is not ok`() {
        val shell = FakeShell(ShellResult("false", 2, "partial", "bad thing", false))

        val result = ShellTool(shell).execute("""{"command":"false"}""")

        assertEquals(ActionOutcome.Failed, result.outcome)
        val body = body(result)
        assertEquals(false, body["ok"]!!.jsonPrimitive.boolean)
        assertEquals(2, body["exitCode"]!!.jsonPrimitive.int)
        assertEquals("partial", body["stdout"]!!.jsonPrimitive.content)
        assertEquals("bad thing", body["stderr"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, body["error"])
    }

    @Test
    fun `channel failures keep their own codes`() {
        // R65: only a reply cut by the carrier is SHELL_REPLY_TOO_LARGE; a carrier failure that did
        // not truncate anything is SHELL_FAILED, as is an unparseable reply.
        val cases = listOf(
            ShellFailure.NOT_BOUND to "SHELL_NOT_BOUND",
            ShellFailure.CARRIER to "SHELL_FAILED",
            ShellFailure.PARSE to "SHELL_FAILED",
        )
        cases.forEach { (failure, expected) ->
            val shell = FakeShell(ShellResult("x", -1, "", "marker", false, failure = failure))

            val result = ShellTool(shell).execute("""{"command":"x"}""")

            assertEquals("$failure must refuse", ActionOutcome.Refused, result.outcome)
            assertEquals(expected, body(result)["error"]!!.jsonObject["code"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun `a reply the carrier cut short is SHELL_REPLY_TOO_LARGE`() {
        val shell = FakeShell(ShellResult("x", 0, "a prefix", "", truncated = true))

        val result = ShellTool(shell).execute("""{"command":"x"}""")

        assertEquals(ActionOutcome.Refused, result.outcome)
        val body = body(result)
        assertEquals("SHELL_REPLY_TOO_LARGE", body["error"]!!.jsonObject["code"]!!.jsonPrimitive.content)
        assertEquals(false, body["ok"]!!.jsonPrimitive.boolean)
        assertTrue(body["truncated"]!!.jsonPrimitive.boolean)
        assertEquals("a prefix", body["stdout"]!!.jsonPrimitive.content)
    }

    @Test
    fun `max_bytes cuts UTF-8 output on a character boundary`() {
        val shell = FakeShell(ShellResult("echo", 0, "你好世界", "错误信息", false))

        val result = ShellTool(shell).execute("""{"command":"echo","max_bytes":4}""")

        assertEquals(ActionOutcome.Done, result.outcome)
        val body = body(result)
        assertEquals("你", body["stdout"]!!.jsonPrimitive.content)
        assertEquals("错", body["stderr"]!!.jsonPrimitive.content)
        assertTrue(body["truncated"]!!.jsonPrimitive.boolean)
        assertTrue(
            "the cut must not produce a partial multibyte character",
            body["stdout"]!!.jsonPrimitive.content.toByteArray(Charsets.UTF_8).size <= 4,
        )
    }

    @Test
    fun `a non-empty stdin is UNSUPPORTED and the command is not run`() {
        val shell = FakeShell()

        val result = ShellTool(shell).execute("""{"command":"ls","stdin":"data"}""")

        assertEquals(ActionOutcome.Refused, result.outcome)
        assertEquals("UNSUPPORTED", body(result)["error"]!!.jsonObject["code"]!!.jsonPrimitive.content)
        assertTrue(shell.commands.isEmpty())
    }

    @Test
    fun `timeout_ms does not invent a timeout the channel never reported`() {
        val shell = FakeShell(ShellResult("slow", -1, "", "marker", false, failure = ShellFailure.CARRIER))

        val result = ShellTool(shell).execute("""{"command":"slow","timeout_ms":1}""")

        val code = body(result)["error"]!!.jsonObject["code"]!!.jsonPrimitive.content
        assertEquals("SHELL_FAILED", code)
        assertFalse("the shell has no timeout API; the tool must not claim ACTION_TIMEOUT",
            result.detail.contains("ACTION_TIMEOUT"))
    }

    @Test
    fun `a missing command is refused as invalid arguments`() {
        val shell = FakeShell()

        assertEquals("INVALID_ARGUMENTS", ShellTool(shell).execute("""{"timeout_ms":10}""").detail)
        assertTrue(shell.commands.isEmpty())
    }

    // ---- the kernel-side ShellPort adapter ----

    @Test
    fun `the kernel adapter maps success truncation and channel failure`() {
        val shell = FakeShell(ShellResult("cmd", 0, "你好世界", "", false))
        val port = PrivilegedShellPort(shell)

        val cut = port.run("cmd", 1_000, 4)
        assertEquals(0, cut.exitCode)
        assertEquals("你", cut.stdout)
        assertTrue(cut.truncated)
        assertNull(cut.failure)

        shell.result = ShellResult("cmd", -1, "", "marker", false, failure = ShellFailure.NOT_BOUND)
        val down = port.run("cmd", 1_000, 64)
        assertEquals("SHELL_NOT_BOUND", down.failure)

        shell.result = ShellResult("cmd", -1, "", "carrier refused", false, failure = ShellFailure.CARRIER)
        val carrier = port.run("cmd", 1_000, 64)
        assertEquals("SHELL_FAILED", carrier.failure)

        shell.result = ShellResult("cmd", 0, "a prefix", "", truncated = true)
        val cutShort = port.run("cmd", 1_000, 64)
        assertEquals("SHELL_REPLY_TOO_LARGE", cutShort.failure)
        assertTrue(cutShort.truncated)
    }

    // ---- R49/R50: the precheck seam ----

    @Test
    fun `classify allows a read only command without touching the shell`() {
        val shell = FakeShell()

        val precheck = ShellTool(shell).classify("""{"command":"dumpsys window"}""")

        assertEquals(PrecheckDecision.Allow, precheck.decision)
        assertTrue("classifying is not executing", shell.commands.isEmpty())
    }

    @Test
    fun `classify requires confirmation for an irreversible command without touching the shell`() {
        val shell = FakeShell()

        val precheck = ShellTool(shell).classify("""{"command":"pm clear com.x"}""")

        assertEquals(PrecheckDecision.RequireConfirmation, precheck.decision)
        assertTrue("the wearer needs a summary to decide", !precheck.summary.isNullOrBlank())
        assertTrue("the reason is a category, never the command body",
            !precheck.reason.isNullOrBlank() && !precheck.reason!!.contains("pm clear"))
        assertTrue("classifying must never run the command", shell.commands.isEmpty())
    }

    @Test
    fun `classify rejects a malformed or blank command argument`() {
        val tool = ShellTool(FakeShell())

        listOf(
            """{"timeout_ms":1}""",
            """{"command":""}""",
            """{"command":"   "}""",
            """{"command":123}""",
            """{"command":"ls\u0000/sdcard"}""",
            "not json",
            "",
        ).forEach { args ->
            assertEquals("args: $args", PrecheckDecision.Reject, tool.classify(args).decision)
        }
    }

    @Test
    fun `precheck classifies the shell tool and defaults every other name to allow`() {
        val tool = ShellTool(null)

        assertEquals(PrecheckDecision.RequireConfirmation,
            tool.precheck("shell", """{"command":"rm -rf /sdcard/x"}""").decision)
        assertEquals(PrecheckDecision.Allow,
            tool.precheck("shell", """{"command":"ls /sdcard"}""").decision)
        assertEquals(PrecheckDecision.Allow,
            tool.precheck("device", """{"action":"observe"}""").decision)
    }

    private fun body(result: ActionResult): JsonObject =
        json.parseToJsonElement(result.detail).jsonObject

    private class FakeShell(
        var result: ShellResult = ShellResult("", 0, "", ""),
    ) : PrivilegedShell {
        val commands = mutableListOf<String>()

        override fun run(command: String): ShellResult {
            commands += command
            return result
        }
    }
}
