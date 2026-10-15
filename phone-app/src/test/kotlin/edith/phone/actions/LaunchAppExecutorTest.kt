package edith.phone.actions

import edith.plugin.ActionOutcome
import edith.plugin.RiskLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Shizuku-backed launch and list tools (T2.2 + T2.3).
 *
 * launch_app resolves a spoken name through [AppLaunchResolver] and, when exactly one candidate survives, asks the
 * privileged shell to start it. Ambiguity is returned as data rather than a guess — the same rule the resolver itself
 * enforces, now carried through to the action's outcome. list_apps formats the catalog into lines the model can read,
 * truncating at 100 entries so a device with hundreds of packages does not flood the context window.
 */
class LaunchAppExecutorTest {

    private class FakeShell(var result: ShellResult = ShellResult("", 0, "Status: ok", "")) : PrivilegedShell {
        val commands = mutableListOf<String>()
        override fun run(command: String): ShellResult {
            commands += command
            return result.copy(command = command)
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
        val shell = FakeShell(ShellResult("", 0, "Status: ok", ""))
        val catalog = FakeCatalog(listOf(entry("WeChat", "com.tencent.mm")))
        val executor = backend(catalog = catalog, shell = shell)

        val result = executor.execute("launch_app", """{"name":"WeChat"}""")

        assertEquals(ActionOutcome.Done, result.outcome)
        assertEquals("launched WeChat", result.detail)
        assertEquals(1, shell.commands.size)
        assertTrue(shell.commands[0].startsWith("am start -W"))
        assertTrue(shell.commands[0].contains("com.tencent.mm"))
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

    // ── launch_app: Ambiguous → Done with candidate JSON ───────────────────────

    @Test
    fun `launch_app ambiguous returns Done with candidate list`() {
        val shell = FakeShell()
        val catalog = FakeCatalog(listOf(
            entry("Camera", "com.one"),
            entry("Camera", "com.two"),
        ))
        val executor = backend(catalog = catalog, shell = shell)

        val result = executor.execute("launch_app", """{"name":"Camera"}""")

        assertEquals(ActionOutcome.Done, result.outcome)
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
