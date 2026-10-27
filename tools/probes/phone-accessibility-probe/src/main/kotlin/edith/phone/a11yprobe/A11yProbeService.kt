package edith.phone.a11yprobe

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.GestureResultCallback
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * A node together with its **tree position**: the child-index path from the window root (A1).
 *
 * This is the probe's identity for a node. A path is a plain structural fact of the walk that produced the node, so two
 * nodes can be related (same node / ancestor / descendant) without an identity API — the SDK exposes no usable
 * `sourceNodeId` on `AccessibilityNodeInfo`, and a *label* cannot answer the question at all because the row container
 * the K40 read back at a tap point carries no text.
 */
data class NodeInTree(val node: AccessibilityNodeInfo, val path: List<Int>)

/**
 * What a coordinate tap produced (A1): whether the platform accepted and completed the gesture, and the node read back
 * **at the tap point**. [hit] is null both when nothing is at the point and when the gesture never completed — the
 * caller distinguishes them with [dispatched] so a refused gesture is still visible as `click_refused`.
 */
data class CoordinateTap(val dispatched: Boolean, val hit: NodeInTree?)

/**
 * The two halves of one injected tap, kept apart (coordinate arm).
 *
 * `accepted` is whether the platform took the gesture; `completed` is whether its completion callback arrived. A
 * gesture can be accepted and never reported, and calling that "clicked" would claim a landing the probe never saw -
 * the same discipline as [CoordinateTap]'s `dispatched`, with the missing half named.
 */
data class GestureOutcome(val accepted: Boolean, val completed: Boolean)

/**
 * One active-window node paired with the plain-value facts the text arm's selector decides from (Task 13a).
 *
 * The pairing exists because the decision is a JVM-tested pure function while the action needs the platform handle.
 * [facts] is the only part [chooseTextTarget] may see, which keeps "which node did the probe aim at" reviewable
 * without a device and without a log line carrying the node's text.
 */
data class TextNodeCandidate(val node: AccessibilityNodeInfo, val facts: TextCandidateFacts)

/**
 * Structural shape of the active window's tree for the text arm's timeout diagnosis (Task 13a): how many nodes were
 * walked, how many the platform called visible, how many were editable, and the editable class names. No text.
 */
data class TextTreeShape(
    val walked: Int,
    val visible: Int,
    val editable: Int,
    val editableClasses: List<String>,
)

/**
 * The active window's package and the visible nodes read from **that same root** (Task 13a).
 *
 * They are returned together because reading them separately races a window transition: `activePackage()` followed by
 * a separate tree read can name the app that was in front a frame ago while the candidates come from the app that just
 * came forward (the K40 produced exactly that during the first cross-window read). A verdict whose `pkg=` and target
 * came from different windows is not reviewable, so the package is taken from the root that produced the candidates.
 */
data class ActiveTextTree(val pkg: String, val candidates: List<TextNodeCandidate>)

/**
 * P3.0 T3b: the two cross-app UI mechanisms, measured on the same target.
 *
 * Node mode uses the accessibility tree (`findAccessibilityNodeInfosByText` + `ACTION_CLICK`), which is what a semantic
 * agent action would use: it survives a layout shift or a different screen density. Coordinate mode uses
 * `dispatchGesture` at the same node's bounds, which is what a blind `input tap` equivalent does.
 *
 * There is no `input` shell command here: this service exists so the probe measures the *unprivileged* path too, which
 * is the fallback when neither Shizuku nor Root is available.
 */
