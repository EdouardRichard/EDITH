package com.sheen.rokid.phone.actions.a11y
import java.security.MessageDigest
import java.util.UUID

/**
 * ScreenSnapshot data shape (EDITH 0.21 Phase 1, T1.2).
 *
 * A bounded, filterable, stable view of the accessibility node tree. The agent loop uses snapshots to reason about
 * what is on screen without touching a real device in tests and without re-walking the tree on every query.
 *
 * Two rules from the plan:
 * - **Stability**: the same tree read twice produces identical indices and content.
 * - **Boundedness**: the walk stops at [MAX_NODES] and reports truncation rather than running away on deep trees.
 *
 * The snapshot is invalidated after any mutating action; using a stale [snapshotId] returns [SnapshotRefusal.STALE_SNAPSHOT].
 */

// ── Data shapes ──────────────────────────────────────────────────────────────

data class ScreenSnapshot(
    val snapshotId: String,
    val elements: List<ScreenElement>,
    val truncated: Boolean,
    val fingerprint: ScreenFingerprint,
)

data class ScreenElement(
    val index: Int,
    val text: String?,
    val isHintText: Boolean,
    val clickable: Boolean,
    val editable: Boolean,
    val scrollable: Boolean,
    val checked: Boolean,
    val boundsDigest: String,
)

data class ScreenFingerprint(
    val hash: String,
    val nodeCount: Int,
    val clickableCount: Int,
    val truncated: Boolean,
)

enum class ScreenFilter { ALL, INTERACTIVE, TEXT }

/**
 * Recognizable refusal codes for snapshot operations.
 * These are returned instead of exceptions so callers can distinguish "wrong index" from "stale snapshot"
 * without parsing error messages.
 */
enum class SnapshotRefusal {
    STALE_SNAPSHOT,
    INDEX_OUT_OF_BOUNDS,
}

// ── Intermediate abstraction ─────────────────────────────────────────────────

/**
 * Raw node data extracted from the accessibility tree.
 * This is the contract between the platform-specific node walker and the platform-independent [SnapshotBuilder].
 * Tests implement this via a fake; production will implement it via [EdithAccessibilityService] in T1.3.
 */
data class RawSnapshotNode(
    val text: String?,
    val contentDescription: String?,
    val className: String?,
    val clickable: Boolean,
    val editable: Boolean,
    val scrollable: Boolean,
    val checked: Boolean,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val hintText: String?,
)

/**
 * Source of raw nodes for snapshot construction.
 * Decouples the builder from the platform-specific tree walking mechanism.
 */
interface SnapshotNodeSource {
    /** Walks the tree and returns nodes matching [filter], up to [maxNodeLimit] nodes. */
    fun walkNodes(filter: ScreenFilter): List<RawSnapshotNode>

    /** The maximum number of nodes the source will visit in a single walk. */
    fun maxNodeLimit(): Int
}

// ── Builder ──────────────────────────────────────────────────────────────────

/** How many nodes one snapshot walk may visit. Matches the existing fingerprint limit. */
const val MAX_NODES = 400

object SnapshotBuilder {

    /**
     * Builds a [ScreenSnapshot] from [source] with the given [filter].
     *
     * The builder:
     * - walks the tree through [SnapshotNodeSource.walkNodes]
     * - assigns sequential indices starting at 0
     * - detects hint text (text == hintText on an editable node)
     * - computes a structural fingerprint (SHA-256 of element facts)
     * - generates a unique snapshotId
     * - respects the node limit and sets truncated when the walk was capped
     */
    fun build(source: SnapshotNodeSource, filter: ScreenFilter = ScreenFilter.ALL): ScreenSnapshot {
        val rawNodes = source.walkNodes(filter)
        val limit = source.maxNodeLimit()
        val truncated = rawNodes.size >= limit && limit > 0

        val elements = rawNodes.mapIndexed { index, raw ->
            val isHint = raw.editable && !raw.hintText.isNullOrBlank() && raw.text == raw.hintText
            ScreenElement(
                index = index,
                text = raw.text,
                isHintText = isHint,
                clickable = raw.clickable,
                editable = raw.editable,
                scrollable = raw.scrollable,
                checked = raw.checked,
                boundsDigest = boundsDigest(raw),
            )
        }

        val clickableCount = elements.count { it.clickable }
        val hash = computeHash(elements, truncated)

        return ScreenSnapshot(
            snapshotId = UUID.randomUUID().toString(),
            elements = elements,
            truncated = truncated,
            fingerprint = ScreenFingerprint(
                hash = hash,
                nodeCount = rawNodes.size,
                clickableCount = clickableCount,
                truncated = truncated,
            ),
        )
    }

    private fun boundsDigest(raw: RawSnapshotNode): String =
        "${raw.left},${raw.top},${raw.right},${raw.bottom}"

    private fun computeHash(elements: List<ScreenElement>, truncated: Boolean): String {
        val digest = MessageDigest.getInstance("SHA-256")
        for (e in elements) {
            digest.update((e.text ?: "").toByteArray(Charsets.UTF_8))
            digest.update(byteArrayOf(
                if (e.clickable) 1 else 0,
                if (e.editable) 1 else 0,
                if (e.scrollable) 1 else 0,
                if (e.checked) 1 else 0,
                if (e.isHintText) 1 else 0,
            ))
            digest.update(e.boundsDigest.toByteArray(Charsets.UTF_8))
        }
        digest.update(byteArrayOf(if (truncated) 1 else 0))
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

// ── Validator ────────────────────────────────────────────────────────────────

/**
 * Validates snapshot references before use.
 * Returns a [SnapshotRefusal] when the reference is invalid, `null` when it is valid.
 */
object SnapshotValidator {

    /** Checks whether [snapshotId] matches the current [snapshot]. Returns a refusal code if stale. */
    fun validate(snapshotId: String, snapshot: ScreenSnapshot): SnapshotRefusal? {
        return if (snapshotId != snapshot.snapshotId) SnapshotRefusal.STALE_SNAPSHOT else null
    }

    /** Checks whether [index] is within [snapshot]'s element list. Returns a refusal code if out of bounds. */
    fun validateIndex(snapshot: ScreenSnapshot, index: Int): SnapshotRefusal? {
        return if (index < 0 || index >= snapshot.elements.size) SnapshotRefusal.INDEX_OUT_OF_BOUNDS else null
    }
}
