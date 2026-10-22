package com.sheen.rokid.audio

/**
 * Times a sequence of sends to decide whether a channel can carry real-time audio.
 *
 * The decision is the point, not the numbers: `UplinkThroughputProbe` on the glasses side already states the
 * requirement (64,000 B/s for the two processed channels) and the verdict vocabulary, so this class supplies the
 * measurement in exactly those terms. It is separated from the transport so the arithmetic and the refusal rule can be
 * verified on the JVM - a wrong verdict would either abandon a workable channel or, worse, green-light one that
 * cannot carry audio.
 */
class UplinkThroughputMeasurement(
    private val requiredBytesPerSecond: Int,
    private val expectedFrames: Int,
    /**
     * The cadence a real sender would use. When non-zero the run is judged as a **paced** run: frames must be
     * delivered as they are due, so a run that bursts everything instantly and then waits is not sustaining anything.
     * This is what separates "the channel has capacity" from "the channel keeps up in real time".
     */
    private val frameIntervalMillis: Long = 0L,
) {
    init {
        require(requiredBytesPerSecond > 0) { "REQUIRED_RATE_MUST_BE_POSITIVE" }
        require(expectedFrames > 0) { "EXPECTED_FRAMES_MUST_BE_POSITIVE" }
        require(frameIntervalMillis >= 0) { "FRAME_INTERVAL_MUST_NOT_BE_NEGATIVE" }
    }

    private var attempted = 0L
    private var accepted = 0L
    private var payloadBytes = 0L
    private var startedAtNanos = 0L
    private var finishedAtNanos = 0L
    private var running = false
    private var totalSendNanos = 0L
    private var slowestSendNanos = 0L

    fun start(nowNanos: Long) {
        startedAtNanos = nowNanos
        finishedAtNanos = 0
        attempted = 0
        accepted = 0
        payloadBytes = 0
        totalSendNanos = 0
        slowestSendNanos = 0
        running = true
    }

    /**
     * Records one send attempt, how long it took, and whether the transport took it.
     *
     * [sendNanos] matters for a paced run: what decides whether audio can be sustained is whether a single send
     * finishes inside the frame interval. A send that occasionally takes longer than the interval cannot keep up,
     * however good the average looks.
     */
    fun record(acceptedFrame: Boolean, bytes: Int, sendNanos: Long = 0L) {
        attempted++
        totalSendNanos += sendNanos
        if (sendNanos > slowestSendNanos) slowestSendNanos = sendNanos
        if (acceptedFrame) {
            accepted++
            payloadBytes += bytes
        }
    }

    fun finish(nowNanos: Long) {
        finishedAtNanos = nowNanos
        running = false
    }

    /**
     * Wall time of the run. A separate `running` flag is used rather than testing a timestamp against zero, because
     * zero is a legitimate clock reading and such a sentinel would silently report an elapsed time of zero.
     */
    val elapsedMillis: Long
        get() = if (running) 0L else ((finishedAtNanos - startedAtNanos) / 1_000_000).coerceAtLeast(0L)

    val slowestSendMillis: Long get() = slowestSendNanos / 1_000_000

    /**
     * True when no single send exceeded the frame interval. A paced run fails this even if every frame was accepted,
     * because a send slower than the interval means the sender would fall behind and the audio would accumulate
     * latency until it was dropped.
     */
    val everySendFitsTheInterval: Boolean
        get() = frameIntervalMillis <= 0L || slowestSendNanos <= frameIntervalMillis * 1_000_000

    val achievedBytesPerSecond: Double
        get() = if (elapsedMillis <= 0L) 0.0 else payloadBytes.toDouble() * 1000.0 / elapsedMillis

    val refusalRatio: Double
        get() = if (attempted == 0L) 0.0 else (attempted - accepted).toDouble() / attempted

    /**
     * Whether the run actually attempted everything it was supposed to. A short run can post an impressive rate from
     * far too few frames, so an incomplete run is reported rather than scored.
     */
    val completedExpectedFrames: Boolean get() = attempted >= expectedFrames

    /**
     * The verdict. Order matters, and each rule exists because a weaker one would pass something that cannot carry
     * audio. Refusal outranks rate: a channel that averages adequately while rejecting frames drops audio in bursts.
     * A paced send slower than its frame interval outranks rate too, because the sender would accumulate latency until
     * frames had to be dropped. An incomplete run is never a pass.
     */
    fun verdict(): String = when {
        attempted == 0L -> "NO_FRAMES_ATTEMPTED"
        !completedExpectedFrames -> "INCOMPLETE_RUN"
        refusalRatio > 0.0 -> "TRANSPORT_REFUSED_FRAMES"
        !everySendFitsTheInterval -> "SLOWEST_SEND_EXCEEDS_FRAME_INTERVAL"
        achievedBytesPerSecond >= requiredBytesPerSecond -> "SUSTAINS_REAL_TIME"
        else -> "TOO_SLOW_FOR_REAL_TIME"
    }

    /** One line for the device report; the raw numbers are included so the verdict can be audited. */
    fun summary(): String = buildString {
        append("accepted=").append(accepted).append('/').append(attempted)
        append(" payloadBytes=").append(payloadBytes)
        append(" elapsedMillis=").append(elapsedMillis)
        append(" achievedKBps=").append((achievedBytesPerSecond / 1000).toInt())
        append(" requiredKBps=").append(requiredBytesPerSecond / 1000)
        append(" refusalPercent=").append((refusalRatio * 100).toInt())
        if (frameIntervalMillis > 0L) {
            append(" frameIntervalMillis=").append(frameIntervalMillis)
            append(" slowestSendMillis=").append(slowestSendMillis)
        }
        append(" verdict=").append(verdict())
    }

    /**
     * Samples the required rate as a count of frames, so a genuine attempt sends the right number rather than an
     * arbitrary loop count: [framesFor] answers "how many frames of this size make up one second of audio".
     */
    companion object {
        fun framesFor(payloadBytesPerFrame: Int, requiredBytesPerSecond: Int): Int {
            require(payloadBytesPerFrame > 0) { "PAYLOAD_MUST_BE_POSITIVE" }
            return Math.ceilDiv(requiredBytesPerSecond, payloadBytesPerFrame)
        }
    }
}
