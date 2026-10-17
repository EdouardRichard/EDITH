package com.sheen.rokid.phone.actions.a11y

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.security.MessageDigest
import java.util.UUID

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
 */
class EdithAccessibilityService : AccessibilityService(), AccessibilityUi {

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

    /** The service is driven on demand; events are not used for anything, so nothing is consumed here. */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

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

        /** The live UI port, or `null` while the user has the service switched off. */
        fun ui(): AccessibilityUi? = instance
    }
}
