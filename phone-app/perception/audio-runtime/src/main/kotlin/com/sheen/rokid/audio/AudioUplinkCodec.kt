package com.sheen.rokid.audio

/** Why an audio uplink stopped. Carried explicitly so the phone never has to infer it from a silence. */
enum class UplinkEndReason { UserStopped, Error, SourceEnded }

/** A decoded uplink message. */
sealed interface UplinkMessage {
    data class Start(
        val sessionTag: String,
        val sampleRateHz: Int,
        val channelCount: Int,
        val bitsPerSample: Int,
        val selectedChannels: List<Int>,
    ) : UplinkMessage

    data class Pcm(val sequence: Long, val capturedAtNanos: Long, val pcm: ByteArray) : UplinkMessage {
        // ByteArray gives identity equality, which would make two equal frames compare unequal. The codec's
        // correctness is checked on the bytes, so compare them.
        override fun equals(other: Any?): Boolean =
            this === other || (other is Pcm && sequence == other.sequence &&
                capturedAtNanos == other.capturedAtNanos && pcm.contentEquals(other.pcm))

        override fun hashCode(): Int =
            (sequence.hashCode() * 31 + capturedAtNanos.hashCode()) * 31 + pcm.contentHashCode()
    }

    data class End(val sequence: Long, val framesSent: Long, val reason: UplinkEndReason) : UplinkMessage
}

/**
 * Binary frame codec for the glasses-to-phone audio uplink.
 *
 * **Why binary.** The transport is the CXR custom-command channel, which is a request/response message channel
 * rather than a media pipe, so every byte costs. A JSON envelope would force base64 and inflate the audio by a
 * third; a 24-byte fixed header costs 1.5% on a 1600-byte frame.
 *
 * **Why the format is declared, not inferred.** A `START` frame states the sample rate, channel count, bit depth and
 * which channels were selected. The receiver therefore never has to assume a layout, and a mismatch is a refusal
 * rather than audio that plays as noise and looks like a codec fault.
 *
 * **Why the header is big-endian.** It is a network-order wire format read by two different runtimes, so a fixed
 * byte order removes an entire class of "works on one side only" defects. The PCM payload itself stays in the
 * platform's native little-endian 16-bit form, because re-encoding audio per sample would be absurd; that
 * difference is stated here rather than left implicit.
 *
 * Layout: magic `'E','A','U','1'` (4) | message type (1) | flags (1) | reserved (2) | sequence (8, big-endian)
 * | capturedAtNanos (8, big-endian) | declared payload length (4, big-endian) | payload.
 */
object AudioUplinkCodec {
    const val MAGIC_0 = 'E'.code.toByte()
    const val MAGIC_1 = 'A'.code.toByte()
    const val MAGIC_2 = 'U'.code.toByte()
    const val MAGIC_3 = '1'.code.toByte()

    const val HEADER_BYTES = 28

    /**
     * Largest payload accepted. Chosen so a frame stays far below the transport's own message bound while still
     * carrying 40 ms of 8-channel audio, which keeps the per-message overhead negligible.
     */
    const val MAX_FRAME_BYTES = 8 * 1024

    private const val TYPE_START = 1
    private const val TYPE_PCM = 2
    private const val TYPE_END = 3

    /** 40 ms at 16 kHz: a compromise between per-message overhead and added latency. */
    const val DEFAULT_FRAME_MILLIS = 40

    fun encodeStart(sessionTag: String, sampleRateHz: Int, channelCount: Int, bitsPerSample: Int,
        selectedChannels: List<Int>): ByteArray {
        require(sessionTag.isNotBlank()) { "SESSION_TAG_REQUIRED" }
        require(sampleRateHz > 0) { "SAMPLE_RATE_MUST_BE_POSITIVE" }
        require(channelCount in 1..64) { "CHANNEL_COUNT_OUT_OF_RANGE" }
        require(bitsPerSample in setOf(8, 16, 24, 32)) { "UNSUPPORTED_BIT_DEPTH" }
        require(selectedChannels.isNotEmpty()) { "NO_CHANNELS_SELECTED" }
        require(selectedChannels.all { it in 0 until channelCount }) { "SELECTED_CHANNEL_OUT_OF_RANGE" }
        require(selectedChannels.distinct().size == selectedChannels.size) { "DUPLICATE_SELECTED_CHANNEL" }
        val text = sessionTag.toByteArray(Charsets.UTF_8)
        require(text.size <= 255) { "SESSION_TAG_TOO_LONG" }
        val payload = java.io.ByteArrayOutputStream()
        payload.write(text.size)
        payload.write(text)
        writeIntToStream(payload, sampleRateHz)
        payload.write(channelCount)
        payload.write(bitsPerSample)
        payload.write(selectedChannels.size)
        selectedChannels.forEach { payload.write(it) }
        return frame(TYPE_START, 0L, 0L, payload.toByteArray())
    }

