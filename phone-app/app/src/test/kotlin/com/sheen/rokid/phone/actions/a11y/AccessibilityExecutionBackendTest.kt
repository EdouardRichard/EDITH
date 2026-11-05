package com.sheen.rokid.phone.actions.a11y
import com.sheen.rokid.phone.storage.unavailable
import com.sheen.rokid.phone.withP3Sink

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

    /** A run of plain text nodes with distinct rectangles, for the pagination tests. */
    private fun textNodes(count: Int): List<RawSnapshotNode> = (0 until count).map { i ->
        RawSnapshotNode(
            text = "Item $i", contentDescription = null, className = null,
            clickable = true, editable = false, scrollable = false, checked = false,
            left = 0, top = i * 10, right = 100, bottom = i * 10 + 10, hintText = null,
        )
    }

    // ── read_screen fields and pagination (0.5.4 T9) ────────────────────────

    @Test fun `read screen renders content description and bounds`() {
        val nodes = listOf(
            RawSnapshotNode(
                text = null, contentDescription = "搜索设置项", className = null,
                clickable = true, editable = false, scrollable = false, checked = false,
                left = 900, top = 120, right = 1056, bottom = 240, hintText = null,
            ),
            RawSnapshotNode(
                text = "WLAN", contentDescription = null, className = null,
                clickable = true, editable = false, scrollable = false, checked = false,
                left = 0, top = 0, right = 100, bottom = 50, hintText = null,
            ),
            RawSnapshotNode(
                text = "Wi-Fi", contentDescription = "Wi-Fi 设置", className = null,
                clickable = true, editable = false, scrollable = false, checked = false,
                left = 0, top = 50, right = 100, bottom = 100, hintText = null,
            ),
        )
        val result = backend(FakeUi(nodes = nodes)).execute("read_screen", """{"filter":"all"}""")

        assertEquals(ActionOutcome.Done, result.outcome)
        val detail = result.detail
        // A node without text still reaches the model through its description, with the rectangle and its midpoint.
        assertTrue(
            detail,
            """[0] desc="搜索设置项" bounds=[900,120][1056,240] center=(978,180) clickable""" in detail,
        )
        // Text stays the primary label; a node without a description just carries geometry.
        assertTrue(detail, """[1] "WLAN" bounds=[0,0][100,50] center=(50,25) clickable""" in detail)
        // A description that adds information the text does not carry is kept next to it.
        assertTrue(
            detail,
            """[2] "Wi-Fi" desc="Wi-Fi 设置" bounds=[0,50][100,100] center=(50,75) clickable""" in detail,
        )
    }

    @Test fun `read screen page offset returns the next slice`() {
        val ui = FakeUi(nodes = textNodes(150))
        val b = backend(ui)

        val firstPage = b.execute("read_screen", """{"filter":"all"}""").detail

        assertTrue("the first page starts at index 0: $firstPage", """[0] "Item 0"""" in firstPage)
        assertTrue("the first page ends at index 99: $firstPage", """[99] "Item 99"""" in firstPage)
        assertFalse("the first page must not leak index 100: $firstPage", "[100]" in firstPage)

        val nextPage = b.execute("read_screen", """{"filter":"all","offset":100}""").detail

        assertTrue("offset 100 starts at the global index 100: $nextPage", """[100] "Item 100"""" in nextPage)
        assertTrue("the next page reaches the last element: $nextPage", """[149] "Item 149"""" in nextPage)
        assertFalse("the next page must not repeat index 99: $nextPage", "[99]" in nextPage)

        // The index is global, not page-relative: a snapshot read with an offset is still actionable through `tap`.
        val tap = b.execute("tap", """{"index":149}""")
        assertEquals(ActionOutcome.Done, tap.outcome)
        assertEquals(149, ui.clickByIndexCalls.last().second)
    }

    @Test fun `read screen truncated still marks truncation`() {
        // 400 nodes is the snapshot cap; paging must not change the snapshot's own truncation fact.
        val nodes = textNodes(MAX_NODES)
        val b = backend(FakeUi(nodes = nodes))

        val firstPage = b.execute("read_screen", """{"filter":"all"}""").detail

        assertTrue("the first page is bounded: $firstPage", """[99] "Item 99"""" in firstPage)
        assertFalse("the 400 cap does not lift the page bound: $firstPage", "[100]" in firstPage)
        assertTrue("the underlying snapshot is truncated: $firstPage", "truncated: true" in firstPage)

        val lastPage = b.execute("read_screen", """{"filter":"all","offset":300}""").detail

        assertTrue("offset 300 addresses the last page: $lastPage", """[300] "Item 300"""" in lastPage)
        assertTrue("the last page reaches the capped tail: $lastPage", """[399] "Item 399"""" in lastPage)
        assertTrue("truncation is a snapshot fact, not a page fact: $lastPage", "truncated: true" in lastPage)
    }

    @Test fun `read screen refuses a negative offset`() {
        val b = backend(FakeUi())

        val result = b.execute("read_screen", """{"offset":-1}""")

        assertEquals(ActionOutcome.Refused, result.outcome)
        assertEquals("ARGUMENTS_INVALID", result.detail)
    }

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

    @Test fun `index actions report only their via token`() {
        // 0.5.4 Task 8 (§6.2.2): `P3_ACT` starts with the targeting mode this task really has — index. Coordinate taps
        // arrive in Task 11 and extend the same token vocabulary; the line must never carry screen text or the index.
        val ui = FakeUi()
        val b = backend(ui)

        val acts = withP3Sink { captured ->
            fun readAnd(action: String, args: String) {
                b.execute("read_screen", "{}")
                b.execute(action, args)
            }
            readAnd("tap", """{"index":0}""")
            readAnd("long_press", """{"index":0}""")
            readAnd("input_text", """{"index":0,"text":"hello"}""")
            captured.filter { it.startsWith("P3_ACT") }.toList()
        }

        assertEquals(listOf("P3_ACT via=index", "P3_ACT via=index", "P3_ACT via=index"), acts)
        assertTrue(
            "no screen or typed text may ride it: $acts",
            acts.none { it.contains("WLAN") || it.contains("hello") },
        )
    }

    @Test fun `a refused index action emits no act line`() {
        // 0.5.4 Task 8 fix round (review Important 1): `P3_ACT` reports a **dispatched** index action, not an attempt.
        // A refusal returns before `ui.*ByIndex` is ever called, so counting it would make a device log overstate acts
        // and attribute a device line to an action that never touched the device.
        val ui = FakeUi()
        val b = backend(ui)

        val acts = withP3Sink { captured ->
            // No snapshot established: all three index actions are refused before any UI call.
            b.execute("tap", """{"index":0}""")
            b.execute("long_press", """{"index":0}""")
            b.execute("input_text", """{"index":0,"text":"hello"}""")
            // A real snapshot but an out-of-range index.
            b.execute("read_screen", "{}")
            b.execute("tap", """{"index":9}""")
            b.execute("long_press", """{"index":9}""")
            b.execute("input_text", """{"index":9,"text":"hello"}""")
            // A real snapshot and index but invalid text.
            b.execute("input_text", """{"index":0,"text":""}""")
            b.execute("input_text", """{"index":0,"text":"two\nlines"}""")
            captured.filter { it.startsWith("P3_ACT") }.toList()
        }

        assertTrue("a refused action was never dispatched and must emit no act line: $acts", acts.isEmpty())
        assertTrue("a refused action must not reach the UI: ${ui.clickByIndexCalls}", ui.clickByIndexCalls.isEmpty())
        assertTrue("a refused action must not reach the UI: ${ui.setTextByIndexCalls}", ui.setTextByIndexCalls.isEmpty())
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
