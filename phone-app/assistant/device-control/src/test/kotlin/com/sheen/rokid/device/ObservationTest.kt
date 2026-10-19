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
