package com.sheen.rokid.phone.actions.a11y

import com.sheen.rokid.device.Bounds
import com.sheen.rokid.device.CollectionInfo
import com.sheen.rokid.device.DeviceActions
import com.sheen.rokid.device.Gesture
import com.sheen.rokid.device.ObservationWindow
import com.sheen.rokid.device.Point
import com.sheen.rokid.device.RangeInfo
import com.sheen.rokid.device.Ref
import com.sheen.rokid.device.RefRegistry
import com.sheen.rokid.device.ScreenFilter as DeviceScreenFilter
import com.sheen.rokid.device.Stroke
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The platform-neutral half of the 0.25 Task 9 accessibility adapter.
 *
 * A fake [AccessibilityPlatform] stands in for the Android service, so these tests run as plain
 * JVM tests and pin the mapping contract (R38), the "collect everything, filter at render time"
 * rule (R37) and the ref/action contract shared with the kernel (R36).
 */
class AccessibilityAdapterTest {

    // ── Fake platform ────────────────────────────────────────────────────────

    private class FakePlatform : AccessibilityPlatform {
        var windows: List<PlatformWindow> = emptyList()
        var truncated: Boolean = false
        var clickResult: Boolean = true
        var gestureResult: Boolean = true

        val clicks = mutableListOf<Int>()
        val longClicks = mutableListOf<Int>()
        val scrolls = mutableListOf<Pair<Int, Boolean>>()
        val setTexts = mutableListOf<Pair<Int, String>>()
        val gestures = mutableListOf<Gesture>()

        override fun collect(maxNodes: Int): PlatformSnapshot = PlatformSnapshot(windows, truncated)

        override fun click(nodeId: Int): Boolean {
            clicks += nodeId
            return clickResult
        }

        override fun longClick(nodeId: Int): Boolean {
            longClicks += nodeId
            return true
        }

        override fun scrollForward(nodeId: Int): Boolean {
            scrolls += nodeId to true
            return true
        }

        override fun scrollBackward(nodeId: Int): Boolean {
            scrolls += nodeId to false
            return true
        }

        override fun setText(nodeId: Int, text: String): Boolean {
            setTexts += nodeId to text
            return true
        }

        override fun dispatchGesture(gesture: Gesture): Boolean {
            gestures += gesture
            return gestureResult
        }
    }

    // ── Builders ─────────────────────────────────────────────────────────────

    private fun window(
        id: Int = 1,
        type: Int = TYPE_APPLICATION,
        layer: Int = 0,
        bounds: Bounds = Bounds(0, 0, 1080, 2400),
        packageName: String? = null,
        title: String? = null,
        nodes: List<PlatformNode> = emptyList(),
    ): PlatformWindow = PlatformWindow(
        id = id,
        type = type,
        layer = layer,
        bounds = bounds,
        packageName = packageName,
        title = title,
        nodes = nodes,
    )

    private fun node(
        nodeId: Int = 0,
        windowId: Int = 1,
        depth: Int = 0,
        className: String? = null,
        text: String? = null,
        contentDescription: String? = null,
        viewIdResourceName: String? = null,
        bounds: Bounds = Bounds(0, 0, 100, 100),
        clickable: Boolean = false,
        longClickable: Boolean = false,
        editable: Boolean = false,
        scrollable: Boolean = false,
        checkable: Boolean = false,
        checked: Boolean = false,
        selected: Boolean = false,
        enabled: Boolean = true,
        focused: Boolean = false,
        hintText: String? = null,
        paneTitle: String? = null,
        tooltipText: String? = null,
        drawingOrder: Int = 0,
        actionIds: List<Int> = emptyList(),
        collection: PlatformCollectionInfo? = null,
        range: PlatformRangeInfo? = null,
        visibleToUser: Boolean = true,
    ): PlatformNode = PlatformNode(
        nodeId = nodeId,
        windowId = windowId,
        depth = depth,
        className = className,
        text = text,
        contentDescription = contentDescription,
        viewIdResourceName = viewIdResourceName,
        bounds = bounds,
        clickable = clickable,
        longClickable = longClickable,
        editable = editable,
        scrollable = scrollable,
        checkable = checkable,
        checked = checked,
        selected = selected,
        enabled = enabled,
        focused = focused,
        hintText = hintText,
        paneTitle = paneTitle,
        tooltipText = tooltipText,
        drawingOrder = drawingOrder,
        actionIds = actionIds,
        collection = collection,
        range = range,
        visibleToUser = visibleToUser,
    )

