package com.sheen.rokid.phone.actions.a11y

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.KeyguardManager
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.sheen.rokid.device.AccessibilityPort
import com.sheen.rokid.device.Bounds
import com.sheen.rokid.device.Foreground
import com.sheen.rokid.device.Gesture
import com.sheen.rokid.device.Observation
import com.sheen.rokid.device.Ref
import com.sheen.rokid.device.RefRegistry
import com.sheen.rokid.device.Screenshot
import com.sheen.rokid.device.ScreenFilter as DeviceScreenFilter
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * The production accessibility service (P3.3 Task 10): the only thing in this app that may drive **another** app's UI.
 *
 * The user enables it in system settings and can disable it there at any time, which is why the channel's availability
 * is read from [connected] rather than remembered: a service the system stopped must stop being advertised.
 *
 * Two rules from P0's T3b probe live in this class:
 * - a semantic click aims at the **nearest clickable ancestor** of the node carrying the text. The first probe version
 *   clicked the label itself and every `performAction(ACTION_CLICK)` returned `false` - 20/20 false failures;
 * - the screen content is read only to produce a before/after fingerprint. It is hashed and compared, **never logged,
 *   never stored and never sent**: spec §8.3 keeps screen reading off the wire, and §17.5's audit rule follows it.
 *
 * Extended in T1.3 with snapshot-based semantic actions. The service now tracks a "current valid snapshot" and
 * invalidates it after any mutating action. Using a stale snapshotId returns false.
 *
 * Extended in 0.25 Task 9 with the device-control port. This class is now the production
 * [com.sheen.rokid.device.AccessibilityPort]: it exposes [registry] to the kernel (R36) and delegates
 * observation and node actions to [AccessibilityAdapter], whose pure half is JVM-testable. The old
 * [AccessibilityUi] surface above is unchanged and still serves the legacy tool path.
 */
class EdithAccessibilityService : AccessibilityService(), AccessibilityUi, AccessibilityPort {

    // ── Snapshot lifecycle ───────────────────────────────────────────────────

    /** The id of the currently valid snapshot, or null if none exists or it was invalidated. */
    @Volatile
    private var currentSnapshotId: String? = null

    /** The snapshot elements for the current snapshot, indexed by position. */
    @Volatile
    private var currentSnapshotElements: List<ScreenElement> = emptyList()

    /** Map from index to the live AccessibilityNodeInfo for the current snapshot. */
    @Volatile
    private var currentNodeMap: Map<Int, AccessibilityNodeInfo> = emptyMap()

    /** Invalidates the current snapshot so that subsequent actions with the old id return false. */
    private fun invalidateSnapshot() {
        currentSnapshotId = null
        currentSnapshotElements = emptyList()
        currentNodeMap = emptyMap()
    }

    // ── 0.25 device-control port (Task 9) ────────────────────────────────────

    /**
     * The one [RefRegistry] the adapter and the kernel share (R36).
     *
     * This is the class-level [sharedRegistry], not a per-instance field: the composition builds the
     * kernel at app start, and the accessibility service may connect afterwards. Task 10 MUST
     * construct the kernel with that same instance:
     * `DeviceKernel(accessibility = service.port(), ..., registry = service.registry)`.
     * A second registry would render refs that [performNodeAction] cannot resolve.
     */
    val registry: RefRegistry get() = sharedRegistry

    private val adapter by lazy { AccessibilityAdapter(AndroidAccessibilityPlatform(this), registry) }

    /** Latest `TYPE_WINDOW_STATE_CHANGED` package; strings only, never content (spec §8.3). */
    @Volatile
    private var foregroundPackage: String? = null

    /** Latest `TYPE_WINDOW_STATE_CHANGED` activity class; null until the first window-state event. */
    @Volatile
    private var foregroundActivity: String? = null

    override fun isBound(): Boolean = true

