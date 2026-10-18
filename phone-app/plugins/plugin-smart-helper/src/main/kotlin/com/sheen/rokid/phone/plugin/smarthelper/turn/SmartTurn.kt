package com.sheen.rokid.phone.plugin.smarthelper.turn
import com.sheen.rokid.phone.plugin.smarthelper.SmartAssistantPlugin

import com.sheen.rokid.agent.AgentLoop
import com.sheen.rokid.contracts.Generation
import com.sheen.rokid.contracts.OutputStatus
import com.sheen.rokid.contracts.SessionId
import com.sheen.rokid.plugin.CancellationHandle
import com.sheen.rokid.plugin.DeadlineScheduler
import com.sheen.rokid.plugin.MonotonicClock
import com.sheen.rokid.output.ReadableHintAssembler

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
 * [listeningExpiry] is gone in 0.24 S3: the official 10 s listening window belongs to `ConversationCycle` (it starts
 * at `PANEL_SHOWN` and belongs to the conversation, not to one turn), so this class owns no timer at all.
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
     * The turn's **machine** budget: when the agent run must stop.
     *
     * It is not a listening window. Since 0.24 S3 the official 10 s belongs to `ConversationCycle` and starts at
     * `PANEL_SHOWN`; this number bounds the model/tool work and is re-anchored at the usable final ([extendDeadline]),
     * so waiting for the wearer cannot eat the run's budget. [extendDeadline] moves it forward and never back.
     */
    var deadlineNanos: Long = deadlineNanos
        private set

    var assembler: ReadableHintAssembler? = null
    var loop: AgentLoop? = null
    var hintValidUntil: Long = 0
    var hintExpiry: Long = 0
    var readable = false
    var displayed = false
    var submitting = false
    var pendingAck = false
    var closed = false

    /**
     * 0.24 S3 Task 14 (spec §5.5, Task 12 carry I1): the per-emit identity of the answer this turn last emitted.
     *
     * The production answer shape is `notify_user` (spec §6.1), which is emitted **while the run is still `Running`**.
     * A `phase == Idle` test therefore cannot tell the final answer from a mid-run notification - or see it at all
     * for the suppressed/rejected/silent endings. The turn records the emit it last put in front of the wearer here,
     * and [answerFinal] marks the moment the run ended and that emit became the answer the conversation continues.
     *
     * **Fix round C1:** this is the emit id (`Assistance.emitId`), not [hintId]. A second emit in the same turn keeps
     * the same entry hint id (spec §6: `say-${hintId}` is one row) but gets its own emit id, so the arbiter's
     * replacement `Rejected` for the superseded emit can be told apart from a terminal of the current answer.
     */
    var answerEmitId: String? = null

    /** Whether [answerEmitId] asked the host to speak (and the host could). */
    var answerEmitSpeech = false

    /** Whether the host accepted that emit for display; a rejected submit has nothing to continue. */
    var answerEmitAccepted = false

    /** The run ended, so [answerEmitId] is the final answer (or null when the turn never emitted one). */
    var answerFinal = false

    /**
     * The last terminal display status (`Expired`/`Rejected`) reported for the answer hint while it was not displayed.
     *
     * Cleared by a `Displayed` acknowledgement. Spec §5.5 line 324: a rejected/expired answer closes the conversation
     * and records `P3_CONV no_display` instead of opening a next round.
     */
    var answerTerminal: OutputStatus? = null

    /**
     * 0.24 S4 Task 16 (I1): a non-answer emit (the explicit-memory acknowledgement) was submitted under this turn's
     * hint id after the answer.
     *
     * `OutputArbiter` replaces an item with the **same hint id** and reports the removed answer as `Rejected` naming
     * the answer's emit id. That status means "superseded by a later statement", not "the wearer never saw the
     * answer", so [SmartAssistantPlugin.noteAnswerTerminal] must not record it as the answer's terminal. A new answer
     * emit clears it: from then on a terminal names a different emit (or a real terminal of that new answer).
     */
    var answerSupersededByNotice = false

    /** 0.24 S3: the answer-done decision was already reported, so a duplicate `Displayed` cannot continue twice. */
    var answerNotified = false

    /**
     * 0.24 S4 Task 16: the answer text the gateway has streamed for **this** turn.
     *
     * Display-only. The final answer is still assembled by [assembler] and submitted as the one hint emit; this buffer
     * exists so every `TextDelta` can be published as the full accumulated revision the glasses replace in place. It is
     * guarded by the plugin's `gate` (the callback runs on the transport thread), and it never feeds the model or the
     * final text.
     */
    private val streamedAnswer = StringBuilder()
    private var streamedAnswerRevision = 0L

    /**
     * Appends one accepted model text delta and returns this revision's number and the accumulated text.
     *
     * Must be called under the plugin's `gate`; an empty delta is not a revision. The turn id and hint id are the
     * caller's to read from the same locked snapshot, so a revision can never be published under a newer turn's ids.
     */
    fun appendAnswerDelta(delta: String): Pair<Long, String>? {
        if (delta.isEmpty()) return null
        streamedAnswer.append(delta)
        streamedAnswerRevision += 1
        return streamedAnswerRevision to streamedAnswer.toString()
    }

    /**
     * 0.24 S3 Task 14 fix round (I2): the liveness bound for a closing fallback that the assembler may never emit.
     *
     * `ReadableHintAssembler` deliberately drops a sentence shorter than two code points after stripping punctuation,
     * so a one-character terminal answer (`好。`) emits nothing. Without this deadline nothing would arm a timer and the
     * panel would stay at the pre-answer status forever - the same "no timer" state the speech bound removes.
     */
    var fallbackWait: CancellationHandle? = null

    /** Moves the turn to [SmartTurnPhase.Running]: the final transcript has arrived and will open the loop. */
    fun running() {
        phase = SmartTurnPhase.Running
    }

    /**
     * Moves the turn's deadline - and the hint window that follows it - to [newDeadlineNanos], never backwards.
     *
     * 0.24 S3: this is the **machine budget**, not a listening window. `acceptTranscript` re-anchors it at the usable
     * final (the official conversation may stay open for a long time while the wearer is still speaking), and the
     * run-end path extends it by the human wait a confirmation consumed. Both halves have to move together: a hint
     * assembled after the original deadline would otherwise be dropped as expired, and the plugin would go silent on
     * exactly the path that must never be silent.
     */
    fun extendDeadline(newDeadlineNanos: Long) {
        if (newDeadlineNanos <= deadlineNanos) return
        deadlineNanos = newDeadlineNanos
        hintValidUntil = saturatingAdd(newDeadlineNanos, hintGraceNanos)
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

    /** Releases the fallback liveness bound, if one is armed; safe to call more than once. */
    fun cancelFallbackWait() {
        fallbackWait?.cancel()
        fallbackWait = null
    }

    companion object {
        /**
         * Opens a turn in [SmartTurnPhase.AwaitingTranscript] and installs its hint assembler.
         *
         * The assembler is built here, once per turn, and its emit callback is the plugin's own — so a hint can only
         * ever be emitted while this exact turn is still the plugin's current one. [deadlineNanos] is the **machine
         * budget** the caller chose (the configured profile's timeout, or its documented default), not a listening
         * window: since 0.24 S3 the official 10 s lives in `ConversationCycle` and starts at `PANEL_SHOWN`, and the
         * budget is re-anchored at the usable final. A turn opened before a profile exists still gets a usable budget
         * and a later, honest failure rather than an unbounded one.
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