    private fun adapter(platform: FakePlatform, registry: RefRegistry = RefRegistry()) =
        AccessibilityAdapter(platform, registry)

    // ── Observation shape ───────────────────────────────────────────────────

    @Test
    fun observeReturnsEveryWindowAndNodeAndIgnoresTheCollectionFilter() {
        val platform = FakePlatform().apply {
            windows = listOf(
                window(
                    id = 1,
                    type = TYPE_APPLICATION,
                    layer = 4,
                    packageName = "com.example.app",
                    title = "Example",
                    nodes = listOf(
                        // A container that matches no interactive trait: it only survives when the
                        // producer collects the full tree (R37).
                        node(nodeId = 10, windowId = 1, depth = 0, className = "android.widget.FrameLayout"),
                        node(nodeId = 11, windowId = 1, depth = 1, className = "android.widget.Button", text = "OK", clickable = true),
                    ),
                ),
                window(
                    id = 2,
                    type = TYPE_INPUT_METHOD,
                    packageName = "com.android.inputmethod.latin",
                    nodes = listOf(
                        node(nodeId = 20, windowId = 2, depth = 0, className = "android.widget.EditText", editable = true),
                    ),
                ),
            )
        }
        val observation = adapter(platform).observe(DeviceScreenFilter.INTERACTIVE)

        assertEquals(listOf("application", "ime"), observation.windows.map { it.type })
        assertEquals(listOf(1, 2), observation.windows.map { it.id })
        assertEquals(
            observationOfWindow(
                id = 1,
                type = "application",
                layer = 4,
                packageName = "com.example.app",
                title = "Example",
            ),
            observation.windows[0],
        )
        assertEquals(3, observation.nodes.size)
        assertEquals(listOf(1, 1, 2), observation.nodes.map { it.windowId })
        assertEquals(listOf(0, 1, 0), observation.nodes.map { it.depth })
        assertEquals(0L, observation.revision)
        assertFalse(observation.truncated)
    }

    @Test
    fun observeMapsEveryWindowTypeToItsToken() {
        val platform = FakePlatform().apply {
            windows = listOf(1, 2, 3, 4, 99).mapIndexed { index, type ->
                window(id = index + 1, type = type)
            }
        }
        val observation = adapter(platform).observe(DeviceScreenFilter.ALL)

        assertEquals(
            listOf("application", "ime", "system", "accessibility_overlay", "unknown"),
            observation.windows.map { it.type },
        )
    }

    // ── Field mapping (R38) ──────────────────────────────────────────────────

    @Test
    fun observeMapsPlatformNodeFieldsOneToOne() {
        val bounds = Bounds(10, 20, 300, 400)
        val platform = FakePlatform().apply {
            windows = listOf(
                window(
                    id = 7,
                    nodes = listOf(
                        node(
                            nodeId = 3,
                            windowId = 7,
                            depth = 0,
                            className = "android.widget.SeekBar",
                            text = "50%",
                            contentDescription = "Volume",
                            viewIdResourceName = "com.example:id/seek",
                            bounds = bounds,
                            clickable = true,
                            longClickable = true,
                            editable = true,
                            scrollable = true,
                            checkable = true,
                            checked = true,
                            selected = true,
                            enabled = true,
                            focused = true,
                            hintText = "hint",
                            paneTitle = "pane",
                            tooltipText = "tip",
                            drawingOrder = 3,
                            actionIds = listOf(16),
                            collection = PlatformCollectionInfo(3, 2, true, 1),
                            range = PlatformRangeInfo(5f, 0f, 10f, 1),
                        ),
                        // Everything the platform does not report stays null/empty — never invented.
                        node(
                            nodeId = 4,
                            windowId = 7,
                            depth = 1,
                            enabled = false,
                        ),
                    ),
                ),
            )
        }
        val observation = adapter(platform).observe(DeviceScreenFilter.ALL)
        val mapped = observation.nodes[0]

        assertEquals("seekbar", mapped.role)
        assertEquals("50%", mapped.text)
        assertEquals("Volume", mapped.contentDescription)
        assertEquals("com.example:id/seek", mapped.viewId)
        assertEquals(bounds, mapped.bounds)
        assertEquals(
            setOf("clickable", "long_clickable", "editable", "scrollable", "checkable", "checked", "selected", "enabled", "focused"),
            mapped.states,
        )
        assertEquals(listOf("click"), mapped.actions)
        assertEquals("hint", mapped.hint)
        assertEquals("pane", mapped.paneTitle)
        assertEquals("tip", mapped.tooltip)
        assertEquals(3, mapped.drawingOrder)
        assertEquals(7, mapped.windowId)
        assertEquals(0, mapped.depth)
        assertEquals(CollectionInfo(3, 2, hierarchical = true, selectionMode = "single"), mapped.collection)
        assertEquals(RangeInfo(5f, 0f, 10f, "float"), mapped.range)

        val bare = observation.nodes[1]
        assertEquals("unknown", bare.role)
        assertNull(bare.text)
        assertNull(bare.contentDescription)
        assertNull(bare.viewId)
        assertTrue(bare.states.isEmpty())
        assertTrue(bare.actions.isEmpty())
        assertNull(bare.hint)
        assertNull(bare.paneTitle)
        assertNull(bare.tooltip)
        assertEquals(0, bare.drawingOrder)
        assertNull(bare.collection)
        assertNull(bare.range)
    }

