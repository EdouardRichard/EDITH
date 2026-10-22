package com.sheen.rokid.phone.actions.a11y

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ScreenSnapshot data shape tests (T1.1 + T1.2 of EDITH 0.21 Phase 1).
 *
 * These tests define the contract for the snapshot abstraction: a stable, filterable, bounded view of the
 * accessibility node tree that can be used by the agent loop without touching a real device.
 *
 * The fake node tree builder lets tests construct trees with text, contentDescription, className, flags
 * (clickable/editable/scrollable/checked), bounds, children, and hint text — all without Android framework.
 */
class ScreenSnapshotTest {

    // ── Fake node tree builder ───────────────────────────────────────────────

    /**
     * A fake accessibility node that carries exactly the properties ScreenSnapshot needs.
     * Tests build trees from these; the SnapshotBuilder reads them through [SnapshotNodeSource].
     */
    private data class FakeNode(
        val text: String? = null,
        val contentDescription: String? = null,
        val className: String? = null,
        val clickable: Boolean = false,
        val editable: Boolean = false,
        val scrollable: Boolean = false,
        val checked: Boolean = false,
        val left: Int = 0,
        val top: Int = 0,
        val right: Int = 0,
        val bottom: Int = 0,
        val hintText: String? = null,
        val children: List<FakeNode> = emptyList(),
    )

    /**
     * Adapts a [FakeNode] tree into the [SnapshotNodeSource] contract that [SnapshotBuilder] consumes.
     * This is the test-side implementation of whatever intermediate abstraction the production code defines.
     */
    private class FakeNodeSource(
        private val roots: List<FakeNode>,
        private val maxNodes: Int = MAX_NODES_DEFAULT,
    ) : SnapshotNodeSource {

        override fun walkNodes(filter: ScreenFilter): List<RawSnapshotNode> {
            val result = mutableListOf<RawSnapshotNode>()
            var visited = 0
            fun visit(node: FakeNode) {
                if (visited >= maxNodes) return
                visited++
                val raw = RawSnapshotNode(
                    text = node.text,
                    contentDescription = node.contentDescription,
                    className = node.className,
                    clickable = node.clickable,
                    editable = node.editable,
                    scrollable = node.scrollable,
                    checked = node.checked,
                    left = node.left,
                    top = node.top,
                    right = node.right,
                    bottom = node.bottom,
                    hintText = node.hintText,
                )
                val matches = when (filter) {
                    ScreenFilter.ALL -> true
                    ScreenFilter.INTERACTIVE -> node.clickable || node.editable || node.scrollable
                    ScreenFilter.TEXT -> !node.text.isNullOrBlank()
                }
                if (matches) result += raw
                for (child in node.children) visit(child)
            }
            for (root in roots) visit(root)
            return result
        }

        override fun maxNodeLimit(): Int = maxNodes
    }

    // ── Test cases ───────────────────────────────────────────────────────────

    @Test
    fun `stable reads produce identical indices and content`() {
        val tree = listOf(
            FakeNode(text = "Settings", clickable = true, left = 0, top = 0, right = 100, bottom = 50),
            FakeNode(text = "Wi-Fi", clickable = true, left = 0, top = 50, right = 100, bottom = 100),
        )
        val source = FakeNodeSource(tree)

        val first = SnapshotBuilder.build(source)
        val second = SnapshotBuilder.build(source)

        assertEquals(first.elements.size, second.elements.size)
        for (i in first.elements.indices) {
            assertEquals("index $i text", first.elements[i].text, second.elements[i].text)
            assertEquals("index $i index field", first.elements[i].index, second.elements[i].index)
            assertEquals("index $i boundsDigest", first.elements[i].boundsDigest, second.elements[i].boundsDigest)
        }
        assertEquals(first.fingerprint.hash, second.fingerprint.hash)
    }

