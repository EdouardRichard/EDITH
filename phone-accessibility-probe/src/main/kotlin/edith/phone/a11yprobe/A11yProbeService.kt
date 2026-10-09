package edith.phone.a11yprobe

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

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

    companion object {
        const val TAG = "EdithP3T3b"

        /** Null until the user enables the service in system settings; the probe can never enable itself. */
        @Volatile
        var instance: A11yProbeService? = null
            private set

        private const val TAP_MILLIS = 60L

        /** Bounded so a deep or cyclic hierarchy cannot hang the fingerprint walk. */
        private const val MAX_DEPTH = 40

        /** A row is a few levels above its label; the bound stops a malformed tree from walking forever. */
        private const val MAX_ANCESTOR_DEPTH = 12
    }
}
