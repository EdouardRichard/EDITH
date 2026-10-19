package com.sheen.rokid.device

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Diff contract: node keys are structural paths, a truncated current observation never claims that
 * nodes disappeared, and the mode falls back to a full render for a first screen, more than 60
 * changed paths, or more than half of the baseline changing.
 */
class ObservationDiffTest {

    @Test
    fun `an unchanged observation produces an empty diff`() {
        val nodes = listOf(node("Root", depth = 0), node("Alpha", depth = 1))

        val diff = ObservationDiff.between(observation(1, nodes), observation(2, nodes))

        assertEquals(Diff(emptyList(), emptyList(), emptyList(), "diff"), diff)
    }

    @Test
    fun `one added, one updated and one removed path are reported`() {
        val old = observation(
            1,
            listOf(
                node("Root", depth = 0),
                node("Alpha", depth = 1),
                node("Beta", depth = 1),
                node("Gamma", depth = 1),
            ),
        )
        val new = observation(
            2,
            listOf(
                node("Root", depth = 0),
                node("Alpha 2", depth = 1),
                node("Beta", depth = 1),
                node("Delta", depth = 2),
            ),
        )

        val diff = ObservationDiff.between(old, new)

        assertEquals(listOf("0/1/0"), diff.added)
        assertEquals(listOf("0/0"), diff.updated)
        assertEquals(listOf("0/2"), diff.removed)
    }

    @Test
    fun `a truncated current observation never reports removed nodes`() {
        val old = observation(1, listOf(node("Root", depth = 0), node("Alpha", depth = 1)))
        val truncated = observation(2, listOf(node("Root", depth = 0)), truncated = true)

        val diff = ObservationDiff.between(old, truncated)

        assertTrue(diff.removed.isEmpty(), "a truncated observation only reports the visible part")
        assertTrue(diff.added.isEmpty())
        assertTrue(diff.updated.isEmpty())
        // The contrast: without the truncation flag the same tree does prove the node went away.
        assertEquals(listOf("0/0"), ObservationDiff.between(old, truncated.copy(truncated = false)).removed)
    }

    @Test
    fun `the first observation is a full render`() {
        val diff = ObservationDiff.between(
            observation(0, emptyList()),
            observation(1, listOf(node("Root", depth = 0), node("Alpha", depth = 1))),
        )

        assertEquals(listOf("0", "0/0"), diff.added)
        assertEquals("full", diff.mode)
    }

    @Test
    fun `a change covering more than half of the baseline forces full mode`() {
        val old = observation(
            1,
            listOf(node("Root", depth = 0), node("Alpha", depth = 1), node("Beta", depth = 1)),
        )
        val new = observation(2, listOf(node("Root", depth = 0), node("Alpha 2", depth = 1)))

        val diff = ObservationDiff.between(old, new)

        assertEquals(1, diff.updated.size)
        assertEquals(1, diff.removed.size)
        assertEquals("full", diff.mode)
    }

    @Test
    fun `a change covering less than half of the baseline stays in diff mode`() {
        val oldNodes = (0 until 40).map { node("Item-$it", depth = if (it == 0) 0 else 1) }
        val newNodes = oldNodes.mapIndexed { index, node ->
            if (index == 17) node.copy(text = "Item-17 changed") else node
        }

        val diff = ObservationDiff.between(observation(1, oldNodes), observation(2, newNodes))

        assertEquals(listOf("0/16"), diff.updated)
        assertEquals("diff", diff.mode)
    }

    @Test
    fun `exactly sixty changed paths stays in diff mode`() {
        val oldNodes = (0 until 400).map { node("Item-$it", depth = if (it == 0) 0 else 1) }
        val newNodes = oldNodes.mapIndexed { index, node ->
            if (index in 1..60) node.copy(text = "Changed-$index") else node
        }

        val diff = ObservationDiff.between(observation(1, oldNodes), observation(2, newNodes))

        assertEquals(60, diff.updated.size)
        assertEquals("diff", diff.mode)
    }

    @Test
    fun `a change covering exactly half of the baseline stays in diff mode`() {
        val oldNodes = (0 until 40).map { node("Item-$it", depth = if (it == 0) 0 else 1) }
        val newNodes = oldNodes.mapIndexed { index, node ->
            if (index in 1..20) node.copy(text = "Changed-$index") else node
        }

        val diff = ObservationDiff.between(observation(1, oldNodes), observation(2, newNodes))

        assertEquals(20, diff.updated.size)
        assertEquals("diff", diff.mode)
    }

    @Test
    fun `an insertion in the middle shifts later paths and is reported positionally`() {
        val oldNodes = (0 until 40).map { node("Item-$it", depth = if (it == 0) 0 else 1) }
        val newNodes = oldNodes.toMutableList().apply {
            add(19, node("Inserted", depth = 1))
        }

        val diff = ObservationDiff.between(observation(1, oldNodes), observation(2, newNodes))

        // The inserted node takes path 0/18; every sibling after it shifts one path down.
        assertEquals(listOf("0/39"), diff.added)
        assertEquals((18..38).map { "0/$it" }, diff.updated)
        assertTrue(diff.removed.isEmpty(), "a positional shift is not a removal")
        assertEquals(22, diff.added.size + diff.updated.size)
        assertEquals("full", diff.mode)
    }

    @Test
    fun `more than sixty changed paths forces full mode even under half of the baseline`() {
        val oldNodes = (0 until 200).map { node("Item-$it", depth = if (it == 0) 0 else 1) }
        val newNodes = oldNodes.mapIndexed { index, node ->
            if (index in 1..61) node.copy(text = "Changed-$index") else node
        }

        val diff = ObservationDiff.between(observation(1, oldNodes), observation(2, newNodes))

        assertEquals(61, diff.updated.size)
        assertEquals("full", diff.mode)
    }

    private fun node(text: String, depth: Int) = DeviceNode(
        role = "text",
        text = text,
        contentDescription = null,
        viewId = null,
        bounds = Bounds(0, depth * 10, 100, depth * 10 + 10),
        states = emptySet(),
        actions = emptyList(),
        hint = null,
        paneTitle = null,
        windowId = 0,
        depth = depth,
    )

    private fun observation(
        revision: Long,
        nodes: List<DeviceNode>,
        truncated: Boolean = false,
    ) = Observation(
        revision = revision,
        windows = listOf(
            ObservationWindow(
                id = 0,
                type = "application",
                layer = 0,
                bounds = Bounds(0, 0, 1080, 2400),
                packageName = "com.example",
                title = "Demo",
            ),
        ),
        nodes = nodes,
        truncated = truncated,
    )
}
