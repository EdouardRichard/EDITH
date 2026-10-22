package com.sheen.rokid.agent

import com.sheen.rokid.plugin.MonotonicClock

/** At most this many characters of a summary are ever stored; the rest is dropped, not summarised. */
private const val MAX_SUMMARY_CHARS = 200

/**
 * One audit line: the identity it was recorded with, the flattened [summary], and the monotonic stamp of the moment it
 * was written ([atNanos], from the [BoundedAuditLog]'s clock rather than a wall clock, so a line can be ordered against
 * a run without ever recording a real timestamp).
 */
data class AuditEntry(
    val turnId: String,
    val action: String,
    val summary: String,
    val outcome: String,
    val atNanos: Long,
    /** P3.3 (spec §17.5): the action's scope, risk level, policy decision and confirming channel, when known. */
    val scope: String? = null,
    val risk: String? = null,
    val decision: String? = null,
    val confirmationSource: ConfirmationSource? = null,
)

/**
 * The bounded in-memory [AuditSink] P3.1 can actually run (spec §7.4's encrypted on-disk store is a later phase).
 *
 * Its job is to be **honest and bounded**:
 * - honest — [turnId], [action] and [outcome] are stored verbatim; only the free-text `summary` is touched, and it is
 *   never invented or replaced with a placeholder;
 * - bounded — the summary is flattened to a single line and capped at [MAX_SUMMARY_CHARS], so a pasted screen dump or
 *   a notification body cannot be stored wholesale, and at most [capacity] entries are kept with the oldest evicted
 *   first (insertion order is preserved), so a long run cannot grow the log without limit.
 *
 * [entries] hands out a copy: a caller reading the trail can never reach in and mutate the recorder's own buffer.
 */
class BoundedAuditLog(
    private val capacity: Int = 512,
    private val clock: MonotonicClock,
) : AuditSink {

    private val buffer = ArrayDeque<AuditEntry>()

    init {
        require(capacity >= 1) { "BoundedAuditLog capacity must be at least 1, was $capacity" }
    }

    override fun record(
        turnId: String,
        action: String,
        summary: String,
        outcome: String,
        scope: String?,
        risk: String?,
        decision: String?,
        confirmationSource: ConfirmationSource?,
    ) {
        buffer += AuditEntry(
            turnId, action, flatten(summary), outcome, clock.nowNanos(),
            scope, risk, decision, confirmationSource,
        )
        while (buffer.size > capacity) buffer.removeFirst()
    }

    /** The trail so far, oldest first, as a defensive copy. */
    fun entries(): List<AuditEntry> = buffer.toList()

    fun clear() {
        buffer.clear()
    }

    /**
     * Collapses a free-text summary to one line of at most [MAX_SUMMARY_CHARS] characters. Every line break and
     * control character becomes a space rather than being removed: deleting them would splice two unrelated fragments
     * into a new sentence the model never produced, while a space keeps the boundary visible.
     */
    private fun flatten(summary: String): String = buildString {
        for (char in summary) {
            if (length == MAX_SUMMARY_CHARS) break
            append(if (char.isLineBreakOrControl()) ' ' else char)
        }
    }

    private fun Char.isLineBreakOrControl(): Boolean = isISOControl() || this == '\u2028' || this == '\u2029'
}
