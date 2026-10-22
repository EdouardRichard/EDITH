package com.sheen.rokid.glasses.bridge

import com.sheen.rokid.output.HintCommand
import com.sheen.rokid.output.HintWireCodec
import com.sheen.rokid.plugin.MonotonicClock

/**
 * Answers the phone's `PING` with the glasses' two stamps - the half of the T18 clock handshake that only the
 * glasses can produce.
 *
 * The phone sends `PING(syncId, p0)`, where `p0` is its own send instant. The glasses stamp `g1` when the message
 * arrives and `g2` when they reply; the phone then has all four timestamps of the exchange and can bound the
 * clock offset to `[g2 - p3, g1 - p0]` without assuming a symmetric network. That is why this must be answered
 * **immediately**, from the arrival path: any delay here is included in the interval and only widens it.
 *
 * Without this answer nothing downstream works - the phone never publishes a `SYNC`, the glasses hold no mapping,
 * and `GlassHintController` correctly refuses every `SHOW` as unmapped. It is a protocol obligation, not an
 * optimisation.
 *
 * Kept free of Android so the handshake can be tested off-device; the Activity supplies the real clock and the real
 * bridge send.
 */
class GlassClockResponder(
    private val clock: MonotonicClock,
    private val reply: (String) -> Boolean,
) {
    /** Answers one `PING`; returns whether the reply was accepted by the channel. */
    fun onPing(ping: HintCommand.Ping): Boolean {
        val receivedNanos = clock.nowNanos()
        val sentNanos = clock.nowNanos()
        val pong = HintCommand.Pong(syncId = ping.syncId, p0 = ping.p0, g1 = receivedNanos, g2 = sentNanos)
        return reply(HintWireCodec.encode(pong))
    }
}
