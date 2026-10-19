package com.sheen.rokid.phone.actions.intent
import com.sheen.rokid.phone.actions.shizuku.ShellResult
import com.sheen.rokid.phone.actions.shizuku.ShellFailure
import com.sheen.rokid.phone.actions.shizuku.SHELL_NOT_BOUND
import com.sheen.rokid.phone.actions.shizuku.PrivilegedShell
import com.sheen.rokid.phone.actions.shizuku.SHELL_REPLY_INVALID
import com.sheen.rokid.phone.actions.shizuku.SHELL_REPLY_TOO_LARGE
import com.sheen.rokid.phone.storage.unavailable

import com.sheen.rokid.plugin.ActionOutcome
import com.sheen.rokid.plugin.RiskLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Shizuku-backed launch and list tools (T2.2 + T2.3).
 *
 * launch_app resolves a spoken name through [AppLaunchResolver] and, when exactly one candidate survives, asks the
 * privileged shell to start it. Ambiguity is refused with its candidates attached rather than reported as success —
 * the resolver's rule carried through to the action's outcome, so the model can ask the user to pick. list_apps
 * formats the catalog into lines the model can read, truncating at 100 entries so a device with hundreds of packages
 * does not flood the context window.
 */
class LaunchAppExecutorTest {

    private class FakeShell(
        var result: ShellResult = ShellResult("", 0, "Status: ok", ""),
        /** Optional per-command results; matched by prefix. Falls back to [result] when nothing matches. */
        private val overrides: Map<String, ShellResult> = emptyMap(),
    ) : PrivilegedShell {
        val commands = mutableListOf<String>()
        override fun run(command: String): ShellResult {
            commands += command
            val matched = overrides.entries.firstOrNull { command.startsWith(it.key) }?.value
            return (matched ?: result).copy(command = command)
        }
    }

    private class FakeCatalog(private val apps: List<AppEntry>) : AppCatalog {
        override fun visibleApps(): List<AppEntry> = apps
    }

    private class FakeResolver(
        private val catalog: AppCatalog,
    ) {
        private val delegate = AppLaunchResolver(catalog)
        fun resolve(name: String): LaunchResolution = delegate.resolve(name)
    }

    private fun entry(
        label: String,
        packageName: String = "pkg.${label.replace(' ', '.')}",
        launchIntentAction: String? = "android.intent.action.MAIN",
    ) = AppEntry(packageName, label, launchIntentAction)

    private fun backend(
        catalog: AppCatalog = FakeCatalog(emptyList()),
        shell: FakeShell = FakeShell(),
        channelUp: Boolean = true,
    ): LaunchAppExecutor {
        val resolver = AppLaunchResolver(catalog)
        return LaunchAppExecutor(resolver = resolver, catalog = catalog, shell = shell, channelUp = { channelUp })
    }

    // ── launch_app: Found + shell success → Done ───────────────────────────────

    @Test
    fun `launch_app found and shell succeeds returns Done`() {
        val shell = FakeShell(
            result = ShellResult("", 0, "Status: ok", ""),
            overrides = mapOf(
                "cmd package resolve-activity" to ShellResult("", 0,
                    "priority=0\ncom.tencent.mm/.ui.LauncherUI", ""),
            ),
        )
        val catalog = FakeCatalog(listOf(entry("WeChat", "com.tencent.mm")))
        val executor = backend(catalog = catalog, shell = shell)

        val result = executor.execute("launch_app", """{"name":"WeChat"}""")

        assertEquals(ActionOutcome.Done, result.outcome)
        assertEquals("launched WeChat", result.detail)
        // Two shell calls: resolve-activity first, then am start with the resolved component.
        assertEquals(2, shell.commands.size)
        assertTrue(shell.commands[0].startsWith("cmd package resolve-activity"))
        assertTrue(shell.commands[1].startsWith("am start -W -n com.tencent.mm/.ui.LauncherUI"))
    }

    // ── launch_app: Found + shell failure → Failed ─────────────────────────────

    @Test
    fun `launch_app found but shell fails returns Failed STATE_UNCHANGED`() {
        val shell = FakeShell(ShellResult("", 1, "", "Error"))
        val catalog = FakeCatalog(listOf(entry("WeChat", "com.tencent.mm")))
        val executor = backend(catalog = catalog, shell = shell)

        val result = executor.execute("launch_app", """{"name":"WeChat"}""")

        assertEquals(ActionOutcome.Failed, result.outcome)
        assertEquals("STATE_UNCHANGED", result.detail)
    }

    // ── launch_app: Found + shell exit 0 but no Status: ok → Failed ────────────

    @Test
    fun `launch_app shell exit zero without Status ok returns Failed`() {
        val shell = FakeShell(ShellResult("", 0, "Starting: Intent { ... }", ""))
        val catalog = FakeCatalog(listOf(entry("WeChat", "com.tencent.mm")))
        val executor = backend(catalog = catalog, shell = shell)

        val result = executor.execute("launch_app", """{"name":"WeChat"}""")

        assertEquals(ActionOutcome.Failed, result.outcome)
        assertEquals("STATE_UNCHANGED", result.detail)
    }

