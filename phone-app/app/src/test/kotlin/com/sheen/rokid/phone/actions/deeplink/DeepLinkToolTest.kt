package com.sheen.rokid.phone.actions.deeplink

import com.sheen.rokid.phone.platform.P3Log
import com.sheen.rokid.plugin.ActionOutcome
import com.sheen.rokid.plugin.ActionResult
import com.sheen.rokid.plugin.RiskLevel
import com.sheen.rokid.plugin.ToolOutcome
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.5.4 Task 14 (spec §6.4): `deep_link` launches `ACTION_VIEW` and then **proves** where the phone ended up.
 *
 * The launcher's `Accepted` is only the shell's answer; the tool must wait for the requested package and require the
 * accessibility root to agree before it reports success. A launch that lands somewhere else is reported as what it is:
 * `STATE_UNCHANGED`, `BROWSER_FALLBACK` when an http(s) link was taken by another package, and `UNVERIFIED` when the
 * accessibility root cannot be read at all. The audit line may carry scheme/host/package, never the URI or its query.
 */
class DeepLinkToolTest {

    // ── verification ──────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a package that becomes foreground and root is Done with verified execution facts`() {
        val rig = Rig(
            result = DeepLinkLaunchResult.Accepted("com.sankuai.meituan"),
            foregroundNow = { "com.sankuai.meituan" },
            rootNow = { "com.sankuai.meituan" },
        )
        val uri = "imeituan://www.meituan.com/restaurant?id=1"

        val result = rig.tool.execute("""{"uri":"$uri","package":"com.sankuai.meituan"}""")

