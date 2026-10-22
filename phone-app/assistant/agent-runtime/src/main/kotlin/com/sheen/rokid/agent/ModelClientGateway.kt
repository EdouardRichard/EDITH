package com.sheen.rokid.agent

import com.sheen.rokid.contracts.ContractError
import com.sheen.rokid.contracts.EventEnvelope
import com.sheen.rokid.plugin.CancellationHandle
import com.sheen.rokid.plugin.DeadlineScheduler
import com.sheen.rokid.plugin.ImageRef
import com.sheen.rokid.plugin.InputPart
import com.sheen.rokid.plugin.MessageRole
import com.sheen.rokid.plugin.ModelClient
import com.sheen.rokid.plugin.ModelEnd
import com.sheen.rokid.plugin.ModelHistoryItem
import com.sheen.rokid.plugin.ModelProfile
import com.sheen.rokid.plugin.ModelRequest
import com.sheen.rokid.plugin.ModelStreamEvent
import com.sheen.rokid.plugin.ModelUsage
import com.sheen.rokid.plugin.MonotonicClock
import com.sheen.rokid.plugin.OutputModality
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

private const val NO_USER_TURN = "NO_USER_TURN"
private const val NO_TERMINAL = "NO_TERMINAL"
private const val DEADLINE = "DEADLINE"
private const val CANCELLED = "CANCELLED"
private const val TOOL_CALL_DELIVERY_INCOMPLETE = "TOOL_CALL_DELIVERY_INCOMPLETE"

/**
 * The interval one wait step sleeps before the adapter re-polls `stop` and the clock. The deadline is *also* armed on
 * the injected [DeadlineScheduler], so a real scheduler wakes the wait exactly at the deadline; this step only bounds
 * how long an observed `stop` can go unnoticed when the scheduler is not the one delivering time.
 */
private const val WAIT_STEP_MILLIS = 10L

/** R70: at most two deterministic recovery re-sends — compress the historical tool rounds, then hard-truncate. */
private const val MAX_OVERFLOW_RECOVERIES = 2

/** Lenient by construction: a `TOOL_ROUND` body is a JSON document assembled by a caller, and a bad one falls back. */
private val toolRoundJson = Json { isLenient = true }

/**
 * The one [ModelGateway] over `plugin-api`'s streaming [ModelClient]: it turns the loop's [GatewayTurn] into a
 * stateless [ModelRequest], accumulates the stream into a [GatewayReply], and owns the turn's wall-clock bound.
 *
 * **The request input is the in-flight user turn, not history.** `ModelRequest.input` is rendered last on the wire, so
 * a transcript that ends on the pending `Text(USER)` lifts that turn into `input`, prefixed by the request-scoped
 * dynamic tail (perception, memory evidence, a skill body, runtime state, a retry instruction). When the transcript has
 * already moved past that turn (a tool round appended after it), the whole transcript stays `ModelRequest.history` and
 * the request carries an empty `input`: a history-only request, which `plugin-api` admits and `ContextBudgeter`
 * validates. That keeps the tool output last on the wire instead of re-asserting the user message after it.
 *
 * **A turn has one verdict.** Only a [ModelStreamEvent.Terminal] ends the turn; a tool-call delta whose terminal never
 * arrived is not a call, and an empty turn left by one is `INCOMPLETE`/`TOOL_CALL_DELIVERY_INCOMPLETE` rather than a
 * silent success. Because the loop is single-threaded and the transport is not, this adapter blocks until the terminal,
 * the caller's `stop()`, or [GatewayTurn.deadlineNanos] — and never past the deadline. Transport failures are not
 * caught: they must escape to the loop rather than be disguised as a finished turn.
 *
 * Nothing here prints, logs or `toString()`s a request body or a profile secret; [ModelRequest]'s own `toString` is
 * already redacted and is never defeated.
 */
