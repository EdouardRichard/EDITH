package com.sheen.rokid.contracts

enum class SessionState {
    Idle, Authorizing, Connecting, BuildingScene, Ready, Listening,
    Paused, Interrupted, Reconnecting, Error,
}

sealed interface AudioFormat {
    data object Unknown : AudioFormat
    data class Pcm(val sampleRateHz: Int, val channels: Int, val bitsPerSample: Int) : AudioFormat {
        init {
            require(sampleRateHz > 0)
            require(channels > 0)
            require(bitsPerSample > 0)
        }
    }
}

data class AudioSegment(
    val envelope: EventEnvelope,
    val format: AudioFormat,
    val pcm: ByteArray,
) {
    override fun equals(other: Any?): Boolean = other is AudioSegment && envelope == other.envelope && format == other.format && pcm.contentEquals(other.pcm)
    override fun hashCode(): Int = 31 * (31 * envelope.hashCode() + format.hashCode()) + pcm.contentHashCode()
}
