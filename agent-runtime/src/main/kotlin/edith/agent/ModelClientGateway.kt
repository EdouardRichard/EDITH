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
) : ModelGateway {

    override fun turn(request: GatewayTurn): GatewayReply {
        val profile = profileLookup(request.profileId)
            ?: return GatewayReply(emptyList(), "", ModelEnd.FAILED, PROFILE_MISSING)
        val transcript = split(request.history)
            ?: return GatewayReply(emptyList(), "", ModelEnd.FAILED, NO_USER_TURN)
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
            instructions = request.instructions,
            input = transcript.input,
            history = transcript.history,
            context = emptyList(),
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
