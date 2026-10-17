package com.sheen.rokid.output

import com.sheen.rokid.contracts.Assistance
import com.sheen.rokid.contracts.Generation
import com.sheen.rokid.contracts.OutputStatus
import com.sheen.rokid.contracts.SessionId
import com.sheen.rokid.plugin.CancellationHandle
import com.sheen.rokid.plugin.HintDevice
import com.sheen.rokid.plugin.MonotonicClock
import java.util.concurrent.ConcurrentHashMap

/**
 * The production [HintDevice]: what the App calls to put a hint on the glasses and to learn what happened to it.
 *
 * This is the composition that was missing. [HintExchange] owned the acknowledgement rules and [HintWireCodec] owned
 * the wire format, but nothing joined them, so `OutputStatus.Displayed` was unreachable in the running App - a hint
 * could be sent and never confirmed. This adapter sends encoded commands through the CXR custom-command channel and
 * routes inbound acknowledgements to the hint that is actually in flight.
 *
 * The wire is injected (`sendJson` plus [onCommand]) so these rules are provable without a device; the CXR wiring is a
 * one-line composition in the App.
 *
 * Correlation is by sequence, and only for hints still in flight: an acknowledgement for a hint this device never
 * showed, or for one already finished, is ignored rather than adopted. An undecodable inbound message is dropped
 * because this runs on the SDK's callback thread, where a throw would take the session down.
 */
class RokidHintDevice(
    clock: MonotonicClock,
    private val sendJson: (String) -> Boolean,
    /**
     * Identifies the clock mapping the glasses must use for this hint's deadline.
     *
     * Defaulted to a fresh identifier so the device is usable on its own, but a composition that holds a live
     * `ClockMapping` (see [HintClockSync]) must supply its id: the glasses refuse a `SHOW` whose `syncId` has no
     * mapping, so a generated id means the hint is silently dropped on their side.
     */
    syncId: () -> String = { java.util.UUID.randomUUID().toString() },
) : HintDevice {

    private val exchange = HintExchange(clock = clock, send = { command -> sendJson(HintWireCodec.encode(command)) },
        syncId = syncId)

    /** Hints awaiting an acknowledgement, keyed by the sequence their SHOW carried. */
    private val inFlight = ConcurrentHashMap<Long, HintExchange.Handle>()

    override fun show(assistance: Assistance, revision: Long, listener: (OutputStatus) -> Unit): CancellationHandle {
        // Registered inside the exchange's before-send hook, not after it returns: an acknowledgement can arrive on
        // the sending thread, and a hint registered afterwards would miss its own ack and be reported as expired.
        val handle = exchange.show(assistance, revision,
            beforeSend = { dispatched -> inFlight[dispatched.sequence] = dispatched },
            listener = listener)
        // A hint that never left the phone has no acknowledgement to wait for, and one that was already confirmed
        // before this call returned must not be re-registered as in flight.
        if (handle.rejected || handle.finished) {
            inFlight.remove(handle.sequence)
            return handle
        }
        // Cancelling must take the hint off the display, not merely stop reporting about it, so the handle returned to
        // the App also sends the CLEAR that this device owns.
        return CancellingHandle(handle, assistance.sessionId, assistance.generation, ::clear)
    }

    override fun clear(sessionId: SessionId, generation: Generation) {
        sendJson(
            HintWireCodec.encode(
                HintCommand.Clear(sessionId = sessionId.value, generation = generation.value, sequence = 0L),
            ),
        )
    }

    /** Stops the local exchange and clears the hint on the glasses, in that order. */
    private class CancellingHandle(
        private val delegate: HintExchange.Handle,
        private val sessionId: SessionId,
        private val generation: Generation,
        private val clear: (SessionId, Generation) -> Unit,
    ) : CancellationHandle {
        private var cleared = false

        override fun cancel() {
            delegate.cancel()
            if (cleared) return
            cleared = true
            clear(sessionId, generation)
        }
    }

    /**
     * Entry point for commands received from the glasses.
     *
     * Returns true when the message was understood and handled, so a caller can log the ones that were not. Never
     * throws: this is invoked from the SDK's callback thread.
     */
    fun onCommand(json: String): Boolean {
        val command = HintWireCodec.decode(json) ?: return false
        return when (command) {
            is HintCommand.Ack -> {
                val handle = inFlight[command.sequence] ?: return false
                handle.ack(command)
                // A finished hint must leave the map, or the map grows for the life of the session and a stale
                // sequence could be re-adopted.
                if (handle.finished) inFlight.remove(command.sequence)
                true
            }
            else -> false
        }
    }

    /** Hints still awaiting a confirmation; exposed so a caller can bound or report the set. */
    val inFlightCount: Int get() = inFlight.size
}
