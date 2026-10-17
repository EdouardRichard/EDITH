package com.sheen.rokid.audio

import com.sheen.rokid.contracts.AudioFormat
import com.sheen.rokid.contracts.InputEpoch
import com.sheen.rokid.contracts.PcmEncoding
import com.sheen.rokid.contracts.VerifiedPcm
import com.sheen.rokid.plugin.MonotonicClock

/** What the receiver did with a datagram. Every outcome is explicit so a caller can count what was lost. */
enum class UplinkFeed {
    /** Audio accepted and offered to the ingress. */
    Accepted,

    /** Audio accepted, but at least one earlier frame never arrived. */
    AcceptedWithGap,

    /** Refused: no `START` has been seen, or the stream was already stopped. */
    RefusedBeforeStart,

    /** Refused: a second `START` on a live stream would reshape the audio underneath the pipeline. */
    RefusedAfterStart,

    /** Refused: the datagram did not decode as a frame of this protocol. */
    Malformed,

    /** Refused: the same sequence already arrived. */
    Duplicate,

    /** Refused: the ingress buffer is full, so the phone is not keeping up with the glasses. */
    RefusedByOverflow,
}

/** Counters a caller can report. Losses are counted, never inferred from silence. */
data class UplinkStats(
    val framesAccepted: Long = 0,
    val framesLost: Long = 0,
    val duplicates: Long = 0,
    val malformed: Long = 0,
    val gaps: Long = 0,
    val overflowed: Boolean = false,
)

/** The format the sender declared, in the terms the ingress needs. */
data class DeclaredUplinkFormat(
    val sessionTag: String,
    val sampleRateHz: Int,
    val channelCount: Int,
    val bitsPerSample: Int,
    val selectedChannels: List<Int>,
)

/**
 * The phone-side half of the glasses audio uplink.
 *
 * It decodes [UplinkMessage] frames arriving on the CXR custom-command channel and offers their PCM to
 * [PcmIngress], which owns buffering, overflow and gap bookkeeping. This class owns only what the ingress cannot
 * know: whether a format has been declared, which sequences have been seen, and what to refuse.
 *
 * The one rule that matters: **no audio is offered before the sender declares its format.** Undeclared bytes are
 * the most expensive mistake available here, because the ASR pipeline consumes them happily and returns confident
 * nonsense rather than an error.
 */
class AudioUplinkReceiver(private val ingress: PcmIngress, private val clock: MonotonicClock) {
    private var epoch: InputEpoch? = null
    private var lastSequence: Long? = null
    private var counters = UplinkStats()

    /** Non-null once the sender has declared its format, with the channel count reduced to the selection. */
    var declaredFormat: DeclaredUplinkFormat? = null
        private set

    /** Non-null once an `END` frame arrived, stating why the glasses stopped sending. */
    var endedWith: UplinkEndReason? = null
        private set

    /** Counters a caller can report. Losses are counted here, never inferred from a silence. */
    val stats: UplinkStats get() = counters

    /** Opens the ingress for [epoch]. No audio is accepted until the sender declares its format. */
    fun start(epoch: InputEpoch) {
        this.epoch = epoch
        lastSequence = null
        endedWith = null
        declaredFormat = null
        // The ingress is left closed until START arrives: `offer` refuses with UNKNOWN_FORMAT while it has no
        // format, which is exactly the "no audio before a declared format" rule this receiver exists to enforce.
        ingress.open(epoch, null)
    }

    /**
     * Feeds one datagram. Refusals are returned rather than thrown: a hostile or buggy peer must not be able to
     * crash the audio path, and the caller needs the outcome to report loss honestly.
     */
    fun feed(datagram: ByteArray): UplinkFeed {
        val currentEpoch = epoch ?: return UplinkFeed.RefusedBeforeStart
        val message = AudioUplinkCodec.decode(datagram)
        if (message == null) {
            counters = counters.copy(malformed = counters.malformed + 1)
            return UplinkFeed.Malformed
        }
        return when (message) {
            is UplinkMessage.Start -> onStart(currentEpoch, message)
            is UplinkMessage.Pcm -> onPcm(currentEpoch, message)
            is UplinkMessage.End -> {
                endedWith = message.reason
                UplinkFeed.Accepted
            }
        }
    }

