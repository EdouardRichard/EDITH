package com.sheen.rokid.phone.actions.a11y
import com.sheen.rokid.phone.storage.unavailable
import com.sheen.rokid.phone.actions.ExecutionBackend
import com.sheen.rokid.phone.actions.ExecutionRouter
import com.sheen.rokid.phone.actions.device.DeviceTool
import com.sheen.rokid.plugin.ActionDescriptor
import com.sheen.rokid.plugin.ActionOutcome
import com.sheen.rokid.plugin.ActionResult
import com.sheen.rokid.plugin.RiskLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T1.5 — the accessibility backend's five tools, and the **retired** router's availability gate.
 *
 * Verifies that the five tools (read_screen, tap, long_press, scroll, input_text) are correctly registered in the
 * accessibility backend and that old tools (tap_text, type_text) are gone. The two router tests below pin
 * `ExecutionRouter`'s historical rule (an unavailable channel's tools are hidden) for the class that still exists for
 * those tests; since edith-0.3 Phase 4A Task 4 production no longer uses that router. The production face is
 * `com.sheen.rokid.phone.actions.ToolSurface`, which deliberately keeps a down channel's tools advertised and refuses
 * the call instead (A3; see `ToolFaceWiringTest`/`ToolFaceCompositionTest`).
 */
class ToolSurfaceWiringTest {

    // ── Fakes ────────────────────────────────────────────────────────────────

    private class FakeUi(
        private val nodes: List<RawSnapshotNode> = emptyList(),
        private val clickResult: Boolean = true,
        private val longPressResult: Boolean = true,
        private val scrollResult: Boolean = true,
        private val setTextResult: Boolean = true,
        private val fingerprintBefore: String = "screen-a",
        private val fingerprintAfter: String = "screen-b",
    ) : AccessibilityUi {
        val clickByIndexCalls = mutableListOf<Pair<String, Int>>()
        val longPressByIndexCalls = mutableListOf<Pair<String, Int>>()
        val scrollCalls = mutableListOf<ScrollDirection>()
        val setTextByIndexCalls = mutableListOf<Triple<String, Int, String>>()
        var snapshotFilterUsed: ScreenFilter? = null
        private var currentSnapshot: ScreenSnapshot? = null
        private var fingerprints = 0

        override fun clickText(text: String): Boolean = true
        override fun setText(text: String): Boolean = true
        override fun screenFingerprint(): String {
            fingerprints++
            return if (fingerprints == 1) fingerprintBefore else fingerprintAfter
        }

        override fun snapshot(filter: ScreenFilter): ScreenSnapshot {
            snapshotFilterUsed = filter
            val source = object : SnapshotNodeSource {
                override fun walkNodes(filter: ScreenFilter): List<RawSnapshotNode> {
                    return when (filter) {
                        ScreenFilter.ALL -> nodes
                        ScreenFilter.INTERACTIVE -> nodes.filter { it.clickable || it.editable || it.scrollable }
                        ScreenFilter.TEXT -> nodes.filter { !it.text.isNullOrBlank() }
                    }
                }
                override fun maxNodeLimit(): Int = MAX_NODES
            }
            val snap = SnapshotBuilder.build(source, filter)
            currentSnapshot = snap
            return snap
        }

        override fun clickByIndex(snapshotId: String, index: Int): Boolean {
            val snap = currentSnapshot ?: return false
            if (SnapshotValidator.validate(snapshotId, snap) != null) return false
            if (SnapshotValidator.validateIndex(snap, index) != null) return false
            clickByIndexCalls += snapshotId to index
            currentSnapshot = null
            return clickResult
        }

        override fun longPressByIndex(snapshotId: String, index: Int): Boolean {
            val snap = currentSnapshot ?: return false
            if (SnapshotValidator.validate(snapshotId, snap) != null) return false
            if (SnapshotValidator.validateIndex(snap, index) != null) return false
            longPressByIndexCalls += snapshotId to index
            currentSnapshot = null
            return longPressResult
        }

        override fun scroll(direction: ScrollDirection): Boolean {
            scrollCalls += direction
            currentSnapshot = null
            return scrollResult
        }

        override fun setTextByIndex(snapshotId: String, index: Int, text: String): Boolean {
            val snap = currentSnapshot ?: return false
            if (SnapshotValidator.validate(snapshotId, snap) != null) return false
            if (SnapshotValidator.validateIndex(snap, index) != null) return false
            val element = snap.elements[index]
            if (!element.editable) return false
            setTextByIndexCalls += Triple(snapshotId, index, text)
            currentSnapshot = null
            return setTextResult
        }

        override fun structuralFingerprint(): ScreenFingerprint =
            ScreenFingerprint(hash = "fake", nodeCount = nodes.size, clickableCount = nodes.count { it.clickable }, truncated = false)
    }

