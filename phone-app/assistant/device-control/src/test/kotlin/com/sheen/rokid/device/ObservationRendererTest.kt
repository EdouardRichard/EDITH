package com.sheen.rokid.device

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Render contract: one line per visible node in the spec §4.4 order
 * (`#ref role "text" id=... bounds=[l,t,r,b] states=[...] actions=[...]`), window sections for a
 * multi-window observation, a hard byte/line budget cut on line boundaries, and an explicit
 * `truncated: true` marker whenever the observation or the render was cut.
 */
class ObservationRendererTest {

    @Test
    fun `a node line carries ref role text id bounds states and actions in the documented order`() {
        val observation = observation(nodes = listOf(node()))

        val output = ObservationRenderer().render(observation, ScreenFilter.ALL)

        val line = output.lines().single { it.contains("\"Wi-Fi\"") }
        assertTrue(
            Regex(
                """^#n\d+ button "Wi-Fi" id=android:id/title bounds=\[10,20,300,80\] """ +
                    """states=\[clickable,focused\] actions=\[click,long_click]$""",
            ).matches(line),
            "unexpected node line: $line",
        )
    }

    @Test
    fun `a node without text still renders its role bounds states and actions`() {
        val edit = node(
            role = "edit",
            text = null,
            viewId = null,
            states = setOf("editable"),
            actions = listOf("set_text"),
            bounds = Bounds(40, 200, 1000, 260),
        )

        val output = ObservationRenderer().render(observation(nodes = listOf(edit)), ScreenFilter.ALL)

        val line = output.lines().single { it.startsWith("#n") }
        assertTrue(
            Regex(
                """^#n\d+ edit bounds=\[40,200,1000,260\] states=\[editable\] actions=\[set_text]$""",
            ).matches(line),
            "unexpected node line: $line",
        )
    }

    @Test
    fun `a text node stays on one line and quotes are escaped`() {
        val observation = observation(nodes = listOf(node(text = "Line1\nLine2 \"quoted\" \\ tail")))

        val output = ObservationRenderer().render(observation, ScreenFilter.ALL)

        assertEquals(1, output.lines().count { it.contains("Line1") })
        val line = output.lines().first { it.contains("Line1") }
        assertTrue(line.contains("\"Line1 Line2 \\\"quoted\\\" \\\\ tail\""), "unexpected line: $line")
    }

    @Test
    fun `nodes from different windows are grouped under their window section`() {
        val nodes = listOf(
            node(text = "First", windowId = 0),
            node(text = "Second", windowId = 1),
            node(text = "Third", windowId = 0),
        )
        val windows = listOf(
            window(id = 0, packageName = "com.android.settings", title = "Settings"),
            window(id = 1, type = "ime", packageName = null, title = null),
        )

        val output = ObservationRenderer().render(observation(nodes = nodes, windows = windows), ScreenFilter.ALL)
        val lines = output.lines()

        val firstHeader = lines.indexOfFirst { it.startsWith("window=0 ") }
        val secondHeader = lines.indexOfFirst { it.startsWith("window=1 ") }
        val firstNode = lines.indexOfFirst { it.contains("\"First\"") }
        val secondNode = lines.indexOfFirst { it.contains("\"Second\"") }
        val thirdNode = lines.indexOfFirst { it.contains("\"Third\"") }
        assertTrue(firstHeader in 0 until firstNode, "window 0 header must precede its nodes: $lines")
        assertTrue(thirdNode < secondHeader, "window 0 nodes must stay in their section: $lines")
        assertTrue(secondHeader < secondNode, "window 1 header must precede its node: $lines")
        assertTrue(lines[firstHeader].contains("pkg=com.android.settings"))
        assertTrue(lines[firstHeader].contains("title=\"Settings\""))
        assertTrue(lines[secondHeader].contains("type=ime"))
    }

