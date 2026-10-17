package com.sheen.rokid.output

import com.sheen.rokid.contracts.Assistance
import com.sheen.rokid.contracts.OutputStatus
import com.sheen.rokid.plugin.MonotonicClock
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * The phone end of a hint acknowledgement.
 *
 * The glasses confirm a render with an `Ack`; until this existed nothing on the phone consumed one, so
 * `OutputStatus.Displayed` was unreachable and a hint could never be confirmed as shown - the arbiter only ever saw
 * the status it started with. This class sends the `Show`, correlates the reply, and reports what actually happened.
 *
 * Deliberately transport-free: it takes a `send` function and a clock, so every rule below is testable without a
 * device. The device adapter composes this with the CXR custom-command channel.
 *
 * The rules it enforces, each of which a cross-device acknowledgement gets wrong in practice:
 *  - one sequence per `Show`, because the reply channel is shared and correlation is positional;
 *  - an ack is adopted only for the same hint **and** the same revision, so a late render cannot overwrite a newer one;
 *  - the wire carries the hint's **remaining** life, not its absolute deadline, because the glasses hold a different
 *    clock;
 *  - a hint with no deadline is sent as unbounded rather than as already-expired;
 *  - a refused send is `Rejected`, never `Queued`;
 *  - nothing is reported after cancellation, and expiry fires once.
 */