    /**
     * Screen-on state. A failed probe answers `false`: this version never unlocks anything, so
     * reporting "interactive" when unsure is the more dangerous error for the kernel.
     */
    override fun isInteractive(): Boolean = runCatching {
        getSystemService(PowerManager::class.java)?.isInteractive == true
    }.getOrDefault(false)

    /**
     * Keyguard state. A failed probe answers `true` (locked): the kernel must not drive the screen
     * when the service cannot prove it is unlocked.
     */
    override fun isKeyguardLocked(): Boolean = runCatching {
        getSystemService(KeyguardManager::class.java)?.isKeyguardLocked ?: true
    }.getOrDefault(true)

    /** Collects the full tree; [filter] only affects rendering, never collection (R37). */
    override fun observe(filter: DeviceScreenFilter): Observation = adapter.observe(filter)

    override fun performNodeAction(ref: Ref, action: String): Boolean = adapter.performNodeAction(ref, action)

    override fun dispatchGesture(gesture: Gesture): Boolean = adapter.dispatchGesture(gesture)

    override fun foreground(): Foreground {
        val packageName = foregroundPackage
            ?: runCatching { rootInActiveWindow?.packageName?.toString() }.getOrNull()
        return Foreground(packageName, foregroundActivity)
    }

    /**
     * The active window's root package — the second witness `open` verifies a launch against.
     *
     * Only the package string is read; no node content. A failed or missing root answers `null` (an
     * honest "cannot tell"), never the last event's package.
     */
    override fun rootPackage(): String? =
        runCatching { rootInActiveWindow?.packageName?.toString() }.getOrNull()

    /**
     * Takes one JPEG screenshot through the platform's `takeScreenshot` (API 30+).
     *
     * The encoded bytes are returned to the caller only; the service never writes the payload to
     * disk (the Task 13 artifact store owns persistence). Every failure - old platform, refusal,
     * exception, five-second timeout - answers `null`.
     */
    override fun screenshot(): Screenshot? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return try {
            val latch = CountDownLatch(1)
            val captured = AtomicReference<AccessibilityService.ScreenshotResult?>()
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                Executor { command -> command.run() },
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                        captured.set(result)
                        latch.countDown()
                    }

