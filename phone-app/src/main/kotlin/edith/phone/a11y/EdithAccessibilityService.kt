package edith.phone.a11y

import android.accessibilityservice.AccessibilityService
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.security.MessageDigest

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
 */
class EdithAccessibilityService : AccessibilityService(), AccessibilityUi {

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
