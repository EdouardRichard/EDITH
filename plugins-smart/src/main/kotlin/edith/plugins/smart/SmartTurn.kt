package edith.plugins.smart

import edith.agent.AgentLoop
import edith.contracts.Generation
import edith.contracts.SessionId
import edith.plugin.CancellationHandle
import edith.plugin.DeadlineScheduler
import edith.plugin.MonotonicClock
import edith.output.ReadableHintAssembler

/**
 * Where one smart turn is in its life.
 *
 * The order is the whole contract of this plugin: a wake opens an [AwaitingTranscript] turn, only the **final**
 * transcript moves it to [Running], and a run always ends by moving to [Idle] (or being torn down). A turn is never
 * re-run: `agent-runtime`'s `AgentLoop` is single-use, so `SmartAssistantPlugin` builds a fresh loop per turn rather
 * than holding one here.
 */
internal enum class SmartTurnPhase { AwaitingTranscript, Running, Idle }

/**
 * One passive turn: the transcript it waits for, the hint being assembled, and the bookkeeping the host needs to
 * cancel the right output later.
 *
 * This holds **state, not wiring**: the model client, action executor and policy are rebuilt per turn by the plugin
 * (`AgentLoop` is single-use), so nothing here can leak a half-finished run into the next turn. [loop] is kept only so
 * `Stop` and `deactivate` can cancel a run that is already in flight.
 *
 * [hintValidUntil] bounds the turn's `sourceTurnId` lifetime: the turn's deadline plus a small grace window, so a hint
 * can still expire on its own after the deadline passes instead of sitting on the glasses forever. [hintExpiry] is the
 * concrete expiry stamped on the hint currently shown; a `Displayed` ACK is honoured only while it is still in the
 * future, which is what keeps a late ACK for an already-expired hint from being counted.
 *
 * [submitting] and [pendingAck] are the two halves of the race the diagnostic plugin already solves: an
 * `OutputStateEvent` the host delivers *inside* `submit` (before `submit` returned) must not be dropped, and must not
 * be admitted either until the submission came back `Accepted`. The first sets [pendingAck]; the emit path replays it
 * after admission.
 *
 * [listeningExpiry] is the turn's bounded listening window (D12): the scheduled task that ends a wake-opened turn
 * whose window expires with no usable utterance. It lives here, not in the plugin, so `Stop`, `deactivate` and a
 * replacement turn release it through the same teardown that cancels the in-flight loop.
 */
internal class SmartTurn private constructor(
    /**
     * The turn identity the host correlates hints and cancellations by.
     *
     * It is minted when the wake opens the turn and **replaced by the ASR turn id** as soon as the final transcript
     * arrives: P2's semantics (and the host's `cancelOutputTurn`) key on the transcript's turn id, so the hint this
     * run emits must carry that id, not the plugin's own placeholder.
     */
    var turnId: String,
    val sessionId: SessionId,
    val generation: Generation,
    deadlineNanos: Long,
    val hintId: String,
    private val hintGraceNanos: Long,
) {
    var phase: SmartTurnPhase = SmartTurnPhase.AwaitingTranscript
        private set

    /**
     * The turn's own deadline: the end of its listening window while it waits for an utterance, and the agent run's
     * deadline once one arrived. [extendDeadline] moves it forward and never back.
     */
    var deadlineNanos: Long = deadlineNanos
        private set

    var assembler: ReadableHintAssembler? = null
    var loop: AgentLoop? = null
    var listeningExpiry: CancellationHandle? = null
    var hintValidUntil: Long = 0
    var hintExpiry: Long = 0
    var readable = false
    var displayed = false
    var submitting = false
    var pendingAck = false
    var closed = false

    /** Moves the turn to [SmartTurnPhase.Running]: the final transcript has arrived and will open the loop. */
    fun running() {
        phase = SmartTurnPhase.Running
    }

    /**
     * Moves the turn's deadline - and the hint window that follows it - to [newDeadlineNanos], never backwards.
     *
     * A wake segment's blank final is evidence that the request has not been spoken yet, so it re-arms the listening
     * window from that moment. Both halves have to move together: a hint assembled after the original deadline would
     * otherwise be dropped as expired, and the plugin would go silent on exactly the path that must never be silent.
     */
    fun extendDeadline(newDeadlineNanos: Long) {
        if (newDeadlineNanos <= deadlineNanos) return
        deadlineNanos = newDeadlineNanos
        hintValidUntil = saturatingAdd(newDeadlineNanos, hintGraceNanos)
    }

    /** Releases the armed listening window, if any; safe to call more than once. */
    fun cancelListening() {
        listeningExpiry?.cancel()
        listeningExpiry = null
    }

    /** Replaces the wake's placeholder turn id with the ASR turn id the transcript carries. */
    fun adoptTranscriptTurnId(transcriptTurnId: String) {
        if (transcriptTurnId.isNotBlank()) turnId = transcriptTurnId
    }

    /** Marks the run finished without tearing the turn down, so its hint can still be acknowledged. */
    fun idle() {
        phase = SmartTurnPhase.Idle
    }

    /** Stops the assembler and any scheduled hint emit; safe to call more than once. */
    fun cancelAssembler() {
        assembler?.cancel()
        assembler = null
    }

    companion object {
        /**
         * Opens a turn in [SmartTurnPhase.AwaitingTranscript] and installs its hint assembler.
         *
         * The assembler is built here, once per turn, and its emit callback is the plugin's own — so a hint can only
         * ever be emitted while this exact turn is still the plugin's current one. The turn's deadline is `now` plus
         * the listening window the plugin chose (the configured profile's timeout, or its documented default), so a
         * turn opened before a profile exists still gets a usable window and a later, honest failure rather than an
         * unbounded one.
         */
        fun awaiting(
            turnId: String,
            sessionId: SessionId,
            generation: Generation,
            clock: MonotonicClock,
            scheduler: DeadlineScheduler,
            deadlineNanos: Long,
            hintGraceNanos: Long,
            hintId: String,
            onEmit: (String) -> Unit,
        ): SmartTurn {
            val turn = SmartTurn(
                turnId = turnId,
                sessionId = sessionId,
                generation = generation,
                deadlineNanos = deadlineNanos,
                hintId = hintId,
                hintGraceNanos = hintGraceNanos,
            )
            turn.hintValidUntil = saturatingAdd(deadlineNanos, hintGraceNanos)
            turn.assembler = ReadableHintAssembler(clock, scheduler, onEmit)
            return turn
        }

        /** [base] + [delta], saturated at [Long.MAX_VALUE] instead of wrapping. */
        fun saturatingAdd(base: Long, delta: Long): Long =
            if (delta > 0 && base > Long.MAX_VALUE - delta) Long.MAX_VALUE else base + delta
    }
}
