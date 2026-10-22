package com.sheen.rokid.phone.actions

import com.sheen.rokid.device.ActKind
import com.sheen.rokid.device.DeviceEnvelope
import com.sheen.rokid.device.Observation
import com.sheen.rokid.device.ObservationRenderer
import com.sheen.rokid.device.Ref
import com.sheen.rokid.device.RefRegistry
import com.sheen.rokid.device.ScreenFilter as DeviceScreenFilter
import com.sheen.rokid.phone.actions.a11y.AccessibilityExecutionBackend
import com.sheen.rokid.phone.actions.a11y.AccessibilityUi
import com.sheen.rokid.phone.actions.a11y.ScreenElement
import com.sheen.rokid.phone.actions.a11y.ScreenFingerprint
import com.sheen.rokid.phone.actions.a11y.ScreenFilter
import com.sheen.rokid.phone.actions.a11y.ScreenSnapshot
import com.sheen.rokid.phone.actions.a11y.ScrollDirection
import com.sheen.rokid.phone.actions.device.DeviceOperations
import com.sheen.rokid.phone.actions.device.DeviceTool
import com.sheen.rokid.phone.actions.device.DialTool
import com.sheen.rokid.phone.actions.device.OpenTool
import com.sheen.rokid.phone.actions.device.ScreenProjector
import com.sheen.rokid.phone.actions.device.ShellTool
import com.sheen.rokid.phone.actions.device.ToolCatalog
import com.sheen.rokid.phone.actions.intent.AppCatalog
import com.sheen.rokid.phone.actions.intent.AppEntry
import com.sheen.rokid.phone.actions.intent.AppLaunchResolver
import com.sheen.rokid.phone.actions.intent.CallPermission
import com.sheen.rokid.phone.actions.intent.IntentActionExecutor
import com.sheen.rokid.phone.actions.intent.IntentAtomicExecutor
import com.sheen.rokid.phone.actions.intent.IntentLauncher
import com.sheen.rokid.phone.actions.intent.LaunchAppExecutor
import com.sheen.rokid.phone.actions.intent.SystemActionExecutor
import com.sheen.rokid.phone.actions.shizuku.PrivilegedShell
import com.sheen.rokid.phone.actions.shizuku.ShellResult
import com.sheen.rokid.phone.actions.shizuku.ShizukuExecutionBackend
import com.sheen.rokid.phone.actions.shizuku.TargetState
import com.sheen.rokid.plugin.ActionDescriptor
import com.sheen.rokid.plugin.ActionExecutor
import com.sheen.rokid.plugin.ActionOutcome
import com.sheen.rokid.plugin.ActionResult
import com.sheen.rokid.plugin.RiskLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * edith-0.3 Phase 4A Task 4 (A4): the production-shaped host face — 18 atomic tools plus the umbrella catalog's four —
 * is **one** `ActionExecutor` with stable advertising.
 *
 * What is pinned here, and why each half matters:
 * - the 22 names, in the fixed order the composition builds (`a11y`, intent without `dial`, Shizuku, launch, system,
 *   catalog), because a tool table that changes shape between reads would also change the cached prompt prefix;
 * - `dial` has exactly one owner and it is the catalog's L1 `DialTool`; the legacy L2 row inside `IntentActionExecutor`
 *   stays reachable only through that tool, never as its own advertised action (the surface refuses a duplicate name at
 *   construction, so the filter is load-bearing rather than cosmetic);
 * - a channel that goes down keeps its tools advertised (A3's stable-advertising rule) and `execute` answers the
 *   channel refusal instead of hiding the tool or running anything.
 *
 * The fakes here answer only what the exercised path needs and **throw** when asked anything else, so "the channel was
 * down" is proven by the absence of a command/UI read rather than by an assertion on the fake's bookkeeping.
 */
class ToolFaceWiringTest {

    // ── the expected host face ───────────────────────────────────────────────────────────────────────────────

    /** The 17 atomic descriptors, grouped the way the five backends declare them. */
    private val atomicNames = listOf(
        // accessibility (atomic perception/operation)
        "read_screen", "tap", "long_press", "scroll", "input_text",
        // intent (atomic open/alarm/note) — `dial` is deliberately absent: the catalog owns it
        "open_app", "open_url", "open_settings", "set_alarm", "compose_note",
        // Shizuku (atomic privileged/settings)
        "clear_data", "uninstall", "disable_app", "set_permission", "set_brightness",
        // local app directory (`launch_app` retired 2026-10-22, 24 → 23)
        "list_apps",
        // Shizuku system keys
        "system_action",
    )

    /** The umbrella catalog's four, in its own descriptor order. */
    private val catalogNames = listOf("device", "shell", "open", "dial")

    private val hostNames = atomicNames + catalogNames

    // ── production-shaped host face ──────────────────────────────────────────────────────────────────────────

    /** The pieces the test toggles or inspects, built exactly as `HostCompositionRoot.create` builds them. */
    private class HostFace(
        val surface: ToolSurface,
        val intent: IntentActionExecutor,
        val atomicIntent: IntentAtomicExecutor,
        val launcher: RecordingLauncher,
        val shell: RecordingShell,
    )

    private fun hostFace(
        a11y: () -> Boolean = { true },
        shizuku: () -> Boolean = { true },
        call: () -> Boolean = { true },
        ui: AccessibilityUi = NeverAskedUi,
    ): HostFace {
        val launcher = RecordingLauncher()
        val shell = RecordingShell()
        val appCatalog = EmptyAppCatalog
        // The one intent executor: its `dial` row is what the catalog's DialTool delegates to (single implementation),
        // which is exactly why the atomic wrapper filters only the *advertisement*, not the body.
        val intent = IntentActionExecutor(
            resolver = AppLaunchResolver(appCatalog),
            launcher = launcher,
            noteStorage = null,
            callPermission = CallPermission { call() },
        )
        val catalog = ToolCatalog(
            device = DeviceTool(
                operations = NeverAskedOperations,
                projector = ScreenProjector(ObservationRenderer(RefRegistry()), RefRegistry()),
            ),
            shell = ShellTool(shell),
            open = OpenTool(shell, appCatalog),
            dial = DialTool(intent, call),
            a11yAvailable = a11y,
            shellAvailable = shizuku,
            callAvailable = call,
        )
        val atomicIntent = IntentAtomicExecutor(intent)
        // The production wiring passes the turn seam only to the leg that owns turn-scoped state (the a11y snapshot).
        val accessibilityBackend = AccessibilityExecutionBackend(ui, a11y)
        val atomic = listOf(
            AtomicActionExecutor(accessibilityBackend, onTurnBoundary = accessibilityBackend::beginTurn),
            atomicIntent,
            AtomicActionExecutor(ShizukuExecutionBackend(shell, NeverAskedState, shizuku)),
            AtomicActionExecutor(LaunchAppExecutor(appCatalog)),
            AtomicActionExecutor(SystemActionExecutor(shell, shizuku)),
        )
        return HostFace(ToolSurface(catalog, atomic), intent, atomicIntent, launcher, shell)
    }

    // ── the 22-name face ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `the host face advertises the seventeen atomic tools then the catalog's four`() {
        val names = hostFace().surface.descriptors().map { it.name }

        assertEquals(atomicNames.size, names.count { it in atomicNames })
        assertEquals(17, atomicNames.size)
        assertEquals(hostNames, names)
        assertEquals("21 distinct names, no duplicate", 21, names.toSet().size)
    }

    @Test
    fun `every read advertises the same 21 names`() {
        val surface = hostFace().surface

        val first = surface.descriptors().map { it.name }
        val second = surface.descriptors().map { it.name }
        // The refusal's own result is deliberately discarded: only the schema read after it matters here.
        surface.execute("teleport", "{}")
        val afterExecute = surface.descriptors().map { it.name }

        assertEquals(hostNames, first)
        assertEquals(first, second)
        assertEquals(first, afterExecute)
    }

    @Test
    fun `dial has exactly one owner and it is the catalog's L1 tool`() {
        val surface = hostFace().surface

        val dials = surface.descriptors().filter { it.name == "dial" }
        assertEquals("one advertised dial row", 1, dials.size)
        assertEquals(
            "the catalog's DialTool is the owner, not IntentActionExecutor's legacy L2 row",
            RiskLevel.L1,
            dials.single().risk,
        )
    }

    @Test
    fun `the atomic intent leg advertises five tools and refuses dial instead of delegating it`() {
        val face = hostFace()

        assertEquals(
            listOf("open_app", "open_url", "open_settings", "set_alarm", "compose_note"),
            face.atomicIntent.descriptors().map { it.name },
        )
        // The inner executor keeps its six rows: DialTool still delegates the *call* to this one implementation.
        assertTrue(face.intent.descriptors().any { it.name == "dial" })

        val result = face.atomicIntent.execute("dial", """{"number":"10086"}""")

        assertEquals(ActionOutcome.Refused, result.outcome)
        assertEquals("UNKNOWN_ACTION", result.detail)
        assertTrue("the filtered name must not reach the intent executor", face.launcher.calls.isEmpty())
    }

    // ── stable advertising across channel states ─────────────────────────────────────────────────────────────

    @Test
    fun `a down accessibility channel keeps all 22 advertised and refuses without asking the UI`() {
        var up = false
        val face = hostFace(a11y = { up })

        assertEquals("stable advertising (A3)", hostNames, face.surface.descriptors().map { it.name })

        // The atomic leg answers its own channel-unavailable code (the legacy ExecutionBackend vocabulary); the
        // umbrella catalog keeps its `CHANNEL_UNAVAILABLE` code. Both are stable refusals, neither hides the tool.
        assertEquals("ACTION_CHANNEL_UNAVAILABLE", face.surface.execute("read_screen", "{}").detail)
        assertEquals("ACTION_CHANNEL_UNAVAILABLE", face.surface.execute("tap", """{"index":0}""").detail)
        assertEquals("CHANNEL_UNAVAILABLE", face.surface.execute("device", """{"action":"observe"}""").execution?.code)

        up = true
        assertEquals("a recovered channel is answerable without a new composition", hostNames,
            face.surface.descriptors().map { it.name })
        // Routed to the a11y backend, which refuses the malformed call itself.
        assertEquals("ARGUMENTS_INVALID", face.surface.execute("tap", "{}").detail)
    }

    @Test
    fun `a down Shizuku channel keeps all 21 advertised and runs no command`() {
        var up = false
        val face = hostFace(shizuku = { up })

        assertEquals("stable advertising (A3)", hostNames, face.surface.descriptors().map { it.name })

        listOf(
            "clear_data" to """{"package":"com.example.app"}""",
            "system_action" to """{"action":"back"}""",
        ).forEach { (name, args) ->
            val result = face.surface.execute(name, args)
            assertEquals("$name must refuse on its channel", ActionOutcome.Refused, result.outcome)
            assertEquals("$name keeps the atomic channel code", "ACTION_CHANNEL_UNAVAILABLE", result.detail)
        }
        assertEquals("CHANNEL_UNAVAILABLE", face.surface.execute("shell", """{"command":"id"}""").execution?.code)
        assertTrue("no command may run while the channel is down", face.shell.commands.isEmpty())

        up = true
        // `set_brightness` is validated before the shell/state read, so reaching ARGUMENTS_INVALID proves the route.
        assertEquals("ARGUMENTS_INVALID",
            face.surface.execute("set_brightness", """{"value":999}""").detail)
        assertEquals("UNKNOWN_ACTION",
            face.surface.execute("system_action", """{"action":"nope"}""").detail)
        // `list_apps` is a local PackageManager read: no channel, an empty catalog is a valid Done.
        assertEquals(ActionOutcome.Done, face.surface.execute("list_apps", "{}").outcome)
        assertTrue("none of those refusals had a side effect", face.shell.commands.isEmpty())
    }

    @Test
    fun `an unknown name is the surface's own refusal and reaches no executor`() {
        val face = hostFace()

        val result = face.surface.execute("teleport", "{}")

        assertEquals(ActionOutcome.Refused, result.outcome)
        assertEquals(ToolSurface.UNKNOWN_ACTION, result.execution?.code)
        assertTrue("no side effect", face.launcher.calls.isEmpty())
        assertTrue("no command", face.shell.commands.isEmpty())
    }

    @Test
    fun `the catalog's four still answer while the atomic legs are advertised beside them`() {
        val face = hostFace()

        // `shell` with a malformed command: the catalog routes to ShellTool, which refuses the arguments itself.
        assertEquals("INVALID_ARGUMENTS", face.surface.execute("shell", """{"command":""}""").execution?.code)
        // `open list` is a local catalog read and needs no channel.
        assertEquals(ActionOutcome.Done, face.surface.execute("open", """{"list":true}""").outcome)
        // `dial` without CALL_PHONE is the catalog's gate, before the intent executor.
        val refused = hostFace(call = { false }).surface.execute("dial", """{"number":"10086"}""")
        assertEquals("CALL_PERMISSION_MISSING", refused.execution?.code)
    }

    @Test
    fun `precheck still classifies only the catalog's shell`() {
        val surface = hostFace().surface

        assertEquals(
            com.sheen.rokid.plugin.PrecheckDecision.RequireConfirmation,
            surface.precheck("shell", """{"command":"pm clear com.x"}""").decision,
        )
        assertEquals(
            com.sheen.rokid.plugin.PrecheckDecision.Allow,
            surface.precheck("clear_data", """{"package":"com.example.app"}""").decision,
        )
    }

    // ── turn boundary ────────────────────────────────────────────────────────────────────────────────────────

    /**
     * edith-0.3 Phase 4A close fix: A5's index tools say "index 必须来自本回合上一次 read_screen".
     *
     * The plugin calls `services.actions().beginTurn()` at the start of every turn; that reaches the production
     * [ToolSurface], which must therefore clear the atomic accessibility backend's snapshot. Without the turn
     * boundary being forwarded through the adapter, turn 2 could reuse turn 1's snapshot and act on a screen the
     * model never read this turn - which this test reproduces as a successful `tap`/`long_press`/`input_text`
     * instead of the promised `ARGUMENTS_INVALID`.
     */
    @Test
    fun `a new turn invalidates the previous turns read screen snapshot`() {
        val ui = SnapshotUi()
        val surface = hostFace(ui = ui).surface

        // Turn 1: the model reads the screen once, establishing the index snapshot.
        assertEquals(ActionOutcome.Done, surface.execute("read_screen", "{}").outcome)
        assertEquals(1, ui.snapshotReads.size)

        // The production turn boundary, exactly the call the plugin makes at the start of every assistant turn.
        surface.beginTurn()

        // Turn 2: no `read_screen` yet, so every index action must refuse rather than reuse turn 1's snapshot.
        listOf(
            "tap" to """{"index":0}""",
            "long_press" to """{"index":0}""",
            "input_text" to """{"index":0,"text":"hello"}""",
        ).forEach { (name, args) ->
            val result = surface.execute(name, args)
            assertEquals("$name must refuse an index from the previous turn", ActionOutcome.Refused, result.outcome)
            assertEquals("$name must not reuse the previous turn's snapshot", "ARGUMENTS_INVALID", result.detail)
        }
        assertTrue("no index action may reach the UI without a read_screen this turn", ui.mutations.isEmpty())

        // The boundary ends one turn's snapshot, it does not disable the tools: a fresh read in this turn re-arms them.
        assertEquals(ActionOutcome.Done, surface.execute("read_screen", "{}").outcome)
        assertEquals(ActionOutcome.Done, surface.execute("tap", """{"index":0}""").outcome)
        assertEquals(listOf("tap snap-turn-1:0"), ui.mutations)
    }

    // ── adapter contract ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `the surface's close reaches a closeable atomic backend through the adapter`() {
        val closeable = CloseableBackend()
        // The second adapter wraps a backend that owns nothing: its close is in the same broadcast and must be a no-op
        // (the unsafe `as AutoCloseable` would throw here, and the test would fail before the assertion below).
        val surface = ToolSurface(
            catalog = EmptyCatalog,
            atomic = listOf(AtomicActionExecutor(closeable), AtomicActionExecutor(StatelessBackend)),
        )

        surface.close()

        assertTrue(
            "ToolSurface.close only closes an atomic that is AutoCloseable, so the adapter must forward it",
            closeable.closed,
        )
    }

    // ── fakes ────────────────────────────────────────────────────────────────────────────────────────────────

    private class RecordingLauncher : IntentLauncher {
        data class Call(val action: String, val data: String?, val extras: Map<String, Any>)

        val calls = mutableListOf<Call>()

        override fun launch(action: String, data: String?, extras: Map<String, Any>): Boolean {
            calls += Call(action, data, extras)
            return true
        }
    }

    private class RecordingShell : PrivilegedShell {
        val commands = mutableListOf<String>()

        override fun run(command: String): ShellResult {
            commands += command
            return ShellResult(command, 0, "", "")
        }
    }

    private object EmptyAppCatalog : AppCatalog {
        override fun visibleApps(): List<AppEntry> = emptyList()
    }

    /** Every UI call is unexpected: the only a11y-down paths that reach the backend must stop at its `connected` gate. */
    private object NeverAskedUi : AccessibilityUi {
        private fun fail(): Nothing =
            throw AssertionError("the accessibility UI must not be asked while the channel is down")

        override fun clickText(text: String): Boolean = fail()
        override fun setText(text: String): Boolean = fail()
        override fun screenFingerprint(): String = fail()
        override fun snapshot(filter: ScreenFilter): ScreenSnapshot = fail()
        override fun clickByIndex(snapshotId: String, index: Int): Boolean = fail()
        override fun longPressByIndex(snapshotId: String, index: Int): Boolean = fail()
        override fun scroll(direction: ScrollDirection): Boolean = fail()
        override fun setTextByIndex(snapshotId: String, index: Int, text: String): Boolean = fail()
        override fun structuralFingerprint(): ScreenFingerprint = fail()
    }

    /** The same rule for the privileged target state: a down channel must never read it. */
    private object NeverAskedState : TargetState {
        private fun fail(): Nothing =
            throw AssertionError("the privileged target state must not be read while the channel is down")

        override fun installed(pkg: String): Boolean = fail()
        override fun permissionGranted(pkg: String, permission: String): Boolean = fail()
        override fun disabled(pkg: String): Boolean = fail()
        override fun setting(name: String): String? = fail()
    }

    /**
     * Serves one fixed snapshot and records every mutation: a mutation landing here is the proof that the snapshot
     * was reused, because the backend only reaches the UI after finding a `currentSnapshot` to act on.
     */
    private class SnapshotUi : AccessibilityUi {
        val snapshotReads = mutableListOf<ScreenFilter>()
        val mutations = mutableListOf<String>()
        private var fingerprintCalls = 0
        private val snapshot = ScreenSnapshot(
            snapshotId = "snap-turn-1",
            elements = listOf(
                ScreenElement(
                    index = 0,
                    text = "WLAN",
                    isHintText = false,
                    clickable = true,
                    editable = true,
                    scrollable = false,
                    checked = false,
                    boundsDigest = "digest-0",
                ),
            ),
            truncated = false,
            fingerprint = ScreenFingerprint(hash = "fp", nodeCount = 1, clickableCount = 1, truncated = false),
        )

        override fun clickText(text: String): Boolean = error("the turn-boundary test never asks the UI by text")
        override fun setText(text: String): Boolean = error("the turn-boundary test never asks the UI by text")
        override fun snapshot(filter: ScreenFilter): ScreenSnapshot {
            snapshotReads += filter
            return snapshot
        }

        override fun screenFingerprint(): String {
            fingerprintCalls++
            return if (fingerprintCalls == 1) "screen-a" else "screen-b"
        }

        override fun clickByIndex(snapshotId: String, index: Int): Boolean {
            mutations += "tap $snapshotId:$index"
            return true
        }

        override fun longPressByIndex(snapshotId: String, index: Int): Boolean {
            mutations += "long_press $snapshotId:$index"
            return true
        }

        override fun scroll(direction: ScrollDirection): Boolean =
            error("the turn-boundary test never scrolls")

        override fun setTextByIndex(snapshotId: String, index: Int, text: String): Boolean {
            mutations += "input_text $snapshotId:$index"
            return true
        }

        override fun structuralFingerprint(): ScreenFingerprint = error("not used by the turn-boundary test")
    }

    /** A legacy backend that owns a resource: only a `Closeable` backend has anything to forward the close to. */
    private class CloseableBackend : ExecutionBackend, AutoCloseable {
        var closed = false

        override val id = "closeable"
        override fun available() = true
        override fun descriptors(): List<ActionDescriptor> = emptyList()
        override fun execute(name: String, argsJson: String): ActionResult = error("this fake is never executed")
        override fun close() {
            closed = true
        }
    }

    /** A legacy backend that owns nothing: closing the adapter over it must be a no-op, never a fabricated close. */
    private object StatelessBackend : ExecutionBackend {
        override val id = "stateless"
        override fun available() = true
        override fun descriptors(): List<ActionDescriptor> = emptyList()
        override fun execute(name: String, argsJson: String): ActionResult = error("this fake is never executed")
    }

    /** The close path needs a catalog with no names, so the surface's uniqueness check has nothing to reject. */
    private object EmptyCatalog : ActionExecutor {
        override fun descriptors(): List<ActionDescriptor> = emptyList()
        override fun execute(name: String, argsJson: String): ActionResult = error("this fake is never executed")
        override fun cancel() = Unit
    }

    /** The device kernel seam: the exercised host-face paths never call it, so any call is a wiring bug. */
    private object NeverAskedOperations : DeviceOperations {
        private fun fail(): Nothing =
            throw AssertionError("the device kernel must not be reached from the host-face wiring test")

        override val latestObservation: Observation? get() = fail()

        override fun observe(filter: DeviceScreenFilter): DeviceEnvelope = fail()
        override fun act(
            kind: ActKind,
            ref: Ref?,
            text: String?,
            value: String?,
            direction: String?,
            key: String?,
            timeoutMillis: Long,
        ): DeviceEnvelope = fail()

        override fun screenshot(): DeviceEnvelope = fail()
        override fun waitFor(text: String?, foreground: String?, idle: Boolean, timeoutMillis: Long): DeviceEnvelope =
            fail()

        override fun status(): DeviceEnvelope = fail()
        override fun cancel() = Unit
        override fun beginTurn() = Unit
        override fun close() = Unit
    }
}
