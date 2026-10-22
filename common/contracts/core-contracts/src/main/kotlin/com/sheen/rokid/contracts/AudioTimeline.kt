package com.sheen.rokid.contracts

data class InputEpoch(val sessionId: SessionId, val generation: Generation, val serial: Long) {
    init { require(serial >= 0) }
}

enum class PcmEncoding { SIGNED_LE, SIGNED_BE }

data class VerifiedPcm(val format: AudioFormat.Pcm, val encoding: PcmEncoding, val evidenceId: String) {
    init {
        require(evidenceId.isNotBlank())
        require(format.sampleRateHz in 8000..192000)
        require(format.channels in 1..8)
        require(format.bitsPerSample in setOf(8, 16, 24, 32))
    }
}

data class SampleRange(val continuityId: String, val first: Long, val endExclusive: Long) {
    init {
        require(continuityId.isNotBlank())
        require(first >= 0)
        require(endExclusive >= first)
    }
}

enum class GapReason { OVERFLOW, FORMAT_CHANGED, SESSION_INTERRUPTED }