class ModelClientGateway(
    private val client: ModelClient,
    private val profileLookup: (String) -> ModelProfile?,
    private val clock: MonotonicClock,
    private val scheduler: DeadlineScheduler,
    private val envelopeFor: (requestId: String, startNanos: Long, endNanos: Long) -> EventEnvelope,
    private val stop: () -> Boolean = { false },
    /**
     * P5 (spec §A2): optional audio parts appended to the request input after the transcript. Null reproduces the
     * pre-P5 text-only behaviour exactly. A lambda rather than a value so the segment is resolved at request
     * time, not at gateway construction time.
     */
    private val audioParts: (() -> List<InputPart>)? = null,
    /**
     * 0.3 Phase 2 Task 11 (spec §5.3): the count-only telemetry sink this gateway reports one `P3_CACHE` line to per
     * model request. A lambda keeps `agent-runtime` free of Android/logging dependencies; the production plugin passes
     * its `P3Log::line`, tests collect into a list. Defaulted so every pre-Task-11 construction is unchanged, and
     * failures are the sink's problem: a throwing logger must never turn a delivered reply into a failed turn.
     */
    private val metricLine: (String) -> Unit = {},
) : ModelGateway {

    /**
     * Counts from the most recent frame-path trim — how many whole turns and which origins were dropped, and whether
     * compression is still owed. **Never any text**, by type: it is [TrimCounts], which carries no frame and no
     * segment, so no probe's `toString()` can print a surviving body. It exists for tests and for the two count-only
     * consumers the production plugin has: its `P3_CONTEXT` trim line and the ③ gate it hands to `compressStore`
     * (`plugins-smart`'s `recordTurnAndCompress`). Null until the first frame-path turn, and never updated by the
     * legacy `split(history)` path.
     */
    var lastTrim: TrimCounts? = null
        private set

    /**
     * 0.25 Task 13 (R70; D100): how many deterministic overflow recoveries this gateway has performed.
     *
     * Counts only — a recovery sends the **same** turn's request again with a cheaper shape, never a summary request.
     * It accumulates across the model turns of one run (a gateway instance serves one run), so Task 14's `P3_METRIC`
     * can read it as the run's `overflows=`. Zero for a run that never overflowed.
     */
    var overflowRecoveries: Int = 0
        private set

    /**
     * 0.25 Task 13 (R70): how many turns ended with [CONTEXT_OVERFLOW] after both recoveries were exhausted.
     *
     * Separate from [overflowRecoveries] on purpose: "the request needed a cheaper retry" and "the request could not be
     * made to fit" are different facts, and a metric that merged them would hide an outage behind a healthy retry count.
     */
    var overflowFailures: Int = 0
        private set

    companion object {
        /**
         * The token-unit budget a frame is trimmed to (0.3 design §6.2): the session `ContextPolicy.hardLimit`,
         * `hardInputLimit - hardMargin`. Before the final-fix wave this was a second, ad-hoc formula
         * (`minOf(inputBudget, usable - maxOutput - safety) - FIXED_OVERHEAD`), which left the policy's hard margin
         * dead: the trimmer ran closer to the send-time bound than the policy said it should.
         *
         * Both stages still price text with [TokenMeter] and charge the same
         * [com.sheen.rokid.contracts.ImageBudget.UNITS_PER_IMAGE] per image (R68), and a `TOOL_ROUND` is itemised into
         * one wire item per call/output while the trimmer charged one +32 for the whole segment. The real gate remains
         * the send-time `ContextBudgeter.prepare`: when it throws
         * [ContractError.ContextWindowExceeded], the gateway's bounded AGGRESSIVE/HARD recovery re-trims and resends
         * the same turn, and exhausting both recoveries is reported as `CONTEXT_OVERFLOW`.
         */
        internal fun windowTokens(profile: ModelProfile): Int = ContextPolicy.from(profile).hardLimit
    }

    /**
     * One fail-closed turn: a typed safe code and no transport call. Used for a missing profile, a policy that cannot
     * be derived, and a fixed prompt that cannot fit — three different conditions that must not read as one.
     */
    private fun refusal(code: String): GatewayReply =
        GatewayReply(emptyList(), "", ModelEnd.FAILED, code)

    override fun turn(request: GatewayTurn): GatewayReply {
        val profile = profileLookup(request.profileId)
            ?: return refusal(ContextPolicy.PROFILE_MISSING)
        // The plugin derived the same policy at activation. If it failed there, every turn of the session must fail
        // with that exact code; otherwise the gateway classifies the profile itself (invalid vs too small).
        request.contextFailure?.let { return refusal(it) }
        ContextPolicy.failureCode(profile)?.let { return refusal(it) }
        val policy = ContextPolicy.from(profile)
        // Phase 5: an assembled RequestFrame is trimmed below and mapped to the request's shape; without one, the
        // pre-Phase-5 `split(history)` path is kept verbatim, including its "a transport exception escapes" contract.
        val frame = request.frame
        if (frame == null) {
            val transcript = split(request.history)
                ?: return refusal(NO_USER_TURN)
            // The fixed prompt alone (system slot + this turn's input) can already exceed the hard input; no history
            // trim can rescue that request, so it is refused with its own code instead of burning the overflow
            // recoveries. Measured before `sendTurn`, so the transport is never called.
            if (fixedPromptTokens(request.instructions, transcript.input) > policy.hardInputLimit) {
                return refusal(ContextPolicy.PROMPT_EXCEEDS_BUDGET)
            }
            return sendTurn(request, modelRequest(request, profile, request.instructions, transcript))
        }
        // Same fail-closed check for the frame path, against the untrimmed system slot + dynamic tail + current
        // input. A frame with no USER_INPUT is left to `frameToTranscript` (it answers NO_USER_TURN).
        val fixed = fixedPromptTokens(frame)
        if (fixed != null && fixed > policy.hardInputLimit) {
            return refusal(ContextPolicy.PROMPT_EXCEEDS_BUDGET)
        }
        // The trim window is the policy's hard limit; a caller-supplied `limitTokens` (the plugin's session policy,
        // or a test seam) wins.
        val limit = if (request.limitTokens == Int.MAX_VALUE) policy.hardLimit else request.limitTokens
        return turnWithOverflowRecovery(request, profile, frame, limit)
    }

    /**
     * The fixed, untrimmable prompt cost: the `+32` accounting overhead once, the system slot as one message, and the
     * request input — which on the frame path already folds the request-scoped dynamic tail and the wearer's turn.
     * Token units, the same unit [TokenMeter] and the trimmer use.
     */
    private fun fixedPromptTokens(instructions: String, input: List<InputPart>): Int {
        var total = FIXED_OVERHEAD + tokenUnitsPlusOverhead(instructions)
        input.forEach { part ->
            if (part is InputPart.Text) total += tokenUnitsPlusOverhead(part.text)
        }
        return total
    }

    /**
     * The frame path's fixed prompt cost before trimming: system slot + the request-scoped tail + the one
     * `USER_INPUT`, each at the trimmer's own per-segment price. `null` when the frame carries no `USER_INPUT`
     * (`frameToTranscript` owns that verdict).
     */
    private fun fixedPromptTokens(frame: RequestFrame): Int? {
        val flow = frame.conversationFlow
        val inputIndex = flow.indexOfLast { it.origin == SegmentOrigin.USER_INPUT }
        if (inputIndex < 0) return null
        val dynamic = RequestLayout.dynamicTail(flow).sumOf { tokenUnitsPlusOverhead(it.text) }
        return FIXED_OVERHEAD + tokenUnitsPlusOverhead(frame.systemText) + dynamic +
            tokenUnitsPlusOverhead(flow[inputIndex].text)
    }

    /**
     * The frame path with R70's bounded recovery: one normal send, then up to [MAX_OVERFLOW_RECOVERIES] deterministic
     * re-sends of the **same** turn with a cheaper shape, then [CONTEXT_OVERFLOW].
     *
     * The two detectors are the send-time budget's dedicated overflow signal
     * ([ContractError.ContextWindowExceeded], thrown only when the request does not fit) and the provider's own overflow
     * verdict ([GatewayReply.safeCode] carrying [CONTEXT_OVERFLOW]). Recovery ① drops the historical tool rounds;
     * recovery ② hard-truncates to the newest turn + the current input. No LLM summary is ever asked for: a summary
     * affects the *next* turn, not this request (D100), and spending another model call to rescue one would be the
     * failure mode the ruling forbids.
     *
     * A different [ContractError.ConfigurationRejected] — a profile-revision race, an invalid endpoint, a client-state
     * refusal — is **not** an overflow (R101) and escapes this method unchanged: retrying it with a cheaper context
     * would spend a recovery and relabel the real cause as `CONTEXT_OVERFLOW`.
     */
    private fun turnWithOverflowRecovery(
        request: GatewayTurn,
        profile: ModelProfile,
        frame: RequestFrame,
        limit: Int,
    ): GatewayReply {
        var mode = TrimMode.STANDARD
        var recoveries = 0
        while (true) {
            val report = TurnTrimmer().trim(frame, limit, mode)
            lastTrim = report.counts
            val transcript = frameToTranscript(report.kept, request.userUtterance)
                ?: return GatewayReply(emptyList(), "", ModelEnd.FAILED, NO_USER_TURN, overflowRecoveries = recoveries)
            val modelRequest = modelRequest(request, profile, frame.systemText, transcript)
            val reply = try {
                // The metric describes the kept frame, never the untrimmed assembly: a HARD recovery can drop the
                // request-scoped tail, and a `P3_CACHE` line that still counted it would claim bytes no longer sent.
                sendTurn(request, modelRequest, report.kept)
            } catch (rejected: ContractError.ContextWindowExceeded) {
                // The one local rejection this path may answer with a cheaper request: the send-time budget refused
                // the shape the trimmer produced. A plain `ConfigurationRejected` is a different refusal and still
                // escapes; a capability refusal is a different exception and still escapes.
                if (recoveries >= MAX_OVERFLOW_RECOVERIES) return contextOverflowFailure(recoveries = recoveries)
                recoveries++
                overflowRecoveries++
                mode = mode.next()
                continue
            }
            if (isContextOverflow(reply)) {
                if (recoveries >= MAX_OVERFLOW_RECOVERIES) {
                    return contextOverflowFailure(reply.usage, recoveries = recoveries)
                }
                recoveries++
                overflowRecoveries++
                mode = mode.next()
                continue
            }
            return reply.copy(overflowRecoveries = recoveries)
        }
    }

    private fun TrimMode.next(): TrimMode = when (this) {
        TrimMode.STANDARD -> TrimMode.AGGRESSIVE
        TrimMode.AGGRESSIVE, TrimMode.HARD -> TrimMode.HARD
    }

    /** A provider verdict, never a message match: only the stable code the transport mapped the wire error to. */
    private fun isContextOverflow(reply: GatewayReply): Boolean =
        reply.safeCode?.contains(CONTEXT_OVERFLOW, ignoreCase = true) == true

    private fun contextOverflowFailure(usage: ModelUsage? = null, recoveries: Int = 0): GatewayReply {
        overflowFailures++
        return GatewayReply(emptyList(), "", ModelEnd.FAILED, CONTEXT_OVERFLOW, usage, recoveries)
    }

    /** Builds the one stateless request shape every model call of this gateway uses. */
    private fun modelRequest(
        request: GatewayTurn,
        profile: ModelProfile,
        instructions: String,
        transcript: Transcript,
    ): ModelRequest {
        val startedAt = clock.nowNanos()
        // The loop's turn id is the only id this adapter owns, and one turn finishes before the next one starts, so it
        // is also the request id the stream's events echo back.
        val requestId = request.turnId
        return ModelRequest(
            envelope = envelopeFor(requestId, startedAt, startedAt),
            requestId = requestId,
            turnId = request.turnId,
            profileId = profile.id,
            profileRevision = profile.revision,
            instructions = instructions,
            input = transcript.input + (audioParts?.invoke() ?: emptyList()),
            history = transcript.history,
            output = OutputModality.TEXT,
            deadlineNanos = request.deadlineNanos,
            tools = request.tools,
            // 0.3 design §5.2: the turn policy's cap travels beside the shape it applies to; the adapter falls back to
            // the profile's window-reservation value when this is null (pre-0.3 callers).
            maxOutputTokens = request.maxOutputTokens,
        )
    }

    /**
     * One model round trip: send the request, wait for the terminal (or the deadline/stop), and turn the accumulated
     * stream into a [GatewayReply]. Extracted verbatim from [turn] so overflow recovery can repeat it; a transport
     * failure is still not caught here.
     *
     * Every request reports its own count-only `P3_CACHE` line when the reply carried usage — the wake run's tool
     * rounds, the proactive `turn`, and each deterministic overflow re-send alike. The line is per request, never a
     * sum: [AgentLoop] no longer reports a run-total duplicate (0.3 Phase 2 Task 11). [sentFrame] is the frame that was
     * actually put on the wire (the trimmer's kept frame); the untrimmed [GatewayTurn.frame] is never used for sizes.
     */
    private fun sendTurn(
        request: GatewayTurn,
        modelRequest: ModelRequest,
        sentFrame: RequestFrame? = request.frame,
    ): GatewayReply {
        val reply = sendTurnOnce(request, modelRequest)
        emitCacheMetric(request, reply, sentFrame)
        return reply
    }

    /**
     * The single `P3_CACHE` line of one model request, or nothing when the provider reported no accounting.
     *
     * Counts only, fixed field order: `cached`/`miss` from the provider's own cache members (an unreported counter is
     * `-`, a reported zero stays `0`), `inputTokens`/`outputTokens`, and the request's shape as two character counts —
     * the sent frame's system slot and the sum of its dynamic-tail segment texts. The legacy no-frame path sends
     * `instructions` directly, so it reports that length and a zero tail. No text ever rides the line.
     */
    private fun emitCacheMetric(request: GatewayTurn, reply: GatewayReply, sentFrame: RequestFrame?) {
        val usage = reply.usage ?: return
        // `systemText` joins the system segments with newlines; for one segment it is exactly its text.
        val systemChars = sentFrame?.systemText?.length ?: request.instructions.length
        val dynamicChars = sentFrame?.conversationFlow
            ?.let { flow -> RequestLayout.dynamicTail(flow).sumOf { it.text.length } }
            ?: 0
        val line = buildString {
            append("P3_CACHE")
            append(" cached=").append(countField(usage.cachedInputTokens))
            append(" miss=").append(countField(usage.cacheMissInputTokens))
            append(" inputTokens=").append(countField(usage.inputTokens))
            append(" outputTokens=").append(countField(usage.outputTokens))
            append(" systemChars=").append(systemChars)
            append(" dynamicChars=").append(dynamicChars)
        }
        // Observability is never a verdict: a throwing sink must not turn a delivered reply into a failed turn.
        runCatching { metricLine(line) }
    }

    /** `-` for a count the provider did not report; a reported zero is printed as `0`, never fabricated. */
    private fun countField(value: Long?): String = value?.toString() ?: "-"

    private fun sendTurnOnce(request: GatewayTurn, modelRequest: ModelRequest): GatewayReply {
        val accumulated = Accumulated()
        val wakeAtDeadline = scheduler.at(request.deadlineNanos) { accumulated.wake() }
        val caller = Thread.currentThread()
        val handle = try {
            client.request(modelRequest) { event ->
                val accepted = accumulated.accept(event, Thread.currentThread() === caller)
                // 0.24 S4 (spec §5.5): every accepted text delta is handed to the display observer on the transport
                // thread. It is deliberately outside the accumulator's lock (the turn thread waits on that lock) and
                // best-effort: a display that throws is a lost revision, never a lost answer. A delta after the
                // verdict is not accepted and therefore not streamed - the final text stays authoritative.
                if (accepted && event is ModelStreamEvent.TextDelta) {
                    runCatching { request.onTextDelta?.invoke(event.text) }
                }
            }
        } catch (error: Throwable) {
            wakeAtDeadline.cancel()
            throw error
        }
        try {
            // A stop that arrived while the turn was in flight wins over a reply that was delivered after it.
            if (stop()) return cancel(handle, accumulated)
            if (accumulated.settled) return reply(accumulated)
            // A client that ran its whole stream to completion on this thread and left no terminal has ended without a
            // verdict; waiting further could only burn the deadline.
            if (accumulated.sawSynchronousEvent) {
                handle.cancel()
                return GatewayReply(emptyList(), accumulated.text(), ModelEnd.FAILED, NO_TERMINAL)
            }
            while (true) {
                if (stop()) return cancel(handle, accumulated)
                if (accumulated.settled) return reply(accumulated)
                if (clock.nowNanos() >= request.deadlineNanos) {
                    handle.cancel()
                    return GatewayReply(emptyList(), accumulated.text(), ModelEnd.DEADLINE, DEADLINE)
                }
                accumulated.waitForStep(WAIT_STEP_MILLIS)
            }
        } catch (interrupted: InterruptedException) {
            // An interrupt tears the turn down from outside. Cancelling only the scheduler timer would leave the
            // client's stream running past the turn, so the transport handle is cancelled as well.
            handle.cancel()
            throw interrupted
        } finally {
            wakeAtDeadline.cancel()
        }
    }

    private fun cancel(handle: CancellationHandle, accumulated: Accumulated): GatewayReply {
        handle.cancel()
        // The stop may race a terminal that was already delivered; when it was, the provider's accounting is still
        // real and rides the cancelled reply up to the run.
        return GatewayReply(
            emptyList(), accumulated.text(), ModelEnd.CANCELLED, CANCELLED, accumulated.terminal?.usage,
        )
    }

    private fun reply(accumulated: Accumulated): GatewayReply {
        val terminal = accumulated.terminal
            ?: return GatewayReply(emptyList(), accumulated.text(), ModelEnd.FAILED, NO_TERMINAL)
        val calls = accumulated.calls()
        val text = accumulated.text()
        // A verdict the transport already delivered (FAILED / DEADLINE / CANCELLED) is not softened. An otherwise
        // successful empty turn that a half-streamed call left behind is re-labelled, so a truncated call can never
        // read as a completed, empty success.
        val truncated = accumulated.hasDanglingDelta && calls.isEmpty() && text.isBlank() &&
            terminal.end != ModelEnd.FAILED && terminal.end != ModelEnd.DEADLINE && terminal.end != ModelEnd.CANCELLED
        return if (truncated) {
            // Keep the provider's own machine-readable reason when it sent one: re-labelling the verdict must not erase
            // what the transport actually reported. The token accounting survives the re-label for the same reason.
            val code = terminal.safeCode?.let { "$TOOL_CALL_DELIVERY_INCOMPLETE:$it" } ?: TOOL_CALL_DELIVERY_INCOMPLETE
            GatewayReply(emptyList(), text, ModelEnd.INCOMPLETE, code, terminal.usage)
        } else {
            GatewayReply(calls, text, terminal.end, terminal.safeCode, terminal.usage)
        }
    }

    /** The request `input` (empty for a continuation turn) and the transcript that becomes `ModelRequest.history`. */
    private class Transcript(val input: List<InputPart>, val history: List<ModelHistoryItem>)

    /**
     * Maps a (already trimmed) frame's conversation flow onto the request's `input`/`history` shape.
     *
     * The in-flight user turn is the frame's one `USER_INPUT` segment and is the **last** text carried by `input` —
     * never history. A conversation-flow segment's `role` is metadata ([Segment.role]), never inferred from text.
     *
     * ## The prefix is system + tools + history; the request-scoped context is the input tail
     *
     * `history` is the transcript, verbatim: its first item is the oldest surviving transcript text, tool rounds keep
     * their structured call/output items, and a summary is folded into that first user turn. Nothing request-scoped is
     * ever folded in: `PERCEPTION`, `MEMORY_EVIDENCE`, `SKILL_BODY`, `RUNTIME_STATE` and `RETRY` ride the request
     * `input` ahead of the wearer's turn, in flow order (0.3 design §5.1/§5.3, [RequestLayout.dynamicTail]). A
     * perception tick, newly retrieved memory, a `load_skill` pull or a retry therefore rewrites only the tail, never
     * the system slot or the history, so the provider's prefix cache stays warm; the one accepted prefix rewrite is a
     * compression point replacing the summary (§6.5).
     *
     * ## The summary is the one head context
     *
     * A `SUMMARY` is not a conversation turn: it is lifted out of the flow and folded into the history's **first
     * user text**, ahead of the oldest surviving transcript text. Attribution is `origin == SUMMARY` throughout —
     * never a text prefix — and a summary body is rendered with [SummaryBoundary] here unless it already carries the
     * boundary (the store hands over the bounded text).
     *
     * The leading context cannot be a **standalone** user message: `ContextBudgeter` requires the history to open
     * with a user text and then alternate, so a leading user message followed by the transcript's own first user turn
     * is two consecutive user texts and is rejected. Folding the summary **into** that first user turn keeps one
     * leading user message, keeps the alternation, and leaves every transcript role untouched. When the flow carries
     * no transcript user turn to ride (an empty store — the first turn of a session that already has a summary), the
     * summary is folded into the request `input` instead, because a history holding only the summary would be a lone
     * user group and unsendable.
     *
     * `RUNTIME_STATE` (Task 12, R59) and `RETRY` were the first tail riders: as history turns they would open a
     * **new** group ending on a bare user text, which `ContextBudgeter` rejects by design (`groups.last().size == 1`)
     * — the loop's designed retry would be refused before it was ever sent. They now share the dynamic tail with
     * memory, perception and a pulled skill body, in flow order: the surviving transcript's last group stays closed,
     * and the request-scoped context is the first thing the model reads in the message it is answering.
     *
     * Folding a context segment into the head/input message removes that segment's own +32 per-message overhead, so
     * the merged message prices at or below the trimmer's per-segment total for the pieces it merges (the `\n\n`
     * separators the join introduces are at most one token unit each). That is only the price-lowering direction: a
     * `TOOL_ROUND` renders as one wire item per call/output entry, and `ContextBudgeter` charges each item its own +32
     * while the trimmer charged one +32 for the whole segment, so with several entries the rendered request can price
     * more than the 32-unit window margin above the trimmer's total. Nothing here claims the rendered request is priced
     * identically to the per-segment total, and the margin is not the guarantee that the request fits: the real gate
     * is the send-time `ContextBudgeter.prepare`, whose `ContextWindowExceeded` is answered by the gateway's bounded
     * AGGRESSIVE/HARD recovery.
     *
     * Returns `null` — the caller answers `NO_USER_TURN` — when the mapping could not produce a request
     * `ContextBudgeter` would send: no `USER_INPUT` segment at all, a non-empty history whose first text is not
     * a user turn, or a history that ends on a lone user message (the last one is the shape cut ① leaves when the
     * only surviving turn is an unanswered user half).
     *
     * ## Cut ① may evict the only copy of the question (Task 27, R143)
     *
     * A continuation frame renders the fixed loop cue as its `USER_INPUT` precisely because the transcript already
     * carries the wearer's question (Task 26). `TurnTrimmer` cut ① removes whole turns from the oldest end and the
     * current turn is a turn too: under a tight window it can evict the current `USER` transcript and leave the cue as
     * the model's **only** input. When no `Text(USER)` survives in the built history and [fallbackUtterance] is
     * non-blank, the request input is rebuilt from that utterance instead of the cue. The request-scoped dynamic tail
     * that normally rides ahead of the input is kept, so the first turn — whose input already is the utterance — is
     * rendered identically as before. When a `USER` text does survive, the input is left untouched: restoring it there
     * would reintroduce the duplicate Task 26 removed. A blank fallback changes nothing; the gateway never invents a
     * question.
     */
    private fun frameToTranscript(frame: RequestFrame, fallbackUtterance: String?): Transcript? {
        val flow = frame.conversationFlow
        val inputIndex = flow.indexOfLast { it.origin == SegmentOrigin.USER_INPUT }
        if (inputIndex < 0) return null
        val inputText = flow[inputIndex].text
        // The request-scoped context is the request tail (0.3 design §5.1/§5.3): `dynamicTail` selects exactly
        // PERCEPTION/MEMORY_EVIDENCE/SKILL_BODY/RUNTIME_STATE/RETRY before the input and keeps their frame order.
        val dynamicTexts = RequestLayout.dynamicTail(flow)
            .map { it.text }
            .filter { it.isNotBlank() }
        val dynamicText = dynamicTexts.joinToString("\n\n")
        val requestInput = if (dynamicText.isEmpty()) inputText else "$dynamicText\n\n$inputText"
        val summaries = mutableListOf<String>()
        val history = mutableListOf<ModelHistoryItem>()
        for (segment in flow.take(inputIndex)) {
            when (segment.origin) {
                SegmentOrigin.SUMMARY -> summaries += boundedSummary(segment.text)
                // Request-scoped context: it is already in `dynamicTexts` above and must never enter the cacheable
                // history — not even as part of the head user message.
                SegmentOrigin.MEMORY_EVIDENCE,
                SegmentOrigin.PERCEPTION,
                SegmentOrigin.SKILL_BODY,
                SegmentOrigin.RETRY,
                SegmentOrigin.RUNTIME_STATE -> Unit
                SegmentOrigin.TOOL_ROUND -> {
                    // A tool round is not conversation-flow text: the endpoint is stateless, so the model's own call
                    // must be echoed back as a call item beside its result or the exchange does not round-trip (and two
                    // assistant texts in a row are a shape `ContextBudgeter` rejects). A segment this cannot parse
                    // (a plain tool-round summary, or a store's raw text) keeps the pre-existing text mapping.
                    val items = toolRoundItems(segment.text)
                    if (items.isEmpty()) {
                        history += ModelHistoryItem.Text(roleOf(segment), segment.text)
                    } else {
                        history += items
                    }
                }
                else -> history += ModelHistoryItem.Text(roleOf(segment), segment.text)
            }
        }
        val summaryText = summaries.joinToString("\n\n")
        // A summary with no transcript user turn to ride goes into the input; `restoreCurrentUtterance` must then
        // keep that summary prefix too.
        var summaryInInput = false
        val transcript = when {
            summaries.isEmpty() ->
                if (history.isEmpty() || opensWithUser(history)) {
                    Transcript(listOf(InputPart.Text(requestInput)), history)
                } else {
                    null
                }
            else -> {
                val head = history.firstOrNull()
                when {
                    head is ModelHistoryItem.Text && head.role == MessageRole.USER -> {
                        history[0] = ModelHistoryItem.Text(MessageRole.USER, "$summaryText\n\n${head.text}")
                        Transcript(listOf(InputPart.Text(requestInput)), history)
                    }
                    history.isEmpty() -> {
                        summaryInInput = true
                        Transcript(listOf(InputPart.Text("$summaryText\n\n$requestInput")), history)
                    }
                    else -> null
                }
            }
        } ?: return null
        // A history whose last group is a single user message is refused exactly like the shapes above: it is what cut
        // ① leaves when the only surviving turn is an unanswered user half, and `ContextBudgeter` rejects it **by
        // design** (`groups.last().size == 1` — whole-group eviction depends on it). Handing it to the transport would
        // make every later turn fail with `ConfigurationRejected` until a restart (the 2026-10-14 incident).
        if (history.isNotEmpty() && !lastGroupIsClosed(history)) return null
        val prefix = buildList {
            if (summaryInInput) add(summaryText)
            addAll(dynamicTexts)
        }
        return restoreCurrentUtterance(transcript, prefix, fallbackUtterance)
    }

    /**
     * R143's safety net: when the trimmed history carries no `USER` text, the current input is rebuilt on
     * [fallbackUtterance] (the wearer's question) rather than on the frame's fixed cue.
     *
     * Only the frame's own `USER_INPUT` is interchangeable here. The request-scoped tail — perception, memory
     * evidence, a skill body, runtime state, a retry instruction, and a summary that had no transcript user head to
     * ride — is not the wearer's conversation turn and stays ahead of it ([prefix]), exactly as [frameToTranscript]
     * had arranged it. That is also why the opening turn is unchanged: its input already is the utterance, so the
     * rebuilt string is identical.
     *
     * A history that still carries a `USER` text keeps its input: the question is already there once, and replacing the
     * input would be the duplicate Task 26 removed. A null or blank fallback never invents content.
     */
    private fun restoreCurrentUtterance(
        transcript: Transcript,
        prefix: List<String>,
        fallbackUtterance: String?,
    ): Transcript {
        val fallback = fallbackUtterance?.takeIf { it.isNotBlank() } ?: return transcript
        val historyCarriesUser = transcript.history.any {
            it is ModelHistoryItem.Text && it.role == MessageRole.USER
        }
        if (historyCarriesUser) return transcript
        val restoredInput = listOf(InputPart.Text((prefix + fallback).joinToString("\n\n")))
        return Transcript(restoredInput, transcript.history)
    }

    /**
     * Whether the history's last whole-turn group holds more than its opening `USER` text — the property
     * `ContextBudgeter` needs for whole-group eviction. A bare trailing `USER` text is a group of one and is
     * unsendable.
     */
    private fun lastGroupIsClosed(history: List<ModelHistoryItem>): Boolean =
        (history.last() as? ModelHistoryItem.Text)?.role != MessageRole.USER

    /**
     * A `TOOL_ROUND` segment's wire items: the JSON array of call/output entries the frame vocabulary defines
     * (`{"type":"call",…}` / `{"type":"output",…}`), decoded back into [ModelHistoryItem]s.
     *
     * Answers an empty list for anything this cannot read — a plain tool-round summary, or an unparsable body — so the
     * caller can fall back to the segment's text. An entry missing the identity the contract type requires (`callId`,
     * `name`) is skipped rather than defaulted: a call with an invented id could never be matched to its output. The
     * `images` member is optional, so a tool round stored before 0.25 (no member) still decodes.
     */
    private fun toolRoundItems(text: String): List<ModelHistoryItem> {
        val array = runCatching { toolRoundJson.parseToJsonElement(text) as? JsonArray }.getOrNull() ?: return emptyList()
        return array.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            when (obj["type"]?.jsonPrimitive?.contentOrNull) {
                "call" -> {
                    val callId = obj["callId"]?.jsonPrimitive?.contentOrNull
                    val name = obj["name"]?.jsonPrimitive?.contentOrNull
                    if (callId.isNullOrBlank() || name.isNullOrBlank()) {
                        null
                    } else {
                        ModelHistoryItem.ToolCall(
                            callId = callId,
                            name = name,
                            argsJson = obj["argsJson"]?.jsonPrimitive?.contentOrNull ?: "{}",
                            itemId = obj["itemId"]?.jsonPrimitive?.contentOrNull ?: callId,
                        )
                    }
                }
                "output" -> {
                    val callId = obj["callId"]?.jsonPrimitive?.contentOrNull
                    if (callId.isNullOrBlank()) {
                        null
                    } else {
                        ModelHistoryItem.ToolOutput(
                            callId = callId,
                            output = obj["output"]?.jsonPrimitive?.contentOrNull ?: "",
                            images = imageRefs(obj["images"]),
                        )
                    }
                }
                else -> null
            }
        }
    }

    /**
     * The optional `images` member of an output item, in order. A tool round stored before 0.25 has no member and
     * decodes to an empty list, so old history keeps working. An entry without the artifact identity is skipped the
     * same way a call without its own identity is; the numeric metadata is forward-tolerant (missing means 0) because
     * the encoder re-reads it from the resolved artifact anyway.
     */
    private fun imageRefs(element: JsonElement?): List<ImageRef> {
        val array = element as? JsonArray ?: return emptyList()
        return array.mapNotNull { entry ->
            val obj = entry as? JsonObject ?: return@mapNotNull null
            val artifactId = obj["artifactId"]?.jsonPrimitive?.contentOrNull
            val mime = obj["mime"]?.jsonPrimitive?.contentOrNull
            if (artifactId.isNullOrBlank() || mime.isNullOrBlank()) {
                null
            } else {
                ImageRef(
                    artifactId = artifactId,
                    mime = mime,
                    width = obj["width"]?.jsonPrimitive?.intOrNull ?: 0,
                    height = obj["height"]?.jsonPrimitive?.intOrNull ?: 0,
                    sha256 = obj["sha256"]?.jsonPrimitive?.contentOrNull ?: "",
                )
            }
        }
    }

    /** A summary body as it must go on the wire: bounded once, never twice. */
    private fun boundedSummary(text: String): String =
        if (SummaryBoundary.isRendered(text)) text else SummaryBoundary.render(text)

    /** Whether the history opens on a user text, the only opening `ContextBudgeter` accepts. */
    private fun opensWithUser(history: List<ModelHistoryItem>): Boolean =
        (history.first() as? ModelHistoryItem.Text)?.role == MessageRole.USER

    private fun roleOf(segment: Segment): MessageRole = when (segment.role) {
        SegmentRole.USER -> MessageRole.USER
        SegmentRole.ASSISTANT, null -> MessageRole.ASSISTANT
    }

    /**
     * Splits the loop's transcript into the request's `input` and `history`.
     *
     * The wire renders `input` last, so a transcript that ends on the pending `Text(USER)` lifts that turn into `input`
     * and leaves every earlier item as history. A transcript that has already moved past its user turn (a tool round
     * follows it) is a history-only request: the whole list stays history and `input` is empty, so the tool output
     * stays last. A transcript with no user turn at all is refused here as `NO_USER_TURN`, because `ContextBudgeter`
     * requires the history to open with a `USER` text and such a request could never be sent.
     */
    private fun split(history: List<ModelHistoryItem>): Transcript? {
        val index = history.indexOfLast { it is ModelHistoryItem.Text && it.role == MessageRole.USER }
        if (index < 0) return null
        val user = history[index] as ModelHistoryItem.Text
        return if (index == history.lastIndex) {
            Transcript(listOf(InputPart.Text(user.text)), history.take(index))
        } else {
            Transcript(emptyList(), history.toList())
        }
    }

    /**
     * The mutable stream state a transport thread fills and the turn thread reads: the accumulated text, the calls that
     * were completed, whether a call was left half-streamed, and the one terminal that settles the turn.
     */
    private class Accumulated {
        private val lock = ReentrantLock()
        private val ready = lock.newCondition()
        private val text = StringBuilder()
        private val deltaItems = LinkedHashSet<String>()
        private val terminalItems = LinkedHashSet<String>()
        private val terminalCalls = mutableListOf<ModelStreamEvent.ToolCallTerminal>()
        private var sawSynchronous = false
        private var terminalEvent: ModelStreamEvent.Terminal? = null

        val settled: Boolean get() = lock.withLock { terminalEvent != null }
        val sawSynchronousEvent: Boolean get() = lock.withLock { sawSynchronous }
        val terminal: ModelStreamEvent.Terminal? get() = lock.withLock { terminalEvent }
        val hasDanglingDelta: Boolean get() = lock.withLock { deltaItems.any { it !in terminalItems } }

        fun text(): String = lock.withLock { text.toString() }

        fun calls(): List<ToolCall> = lock.withLock {
            terminalCalls.map { ToolCall(it.callId, it.name, it.argsJson, it.itemId) }
        }

        fun accept(event: ModelStreamEvent, onCallingThread: Boolean): Boolean {
            lock.withLock {
                // One verdict: everything after the terminal is a late delivery and is dropped.
                if (terminalEvent != null) return false
                sawSynchronous = sawSynchronous || onCallingThread
                return when (event) {
                    is ModelStreamEvent.TextDelta -> {
                        text.append(event.text)
                        // Only text is a display increment; the other events stay internal to the accumulation, so
                        // the observer is called exactly once per accepted answer chunk.
                        true
                    }
                    is ModelStreamEvent.ToolCallDelta -> {
                        deltaItems += event.itemId
                        false
                    }
                    is ModelStreamEvent.ToolCallTerminal -> {
                        terminalItems += event.itemId
                        terminalCalls += event
                        false
                    }
                    is ModelStreamEvent.Terminal -> {
                        terminalEvent = event
                        ready.signalAll()
                        false
                    }
                }
            }
        }

        fun wake() {
            lock.withLock { ready.signalAll() }
        }

        fun waitForStep(millis: Long) {
            lock.withLock { if (terminalEvent == null) ready.await(millis, TimeUnit.MILLISECONDS) }
        }
    }
}
