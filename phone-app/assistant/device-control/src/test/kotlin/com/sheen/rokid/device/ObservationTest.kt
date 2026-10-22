package com.sheen.rokid.device

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins the structural key that [RefRegistry] and [ObservationDiff] use to address a node: the node
 * list is a pre-order traversal and `DeviceNode.depth` carries the tree shape, so a position is the
 * path of child indices from the root (`0`, `0/1`, `0/1/0`, ...).
 */
class ObservationTest {

    @Test
    fun `a pre-order depth walk produces one structural path per node`() {
        val nodes = listOf(
            node(depth = 0),
            node(depth = 1),
            node(depth = 2),
            node(depth = 2),
            node(depth = 1),
            node(depth = 0),
        )

        assertEquals(
            listOf("0", "0/0", "0/0/0", "0/0/1", "0/1", "1"),
            NodeStructure.paths(nodes),
        )
    }

    @Test
    fun `a depth jump still produces one path per node by zero filling the skipped level`() {
        val nodes = listOf(node(depth = 0), node(depth = 2))

        assertEquals(listOf("0", "0/0/0"), NodeStructure.paths(nodes))
    }

    @Test
    fun `an empty observation has no structural paths`() {
        assertEquals(emptyList(), NodeStructure.paths(emptyList()))
    }

    @Test
    fun `collection range tooltip and drawing order default to absent`() {
        val node = node(depth = 0)

        assertEquals(null, node.collection)
        assertEquals(null, node.range)
        assertEquals(null, node.tooltip)
        assertEquals(0, node.drawingOrder)
    }

    @Test
    fun `a node carries collection range tooltip and drawing order metadata`() {
        val collection = CollectionInfo(
            rowCount = 4,
            columnCount = 2,
            hierarchical = true,
            selectionMode = "single",
        )
        val range = RangeInfo(current = 40f, min = 0f, max = 100f, type = "percent")
        val node = node(depth = 0).copy(
            collection = collection,
            range = range,
            tooltip = "Battery level",
            drawingOrder = 7,
        )

        assertEquals(collection, node.collection)
        assertEquals(range, node.range)
        assertEquals("Battery level", node.tooltip)
        assertEquals(7, node.drawingOrder)
    }

    private fun node(depth: Int) = DeviceNode(
        role = "text",
        text = "node",
        contentDescription = null,
        viewId = null,
        bounds = Bounds(0, 0, 1, 1),
        states = emptySet(),
        actions = emptyList(),
        hint = null,
        paneTitle = null,
        windowId = 0,
        depth = depth,
    )
}
