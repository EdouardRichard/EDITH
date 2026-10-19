package com.sheen.rokid.phone.actions.device

import com.sheen.rokid.phone.actions.intent.AppCatalog
import com.sheen.rokid.phone.actions.intent.AppEntry
import com.sheen.rokid.phone.actions.shizuku.PrivilegedShell
import com.sheen.rokid.phone.actions.shizuku.ShellFailure
import com.sheen.rokid.phone.actions.shizuku.ShellResult
import com.sheen.rokid.plugin.ActionOutcome
import com.sheen.rokid.plugin.ActionResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R43 (section 4.11): `open` is a structured tool - ambiguity is a refusal whose `data.candidates` is the
 * payload (never a `CODE:payload` string), a URL is validated and single-quote-escaped before it
 * becomes a shell command, and a package launch resolves the launcher component first.
 */
class OpenToolTest {

    private val json = Json

    // ---- list ----

    @Test
    fun `list returns label with package rows and marks the 100-row truncation`() {
        val packages = (0 until 101).map { "com.example.app$it" }
        val shell = FakeShell { command ->
            ShellResult(command, 0, packages.joinToString("\n") { "package:$it" } + "\n", "")
        }
        val catalog = FakeCatalog(
            listOf(AppEntry("com.example.app0", "音乐", "android.intent.action.MAIN")),
        )

        val result = OpenTool(shell, catalog).execute("""{"list":true}""")

        assertEquals(ActionOutcome.Done, result.outcome)
        val data = body(result)["data"]!!.jsonObject
        assertEquals(100, data["apps"]!!.jsonArray.size)
        assertTrue(data["truncated"]!!.jsonPrimitive.boolean)
        val text = data["text"]!!.jsonPrimitive.content
        assertTrue("known app must use its label: $text", text.contains("音乐 (com.example.app0)"))
        assertTrue("unknown app falls back to its package", text.contains("com.example.app1 (com.example.app1)"))
        assertTrue(shell.commands.any { it.contains("pm list packages -3") })
    }

    @Test
    fun `list without a shell channel is CHANNEL_UNAVAILABLE`() {
        val result = OpenTool(null, FakeCatalog()).execute("""{"list":true}""")

        assertEquals(ActionOutcome.Refused, result.outcome)
        assertEquals("CHANNEL_UNAVAILABLE", errorCode(result))
    }

    // ---- resolution ----

    @Test
    fun `an ambiguous name refuses with structured candidates`() {
        val catalog = FakeCatalog(
            listOf(
                AppEntry("com.example.music1", "音乐", "android.intent.action.MAIN"),
                AppEntry("com.example.music2", "音乐", "android.intent.action.MAIN"),
            ),
        )
        val shell = FakeShell { command -> ShellResult(command, 0, "Status: ok\n", "") }

        val result = OpenTool(shell, catalog).execute("""{"target":"音乐"}""")

        assertEquals(ActionOutcome.Refused, result.outcome)
        val body = body(result)
        assertEquals("APP_AMBIGUOUS", body["error"]!!.jsonObject["code"]!!.jsonPrimitive.content)
        assertTrue(body["error"]!!.jsonObject["recoverable"]!!.jsonPrimitive.boolean)
        val candidates = body["data"]!!.jsonObject["candidates"]!!.jsonArray
        assertEquals(2, candidates.size)
        assertEquals("音乐", candidates[0].jsonObject["label"]!!.jsonPrimitive.content)
        assertEquals("com.example.music1", candidates[0].jsonObject["packageName"]!!.jsonPrimitive.content)
        assertEquals("音乐", candidates[1].jsonObject["label"]!!.jsonPrimitive.content)
        assertEquals("com.example.music2", candidates[1].jsonObject["packageName"]!!.jsonPrimitive.content)
        assertTrue("ambiguity must not launch anything", shell.commands.isEmpty())
    }

    @Test
    fun `an unknown name is APP_NOT_FOUND`() {
        val pipeline = ShellPipeline()
        val result = OpenTool(pipeline.shell, FakeCatalog()).execute("""{"target":"不存在的应用"}""")

        assertEquals(ActionOutcome.Refused, result.outcome)
        assertEquals("APP_NOT_FOUND", errorCode(result))
        assertTrue(pipeline.shell.commands.isEmpty())
    }

    @Test
    fun `a non-http scheme is INVALID_URL and never reaches the shell`() {
        val shell = FakeShell()

        val result = OpenTool(shell, FakeCatalog()).execute("""{"target":"ftp://example.com/x"}""")

        assertEquals(ActionOutcome.Refused, result.outcome)
        assertEquals("INVALID_URL", errorCode(result))
        assertTrue(shell.commands.isEmpty())
    }

    // ---- launches ----

    @Test
    fun `a package target resolves the launcher component then starts it`() {
        val pipeline = ShellPipeline()
        val result = OpenTool(pipeline.shell, FakeCatalog()).execute("""{"target":"com.example.music"}""")

        assertEquals(ActionOutcome.Done, result.outcome)
        assertEquals(
            "cmd package resolve-activity --brief -c android.intent.category.LAUNCHER com.example.music",
            pipeline.shell.commands[0],
        )
        assertEquals(
            "am start -W -n com.example.music/.MainActivity",
            pipeline.shell.commands[1],
        )
    }