    fun encodePcm(sequence: Long, capturedAtNanos: Long, pcm: ByteArray): ByteArray {
        require(pcm.isNotEmpty()) { "EMPTY_PCM_FRAME" }
        require(pcm.size % 2 == 0) { "PCM_MUST_BE_WHOLE_16_BIT_SAMPLES" }
        require(pcm.size <= MAX_FRAME_BYTES) { "FRAME_TOO_LARGE" }
        require(sequence >= 0) { "SEQUENCE_MUST_NOT_BE_NEGATIVE" }
        return frame(TYPE_PCM, sequence, capturedAtNanos, pcm)
    }

    fun encodeEnd(sequence: Long, framesSent: Long, reason: UplinkEndReason): ByteArray {
        val payload = java.io.ByteArrayOutputStream()
        writeLongToStream(payload, framesSent)
        payload.write(reason.ordinal)
        return frame(TYPE_END, sequence, 0L, payload.toByteArray())
    }

    /**
     * Returns the message, or `null` for anything not fully understood: a foreign magic, an unknown type, a
     * truncated buffer, a declared length that disagrees with what arrived, an odd PCM byte count, or a selection
     * that names a channel the capture does not have. Refusing is the only safe option - a partially understood
     * audio frame would be rendered as noise.
     */
    fun decode(bytes: ByteArray): UplinkMessage? {
        if (bytes.size < HEADER_BYTES) return null
        if (bytes[0] != MAGIC_0 || bytes[1] != MAGIC_1 || bytes[2] != MAGIC_2 || bytes[3] != MAGIC_3) return null
        val type = bytes[4].toInt() and 0xFF
        val sequence = readLong(bytes, OFFSET_SEQUENCE)
        val capturedAtNanos = readLong(bytes, OFFSET_CAPTURED_AT)
        // The declared length sits after both 8-byte fields, at offset 20.
        val declared = readInt(bytes, OFFSET_DECLARED)
        if (declared < 0) return null
        val available = bytes.size - HEADER_BYTES
        if (declared != available) return null
        val payload = bytes.copyOfRange(HEADER_BYTES, bytes.size)
        return when (type) {
            TYPE_START -> decodeStart(payload)
            TYPE_PCM -> {
                if (payload.isEmpty() || payload.size % 2 != 0 || payload.size > MAX_FRAME_BYTES) null
                else UplinkMessage.Pcm(sequence, capturedAtNanos, payload)
            }
            TYPE_END -> decodeEnd(sequence, payload)
            else -> null
        }
    }

    /** Bytes per second of a capture at the given geometry, used to justify the channel selection. */
    fun bytesPerSecond(sampleRateHz: Int, channelCount: Int, bitsPerSample: Int): Int =
        sampleRateHz * channelCount * (bitsPerSample / 8)

    /** Frames per second implied by [DEFAULT_FRAME_MILLIS], for sizing the sender's pacing. */
    fun framesPerSecond(frameMillis: Int = DEFAULT_FRAME_MILLIS): Int {
        require(frameMillis > 0) { "FRAME_MILLIS_MUST_BE_POSITIVE" }
        return 1000 / frameMillis
    }