        assertEquals(ActionOutcome.Done, result.outcome)
        val data = body(result)
        assertEquals(true, data["launched"]!!.jsonPrimitive.boolean)
        assertEquals("com.sankuai.meituan", data["handlerPackage"]!!.jsonPrimitive.content)
        assertEquals("imeituan", data["scheme"]!!.jsonPrimitive.content)
        val execution = requireNotNull(result.execution)
        assertEquals(ToolOutcome.Done, execution.outcome)
        assertEquals(true, execution.accepted)
        assertEquals(true, execution.applied)
        assertEquals(true, execution.verified)
        assertEquals(true, execution.changed)
        assertEquals(
            "the launcher receives the URI and the requested package",
            listOf(uri to "com.sankuai.meituan"),
            rig.launcher.calls,
        )
        assertEquals(
            "the tool must wait for the requested package with the same 2 s budget as `open`",
            listOf("com.sankuai.meituan" to 2_000L),
            rig.waits,
        )
    }

    @Test
    fun `an http link without a package is verified when foreground and root agree`() {
        val rig = Rig(
            result = DeepLinkLaunchResult.Accepted("com.android.chrome"),
            foregroundNow = { "com.android.chrome" },
            rootNow = { "com.android.chrome" },
        )

        val result = rig.tool.execute("""{"uri":"https://example.com/a?b=1"}""")

        assertEquals(ActionOutcome.Done, result.outcome)
        val data = body(result)
        assertEquals("com.android.chrome", data["handlerPackage"]!!.jsonPrimitive.content)
        assertEquals("https", data["scheme"]!!.jsonPrimitive.content)
        assertEquals(
            "the resolved handler is the package the launch is awaited on",
            listOf("com.android.chrome" to 2_000L),
            rig.waits,
        )
        assertEquals(true, requireNotNull(result.execution).verified)
    }

    @Test
    fun `a custom scheme launch that never reaches the package is STATE_UNCHANGED`() {
        val rig = Rig(
            result = DeepLinkLaunchResult.Accepted("com.sankuai.meituan"),
            foregroundNow = { "com.miui.home" },
            rootNow = { "com.miui.home" },
            reachesForeground = false,
        )

        val result = rig.tool.execute("""{"uri":"imeituan://www.meituan.com/x","package":"com.sankuai.meituan"}""")

        assertEquals(ActionOutcome.Failed, result.outcome)
        val execution = requireNotNull(result.execution)
        assertEquals("STATE_UNCHANGED", execution.code)
        assertEquals(ToolOutcome.Failed, execution.outcome)
        assertEquals(true, execution.accepted)
        assertNull("the launch may have applied somewhere else", execution.applied)
        assertEquals(false, execution.verified)
    }

    @Test
    fun `an http launch taken by another package is BROWSER_FALLBACK with the actual foreground`() {
        val rig = Rig(
            result = DeepLinkLaunchResult.Accepted("com.example.app"),
            foregroundNow = { "com.android.chrome" },
            rootNow = { "com.android.chrome" },
            reachesForeground = false,
        )

        val result = rig.tool.execute(
            """{"uri":"https://example.com/app?id=1","package":"com.example.app"}""",
        )

        assertEquals(ActionOutcome.Failed, result.outcome)
        val execution = requireNotNull(result.execution)
        assertEquals("BROWSER_FALLBACK", execution.code)
        assertEquals(false, execution.verified)
        assertEquals(true, execution.accepted)
        assertNull(execution.applied)
        assertEquals(
            "the model must see the package that actually came to front",
            "com.android.chrome",
            body(result)["foregroundPackage"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `an unreadable accessibility root is UNVERIFIED and never success`() {
        val rig = Rig(
            result = DeepLinkLaunchResult.Accepted("com.sankuai.meituan"),
            foregroundNow = { "com.sankuai.meituan" },
            rootNow = { null },
        )

        val result = rig.tool.execute("""{"uri":"imeituan://www.meituan.com/x","package":"com.sankuai.meituan"}""")

        assertEquals(ActionOutcome.Failed, result.outcome)
        val execution = requireNotNull(result.execution)
        assertEquals("UNVERIFIED", execution.code)
        assertEquals(ToolOutcome.Failed, execution.outcome)
        assertEquals(true, execution.accepted)
        assertNull("no success may be claimed without the second witness", execution.applied)
        assertNull("the tool could not verify, which is not the same as unverified", execution.verified)
        assertNull(execution.changed)
    }

    // ── launcher refusals ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a no-handler answer is NO_HANDLER and nothing is awaited`() {
        val rig = Rig(result = DeepLinkLaunchResult.NoHandler)

        val result = rig.tool.execute("""{"uri":"imeituan://www.meituan.com/x","package":"com.sankuai.meituan"}""")

        assertEquals(ActionOutcome.Refused, result.outcome)
        val execution = requireNotNull(result.execution)
        assertEquals("NO_HANDLER", execution.code)
        assertEquals(false, execution.accepted)
        assertEquals(false, execution.applied)
        assertTrue("a refused launch must not wait for a foreground that cannot appear", rig.waits.isEmpty())
        assertEquals(1, rig.launcher.calls.size)
    }

    @Test
    fun `a resolved package that differs is PACKAGE_MISMATCH with the actual package`() {
        val rig = Rig(
            result = DeepLinkLaunchResult.PackageMismatch("com.android.chrome"),
            foregroundNow = { "com.android.chrome" },
            rootNow = { "com.android.chrome" },
        )

        val result = rig.tool.execute(
            """{"uri":"https://example.com/app","package":"com.example.app"}""",
        )

        assertEquals(ActionOutcome.Refused, result.outcome)
        val execution = requireNotNull(result.execution)
        assertEquals("PACKAGE_MISMATCH", execution.code)
        assertEquals(false, execution.accepted)
        assertEquals(false, execution.applied)
        assertEquals(
            "the resolved handler must be reported, not silently retried",
            "com.android.chrome",
            body(result)["handlerPackage"]!!.jsonPrimitive.content,
        )
        assertTrue(rig.waits.isEmpty())
    }

    @Test
    fun `a security refusal is LAUNCH_REFUSED and nothing is awaited`() {
        val rig = Rig(result = DeepLinkLaunchResult.Refused)

        val result = rig.tool.execute("""{"uri":"imeituan://www.meituan.com/x","package":"com.sankuai.meituan"}""")

        assertEquals(ActionOutcome.Refused, result.outcome)
        assertEquals("LAUNCH_REFUSED", requireNotNull(result.execution).code)
        assertEquals(false, requireNotNull(result.execution).accepted)
        assertTrue(rig.waits.isEmpty())
    }

    // ── policy boundaries reached through the tool ───────────────────────────────────────────────────────────

    @Test
    fun `invalid requests are refused before the launcher is reached`() {
        val rig = Rig(result = DeepLinkLaunchResult.NoHandler)

        val missingPackage = rig.tool.execute("""{"uri":"imeituan://www.meituan.com/x"}""")
        assertEquals(ActionOutcome.Refused, missingPackage.outcome)
        assertEquals("PACKAGE_REQUIRED", requireNotNull(missingPackage.execution).code)

        val blocked = rig.tool.execute("""{"uri":"file:///sdcard/x","package":"com.example.app"}""")
        assertEquals("SCHEME_NOT_ALLOWED", requireNotNull(blocked.execution).code)

        val malformed = rig.tool.execute("""{"uri":"not a uri"}""")
        assertEquals("INVALID_URI", requireNotNull(malformed.execution).code)

        val missingUri = rig.tool.execute("""{"package":"com.example.app"}""")
        assertEquals("ARGUMENTS_INVALID", requireNotNull(missingUri.execution).code)

        assertTrue("no invalid request may reach Android", rig.launcher.calls.isEmpty())
        assertTrue(rig.waits.isEmpty())
    }

    @Test
    fun `an unknown action name is UNKNOWN_ACTION and reaches no launcher`() {
        val rig = Rig(result = DeepLinkLaunchResult.NoHandler)

        val result = rig.tool.execute("teleport", "{}")

        assertEquals(ActionOutcome.Refused, result.outcome)
        assertEquals("UNKNOWN_ACTION", requireNotNull(result.execution).code)
        assertTrue(rig.launcher.calls.isEmpty())
    }

    // ── audit ────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `the audit line carries scheme host and package but never the URI or its query`() {
        val rig = Rig(
            result = DeepLinkLaunchResult.Accepted("com.example.app"),
            foregroundNow = { "com.example.app" },
            rootNow = { "com.example.app" },
        )
        val lines = mutableListOf<String>()
        val previous = P3Log.sink
        P3Log.sink = { line -> lines += line }
        try {
            rig.tool.execute(
                """{"uri":"https://example.com/private/path?token=super-secret","package":"com.example.app"}""",
            )
        } finally {
            P3Log.sink = previous
        }

        val audit = lines.filter { it.startsWith("P3_DEEPLINK") }
        assertEquals("one audit line per valid call", 1, audit.size)
        val line = audit.single()
        assertTrue("scheme must be recorded: $line", line.contains("scheme=https"))
        assertTrue("host must be recorded: $line", line.contains("host=example.com"))
        assertTrue("the requested package must be recorded: $line", line.contains("package=com.example.app"))
        assertFalse("the path is not an audit field: $line", line.contains("/private/path"))
        assertFalse("the query key is not an audit field: $line", line.contains("token"))
        assertFalse("the full URI must never be logged: $line", line.contains("super-secret"))
    }

    @Test
    fun `a custom-scheme audit line names the scheme and never a query`() {
        val rig = Rig(
            result = DeepLinkLaunchResult.Accepted("com.sankuai.meituan"),
            foregroundNow = { "com.sankuai.meituan" },
            rootNow = { "com.sankuai.meituan" },
        )
        val lines = mutableListOf<String>()
        val previous = P3Log.sink
        P3Log.sink = { line -> lines += line }
        try {
            rig.tool.execute(
                """{"uri":"imeituan://www.meituan.com/restaurant?orderId=999","package":"com.sankuai.meituan"}""",
            )
        } finally {
            P3Log.sink = previous
        }

        val line = lines.single { it.startsWith("P3_DEEPLINK") }
        assertTrue(line, line.contains("scheme=imeituan"))
        assertTrue(line, line.contains("host=www.meituan.com"))
        assertTrue(line, line.contains("package=com.sankuai.meituan"))
        assertFalse("the query must not be logged: $line", line.contains("orderId"))
        assertFalse("the path must not be logged: $line", line.contains("/restaurant"))
    }

    // ── descriptor ───────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `the descriptor declares only uri and package at L1`() {
        val descriptor = DeepLinkTool.DESCRIPTOR

        assertEquals("deep_link", descriptor.name)
        assertEquals(RiskLevel.L1, descriptor.risk)
        assertFalse(descriptor.requiresConfirmation)
        assertNull("a URI is user data, so it must never reach the audit argument", descriptor.auditTargetArgument)

        val schema = Json.parseToJsonElement(descriptor.parametersJson).jsonObject
        assertEquals(
            "no extras/component/multi-URI arguments are offered",
            setOf("uri", "package"),
            schema["properties"]!!.jsonObject.keys,
        )
        assertEquals(
            listOf("uri"),
            schema["required"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
        assertEquals(false, schema["additionalProperties"]!!.jsonPrimitive.boolean)
        assertTrue(descriptor.description.contains("uri"))
        assertTrue(descriptor.description.contains("package"))
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────────────────

    /**
     * One tool over a scripted launcher and screen.
     *
     * [reachesForeground] is the injected 2 s poll's answer; the tool still re-reads `foregroundNow` afterwards, so a
     * test can model "the poll timed out and the screen is somewhere else" without sleeping.
     */
    private class Rig(
        result: DeepLinkLaunchResult,
        private val foregroundNow: () -> String? = { null },
        private val rootNow: () -> String? = { null },
        private val reachesForeground: Boolean = true,
    ) {
        val launcher = FakeLauncher(result)
        val waits = mutableListOf<Pair<String, Long>>()
        val tool = DeepLinkTool(
            launcher = launcher,
            foreground = foregroundNow,
            rootPackage = rootNow,
            waitForForeground = { pkg, timeout ->
                waits += pkg to timeout
                reachesForeground
            },
        )
    }

    private class FakeLauncher(private val result: DeepLinkLaunchResult) : DeepLinkLauncher {
        val calls = mutableListOf<Pair<String, String?>>()

        override fun launch(uri: String, packageName: String?): DeepLinkLaunchResult {
            calls += uri to packageName
            return result
        }
    }

    private fun body(result: ActionResult): JsonObject = Json.parseToJsonElement(result.detail).jsonObject
}