    // ── launch_app: the call never came back → Refused, not Failed ────────────
    //
    // The two commands this executor runs are guarded the same way, and the tests say which command each case is about:
    //  * a failure on `cmd package resolve-activity` **short-circuits** - the second call is not attempted, because a
    //    channel that just failed to carry a reply must not be asked for another one (measured recovery is on a *later*
    //    call, not the immediately next one: evidence/2026-10-17-shizuku-reply-ceiling.md §1/§9);
    //  * a failure on `am start -W` is refused identically once that call has been made.

    @Test
    fun `launch_app refuses when the resolve call never came back, without making the launch call`() {
        val kinds = mapOf(
            ShellFailure.CARRIER to ShellResult("", -1, "", SHELL_REPLY_TOO_LARGE, failure = ShellFailure.CARRIER),
            ShellFailure.NOT_BOUND to ShellResult("", -1, "", SHELL_NOT_BOUND, failure = ShellFailure.NOT_BOUND),
            ShellFailure.PARSE to ShellResult("", -1, "", SHELL_REPLY_INVALID, failure = ShellFailure.PARSE),
        )
        for ((kind, reply) in kinds) {
            val shell = FakeShell(result = reply)
            val catalog = FakeCatalog(listOf(entry("WeChat", "com.tencent.mm")))

            val result = backend(catalog = catalog, shell = shell).execute("launch_app", """{"name":"WeChat"}""")

            assertEquals("$kind must be refused, not failed", ActionOutcome.Refused, result.outcome)
            assertEquals("$kind must name the channel, not the app", "ACTION_CHANNEL_UNAVAILABLE", result.detail)
            assertEquals("no second call may be spent on a channel that just failed", 1, shell.commands.size)
            assertTrue(shell.commands[0].startsWith("cmd package resolve-activity"))
        }
    }

    @Test
    fun `launch_app refuses when the launch call never came back`() {
        val kinds = mapOf(
            ShellFailure.CARRIER to ShellResult("", -1, "", SHELL_REPLY_TOO_LARGE, failure = ShellFailure.CARRIER),
            ShellFailure.NOT_BOUND to ShellResult("", -1, "", SHELL_NOT_BOUND, failure = ShellFailure.NOT_BOUND),
            ShellFailure.PARSE to ShellResult("", -1, "", SHELL_REPLY_INVALID, failure = ShellFailure.PARSE),
        )
        for ((kind, reply) in kinds) {
            val shell = FakeShell(
                result = ShellResult("", 0, "Status: ok", ""),
                overrides = mapOf(
                    "cmd package resolve-activity" to
                        ShellResult("", 0, "priority=0\ncom.tencent.mm/.ui.LauncherUI", ""),
                    "am start" to reply,
                ),
            )
            val catalog = FakeCatalog(listOf(entry("WeChat", "com.tencent.mm")))

            val result = backend(catalog = catalog, shell = shell).execute("launch_app", """{"name":"WeChat"}""")

            assertEquals("$kind must be refused, not failed", ActionOutcome.Refused, result.outcome)
            assertEquals("$kind must name the channel, not the app", "ACTION_CHANNEL_UNAVAILABLE", result.detail)
            assertEquals("resolve-activity then am start, and nothing else", 2, shell.commands.size)
            assertTrue(shell.commands[1].startsWith("am start -W -n com.tencent.mm/.ui.LauncherUI"))
        }
    }

    // ── launch_app: Ambiguous → Refused APP_AMBIGUOUS with candidate JSON ───────

    @Test
    fun `launch_app ambiguous returns Refused APP_AMBIGUOUS with candidate list`() {
        val shell = FakeShell()
        val catalog = FakeCatalog(listOf(
            entry("Camera", "com.one"),
            entry("Camera", "com.two"),
        ))
        val executor = backend(catalog = catalog, shell = shell)

        val result = executor.execute("launch_app", """{"name":"Camera"}""")

        assertNotEquals("ambiguity must never look like success", ActionOutcome.Done, result.outcome)
        assertEquals(ActionOutcome.Refused, result.outcome)
        assertTrue(
            "detail must start with the APP_AMBIGUOUS error code",
            result.detail.startsWith("APP_AMBIGUOUS:"),
        )
        assertTrue("detail must contain packageName", result.detail.contains("com.one"))
        assertTrue("detail must contain packageName", result.detail.contains("com.two"))
        assertTrue("shell must not be touched for ambiguity", shell.commands.isEmpty())
    }

    // ── launch_app: NotFound → Refused ─────────────────────────────────────────

    @Test
    fun `launch_app not found returns Refused APP_NOT_FOUND`() {
        val shell = FakeShell()
        val catalog = FakeCatalog(listOf(entry("WeChat", "com.tencent.mm")))
        val executor = backend(catalog = catalog, shell = shell)

        val result = executor.execute("launch_app", """{"name":"地图"}""")

        assertEquals(ActionOutcome.Refused, result.outcome)
        assertEquals("APP_NOT_FOUND", result.detail)
        assertTrue(shell.commands.isEmpty())
    }

