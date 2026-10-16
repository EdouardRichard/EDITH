package edith.agent

import edith.contracts.EventEnvelope
import edith.plugin.CancellationHandle
import edith.plugin.DeadlineScheduler
import edith.plugin.InputPart
import edith.plugin.MessageRole
import edith.plugin.ModelClient
import edith.plugin.ModelEnd
import edith.plugin.ModelHistoryItem
import edith.plugin.ModelProfile
import edith.plugin.ModelRequest
import edith.plugin.ModelStreamEvent
import edith.plugin.MonotonicClock
import edith.plugin.OutputModality
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
     * compression is still owed. **Never any text**: it exists for tests and count-only probes (spec's "只记计数").
     * Null until the first frame-path turn, and never updated by the legacy `split(history)` path.
     */
    internal var lastTrim: TrimReport? = null
        private set

    companion object {
        /**
         * The byte budget a frame is trimmed to, in the same unit and against the same bound `ContextBudgeter`
         * enforces at send time (`minOf(inputBudgetTokens, usableWindow - maxOutput - safety)`).
         *
         * The `- [FIXED_OVERHEAD]` is **not optional**: [TurnTrimmer.total] already adds that fixed 32, and
         * `ContextBudgeter` adds its own 32 on top of the per-message accounting (`ContextBudgeter.kt:61`). Sharing
         * one limit would let the trimmer call a frame "fits" that the send-time check rejects by 32 bytes, turning a
         * turn that should have succeeded into `ConfigurationRejected` — the exact bug the acceptance test pins.
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
            // The window is the same expression `ContextBudgeter` bounds the send with, minus the fixed overhead the
            // trimmer already counts (see [windowBytes]). A caller-supplied `limitBytes` (test seam) wins.
            val limit = if (request.limitBytes == Int.MAX_VALUE) windowBytes(profile) else request.limitBytes
            val report = TurnTrimmer().trim(frame, limit)
            lastTrim = report
            frameToTranscript(report.kept)
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
            client.request(modelRequest) { event -> accumulated.accept(event, Thread.currentThread() === caller) }
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
        return GatewayReply(emptyList(), accumulated.text(), ModelEnd.CANCELLED, CANCELLED)
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
            // what the transport actually reported.
            val code = terminal.safeCode?.let { "$TOOL_CALL_DELIVERY_INCOMPLETE:$it" } ?: TOOL_CALL_DELIVERY_INCOMPLETE
            GatewayReply(emptyList(), text, ModelEnd.INCOMPLETE, code)
        } else {
            GatewayReply(calls, text, terminal.end, terminal.safeCode)
        }
    }

    /** The request `input` (empty for a continuation turn) and the transcript that becomes `ModelRequest.history`. */
    private class Transcript(val input: List<InputPart>, val history: List<ModelHistoryItem>)

    /**
     * Maps a (already trimmed) frame's conversation flow onto the request's `input`/`history` shape.
     *
     * The in-flight user turn is the frame's one `USER_INPUT` segment and becomes `input`; a conversation-flow
     * segment's `role` is metadata (`Segment.role`), never inferred from text, and a segment with no role
     * (theoretically only `PERCEPTION`/`MEMORY_EVIDENCE`) is rendered as the assistant's own message rather than
     * being promoted into the user's input. A `SUMMARY` is re-wrapped in [SummaryBoundary] here: the store keeps the
     * clean body ([ConversationStore.snapshot]), so the boundary is a render-time product and a summary would
     * otherwise reach the wire as an ordinary assistant utterance. Attribution is `origin == SUMMARY` — never a text
     * prefix — and an already-rendered body is passed through unchanged rather than wrapped twice.
     */
    private fun frameToTranscript(frame: RequestFrame): Transcript {
        val flow = frame.conversationFlow
        val inputIndex = flow.indexOfLast { it.origin == SegmentOrigin.USER_INPUT }
        val input = flow.drop(inputIndex).map { InputPart.Text(it.text) }
        val history = flow.take(inputIndex).map { segment ->
            when (segment.origin) {
                SegmentOrigin.SUMMARY -> {
                    val body = if (SummaryBoundary.isRendered(segment.text)) segment.text else SummaryBoundary.render(segment.text)
                    ModelHistoryItem.Text(MessageRole.ASSISTANT, body)
                }
                else -> ModelHistoryItem.Text(roleOf(segment), segment.text)
            }
        }
        return Transcript(input, history)
    }

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

        fun accept(event: ModelStreamEvent, onCallingThread: Boolean) {
            lock.withLock {
                // One verdict: everything after the terminal is a late delivery and is dropped.
                if (terminalEvent != null) return
                sawSynchronous = sawSynchronous || onCallingThread
                when (event) {
                    is ModelStreamEvent.TextDelta -> text.append(event.text)
                    is ModelStreamEvent.ToolCallDelta -> deltaItems += event.itemId
                    is ModelStreamEvent.ToolCallTerminal -> {
                        terminalItems += event.itemId
                        terminalCalls += event
                    }
                    is ModelStreamEvent.Terminal -> {
                        terminalEvent = event
                        ready.signalAll()
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