    @Test
    fun observeCarriesThePlatformIsVisibleToUserFlagOnEveryNode() {
        val platform = FakePlatform().apply {
            windows = listOf(
                window(
                    nodes = listOf(
                        node(nodeId = 0, text = "可见"),
                        node(nodeId = 1, depth = 1, text = "不可见", visibleToUser = false),
                    ),
                ),
            )
        }

        val nodes = adapter(platform).observe(DeviceScreenFilter.ALL).nodes

        assertEquals(listOf(true, false), nodes.map { it.visibleToUser })
        assertTrue("the additive default keeps pre-0.3 producers visible", node().visibleToUser)
    }

    @Test
    fun observeMapsEveryDocumentedPlatformActionIdAndKeepsUnknownOnes() {
        val actionIds = listOf(
            16, 32, 2097152, 4096, 8192, 262144, 524288, 1048576,
            4, 8, 1, 2, 16384, 32768, 65536, 131072, 77,
        )
        val platform = FakePlatform().apply {
            windows = listOf(window(nodes = listOf(node(actionIds = actionIds))))
        }
        val mapped = adapter(platform).observe(DeviceScreenFilter.ALL).nodes.single()

        assertEquals(
            listOf(
                "click", "long_click", "set_text", "scroll_forward", "scroll_backward",
                "expand", "collapse", "dismiss", "select", "clear_selection", "focus",
                "clear_focus", "scroll_up", "scroll_down", "scroll_left", "scroll_right",
                "action_77",
            ),
            mapped.actions,
        )
    }

    @Test
    fun observeMapsCollectionAndRangeEnumsOrNull() {
        val platform = FakePlatform().apply {
            windows = listOf(
                window(
                    nodes = listOf(
                        node(
                            nodeId = 0,
                            collection = PlatformCollectionInfo(1, 1, false, 0),
                            range = PlatformRangeInfo(1f, 0f, 2f, 0),
                        ),
                        node(
                            nodeId = 1,
                            collection = PlatformCollectionInfo(1, 1, false, 2),
                            range = PlatformRangeInfo(1f, 0f, 2f, 2),
                        ),
                        node(
                            nodeId = 2,
                            collection = PlatformCollectionInfo(1, 1, false, 7),
                            range = PlatformRangeInfo(1f, 0f, 2f, 7),
                        ),
                    ),
                ),
            )
        }
        val nodes = adapter(platform).observe(DeviceScreenFilter.ALL).nodes

        assertEquals("none", nodes[0].collection?.selectionMode)
        assertEquals("int", nodes[0].range?.type)
        assertEquals("multiple", nodes[1].collection?.selectionMode)
        assertEquals("percent", nodes[1].range?.type)
        assertNull(nodes[2].collection?.selectionMode)
        assertNull(nodes[2].range?.type)
    }

    // ── Refs and actions (R33/R36) ───────────────────────────────────────────