    // ── launch_app: Channel unavailable → Refused ──────────────────────────────

    @Test
    fun `launch_app channel down returns Refused ACTION_CHANNEL_UNAVAILABLE`() {
        val shell = FakeShell()
        val catalog = FakeCatalog(listOf(entry("WeChat", "com.tencent.mm")))
        val executor = backend(catalog = catalog, shell = shell, channelUp = false)

        val result = executor.execute("launch_app", """{"name":"WeChat"}""")

        assertEquals(ActionOutcome.Refused, result.outcome)
        assertEquals("ACTION_CHANNEL_UNAVAILABLE", result.detail)
        assertTrue(shell.commands.isEmpty())
    }

    // ── launch_app: Invalid args → Refused ─────────────────────────────────────

    @Test
    fun `launch_app invalid arguments returns Refused ARGUMENTS_INVALID`() {
        val shell = FakeShell()
        val executor = backend(shell = shell)

        listOf("not json", """{}""", """{"name":123}""").forEach { args ->
            val result = executor.execute("launch_app", args)
            assertEquals("refused: $args", ActionOutcome.Refused, result.outcome)
            assertEquals("refused: $args", "ARGUMENTS_INVALID", result.detail)
        }
        assertTrue(shell.commands.isEmpty())
    }

    // ── list_apps: Normal catalog → Done with formatted list ───────────────────

    @Test
    fun `list_apps returns formatted lines for launchable apps`() {
        val catalog = FakeCatalog(listOf(
            entry("WeChat", "com.tencent.mm"),
            entry("Camera", "com.android.camera"),
            entry("Stub Service", "com.stub", launchIntentAction = null),
        ))
        val executor = backend(catalog = catalog)

        val result = executor.execute("list_apps", """{}""")

        assertEquals(ActionOutcome.Done, result.outcome)
        val lines = result.detail.lines()
        assertEquals(2, lines.size)
        assertTrue(lines.any { it.contains("WeChat") && it.contains("com.tencent.mm") })
        assertTrue(lines.any { it.contains("Camera") && it.contains("com.android.camera") })
        assertTrue("non-launchable apps must be excluded", lines.none { it.contains("Stub Service") })
    }

    // ── list_apps: Empty catalog → Done with empty string ──────────────────────

    @Test
    fun `list_apps empty catalog returns Done with empty detail`() {
        val executor = backend(catalog = FakeCatalog(emptyList()))

        val result = executor.execute("list_apps", """{}""")

        assertEquals(ActionOutcome.Done, result.outcome)
        assertEquals("", result.detail)
    }

    // ── list_apps: >100 apps → truncated flag ─────────────────────────────────

    @Test
    fun `list_apps over 100 entries appends truncated flag`() {
        val apps = (1..105).map { entry("App $it", "com.app.$it") }
        val executor = backend(catalog = FakeCatalog(apps))

        val result = executor.execute("list_apps", """{}""")

        assertEquals(ActionOutcome.Done, result.outcome)
        assertTrue("must end with truncated marker", result.detail.endsWith("truncated: true"))
        val contentLines = result.detail.lines().filter { !it.startsWith("truncated:") }
        assertEquals("only 100 entries may appear", 100, contentLines.size)
    }

    // ── list_apps: Channel down still works (catalog is local) ─────────────────

    @Test
    fun `list_apps works even when channel is down`() {
        val catalog = FakeCatalog(listOf(entry("WeChat", "com.tencent.mm")))
        val executor = backend(catalog = catalog, channelUp = false)

        val result = executor.execute("list_apps", """{}""")

        assertEquals(ActionOutcome.Done, result.outcome)
        assertTrue(result.detail.contains("WeChat"))
    }

    // ── Descriptors are correct ────────────────────────────────────────────────

    @Test
    fun `descriptors declare launch_app and list_apps with correct risk and scope`() {
        val executor = backend()
        val byName = executor.descriptors().associateBy { it.name }

        assertEquals(setOf("launch_app", "list_apps"), byName.keys)

        val launch = byName.getValue("launch_app")
        assertEquals(RiskLevel.L1, launch.risk)
        assertEquals("app", launch.scope)
        assertEquals("name", launch.auditTargetArgument)
        assertEquals(false, launch.requiresConfirmation)
        assertEquals(false, launch.idempotent)

        val list = byName.getValue("list_apps")
        assertEquals(RiskLevel.L0, list.risk)
        assertEquals("app", list.scope)
        assertEquals(null, list.auditTargetArgument)
        assertEquals(true, list.idempotent)
    }

    @Test
    fun `backend id is shizuku_launch`() {
        assertEquals("shizuku_launch", backend().id)
    }

    // ── Unknown action → Refused ───────────────────────────────────────────────

    @Test
    fun `unknown action name returns Refused UNKNOWN_ACTION`() {
        val executor = backend()

        val result = executor.execute("something_else", """{}""")

        assertEquals(ActionOutcome.Refused, result.outcome)
        assertEquals("UNKNOWN_ACTION", result.detail)
    }
}
