package com.sheen.rokid.phone.bridge

import com.sheen.rokid.output.HintClockSync
import com.sheen.rokid.output.HintCommand
import com.sheen.rokid.output.HintExchange
import com.sheen.rokid.output.HintWireCodec
import com.sheen.rokid.output.RokidHintDevice
import com.sheen.rokid.contracts.AssistantRoute
import com.sheen.rokid.contracts.ToolPhase
import com.sheen.rokid.plugin.CancellationHandle
import com.sheen.rokid.plugin.DeadlineScheduler
import com.sheen.rokid.plugin.HintDevice
import com.sheen.rokid.plugin.MonotonicClock
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/**
 * What the glasses' resident panel reported about its UI (0.24 spec §5.4).
 *
 * `UI_READY` is not route-scoped: it says the service is alive and can receive commands for the established session,
 * which is what lets the phone distinguish "the scene needs starting" from "the panel is merely hidden". `UI_CLOSED`
 * names the route whose panel went away and says nothing about the service's life.
 */
sealed interface UiState {
    /** The panel service is ready for the established session. */
    data object Ready : UiState

    /** The panel for [route] is hidden; the resident service is still alive. */
    data class Closed(val route: AssistantRoute) : UiState
}

/**
 * The App-side hint link: the object that owns the display path for a live CXR connection.
 *
 * This is T18's composition, and it answers the four points that had to be agreed before wiring:
 *
 * 1. **Ownership** - one [RokidHintDevice] is owned here, for the life of the composition. The connection is built
 *    lazily, so the [sendJson] seam reads the live connection through the composition's holder instead of capturing a
 *    connection that does not exist yet.
 * 2. **`syncId`** - supplied by [HintClockSync] from the mapping the `PING`/`PONG` exchange produced. While no
 *    mapping is live there is nothing to stamp a hint with, and the display is not advertised at all (see
 *    [liveOutput]) - so a hint can never travel with an identifier the glasses have no mapping for.
 * 3. **Scheduler** - the composition's own deadline scheduler drives the mapping renewal; nothing here creates a
 *    thread.
 * 4. **Inbound acknowledgements** - routed in from the connection's reply channel: a `PONG` feeds the clock
 *    handshake, everything else is offered to the device, which correlates acks by sequence and drops what it does
 *    not understand rather than throwing on the SDK's callback thread.
 *
 * Transport-free on purpose: it takes a `sendJson` function, so the whole path is provable in unit tests without a
 * device. The device run that would confirm the glasses' side of it is deliberately deferred.
 */