    private fun rawNode(
        text: String? = null,
        clickable: Boolean = false,
        editable: Boolean = false,
        scrollable: Boolean = false,
        checked: Boolean = false,
    ) = RawSnapshotNode(
        text = text, contentDescription = null, className = null,
        clickable = clickable, editable = editable, scrollable = scrollable, checked = checked,
        left = 0, top = 0, right = 100, bottom = 50, hintText = null,
    )

    private fun backend(ui: AccessibilityUi, connected: Boolean = true) =
        AccessibilityExecutionBackend(ui = ui, connected = { connected })

    // ── Test 1: Model-visible tool set is exactly the 5 new tools ────────────

    @Test
    fun `accessibility backend exposes exactly the five new tools`() {
        val ui = FakeUi()
        val descriptors = backend(ui).descriptors()
        val names = descriptors.map { it.name }.toSet()

        assertEquals(
            setOf("read_screen", "tap", "long_press", "scroll", "input_text"),
            names,
        )
    }

    // ── Test 2: Old tools are NOT in the descriptor list ─────────────────────

    @Test
    fun `old tap_text and type_text are not in the descriptor list`() {
        val ui = FakeUi()
        val names = backend(ui).descriptors().map { it.name }.toSet()

        assertFalse("tap_text must be removed", names.contains("tap_text"))
        assertFalse("type_text must be removed", names.contains("type_text"))
    }

    // ── Test 3: Shizuku unavailable ⇒ read_screen still available (router rule) ──

    @Test
    fun `read_screen is available when accessibility is up regardless of shizuku`() {
        val ui = FakeUi(nodes = listOf(rawNode(text = "OK", clickable = true)))
        val a11yBackend = backend(ui, connected = true)
        val shizukuDown = object : ExecutionBackend {
            override val id = "shizuku"
            override fun available() = false
            override fun descriptors() = listOf(
                ActionDescriptor(
                    name = "clear_data", title = "Clear", parametersJson = """{"type":"object","properties":{},"additionalProperties":false}""",
                    risk = RiskLevel.L3, requiresConfirmation = false, idempotent = false, reversible = false, scope = "app",
                ),
            )
            override fun execute(name: String, argsJson: String) = ActionResult(ActionOutcome.Refused, "ACTION_CHANNEL_UNAVAILABLE")
        }
        val router = ExecutionRouter(listOf(a11yBackend, shizukuDown))

        val names = router.descriptors().map { it.name }.toSet()

        assertTrue("read_screen must be advertised", names.contains("read_screen"))
        assertFalse("clear_data must NOT be advertised (shizuku down)", names.contains("clear_data"))
    }

    // ── Test 4: Accessibility unavailable ⇒ the retired router advertises none ──

    @Test
    fun `the retired router hides a down channels tools, unlike the stable production surface`() {
        // This is ExecutionRouter's rule (the class kept for its own tests). The production `ToolSurface` deliberately
        // does the opposite: A3 keeps the tools advertised so the schema/cached prefix cannot change mid-session, and
        // `execute` answers `ACTION_CHANNEL_UNAVAILABLE`. `ToolFaceWiringTest` pins that production behaviour.
        val ui = FakeUi()
        val a11yBackend = backend(ui, connected = false)
        val router = ExecutionRouter(listOf(a11yBackend))

        val names = router.descriptors().map { it.name }.toSet()

        assertTrue("the retired router advertises nothing while the channel is down", names.isEmpty())
    }

