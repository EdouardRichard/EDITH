package com.sheen.rokid.device

import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Action planning: semantic node actions first, a coordinate gesture when only a text target and
 * a bound accessibility channel are available, the shell for key events, and `Unsupported` when
 * none of the routes applies.
 *
 * The planner never takes a [RefRegistry]: a ref is opaque, so a ref-based plan can only be a
 * semantic node action. Coordinate gestures are derived from an observation (text target bounds or
 * a window's screen area), never from model-supplied coordinates.
 */
class ActionPlannerTest {

    private lateinit var accessibility: FakeAccessibilityPort
    private lateinit var shell: FakeShellPort
    private lateinit var planner: ActionPlanner

    @BeforeTest
    fun setUp() {
        accessibility = FakeAccessibilityPort()
        shell = FakeShellPort()
        planner = ActionPlanner(accessibility, shell)
    }

    // ── node actions ────────────────────────────────────────────────────────

    @Test
    fun `a ref on a bound channel plans a semantic node click without reading the screen`() {
        val planned = planner.plan(ActKind.TAP, ref = Ref("#n0"), text = null, value = null, direction = null, key = null)

        val node = assertIs<PlannedAction.Node>(planned)
        assertEquals(Ref("#n0"), node.ref)
        assertEquals("click", node.action)
        assertTrue(node.arguments.isEmpty())
        assertTrue(accessibility.calls.isEmpty(), "a ref needs no lookup: ${accessibility.calls}")
    }

    @Test
    fun `a ref wins over a text target`() {
        accessibility.screen = testObservation(listOf(testNode(text = "Wi-Fi")))

        val planned = planner.plan(ActKind.TAP, ref = Ref("#n7"), text = "Wi-Fi", value = null, direction = null, key = null)

        assertEquals("click", assertIs<PlannedAction.Node>(planned).action)
        assertTrue(accessibility.calls.isEmpty())
    }

    @Test
    fun `long press and double tap keep their distinct node action tokens`() {
        val longPress = planner.plan(ActKind.LONG_PRESS, Ref("#n0"), null, null, null, null)
        val doubleTap = planner.plan(ActKind.DOUBLE_TAP, Ref("#n0"), null, null, null, null)

        assertEquals("long_click", assertIs<PlannedAction.Node>(longPress).action)
        assertEquals("double_click", assertIs<PlannedAction.Node>(doubleTap).action)
    }

    @Test
    fun `typing a value into a ref carries the text as a node action argument`() {
        val planned = planner.plan(ActKind.TYPE, Ref("#n1"), text = null, value = "hello", direction = null, key = null)

        val node = assertIs<PlannedAction.Node>(planned)
        assertEquals("set_text", node.action)
        assertEquals(mapOf("text" to "hello"), node.arguments)
    }

    @Test
    fun `typing falls back to the text parameter when value is absent`() {
        val planned = planner.plan(ActKind.TYPE, Ref("#n1"), text = "hello", value = null, direction = null, key = null)

        assertEquals(mapOf("text" to "hello"), assertIs<PlannedAction.Node>(planned).arguments)
    }

    @Test
    fun `a ref scroll maps the finger direction onto the semantic scroll action`() {
        val forward = planner.plan(ActKind.SCROLL, Ref("#n0"), null, null, "forward", null)
        val up = planner.plan(ActKind.SCROLL, Ref("#n0"), null, null, "up", null)
        val backward = planner.plan(ActKind.SCROLL, Ref("#n0"), null, null, "backward", null)
        val right = planner.plan(ActKind.SCROLL, Ref("#n0"), null, null, "right", null)
        val missing = planner.plan(ActKind.SCROLL, Ref("#n0"), null, null, null, null)

        assertEquals("scroll_forward", assertIs<PlannedAction.Node>(forward).action)
        assertEquals("scroll_forward", assertIs<PlannedAction.Node>(up).action)
        assertEquals("scroll_backward", assertIs<PlannedAction.Node>(backward).action)
        assertEquals("scroll_backward", assertIs<PlannedAction.Node>(right).action)
        assertEquals("scroll_forward", assertIs<PlannedAction.Node>(missing).action, "a missing direction defaults to forward")
    }

    // ── text and swipe gestures ─────────────────────────────────────────────

    @Test
    fun `a text target falls back to a gesture at the nearest clickable ancestor`() {
        accessibility.screen = testObservation(
            listOf(
                testNode(
                    role = "list",
                    text = null,
                    viewId = null,
                    bounds = Bounds(0, 0, 400, 100),
                    states = setOf("clickable"),
                    actions = listOf("click"),
                    depth = 0,
                ),
                testNode(
                    role = "text",
                    text = "Wi-Fi",
                    viewId = null,
                    bounds = Bounds(10, 10, 200, 50),
                    states = emptySet(),
                    actions = emptyList(),
                    depth = 1,
                ),
            ),
        )

        val planned = planner.plan(ActKind.TAP, ref = null, text = "Wi-Fi", value = null, direction = null, key = null)

        val stroke = assertIs<PlannedAction.GestureAction>(planned).gesture.strokes.single()
        assertEquals(listOf(Point(200, 50)), stroke.points, "the label is not clickable; its clickable ancestor is")
        assertEquals(0L, stroke.startTimeMillis)
        assertTrue(stroke.durationMillis in 1..499, "a tap is a short stroke: ${stroke.durationMillis}")
        assertEquals(listOf(ScreenFilter.ALL), accessibility.filters, "the ancestor walk needs the whole tree")
    }

    @Test
    fun `a long press by text is one long stroke and a double tap is two strokes`() {
        accessibility.screen = testObservation(listOf(testNode(text = "Wi-Fi", bounds = Bounds(0, 0, 100, 100))))

        val longPress = assertIs<PlannedAction.GestureAction>(
            planner.plan(ActKind.LONG_PRESS, null, "Wi-Fi", null, null, null),
        ).gesture.strokes.single()
        val doubleTap = assertIs<PlannedAction.GestureAction>(
            planner.plan(ActKind.DOUBLE_TAP, null, "Wi-Fi", null, null, null),
        ).gesture.strokes

        assertTrue(longPress.durationMillis >= 500, "a long press must be long: ${longPress.durationMillis}")
        assertEquals(2, doubleTap.size)
        assertEquals(doubleTap[0].points, doubleTap[1].points)
        assertTrue(doubleTap[0].durationMillis < 500)
        assertTrue(doubleTap[1].startTimeMillis > doubleTap[0].startTimeMillis)
    }

    @Test
    fun `a swipe uses the observed window area as its screen coordinates`() {
        accessibility.screen = testObservation(
            nodes = listOf(testNode()),
            windows = listOf(testWindow(bounds = Bounds(0, 0, 1000, 2000))),
        )

        val up = assertIs<PlannedAction.GestureAction>(
            planner.plan(ActKind.SWIPE, null, null, null, "up", null),
        ).gesture.strokes.single()
        val right = assertIs<PlannedAction.GestureAction>(
            planner.plan(ActKind.SWIPE, null, null, null, "right", null),
        ).gesture.strokes.single()

        assertEquals(listOf(Point(500, 1500), Point(500, 500)), up.points)
        assertEquals(0L, up.startTimeMillis)
        assertTrue(up.durationMillis > 0)
        assertEquals(listOf(Point(250, 1000), Point(750, 1000)), right.points)
        assertTrue(
            accessibility.filters.all { it == ScreenFilter.ALL },
            "screen coordinates come from a full observation: ${accessibility.filters}",
        )
    }

    @Test
    fun `scroll without a ref falls back to the same screen swipe`() {
        accessibility.screen = testObservation(
            nodes = listOf(testNode()),
            windows = listOf(testWindow(bounds = Bounds(0, 0, 1000, 2000))),
        )

        val backward = assertIs<PlannedAction.GestureAction>(
            planner.plan(ActKind.SCROLL, null, null, null, "backward", null),
        ).gesture.strokes.single()

        assertEquals(listOf(Point(500, 500), Point(500, 1500)), backward.points)
    }

    // ── shell keys ──────────────────────────────────────────────────────────

    @Test
    fun `press key and system keys plan shell key events`() {
        assertEquals("KEYCODE_HOME", shellKey(planner.plan(ActKind.PRESS_KEY, null, null, null, null, "home")))
        assertEquals("KEYCODE_BACK", shellKey(planner.plan(ActKind.PRESS_KEY, null, null, null, null, "KEYCODE_BACK")))
        assertEquals("26", shellKey(planner.plan(ActKind.PRESS_KEY, null, null, null, null, "26")))
        assertEquals("KEYCODE_HOME", shellKey(planner.plan(ActKind.HOME, null, null, null, null, null)))
        assertEquals("KEYCODE_BACK", shellKey(planner.plan(ActKind.BACK, null, null, null, null, null)))
        assertEquals("KEYCODE_APP_SWITCH", shellKey(planner.plan(ActKind.RECENTS, null, null, null, null, null)))
        assertEquals(
            "KEYCODE_NOTIFICATION",
            shellKey(planner.plan(ActKind.NOTIFICATIONS, null, null, null, null, null)),
        )
    }

    @Test
    fun `system keys cannot be planned without a shell`() {
        val noShell = ActionPlanner(accessibility, null)

        assertEquals(PlannedAction.Unsupported, noShell.plan(ActKind.PRESS_KEY, null, null, null, null, "home"))
        assertEquals(PlannedAction.Unsupported, noShell.plan(ActKind.HOME, null, null, null, null, null))
        assertEquals(
            "click",
            assertIs<PlannedAction.Node>(noShell.plan(ActKind.TAP, Ref("#n0"), null, null, null, null)).action,
            "the accessibility route must keep working without a shell",
        )
    }

    @Test
    fun `a key event is still planable while the accessibility channel is unbound`() {
        accessibility.bound = false

        assertEquals("KEYCODE_HOME", shellKey(planner.plan(ActKind.PRESS_KEY, null, null, null, null, "home")))
    }

    @Test
    fun `keys with shell metacharacters are unsupported`() {
        val hostile = listOf("HOME; \$0", "HOME && reboot", "KEYCODE_HOME;input tap 1 1", "\$(reboot)", "HOME\nHOME")

        hostile.forEach { key ->
            assertEquals(
                PlannedAction.Unsupported,
                planner.plan(ActKind.PRESS_KEY, null, null, null, null, key),
                "a key is interpolated into a shell command, so metacharacters must be refused: $key",
            )
        }
    }

    @Test
    fun `typing a value containing the separator is unsupported`() {
        assertEquals(
            PlannedAction.Unsupported,
            planner.plan(ActKind.TYPE, Ref("#n1"), null, "a\u0000b", null, null),
        )
    }

    // ── unsupported inputs ──────────────────────────────────────────────────

    @Test
    fun `visual actions are unsupported while the accessibility channel is unbound`() {
        accessibility.bound = false

        assertEquals(PlannedAction.Unsupported, planner.plan(ActKind.TAP, Ref("#n0"), null, null, null, null))
        assertEquals(PlannedAction.Unsupported, planner.plan(ActKind.TAP, null, "Wi-Fi", null, null, null))
        assertEquals(PlannedAction.Unsupported, planner.plan(ActKind.TYPE, Ref("#n0"), null, "hi", null, null))
    }

    @Test
    fun `a text target that is not on screen is unsupported`() {
        assertEquals(
            PlannedAction.Unsupported,
            planner.plan(ActKind.TAP, null, "Bluetooth", null, null, null),
        )
    }

    @Test
    fun `a tap without a target, a type without a ref, an untargeted swipe and a wait are unsupported`() {
        assertEquals(PlannedAction.Unsupported, planner.plan(ActKind.TAP, null, null, null, null, null))
        assertEquals(PlannedAction.Unsupported, planner.plan(ActKind.TYPE, null, "hello", null, null, null))
        assertEquals(PlannedAction.Unsupported, planner.plan(ActKind.SWIPE, null, null, null, null, null))
        assertEquals(PlannedAction.Unsupported, planner.plan(ActKind.WAIT, null, null, null, null, null))
        assertEquals(PlannedAction.Unsupported, planner.plan(ActKind.PRESS_KEY, null, null, null, null, " "))
    }

    private fun shellKey(planned: PlannedAction): String = assertIs<PlannedAction.ShellKey>(planned).key
}