    @Test
    fun `the interactive filter keeps actionable nodes and drops static labels`() {
        val button = node(text = "Wi-Fi")
        val edit = node(
            role = "edit",
            text = null,
            viewId = null,
            states = setOf("editable"),
            actions = listOf("set_text"),
        )
        val label = node(role = "text", text = "Plain label", viewId = null, states = emptySet(), actions = emptyList())
        val described = node(role = "text", text = null, desc = "Search", viewId = null, states = emptySet(), actions = emptyList())
        val observation = observation(nodes = listOf(button, edit, label, described))
        val renderer = ObservationRenderer()

        val interactive = renderer.render(observation, ScreenFilter.INTERACTIVE).nodeLines()
        val text = renderer.render(observation, ScreenFilter.TEXT).nodeLines()
        val all = renderer.render(observation, ScreenFilter.ALL).nodeLines()

        assertTrue(interactive.any { it.contains("\"Wi-Fi\"") })
        assertTrue(interactive.any { it.startsWith("#n") && it.contains(" edit ") })
        assertFalse(interactive.any { it.contains("Plain label") })
        assertFalse(interactive.any { it.contains("desc=\"Search\"") })

        assertTrue(text.any { it.contains("Plain label") })
        assertTrue(text.any { it.contains("desc=\"Search\"") })
        assertFalse(text.any { it.contains(" edit ") })

        assertTrue(all.any { it.contains("\"Wi-Fi\"") })
        assertTrue(all.any { it.contains("Plain label") })
        assertEquals(4, all.size)
    }

    @Test
    fun `the output is cut on line boundaries and says it is truncated when over budget`() {
        val nodes = (0 until 60).map {
            node(role = "text", text = "Node-$it", viewId = null, states = emptySet(), actions = emptyList())
        }
        val observation = observation(nodes = nodes)

        val output = ObservationRenderer().render(observation, ScreenFilter.ALL, budgetBytes = 300, maxLines = 12)

        assertTrue(
            output.toByteArray(Charsets.UTF_8).size <= 300,
            "output must respect the byte budget: ${output.toByteArray(Charsets.UTF_8).size}",
        )
        assertTrue(output.trimEnd().lines().size <= 12, "output must respect the line budget")
        assertEquals("truncated: true", output.trimEnd().lines().last())
        assertTrue(output.nodeLines().size < 60, "the render must not contain every node")
        assertTrue(output.nodeLines().all { it.endsWith("]") }, "a node line is never cut mid-line")
    }

    @Test
    fun `the byte budget counts UTF-8 bytes rather than characters`() {
        val nodes = (0 until 60).map {
            node(
                role = "text",
                text = "标签-$it",
                viewId = null,
                states = emptySet(),
                actions = emptyList(),
            )
        }

        val output = ObservationRenderer().render(
            observation(nodes = nodes),
            ScreenFilter.ALL,
            budgetBytes = 300,
            maxLines = 400,
        )
        val bytes = output.toByteArray(Charsets.UTF_8)

        assertTrue(bytes.size <= 300, "output must respect the UTF-8 byte budget: ${bytes.size}")
        assertEquals("truncated: true", output.trimEnd().lines().last())
        assertTrue(output.nodeLines().size < 60, "the render must not contain every node")
        assertTrue(output.nodeLines().all { it.endsWith("]") }, "a node line is never cut mid-line")
        assertTrue(output.contains("标签"), "the kept lines must carry the multi-byte text")
        assertTrue(
            bytes.size > output.trimEnd().length,
            "this fixture only cuts on the byte budget when bytes, not characters, are counted",
        )
    }

    @Test
    fun `the line budget alone can cut a render that fits the byte budget`() {
        val nodes = (0 until 60).map {
            node(role = "text", text = "Node-$it", viewId = null, states = emptySet(), actions = emptyList())
        }
        val observation = observation(nodes = nodes)

        val output = ObservationRenderer().render(observation, ScreenFilter.ALL, maxLines = 5)

        assertEquals(5, output.trimEnd().lines().size)
        assertEquals("truncated: true", output.trimEnd().lines().last())
        assertTrue(
            output.toByteArray(Charsets.UTF_8).size < ObservationRenderer.DEFAULT_BUDGET_BYTES,
            "this fixture must be cut by the line budget, not the byte budget",
        )
        assertTrue(output.nodeLines().size < 60)
    }

