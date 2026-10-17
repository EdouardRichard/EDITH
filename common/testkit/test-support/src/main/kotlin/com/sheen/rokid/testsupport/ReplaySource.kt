package com.sheen.rokid.testsupport

import com.sheen.rokid.contracts.InputEpoch

class ReplaySource(private val clock: FakeClock, private val offer: (InputEpoch, ByteArray) -> Unit) {
    fun replay(epoch: InputEpoch, packets: List<ByteArray>, intervalNanos: Long) {
        require(intervalNanos >= 0)
        packets.forEach { packet -> offer(epoch, packet); clock.advance(intervalNanos) }
    }
}
