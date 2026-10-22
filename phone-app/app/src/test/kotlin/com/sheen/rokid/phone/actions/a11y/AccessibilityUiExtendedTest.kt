package com.sheen.rokid.phone.actions.a11y
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the extended AccessibilityUi port (T1.3 of EDITH 0.21 Phase 1).
 *
 * These tests verify:
 * - snapshot() returns elements matching the tree
 * - clickByIndex with valid snapshot succeeds
 * - clickByIndex with stale snapshot returns false
 * - clickByIndex with out-of-bounds index returns false
 * - longPressByIndex works similarly
 * - scroll forward/backward delegates correctly
 * - setTextByIndex writes to the correct node (not focused node)
 * - setTextByIndex rejects non-editable nodes
 * - structuralFingerprint includes node counts and truncation flag
 * - Actions invalidate the current snapshot
 */
class AccessibilityUiExtendedTest {

    // ── Fake UI that implements the extended interface ───────────────────────

    /**
     * A fake AccessibilityUi that tracks all calls and supports snapshot-based actions.
     * It uses SnapshotBuilder internally with a configurable FakeNodeSource.
     */
    private class FakeExtendedUi(
        private val nodes: List<RawSnapshotNode> = emptyList(),
        private val maxNodes: Int = MAX_NODES,
        private val clickResult: Boolean = true,
        private val longPressResult: Boolean = true,
        private val scrollResult: Boolean = true,
        private val setTextResult: Boolean = true,
    ) : AccessibilityUi {

        // Tracking
        val clickByIndexCalls = mutableListOf<Pair<String, Int>>()
        val longPressByIndexCalls = mutableListOf<Pair<String, Int>>()
        val scrollCalls = mutableListOf<ScrollDirection>()
        val setTextByIndexCalls = mutableListOf<Triple<String, Int, String>>()

        // Snapshot lifecycle
        private var currentSnapshot: ScreenSnapshot? = null

        // Legacy methods (still required by interface)
        override fun clickText(text: String): Boolean = true
        override fun setText(text: String): Boolean = true
        override fun screenFingerprint(): String = "fake-fingerprint"

        // New methods
        override fun snapshot(filter: ScreenFilter): ScreenSnapshot {
            val source = object : SnapshotNodeSource {
                override fun walkNodes(filter: ScreenFilter): List<RawSnapshotNode> {
                    // Apply filter at the source level, matching FakeNodeSource behavior in ScreenSnapshotTest
                    return when (filter) {
                        ScreenFilter.ALL -> nodes
                        ScreenFilter.INTERACTIVE -> nodes.filter { it.clickable || it.editable || it.scrollable }
                        ScreenFilter.TEXT -> nodes.filter { !it.text.isNullOrBlank() }
                    }
                }
                override fun maxNodeLimit(): Int = maxNodes
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
            // Invalidate after action
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

        override fun structuralFingerprint(): ScreenFingerprint {
            val source = object : SnapshotNodeSource {
                override fun walkNodes(filter: ScreenFilter): List<RawSnapshotNode> = nodes
                override fun maxNodeLimit(): Int = maxNodes
            }
            val snap = SnapshotBuilder.build(source)
            return snap.fingerprint
        }
    }

    // ── Helper to build RawSnapshotNode lists ────────────────────────────────

    private fun rawNode(
        text: String? = null,
        hintText: String? = null,
        clickable: Boolean = false,
        editable: Boolean = false,
        scrollable: Boolean = false,
        checked: Boolean = false,
        left: Int = 0, top: Int = 0, right: Int = 100, bottom: Int = 50,
    ) = RawSnapshotNode(
        text = text,
        contentDescription = null,
        className = null,
        clickable = clickable,
        editable = editable,
        scrollable = scrollable,
        checked = checked,
        left = left, top = top, right = right, bottom = bottom,
        hintText = hintText,
    )

    // ── Test cases ───────────────────────────────────────────────────────────

    @Test
    fun `snapshot returns elements matching the tree`() {
        val nodes = listOf(
            rawNode(text = "Settings", clickable = true),
            rawNode(text = "Wi-Fi", clickable = true, editable = false),
            rawNode(text = "Enter name", editable = true, hintText = "Enter name"),
        )
        val ui = FakeExtendedUi(nodes = nodes)

        val snapshot = ui.snapshot(ScreenFilter.ALL)

        assertEquals(3, snapshot.elements.size)
        assertEquals("Settings", snapshot.elements[0].text)
        assertEquals("Wi-Fi", snapshot.elements[1].text)
        assertEquals("Enter name", snapshot.elements[2].text)
        assertTrue("hint detection", snapshot.elements[2].isHintText)
        assertFalse(snapshot.truncated)
    }

    @Test
    fun `clickByIndex with valid snapshot succeeds`() {
        val nodes = listOf(rawNode(text = "OK", clickable = true))
        val ui = FakeExtendedUi(nodes = nodes, clickResult = true)

        val snapshot = ui.snapshot(ScreenFilter.ALL)
        val result = ui.clickByIndex(snapshot.snapshotId, 0)

        assertTrue("click should succeed", result)
        assertEquals(1, ui.clickByIndexCalls.size)
        assertEquals(snapshot.snapshotId, ui.clickByIndexCalls[0].first)
        assertEquals(0, ui.clickByIndexCalls[0].second)
    }

    @Test
    fun `clickByIndex with stale snapshot returns false`() {
        val nodes = listOf(rawNode(text = "OK", clickable = true))
        val ui = FakeExtendedUi(nodes = nodes)

        val oldSnapshot = ui.snapshot(ScreenFilter.ALL)
        // Take a new snapshot, invalidating the old one
        val newSnapshot = ui.snapshot(ScreenFilter.ALL)
        assertNotEquals(oldSnapshot.snapshotId, newSnapshot.snapshotId)

        val result = ui.clickByIndex(oldSnapshot.snapshotId, 0)

        assertFalse("stale snapshot must be rejected", result)
        assertTrue("no click should have been attempted", ui.clickByIndexCalls.isEmpty())
    }

    @Test
    fun `clickByIndex with out-of-bounds index returns false`() {
        val nodes = listOf(rawNode(text = "Only", clickable = true))
        val ui = FakeExtendedUi(nodes = nodes)

        val snapshot = ui.snapshot(ScreenFilter.ALL)
        assertEquals(1, snapshot.elements.size)

        val result = ui.clickByIndex(snapshot.snapshotId, 5)

        assertFalse("out-of-bounds index must be rejected", result)
        assertTrue("no click should have been attempted", ui.clickByIndexCalls.isEmpty())
    }

    @Test
    fun `longPressByIndex with valid snapshot succeeds`() {
        val nodes = listOf(rawNode(text = "Item", clickable = true))
        val ui = FakeExtendedUi(nodes = nodes, longPressResult = true)

        val snapshot = ui.snapshot(ScreenFilter.ALL)
        val result = ui.longPressByIndex(snapshot.snapshotId, 0)

        assertTrue("long press should succeed", result)
        assertEquals(1, ui.longPressByIndexCalls.size)
    }

    @Test
    fun `longPressByIndex with stale snapshot returns false`() {
        val nodes = listOf(rawNode(text = "Item", clickable = true))
        val ui = FakeExtendedUi(nodes = nodes)

        val oldSnapshot = ui.snapshot(ScreenFilter.ALL)
        ui.snapshot(ScreenFilter.ALL) // invalidate

        assertFalse(ui.longPressByIndex(oldSnapshot.snapshotId, 0))
        assertTrue(ui.longPressByIndexCalls.isEmpty())
    }

    @Test
    fun `scroll forward delegates correctly`() {
        val ui = FakeExtendedUi(scrollResult = true)

        val result = ui.scroll(ScrollDirection.FORWARD)

        assertTrue(result)
        assertEquals(listOf(ScrollDirection.FORWARD), ui.scrollCalls)
    }

    @Test
    fun `scroll backward delegates correctly`() {
        val ui = FakeExtendedUi(scrollResult = true)

        val result = ui.scroll(ScrollDirection.BACKWARD)

        assertTrue(result)
        assertEquals(listOf(ScrollDirection.BACKWARD), ui.scrollCalls)
    }

    @Test
    fun `scroll returns false at boundary`() {
        val ui = FakeExtendedUi(scrollResult = false)

        assertFalse(ui.scroll(ScrollDirection.FORWARD))
    }

    @Test
    fun `setTextByIndex writes to the correct node`() {
        val nodes = listOf(
            rawNode(text = "Label", clickable = true),
            rawNode(text = "", editable = true),
        )
        val ui = FakeExtendedUi(nodes = nodes, setTextResult = true)

        val snapshot = ui.snapshot(ScreenFilter.ALL)
        val result = ui.setTextByIndex(snapshot.snapshotId, 1, "hello")

        assertTrue("setText should succeed on editable node", result)
        assertEquals(1, ui.setTextByIndexCalls.size)
        assertEquals(1, ui.setTextByIndexCalls[0].second)
        assertEquals("hello", ui.setTextByIndexCalls[0].third)
    }

    @Test
    fun `setTextByIndex rejects non-editable nodes`() {
        val nodes = listOf(rawNode(text = "Button", clickable = true, editable = false))
        val ui = FakeExtendedUi(nodes = nodes)

        val snapshot = ui.snapshot(ScreenFilter.ALL)
        val result = ui.setTextByIndex(snapshot.snapshotId, 0, "hello")

        assertFalse("setText on non-editable must fail", result)
        assertTrue("no setText should have been attempted", ui.setTextByIndexCalls.isEmpty())
    }

    @Test
    fun `structuralFingerprint includes node counts and truncation flag`() {
        val nodes = listOf(
            rawNode(text = "A", clickable = true),
            rawNode(text = "B", clickable = true),
            rawNode(text = "C", clickable = false),
        )
        val ui = FakeExtendedUi(nodes = nodes)

        val fp = ui.structuralFingerprint()

        assertEquals(3, fp.nodeCount)
        assertEquals(2, fp.clickableCount)
        assertFalse(fp.truncated)
        assertTrue("hash must not be empty", fp.hash.isNotEmpty())
    }

    @Test
    fun `structuralFingerprint reports truncation when tree exceeds limit`() {
        val limit = 3
        val nodes = (1..10).map { rawNode(text = "Item $it", clickable = true) }
        val ui = FakeExtendedUi(nodes = nodes, maxNodes = limit)

        val fp = ui.structuralFingerprint()

        assertTrue("must report truncated", fp.truncated)
    }

    @Test
    fun `actions invalidate the current snapshot`() {
        val nodes = listOf(rawNode(text = "OK", clickable = true))
        val ui = FakeExtendedUi(nodes = nodes)

        val snapshot = ui.snapshot(ScreenFilter.ALL)

        // Click invalidates
        ui.clickByIndex(snapshot.snapshotId, 0)

        // Using the same snapshotId again must fail
        assertFalse("snapshot must be invalidated after click",
            ui.clickByIndex(snapshot.snapshotId, 0))
    }

    @Test
    fun `setTextByIndex invalidates the current snapshot`() {
        val nodes = listOf(rawNode(text = "", editable = true))
        val ui = FakeExtendedUi(nodes = nodes)

        val snapshot = ui.snapshot(ScreenFilter.ALL)
        ui.setTextByIndex(snapshot.snapshotId, 0, "text")

        assertFalse("snapshot must be invalidated after setText",
            ui.setTextByIndex(snapshot.snapshotId, 0, "more"))
    }

    @Test
    fun `scroll invalidates the current snapshot`() {
        val nodes = listOf(rawNode(text = "OK", clickable = true))
        val ui = FakeExtendedUi(nodes = nodes)

        val snapshot = ui.snapshot(ScreenFilter.ALL)
        ui.scroll(ScrollDirection.FORWARD)

        assertFalse("snapshot must be invalidated after scroll",
            ui.clickByIndex(snapshot.snapshotId, 0))
    }

    @Test
    fun `snapshot with INTERACTIVE filter returns only interactive elements`() {
        val nodes = listOf(
            rawNode(text = "Title", clickable = false, editable = false, scrollable = false),
            rawNode(text = "Button", clickable = true),
            rawNode(text = "Input", editable = true),
            rawNode(text = "List", scrollable = true),
        )
        val ui = FakeExtendedUi(nodes = nodes)

        val snapshot = ui.snapshot(ScreenFilter.INTERACTIVE)

        assertEquals(3, snapshot.elements.size)
        assertTrue(snapshot.elements.all { it.clickable || it.editable || it.scrollable })
    }
}
