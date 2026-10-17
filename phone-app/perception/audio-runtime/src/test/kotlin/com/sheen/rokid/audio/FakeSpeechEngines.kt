package com.sheen.rokid.audio

import com.sheen.rokid.contracts.*
import com.sheen.rokid.plugin.*
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

internal class ScriptedVad : VadEngine {
    var decision = VadDecision(true, false)

    /**
     * Optional per-frame override of [decision], for scripts whose speech/silence pattern depends on the frame
     * (the D11 regression needs a gate that opens part-way through the clip). Null means "always [decision]".
     */
    var decisionFor: ((PcmFrame) -> VadDecision)? = null
    var failure: RuntimeException? = null
    var resetFailure: RuntimeException? = null
    var resets = 0
    var closes = 0
    override fun accept(frame: PcmFrame): VadDecision {
        failure?.let { throw it }
        return decisionFor?.invoke(frame) ?: decision
    }
    override fun reset() { resets++; resetFailure?.let { throw it } }
    override fun close() { closes++ }
}

internal class RecordingAsr : StreamingAsrEngine {
    val accepted = mutableListOf<SampleRange>()
    var hypotheses = listOf(AsrHypothesis("synthetic partial", false))
    var tail = AsrHypothesis("synthetic final", true)
    var failure: RuntimeException? = null
    var beforeAccept: ((PcmFrame) -> Unit)? = null
    var finishes = 0
    var resets = 0
    var closes = 0
    override fun accept(frame: PcmFrame): List<AsrHypothesis> {
        failure?.let { throw it }
        beforeAccept?.invoke(frame)
        accepted += frame.range
        return hypotheses
    }
    override fun finish(): AsrHypothesis { finishes++; return tail }
    override fun reset() { resets++ }
    override fun close() { closes++ }
}

internal class PendingSpeaker : SpeakerEngine {
    class Request(val window: SpeakerWindow, val listener: (SpeakerAssessment) -> Unit) {
        @Volatile var cancelled = false
        @Volatile var discardedByReset = false
        val cancellation = CountDownLatch(1)
        fun release(label: SpeakerLabel = SpeakerLabel.UNKNOWN) {
            if (discardedByReset) return
            val envelope = window.segment.envelope
            listener(SpeakerAssessment(envelope, label, null, envelope.startNanos, envelope.endNanos))
        }
    }
    val requests = CopyOnWriteArrayList<Request>()
    val started = CountDownLatch(1)
    private val arrivals = LinkedBlockingQueue<Request>()
    var block: CountDownLatch? = null
    var failure: RuntimeException? = null
    var onCancel: ((Request) -> Unit)? = null
    var onAssess: ((Request) -> Unit)? = null
    var onReset: (() -> Unit)? = null
    var resetFailure: RuntimeException? = null
    var discardOnReset = false
    @Volatile var assessmentThread: Thread? = null
    @Volatile var resets = 0
    @Volatile var closes = 0
    override fun assess(window: SpeakerWindow, listener: (SpeakerAssessment) -> Unit): CancellationHandle {
        failure?.let { throw it }
        val request = Request(window, listener)
        requests += request
        assessmentThread = Thread.currentThread()
        arrivals.offer(request)
        started.countDown()
        onAssess?.invoke(request)
        block?.let { barrier ->
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            var released = false
            while (!released) {
                try { released = barrier.await(maxOf(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS) }
                catch (_: InterruptedException) { continue }
                check(released) { "speaker release did not arrive" }
            }
        }
        return object : CancellationHandle {
            override fun cancel() {
                request.cancelled = true
                request.cancellation.countDown()
                onCancel?.invoke(request)
            }
        }
    }
    fun awaitStarted() { check(started.await(2, TimeUnit.SECONDS)) { "speaker request did not start" } }
    fun awaitRequests(count: Int) { repeat(count) { checkNotNull(arrivals.poll(2, TimeUnit.SECONDS)) } }
    override fun reset() {
        resets++
        onReset?.invoke()
        if (discardOnReset) requests.forEach { it.discardedByReset = true }
        resetFailure?.let { throw it }
    }
    override fun close() { closes++ }
}
