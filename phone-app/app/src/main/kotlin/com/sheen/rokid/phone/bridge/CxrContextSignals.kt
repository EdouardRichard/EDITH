package com.sheen.rokid.phone.bridge
import com.sheen.rokid.phone.plugin.smarthelper.host.ProactiveArming

import com.sheen.rokid.plugin.CancellationHandle
import com.sheen.rokid.plugin.ContextSignals
import com.sheen.rokid.plugin.ContextSnapshot
import com.sheen.rokid.plugin.MonotonicClock
import java.time.LocalTime

/**
 * The App's context signals (P3.2 Task 6, spec §16.1#1).
 *
 * Stage A exposes only the keys that need no permission - the local time and which part of the day it is - and
 * Stage B appends its three permissioned sources to [sources]. Nothing here decides what a plugin may see beyond the
 * host's `Capability.Context` grant; this type only assembles the values and reports changes.
 *
 * **Change detection rides on reads.** [snapshot] is the only way values are computed, and it is also where a change
 * is noticed: the first snapshot establishes the baseline, and a later snapshot whose *values* differ notifies every
 * [listen]er exactly once. That is deliberate - the only driver in P3.2 is the plugin reading context (during a
 * proactive turn), and an unread context has no listener waiting for it. A future source that must *push* (Stage B's
 * notification source, say) needs a polling driver of its own; that is a scheduler question, not a values question.
 *
 * [ContextSnapshot.capturedAtNanos] is the monotonic [clock], because that is the time base the host's envelope
 * validation uses. The human-readable `time.local` therefore cannot come from it - a monotonic clock counts from boot,
 * not from the epoch - so it is read from [localTime] instead.
 */
internal class CxrContextSignals(
    private val clock: MonotonicClock,
    private val sources: List<() -> Map<String, String>> = emptyList(),
    /** The wall clock the `time.*` keys are derived from; injectable so a test can pin a literal hour. */
    private val localTime: () -> LocalTime = { LocalTime.now() },
) : ContextSignals {

    private val lock = Any()
    private val listeners = CopyOnWriteList<(ContextSnapshot) -> Unit>()
    private var last: ContextSnapshot? = null

    override fun snapshot(): ContextSnapshot {
        val next = compose()
        val changed = synchronized(lock) {
            val previous = last
            last = next
            previous != null && previous.values != next.values
        }
        if (changed) listeners.forEach { it(next) }
        return next
    }

    override fun listen(listener: (ContextSnapshot) -> Unit): CancellationHandle {
        listeners += listener
        return object : CancellationHandle {
            override fun cancel() { listeners -= listener }
        }
    }

    /** Sources in order, then the two time keys - later writers win, and blanks never become values. */
    private fun compose(): ContextSnapshot {
        val values = LinkedHashMap<String, String>()
        sources.forEach { source ->
            source().forEach { (key, value) -> if (key.isNotBlank() && value.isNotBlank()) values[key] = value }
        }
        val now = localTime()
        values[TIME_LOCAL] = "%02d:%02d".format(now.hour, now.minute)
        values[TIME_PERIOD] = period(now)
        return ContextSnapshot(clock.nowNanos(), values)
    }

    private fun period(time: LocalTime): String = when (time.hour) {
        in 5..11 -> "morning"
        in 12..17 -> "afternoon"
        in 18..22 -> "evening"
        else -> "night"
    }

    companion object {
        const val TIME_LOCAL = "time.local"
        const val TIME_PERIOD = "time.period"

        /** The Stage A provider: time keys only. */
        fun timeOnly(clock: MonotonicClock): CxrContextSignals = CxrContextSignals(clock)

        /**
         * The host's own **effective** 「主动辅助」 state, as one context source.
         *
         * The composition appends this to the Stage A sources, so the arming switch is readable at request time by the
         * plugin that has to honour it (see [com.sheen.rokid.phone.plugin.smarthelper.host.ProactiveArming.CONTEXT_KEY]). 0.4 Task 5:
         * the value is `stored && !detached` (`D202`), so a detached session publishes `false` without rewriting the
         * user's stored switch. It is a source rather than a fourth constructor parameter so the plugin-facing key set
         * stays one flat projection.
         */
        fun armingSource(arming: com.sheen.rokid.phone.plugin.smarthelper.host.ProactiveArming): () -> Map<String, String> =
            { mapOf(com.sheen.rokid.phone.plugin.smarthelper.host.ProactiveArming.CONTEXT_KEY to arming.effectiveProactiveArmed.toString()) }
    }
}

/**
 * A tiny copy-on-write list, so a listener that cancels (or a snapshot that notifies) never mutates the collection
 * being iterated. `java.util.concurrent.CopyOnWriteArrayList` would do the same; keeping it local avoids exposing a
 * mutable collection type through the class.
 */
private class CopyOnWriteList<T> {
    @Volatile private var items: List<T> = emptyList()
    operator fun plusAssign(item: T) { synchronized(this) { items = items + item } }
    operator fun minusAssign(item: T) { synchronized(this) { items = items - item } }
    fun forEach(action: (T) -> Unit) { items.forEach(action) }
}
