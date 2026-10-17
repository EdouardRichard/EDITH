package com.sheen.rokid.host

import com.sheen.rokid.contracts.*

class HostEventDispatcher(
    private val capacity: Int,
    private val deliver: (HostEvent) -> Unit,
    private val onFault: (HostEvent) -> Unit,
) {
    private val lock = Any()
    private val drainLock = Any()
    private val queue = mutableListOf<HostEvent>()
    private val seen = linkedSetOf<Triple<SessionId, Generation, String>>()
    private val revisions = mutableMapOf<Triple<SessionId, Generation, String>, TranscriptRevision>()
    init { require(capacity > 0) }
    internal val hasPending get() = synchronized(lock) { queue.isNotEmpty() }

    fun offer(event: HostEvent): Boolean {
        var fault = false
        val accepted = synchronized(lock) {
            val identity = Triple(event.envelope.sessionId, event.envelope.generation, event.envelope.eventId)
            if (identity in seen) return@synchronized false
            val next = (event as? TranscriptEvent)?.revision
            val turn = next?.let { Triple(it.envelope.sessionId, it.envelope.generation, it.turnId) }
            val previous = revisions[turn]
            if (next != null && previous != null && (previous.final || !next.isNewerThan(previous))) return@synchronized false
            val slot = if (turn == null) -1 else queue.indexOfFirst {
                it is TranscriptEvent && !it.revision.final &&
                    Triple(it.envelope.sessionId, it.envelope.generation, it.revision.turnId) == turn
            }
            if ((slot < 0 && queue.size >= capacity) || seen.size >= 4096 || (turn !in revisions && revisions.size >= 4096)) {
                fault = event !is TranscriptEvent || event.revision.final
                return@synchronized false
            }
            if (slot >= 0) queue[slot] = event else queue += event
            seen += identity
            if (next != null) revisions[requireNotNull(turn)] = next
            true
        }
        if (fault) onFault(DiscontinuityEvent(event.envelope, GapReason.OVERFLOW))
        return accepted
    }

    fun drain() = synchronized(drainLock) {
        while (true) {
            val next = synchronized(lock) { if (queue.isEmpty()) null else queue.removeAt(0) } ?: break
            deliver(next)
        }
    }

    fun clear() { synchronized(lock) { queue.clear(); seen.clear(); revisions.clear() } }
}
