package com.sheen.rokid.agent

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

private const val PROFILE_MISSING = "PROFILE_MISSING"
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

/** Lenient by construction: a `TOOL_ROUND` body is a JSON document assembled by a caller, and a bad one falls back. */
private val toolRoundJson = Json { isLenient = true }

/**
 * The one [ModelGateway] over `plugin-api`'s streaming [ModelClient]: it turns the loop's [GatewayTurn] into a
 * stateless [ModelRequest], accumulates the stream into a [GatewayReply], and owns the turn's wall-clock bound.
 *
 * **The request input is the in-flight user turn, not history.** `ModelRequest.input` is rendered last on the wire, so
 * a transcript that ends on the pending `Text(USER)` lifts that turn into `input`. When the transcript has already
 * moved past that turn (a tool round appended after it), the whole transcript stays `ModelRequest.history` and the
 * request carries an empty `input`: a history-only request, which `plugin-api` admits and `ContextBudgeter` validates.
 * That keeps the tool output last on the wire instead of re-asserting the user message after it.
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
     * pre-P5 text-only behaviour byte for byte. A lambda rather than a value so the segment is resolved at request
     * time, not at gateway construction time.
     */
    private val audioParts: (() -> List<InputPart>)? = null,
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

    companion object {
        /**
         * The byte budget a frame is trimmed to, in the same unit and against the same bound `ContextBudgeter`
         * enforces at send time (`minOf(inputBudgetTokens, usableWindow - maxOutput - safety)`).
         *
         * The `- [FIXED_OVERHEAD]` is a **deliberate 32-byte safety margin**, not a correction.
         * [TurnTrimmer.total] and `ContextBudgeter.prepare` (`ContextBudgeter.kt:55-65`) compute structurally
         * identical totals against the same `minOf(...)` limit — the same per-item `bytes + 32`, the same fixed 32,
         * and `instructions == frame.systemText` — so with one shared limit they would already agree exactly. Taking
         * one more 32 off makes the trimmer 32 bytes **stricter** than the check it must satisfy, which is the safe
         * direction; the cost is that a frame within 32 bytes of fitting is trimmed one message earlier than strictly
         * necessary. Nothing here claims the subtraction is what prevents a rejection: the acceptance test's
         * discriminating assertion is the send-time `prepare` call itself.
         */
        internal fun windowBytes(profile: ModelProfile): Int =
            minOf(
                profile.inputBudgetTokens,
                profile.usableWindowTokens - profile.maxOutputTokens - profile.safetyTokens,
            ) - FIXED_OVERHEAD
    }

    override fun turn(request: GatewayTurn): GatewayReply {
        val profile = profileLookup(request.profileId)
            ?: return GatewayReply(emptyList(), "", ModelEnd.FAILED, PROFILE_MISSING)
        // Phase 5: an assembled RequestFrame is trimmed to the window here and mapped to the request's shape; without
        // one, the pre-Phase-5 `split(history)` path is kept verbatim.
        val frame = request.frame
        val transcript = if (frame == null) {
            split(request.history)
                ?: return GatewayReply(emptyList(), "", ModelEnd.FAILED, NO_USER_TURN)
        } else {
            // The window is the same expression `ContextBudgeter` bounds the send with, minus the deliberate
            // 32-byte margin (see [windowBytes]). A caller-supplied `limitBytes` (test seam) wins.
            val limit = if (request.limitBytes == Int.MAX_VALUE) windowBytes(profile) else request.limitBytes
            val report = TurnTrimmer().trim(frame, limit)
            lastTrim = report.counts
            frameToTranscript(report.kept)
                ?: return GatewayReply(emptyList(), "", ModelEnd.FAILED, NO_USER_TURN)
        }
        val startedAt = clock.nowNanos()
        // The loop's turn id is the only id this adapter owns, and one turn finishes before the next one starts, so it
        // is also the request id the stream's events echo back.
        val requestId = request.turnId
        val modelRequest = ModelRequest(
            envelope = envelopeFor(requestId, startedAt, startedAt),
            requestId = requestId,
            turnId = request.turnId,
            profileId = profile.id,
            profileRevision = profile.revision,
            instructions = if (frame == null) request.instructions else frame.systemText,
            input = transcript.input + (audioParts?.invoke() ?: emptyList()),
            history = transcript.history,
            output = OutputModality.TEXT,
            deadlineNanos = request.deadlineNanos,
            tools = request.tools,
        )
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
     * The in-flight user turn is the frame's one `USER_INPUT` segment and becomes `input` — never history. A
     * conversation-flow segment's `role` is metadata ([Segment.role]), never inferred from text.
     *
     * ## The summary and the memory form the head of the history — as one user message
     *
     * A `SUMMARY`, every `MEMORY_EVIDENCE` and every `PERCEPTION` segment is not a conversation turn: it is lifted
     * out of the flow and folded into the **one leading user message**, ahead of the oldest surviving transcript
     * text. Attribution is `origin == SUMMARY` throughout — never a text prefix — and a summary body is rendered
     * with [SummaryBoundary] here unless it already carries the boundary (the store hands over the bounded text).
     *
     * This is the owner-approved shape (2026-10-16). The previous order (transcript → memory → `USER_INPUT`) made
     * the history end on a lone `Text(USER, memory)` group immediately before the request `input`, a shape
     * `ContextBudgeter` rejects **by design** (whole-group eviction depends on it), so every later turn would fail
     * `ConfigurationRejected` until an app restart — the 2026-10-14 incident.
     *
     * The leading context cannot be a **standalone** user message: `ContextBudgeter` requires the history to open
     * with a user text and then alternate, so a leading user message followed by the transcript's own first user
     * turn is two consecutive user texts and is rejected. Folding the context **into** that first user turn keeps
     * one leading user message, keeps the alternation, and leaves every transcript role untouched. When the flow
     * carries no transcript user turn to ride (an empty store — the first turn of a session that already has
     * memory), the context is folded into the request `input` instead, because a history holding only the context
     * would be a lone user group and unsendable.
     *
     * Returns `null` — the caller answers `NO_USER_TURN` — when the mapping could not produce a request
     * `ContextBudgeter` would send: no `USER_INPUT` segment at all, a non-empty history whose first text is not
     * a user turn, or a history that ends on a lone user message (the last one is the shape cut ① leaves when the
     * only surviving turn is an unanswered user half).
     *
     * ## A `RETRY` is folded into the request `input`, never a history turn
     *
     * A retry instruction is the loop talking to the model, not the wearer speaking. As a history turn it would open
     * a **new** group ending on a bare user text, which `ContextBudgeter` rejects by design (`groups.last().size == 1`)
     * — the loop's designed retry would be refused before it was ever sent. It therefore rides **ahead of the request
     * `input`**, in flow order, exactly as a summary and the memory ride ahead of the history's first user turn: the
     * surviving transcript's last group stays closed, and the loop's nudge is the first thing the model reads in the
     * message it is answering. The frame's one `USER_INPUT` is still the request `input` — it is not replaced.
     *
     * Folding several context segments into one message removes one 32-byte per-message overhead per folded segment and
     * adds 2 bytes per join, so it is cheaper whenever more than one segment is folded. With a **single** context
     * segment it is 2 bytes **more** expensive than the trimmer's measurement of that one segment (no overhead is
     * removed), which the gateway's deliberate 32-byte window margin absorbs; the trimmer's verdict therefore stays
     * the conservative one either way and no fold can push a request over the limit.
     */
    private fun frameToTranscript(frame: RequestFrame): Transcript? {
        val flow = frame.conversationFlow
        val inputIndex = flow.indexOfLast { it.origin == SegmentOrigin.USER_INPUT }
        if (inputIndex < 0) return null
        val inputText = flow[inputIndex].text
        // Loop-internal continuations are not conversation turns; see the KDoc above.
        val retryText = flow.take(inputIndex)
            .filter { it.origin == SegmentOrigin.RETRY }
            .joinToString("\n\n") { it.text }
        val requestInput = if (retryText.isEmpty()) inputText else "$retryText\n\n$inputText"
        val leading = mutableListOf<String>()
        val history = mutableListOf<ModelHistoryItem>()
        for (segment in flow.take(inputIndex)) {
            when (segment.origin) {
                SegmentOrigin.SUMMARY -> leading += boundedSummary(segment.text)
                SegmentOrigin.MEMORY_EVIDENCE, SegmentOrigin.PERCEPTION -> leading += segment.text
                SegmentOrigin.RETRY -> Unit
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
        val transcript = when {
            leading.isEmpty() ->
                if (history.isEmpty() || opensWithUser(history)) {
                    Transcript(listOf(InputPart.Text(requestInput)), history)
                } else {
                    null
                }
            else -> {
                val context = leading.joinToString("\n\n")
                val head = history.firstOrNull()
                when {
                    head is ModelHistoryItem.Text && head.role == MessageRole.USER -> {
                        history[0] = ModelHistoryItem.Text(MessageRole.USER, "$context\n\n${head.text}")
                        Transcript(listOf(InputPart.Text(requestInput)), history)
                    }
                    history.isEmpty() -> Transcript(listOf(InputPart.Text("$context\n\n$requestInput")), history)
                    else -> null
                }
            }
        } ?: return null
        // A history whose last group is a single user message is refused exactly like the shapes above: it is what cut
        // ① leaves when the only surviving turn is an unanswered user half, and `ContextBudgeter` rejects it **by
        // design** (`groups.last().size == 1` — whole-group eviction depends on it). Handing it to the transport would
        // make every later turn fail with `ConfigurationRejected` until a restart (the 2026-10-14 incident).
        if (history.isNotEmpty() && !lastGroupIsClosed(history)) return null
        return transcript
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
