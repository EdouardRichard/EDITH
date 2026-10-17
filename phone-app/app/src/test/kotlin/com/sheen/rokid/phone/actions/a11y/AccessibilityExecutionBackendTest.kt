package com.sheen.rokid.phone.actions.a11y
import com.sheen.rokid.phone.storage.unavailable

import com.sheen.rokid.phone.actions.ExecutionRouter
import com.sheen.rokid.plugin.ActionOutcome
import com.sheen.rokid.plugin.RiskLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The accessibility channel (spec §17.2/§17.3): cross-app semantic UI operations, whose success criterion comes from
 * P0's T3b probe.
 *
 * That probe's two hard-won rules are pinned here: a click must aim at the **nearest clickable ancestor** of the node
 * that carries the text (the label itself returns `false` from `ACTION_CLICK`), and success is **the screen changing**,
 * not the action being accepted. "Mock 或编译通过不得当作实机通过证据" - and the same rule cuts the other way: an action
 * that returns `true` while nothing moved is a failed action.
 *
 * T1.5 updated: replaced tap_text/type_text with index-based tools (tap, input_text, etc.).
 * Behavioral assertions preserved per A1 discipline.
 */
class AccessibilityExecutionBackendTest {

    private class FakeUi(
        private val clickable: Boolean = true,
        private val fingerprintBefore: String = "screen-a",
        private val fingerprintAfter: String = "screen-b",
        private val nodes: List<RawSnapshotNode> = listOf(
            RawSnapshotNode(
                text = "WLAN", contentDescription = null, className = null,
                clickable = true, editable = false, scrollable = false, checked = false,
                left = 0, top = 0, right = 100, bottom = 50, hintText = null,
            ),
        ),
    ) : AccessibilityUi {
        val clickByIndexCalls = mutableListOf<Pair<String, Int>>()
        val setTextByIndexCalls = mutableListOf<Triple<String, Int, String>>()
        var fingerprints = 0
        private var currentSnapshot: ScreenSnapshot? = null

        override fun clickText(text: String): Boolean = clickable
        override fun setText(text: String): Boolean = clickable
        override fun screenFingerprint(): String {
            fingerprints++
            return if (fingerprints == 1) fingerprintBefore else fingerprintAfter
        }

        override fun snapshot(filter: ScreenFilter): ScreenSnapshot {
            val source = object : SnapshotNodeSource {
                override fun walkNodes(filter: ScreenFilter): List<RawSnapshotNode> = nodes
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
            return clickable
        }

        override fun longPressByIndex(snapshotId: String, index: Int): Boolean {
            val snap = currentSnapshot ?: return false
            if (SnapshotValidator.validate(snapshotId, snap) != null) return false
            if (SnapshotValidator.validateIndex(snap, index) != null) return false
            currentSnapshot = null
            return clickable
        }

        override fun scroll(direction: ScrollDirection): Boolean {
            currentSnapshot = null
            return clickable
        }

        override fun setTextByIndex(snapshotId: String, index: Int, text: String): Boolean {
            val snap = currentSnapshot ?: return false
            if (SnapshotValidator.validate(snapshotId, snap) != null) return false
            if (SnapshotValidator.validateIndex(snap, index) != null) return false
            setTextByIndexCalls += Triple(snapshotId, index, text)
            currentSnapshot = null
            return clickable
        }

        override fun structuralFingerprint(): ScreenFingerprint =
            ScreenFingerprint(hash = "", nodeCount = 0, clickableCount = 0, truncated = false)
    }

    private fun backend(ui: AccessibilityUi, connected: Boolean = true) =
        AccessibilityExecutionBackend(ui = ui, connected = { connected })

    @Test fun `tap is a success only when the screen actually changed`() {
        val ui = FakeUi(fingerprintAfter = "screen-a")

        // Establish snapshot first
        backend(ui).execute("read_screen", "{}")
        val result = backend(ui).also { it.execute("read_screen", "{}") }.execute("tap", """{"index":0}""")

        assertEquals(ActionOutcome.Refused, result.outcome)
        assertEquals("NO_SCREEN_CHANGE", result.detail)
    }

    @Test fun `tap succeeds when the screen changed`() {
        val ui = FakeUi()
        val b = backend(ui)

        b.execute("read_screen", "{}") // establish snapshot
        val result = b.execute("tap", """{"index":0}""")

        assertEquals(ActionOutcome.Done, result.outcome)
        assertTrue(result.detail.contains("tapped"))
    }

    @Test fun `input_text is judged the same way as tap for screen change`() {
        val editableNode = RawSnapshotNode(
            text = "", contentDescription = null, className = null,
            clickable = false, editable = true, scrollable = false, checked = false,
            left = 0, top = 0, right = 100, bottom = 50, hintText = null,
        )
        val staleUi = FakeUi(fingerprintAfter = "screen-a", nodes = listOf(editableNode))
        val staleB = backend(staleUi)
        staleB.execute("read_screen", "{}")
        val stale = staleB.execute("input_text", """{"index":0,"text":"hello"}""")
        assertEquals(ActionOutcome.Refused, stale.outcome)
        assertEquals("NO_SCREEN_CHANGE", stale.detail)

        val okUi = FakeUi(nodes = listOf(editableNode))
        val okB = backend(okUi)
        okB.execute("read_screen", "{}")
        val ok = okB.execute("input_text", """{"index":0,"text":"hello"}""")
        assertEquals(ActionOutcome.Done, ok.outcome)
    }

    @Test fun `an unavailable service refuses instead of pretending`() {
        val ui = FakeUi()

        val result = backend(ui, connected = false).execute("tap", """{"index":0}""")

        assertEquals(ActionOutcome.Refused, result.outcome)
        assertEquals("ACTION_CHANNEL_UNAVAILABLE", result.detail)
        assertTrue("a down channel must advertise nothing through the router",
            ExecutionRouter(listOf(backend(ui, connected = false))).descriptors().isEmpty())
    }

    @Test fun `no action may record screen text in the audit trail`() {
        val byName = backend(FakeUi()).descriptors().associateBy { it.name }

        assertEquals(setOf("read_screen", "tap", "long_press", "scroll", "input_text"), byName.keys)
        byName.values.forEach { descriptor ->
            assertEquals("${descriptor.name} scope", "app.ui", descriptor.scope)
            // Screen content is private; recording arguments would be recording what the user saw.
            assertEquals("${descriptor.name} audit target", null, descriptor.auditTargetArgument)
        }
    }

    @Test fun `blank or multiline text in input_text is refused`() {
        val editableNode = RawSnapshotNode(
            text = "", contentDescription = null, className = null,
            clickable = false, editable = true, scrollable = false, checked = false,
            left = 0, top = 0, right = 100, bottom = 50, hintText = null,
        )
        val ui = FakeUi(nodes = listOf(editableNode))
        val b = backend(ui)

        listOf("""{"index":0,"text":""}""", """{"index":0,"text":"two\nlines"}""", """{"index":0,"text":"   "}""").forEach { args ->
            b.execute("read_screen", "{}") // re-establish snapshot
            val result = b.execute("input_text", args)
            assertEquals("refused: $args", ActionOutcome.Refused, result.outcome)
            assertEquals("refused: $args", "ARGUMENTS_INVALID", result.detail)
        }
        assertTrue(ui.setTextByIndexCalls.isEmpty())
    }

    @Test fun `an unknown action name is refused rather than executed`() {
        val ui = FakeUi()

        assertEquals(ActionOutcome.Refused, backend(ui).execute("tap_anything", "{}").outcome)
    }

    @Test fun `the click result alone is not the verdict`() {
        // `clickByIndex` answered `false` (the node was not clickable): whatever the screen does afterwards, this is a
        // failure, and the code says why.
        val ui = FakeUi(clickable = false)
        val b = backend(ui)
        b.execute("read_screen", "{}")

        val result = b.execute("tap", """{"index":0}""")

        assertEquals(ActionOutcome.Refused, result.outcome)
        assertEquals("NODE_NOT_CLICKABLE", result.detail)
        assertFalse(result.detail == "NO_SCREEN_CHANGE")
    }
}