    /** Stops the stream and closes the ingress, so no later frame can be mistaken for live audio. */
    fun stop() {
        epoch = null
        declaredFormat = null
        ingress.close()
    }

    private fun onStart(currentEpoch: InputEpoch, message: UplinkMessage.Start): UplinkFeed {
        if (declaredFormat != null) return UplinkFeed.RefusedAfterStart
        if (message.selectedChannels.isEmpty() || message.selectedChannels.any { it >= message.channelCount }) {
            counters = counters.copy(malformed = counters.malformed + 1)
            return UplinkFeed.Malformed
        }
        declaredFormat = DeclaredUplinkFormat(
            sessionTag = message.sessionTag,
            sampleRateHz = message.sampleRateHz,
            channelCount = message.channelCount,
            bitsPerSample = message.bitsPerSample,
            selectedChannels = message.selectedChannels,
        )
        // Opening the ingress here is what lets audio flow at all, and the channel count is the *selection*: the
        // pipeline receives the channels the sender chose, not the capture's full width.
        //
        // The evidence id says "declared by the peer", not "verified", on purpose. A format asserted over the wire
        // is not the same class of fact as one measured from the delivery path, and T17 must not be able to mistake
        // one for the other.
        ingress.open(
            currentEpoch,
            VerifiedPcm(
                format = AudioFormat.Pcm(
                    sampleRateHz = message.sampleRateHz,
                    channels = message.selectedChannels.size,
                    bitsPerSample = message.bitsPerSample,
                ),
                encoding = PcmEncoding.SIGNED_LE,
                evidenceId = "uplink-declared:${message.sessionTag}",
            ),
        )
        return UplinkFeed.Accepted
    }

    private fun onPcm(currentEpoch: InputEpoch, message: UplinkMessage.Pcm): UplinkFeed {
        val declared = declaredFormat ?: run {
            // Audio before a declared format is refused, and counted as malformed because the sender broke the
            // protocol rather than merely arriving late.
            counters = counters.copy(malformed = counters.malformed + 1)
            return UplinkFeed.RefusedBeforeStart
        }
        val previous = lastSequence
        if (previous != null && message.sequence <= previous) {
            return if (message.sequence == previous) {
                counters = counters.copy(duplicates = counters.duplicates + 1)
                UplinkFeed.Duplicate
            } else {
                // An older sequence than one already delivered is out-of-order audio; replaying it would rewind the
                // transcript, so it is refused and counted as a duplicate-class defect.
                counters = counters.copy(duplicates = counters.duplicates + 1)
                UplinkFeed.Duplicate
            }
        }
        val gap = previous != null && message.sequence > previous + 1
        lastSequence = message.sequence
        // A sustained overflow makes the ingress accumulate one pending gap per reset, and once that backlog reaches
        // its bound the ingress throws rather than silently discarding an affected continuity identity. That is the
        // right policy inside the ingress, but it must not escape as an exception through the transport callback:
        // the caller gets an explicit overflow outcome and the counters stay truthful.
        val offer = runCatching { ingress.offer(currentEpoch, message.pcm) }.getOrElse {
            counters = counters.copy(overflowed = true)
            return UplinkFeed.RefusedByOverflow
        }
        return when (offer) {
            PcmOffer.ACCEPTED -> {
                counters = if (gap) {
                    counters.copy(
                        framesAccepted = counters.framesAccepted + 1,
                        gaps = counters.gaps + 1,
                        framesLost = counters.framesLost + (message.sequence - (previous ?: 0L) - 1),
                    )
                } else {
                    counters.copy(framesAccepted = counters.framesAccepted + 1)
                }
                if (gap) UplinkFeed.AcceptedWithGap else UplinkFeed.Accepted
            }
            PcmOffer.OVERFLOW -> {
                counters = counters.copy(overflowed = true)
                UplinkFeed.RefusedByOverflow
            }
            PcmOffer.UNKNOWN_FORMAT, PcmOffer.STALE_EPOCH -> {
                counters = counters.copy(malformed = counters.malformed + 1)
                UplinkFeed.RefusedBeforeStart
            }
        }
    }
}