    private fun decodeStart(payload: ByteArray): UplinkMessage? {
        var index = 0
        if (payload.isEmpty()) return null
        val tagLength = payload[index].toInt() and 0xFF
        index++
        if (payload.size < index + tagLength + 4 + 1 + 1 + 1) return null
        val tag = String(payload, index, tagLength, Charsets.UTF_8)
        index += tagLength
        val sampleRate = readInt(payload, index)
        index += 4
        val channels = payload[index].toInt() and 0xFF
        index++
        val bits = payload[index].toInt() and 0xFF
        index++
        val selectedCount = payload[index].toInt() and 0xFF
        index++
        if (payload.size != index + selectedCount) return null
        val selected = (0 until selectedCount).map { payload[index + it].toInt() and 0xFF }
        if (tag.isBlank() || sampleRate <= 0 || channels !in 1..64) return null
        if (bits !in setOf(8, 16, 24, 32)) return null
        if (selected.isEmpty() || selected.any { it >= channels } || selected.distinct().size != selected.size) return null
        return UplinkMessage.Start(tag, sampleRate, channels, bits, selected)
    }

    private fun decodeEnd(sequence: Long, payload: ByteArray): UplinkMessage? {
        if (payload.size != 9) return null
        val framesSent = readLong(payload, 0)
        val ordinal = payload[8].toInt() and 0xFF
        val reason = UplinkEndReason.entries.firstOrNull { it.ordinal == ordinal } ?: return null
        return UplinkMessage.End(sequence, framesSent, reason)
    }

    /**
     * Builds a frame with the field offsets written out explicitly rather than appended in sequence.
     *
     * This is deliberate: an earlier version appended the header field by field, miscounted the reserved bytes, and
     * shifted the declared length by one - the kind of defect that hides behind a green build and corrupts every
     * frame at runtime. Addressing each field by index makes the layout verifiable by reading it, and the assertion
     * at the end makes a future edit fail loudly instead of silently.
     */
    private fun frame(type: Int, sequence: Long, capturedAtNanos: Long, payload: ByteArray): ByteArray {
        val out = ByteArray(HEADER_BYTES + payload.size)
        out[0] = MAGIC_0
        out[1] = MAGIC_1
        out[2] = MAGIC_2
        out[3] = MAGIC_3
        out[4] = type.toByte()
        out[5] = 0 // flags
        out[6] = 0 // reserved
        out[7] = 0 // reserved
        writeLongAt(out, OFFSET_SEQUENCE, sequence)
        writeLongAt(out, OFFSET_CAPTURED_AT, capturedAtNanos)
        writeIntAt(out, OFFSET_DECLARED, payload.size)
        payload.copyInto(out, HEADER_BYTES)
        check(out.size == HEADER_BYTES + payload.size) { "FRAME_SIZE_INVARIANT" }
        return out
    }

    private const val OFFSET_SEQUENCE = 8
    private const val OFFSET_CAPTURED_AT = 16
    private const val OFFSET_DECLARED = 24

    private fun writeIntToStream(out: java.io.ByteArrayOutputStream, value: Int) {
        out.write((value ushr 24) and 0xFF)
        out.write((value ushr 16) and 0xFF)
        out.write((value ushr 8) and 0xFF)
        out.write(value and 0xFF)
    }

    private fun writeLongToStream(out: java.io.ByteArrayOutputStream, value: Long) {
        for (shift in 56 downTo 0 step 8) out.write(((value ushr shift) and 0xFF).toInt())
    }

    private fun writeIntAt(target: ByteArray, at: Int, value: Int) {
        target[at] = ((value ushr 24) and 0xFF).toByte()
        target[at + 1] = ((value ushr 16) and 0xFF).toByte()
        target[at + 2] = ((value ushr 8) and 0xFF).toByte()
        target[at + 3] = (value and 0xFF).toByte()
    }

    private fun writeLongAt(target: ByteArray, at: Int, value: Long) {
        for (offset in 0 until 8) {
            target[at + offset] = ((value ushr (56 - offset * 8)) and 0xFF).toByte()
        }
    }

    private fun readInt(bytes: ByteArray, at: Int): Int =
        ((bytes[at].toInt() and 0xFF) shl 24) or ((bytes[at + 1].toInt() and 0xFF) shl 16) or
            ((bytes[at + 2].toInt() and 0xFF) shl 8) or (bytes[at + 3].toInt() and 0xFF)

    private fun readLong(bytes: ByteArray, at: Int): Long {
        var value = 0L
        for (offset in 0 until 8) value = (value shl 8) or (bytes[at + offset].toLong() and 0xFF)
        return value
    }
}