    @Test
    fun `a truncated observation says so even when its visible nodes fit the budget`() {
        val observation = observation(nodes = listOf(node()), truncated = true)

        val output = ObservationRenderer().render(observation, ScreenFilter.ALL)

        assertEquals("truncated: true", output.trimEnd().lines().last())
    }

    @Test
    fun `refs are stable across renders of the same tree`() {
        val renderer = ObservationRenderer()

        val first = renderer.render(observation(1, listOf(node())), ScreenFilter.ALL)
        val second = renderer.render(observation(2, listOf(node())), ScreenFilter.ALL)

        assertEquals(first, second)
    }

    @Test
    fun `a shared registry makes rendered refs resolvable by the kernel`() {
        val registry = RefRegistry()
        val renderer = ObservationRenderer(registry)
        val observation = observation(nodes = listOf(node()))

        val output = renderer.render(observation, ScreenFilter.ALL)
        val ref = registry.assign(observation).getValue("0")

        assertTrue(output.contains(ref.value))
        assertIs<RefResult.Ok>(registry.resolve(observation, ref))
    }

    @Test
    fun `renderNodes renders only the requested paths in the same line format as render`() {
        val registry = RefRegistry()
        val renderer = ObservationRenderer(registry)
        val observation = observation(
            nodes = listOf(
                node(text = "First"),
                node(text = "Second", viewId = "android:id/second"),
            ),
        )
        val allLines = renderer.render(observation, ScreenFilter.ALL).nodeLines()
        assertEquals(2, allLines.size, "fixture must render one line per node")

        val selected = renderer.renderNodes(observation, listOf("1"))

        assertEquals(allLines[1], selected.trimEnd())
        assertTrue(
            selected.startsWith(registry.assign(observation).getValue("1").value),
            "the node line must carry the shared registry's ref",
        )
        assertEquals("", renderer.renderNodes(observation, emptyList()))
    }

    @Test
    fun `renderNodes of several paths preserves the observation order`() {
        val renderer = ObservationRenderer()
        val observation = observation(
            nodes = listOf(
                node(text = "First"),
                node(text = "Second", viewId = "android:id/second"),
                node(text = "Third", viewId = "android:id/third"),
            ),
        )

        val output = renderer.renderNodes(observation, setOf("2", "0"))

        val lines = output.trimEnd().lines()
        assertEquals(2, lines.size)
        assertTrue(lines[0].contains("\"First\""), "unexpected line: ${lines[0]}")
        assertTrue(lines[1].contains("\"Third\""), "unexpected line: ${lines[1]}")
    }

    private fun String.nodeLines(): List<String> = lines().filter { it.startsWith("#n") }

    private fun node(
        role: String = "button",
        text: String? = "Wi-Fi",
        desc: String? = null,
        viewId: String? = "android:id/title",
        bounds: Bounds = Bounds(10, 20, 300, 80),
        states: Set<String> = setOf("clickable", "focused"),
        actions: List<String> = listOf("click", "long_click"),
        windowId: Int = 0,
    ) = DeviceNode(
        role = role,
        text = text,
        contentDescription = desc,
        viewId = viewId,
        bounds = bounds,
        states = states,
        actions = actions,
        hint = null,
        paneTitle = null,
        windowId = windowId,
        depth = 0,
    )

    private fun window(
        id: Int,
        type: String = "application",
        packageName: String? = "com.example",
        title: String? = "Demo",
    ) = ObservationWindow(
        id = id,
        type = type,
        layer = 0,
        bounds = Bounds(0, 0, 1080, 2400),
        packageName = packageName,
        title = title,
    )

    private fun observation(
        revision: Long = 1,
        nodes: List<DeviceNode>,
        windows: List<ObservationWindow> = listOf(window(id = 0)),
        truncated: Boolean = false,
    ) = Observation(
        revision = revision,
        windows = windows,
        nodes = nodes,
        truncated = truncated,
    )
}
