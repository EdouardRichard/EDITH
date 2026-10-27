package com.sheen.rokid.phone.audio

import com.sheen.rokid.contracts.HostEvent
import com.sheen.rokid.contracts.TranscriptEvent
import com.sheen.rokid.plugin.CancellationHandle
import com.sheen.rokid.plugin.DeadlineScheduler
import com.sheen.rokid.plugin.MonotonicClock

/** 15 s cap for one phone-mic dictation (0.4 spec §5.3, D204). */
private const val DEFAULT_TIMEOUT_MILLIS = DictationController.TIMEOUT_MILLIS

/**
 * The two operations the dictation controller needs from the shared speech pipeline.
 *
 * This is deliberately a seam, not the pipeline itself: the controller is JVM-testable and must not own
 * `AudioRecord`, the PCM ingress, or CXR frame routing. The production adapter (Task 12b-ii) maps [redirect] to
 * `SpeechPipeline.redirectEmit` and [finishCurrentTurn] to the pipeline's forced end-of-turn; nothing else about the
 * wake route changes.
 */
interface DictationPipeline {
    /**
     * Points every subsequent pipeline [HostEvent] at [sink], or restores the constructor/wake sink when null.
     *
     * The pipeline reads the destination at emission time, so [redirect] itself must be a cheap, thread-safe setter
     * and must never emit synchronously.
     */
    fun redirect(sink: ((HostEvent) -> Unit)?)

    /**
     * Ends the active pipeline turn with a final transcript and returns whether a turn was active.
     *
     * Returns `false` (and emits nothing) when there is no active turn. Used by [DictationController.stop] and by the
     * timeout/cancel cleanup, which discards the would-be final instead of letting it reach the wake route.
     */
    fun finishCurrentTurn(): Boolean
}

/**
 * What the dictation button/field can show (0.4 spec §5.3, D204).
 *
 * [Idle] is both the starting state and the successful end of a dictation; [NoSpeech] is a terminal *non-result*
 * (timeout, stop with no turn, blank final); [Error] carries a stable code for a pipeline failure. The controller
 * never emits a state after the terminal state of one dictation - a new [DictationController.start] begins the next
 * sequence.
 */
sealed interface DictationState {
    data object Idle : DictationState
    data object Listening : DictationState
    data object NoSpeech : DictationState

    data class Error(val code: String) : DictationState {
        init { require(code.isNotBlank()) { "a dictation error needs a stable code" } }
    }
}

/**
 * 0.4 Task 12b-i (spec §5.3, D204): the phone-microphone one-shot dictation lease, at the UI boundary.
 *
 * The host owns the microphone (`AudioRecord`), the PCM ingress, the single capture lease and all CXR frame routing.
 * The controller owns the **pipeline's output during one dictation**:
 *
 * - [start] refuses a second start, installs a redirect on the shared [DictationPipeline], and schedules the
 *   [DEFAULT_TIMEOUT_MILLIS] deadline;
 * - a final, non-blank [TranscriptEvent] is consumed here, never forwarded: the redirect is restored, the timer is
 *   stopped, [onState] gets [DictationState.Idle] and [onFinal] gets the text exactly once. The text goes to the input
 *   field; nothing sends it, and it never reaches `HostRuntime` or the plugin;
 * - every **non-transcript** pipeline event (speaker/session/fault) is forwarded to [forward] while listening. The
 *   host decides what to do with those. Partial transcripts are **dropped**, not forwarded: they are dictation
 *   content too, and `HostRuntime` would pass them to the plugin if the host routed them on;
 * - [stop] forces the active turn to its final (`finishCurrentTurn`), [cancel] aborts without a result, and the 15 s
 *   deadline ends as [DictationState.NoSpeech]. Both timeout and cancel seal the pipeline turn while the controller's
 *   redirect is still installed, so the discarded final cannot surface later on the restored wake route;
 * - [close] is a permanent teardown: it restores the route and makes [start] answer
 *   [StartResult.Unavailable].
 *
 * The controller logs nothing (not even the text length): it handles the wearer's content, so there is no content
 * path other than [onFinal], and callers own any `P4_DICTATION` count-only telemetry. Callbacks run outside the
 * controller lock, on the thread that called [start]/[stop]/[cancel], on the pipeline's emission thread, or on the
 * scheduler's thread.
 */
