package com.sheen.rokid.device

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * One node with Task 7's full field set, so the kernel/planner tests only state the fields they
 * care about. [hint], [paneTitle], [collection], [range], [tooltip] and [drawingOrder] stay at
 * their defaults: they are observation metadata the kernel only compares, never interprets.
 */
internal fun testNode(
    role: String = "button",
    text: String? = "Wi-Fi",
    description: String? = null,
    viewId: String? = "android:id/title",
    bounds: Bounds = Bounds(10, 20, 300, 80),
    states: Set<String> = setOf("clickable", "enabled"),
    actions: List<String> = listOf("click"),
    windowId: Int = 0,
    depth: Int = 0,
): DeviceNode = DeviceNode(
    role = role,
    text = text,
    contentDescription = description,
    viewId = viewId,
    bounds = bounds,
    states = states,
    actions = actions,
    hint = null,
    paneTitle = null,
    windowId = windowId,
    depth = depth,
)

/** A snapshot with the revision the producer supplies; the kernel stamps its own revision. */
internal fun testObservation(
    nodes: List<DeviceNode> = listOf(testNode()),
    windows: List<ObservationWindow> = emptyList(),
    truncated: Boolean = false,
): Observation = Observation(revision = 0L, windows = windows, nodes = nodes, truncated = truncated)

internal fun testWindow(
    id: Int = 0,
    type: String = "application",
    layer: Int = 0,
    bounds: Bounds = Bounds(0, 0, 1000, 2000),
    packageName: String? = "com.example.app",
    title: String? = null,
): ObservationWindow = ObservationWindow(id, type, layer, bounds, packageName, title)

/**
 * A scriptable accessibility channel.
 *
 * Every call is appended to [calls] in arrival order, which is how the tests prove that a mutating
 * action is followed by an observation. Write callbacks ([onNodeAction], [onGesture]) run inside
 * the write, so a test can make the action change [screen] or block; [maxConcurrentWrites] proves
 * the kernel never lets two writes overlap.
 */
internal class FakeAccessibilityPort : AccessibilityPort {

    val calls = CopyOnWriteArrayList<String>()
    val filters = CopyOnWriteArrayList<ScreenFilter>()
    val rawNodeActions = CopyOnWriteArrayList<String>()

    @Volatile var bound = true
    @Volatile var interactive = true
    @Volatile var keyguardLocked = false
    @Volatile var screen: Observation = testObservation()
    @Volatile var nodeActionResult = true
    @Volatile var gestureResult = true
    @Volatile var screenshotResult: Screenshot? = Screenshot(byteArrayOf(1, 2, 3), "image/jpeg", 768, 1664)
    @Volatile var foregroundResult = Foreground("com.example.app", ".MainActivity")
    @Volatile var onObserve: (() -> Unit)? = null
    @Volatile var onNodeAction: ((Ref, String) -> Unit)? = null
    @Volatile var onGesture: ((Gesture) -> Unit)? = null
    @Volatile var onIsInteractive: (() -> Unit)? = null

    private val activeWrites = AtomicInteger()
    val maxConcurrentWrites = AtomicInteger()

    override fun isBound(): Boolean = bound

    override fun isInteractive(): Boolean {
        onIsInteractive?.invoke()
        return interactive
    }

    override fun isKeyguardLocked(): Boolean = keyguardLocked

    override fun observe(filter: ScreenFilter): Observation {
        // A real adapter cannot read an unbound service; throwing here means a kernel that forgets
        // its channel gate fails the test instead of silently getting a screen from the fake.
        check(bound) { "the accessibility service is not bound" }
        filters += filter
        calls += "observe:${filter.name}"
        onObserve?.invoke()
        return screen
    }

    override fun performNodeAction(ref: Ref, action: String): Boolean {
        rawNodeActions += action
        return write("node:${ref.value}:${action.replace(DeviceActions.ARGUMENT_SEPARATOR, '|')}") {
            onNodeAction?.invoke(ref, action)
            nodeActionResult
        }
    }

    override fun dispatchGesture(gesture: Gesture): Boolean = write("gesture") {
        onGesture?.invoke(gesture)
        gestureResult
    }

    override fun screenshot(): Screenshot? {
        calls += "screenshot"
        return screenshotResult
    }

    override fun foreground(): Foreground {
        calls += "foreground"
        return foregroundResult
    }

    fun resetCalls() {
        calls.clear()
        filters.clear()
        rawNodeActions.clear()
    }

    fun nodeActions(): List<String> = calls.filter { it.startsWith("node:") }

    private fun write(label: String, block: () -> Boolean): Boolean {
        val active = activeWrites.incrementAndGet()
        maxConcurrentWrites.accumulateAndGet(active) { current, next -> if (current >= next) current else next }
        try {
            calls += label
            return block()
        } finally {
            activeWrites.decrementAndGet()
        }
    }
}

internal data class ShellCall(val command: String, val timeoutMillis: Long, val maxBytes: Int)

/** A scriptable Shizuku-style shell channel. */
internal class FakeShellPort : ShellPort {

    val calls = CopyOnWriteArrayList<ShellCall>()

    @Volatile var outcome: ShellOutcome = ShellOutcome(0, "", "", false, null)
    @Volatile var onRun: (() -> Unit)? = null

    override fun run(command: String, timeoutMillis: Long, maxBytes: Int): ShellOutcome {
        calls += ShellCall(command, timeoutMillis, maxBytes)
        onRun?.invoke()
        return outcome
    }
}