    @Test
    fun refsStayStableAcrossObservationsAndClickReachesTheNode() {
        val registry = RefRegistry()
        val platform = FakePlatform().apply {
            windows = listOf(
                window(
                    nodes = listOf(
                        node(
                            nodeId = 42,
                            className = "android.widget.Button",
                            text = "OK",
                            clickable = true,
                            actionIds = listOf(16),
                        ),
                    ),
                ),
            )
        }
        val adapter = adapter(platform, registry)

        val firstRefs = registry.assign(adapter.observe(DeviceScreenFilter.ALL))
        val secondRefs = registry.assign(adapter.observe(DeviceScreenFilter.ALL))

        assertEquals(firstRefs.values.toList(), secondRefs.values.toList())
        assertTrue(adapter.performNodeAction(secondRefs.values.single(), DeviceActions.CLICK))
        assertEquals(listOf(42), platform.clicks)
    }

    @Test
    fun aNonClickableLabelFallsBackToATapAtItsCentre() {
        val registry = RefRegistry()
        val platform = FakePlatform().apply {
            windows = listOf(
                window(
                    nodes = listOf(
                        node(
                            nodeId = 65,
                            className = "android.widget.TextView",
                            text = "播放",
                            clickable = false,
                            bounds = Bounds(100, 100, 300, 200),
                        ),
                    ),
                ),
            )
            clickResult = false
        }
        val adapter = adapter(platform, registry)
        val ref = registry.assign(adapter.observe(DeviceScreenFilter.ALL)).values.single()

        assertTrue(
            "the model often targets a text child; tapping its centre must reach the clickable parent",
            adapter.performNodeAction(ref, DeviceActions.CLICK),
        )
        assertEquals(listOf(65), platform.clicks)
        assertEquals(1, platform.gestures.size)
        assertEquals(listOf(Point(200, 150)), platform.gestures.single().strokes.single().points)
    }

    @Test
    fun clickReportsThePlatformRefusal() {
        val registry = RefRegistry()
        val platform = FakePlatform().apply {
            windows = listOf(window(nodes = listOf(node(nodeId = 42, clickable = true))))
            clickResult = false
        }
        val adapter = adapter(platform, registry)
        val ref = registry.assign(adapter.observe(DeviceScreenFilter.ALL)).values.single()

        assertFalse(adapter.performNodeAction(ref, DeviceActions.CLICK))
        assertEquals(listOf(42), platform.clicks)
    }

    @Test
    fun setTextPassesTheDecodedValueVerbatimAndRefusesAMissingArgument() {
        val registry = RefRegistry()
        val platform = FakePlatform().apply {
            windows = listOf(window(nodes = listOf(node(nodeId = 42, editable = true))))
        }
        val adapter = adapter(platform, registry)
        val ref = registry.assign(adapter.observe(DeviceScreenFilter.ALL)).values.single()

        val encoded = DeviceActions.encode(DeviceActions.SET_TEXT, mapOf("text" to "你好=a"))
        assertTrue(adapter.performNodeAction(ref, encoded!!))
        assertEquals(listOf(42 to "你好=a"), platform.setTexts)

        platform.setTexts.clear()
        assertFalse(adapter.performNodeAction(ref, DeviceActions.SET_TEXT))
        assertTrue(platform.setTexts.isEmpty())
    }

    @Test
    fun doubleClickDispatchesOneTwoStrokeGestureAtTheNodeCentreAndNeverClicks() {
        val registry = RefRegistry()
        val platform = FakePlatform().apply {
            windows = listOf(
                window(
                    nodes = listOf(
                        node(nodeId = 7, bounds = Bounds(100, 100, 300, 200), clickable = true),
                    ),
                ),
            )
        }
        val adapter = adapter(platform, registry)
        val ref = registry.assign(adapter.observe(DeviceScreenFilter.ALL)).values.single()

        assertTrue(adapter.performNodeAction(ref, DeviceActions.DOUBLE_CLICK))

        assertEquals(1, platform.gestures.size)
        val strokes = platform.gestures.single().strokes
        assertEquals(2, strokes.size)
        assertEquals(listOf(0L, 120L), strokes.map { it.startTimeMillis })
        assertEquals(listOf(50L, 50L), strokes.map { it.durationMillis })
        assertEquals(listOf(Point(200, 150)), strokes[0].points)
        assertEquals(listOf(Point(200, 150)), strokes[1].points)
        assertTrue(platform.clicks.isEmpty())
        assertTrue(platform.setTexts.isEmpty())
    }

