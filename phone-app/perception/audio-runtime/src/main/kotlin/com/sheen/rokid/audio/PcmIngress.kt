package com.sheen.rokid.audio

import com.sheen.rokid.contracts.*
import com.sheen.rokid.plugin.MonotonicClock
import java.util.UUID

enum class PcmOffer { ACCEPTED, OVERFLOW, UNKNOWN_FORMAT, STALE_EPOCH }

data class PcmChunk(val epoch: InputEpoch, val sequence: Long, val receivedNanos: Long,
    val bytes: ByteArray, val continuityId: String)

data class PcmGap(val epoch: InputEpoch, val continuityId: String, val reason: GapReason)

class PcmIngress(private val clock: MonotonicClock, private val maxBufferMillis: Int = 2000) {
    private val queue = ArrayDeque<PcmChunk>()
    private var epoch: InputEpoch? = null
    private var format: VerifiedPcm? = null
    private var capacity = 0L
    private var sequence = 0L
    private val gaps = ArrayDeque<PcmGap>()
    private val maxPendingGaps = 64
    var queuedBytes: Int = 0
        private set
    var continuityId: String = UUID.randomUUID().toString()
        private set
    init { require(maxBufferMillis in 1..60_000) }

    @Synchronized fun open(epoch: InputEpoch, format: VerifiedPcm?) {
        checkGapCapacity()
        val nextCapacity = format?.let { Math.multiplyExact(it.format.sampleRateHz.toLong() * it.format.channels *
            (it.format.bitsPerSample / 8), maxBufferMillis.toLong()) / 1000 } ?: 0
        require(nextCapacity <= Int.MAX_VALUE)
        if (this.epoch != null) reset(if (this.epoch == epoch && this.format != format)
            GapReason.FORMAT_CHANGED else GapReason.SESSION_INTERRUPTED)
        checkGapCapacity()
        queue.clear(); queuedBytes = 0
        this.epoch = epoch; this.format = format
        capacity = nextCapacity
        continuityId = UUID.randomUUID().toString(); sequence = 0
    }

    @Synchronized fun offer(epoch: InputEpoch, bytes: ByteArray): PcmOffer {
        if (this.epoch != epoch) return PcmOffer.STALE_EPOCH
        if (format == null) return PcmOffer.UNKNOWN_FORMAT
        if (bytes.isEmpty()) return PcmOffer.ACCEPTED
        if (queuedBytes.toLong() + bytes.size > capacity) { reset(GapReason.OVERFLOW); return PcmOffer.OVERFLOW }
        queue += PcmChunk(epoch, sequence++, clock.nowNanos(), bytes.copyOf(), continuityId)
        queuedBytes += bytes.size
        return PcmOffer.ACCEPTED
    }

    @Synchronized fun poll(): PcmChunk? = queue.removeFirstOrNull()?.also { queuedBytes -= it.bytes.size }
    @Synchronized fun pollGap(): PcmGap? = gaps.removeFirstOrNull()
    @Synchronized fun reset(reason: GapReason) {
        queue.clear(); queuedBytes = 0
        epoch?.let {
            gaps += PcmGap(it, continuityId, reason)
            // Stop intake when the worker cannot drain notifications; never discard an affected identity.
            if (gaps.size == maxPendingGaps) { epoch = null; format = null }
        }
        continuityId = UUID.randomUUID().toString()
    }
    @Synchronized fun close() {
        reset(GapReason.SESSION_INTERRUPTED)
        epoch = null; format = null
    }
    private fun checkGapCapacity() {
        if (gaps.size >= maxPendingGaps) throw ContractError.ResourceConflict("PCM gap backlog must be drained")
    }
}
