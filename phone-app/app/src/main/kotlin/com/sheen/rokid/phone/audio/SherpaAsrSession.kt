package com.sheen.rokid.phone.audio
import com.sheen.rokid.audio.SpeechPipeline
import com.sheen.rokid.contracts.HostEvent
import com.sheen.rokid.contracts.TranscriptEvent
import com.sheen.rokid.contracts.TranscriptRevision
import com.sheen.rokid.plugin.AsrSession
import com.sheen.rokid.plugin.CancellationHandle

/**
 * The `Capability.Asr` provider: the speech pipeline's transcripts as the plugin-facing [AsrSession].
 *
 * The pipeline emits every revision it produces (partial and final) as a [`TranscriptEvent`]; a plugin consumes
 * them by calling [listen]. Production wires the two together by routing the pipeline's `emit` into [onEvent], so
 * both the host's event delivery and the plugin-facing session see the same revisions in the same order.
 *
 * **Cancellation is per-revision atomic (Task 11b, Minor 6).** [listen], the cancellation of an existing
 * listener and the dispatch of one revision all take [lock], and a listener is invoked while holding it. A
 * `cancel()` therefore either happens before a listener is reached - and that listener receives nothing of the
 * revision in flight - or after it was invoked, and then the cancellation applies to every later revision.
 * There is no window in which a cancelled listener receives a revision it was already cancelled for.
 *
 * The alternative the brief offered, invoking listeners outside the lock, leaves exactly that window: the
 * snapshot of listeners is taken before the invoke, so a `cancel()` racing the dispatch can still deliver one
 * revision to the cancelled listener. Pinning that instead would have been a weaker contract, so delivery sits
 * under the lock and the cost is documented here.
 *
 * The cost is deliberate: a listener's callback runs under the lock, so it must not block (it holds up the
 * other listeners and the thread that produced the revision) and must not call back into this session. The
 * pipeline itself is unaffected, because [onEvent] is invoked from the pipeline's `emit`, which runs outside
 * the pipeline's worker lock. Every listener is still invoked in isolation - one that throws cannot stop the
 * others, and the throw does not change any cancelled state.
 */
class SherpaAsrSession(val pipeline: SpeechPipeline) : AsrSession {

    private class Listener(val callback: (TranscriptRevision) -> Unit) { var cancelled = false }

    private val lock = Any()
    private val listeners = LinkedHashMap<Long, Listener>()
    private var nextId = 0L

    /** Feeds one pipeline host event; only [TranscriptEvent] carries a revision. */
    fun onEvent(event: HostEvent) {
        if (event is TranscriptEvent) dispatch(event.revision)
    }

    /**
     * Delivers [revision] to every listener that is registered and not cancelled at the moment it is reached,
     * in registration order.
     *
     * The snapshot is taken before any callback runs, so a listener's own `cancel()` (or one performed by an
     * earlier listener) cannot mutate the map being iterated; the per-listener check then makes the
     * cancellation effective for this very revision. Cancelled entries are dropped after the pass, so a long
     * session does not accumulate them.
     */
    fun dispatch(revision: TranscriptRevision) {
        synchronized(lock) {
            val snapshot = listeners.values.toList()
            snapshot.forEach { listener ->
                if (listener.cancelled) return@forEach
                runCatching { listener.callback(revision) }
            }
            listeners.values.removeIf { it.cancelled }
        }
    }

    override fun listen(listener: (TranscriptRevision) -> Unit): CancellationHandle {
        val entry = Listener(listener)
        val id: Long
        synchronized(lock) { id = nextId++; listeners[id] = entry }
        return object : CancellationHandle {
            override fun cancel() {
                synchronized(lock) {
                    if (entry.cancelled) return
                    entry.cancelled = true
                    listeners.remove(id)
                }
            }
        }
    }
}