    @Test
    fun unknownActionOrUnknownRefReturnsFalseWithoutTouchingThePlatform() {
        val unobserved = adapter(FakePlatform())
        assertFalse(unobserved.performNodeAction(Ref("#n0"), DeviceActions.CLICK))

        val registry = RefRegistry()
        val platform = FakePlatform().apply {
            windows = listOf(window(nodes = listOf(node(nodeId = 42, clickable = true))))
        }
        val adapter = adapter(platform, registry)
        val ref = registry.assign(adapter.observe(DeviceScreenFilter.ALL)).values.single()

        assertFalse(adapter.performNodeAction(ref, "teleport"))
        assertFalse(adapter.performNodeAction(Ref("#n999"), DeviceActions.CLICK))

        assertTrue(platform.clicks.isEmpty())
        assertTrue(platform.longClicks.isEmpty())
        assertTrue(platform.scrolls.isEmpty())
        assertTrue(platform.setTexts.isEmpty())
        assertTrue(platform.gestures.isEmpty())
    }

    @Test
    fun rebindsARefWhoseNodeTextChangedAfterTheNextObservation() {
        val registry = RefRegistry()
        val platform = FakePlatform()
        var nodeId = 9
        fun render(text: String) {
            platform.windows = listOf(
                window(
                    nodes = listOf(
                        node(
                            nodeId = nodeId,
                            className = "android.widget.TextView",
                            text = text,
                            viewIdResourceName = "com.example:id/title",
                            bounds = Bounds(0, 0, 500, 100),
                            clickable = true,
                        ),
                    ),
                ),
            )
        }

        render(text = "before")
        val adapter = adapter(platform, registry)
        val firstRef = registry.assign(adapter.observe(DeviceScreenFilter.ALL)).values.single()

        // Same view id, role, path and bounds; only the text changed. The platform re-collect also
        // hands out a new transient node id, so a stale id map would be caught here.
        nodeId = 10
        render(text = "after")
        adapter.observe(DeviceScreenFilter.ALL)

        assertTrue(adapter.performNodeAction(firstRef, DeviceActions.CLICK))
        assertEquals(listOf(10), platform.clicks)
    }

    @Test
    fun dispatchGestureDelegatesToThePlatform() {
        val platform = FakePlatform()
        val gesture = Gesture(listOf(Stroke(listOf(Point(10, 20)), 0L, 50L)))

        assertTrue(adapter(platform).dispatchGesture(gesture))
        assertEquals(listOf(gesture), platform.gestures)
    }

    // ── Bounds (R37) ─────────────────────────────────────────────────────────

    @Test
    fun observeCapsTheWholeCollectionAtFourHundredNodes() {
        val platform = FakePlatform().apply {
            windows = listOf(
                window(nodes = (0 until 401).map { node(nodeId = it, windowId = 1, depth = 0) }),
            )
        }
        val observation = adapter(platform).observe(DeviceScreenFilter.ALL)

        assertEquals(400, observation.nodes.size)
        assertTrue(observation.truncated)
    }

    @Test
    fun observeKeepsThePlatformTruncationFlag() {
        val platform = FakePlatform().apply {
            truncated = true
            windows = listOf(window(nodes = listOf(node(nodeId = 0), node(nodeId = 1, depth = 1))))
        }
        val observation = adapter(platform).observe(DeviceScreenFilter.ALL)

        assertEquals(2, observation.nodes.size)
        assertTrue(observation.truncated)
    }

    private fun observationOfWindow(
        id: Int,
        type: String,
        layer: Int,
        packageName: String?,
        title: String?,
    ) = ObservationWindow(
        id = id,
        type = type,
        layer = layer,
        bounds = Bounds(0, 0, 1080, 2400),
        packageName = packageName,
        title = title,
    )

    private companion object {
        /** Mirror of `AccessibilityWindowInfo.TYPE_*`, kept local so the test pins the integer contract. */
        const val TYPE_APPLICATION = 1
        const val TYPE_INPUT_METHOD = 2
    }
}