class CxrHintLink(
    private val clock: MonotonicClock,
    private val scheduler: DeadlineScheduler,
    private val sendJson: (String) -> Boolean,
    /**
     * Where the handshake's few transitions go; wired to the log by the composition.
     *
     * Deliberately narrow: only the states that would otherwise fail silently (a PING nobody answered, a PONG that was
     * not ours, an inbound message nothing understood, the display becoming real). Without them a broken handshake and
     * a broken transport look identical on a device.
     */
    private val diagnostics: (String) -> Unit = {},
    /**
     * D15: how long an unanswered opening handshake waits before it is repeated.
     *
     * The glasses app answers the opening `HELLO`/`PING` only once it has subscribed to the command channel; measured
     * on 2026-10-09 that took 3.45 s after `onOpenAppResult:true`, so the first attempt was sent into the void and
     * that whole session had no display and no audio. Repeats are cheap (two small JSON commands) and bounded.
     */
    private val retryDelayMillis: Long = OPENING_RETRY_DELAY_MILLIS,
    /** D15: the bound on repeats; zero disables them. */
    private val maxRetries: Int = OPENING_MAX_RETRIES,
    /**
     * Where an app-start request goes (0.24 spec §5.7).
     *
     * The link already owns the display path but not the transport, and it must not grow a second connection seam:
     * the composition injects the transport's `openGlassesApp` here. The default refuses, exactly like a composition
     * with no CXR client, so an old test link cannot look like it started the glasses scene.
     */
    private val startGlassesApp: (onResult: (Boolean) -> Unit) -> Boolean = { false },
) : ConversationWire, AutoCloseable {
    init {
        require(retryDelayMillis > 0) { "RETRY_DELAY_OUT_OF_RANGE" }
        require(maxRetries >= 0) { "RETRY_BOUND_OUT_OF_RANGE" }
    }

    /**
     * Every outbound command goes through here.
     *
     * Logged because "the wearer saw nothing" cannot be attributed between "the phone never sent a usable `SHOW`" and
     * "the glasses refused it" without both halves: the glasses report their refusals, this reports what left the phone.
     */
    private val outbound: (String) -> Boolean = { json ->
        diagnostics("outbound: ${HintWireCodec.decode(json)?.let(::describe) ?: "undecodable (${json.length} chars)"}")
        sendJson(json)
    }

    private val clockSync = HintClockSync(clock, scheduler,
        send = { command -> outbound(HintWireCodec.encode(command)) },
        diagnostics = diagnostics)
    private val rokidDevice = RokidHintDevice(clock, outbound,
        // Wire-safe placeholder only: the display is gated on a live mapping below, so this must not be able to
        // travel in a correctly composed App. The glasses refuse an unmapped syncId anyway, which is the point of
        // naming the absence instead of inventing an identifier that looks like a mapping.
        syncId = { clockSync.syncId() ?: HintExchange.UNSYNCED_SYNC_ID })

    /** The device the host shows through; the same instance for the life of the link. */
    val device: HintDevice get() = rokidDevice

    /**
     * Called once each time the display becomes real (not live -> live).
     *
     * Activation is attempted when the scene becomes ready, which is **before** the first clock mapping exists, so a
     * plugin whose only missing capability is the display is refused at that moment. Without this signal nothing would
     * ever try again and a display that has just become usable would stay unused. Renewals replace a live mapping with
     * another live mapping and therefore do **not** fire it - see [onMappingLive] for those.
     */
    var onDisplayBecameLive: (() -> Unit)? = null

    /**
     * Called on **every** successful exchange that leaves a mapping live - the first one and each renewal in place.
     *
     * Additive rather than a replacement for [onDisplayBecameLive]: that one is a transition, and the link's steady
     * state is "live and re-syncing", which produces no transition at all. A renewal replaces a live mapping with a
     * fresh one every ~20 s, and `sessionEstablished` re-arms the exchange for a new session while the display never
     * goes not-live. Measured on the K40 on 2026-10-09, a session whose activation had been refused for the missing
     * display therefore saw `mapping live` every ~20 s and **never** a transition: the refusal was unretryable for
     * the whole session and nothing was ever displayed or listened to.
     *
     * Fired only for a mapping that is genuinely live: a `PONG` that publishes nothing (not this exchange, or an
     * unusable interval) is not a sync and fires nothing.
     */
    var onMappingLive: (() -> Unit)? = null

    /**
     * 0.23 P5 (spec §5.3.2): the wearer's double-click reached us as a `STOP` for the established session.
     *
     * The validated session and generation are passed through, not dropped: the composition re-checks the same pair
     * against the host's identity at the moment it delivers the event, so a stop/start racing this callback cannot
     * turn an old STOP into a cancellation of the new session.
     */
    var onStopRequest: ((sessionId: String, generation: Long) -> Unit)? = null

    /**
     * The wearer pinned the panel for [route] (0.24 spec §5.4).
     *
     * PIN is one-way and route-scoped; it is validated against the established session **and** generation before it
     * is delivered, so a pin from a previous session cannot change the current conversation's state. Runs on the
     * vendor callback thread; a throw is contained here.
     */
    var onPin: ((route: AssistantRoute) -> Unit)? = null

    /**
     * The resident glasses panel reported its own state (0.24 spec §5.4).
     *
     * Validated like [onPin]: `UI_READY`/`UI_CLOSED` for another session or generation is not this conversation's
     * fact. `UI_READY` is deliberately not one-shot - the same identity may report it again after its service is
     * recreated - while `UI_CLOSED` names the route whose panel is now hidden.
     */
    var onUiState: ((UiState) -> Unit)? = null

    /**
     * The identifier of the mapping that is live **right now**, or null while nothing can be displayed.
     *
     * A fresh `syncId` is minted per exchange, so this is the identity of one mapping. A caller that must act once
     * per mapping - the App's activation retry, which receives both [onDisplayBecameLive] and [onMappingLive] for the
     * same exchange - can use it to tell "the same sync, reported twice" from "a later renewal".
     */
    fun liveSyncId(): String? = clockSync.syncId()

    private var displayWasLive = false

    /**
     * The session the glasses have been introduced to, whether or not a clock mapping is live yet (Phase C).
     *
     * Deliberately **not** [openingSession]: that one is cleared the moment a mapping goes live, and it is cleared for
     * the opposite reason - it means "an unanswered handshake may still be repeated". Conversation traffic must keep
     * flowing exactly when the display is working, so it needs the session's own lifetime: set by
     * [sessionEstablished], cleared by [sessionEnded] and [linkLost].
     */
    private var establishedSession: String? = null

    /**
     * The number the next `SAID` carries (Phase C).
     *
     * The custom-command channel promises nothing about ordering, and an utterance that arrives late must not be
     * appended behind a later one. One counter per session, drawn from exactly where the `SHOW` sequence comes from
     * for hints: a number only has to be comparable with the ones on the same route.
     */
    private val conversationSequence = AtomicLong(0)

    /**
     * Reports one thing the wearer said, for the conversation named by [route].
     *
     * Returns false when there is no session to say it to, or when the send was refused - the caller decides whether
     * that is worth recording, and the phone's own log keeps the words either way, so a broken link loses the display
     * of an utterance rather than the utterance.
     */
    fun userSaid(route: AssistantRoute, text: String): Boolean {
        if (establishedSession == null) {
            diagnostics("conversation: no session yet - an utterance is not sent")
            return false
        }
        if (text.isBlank()) return false
        val command = HintCommand.Said(
            sessionId = establishedSession!!,
            generation = establishedGeneration,
            sequence = conversationSequence.incrementAndGet(),
            route = route,
            entryId = "said-${UUID.randomUUID()}",
            text = text,
        )
        return runCatching { outbound(HintWireCodec.encode(command)) }.getOrDefault(false)
    }

    /**
     * States whether the assistant is working on [route] (Phase C 加载动画).
     *
     * A level, not an event: `HintCommand.Busy` carries no sequence because two statements of the same level are the
     * same statement. Returns false when there is no session, or when the send was refused.
     */
    fun busy(route: AssistantRoute, busy: Boolean): Boolean {
        val session = establishedSession ?: run {
            diagnostics("conversation: no session yet - a busy level is not sent")
            return false
        }
        val command = HintCommand.Busy(session, establishedGeneration, route, busy)
        return runCatching { outbound(HintWireCodec.encode(command)) }.getOrDefault(false)
    }

    /**
     * Sends one official conversation command for the established session (0.24 spec §5.4).
     *
     * Deliberately the same identity rule as [userSaid]/[busy]: with no session introduced to the glasses, a command
     * has nobody to be true for, so it is refused rather than queued for a session that may never exist. The return
     * value is what this link can honestly say about its own send; the phone's record was written before the call.
     */
    private fun sendConversation(what: String, build: (sessionId: String, generation: Long) -> HintCommand): Boolean {
        val session = establishedSession
        if (session == null) {
            diagnostics("conversation: no session yet - $what is not sent")
            return false
        }
        return runCatching { outbound(HintWireCodec.encode(build(session, establishedGeneration))) }
            .getOrDefault(false)
    }

    override fun state(route: AssistantRoute, turnId: String, status: Int, sequence: Long): Boolean =
        sendConversation("a state") { session, generation ->
            HintCommand.State(session, generation, route, turnId, status, sequence)
        }

    override fun idle(route: AssistantRoute, sequence: Long): Boolean =
        sendConversation("an idle") { session, generation ->
            HintCommand.Idle(session, generation, route, sequence)
        }

    override fun asr(route: AssistantRoute, turnId: String, revision: Long, text: String): Boolean =
        sendConversation("an ASR revision") { session, generation ->
            HintCommand.Asr(session, generation, route, turnId, revision, text)
        }

    override fun answer(route: AssistantRoute, turnId: String, hintId: String, revision: Long, text: String): Boolean =
        sendConversation("an answer revision") { session, generation ->
            HintCommand.Answer(session, generation, route, turnId, hintId, revision, text)
        }

    override fun tool(route: AssistantRoute, turnId: String, phase: ToolPhase, label: String): Boolean =
        sendConversation("a tool row") { session, generation ->
            HintCommand.Tool(session, generation, route, turnId, phase, label)
        }

    override fun panelOpen(route: AssistantRoute): Boolean =
        sendConversation("a panel open") { session, generation ->
            HintCommand.PanelOpen(session, generation, route)
        }

    override fun close(route: AssistantRoute): Boolean =
        sendConversation("a panel close") { session, generation ->
            HintCommand.Close(session, generation, route)
        }

    /**
     * The generation the current session was introduced with (Phase C).
     *
     * The glasses gate every message on session **and** generation, so conversation traffic has to carry the same pair
     * the handshake carried; a second counter here would be a second identity.
     */
    private var establishedGeneration = 0L

    /**
     * D15: the session whose opening handshake is still unconfirmed, and how many times it has been repeated.
     *
     * Non-null only between [sessionEstablished] and the first live mapping (or [sessionEnded]/[linkLost]); it is the
     * identity a repeat must still belong to, so a repeat can never introduce a session that has already ended.
     */
    private var openingSession: String? = null
    private var openingGeneration = 0L
    private var openingRetries = 0
    private var openingRetry: CancellationHandle? = null

    /**
     * True only while a hint could actually be displayed: a live clock mapping exists, so the glasses would accept
     * the `SHOW`. Before the exchange completes, advertising a display would let a plugin start and then lose hints.
     */
    fun liveOutput(): HintDevice? = if (clockSync.hasLiveMapping()) rokidDevice else null

    /** Establishes the session on the glasses and starts the clock exchange. */
    fun sessionEstablished(sessionId: String, generation: Long) {
        openingSession = sessionId
        openingGeneration = generation
        // Phase C: the conversation's own idea of "there is a session to talk to", which outlives the handshake.
        establishedSession = sessionId
        establishedGeneration = generation
        conversationSequence.set(0)
        openingRetries = 0
        cancelOpeningRetry()
        clockSync.sessionEstablished(sessionId, generation)
        scheduleOpeningRetry()
    }

    /**
     * Introduces the **already established** session again, without changing the link's identity or conversation.
     *
     * This is the recovery path for a glasses scene that was just started: the SDK session never went away, but the
     * new glasses process has not heard the `HELLO`/`PING` the old one answered, so the clock mapping is dead. The
     * conversation sequence is deliberately kept - this is the same conversation, not a new one - while the clock
     * exchange restarts from the session identity the glasses already have.
     *
     * A no-op with no established session: there is nothing to reintroduce, and inventing an identity here would let a
     * stopped session look alive again.
     */
    fun reintroduce() {
        val session = establishedSession
        if (session == null) {
            diagnostics("handshake: reintroduce ignored - no established session")
            return
        }
        openingSession = session
        openingGeneration = establishedGeneration
        openingRetries = 0
        cancelOpeningRetry()
        clockSync.sessionEstablished(session, establishedGeneration)
        // The new scene needs the same bounded opening repeat as a fresh session: the 2026-10-09 device measurement
        // showed the first HELLO/PING can be sent before the glasses has subscribed.
        scheduleOpeningRetry()
    }

    /**
     * Asks the composition's transport to start the glasses scene (0.24 spec §5.7).
     *
     * Delegation only: deciding *when* the scene is needed belongs to the composition, which knows whether
     * `UI_READY`/`PONG` is missing. The injected seam keeps this link transport-free and testable.
     */
    fun openGlassesApp(onResult: (Boolean) -> Unit): Boolean = startGlassesApp(onResult)

    /**
     * D15: the session ended without the link being torn down, so the opening handshake must not be repeated again.
     *
     * Separate from [linkLost] on purpose: this is the composition's `stop`, which keeps the link (and the clock
     * mapping it already published) exactly as it is today, and only ends the repeat. A stopped session must not keep
     * introducing itself to the glasses.
     */
    fun sessionEnded() {
        // 0.23 P5 review: state both routes idle **before** the identity is cleared. The phone's own 停止 otherwise
        // left the glasses spinning until the local 60 s bound, and a STOP could no longer be sent for a dead session.
        val session = establishedSession
        if (session != null) {
            AssistantRoute.entries.forEach { route -> runCatching { busy(route, false) } }
        }
        openingSession = null
        // Phase C: a stopped session must not keep accepting conversation traffic - the glasses will refuse it, and
        // sending it would put the wearer's words on a wire for a session that is over.
        establishedSession = null
        cancelOpeningRetry()
    }

    /** Cancels a scheduled repeat, if any. Idempotent. */
    private fun cancelOpeningRetry() {
        openingRetry?.cancel()
        openingRetry = null
    }

    /**
     * Schedules one repeat of an opening handshake that has not been answered (D15).
     *
     * Unacknowledged by design: the glasses answer a `HELLO` only with the `SYNC` that follows their `PONG`, so "no
     * live mapping after the grace period" is the only signal that the first attempt cannot have been seen. Each
     * repeat is the same `HELLO`+`PING` on the same session - no new command and no new field - and stops at the
     * first of: a live mapping, the session ending, or the bound. A repeat of a session that has meanwhile ended can
     * therefore never introduce it.
     */
    private fun scheduleOpeningRetry() {
        val session = openingSession ?: return
        cancelOpeningRetry()
        openingRetry = scheduler.at(clock.nowNanos() + retryDelayMillis * 1_000_000) {
            if (openingSession != session) return@at
            if (clockSync.hasLiveMapping()) return@at
            if (openingRetries >= maxRetries) {
                diagnostics("handshake: opening handshake still unanswered after $maxRetries retries - giving up")
                openingRetry = null
                return@at
            }
            openingRetries++
            diagnostics("handshake: no mapping yet - repeating the opening handshake " +
                "(attempt $openingRetries/$maxRetries)")
            clockSync.sessionEstablished(session, openingGeneration)
            scheduleOpeningRetry()
        }
    }

    /** Handles one inbound message; returns true when it was understood. */
    fun onInbound(json: String): Boolean {
        val command = HintWireCodec.decode(json) ?: run {
            diagnostics("inbound: undecodable (${json.length} chars)")
            return false
        }
        diagnostics("inbound: ${command::class.simpleName}")
        if (command is HintCommand.Pong) {
            val handled = clockSync.onCommand(command)
            if (handled) notifyDisplayLiveness()
            return handled
        }
        if (command is HintCommand.Stop) {
            val session = establishedSession
            if (session == null || command.sessionId != session || command.generation != establishedGeneration) {
                diagnostics("stop refused: session=${command.sessionId} generation=${command.generation} " +
                    "established=${session ?: "none"}/$establishedGeneration")
                return false
            }
            // No ACK: STOP is an event. The callback is the emergency brake (UserAction.Stop -> plugin.stopAll), and
            // the plugin's own BUSY=false is what the glasses see as the effect. The callback reaches the host on this
            // SDK thread, so a throw must be contained here rather than escape into the vendor's callback.
            diagnostics("stop accepted: session=${command.sessionId} generation=${command.generation}")
            val callback = onStopRequest
            if (callback != null) {
                runCatching { callback(command.sessionId, command.generation) }
                    .onFailure { diagnostics("stop callback failed: ${it.javaClass.simpleName}") }
            }
            return true
        }
        if (command is HintCommand.Pin) {
            if (!isEstablished(command.sessionId, command.generation, "pin")) return false
            diagnostics("pin accepted: session=${command.sessionId} route=${command.route}")
            deliver("pin") { onPin?.invoke(command.route) }
            return true
        }
        if (command is HintCommand.UiReady) {
            if (!isEstablished(command.sessionId, command.generation, "UI_READY")) return false
            diagnostics("UI_READY accepted: session=${command.sessionId} generation=${command.generation}")
            deliver("UI_READY") { onUiState?.invoke(UiState.Ready) }
            return true
        }
        if (command is HintCommand.UiClosed) {
            if (!isEstablished(command.sessionId, command.generation, "UI_CLOSED")) return false
            diagnostics("UI_CLOSED accepted: session=${command.sessionId} route=${command.route}")
            deliver("UI_CLOSED") { onUiState?.invoke(UiState.Closed(command.route)) }
            return true
        }
        return rokidDevice.onCommand(json)
    }

    /**
     * True only for the session/generation this link introduced to the glasses; logs the refusal, because a stale
     * identity arriving here is otherwise indistinguishable from a lost message.
     */
    private fun isEstablished(sessionId: String, generation: Long, what: String): Boolean {
        val session = establishedSession
        if (session != null && sessionId == session && generation == establishedGeneration) return true
        diagnostics("$what refused: session=$sessionId generation=$generation " +
            "established=${session ?: "none"}/$establishedGeneration")
        return false
    }

    /** Runs one host callback on the vendor callback thread without letting a host failure take the SDK down. */
    private inline fun deliver(what: String, action: () -> Unit) {
        runCatching(action).onFailure { diagnostics("$what callback failed: ${it.javaClass.simpleName}") }
    }

    /** Fires [onDisplayBecameLive] only on a not-live -> live transition, and [onMappingLive] on every live mapping. */
    private fun notifyDisplayLiveness() {
        val live = clockSync.hasLiveMapping()
        if (live && !displayWasLive) diagnostics("display became live")
        // D15: an answered exchange confirms the opening handshake, so nothing was lost and nothing may be repeated.
        if (live) { openingSession = null; cancelOpeningRetry() }
        if (live && !displayWasLive) onDisplayBecameLive?.invoke()
        displayWasLive = live
        // Deliberately after the transition callback, and fired even when the transition just fired: this signal is
        // "a mapping is live", not "the display changed". The consumer de-duplicates, so the double report for one
        // exchange costs nothing - while missing it for a renewal in place is the D10 defect.
        if (live) onMappingLive?.invoke()
    }

    /** The link is gone: the mapping is dropped so no stale interval can be used for a fresh deadline. */
    fun linkLost() {
        sessionEnded()
        clockSync.linkLost()
        displayWasLive = false
    }

    override fun close() = linkLost()

    /** Enough of a command to tell a usable `SHOW` from an unusable one in a log. */
    private fun describe(command: HintCommand): String = when (command) {
        is HintCommand.Show -> "Show sequence=${command.sequence} syncId=${command.syncId} " +
            "ttl=${command.remainingTtlMillis}ms hintId=${command.hintId}"
        is HintCommand.Clear -> "Clear sequence=${command.sequence}"
        is HintCommand.Ack -> "Ack sequence=${command.sequence}"
        is HintCommand.Stop -> "Stop session=${command.sessionId} generation=${command.generation}"
        else -> command::class.simpleName ?: "unknown"
    }

    companion object {
        /**
         * D15: the wait before an unanswered opening handshake is repeated.
         *
         * One second is far longer than a live link's round trip (the exchange itself is measured in milliseconds),
         * and five of them cover the 3.45 s the glasses needed to subscribe on 2026-10-09 - while an answered
         * handshake cancels them at once, so a healthy link sends exactly one.
         */
        const val OPENING_RETRY_DELAY_MILLIS = 1_000L

        /** D15: the bound on repeats of the opening handshake, after which the link reports and stops. */
        const val OPENING_MAX_RETRIES = 5
    }
}
