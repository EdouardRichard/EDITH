package com.sheen.rokid.phone.actions.device

import com.sheen.rokid.phone.actions.shizuku.PrivilegedShell
import com.sheen.rokid.phone.actions.shizuku.ShellFailure
import com.sheen.rokid.phone.actions.shizuku.ShellResult
import com.sheen.rokid.plugin.ActionOutcome
import com.sheen.rokid.plugin.ActionResult
import com.sheen.rokid.plugin.PrecheckDecision
import com.sheen.rokid.plugin.ToolOutcome
import kotlinx.serialization.json.Json
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
        val execution = requireNotNull(result.execution)
        assertEquals(ToolOutcome.Done, execution.outcome)
        assertEquals(true, execution.accepted)
        val body = body(result)
        assertEquals(0, body["exitCode"]!!.jsonPrimitive.int)
        assertEquals("hi\n", body["stdout"]!!.jsonPrimitive.content)
        assertEquals("", body["stderr"]!!.jsonPrimitive.content)
        assertFalse(body["truncated"]!!.jsonPrimitive.boolean)
        assertFalse("data no longer repeats ok/channel/error", body.containsKey("ok"))
        assertFalse(body.containsKey("channel"))
        assertFalse(body.containsKey("error"))
        assertEquals(listOf("echo hi"), shell.commands)
    }

    @Test
    fun `a non-zero exit keeps the exit code and the output but is not ok`() {
        val shell = FakeShell(ShellResult("false", 2, "partial", "bad thing", false))

        val result = ShellTool(shell).execute("""{"command":"false"}""")

        assertEquals(ActionOutcome.Failed, result.outcome)
        val execution = requireNotNull(result.execution)
        assertEquals(ToolOutcome.Failed, execution.outcome)
        assertEquals("a non-zero exit is not a missing channel", null, execution.code)
        assertEquals("the command ran", true, execution.accepted)
        val body = body(result)
        assertEquals(2, body["exitCode"]!!.jsonPrimitive.int)
        assertEquals("partial", body["stdout"]!!.jsonPrimitive.content)
        assertEquals("bad thing", body["stderr"]!!.jsonPrimitive.content)
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
            val execution = requireNotNull(result.execution)
            assertEquals(ToolOutcome.Refused, execution.outcome)
            assertEquals(expected, execution.code)
            assertEquals("a channel refusal never dispatched the command", false, execution.accepted)
            assertEquals(false, execution.applied)
            assertEquals("the channel can come back", true, execution.recoverable)
            assertTrue(
                "a channel failure keeps a stable, content-free message: ${execution.message}",
                !execution.message.isNullOrBlank() && !execution.message!!.contains("marker"),
            )
        }
    }

    @Test
    fun `a reply the carrier cut short is SHELL_REPLY_TOO_LARGE`() {
        val shell = FakeShell(ShellResult("x", 0, "a prefix", "", truncated = true))

        val result = ShellTool(shell).execute("""{"command":"x"}""")

        assertEquals(ActionOutcome.Refused, result.outcome)
        val execution = requireNotNull(result.execution)
        assertEquals("SHELL_REPLY_TOO_LARGE", execution.code)
        assertEquals("a cut reply proves the command ran", true, execution.accepted)
        assertEquals("only the answer was cut; applying is unknown", null, execution.applied)
        val body = body(result)
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
        assertEquals("UNSUPPORTED", requireNotNull(result.execution).code)
        assertEquals("a malformed call has no recoverable channel", false, result.execution!!.recoverable)
        assertEquals("stdin is not supported", result.execution!!.message)
        assertTrue(shell.commands.isEmpty())
    }

    @Test
    fun `timeout_ms does not invent a timeout the channel never reported`() {
        val shell = FakeShell(ShellResult("slow", -1, "", "marker", false, failure = ShellFailure.CARRIER))

        val result = ShellTool(shell).execute("""{"command":"slow","timeout_ms":1}""")

        val code = requireNotNull(result.execution).code
        assertEquals("SHELL_FAILED", code)
        assertFalse("the shell has no timeout API; the tool must not claim ACTION_TIMEOUT",
            result.detail.contains("ACTION_TIMEOUT"))
    }

    @Test
    fun `a missing command is refused as invalid arguments`() {
        val shell = FakeShell()

        val result = ShellTool(shell).execute("""{"timeout_ms":10}""")
        assertEquals("INVALID_ARGUMENTS", requireNotNull(result.execution).code)
        assertEquals("a malformed call is not recoverable", false, result.execution!!.recoverable)
        assertTrue(shell.commands.isEmpty())
    }

    // ---- M1: a post-dispatch throw is uncertain, never a pre-dispatch refusal ----

    @Test
    fun `a dispatched command whose shell throws is uncertain and accepted`() {
        val shell = ThrowingShell()

        val result = ShellTool(shell).execute("""{"command":"reboot"}""")

        assertEquals("the legacy projection cannot express uncertainty", ActionOutcome.Failed, result.outcome)
        val execution = requireNotNull(result.execution)
        assertEquals(ToolOutcome.Uncertain, execution.outcome)
        assertEquals("SHELL_FAILED", execution.code)
        assertEquals("the command crossed into the shell before the throw", true, execution.accepted)
        assertTrue("a dispatched call may have applied", execution.uncertain)
        assertEquals("an uncertain channel can be retried", true, execution.recoverable)
        assertNull("the command may have applied", execution.applied)
        assertNull(execution.verified)
        assertNull(execution.changed)
        assertEquals("the shell call may have been dispatched", execution.message)
        assertEquals("the dispatch really reached the shell", listOf("reboot"), shell.commands)
        assertTrue("a shell call is a write even when it threw", result.stateChanging)
        assertEquals("no tool-specific data for a thrown call", "{}", result.detail)
    }

    @Test
    fun `an argument refusal stays a pre-dispatch refusal`() {
        val result = ShellTool(FakeShell()).execute("""{"timeout_ms":10}""")

        val execution = requireNotNull(result.execution)
        assertEquals(ActionOutcome.Refused, result.outcome)
        assertEquals(ToolOutcome.Refused, execution.outcome)
        assertEquals("a malformed call never dispatched anything", false, execution.accepted)
        assertFalse(execution.uncertain)
        assertEquals("INVALID_ARGUMENTS", execution.code)
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

    // ---- R98: the state-changing declaration ----

    @Test
    fun `shell results declare a state change`() {
        val done = ShellTool(FakeShell(ShellResult("echo hi", 0, "hi\n", "", false)))
            .execute("""{"command":"echo hi"}""")
        assertTrue(done.stateChanging)

        // The declaration describes the call class, not whether the command got to run.
        val refused = ShellTool(null).execute("""{"command":"echo hi"}""")
        assertTrue("a shell call is a write even when its channel is unavailable", refused.stateChanging)

        // Fix review: an unknown action is not a shell call at all, so it must not declare a state change.
        val unknown = ShellTool(null).execute("device", "{}")
        assertEquals("UNKNOWN_ACTION", requireNotNull(unknown.execution).code)
        assertFalse("an unknown name is not a shell call", unknown.stateChanging)
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

    /** M1: the dispatch itself blows up (a broken binder/port); the call already reached the shell. */
    private class ThrowingShell : PrivilegedShell {
        val commands = mutableListOf<String>()

        override fun run(command: String): ShellResult {
            commands += command
            error("shell port died mid-call")
        }
    }
}
