package edith.phone.a11yprobe

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * One node plus the structural facts the survey needs about it. Deliberately **not** carrying the node's text as a
 * field: the survey's logs may name a label *it* asked for, never a label it happened to read (D53 / spec §17.5).
 */
internal data class SurveyNode(
    val node: AccessibilityNodeInfo,
    val depth: Int,
    val path: List<Int>,
    val childCount: Int,
)

/** What one tree walk saw. Counts and extremes only. */
internal data class TreeShape(
    val nodes: Int,
    val maxDepth: Int,
    val maxChildCount: Int,
    val truncated: Boolean,
    /**
     * How many `getChild(i)` calls returned null for an `i` below `childCount`.
     *
     * This is the signature of a tree read that was **cut short rather than complete**: the platform promises a child
     * exists, and the read did not produce it. It is what a walk that competes with a busy target main thread looks
     * like, and reporting it is the difference between "the screen has no more nodes" and "the read stopped early".
     */
    val nullChildren: Int,
    val withText: Int,
    val withContentDescription: Int,
    val withViewId: Int,
    val withClassName: Int,
    val clickable: Int,
    val editable: Int,
    val scrollable: Int,
    val checkable: Int,
    val checked: Int,
    val visible: Int,
    val withBounds: Int,
    val distinctActionCounts: Int,
    val deepestLabelFound: Boolean,
    val wideChildCount: Int,
)

/**
 * S1 **(2026-10-16) the accessibility capability survey**: a second probe service, separate from `A11yProbeService`,
 * because the survey needs a different flag set (notably `flagReportViewIds`) and a place to count events.
 *
 * Everything here is a platform read or a platform action. Nothing is written to disk, nothing is uploaded, and the
 * only strings that reach logcat are constants this package authored plus class names, package names and resource ids
 * — screen text is read only to be *counted* or to be compared against a constant (D53, spec §17.5).
 */