    @Test
    fun `filter interactive returns only clickable editable or scrollable nodes`() {
        val tree = listOf(
            FakeNode(text = "Title", clickable = false, editable = false, scrollable = false),
            FakeNode(text = "Button", clickable = true),
            FakeNode(text = "Input", editable = true),
            FakeNode(text = "List", scrollable = true),
            FakeNode(text = "Label", clickable = false),
        )
        val source = FakeNodeSource(tree)

        val snapshot = SnapshotBuilder.build(source, filter = ScreenFilter.INTERACTIVE)

        assertEquals(3, snapshot.elements.size)
        assertTrue(snapshot.elements.all { it.clickable || it.editable || it.scrollable })
        assertEquals(setOf("Button", "Input", "List"), snapshot.elements.mapNotNull { it.text }.toSet())
    }

    @Test
    fun `filter text returns only nodes with non-empty text`() {
        val tree = listOf(
            FakeNode(text = "Hello", clickable = true),
            FakeNode(text = "", clickable = true),
            FakeNode(text = null, clickable = true),
            FakeNode(text = "World", clickable = false),
            FakeNode(text = "   ", clickable = true),
        )
        val source = FakeNodeSource(tree)

        val snapshot = SnapshotBuilder.build(source, filter = ScreenFilter.TEXT)

        assertEquals(2, snapshot.elements.size)
        assertEquals(setOf("Hello", "World"), snapshot.elements.mapNotNull { it.text }.toSet())
    }

    @Test
    fun `empty input whose text equals its hint is marked as hint not content`() {
        val tree = listOf(
            FakeNode(text = "Enter name", hintText = "Enter name", editable = true),
        )
        val source = FakeNodeSource(tree)

        val snapshot = SnapshotBuilder.build(source)

        assertEquals(1, snapshot.elements.size)
        assertTrue("text matching hint must set isHintText=true", snapshot.elements[0].isHintText)
        assertEquals("Enter name", snapshot.elements[0].text)
    }

    @Test
    fun `tree exceeding node limit sets truncated and caps element count`() {
        val limit = 5
        val tree = (1..10).map { FakeNode(text = "Item $it", clickable = true) }
        val source = FakeNodeSource(tree, maxNodes = limit)

        val snapshot = SnapshotBuilder.build(source)

        assertTrue("snapshot must report truncated when tree exceeds limit", snapshot.truncated)
        assertTrue("element count must not exceed limit", snapshot.elements.size <= limit)
        assertTrue("fingerprint must also report truncated", snapshot.fingerprint.truncated)
    }

    @Test
    fun `stale snapshot id is rejected with a recognizable refusal code`() {
        val tree = listOf(FakeNode(text = "OK", clickable = true))
        val source = FakeNodeSource(tree)

        val snapshot = SnapshotBuilder.build(source)
        // Simulate invalidation: build a new snapshot (which generates a new id)
        val newer = SnapshotBuilder.build(source)

        assertNotEquals("new snapshot must have a different id", snapshot.snapshotId, newer.snapshotId)

        // Using the old snapshot's id against the current state must produce a refusal
        val refusal = SnapshotValidator.validate(snapshot.snapshotId, newer)
        assertNotNull(refusal)
        assertEquals(SnapshotRefusal.STALE_SNAPSHOT, refusal)
    }

    @Test
    fun `index out of bounds returns a recognizable refusal code`() {
        val tree = listOf(FakeNode(text = "Only", clickable = true))
        val source = FakeNodeSource(tree)

        val snapshot = SnapshotBuilder.build(source)
        assertEquals(1, snapshot.elements.size)

        val refusal = SnapshotValidator.validateIndex(snapshot, index = 5)
        assertNotNull(refusal)
        assertEquals(SnapshotRefusal.INDEX_OUT_OF_BOUNDS, refusal)
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun <T> assertNotNull(value: T?) {
        assertTrue("expected non-null", value != null)
    }

    companion object {
        /** Matches the default in SnapshotBuilder / production code. */
        private const val MAX_NODES_DEFAULT = 400
    }
}