class DictationController(
    private val pipeline: DictationPipeline,
    private val scheduler: DeadlineScheduler,
    private val clock: MonotonicClock,
    private val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
    private val onFinal: (String) -> Unit,
    private val onState: (DictationState) -> Unit,
    private val forward: (HostEvent) -> Unit,
) {

    /** The answer to [start]; [AlreadyListening] doubles as "a stop/cancel cleanup is still settling". */
    enum class StartResult { Accepted, AlreadyListening, Unavailable }

    private val lock = Any()

    /** A dictation is listening or stopping; once false, late pipeline events are ignored. */
    private var active = false

    /** [stop] is waiting for `finishCurrentTurn`; a second stop must not finish the turn again. */
    private var stopping = false

    /** [cancel]/timeout finalise the turn under the redirect; a new start must wait for that to finish. */
    private var settling = false

    /** Permanent teardown; [start] answers [StartResult.Unavailable]. */
    private var closed = false

    /** Which dictation owns the installed redirect; a late event from an older one is ignored. */
    private var generation = 0L
    private var nextGeneration = 0L
    private var timeout: CancellationHandle? = null

    init {
        require(timeoutMillis > 0) { "a dictation needs a positive timeout" }
    }

    /**
     * Starts one dictation, or reports why not.
     *
     * [StartResult.Accepted] means the redirect is installed, `Listening` has been reported and the deadline is
     * armed. [StartResult.AlreadyListening] covers both a second press while listening and a press while a previous
     * stop/cancel is still finishing the pipeline turn. [StartResult.Unavailable] means the controller was
     * [close]d, or the pipeline/scheduler refused to install the redirect; no state is reported in that case.
     */
    fun start(): StartResult {
        var startedGeneration = 0L
        synchronized(lock) {
            if (closed) return StartResult.Unavailable
            if (active || settling) return StartResult.AlreadyListening

            startedGeneration = ++nextGeneration
            generation = startedGeneration
            active = true
            stopping = false
            timeout?.cancel()
            timeout = null

            try {
                // The closure captures this dictation's generation, so a sink retained by an older in-flight event
                // cannot deliver into the next dictation.
                pipeline.redirect { event -> onPipelineEvent(startedGeneration, event) }
            } catch (_: Exception) {
                active = false
                runCatching { pipeline.redirect(null) }
                return StartResult.Unavailable
            }

            val handle = try {
                scheduler.at(clock.nowNanos() + timeoutMillis * 1_000_000L) {
                    onDeadline(startedGeneration)
                }
            } catch (_: Exception) {
                active = false
                runCatching { pipeline.redirect(null) }
                return StartResult.Unavailable
            }
            if (active && generation == startedGeneration) timeout = handle else handle.cancel()
        }
        onState(DictationState.Listening)
        return StartResult.Accepted
    }

    /**
     * Ends the dictation through the pipeline, exactly once.
     *
     * `finishCurrentTurn` emits its final synchronously through the still-installed redirect, so the normal path is
     * identical to a VAD endpoint: [onFinal] once + [DictationState.Idle] + restored route. If no final is produced
     * (no active turn, or the ASR had already sealed it) the dictation still ends - with
     * [DictationState.NoSpeech] and no [onFinal]. Calling stop again, or after a final, is a no-op.
     */
    fun stop() {
        var stopGeneration = 0L
        synchronized(lock) {
            if (!active || stopping) return
            stopping = true
            stopGeneration = generation
        }

        val shouldFinish = synchronized(lock) {
            active && stopping && generation == stopGeneration
        }
        if (!shouldFinish) return

        try {
            pipeline.finishCurrentTurn()
        } catch (_: Exception) {
            reportStopFailure(stopGeneration)
            return
        }

        // The final path already cleared `active` in onPipelineEvent. Only a turn that produced no final reaches here.
        val noSpeech = synchronized(lock) {
            if (active && stopping && generation == stopGeneration) {
                active = false
                stopping = false
                timeout?.cancel()
                timeout = null
                pipeline.redirect(null)
                true
            } else {
                false
            }
        }
        if (noSpeech) onState(DictationState.NoSpeech)
    }

    /**
     * Aborts the dictation: no [onFinal], route restored, [DictationState.Idle].
     *
     * An already-active pipeline turn is finalised while the redirect is still installed, so the final it would
     * otherwise emit is dropped here instead of arriving on the wake route after cancel. [cancel] before a start, or
     * twice, is a no-op.
     */
    fun cancel() {
        var canceledGeneration = 0L
        synchronized(lock) {
            if (!active || settling) return
            canceledGeneration = generation
            active = false
            stopping = false
            settling = true
            timeout?.cancel()
            timeout = null
        }

        discardCurrentTurn()

        val notify = synchronized(lock) {
            settling = false
            pipeline.redirect(null)
            generation == canceledGeneration && !closed
        }
        if (notify) onState(DictationState.Idle)
    }

    /**
     * Permanent teardown for the owning host.
     *
     * Restores the wake route, cancels the deadline and stops reporting. It does **not** finish the pipeline turn or
     * emit a state - the host is tearing the session down anyway. Idempotent; after it [start] answers
     * [StartResult.Unavailable].
     */
    fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            val wasActive = active
            active = false
            stopping = false
            timeout?.cancel()
            timeout = null
            if (wasActive) pipeline.redirect(null)
        }
    }

    /** The pipeline's sink: take exactly one final transcript; forward everything else; drop everything after end. */
    private fun onPipelineEvent(generation: Long, event: HostEvent) {
        var finalText: String? = null
        var noSpeech = false
        var forwardEvent: HostEvent? = null
        synchronized(lock) {
            if (!active || this.generation != generation) return
            if (event is TranscriptEvent && event.revision.final) {
                active = false
                stopping = false
                timeout?.cancel()
                timeout = null
                // Under the lock, so a concurrent start cannot install its redirect between this terminal state and
                // the restore. The final itself is never forwarded.
                pipeline.redirect(null)
                if (event.revision.text.isBlank()) noSpeech = true else finalText = event.revision.text
            } else if (event is TranscriptEvent) {
                // A partial hypothesis is dictation content. Forwarding it would let a host route it to the plugin
                // through HostRuntime, which delivers every TranscriptEvent regardless of `final`.
            } else {
                forwardEvent = event
            }
        }

        val text = finalText
        when {
            text != null -> {
                onState(DictationState.Idle)
                onFinal(text)
            }
            noSpeech -> onState(DictationState.NoSpeech)
            forwardEvent != null -> forward(forwardEvent)
        }
    }

    /** The 15 s deadline: no final, so seal the turn under the redirect and report [DictationState.NoSpeech]. */
    private fun onDeadline(generation: Long) {
        synchronized(lock) {
            if (!active || stopping || this.generation != generation) return
            active = false
            settling = true
            timeout = null
        }

        discardCurrentTurn()

        val notify = synchronized(lock) {
            settling = false
            pipeline.redirect(null)
            !closed
        }
        if (notify) onState(DictationState.NoSpeech)
    }

    /**
     * Ends the active pipeline turn while the controller's redirect is still installed.
     *
     * The final this may produce is swallowed by [onPipelineEvent] (the dictation is already inactive), which is the
     * point: a timeout/cancel must not leave a dictation turn that could emit its transcript later on the wake route.
     * The pipeline already says "no turn" with `false`, and a failure here has no transcript to lose.
     */
    private fun discardCurrentTurn() {
        runCatching { pipeline.finishCurrentTurn() }
    }

    /** A throwing `finishCurrentTurn` ends the dictation as [DictationState.Error] and still restores the route. */
    private fun reportStopFailure(stopGeneration: Long) {
        val notify = synchronized(lock) {
            if (!active || !stopping || generation != stopGeneration) return
            active = false
            stopping = false
            timeout?.cancel()
            timeout = null
            pipeline.redirect(null)
            !closed
        }
        if (notify) onState(DictationState.Error(ERROR_STOP_FAILED))
    }

    companion object {
        /** Stable code for a pipeline that threw while [stop] was forcing the final. */
        const val ERROR_STOP_FAILED = "DICTATION_STOP_FAILED"

        /**
         * The 0.4 spec §5.3 cap: one dictation may listen for 15 s, then it ends as [DictationState.NoSpeech].
         *
         * Public so the host's telemetry can attribute a `NoSpeech` to the deadline without duplicating the number.
         */
        const val TIMEOUT_MILLIS = 15_000L
    }
}