class A11yProbeService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "A11Y_SERVICE_CONNECTED")
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    /**
     * The first node whose visible text matches one of [candidates], **preferring one the platform says is clickable**.
     *
     * The first real run took the first *visible* match and got `performAction(ACTION_CLICK) returned false` 19 times out
     * of 20: the match was a nested label, not the row that owns the action. Preferring `isClickable` is the difference
     * between aiming at the label and aiming at the control.
     */
    fun findNode(candidates: List<String>): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        for (candidate in candidates) {
            val matches = runCatching { root.findAccessibilityNodeInfosByText(candidate) }.getOrNull().orEmpty()
            val visible = matches.filter { it.isVisibleToUser }
            if (visible.isEmpty()) continue
            return visible.firstOrNull { it.isClickable } ?: visible.first()
        }
        return null
    }

    /** The whole visible screen as text — the identity an action is judged against. */
    fun fingerprint(): String {
        val root = rootInActiveWindow ?: return ""
        val texts = mutableListOf<String>()
        fun walk(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || depth > MAX_DEPTH) return
            node.text?.toString()?.takeIf { it.isNotBlank() }?.let { texts += it }
            node.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let { texts += it }
            for (index in 0 until node.childCount) walk(node.getChild(index), depth + 1)
        }
        walk(root, 0)
        return texts.distinct().joinToString("|")
    }

    /**
     * The nearest ancestor that the platform says is clickable, or null.
     *
     * `findAccessibilityNodeInfosByText` returns the node that *carries the text* — usually a child label — while the
     * control that owns the action is its ancestor row. Clicking the label is what `performAction(ACTION_CLICK) returned
     * false` looks like 20 times out of 20. This is the standard climb, and the first version of the probe omitted it.
     */
    fun clickableAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node
        var depth = 0
        while (current != null && depth < MAX_ANCESTOR_DEPTH) {
            if (current.isClickable) return current
            current = runCatching { current?.parent }.getOrNull()
            depth += 1
        }
        return null
    }

    fun boundsOf(node: AccessibilityNodeInfo): Rect {
        val rect = Rect()
        node.getBoundsInScreen(rect)
        return rect
    }

    /** Why a node could not be found — the failure name recorded for grouping. */
    fun describeMissing(candidates: List<String>): String {
        val root = rootInActiveWindow ?: return "no active window"
        val packageName = root.packageName?.toString() ?: "unknown"
        return "node not found in $packageName for ${candidates.first()}"
    }

    fun clickNode(node: AccessibilityNodeInfo): Boolean =
        runCatching { node.performAction(AccessibilityNodeInfo.ACTION_CLICK) }.getOrDefault(false)

    /** A coordinate tap at the centre of [rect]. Returns whether the gesture was dispatched, not whether it landed. */
    fun tapCenter(rect: Rect): Boolean {
        val path = Path().apply { moveTo(rect.exactCenterX(), rect.exactCenterY()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, TAP_MILLIS))
            .build()
        return runCatching { dispatchGesture(gesture, null, Handler(Looper.getMainLooper())) }.getOrDefault(false)
    }

    fun goBack(): Boolean = runCatching { performGlobalAction(GLOBAL_ACTION_BACK) }.getOrDefault(false)

    /** Whether the active window still contains one of [candidates] — the probe's "did the tap do anything" signal. */
    fun stillContains(candidates: List<String>): Boolean = findNode(candidates) != null

    // ----------------------------------------------------------------------------------------------
    // A1 (`A11yIntentActivity`) additions. All of these are platform-API only, and none of them returns
    // or logs user content: a caller gets counts, class names and the labels it asked about.
    // ----------------------------------------------------------------------------------------------

    /**
     * The label the platform itself would name for this node, for the *probe's own bookkeeping only*.
     *
     * Preferred order is text → contentDescription → class: a clickable row usually has no text of its own (T3b saw
     * `clickedLabel='null'`), so the class is the honest fallback rather than an empty string that would read as "the
     * tap hit nothing".
     */
    fun labelOf(node: AccessibilityNodeInfo?): String? {
        if (node == null) return null
        return runCatching {
            node.text?.toString()?.takeIf { it.isNotBlank() }
                ?: node.contentDescription?.toString()?.takeIf { it.isNotBlank() }
                ?: node.className?.toString()
        }.getOrNull()
    }

    /**
     * Whether one of [candidates] is on the current screen **exactly**, using the same first-visible-match rule as
     * [findNode]. Exact matching lives in `firstCandidateIn` (unit-tested); this is only the device-side read.
     */
    fun isShown(candidates: List<String>): Boolean {
        val visible = visibleLabels()
        return firstCandidateIn(candidates, visible) != null
    }

    /**
     * The first visible node whose text or contentDescription is **exactly** one of [candidates], preferring a clickable
     * one — the device counterpart of the unit-tested `firstCandidateIn`.
     *
     * Deliberately not [findNode]: that one uses `findAccessibilityNodeInfosByText`, which matches *substrings*, so
     * "关于手机" would also match a row labelled "关于手机信息" and the probe could click a different row than the one
     * the verdict then checks for. T3b's evidence depends on its own substring behaviour, so it keeps [findNode];
     * A1's "did we reach the intended screen" question needs the exact rule on both sides of the comparison.
     */
    fun findExactNode(candidates: List<String>): AccessibilityNodeInfo? = findExactNodeWithPath(candidates)?.node

    /**
     * [findExactNode] plus the node's tree position (A1). Exact matching lives in `firstCandidateIn` (unit-tested); this
     * is the device-side read, unchanged in what it matches — only in what it also reports.
     */
    fun findExactNodeWithPath(candidates: List<String>): NodeInTree? {
        val root = rootInActiveWindow ?: return null
        val byCandidate = mutableMapOf<String, MutableList<NodeInTree>>()
        fun walk(node: AccessibilityNodeInfo?, path: List<Int>, depth: Int) {
            if (node == null || depth > MAX_DEPTH) return
            if (node.isVisibleToUser) {
                val labels = listOfNotNull(
                    node.text?.toString()?.takeIf { it.isNotBlank() },
                    node.contentDescription?.toString()?.takeIf { it.isNotBlank() },
                )
                for (candidate in candidates) {
                    if (labels.contains(candidate)) byCandidate.getOrPut(candidate) { mutableListOf() } += NodeInTree(node, path)
                }
            }
            for (index in 0 until node.childCount) walk(node.getChild(index), path + index, depth + 1)
        }
        walk(root, emptyList(), 0)
        // Candidate order is the priority decision (Chinese before English), so it decides, not the tree order.
        for (candidate in candidates) {
            val matches = byCandidate[candidate] ?: continue
            return matches.firstOrNull { it.node.isClickable } ?: matches.first()
        }
        return null
    }

    /**
     * The **deepest visible node whose on-screen bounds contain (x, y)**, with its tree position, or null when no node is
     * under the point (A1, coordinate mis-tap check).
     *
     * The framework exposes no hit-test call, so this is the walk; the smallest containing node is the deepest one, i.e.
     * the node a touch at that point would be delivered to. `null` is a real answer — "the coordinate landed on empty
     * space" — and `isHitIntended` treats it as a miss rather than as a missing measurement, so the worst case stays in
     * the mis-tap numerator.
     *
     * A container that *draws* rows without exposing them (self-drawn lists, some MIUI settings pages) makes this read
     * coarser than the eye: the smallest containing node is then a shared **ancestor** of the row that was actually
     * under the point, and an ancestor counts as relatedness — so on such a page this can report `hitIntended=true` for a
     * tap that in truth landed on a row the probe cannot see. That failure mode overstates the hit count instead of the
     * miss count, which is why `A1_SCROLL_POINT` logs the intended and hit paths and `A1_SCROLL` logs `hitLabel=`: a
     * hit whose path is a short prefix of the intended one is the signature of a coarse read, not of a good tap. The
     * probe does not "fix" this by lowering the criterion — an unreadable row must not be turned into a hit silently, and
     * it must not be turned into a miss either; it is reported and recorded as unverified on device.
     */
    fun nodeAtPoint(x: Int, y: Int): NodeInTree? {
        val root = rootInActiveWindow ?: return null
        var best: NodeInTree? = null
        var bestArea = Long.MAX_VALUE
        fun walk(node: AccessibilityNodeInfo?, path: List<Int>, depth: Int) {
            if (node == null || depth > MAX_DEPTH) return
            if (node.isVisibleToUser) {
                val bounds = boundsOf(node)
                if (bounds.width() > 0 && bounds.height() > 0 && bounds.contains(x, y)) {
                    val area = bounds.width().toLong() * bounds.height()
                    if (area < bestArea) {
                        bestArea = area
                        best = NodeInTree(node, path)
                    }
                }
            }
            for (index in 0 until node.childCount) walk(node.getChild(index), path + index, depth + 1)
        }
        walk(root, emptyList(), 0)
        return best
    }

    /**
     * Dispatch a **coordinate** tap at the centre of [rect] and read back what is at that point (A1).
     *
     * The read happens inside the gesture's own completion callback, not after a sleep: the tap is what makes the screen
     * change, so a read taken after the window transition would describe the *destination* screen and score a correct
     * tap as a miss. This is the closest observable to "what was under the point when the touch was delivered", and it
     * is a race against a fast transition — recorded as unverified-on-device rather than assumed.
     *
     * The tap and the read use the same integer point (`Rect.centerX/centerY`), so the read cannot disagree with the
     * injection by a rounding step. `dispatchGesture` returning false, or the gesture being cancelled, is reported as
     * `dispatched = false` with no hit.
     */
    fun tapCenterAndReadPoint(rect: Rect): CoordinateTap {
        val x = rect.centerX()
        val y = rect.centerY()
        val hit = AtomicReference<NodeInTree?>(null)
        val completed = AtomicReference(false)
        val latch = CountDownLatch(1)
        val callback = object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                hit.set(nodeAtPoint(x, y))
                completed.set(true)
                latch.countDown()
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                latch.countDown()
            }
        }
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, TAP_MILLIS))
            .build()
        val accepted = runCatching { dispatchGesture(gesture, callback, Handler(Looper.getMainLooper())) }
            .getOrDefault(false)
        if (!accepted) return CoordinateTap(dispatched = false, hit = null)
        runCatching { latch.await(GESTURE_READ_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS) }
        return CoordinateTap(dispatched = completed.get(), hit = hit.get())
    }

    /**
     * Dispatch one **coordinate** tap at an arbitrary screen point (the coordinate arm of click-now).
     *
     * Deliberately not `tapCenterAndReadPoint`: that one answers "what did I hit" for A1's mis-tap metric, while this
     * one answers "did the platform take and complete the touch" for a point the caller already validated. Both report
     * the two halves separately, because an accepted gesture with no completion callback is not a landing.
     */
    fun tapAt(x: Int, y: Int): GestureOutcome {
        val completed = AtomicReference(false)
        val latch = CountDownLatch(1)
        val callback = object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                completed.set(true)
                latch.countDown()
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                latch.countDown()
            }
        }
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, TAP_MILLIS))
            .build()
        val accepted = runCatching { dispatchGesture(gesture, callback, Handler(Looper.getMainLooper())) }
            .getOrDefault(false)
        if (!accepted) return GestureOutcome(accepted = false, completed = false)
        runCatching { latch.await(GESTURE_READ_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS) }
        return GestureOutcome(accepted = true, completed = completed.get())
    }

    /**
     * The active window root's bounds in screen pixels, or null when no window can be read (the coordinate arm).
     *
     * This is the accessibility tree's own answer to "how big is the window this point must miss", read **at dispatch
     * time** so the verdict carries the bounds the decision actually used rather than a figure from another run.
     */
    fun activeRootBounds(): Rect? = runCatching {
        val root = rootInActiveWindow ?: return@runCatching null
        Rect().also { root.getBoundsInScreen(it) }
    }.getOrNull()

    /** Every non-blank text/contentDescription currently visible, in tree order. Used for marker and label matching. */
    fun visibleLabels(): List<String> {
        val root = rootInActiveWindow ?: return emptyList()
        val labels = mutableListOf<String>()
        fun walk(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || depth > MAX_DEPTH) return
            if (node.isVisibleToUser) {
                node.text?.toString()?.takeIf { it.isNotBlank() }?.let { labels += it }
                node.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let { labels += it }
            }
            for (index in 0 until node.childCount) walk(node.getChild(index), depth + 1)
        }
        walk(root, 0)
        return labels
    }

    /** The package that owns the active window, so a verdict can say *which* app was on screen. Never user content. */
    fun activePackage(): String = runCatching { rootInActiveWindow?.packageName?.toString() }.getOrNull() ?: "unknown"

    /**
     * Scroll the list that contains [node] forward (A1, scroll scenario).
     *
     * `performAction(ACTION_SCROLL_FORWARD)` on the *scrollable ancestor* is the platform's own scroll: it moves the
     * container no matter where the target currently is, and unlike a `dispatchGesture` swipe it cannot be swallowed by
     * an animation still settling. That is the same reason A1 prefers a semantic click over a coordinate tap.
     */
    fun scrollForwardFrom(node: AccessibilityNodeInfo?): Boolean {
        var current = node
        var depth = 0
        while (current != null && depth < MAX_ANCESTOR_DEPTH) {
            if (current.isScrollable) {
                return runCatching { current.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) }
                    .getOrDefault(false)
            }
            current = runCatching { current.parent }.getOrNull()
            depth += 1
        }
        return false
    }

    // ----------------------------------------------------------------------------------------------
    // Task 13a (`A11yTextActivity`) additions. The text SET/READ arm needs the active window's editable
    // nodes and two platform actions, and nothing else: no text is stored, uploaded or written to logcat
    // by these methods. Callers only ever log a TextFingerprint (length/chars/SHA-256).
    // ----------------------------------------------------------------------------------------------

    /**
     * The package name and **visible** nodes of the active window, read from one root (Task 13a).
     *
     * The node itself is kept next to its plain-value facts so the selector can be a JVM test and the action still has
     * the platform handle. Text/hint/contentDescription are read into memory here; the caller is responsible for never
     * putting them in a log line (Task 13a's verdict carries only [TextFingerprint]).
     *
     * `hintText` exists on API 26+ and this probe's `minSdk` is 28, so the read is safe across the supported range; the
     * walk is bounded by [MAX_DEPTH] like every other tree read in this service.
     */
    fun activeTextTree(): ActiveTextTree {
        val root = runCatching { rootInActiveWindow }.getOrNull()
            ?: return ActiveTextTree("unknown", emptyList())
        val pkg = runCatching { root.packageName?.toString() }.getOrNull() ?: "unknown"
        val candidates = mutableListOf<TextNodeCandidate>()
        fun walk(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || depth > MAX_DEPTH) return
            if (runCatching { node.isVisibleToUser }.getOrDefault(false)) {
                val facts = TextCandidateFacts(
                    key = candidates.size,
                    viewId = runCatching { node.viewIdResourceName }.getOrNull(),
                    text = runCatching { node.text?.toString() }.getOrNull(),
                    hint = runCatching { node.hintText?.toString() }.getOrNull(),
                    contentDescription = runCatching { node.contentDescription?.toString() }.getOrNull(),
                    className = runCatching { node.className?.toString() }.getOrNull(),
                    editable = runCatching { node.isEditable }.getOrDefault(false),
                    focused = runCatching { node.isFocused }.getOrDefault(false),
                    visible = true,
                )
                candidates += TextNodeCandidate(node, facts)
            }
            val childCount = runCatching { node.childCount }.getOrDefault(0)
            for (index in 0 until childCount) {
                walk(runCatching { node.getChild(index) }.getOrNull(), depth + 1)
            }
        }
        walk(root, 0)
        return ActiveTextTree(pkg, candidates)
    }

    /**
     * `ACTION_FOCUS` on the selected node (Task 13a). The arm performs it even though S1 A05 measured that
     * `ACTION_SET_TEXT` works on an unfocused field: the acceptance asks for the focus-first order, and the returned
     * boolean makes a focus the platform refused visible in the verdict instead of silent.
     */
    fun focusNode(node: AccessibilityNodeInfo): Boolean =
        runCatching { node.performAction(AccessibilityNodeInfo.ACTION_FOCUS) }.getOrDefault(false)

    /**
     * `ACTION_SET_TEXT` on the selected node (Task 13a). `true` means only that the platform accepted the action:
     * S1 A09 measured a field that returns true and keeps its own text, so the caller reads the value back and reports
     * the match separately rather than treating this boolean as "the text was written".
     */
    fun setText(node: AccessibilityNodeInfo, text: String): Boolean {
        val arguments = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return runCatching { node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments) }
            .getOrDefault(false)
    }

    /**
     * The shape of the active window's tree, for the text arm's `no_field` diagnosis (Task 13a).
     *
     * When a real screen is in front and the selector still finds nothing, the only question worth answering is whether
     * the *tree* was unreadable or the *matching* was wrong. This reports counts and class names (structural facts), not
     * any node's text, hint or description: it is a diagnostic, not a second read path.
     */
    fun textTreeShape(): TextTreeShape {
        val root = runCatching { rootInActiveWindow }.getOrNull() ?: return TextTreeShape(0, 0, 0, emptyList())
        var walked = 0
        var visible = 0
        var editable = 0
        val editableClasses = mutableListOf<String>()
        fun walk(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || depth > MAX_DEPTH) return
            walked += 1
            if (runCatching { node.isVisibleToUser }.getOrDefault(false)) visible += 1
            if (runCatching { node.isEditable }.getOrDefault(false)) {
                editable += 1
                runCatching { node.className?.toString()?.substringAfterLast('.') }
                    .getOrNull()?.takeIf { it.isNotEmpty() }?.let { editableClasses += it }
            }
            val childCount = runCatching { node.childCount }.getOrDefault(0)
            for (index in 0 until childCount) {
                walk(runCatching { node.getChild(index) }.getOrNull(), depth + 1)
            }
        }
        walk(root, 0)
        return TextTreeShape(walked, visible, editable, editableClasses.take(12))
    }

    /**
     * A fallback anchor for `ACTION_SCROLL_FORWARD` when the target has not been found yet (A1, scroll scenario).
     *
     * Before the target is visible there is nothing to climb from, and a Settings list scrolls from *any* of its rows —
     * `ACTION_SCROLL_FORWARD` is delivered to the nearest scrollable ancestor of whatever node is used as the receiver.
     * If this fallback turns out to pick a non-scrolling branch on the real device, the verdict reports
     * `scroll_no_progress` and the next attempt should anchor at a known row instead; that limit is recorded in the
     * handover rather than hidden.
     */
    fun scrollAnchor(): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        for (index in 0 until root.childCount) {
            val child = runCatching { root.getChild(index) }.getOrNull() ?: continue
            if (child.isVisibleToUser && child.isScrollable) return child
        }
        return root
    }

    companion object {
        const val TAG = "EdithP3T3b"

        /** Null until the user enables the service in system settings; the probe can never enable itself. */
        @Volatile
        var instance: A11yProbeService? = null
            private set

        private const val TAP_MILLIS = 60L

        /**
         * Bound on waiting for a dispatched gesture's completion callback before giving up on the read-back (A1). The
         * gesture itself lasts [TAP_MILLIS]; a callback that has not arrived a second later is a platform that accepted
         * the gesture and never reported it, and reporting that as "no hit" keeps the probe bounded.
         */
        private const val GESTURE_READ_TIMEOUT_MILLIS = 1_000L

        /** Bounded so a deep or cyclic hierarchy cannot hang the fingerprint walk. */
        private const val MAX_DEPTH = 40

        /** A row is a few levels above its label; the bound stops a malformed tree from walking forever. */
        private const val MAX_ANCESTOR_DEPTH = 12
    }
}