                    override fun onFailure(errorCode: Int) {
                        latch.countDown()
                    }
                },
            )
            if (!latch.await(SCREENSHOT_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                return null
            }
            val result = captured.get() ?: return null
            val buffer = result.hardwareBuffer
            try {
                val hardware = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace) ?: return null
                // Hardware bitmaps are read-only; copy to ARGB_8888 before scaling or JPEG encoding.
                val software = hardware.copy(Bitmap.Config.ARGB_8888, false) ?: return null
                try {
                    // One shared compression step: every caller pays for the same bounded JPEG, so a
                    // full-resolution screen never leaves this service (0.3 Phase 2.1, Task 1).
                    val (targetWidth, targetHeight) = scaledScreenshotSize(software.width, software.height)
                    // `0 to 0` means the sizing rule refused degenerate input; never request that bitmap.
                    if (targetWidth <= 0 || targetHeight <= 0) return null
                    val scaled = if (targetWidth == software.width && targetHeight == software.height) {
                        software
                    } else {
                        Bitmap.createScaledBitmap(software, targetWidth, targetHeight, true)
                    }
                    try {
                        val output = ByteArrayOutputStream()
                        if (!scaled.compress(Bitmap.CompressFormat.JPEG, SCREENSHOT_JPEG_QUALITY, output)) return null
                        Screenshot(output.toByteArray(), SCREENSHOT_MIME, scaled.width, scaled.height)
                    } finally {
                        // `createScaledBitmap` returns a separate bitmap; free it before the copy.
                        if (scaled !== software) scaled.recycle()
                    }
                } finally {
                    software.recycle()
                }
            } finally {
                buffer.close()
            }
        } catch (t: Throwable) {
            null
        }
    }

    // ── Service lifecycle ────────────────────────────────────────────────────

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    /**
     * Tracks the foreground package/class for [foreground].
     *
     * Only the package and class strings are read - never text, descriptions or any other node
     * content, and nothing is logged. A window-state change is the event that names the window now
     * in front; content-change events do not change which window that is, so they are deliberately
     * not consumed (they would also let IME content churn overwrite the foreground app).
     */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        event.packageName?.toString()?.let { foregroundPackage = it }
        event.className?.toString()?.let { foregroundActivity = it }
    }

    override fun onInterrupt() = Unit

    // ── Legacy methods (kept for backward compatibility until T1.5) ──────────

    override fun clickText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val carrying = root.findAccessibilityNodeInfosByText(text).firstOrNull() ?: return false
        val clickable = generateSequence(carrying) { it.parent }.firstOrNull { it.isClickable } ?: return false
        return clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    override fun setText(text: String): Boolean {
        val focused = findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        if (!focused.isEditable) return false
        val arguments = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
    }

    override fun screenFingerprint(): String {
        val root = rootInActiveWindow ?: return ""
        val texts = mutableListOf<String>()
        collect(root, texts)
        return MessageDigest.getInstance("SHA-256")
            .digest(texts.joinToString("\u0000").toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    // ── T1.3 extended methods ────────────────────────────────────────────────

    override fun snapshot(filter: ScreenFilter): ScreenSnapshot {
        val source = AccessibilityNodeSource(this)
        val snap = SnapshotBuilder.build(source, filter)
        // Store the snapshot state for subsequent actions
        currentSnapshotId = snap.snapshotId
        currentSnapshotElements = snap.elements
        // Build the node map from the source's captured nodes
        currentNodeMap = source.capturedNodes
        return snap
    }

    override fun clickByIndex(snapshotId: String, index: Int): Boolean {
        if (snapshotId != currentSnapshotId) return false
        val node = currentNodeMap[index] ?: return false
        // Find nearest clickable ancestor (same pattern as legacy clickText)
        val clickable = generateSequence(node) { it.parent }.firstOrNull { it.isClickable } ?: return false
        val result = clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        invalidateSnapshot()
        return result
    }

    override fun longPressByIndex(snapshotId: String, index: Int): Boolean {
        if (snapshotId != currentSnapshotId) return false
        val node = currentNodeMap[index] ?: return false
        val target = generateSequence(node) { it.parent }.firstOrNull { it.isLongClickable } ?: return false
        val result = target.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
        invalidateSnapshot()
        return result
    }

    override fun scroll(direction: ScrollDirection): Boolean {
        val root = rootInActiveWindow ?: return false
        val action = when (direction) {
            ScrollDirection.FORWARD -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            ScrollDirection.BACKWARD -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        }
        // Find the first scrollable node in the tree
        val scrollable = findFirstScrollable(root) ?: return false
        val result = scrollable.performAction(action)
        invalidateSnapshot()
        return result
    }

    override fun setTextByIndex(snapshotId: String, index: Int, text: String): Boolean {
        if (snapshotId != currentSnapshotId) return false
        val element = currentSnapshotElements.getOrNull(index) ?: return false
        if (!element.editable) return false
        val node = currentNodeMap[index] ?: return false
        val arguments = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        val result = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
        invalidateSnapshot()
        return result
    }

    override fun structuralFingerprint(): ScreenFingerprint {
        val source = AccessibilityNodeSource(this)
        val snap = SnapshotBuilder.build(source)
        return snap.fingerprint
    }

    // ── Internal helpers ─────────────────────────────────────────────────────

    /** Bounded breadth-first walk: the fingerprint only has to be stable, not complete. */
    private fun collect(root: AccessibilityNodeInfo, into: MutableList<String>) {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue += root
        var visited = 0
        while (queue.isNotEmpty() && visited < MAX_NODES) {
            val node = queue.removeFirst()
            visited++
            node.text?.let { into += it.toString() }
            node.contentDescription?.let { into += it.toString() }
            for (index in 0 until node.childCount) node.getChild(index)?.let { queue += it }
        }
    }

    /** Finds the first scrollable node via breadth-first search. */
    private fun findFirstScrollable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue += root
        var visited = 0
        while (queue.isNotEmpty() && visited < MAX_NODES) {
            val node = queue.removeFirst()
            visited++
            if (node.isScrollable) return node
            for (i in 0 until node.childCount) node.getChild(i)?.let { queue += it }
        }
        return null
    }

    // ── Production SnapshotNodeSource ────────────────────────────────────────

    /**
     * Walks the real Android accessibility node tree breadth-first from [service]'s rootInActiveWindow.
     * Captures a mapping from snapshot index to live [AccessibilityNodeInfo] for subsequent actions.
     */
    internal class AccessibilityNodeSource(
        private val service: EdithAccessibilityService,
    ) : SnapshotNodeSource {

        /** Nodes captured during the last [walkNodes] call, keyed by their assigned index. */
        var capturedNodes: Map<Int, AccessibilityNodeInfo> = emptyMap()
            private set

        override fun walkNodes(filter: ScreenFilter): List<RawSnapshotNode> {
            val root = service.rootInActiveWindow ?: return emptyList()
            val result = mutableListOf<RawSnapshotNode>()
            val nodeMap = mutableMapOf<Int, AccessibilityNodeInfo>()
            val queue = ArrayDeque<AccessibilityNodeInfo>()
            queue += root
            var visited = 0
            val limit = maxNodeLimit()

            while (queue.isNotEmpty() && visited < limit) {
                val node = queue.removeFirst()
                visited++

                val raw = extractRaw(node)
                val matches = when (filter) {
                    ScreenFilter.ALL -> true
                    ScreenFilter.INTERACTIVE -> raw.clickable || raw.editable || raw.scrollable
                    ScreenFilter.TEXT -> !raw.text.isNullOrBlank()
                }
                if (matches) {
                    val index = result.size
                    result += raw
                    nodeMap[index] = node
                }

                for (i in 0 until node.childCount) {
                    node.getChild(i)?.let { queue += it }
                }
            }

            capturedNodes = nodeMap
            return result
        }

        override fun maxNodeLimit(): Int = MAX_NODES

        private fun extractRaw(node: AccessibilityNodeInfo): RawSnapshotNode {
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            val hintText = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                node.hintText?.toString()
            } else {
                null
            }
            return RawSnapshotNode(
                text = node.text?.toString(),
                contentDescription = node.contentDescription?.toString(),
                className = node.className?.toString(),
                clickable = node.isClickable,
                editable = node.isEditable,
                scrollable = node.isScrollable,
                checked = node.isChecked,
                left = bounds.left,
                top = bounds.top,
                right = bounds.right,
                bottom = bounds.bottom,
                hintText = hintText,
            )
        }
    }

    companion object {
        /** How much of the tree one fingerprint may read. */
        private const val MAX_NODES = 400

        @Volatile
        private var instance: EdithAccessibilityService? = null

        /** The channel's own answer to "am I usable right now". */
        fun connected(): Boolean = instance != null

        /**
         * Whether an input-method window is up (0.3 Phase 2, Task 2's IME fact), or `null` when it cannot be told.
         *
         * A service the user has switched off is `null`, not `false`: it never looked, and a `false` would be a fact
         * this channel does not have. While connected, the platform's window list is the answer - a
         * `TYPE_INPUT_METHOD` window present means the IME is up, and a readable list without one means it is down.
         * Only the window **type** is read; no window content, title or node is touched.
         */
        fun imeVisibleNow(): Boolean? {
            val service = instance ?: return null
            return runCatching {
                val windows = service.windows ?: return@runCatching null
                windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
            }.getOrNull()
        }

        /**
         * Whether the keyguard's PIN entry view is on screen, or `null` when this channel cannot tell.
         *
         * The lock-screen gate needs a real "the PIN view is up" signal before it injects digits: the 2026-10-22
         * K70 acceptance log showed the view appears about 1.9 s after the wake key, so digits typed immediately
         * were dropped even with a correct PIN. Only window structure and the keyguard's own view id are read -
         * never text. `null` means the service is off or the platform would not answer, so a caller must treat it
         * as "unknown" (bounded wait) instead of "no PIN view".
         */
        fun keyguardPinVisibleNow(): Boolean? {
            val service = instance ?: return null
            return runCatching {
                val windows = service.windows ?: return@runCatching null
                val roots = windows.mapNotNull { it.root }
                if (roots.isEmpty()) return@runCatching null
                val found = roots.any { containsPinEntry(it) }
                if (!found) {
                    // 2026-10-22 acceptance diagnostic: the unlock gate polls this probe before typing. Counts and
                    // the keyguard's own view ids only - no text, no user content.
                    val ids = buildList { roots.forEach { collectIds(it, 12, this) } }.distinct()
                    android.util.Log.i(
                        "EdithA11y",
                        "PIN_PROBE found=false windows=${windows.size} roots=${roots.size} " +
                            "types=${windows.map { it.type }} ids=$ids",
                    )
                }
                found
            }.getOrNull()
        }

        private fun collectIds(node: AccessibilityNodeInfo, budget: Int, into: MutableList<String>) {
            if (budget <= 0 || into.size >= 24) return
            node.viewIdResourceName?.let { into += it.substringAfterLast('/') }
            for (index in 0 until node.childCount) {
                val child = node.getChild(index) ?: continue
                collectIds(child, budget - 1, into)
            }
        }

        private const val KEYGUARD_PIN_ENTRY_ID = "com.android.systemui:id/pinEntry"

        private fun containsPinEntry(root: AccessibilityNodeInfo): Boolean {
            var budget = MAX_NODES
            fun walk(node: AccessibilityNodeInfo): Boolean {
                if (budget-- <= 0) return false
                if (node.viewIdResourceName == KEYGUARD_PIN_ENTRY_ID) return true
                for (index in 0 until node.childCount) {
                    val child = node.getChild(index) ?: continue
                    if (walk(child)) return true
                }
                return false
            }
            return walk(root)
        }

        /** The live UI port, or `null` while the user has the service switched off. */
        fun ui(): AccessibilityUi? = instance

        /** The live device-control port for Task 10's kernel wiring, or `null` while disabled. */
        fun port(): AccessibilityPort? = instance

        /**
         * The one [RefRegistry] every service instance and the kernel share (R36).
         *
         * Created with the class, not with a connected service: `HostCompositionRoot` builds the
         * kernel during app start, which can happen before Android binds the accessibility service.
         * Task 10 constructs `DeviceKernel(accessibility = port(), ..., registry = registry())`
         * unconditionally; a fallback registry would render refs [performNodeAction] cannot resolve.
         */
        fun registry(): RefRegistry = sharedRegistry

        /** The production registry, shared by all service instances (there is at most one). */
        private val sharedRegistry = RefRegistry()
    }
}