    @Test
    fun `an incomplete resolve reply is CHANNEL_UNAVAILABLE`() {
        val shell = FakeShell { command ->
            ShellResult(command, -1, "", "SHELL_NOT_BOUND", false, failure = ShellFailure.NOT_BOUND)
        }

        val result = OpenTool(shell, FakeCatalog()).execute("""{"target":"com.example.music"}""")

        assertEquals(ActionOutcome.Refused, result.outcome)
        assertEquals("CHANNEL_UNAVAILABLE", errorCode(result))
    }

    @Test
    fun `a resolve exit other than zero is CHANNEL_UNAVAILABLE`() {
        val shell = FakeShell { command -> ShellResult(command, 1, "", "boom") }

        val result = OpenTool(shell, FakeCatalog()).execute("""{"target":"com.example.music"}""")

        assertEquals("CHANNEL_UNAVAILABLE", errorCode(result))
    }

    @Test
    fun `no resolved component falls back to a launcher intent`() {
        val pipeline = ShellPipeline(resolveComponent = null)
        val result = OpenTool(pipeline.shell, FakeCatalog()).execute("""{"target":"com.example.music"}""")

        assertEquals(ActionOutcome.Done, result.outcome)
        assertEquals(
            "am start -W -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -p com.example.music",
            pipeline.shell.commands[1],
        )
    }

    @Test
    fun `a launch without Status ok is a Failed STATE_UNCHANGED`() {
        val pipeline = ShellPipeline(statusOk = false)

        val result = OpenTool(pipeline.shell, FakeCatalog()).execute("""{"target":"com.example.music"}""")

        assertEquals(ActionOutcome.Failed, result.outcome)
        assertEquals("STATE_UNCHANGED", errorCode(result))
        assertFalse(result.detail.contains("Status: ok"))
    }

    @Test
    fun `relaunch false with the target already in front does not start it again`() {
        val shell = FakeShell { command -> ShellResult(command, 0, "Status: ok\n", "") }

        val result = OpenTool(shell, FakeCatalog(), foreground = { "com.example.music" })
            .execute("""{"target":"com.example.music","relaunch":false}""")

        assertEquals(ActionOutcome.Done, result.outcome)
        assertTrue("already-foreground must not launch", shell.commands.isEmpty())
        assertTrue(result.detail.contains("already foreground"))
    }

    @Test
    fun `relaunch true starts the app even when it is already in front`() {
        val pipeline = ShellPipeline()

        val result = OpenTool(pipeline.shell, FakeCatalog(), foreground = { "com.example.music" })
            .execute("""{"target":"com.example.music","relaunch":true}""")

        assertEquals(ActionOutcome.Done, result.outcome)
        assertEquals(2, pipeline.shell.commands.size)
    }

    @Test
    fun `an http URL is single-quote escaped before becoming a shell command`() {
        val shell = FakeShell { command -> ShellResult(command, 0, "Status: ok\n", "") }

        val result = OpenTool(shell, FakeCatalog())
            .execute("""{"target":"https://example.com/a'b"}""")

        assertEquals(ActionOutcome.Done, result.outcome)
        assertEquals(
            "am start -W -a android.intent.action.VIEW -d 'https://example.com/a'\\''b'",
            shell.commands.single(),
        )
    }

    @Test
    fun `a settings page maps to its android settings action`() {
        val shell = FakeShell { command -> ShellResult(command, 0, "Status: ok\n", "") }

        val result = OpenTool(shell, FakeCatalog()).execute("""{"target":"wifi"}""")

        assertEquals(ActionOutcome.Done, result.outcome)
        assertEquals("am start -W -a android.settings.WIFI_SETTINGS", shell.commands.single())
    }

    @Test
    fun `open without a shell channel is CHANNEL_UNAVAILABLE`() {
        val result = OpenTool(null, FakeCatalog()).execute("""{"target":"com.example.music"}""")

        assertEquals(ActionOutcome.Refused, result.outcome)
        assertEquals("CHANNEL_UNAVAILABLE", errorCode(result))
    }

    // ---- helpers ----

    private fun body(result: ActionResult): JsonObject =
        json.parseToJsonElement(result.detail).jsonObject

    private fun errorCode(result: ActionResult): String =
        body(result)["error"]!!.jsonObject["code"]!!.jsonPrimitive.content

    private class FakeCatalog(
        private val apps: List<AppEntry> = emptyList(),
    ) : AppCatalog {
        override fun visibleApps(): List<AppEntry> = apps
    }

    private class FakeShell(
        private val answer: (String) -> ShellResult = { command -> ShellResult(command, 0, "", "") },
    ) : PrivilegedShell {
        val commands = mutableListOf<String>()

        override fun run(command: String): ShellResult {
            commands += command
            return answer(command)
        }
    }

    /**
     * The resolve-activity/am-start pair a package launch needs, with the component and Status the
     * test chose. Every command is recorded by [shell].
     */
    private class ShellPipeline(
        private val resolveComponent: String? = "com.example.music/.MainActivity",
        private val statusOk: Boolean = true,
    ) {
        val shell = FakeShell { command ->
            when {
                command.startsWith("cmd package resolve-activity") ->
                    ShellResult(
                        command,
                        0,
                        "priority=0 preferredOrder=0\n${resolveComponent ?: ""}\n",
                        "",
                    )
                command.startsWith("am start -W") ->
                    ShellResult(
                        command,
                        0,
                        "Starting: Intent { act=android.intent.action.MAIN }\n" +
                            (if (statusOk) "Status: ok\n" else "Error: Activity not started\n"),
                        "",
                    )
                else -> ShellResult(command, 0, "", "")
            }
        }
    }
}