class HintExchange(
    private val clock: MonotonicClock,
    private val send: (HintCommand) -> Boolean,
    /**
     * Identifies the clock mapping the glasses should use to interpret this hint's expiry.
     *
     * The wire contract requires it: a hint's TTL is meaningless without knowing which phone/glasses clock mapping it
     * was computed against, so the codec refuses a blank id. Injectable so a caller holding a live `ClockMapping` can
     * supply that mapping's id, and so tests are deterministic.
     */
    private val syncId: () -> String = { java.util.UUID.randomUUID().toString() },
) {
    private val sequences = AtomicLong(0)

    /**
     * Sends [assistance] and returns a handle whose [Handle.ack] accepts the glasses' confirmation.
     *
     * [beforeSend] is called with the new handle **before** the `SHOW` is sent, and it is deliberately not the last
     * parameter: a trailing lambda must keep meaning the status listener, as every existing caller expects.
     * A device adapter must register the hint there rather than after [show] returns, because an acknowledgement can
     * arrive on the same thread that sends it (a same-process or very fast loopback link) - a hint registered
     * afterwards would miss its own ack and be reported as expired instead.
     */
    fun show(assistance: Assistance, revision: Long, beforeSend: (Handle) -> Unit = {},
        listener: (OutputStatus) -> Unit): Handle {
        val sequence = sequences.incrementAndGet()
        val remainingTtl = remainingTtlMillis(assistance)
        val command = HintCommand.Show(
            sessionId = assistance.sessionId.value,
            generation = assistance.generation.value,
            sequence = sequence,
            hintId = assistance.hintId,
            revision = revision,
            syncId = syncId(),
            phoneSentNanos = clock.nowNanos(),
            remainingTtlMillis = remainingTtl,
            text = assistance.text,
            // Phase C (spec §6.6): the glasses file a hint into the conversation it belongs to, and this is the only
            // place that knows it - the arbiter hands over the plugin's `Assistance` unchanged.
            route = assistance.route,
        )
        val handle = Handle(
            hintId = assistance.hintId,
            sequence = sequence,
            revision = revision,
            expiresAtNanos = assistance.expiresAtNanos,
            listener = listener,
        )
        beforeSend(handle)
        // The status is reported only after the send result is known: announcing Queued for a hint that never left
        // the phone would be a silent loss. The send itself can deliver an acknowledgement on this thread, so the
        // handle holds those reports back: `Queued`/`Rejected` must reach the caller before the ack it caused, or the
        // host's arbiter would see `Displayed` and then `Queued` for the same hint.
        handle.beginSend()
        return handle.finishSend(if (send(command)) OutputStatus.Queued else OutputStatus.Rejected)
    }

    /**
     * How long the glasses may keep showing this hint.
     *
     * `0` means "no deadline" in [Assistance], which must **not** travel as a zero TTL: the glasses would read that as
     * already-expired and drop the hint. An unbounded hint therefore gets [UNBOUNDED_TTL_MILLIS]. An expired hint gets
     * the smallest positive TTL, so it is sent and immediately retired rather than silently vanishing.
     */
    private fun remainingTtlMillis(assistance: Assistance): Long {
        if (assistance.expiresAtNanos <= 0L) return UNBOUNDED_TTL_MILLIS
        val remainingNanos = assistance.expiresAtNanos - clock.nowNanos()
        if (remainingNanos <= 0L) return 1L
        return remainingNanos / 1_000_000L
    }

    /** One in-flight hint. Not reusable: its sequence and revision belong to a single `Show`. */
    inner class Handle internal constructor(
        val hintId: String,
        val sequence: Long,
        private val revision: Long,
        private val expiresAtNanos: Long,
        private val listener: (OutputStatus) -> Unit,
    ) : com.sheen.rokid.plugin.CancellationHandle {
        private val cancelled = AtomicBoolean(false)
        private val expired = AtomicBoolean(false)
        private val cancellations = AtomicLong(0)
        private val terminal = AtomicBoolean(false)

        @Volatile private var lastStatus: OutputStatus? = null

        /**
         * True when the hint never left the phone, so no acknowledgement can ever arrive for it.
         *
         * Callers use this to avoid registering a hint that nothing will ever complete.
         */
        val rejected: Boolean get() = lastStatus == OutputStatus.Rejected

        /**
         * True once the hint reached a status nothing further can change.
         *
         * Used to drop the hint from the in-flight set: otherwise that set grows for the life of the session and an
         * old sequence could be re-adopted by a later corroborating message.
         */
        val finished: Boolean get() = terminal.get()

        /** Consumes the glasses' confirmation, or ignores it when it is not for this hint and revision. */
        fun ack(ack: HintCommand.Ack) {
            if (ack.sequence != sequence) return
            if (ack.hintId != hintId) return
            // A stale revision means the glasses confirmed an older render; adopting it would overwrite newer state.
            if (ack.revision != revision) return
            report(ack.status)
        }

        /** Stops this exchange. Any later ack is ignored rather than reviving a finished hint. */
        override fun cancel() {
            if (cancelled.compareAndSet(false, true)) cancellations.incrementAndGet()
        }

        /**
         * Reports [OutputStatus.Expired] once the hint's own deadline has passed with no confirmation.
         *
         * Bounded to a single report because nothing else will ever arrive for an expired hint; repeating it would
         * make the host act on the same expiry many times over.
         */
        fun expireIfDue(nowNanos: Long) {
            if (expiresAtNanos <= 0L) return
            if (nowNanos < expiresAtNanos) return
            if (!expired.compareAndSet(false, true)) return
            report(OutputStatus.Expired)
        }

        internal fun cancellationCount(): Long = cancellations.get()

        /** Set while the `SHOW` is being sent; reports that arrive then are held back rather than reordered. */
        private val sendGate = Any()
        private var sending = false
        private val buffered = ArrayDeque<OutputStatus>()

        internal fun beginSend() = synchronized(sendGate) { sending = true }

        /**
         * Closes the send window and emits [status] **first**, then anything the send itself produced.
         *
         * The order is the whole point: an acknowledgement is a consequence of the `SHOW`, so the caller must see
         * `Queued` (or `Rejected`) before it. Emitting the ack first would tell the host's arbiter that a hint is
         * displayed and then that it is merely queued.
         */
        internal fun finishSend(status: OutputStatus): Handle {
            val emit: List<OutputStatus>
            synchronized(sendGate) {
                sending = false
                emit = buildList { add(status); addAll(buffered) }
                buffered.clear()
            }
            emit.forEach { deliver(it) }
            return this
        }

        internal fun report(status: OutputStatus) {
            synchronized(sendGate) {
                if (sending) { buffered.addLast(status); return }
            }
            deliver(status)
        }

        private fun deliver(status: OutputStatus) {
            if (cancelled.get()) return
            lastStatus = status
            // Queued is the only status the glasses can still change; everything else is final for this hint.
            if (status != OutputStatus.Queued) terminal.set(true)
            listener(status)
        }
    }

    companion object {
        /**
         * The TTL used for a hint with no deadline. Positive because a zero TTL means "expired" on the wire, and large
         * enough to outlive any session the glasses will hold.
         */
        const val UNBOUNDED_TTL_MILLIS = 24L * 60L * 60L * 1000L

        /**
         * A wire-safe `syncId` for the case where a hint is attempted before any clock mapping exists.
         *
         * It is deliberately a named marker rather than a fresh identifier: it makes the absence of a mapping visible
         * on the wire instead of impersonating one. The composition gates the display path on a live
         * [ClockMapping] (see `CxrHintLink`), and the glasses refuse a `SHOW` whose `syncId` has no mapping, so this
         * value is the honest fallback rather than a way to make a hint display.
         */
        const val UNSYNCED_SYNC_ID = "unsynced"
    }
}