    // ── Test 5: read_screen with default filter ──────────────────────────────

    @Test
    fun `read_screen with no filter arg defaults to interactive`() {
        val nodes = listOf(
            rawNode(text = "Title", clickable = false),
            rawNode(text = "Button", clickable = true),
        )
        val ui = FakeUi(nodes = nodes)
        val b = backend(ui)

        // Execute read_screen with empty JSON object (no filter specified)
        val result = b.execute("read_screen", "{}")

        assertEquals(ActionOutcome.Done, result.outcome)
        assertEquals(ScreenFilter.INTERACTIVE, ui.snapshotFilterUsed)
    }

    @Test
    fun `read_screen with explicit all filter uses ALL`() {
        val nodes = listOf(rawNode(text = "A", clickable = true))
        val ui = FakeUi(nodes = nodes)
        val b = backend(ui)

        val result = b.execute("read_screen", """{"filter":"all"}""")

        assertEquals(ActionOutcome.Done, result.outcome)
        assertEquals(ScreenFilter.ALL, ui.snapshotFilterUsed)
    }

    // ── Test 6: tap with valid index returns Done when screen changes ────────

    @Test
    fun `tap with valid index returns Done when screen changes`() {
        val nodes = listOf(rawNode(text = "OK", clickable = true))
        val ui = FakeUi(nodes = nodes, fingerprintBefore = "a", fingerprintAfter = "b")
        val b = backend(ui)

        // First read_screen to get a snapshot
        b.execute("read_screen", "{}")
        val snapshotId = ui.snapshotFilterUsed?.let { /* snapshot was taken */ }

        // Now tap at index 0
        val result = b.execute("tap", """{"index":0}""")

        assertEquals(ActionOutcome.Done, result.outcome)
        assertEquals(1, ui.clickByIndexCalls.size)
        assertEquals(0, ui.clickByIndexCalls[0].second)
    }

    // ── Test 7: input_text requires both index and text ──────────────────────

    @Test
    fun `input_text missing index is refused`() {
        val ui = FakeUi()
        val b = backend(ui)

        val result = b.execute("input_text", """{"text":"hello"}""")

        assertEquals(ActionOutcome.Refused, result.outcome)
        assertEquals("ARGUMENTS_INVALID", result.detail)
    }

    @Test
    fun `input_text missing text is refused`() {
        val ui = FakeUi()
        val b = backend(ui)

        val result = b.execute("input_text", """{"index":0}""")

        assertEquals(ActionOutcome.Refused, result.outcome)
        assertEquals("ARGUMENTS_INVALID", result.detail)
    }

    @Test
    fun `input_text with both index and text succeeds when screen changes`() {
        val nodes = listOf(rawNode(text = "", editable = true))
        val ui = FakeUi(nodes = nodes, fingerprintBefore = "before", fingerprintAfter = "after")
        val b = backend(ui)

        // First take a snapshot
        b.execute("read_screen", "{}")

        val result = b.execute("input_text", """{"index":0,"text":"hello"}""")

        assertEquals(ActionOutcome.Done, result.outcome)
        assertEquals(1, ui.setTextByIndexCalls.size)
        assertEquals("hello", ui.setTextByIndexCalls[0].third)
    }

    // ── Additional behavioral tests preserving old acceptance criteria ───────

    @Test
    fun `unknown action name is refused`() {
        val ui = FakeUi()
        val result = backend(ui).execute("nonexistent_action", "{}")

        assertEquals(ActionOutcome.Refused, result.outcome)
        assertEquals("UNKNOWN_ACTION", result.detail)
    }

    @Test
    fun `unavailable service refuses all actions`() {
        val ui = FakeUi()
        val b = backend(ui, connected = false)

        listOf("read_screen", "tap", "long_press", "scroll", "input_text").forEach { name ->
            val result = b.execute(name, "{}")
            assertEquals("$name must refuse when unavailable", ActionOutcome.Refused, result.outcome)
            assertEquals("$name must report channel unavailable", "ACTION_CHANNEL_UNAVAILABLE", result.detail)
        }
    }