/** Five seconds is the whole budget for one screenshot or one synthetic gesture. */
private const val SCREENSHOT_TIMEOUT_MILLIS = 5_000L
private const val GESTURE_TIMEOUT_MILLIS = 5_000L

/**
 * The Android implementation of [AccessibilityPlatform] (0.25 Task 9).
 *
 * It is the only class that turns `AccessibilityNodeInfo`/`AccessibilityWindowInfo` into the
 * platform-neutral shapes [AccessibilityAdapter] consumes, and the only one that performs platform
 * node actions and gesture dispatch. Node ids are transient handles into the latest [collect];
 * the adapter never uses an id from an older collection.
 */
private class AndroidAccessibilityPlatform(
    private val service: EdithAccessibilityService,
) : AccessibilityPlatform {

    @Volatile
    private var nodesById: Map<Int, AccessibilityNodeInfo> = emptyMap()

    override fun collect(maxNodes: Int): PlatformSnapshot {
        val collectedNodes = LinkedHashMap<Int, AccessibilityNodeInfo>()
        val windows = try {
            service.windows?.toList().orEmpty()
        } catch (t: Throwable) {
            emptyList()
        }
        val snapshot = if (windows.isEmpty()) {
            collectActiveWindow(maxNodes, collectedNodes)
        } else {
            try {
                collectWindows(windows, maxNodes, collectedNodes)
            } catch (t: Throwable) {
                // A window that cannot be read must not hide the active one: fall back to the same
                // single-window shape `getWindows()` failure uses.
                collectedNodes.clear()
                collectActiveWindow(maxNodes, collectedNodes)
            }
        }
        nodesById = collectedNodes
        return snapshot
    }

    override fun click(nodeId: Int): Boolean =
        nodesById[nodeId]?.performAction(AccessibilityNodeInfo.ACTION_CLICK) ?: false

    override fun longClick(nodeId: Int): Boolean =
        nodesById[nodeId]?.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK) ?: false

    override fun scrollForward(nodeId: Int): Boolean =
        nodesById[nodeId]?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) ?: false

    override fun scrollBackward(nodeId: Int): Boolean =
        nodesById[nodeId]?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) ?: false

    override fun setText(nodeId: Int, text: String): Boolean {
        val node = nodesById[nodeId] ?: return false
        val arguments = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
    }

    override fun dispatchGesture(gesture: Gesture): Boolean {
        val description = try {
            buildGesture(gesture)
        } catch (t: Throwable) {
            return false
        }
        val latch = CountDownLatch(1)
        val completed = AtomicBoolean(false)
        val accepted = service.dispatchGesture(
            description,
            object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    completed.set(true)
                    latch.countDown()
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    latch.countDown()
                }
            },
            null,
        )
        if (!accepted) return false
        return try {
            latch.await(GESTURE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS) && completed.get()
        } catch (t: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    private fun collectWindows(
        windows: List<AccessibilityWindowInfo>,
        maxNodes: Int,
        nodesById: MutableMap<Int, AccessibilityNodeInfo>,
    ): PlatformSnapshot {
        val platformWindows = ArrayList<PlatformWindow>(windows.size)
        var nextNodeId = 0
        var visited = 0
        var truncated = false

        for (window in windows) {
            val root = runCatching { window.root }.getOrNull()
            val windowBounds = Rect()
            runCatching { window.getBoundsInScreen(windowBounds) }
            val windowNodes = ArrayList<PlatformNode>()

            if (root != null) {
                val stack = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
                stack.addLast(root to 0)
                walk@ while (stack.isNotEmpty()) {
                    if (visited >= maxNodes) {
                        truncated = true
                        break@walk
                    }
                    val (node, depth) = stack.removeLast()
                    val nodeId = nextNodeId++
                    nodesById[nodeId] = node
                    windowNodes += node.toPlatformNode(nodeId = nodeId, windowId = window.id, depth = depth)
                    visited++
                    for (index in node.childCount - 1 downTo 0) {
                        node.getChild(index)?.let { stack.addLast(it to depth + 1) }
                    }
                }
            }

            platformWindows += PlatformWindow(
                id = window.id,
                type = window.type,
                layer = window.layer,
                bounds = windowBounds.toBounds(),
                packageName = root?.packageName?.toString(),
                title = runCatching { window.title }.getOrNull()?.toString(),
                nodes = windowNodes,
            )
            if (truncated) break
        }
        return PlatformSnapshot(windows = platformWindows, truncated = truncated)
    }

    private fun collectActiveWindow(
        maxNodes: Int,
        nodesById: MutableMap<Int, AccessibilityNodeInfo>,
    ): PlatformSnapshot {
        val root = runCatching { service.rootInActiveWindow }.getOrNull()
            ?: return PlatformSnapshot(windows = emptyList())
        val windowBounds = Rect()
        runCatching { root.getBoundsInScreen(windowBounds) }
        val windowId = root.windowId
        val windowNodes = ArrayList<PlatformNode>()
        var nextNodeId = 0
        var visited = 0
        var truncated = false

        val stack = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        stack.addLast(root to 0)
        while (stack.isNotEmpty()) {
            if (visited >= maxNodes) {
                truncated = true
                break
            }
            val (node, depth) = stack.removeLast()
            val nodeId = nextNodeId++
            nodesById[nodeId] = node
            windowNodes += node.toPlatformNode(nodeId = nodeId, windowId = windowId, depth = depth)
            visited++
            for (index in node.childCount - 1 downTo 0) {
                node.getChild(index)?.let { stack.addLast(it to depth + 1) }
            }
        }

        val window = PlatformWindow(
            id = windowId,
            type = PlatformWindowTypes.APPLICATION,
            layer = 0,
            bounds = windowBounds.toBounds(),
            packageName = root.packageName?.toString(),
            title = null,
            nodes = windowNodes,
        )
        return PlatformSnapshot(windows = listOf(window), truncated = truncated)
    }

    private fun AccessibilityNodeInfo.toPlatformNode(
        nodeId: Int,
        windowId: Int,
        depth: Int,
    ): PlatformNode {
        val bounds = Rect()
        getBoundsInScreen(bounds)
        return PlatformNode(
            nodeId = nodeId,
            windowId = windowId,
            depth = depth,
            className = className?.toString(),
            text = text?.toString(),
            contentDescription = contentDescription?.toString(),
            viewIdResourceName = viewIdResourceName,
            bounds = bounds.toBounds(),
            clickable = isClickable,
            longClickable = isLongClickable,
            editable = isEditable,
            scrollable = isScrollable,
            checkable = isCheckable,
            checked = isChecked,
            selected = isSelected,
            enabled = isEnabled,
            focused = isFocused,
            hintText = hintText?.toString(),
            paneTitle = paneTitle?.toString(),
            tooltipText = tooltipText?.toString(),
            drawingOrder = drawingOrder,
            actionIds = actionList.map { it.id },
            visibleToUser = isVisibleToUser,
            collection = collectionInfo?.let {
                PlatformCollectionInfo(
                    rowCount = it.rowCount,
                    columnCount = it.columnCount,
                    hierarchical = it.isHierarchical,
                    selectionMode = it.selectionMode,
                )
            },
            range = rangeInfo?.let {
                PlatformRangeInfo(
                    current = it.current,
                    min = it.min,
                    max = it.max,
                    type = it.type,
                )
            },
        )
    }

    private fun buildGesture(gesture: Gesture): GestureDescription {
        val builder = GestureDescription.Builder()
        gesture.strokes.forEach { stroke ->
            val first = stroke.points.firstOrNull()
                ?: throw IllegalArgumentException("a stroke needs at least one point")
            val path = Path().apply {
                moveTo(first.x.toFloat(), first.y.toFloat())
                stroke.points.drop(1).forEach { lineTo(it.x.toFloat(), it.y.toFloat()) }
            }
            builder.addStroke(
                GestureDescription.StrokeDescription(
                    path,
                    stroke.startTimeMillis,
                    stroke.durationMillis,
                ),
            )
        }
        if (gesture.strokes.isEmpty()) throw IllegalArgumentException("a gesture needs at least one stroke")
        return builder.build()
    }
}

private fun Rect.toBounds(): Bounds = Bounds(
    left = left,
    top = top,
    right = right,
    bottom = bottom,
)