open class A11ySurveyService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        register(this)
        val flags = serviceInfo?.flags ?: 0
        val capabilities = serviceInfo?.capabilities ?: 0
        Log.i(
            TAG,
            "SURVEY_SERVICE_CONNECTED class=${javaClass.simpleName}" +
                " reportViewIds=${flags and AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS != 0}" +
                " interactiveWindows=${flags and AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS != 0}" +
                " includeNotImportant=${flags and AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS != 0}" +
                " canRetrieveWindowContent=" +
                "${capabilities and AccessibilityServiceInfo.CAPABILITY_CAN_RETRIEVE_WINDOW_CONTENT != 0}" +
                " canPerformGestures=" +
                "${capabilities and AccessibilityServiceInfo.CAPABILITY_CAN_PERFORM_GESTURES != 0}",
        )
    }

    override fun onUnbind(intent: Intent?): Boolean {
        unregister(this)
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        unregister(this)
        super.onDestroy()
    }

    override fun onInterrupt() = Unit

    /**
     * The only thing the survey does with events: count them by type, remember when the last one arrived, and remember
     * which package it was about. No event content is stored and no event content is logged — the question the survey
     * asks is "can events be used as a completion signal", which is answered by *when* and *how many*, not by *what*.
     */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val type = event?.eventType ?: return
        eventCounts.computeIfAbsent(type) { AtomicInteger() }.incrementAndGet()
        totalEvents.incrementAndGet()
        val now = SystemClock.uptimeMillis()
        lastEventAtMillis.set(now)
        // A bounded timeline, so "did an event arrive before the tree reflected the change" is answerable rather than
        // guessed. Types and timestamps only.
        synchronized(eventTimeline) {
            if (eventTimeline.size >= MAX_TIMELINE) eventTimeline.removeAt(0)
            eventTimeline.add(EventStamp(type, now))
        }
    }

    // ------------------------------------------------------------------------------------------
    // Reads
    // ------------------------------------------------------------------------------------------

    internal fun activePackage(): String =
        runCatching { rootInActiveWindow?.packageName?.toString() }.getOrNull() ?: "none"

    internal fun activeWindowRoot(): AccessibilityNodeInfo? = runCatching { rootInActiveWindow }.getOrNull()

    /**
     * A bounded walk of the active window that reports the tree's **shape** and nothing else.
     *
     * [maxNodes] is the survey's own bound so a pathological tree cannot hang the run; [truncated] says whether it bit,
     * which is the difference between "the platform exposed this much" and "the probe stopped looking".
     */
    internal fun shapeOf(
        root: AccessibilityNodeInfo,
        maxNodes: Int = 4_000,
        maxDepth: Int = 200,
        deepestLabel: String? = null,
        wideId: String? = null,
    ): TreeShape {
        var nodes = 0
        var deepest = 0
        var maxChildren = 0
        var withText = 0
        var withDesc = 0
        var withId = 0
        var withClass = 0
        var clickable = 0
        var editable = 0
        var scrollable = 0
        var checkable = 0
        var checked = 0
        var visible = 0
        var withBounds = 0
        val actionCounts = HashSet<Int>()
        var deepestFound = false
        var wide = -1
        var truncated = false
        var nullChildren = 0

        fun walk(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null) return
            if (nodes >= maxNodes || depth > maxDepth) {
                truncated = true
                return
            }
            nodes += 1
            if (depth > deepest) deepest = depth
            if (node.childCount > maxChildren) maxChildren = node.childCount
            if (node.text != null) withText += 1
            if (node.contentDescription != null) withDesc += 1
            if (node.viewIdResourceName != null) withId += 1
            if (node.className != null) withClass += 1
            if (node.isClickable) clickable += 1
            if (node.isEditable) editable += 1
            if (node.isScrollable) scrollable += 1
            if (node.isCheckable) checkable += 1
            if (node.isChecked) checked += 1
            if (node.isVisibleToUser) visible += 1
            val rect = Rect()
            if (runCatching { node.getBoundsInScreen(rect) }.isSuccess) withBounds += 1
            // The action list is a count of *distinct* action ids, never the ids themselves concatenated with content.
            for (action in runCatching { node.actionList }.getOrDefault(emptyList())) {
                actionCounts.add(action.id)
            }
            if (deepestLabel != null && node.text?.toString() == deepestLabel) deepestFound = true
            if (wideId != null && node.viewIdResourceName == wideId) wide = node.childCount
            for (index in 0 until node.childCount) {
                val child = node.getChild(index)
                if (child == null) nullChildren += 1
                walk(child, depth + 1)
            }
        }
        walk(root, 0)
        return TreeShape(
            nodes = nodes,
            maxDepth = deepest,
            maxChildCount = maxChildren,
            truncated = truncated,
            nullChildren = nullChildren,
            withText = withText,
            withContentDescription = withDesc,
            withViewId = withId,
            withClassName = withClass,
            clickable = clickable,
            editable = editable,
            scrollable = scrollable,
            checkable = checkable,
            checked = checked,
            visible = visible,
            withBounds = withBounds,
            distinctActionCounts = actionCounts.size,
            deepestLabelFound = deepestFound,
            wideChildCount = wide,
        )
    }

    internal fun findByText(root: AccessibilityNodeInfo, query: String): List<AccessibilityNodeInfo> =
        runCatching { root.findAccessibilityNodeInfosByText(query) }.getOrDefault(emptyList())

    internal fun findByViewId(root: AccessibilityNodeInfo, id: String): List<AccessibilityNodeInfo> =
        runCatching { root.findAccessibilityNodeInfosByViewId(id) }.getOrDefault(emptyList())

    /**
     * The distinct `className`s under [root], bounded and sorted — a structural fingerprint of a screen that names no
     * user content. Used to show that a *system* app's tree is readable at all.
     */
    internal fun classesUnder(root: AccessibilityNodeInfo, maxNodes: Int = 4_000): List<String> {
        val classes = sortedSetOf<String>()
        var seen = 0
        fun walk(node: AccessibilityNodeInfo?) {
            if (node == null || seen >= maxNodes) return
            seen += 1
            node.className?.toString()?.let { classes.add(it) }
            for (index in 0 until node.childCount) walk(node.getChild(index))
        }
        walk(root)
        return classes.toList()
    }

    /** Every window the service can see, as `type:isActive:isFocused:bounds:package` — structural facts only. */
    internal fun windowSurvey(): List<String> = runCatching {
        windows.orEmpty().map { window ->
            val type = when (window.type) {
                AccessibilityWindowInfo.TYPE_APPLICATION -> "APPLICATION"
                AccessibilityWindowInfo.TYPE_INPUT_METHOD -> "INPUT_METHOD"
                AccessibilityWindowInfo.TYPE_SYSTEM -> "SYSTEM"
                AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY -> "A11Y_OVERLAY"
                AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER -> "SPLIT_DIVIDER"
                else -> "OTHER(${window.type})"
            }
            val pkg = runCatching { window.root?.packageName?.toString() }.getOrNull() ?: "unreadable"
            // The frame is what makes an occlusion claim checkable: "the node click worked under the overlay" only
            // means something if the overlay's rectangle is known to cover the node.
            val bounds = Rect()
            runCatching { window.getBoundsInScreen(bounds) }
            "$type:active=${window.isActive}:focused=${window.isFocused}" +
                ":bounds=[${bounds.left},${bounds.top}][${bounds.right},${bounds.bottom}]:pkg=$pkg"
        }
    }.getOrDefault(emptyList())

    /** The text of the first descendant of `windowRoots()` that matches [label], for windows other than the active one. */
    internal fun findInAnyWindow(label: String): Int = runCatching {
        var found = 0
        for (window in windows.orEmpty()) {
            val root = runCatching { window.root }.getOrNull() ?: continue
            found += findAccessibilityNodeInfosByTextOn(root, label)
        }
        found
    }.getOrDefault(-1)

    /** A manual subtree text match: `findAccessibilityNodeInfosByText` is unreliable inside virtual (WebView) trees. */
    internal fun findAccessibilityNodeInfosByTextOn(root: AccessibilityNodeInfo, label: String, maxNodes: Int = 2_000): Int {
        var found = 0
        var seen = 0
        fun walk(node: AccessibilityNodeInfo?) {
            if (node == null || seen >= maxNodes) return
            seen += 1
            if (node.text?.toString()?.contains(label) == true ||
                node.contentDescription?.toString()?.contains(label) == true
            ) {
                found += 1
            }
            for (index in 0 until node.childCount) walk(node.getChild(index))
        }
        walk(root)
        return found
    }

    /** The first node under [root] whose text is exactly [label], by manual walk. */
    internal fun firstNodeWithText(root: AccessibilityNodeInfo, label: String, maxNodes: Int = 2_000): AccessibilityNodeInfo? {
        var result: AccessibilityNodeInfo? = null
        var seen = 0
        fun walk(node: AccessibilityNodeInfo?) {
            if (node == null || result != null || seen >= maxNodes) return
            seen += 1
            if (node.text?.toString() == label) {
                result = node
                return
            }
            for (index in 0 until node.childCount) walk(node.getChild(index))
        }
        walk(root)
        return result
    }

    internal fun findNodeByViewId(id: String): AccessibilityNodeInfo? =
        activeWindowRoot()?.let { findByViewId(it, id).firstOrNull() }

    /**
     * Every non-blank visible text/contentDescription under [root], in tree order.
     *
     * These strings exist only inside this process and are used to *match* against constants the survey authored; none
     * of them is written to logcat, to a file or to the network (D53).
     */
    internal fun visibleLabels(root: AccessibilityNodeInfo, maxNodes: Int = 4_000): List<String> {
        val labels = mutableListOf<String>()
        var seen = 0
        fun walk(node: AccessibilityNodeInfo?) {
            if (node == null || seen >= maxNodes) return
            seen += 1
            if (node.isVisibleToUser) {
                node.text?.toString()?.takeIf { it.isNotBlank() }?.let { labels += it }
                node.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let { labels += it }
            }
            for (index in 0 until node.childCount) walk(node.getChild(index))
        }
        walk(root)
        return labels
    }

    /**
     * The distinct `viewIdResourceName`s under [root], bounded and sorted — resource identifiers, not content.
     *
     * This is what makes "does a *system* app report view ids" answerable on the device: the count and a small sample
     * say whether `findAccessibilityNodeInfosByViewId` has anything to work with at all on that app.
     */
    internal fun distinctViewIds(root: AccessibilityNodeInfo, maxNodes: Int = 4_000, cap: Int = 40): List<String> {
        val ids = sortedSetOf<String>()
        var seen = 0
        fun walk(node: AccessibilityNodeInfo?) {
            if (node == null || seen >= maxNodes || ids.size >= cap) return
            seen += 1
            node.viewIdResourceName?.let { ids.add(it) }
            for (index in 0 until node.childCount) walk(node.getChild(index))
        }
        walk(root)
        return ids.toList()
    }

    internal fun textOfViewId(id: String): String? =
        findNodeByViewId(id)?.text?.toString()

    internal fun perform(node: AccessibilityNodeInfo?, action: Int, args: android.os.Bundle? = null): Boolean =
        node != null && runCatching { node.performAction(action, args) }.getOrDefault(false)

    /** The node the platform says currently holds input focus, or null. */
    internal fun focusedEditable(): AccessibilityNodeInfo? =
        runCatching { findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }.getOrNull()

    companion object {
        const val TAG = "EdithA11ySurvey"

        /**
         * Both services live in one process and are enabled at the same time for the A/B read, so the holder is split by
         * class rather than being one slot: a single slot would have the second `onServiceConnected` overwrite the first
         * and the A/B comparison would silently compare a service with itself.
         */
        @Volatile
        var instance: A11ySurveyService? = null
            private set

        /** The flag-less twin (`A11ySurveyNoIdsService`), or null while it is not enabled in system settings. */
        @Volatile
        var noIdsInstance: A11ySurveyService? = null
            private set

        private fun register(service: A11ySurveyService) {
            if (service is A11ySurveyNoIdsService) noIdsInstance = service else instance = service
        }

        private fun unregister(service: A11ySurveyService) {
            if (service is A11ySurveyNoIdsService) {
                if (noIdsInstance === service) noIdsInstance = null
            } else {
                if (instance === service) instance = null
            }
        }

        /**
         * Event counts by `AccessibilityEvent.eventType`, for the "is `onAccessibilityEvent` worth using" question.
         *
         * Counted by the base class for **both** services, so with both enabled every event is counted twice. That is
         * deliberate and stated in the report: the histogram's *shape* is the measurement, its absolute size is not.
         */
        val eventCounts = ConcurrentHashMap<Int, AtomicInteger>()
        val totalEvents = AtomicInteger()
        val lastEventAtMillis = java.util.concurrent.atomic.AtomicLong(0L)

        /** One observed event: its SDK type constant and the `uptimeMillis` it arrived at. No content. */
        internal data class EventStamp(val type: Int, val at: Long)

        private const val MAX_TIMELINE = 2_000
        internal val eventTimeline = ArrayList<EventStamp>()

        fun resetEventCounters() {
            eventCounts.clear()
            totalEvents.set(0)
            lastEventAtMillis.set(0L)
            synchronized(eventTimeline) { eventTimeline.clear() }
        }

        /** The first event of [type] at or after [since], or -1 when none arrived — the latency half of the events question. */
        fun firstEventAfter(since: Long, type: Int? = null): Long = synchronized(eventTimeline) {
            eventTimeline.firstOrNull { it.at >= since && (type == null || it.type == type) }?.at ?: -1L
        }

        /** How long the screen has been quiet, in milliseconds; `-1` when no event has been seen at all yet. */
        fun quietMillis(): Long {
            val last = lastEventAtMillis.get()
            return if (last == 0L) -1L else SystemClock.uptimeMillis() - last
        }

        /** The event-type histogram as `type=count` pairs, biggest first. Types are SDK constants, not content. */
        fun eventHistogram(): String = eventCounts.entries
            .sortedByDescending { it.value.get() }
            .joinToString(",") { "${it.key}=${it.value.get()}" }
    }
}

/**
 * The flag-less twin: same behaviour, different `android:accessibilityFlags`. Its whole purpose is the A/B read in
 * `SURVEY_A_B_VIEWIDS` — one screen, two services, one flag apart. It needs no override: registration is keyed by class.
 */
class A11ySurveyNoIdsService : A11ySurveyService()