    @Test
    fun `all five descriptors have correct metadata`() {
        val ui = FakeUi()
        val byName = backend(ui).descriptors().associateBy { it.name }

        // All must have scope app.ui and null auditTargetArgument
        byName.values.forEach { d ->
            assertEquals("${d.name} scope", "app.ui", d.scope)
            assertNull("${d.name} audit target", d.auditTargetArgument)
            assertFalse("${d.name} requiresConfirmation", d.requiresConfirmation)
            assertFalse("${d.name} reversible", d.reversible)
        }

        // Risk levels (A2: the atomic a11y writes are L1, same as the umbrella `device` tool)
        assertEquals(RiskLevel.L1, byName["read_screen"]?.risk)
        assertEquals(RiskLevel.L1, byName["tap"]?.risk)
        assertEquals(RiskLevel.L1, byName["long_press"]?.risk)
        assertEquals(RiskLevel.L1, byName["scroll"]?.risk)
        assertEquals(RiskLevel.L1, byName["input_text"]?.risk)

        // Idempotent: only scroll could be true, but spec says false for all except scroll
        assertFalse(byName["read_screen"]!!.idempotent)
        assertFalse(byName["tap"]!!.idempotent)
        assertFalse(byName["long_press"]!!.idempotent)
        assertFalse(byName["input_text"]!!.idempotent)
    }

    @Test
    fun `tap long_press and input_text carry the same risk as the device tool`() {
        val byName = backend(FakeUi()).descriptors().associateBy { it.name }
        val deviceRisk = DeviceTool.DESCRIPTOR.risk

        listOf("tap", "long_press", "input_text").forEach { name ->
            assertEquals(
                "$name must match the umbrella device tool's risk",
                deviceRisk,
                byName.getValue(name).risk,
            )
        }
    }

    @Test
    fun `read_screen output format includes numbered elements`() {
        val nodes = listOf(
            rawNode(text = "Settings", clickable = true),
            rawNode(text = "Search", editable = true),
            rawNode(text = "Wi-Fi", clickable = true, checked = true),
        )
        val ui = FakeUi(nodes = nodes)
        val b = backend(ui)

        val result = b.execute("read_screen", """{"filter":"all"}""")

        assertEquals(ActionOutcome.Done, result.outcome)
        val detail = result.detail
        assertTrue("must contain [0]", detail.contains("[0]"))
        assertTrue("must contain Settings", detail.contains("Settings"))
        assertTrue("must contain clickable", detail.contains("clickable"))
        assertTrue("must contain truncated line", detail.contains("truncated:"))
    }

    @Test
    fun `tap with out-of-bounds index is refused`() {
        val nodes = listOf(rawNode(text = "Only", clickable = true))
        val ui = FakeUi(nodes = nodes)
        val b = backend(ui)

        // Take a snapshot first
        b.execute("read_screen", "{}")

        val result = b.execute("tap", """{"index":99}""")

        assertEquals(ActionOutcome.Refused, result.outcome)
    }

    @Test
    fun `scroll delegates direction correctly`() {
        val ui = FakeUi(nodes = listOf(rawNode(text = "List", scrollable = true)))
        val b = backend(ui)

        val fwd = b.execute("scroll", """{"direction":"forward"}""")
        assertEquals(ActionOutcome.Done, fwd.outcome)
        assertEquals(listOf(ScrollDirection.FORWARD), ui.scrollCalls)

        // Need a fresh snapshot after scroll invalidates it - but scroll doesn't need a snapshot
        val bwd = b.execute("scroll", """{"direction":"backward"}""")
        assertEquals(ActionOutcome.Done, bwd.outcome)
        assertEquals(listOf(ScrollDirection.FORWARD, ScrollDirection.BACKWARD), ui.scrollCalls)
    }

    @Test
    fun `scroll with invalid direction is refused`() {
        val ui = FakeUi()
        val b = backend(ui)

        val result = b.execute("scroll", """{"direction":"sideways"}""")

        assertEquals(ActionOutcome.Refused, result.outcome)
        assertEquals("ARGUMENTS_INVALID", result.detail)
    }

    private fun assertNull(message: String, actual: Any?) {
        org.junit.Assert.assertNull(message, actual)
    }
}
