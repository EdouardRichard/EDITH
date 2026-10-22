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
 * The session/generation an inbound resident-handshake event was validated against.
 *
 * The link checks `PIN`/`PANEL_SHOWN`/`UI_*` against the identity it established; carrying that pair to the host lets
 * the host compare it with its own current identity when a queued event is finally delivered. Stamping such an event
 * with whatever identity happens to be current at delivery time would let a pin from the previous session change the
 * new conversation's state.
 */
data class HintIdentity(val sessionId: String, val generation: Long)

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
     * 0.25 Task 11 (R51): the wearer's ring decision on a confirmation question.
     *
     * The event is an ENTER/BACK press while the phone had announced `CONFIRM_STATE`, so it is validated against the
     * same established session/generation as [Stop]/[Pin] **before** it is delivered: a key press queued from a
     * previous session must not answer the current question. The validated [HintIdentity] travels with the event so
     * the composition can re-check it at delivery time. Runs on the vendor callback thread; a throw is contained here.
     */
    var onConfirmationDecision: ((identity: HintIdentity, requestId: String, approved: Boolean) -> Unit)? = null

    /**
     * The wearer pinned the panel for [route] (0.24 spec §5.4).
     *
     * PIN is one-way and route-scoped; it is validated against the established session **and** generation before it
     * is delivered, so a pin from a previous session cannot change the current conversation's state. The validated
     * [HintIdentity] travels with the event: a host that has moved on refuses it at delivery time. Runs on the vendor
     * callback thread; a throw is contained here.
     */
    var onPin: ((identity: HintIdentity, route: AssistantRoute) -> Unit)? = null

    /**
     * The resident glasses panel reported its own state (0.24 spec §5.4).
     *
     * Validated like [onPin]: `UI_READY`/`UI_CLOSED` for another session or generation is not this conversation's
     * fact. `UI_READY` is deliberately not one-shot - the same identity may report it again after its service is
     * recreated - while `UI_CLOSED` names the route whose panel is now hidden. The validated identity travels with the
     * event so a queued close from a previous session cannot cancel the new session's turn window.
     */
    var onUiState: ((identity: HintIdentity, state: UiState) -> Unit)? = null

    /**
     * The glasses reported that the panel's first layout completed for [route] (0.24 spec §5.2).
     *
     * This is the event Task 12's 10 s listening window waits for: the timer must not start before the wearer can
     * actually see the panel. The validated [HintIdentity] travels with it so a queued event from a previous session
     * cannot start the new session's timer. Runs on the vendor callback thread; the composition marshals it to the
     * host thread and exposes it as a named seam rather than letting a vendor callback own timing policy.
     */
    var onPanelShown: ((identity: HintIdentity, route: AssistantRoute) -> Unit)? = null

    /**
     * The identifier of the mapping that is live **right now**, or null while nothing can be displayed.
     *
     * A fresh `syncId` is minted per exchange, so this is the identity of one mapping. A caller that must act once
     * per mapping - the App's activation retry, which receives both [onDisplayBecameLive] and [onMappingLive] for the
     * same exchange - can use it to tell "the same sync, reported twice" from "a later renewal".
     */
    fun liveSyncId(): String? = synchronized(handshakeGate) { clockSync.syncId() }

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
     * [entryId] is the display's stable identity for the utterance (0.24 spec §6: `asr-${turnId}` for a final whose
     * partials streamed). It is **passed through, never minted here**: the glasses replace on this id, so a second
     * identity would append a second row for the same words. A blank id or text is refused.
     *
     * Returns false when there is no session to say it to, or when the send was refused - the caller decides whether
     * that is worth recording, and the phone's own log keeps the words either way, so a broken link loses the display
     * of an utterance rather than the utterance.
     */
    fun userSaid(route: AssistantRoute, entryId: String, text: String): Boolean {
        if (entryId.isBlank() || text.isBlank()) return false
        val identity = synchronized(handshakeGate) {
            val session = establishedSession ?: run {
                diagnostics("conversation: no session yet - an utterance is not sent")
                return false
            }
            HintIdentity(session, establishedGeneration)
        }
        val command = HintCommand.Said(
            sessionId = identity.sessionId,
            generation = identity.generation,
            sequence = conversationSequence.incrementAndGet(),
            route = route,
            entryId = entryId,
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
        val identity = synchronized(handshakeGate) {
            val session = establishedSession ?: run {
                diagnostics("conversation: no session yet - a busy level is not sent")
                return false
            }
            HintIdentity(session, establishedGeneration)
        }
        val command = HintCommand.Busy(identity.sessionId, identity.generation, route, busy)
        return runCatching { outbound(HintWireCodec.encode(command)) }.getOrDefault(false)
    }

    /**
     * Sends one official conversation command for the established session (0.24 spec §5.4).
     *
     * Deliberately the same identity rule as [userSaid]/[busy]: with no session introduced to the glasses, a command
     * has nobody to be true for, so it is refused rather than queued for a session that may never exist. The
     * validated identity is captured under the handshake gate, so a session switch cannot stamp a command with half
     * of the old and half of the new identity. The return value is what this link can honestly say about its own
     * send; the phone's record was written before the call.
     */
    private fun sendConversation(what: String, build: (sessionId: String, generation: Long) -> HintCommand): Boolean {
        val identity = synchronized(handshakeGate) {
            val session = establishedSession ?: run {
                diagnostics("conversation: no session yet - $what is not sent")
                return false
            }
            HintIdentity(session, establishedGeneration)
        }
        return runCatching { outbound(HintWireCodec.encode(build(identity.sessionId, identity.generation))) }
            .getOrDefault(false)
    }

    /**
     * 0.25 Task 11 (R51): tells the glasses whether a confirmation question is up.
     *
     * Same identity rule as every other conversation command: sent only for the session this link introduced, and a
     * refusal (no session yet, link down) is the honest `false` rather than an invented identity. A repeat is
     * idempotent on the wire - [HintCommand.ConfirmState] is a level.
     */
    fun confirmationState(requestId: String, active: Boolean): Boolean {
        if (requestId.isBlank()) return false
        return sendConversation("a confirmation state") { session, generation ->
            HintCommand.ConfirmState(session, generation, requestId, active)
        }
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

    override fun close(route: AssistantRoute): Boolean {
        // A close ends the panel's whole lifecycle: a pending open for this route and the bounded PANEL_SHOWN repeat
        // must not reopen the window the caller just asked to hide.
        synchronized(handshakeGate) {
            if (panelRoutePending == route) panelRoutePending = null
            if (panelOpenRoute == route) {
                cancelPanelOpenRetryLocked()
                panelOpenAwaitingShown = false
                panelOpenRoute = null
            }
        }
        return sendConversation("a panel close") { session, generation ->
            HintCommand.Close(session, generation, route)
        }
    }

    /**
     * Closes the panel for **every** route, using the wire's official ALL form (`CLOSE` with no route).
     *
     * 0.24 S5 Task 20: taking the glasses off is not a route-scoped event - the wearer does not tell us which route the
     * visible panel belonged to - so the ALL form is the honest command. Any queued or already-sent-but-unshown
     * `PANEL_OPEN` is dropped for both routes before the close leaves, for the same reason [close] does it for one: a
     * bounded repeat must not reopen a window the wearer just took the glasses off for. A failure to send is reported
     * as the boolean this link can honestly answer.
     */
    fun closeAll(): Boolean {
        synchronized(handshakeGate) {
            panelRoutePending = null
            cancelPanelOpenRetryLocked()
            panelOpenAwaitingShown = false
            panelOpenRoute = null
        }
        return sendConversation("a panel close (ALL)") { session, generation ->
            HintCommand.Close(session, generation, null)
        }
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
     * The resident handshake's 5 s grace (0.24 spec §5.5).
     *
     * The glasses scene may or may not be started by the SDK's own connect flow (`glassesApkPath=null`), so the phone
     * waits for `UI_READY` after establishing the session and only then asks CXR to start the app. A timer per session,
     * cancelled by the first matching `UI_READY`, by [sessionEnded] or by [linkLost].
     */
    private var uiReadyGrace: CancellationHandle? = null

    /**
     * True once the resident service answered `UI_READY` for [establishedSession].
     *
     * Deliberately informational: it is **not** the readiness test for opening a panel or for deciding that a wake
     * needs no `appStart`. Only a live clock mapping (`PONG`) proves that the service is still there; a sticky
     * `UI_READY` can outlive the service that sent it.
     */
    private var uiReadyForSession = false

    /** True only after a matching `PANEL_SHOWN` and false again after `UI_CLOSED`/session end. */
    private var panelVisible = false

    /**
     * When [panelVisible] was last established by a matching `PANEL_SHOWN` (monotonic nanos).
     *
     * Visibility alone is not proof that the service that reported it is gone: `PANEL_SHOWN` is sent as soon as the
     * window has laid out, and the first `PONG` that produces a clock mapping can arrive later. The flag is therefore
     * only ever treated as stale after [PANEL_VISIBLE_GRACE_MILLIS]; the timestamp is what bounds that grace. Zero
     * while hidden or after a session/visibility reset.
     */
    private var panelVisibleSinceNanos = 0L

    /**
     * The route a wake asked to open while the resident service could not receive a `PANEL_OPEN` yet.
     *
     * Kept until a valid `UI_READY` or a live PONG mapping proves the service is there. The spec's order is
     * `appStart -> ... -> UI_READY -> PANEL_OPEN`; sending the open in the same burst as `HELLO`/`PING` was measured
     * on 2026-10-09 to be lost for ~3.45 s while the glasses subscribed, so the command must wait for proof.
     */
    private var panelRoutePending: AssistantRoute? = null

    /** The single `PANEL_OPEN` that has been sent and is waiting for `PANEL_SHOWN`. */
    private var panelOpenAwaitingShown = false
    private var panelOpenRoute: AssistantRoute? = null
    private var panelOpenRetries = 0
    private var panelOpenRetry: CancellationHandle? = null

    /**
     * The identity of the current open attempt, incremented by every `PANEL_OPEN` actually sent.
     *
     * The bounded repeat captures this outside the send section. A close + reopen of the **same route** keeps the
     * session, route and awaiting flag identical, so without an attempt token the captured retry would send a
     * duplicate into the new window and re-arm its repeat under the new open's state.
     */
    private var panelOpenEpoch = 0L

    /**
     * The single app-start in flight (0.24 spec §5.7).
     *
     * At most one `appStart` may be in flight per session: a wake arriving during the 5 s grace would otherwise start
     * the same scene twice. The object itself is the request token - a late SDK answer is compared by reference with
     * the current request, so a callback from a session that has already moved on cannot clear the new request's
     * state, drop its queued route, or send anything.
     */
    private var sceneStart: SceneStart? = null

    /** After an accepted app-start, a wake queues instead of starting the scene again until this moment. */
    private var sceneReadyDeadlineNanos = 0L

    /**
     * Serializes every resident-handshake state transition.
     *
     * The state is written from at least three threads: the vendor callback thread (`UI_READY`/`PONG`),
     * the deadline scheduler (the 5 s grace and the bounded open repeats) and the wake/backend callback. Without one
     * gate a check-then-act at the 5 s boundary can issue two `appStart`s, and a `UI_READY` written on one thread is
     * not guaranteed to be visible to the other.
     */
    private val handshakeGate = Any()

    /** One app-start request; its object identity is the token a late answer must still match. */
    private class SceneStart(val sessionId: String, val generation: Long, val answerDeadlineNanos: Long)

    /**
     * True only while a hint could actually be displayed: a live clock mapping exists, so the glasses would accept
     * the `SHOW`. Before the exchange completes, advertising a display would let a plugin start and then lose hints.
     */
    fun liveOutput(): HintDevice? = synchronized(handshakeGate) {
        if (clockSync.hasLiveMapping()) rokidDevice else null
    }

    /** Establishes the session on the glasses and starts the clock exchange. */
    fun sessionEstablished(sessionId: String, generation: Long) {
        synchronized(handshakeGate) {
            openingSession = sessionId
            openingGeneration = generation
            // Phase C: the conversation's own idea of "there is a session to talk to", which outlives the handshake.
            establishedSession = sessionId
            establishedGeneration = generation
            conversationSequence.set(0)
            openingRetries = 0
            cancelOpeningRetryLocked()
            // A new session may be a new glasses process: neither readiness nor a visible panel can be assumed, and
            // every request that belonged to the previous session is dropped. Dropping `sceneStart` also makes the
            // previous session's late appStart answer inert (the callback compares the request by reference).
            uiReadyForSession = false
            panelVisible = false
            panelVisibleSinceNanos = 0
            panelRoutePending = null
            panelOpenAwaitingShown = false
            panelOpenRoute = null
            panelOpenRetries = 0
            cancelPanelOpenRetryLocked()
            sceneStart = null
            sceneReadyDeadlineNanos = 0
            clockSync.sessionEstablished(sessionId, generation)
            scheduleOpeningRetryLocked(sessionId, generation)
            scheduleUiReadyGraceLocked(sessionId, generation)
        }
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
        synchronized(handshakeGate) {
            val session = establishedSession ?: run {
                diagnostics("handshake: reintroduce ignored - no established session")
                return
            }
            reintroduceLocked(session, establishedGeneration)
        }
    }

    /** Re-runs the opening exchange for [sessionId]/[generation] if that pair is still the established one. */
    private fun reintroduceLocked(sessionId: String, generation: Long) {
        if (establishedSession != sessionId || establishedGeneration != generation) return
        openingSession = sessionId
        openingGeneration = generation
        openingRetries = 0
        cancelOpeningRetryLocked()
        clockSync.sessionEstablished(sessionId, generation)
        // The new scene needs the same bounded opening repeat as a fresh session: the 2026-10-09 device measurement
        // showed the first HELLO/PING can be sent before the glasses has subscribed.
        scheduleOpeningRetryLocked(sessionId, generation)
    }

    /**
     * Asks the composition's transport to start the glasses scene (0.24 spec §5.7).
     *
     * Delegation only: deciding *when* the scene is needed belongs to the composition, which knows whether
     * `UI_READY`/`PONG` is missing. The injected seam keeps this link transport-free and testable.
     */
    fun openGlassesApp(onResult: (Boolean) -> Unit): Boolean = startGlassesApp(onResult)

    /**
     * Opens the conversation panel for [route] if it is not already visible (0.24 spec §5.5, §5.7).
     *
     * Readiness is a **live clock mapping**, not "UI_READY was seen once": the resident service can die while the CXR
     * session survives, and a sticky `UI_READY` would then suppress the only recovery path. The states are:
     * - **already visible with a live mapping**: no-op. A `PANEL_OPEN` for a visible panel must not reset the
     *   conversation;
     * - **visible without a live mapping, inside [PANEL_VISIBLE_GRACE_MILLIS]**: no-op. `PANEL_SHOWN` needs only the
     *   session identity, so a genuinely visible panel can legitimately precede the first `PONG`; treating that gap
     *   as a dead service would start a redundant scene and make the phone forget a panel the wearer can still see;
     * - **visible without a live mapping, after the grace**: the visible flag outlived the service that reported it.
     *   It is cleared and the no-mapping recovery below runs, so the next wake can start the scene again instead of
     *   reporting a phantom panel to Task 12. The glasses answer the resulting `PANEL_OPEN` for the still-visible
     *   route with an idempotent `PANEL_SHOWN` re-announce, which lets the phone re-correlate;
     * - **live mapping**: the service answered a recent `PING`, so only `PANEL_OPEN` travels; no `appStart`;
     * - **no live mapping**: ask CXR to start the scene (or queue while an earlier start is still in flight) and keep
     *   the route pending. `reintroduce()` runs when the start is accepted, and `PANEL_OPEN` is sent only after a
     *   valid `UI_READY` or a live PONG mapping proves the new scene can receive it. Sending it in the start burst
     *   would repeat the measured 2026-10-09 loss of the first `HELLO`/`PING` while the glasses subscribed;
     * - **a scene was started moments ago**: queue instead of starting it again while it comes up. The queue only
     *   outlives the `appStart` answer window and the accepted start's readiness window; once either expires without
     *   `UI_READY`/`PONG`, a wake starts the scene again rather than queueing forever.
     *
     * The app-start result is asynchronous: this returns whether the request was accepted, and the `PANEL_OPEN` is
     * sent only after readiness. A refused scene start leaves the panel closed rather than opening a window whose
     * `PANEL_SHOWN` the phone could never correlate.
     */
    fun requestPanelOpen(route: AssistantRoute): Boolean {
        val start: SceneStart?
        synchronized(handshakeGate) {
            val session = establishedSession ?: run {
                diagnostics("panel: no session - PANEL_OPEN is not sent")
                return false
            }
            val now = clock.nowNanos()
            // An appStart the SDK never answered (the backend deliberately drops answers once the binding closed) must
            // not reserve the handshake forever. The request token expires after the same window an accepted start
            // gets to prove readiness; a later wake can then start the scene again instead of queueing into a void.
            val inFlight = sceneStart
            if (inFlight != null && now >= inFlight.answerDeadlineNanos) {
                diagnostics("panel: appStart was never answered - the request expires")
                sceneStart = null
                panelRoutePending = null
                sceneReadyDeadlineNanos = 0
            }
            if (panelVisible) {
                // Visibility is only credible while a live mapping proves the service that reported it is still
                // there. A resident service that died while its panel was shown would otherwise block every later
                // wake and report a phantom panel to Task 12.
                if (clockSync.hasLiveMapping()) {
                    diagnostics("panel: already visible - PANEL_OPEN ignored route=${route.name}")
                    return false
                }
                val visibleForNanos = now - panelVisibleSinceNanos
                if (visibleForNanos < PANEL_VISIBLE_GRACE_MILLIS * 1_000_000L) {
                    // The panel may be genuinely visible before the first PONG: `PANEL_SHOWN` needs only the session
                    // identity, so the mapping gap is ordinary startup, not a dead service. Clearing it here would
                    // start a redundant scene and hide a panel the wearer can still see; after the grace the stale
                    // branch below still recovers a service that really died.
                    diagnostics("panel: visible without a mapping yet - within the ${PANEL_VISIBLE_GRACE_MILLIS}ms " +
                        "readiness grace route=${route.name}")
                    return false
                }
                diagnostics("panel: visible without a live mapping after the ${PANEL_VISIBLE_GRACE_MILLIS}ms grace " +
                    "- clearing the stale panel")
                panelVisible = false
                panelVisibleSinceNanos = 0
            }
            if (panelOpenAwaitingShown) {
                diagnostics("panel: a PANEL_OPEN is already awaiting PANEL_SHOWN - request ignored")
                return false
            }
            if (panelRoutePending != null) {
                val inFlightStart = sceneStart
                val startStillComing = inFlightStart != null && now < inFlightStart.answerDeadlineNanos
                val readyWindowStillOpen = inFlightStart == null && now < sceneReadyDeadlineNanos
                if (startStillComing || readyWindowStillOpen) {
                    // A later wake updates the route of the open that is already waiting; it cannot be sent sooner.
                    panelRoutePending = route
                    diagnostics("panel: an open is already waiting for readiness - route=${route.name}")
                    return true
                }
                // The start or its readiness window expired without UI_READY/PONG. The queued open belongs to a
                // recovery that failed, so this wake must start a fresh one rather than merely update the route.
                diagnostics("panel: the queued open's scene start never became ready - retrying recovery")
                panelRoutePending = null
                sceneReadyDeadlineNanos = 0
            }
            if (clockSync.hasLiveMapping()) {
                diagnostics("panel: live mapping - opening without appStart route=${route.name}")
                beginPanelOpenLocked(route, HintIdentity(session, establishedGeneration))
                return true
            }
            if (sceneStart != null) {
                panelRoutePending = route
                diagnostics("panel: an appStart is already in flight - PANEL_OPEN queued route=${route.name}")
                return true
            }
            if (now < sceneReadyDeadlineNanos) {
                panelRoutePending = route
                diagnostics("panel: a scene start was accepted recently - PANEL_OPEN queued route=${route.name}")
                return true
            }
            panelRoutePending = route
            // The grace exists to make this same attempt; a wake that gets there first supersedes it.
            cancelUiReadyGraceLocked()
            start = SceneStart(session, establishedGeneration,
                now + SCENE_READY_TIMEOUT_MILLIS * 1_000_000L)
            sceneStart = start
        }
        diagnostics("panel: starting the glasses scene for a missing service")
        val request = start ?: return false
        return startScene(request)
    }

    /** True once the resident service answered `UI_READY` for the current session. */
    fun hasUiReady(): Boolean = synchronized(handshakeGate) { uiReadyForSession }

    /** True after a matching `PANEL_SHOWN`, false after `UI_CLOSED` or a session end. Read-only Task 12 seam. */
    fun isPanelVisible(): Boolean = synchronized(handshakeGate) { panelVisible }

    /**
     * True while the glasses have been introduced to a session (0.3 Phase 2, Task 2's glasses-connected fact).
     *
     * This is the same lifetime as the conversation channel: set by [sessionEstablished], cleared by [sessionEnded]
     * and [linkLost]. It deliberately does **not** mean "the clock mapping is live or the panel is open" - those are
     * narrower states with their own accessors. A read-only seam; it adds nothing to the wire.
     */
    fun isEstablished(): Boolean = synchronized(handshakeGate) { establishedSession != null }

    /**
     * Sends the single `appStart` request for [start].
     *
     * The SDK answer is asynchronous and may be late. [onSceneStartResult] compares it with the current request
     * reference before touching any state, so a stale answer can neither clear a newer request nor drop its route.
     */
    private fun startScene(start: SceneStart): Boolean {
        val accepted = runCatching {
            startGlassesApp { started -> onSceneStartResult(start, started) }
        }.getOrDefault(false)
        if (!accepted) {
            synchronized(handshakeGate) {
                if (sceneStart === start) {
                    sceneStart = null
                    // A refused start leaves the panel closed: no window could ever produce the waiting PANEL_SHOWN.
                    panelRoutePending = null
                }
            }
            diagnostics("panel: no CXR client accepted appStart - panel stays hidden")
        }
        return accepted
    }

    /** Handles the SDK's answer for exactly [start]; a late answer from an older session is inert. */
    private fun onSceneStartResult(start: SceneStart, started: Boolean) {
        var introduce = false
        synchronized(handshakeGate) {
            if (sceneStart !== start) {
                diagnostics("panel: stale appStart answer ignored session=${start.sessionId}")
                return
            }
            sceneStart = null
            if (establishedSession != start.sessionId || establishedGeneration != start.generation) {
                diagnostics("panel: session changed while appStart was pending - not re-introduced")
                return
            }
            if (started) {
                // A wake (or the grace) has this long to queue onto a scene that is coming up; after it a later wake
                // may start the scene again rather than queue behind a start that never reported ready.
                sceneReadyDeadlineNanos = clock.nowNanos() + SCENE_READY_TIMEOUT_MILLIS * 1_000_000L
                // The queued route stays pending until UI_READY/PONG proves the new scene can receive PANEL_OPEN.
                introduce = !clockSync.hasLiveMapping()
            } else {
                panelRoutePending = null
            }
        }
        if (started) {
            diagnostics("panel: appStart accepted - re-introducing session=${start.sessionId}")
            if (introduce) {
                synchronized(handshakeGate) { reintroduceLocked(start.sessionId, start.generation) }
            }
        } else {
            diagnostics("panel: appStart was refused - panel stays hidden")
        }
    }

    /** Starts a fresh open (as opposed to repeating one): resets the bounded-repeat counter, then sends. */
    private fun beginPanelOpenLocked(route: AssistantRoute, identity: HintIdentity) {
        panelOpenRetries = 0
        sendPanelOpenLocked(route, identity)
    }

    /** Sends one `PANEL_OPEN` for [route] and arms its bounded repeat; caller holds [handshakeGate]. */
    private fun sendPanelOpenLocked(route: AssistantRoute, identity: HintIdentity) {
        if (panelVisible) return
        panelRoutePending = null
        panelOpenAwaitingShown = true
        panelOpenRoute = route
        // Every actual send is a new attempt. The captured retry below compares it so a close + reopen of the same
        // route cannot look like the attempt that retry was armed for.
        panelOpenEpoch++
        diagnostics("panel: sending PANEL_OPEN route=${route.name} session=${identity.sessionId}")
        val sent = runCatching {
            outbound(HintWireCodec.encode(HintCommand.PanelOpen(identity.sessionId, identity.generation, route)))
        }.getOrDefault(false)
        if (!sent) diagnostics("panel: PANEL_OPEN send refused route=${route.name}")
        armPanelOpenRetryLocked()
    }

    /**
     * Arms the single bounded repeat for a `PANEL_OPEN` that has not been followed by `PANEL_SHOWN`.
     *
     * The repeat is deliberately a repeat of `PANEL_OPEN`, not of `HELLO`/`PING`: once the service is known ready,
     * the only lost fact can be the open itself. The glasses treat an `PANEL_OPEN` for an already-visible panel as the
     * official no-reset no-op, but re-announce `PANEL_SHOWN` for the same route as an idempotent liveness event, so a
     * lost `PANEL_SHOWN` can be recovered without opening a second window.
     */
    private fun armPanelOpenRetryLocked() {
        panelOpenRetry?.cancel()
        panelOpenRetry = null
        if (!panelOpenAwaitingShown || panelVisible) return
        if (panelOpenRetries >= PANEL_OPEN_MAX_RETRIES) {
            diagnostics("panel: no PANEL_SHOWN after $PANEL_OPEN_MAX_RETRIES repeats - giving up")
            panelOpenAwaitingShown = false
            panelOpenRoute = null
            return
        }
        panelOpenRetries++
        val attempt = panelOpenRetries
        panelOpenRetry = scheduler.at(clock.nowNanos() + PANEL_OPEN_RETRY_DELAY_MILLIS * 1_000_000L) {
            var route: AssistantRoute? = null
            var identity: HintIdentity? = null
            var epoch = 0L
            synchronized(handshakeGate) {
                panelOpenRetry = null
                if (!panelOpenAwaitingShown || panelVisible) return@at
                val session = establishedSession
                val commandRoute = panelOpenRoute
                if (session == null || commandRoute == null) {
                    panelOpenAwaitingShown = false
                    panelOpenRoute = null
                    return@at
                }
                route = commandRoute
                identity = HintIdentity(session, establishedGeneration)
                epoch = panelOpenEpoch
            }
            val resendRoute = route ?: return@at
            val resendIdentity = identity ?: return@at
            val resendEpoch = epoch
            diagnostics("panel: no PANEL_SHOWN yet - repeating PANEL_OPEN " +
                "(attempt $attempt/$PANEL_OPEN_MAX_RETRIES)")
            synchronized(handshakeGate) {
                if (panelOpenAwaitingShown && !panelVisible) {
                    // Re-check the identity, route and attempt inside the send section: a session change or a
                    // close+new open can land between the capture above and here. Sending the captured command then
                    // would put the old route/identity on the wire, overwrite the new open, and cancel its repeat.
                    // The attempt matters for a close+reopen of the *same* route, where session and route are both
                    // unchanged but the retry belongs to a window that no longer exists.
                    val sameSession = establishedSession == resendIdentity.sessionId &&
                        establishedGeneration == resendIdentity.generation
                    if (sameSession && panelOpenRoute == resendRoute && panelOpenEpoch == resendEpoch) {
                        sendPanelOpenLocked(resendRoute, resendIdentity)
                    } else {
                        diagnostics("panel: stale PANEL_OPEN retry dropped route=${resendRoute.name}")
                    }
                }
            }
        }
    }

    /** Cancels the bounded `PANEL_OPEN` repeat, if any. Idempotent; caller holds [handshakeGate]. */
    private fun cancelPanelOpenRetryLocked() {
        panelOpenRetry?.cancel()
        panelOpenRetry = null
    }

    /**
     * D15: the session ended without the link being torn down, so the opening handshake must not be repeated again.
     *
     * Separate from [linkLost] on purpose: this is the composition's `stop`, which keeps the link (and the clock
     * mapping it already published) exactly as it is today, and only ends the repeat. A stopped session must not keep
     * introducing itself to the glasses.
     */
    fun sessionEnded() {
        synchronized(handshakeGate) {
            // 0.23 P5 review: state both routes idle **before** the identity is cleared. The phone's own 停止 otherwise
            // left the glasses spinning until the local 60 s bound, and a STOP could no longer be sent for a dead
            // session.
            val session = establishedSession
            if (session != null) {
                AssistantRoute.entries.forEach { route -> runCatching { busy(route, false) } }
                // 0.24 S2 Task 11: 停止 also closes the panel. There is deliberately no new `SESSION_END` command -
                // `CLOSE` already means "hide this window and drop its cache", and the resident service keeps its
                // session identity only until this link clears it below. A hidden panel clears on the same command.
                AssistantRoute.entries.forEach { route -> runCatching { close(route) } }
            }
            openingSession = null
            // Phase C: a stopped session must not keep accepting conversation traffic - the glasses will refuse it,
            // and sending it would put the wearer's words on a wire for a session that is over.
            establishedSession = null
            cancelOpeningRetryLocked()
            cancelUiReadyGraceLocked()
            uiReadyForSession = false
            panelVisible = false
            panelVisibleSinceNanos = 0
            panelRoutePending = null
            panelOpenAwaitingShown = false
            panelOpenRoute = null
            panelOpenRetries = 0
            cancelPanelOpenRetryLocked()
            // Dropping the request makes its late appStart answer inert (the callback compares by reference).
            sceneStart = null
            sceneReadyDeadlineNanos = 0
        }
    }

    /** Cancels a scheduled repeat, if any. Idempotent; caller holds [handshakeGate]. */
    private fun cancelOpeningRetryLocked() {
        openingRetry?.cancel()
        openingRetry = null
    }

    /**
     * Arms the one-shot 5 s `UI_READY` grace for [sessionId]/[generation].
     *
     * The check is repeated when the timer fires - the service can answer, or the session can end, between scheduling
     * and the deadline - and the identity is captured so a timer from the previous session can never start the scene
     * for the current one. If the CXR call itself is refused there is no scene to re-introduce, so the case is
     * recorded rather than retried into the void.
     */
    private fun scheduleUiReadyGraceLocked(sessionId: String, generation: Long) {
        cancelUiReadyGraceLocked()
        uiReadyGrace = scheduler.at(clock.nowNanos() + UI_READY_GRACE_MILLIS * 1_000_000L) {
            var start: SceneStart? = null
            synchronized(handshakeGate) {
                uiReadyGrace = null
                if (establishedSession != sessionId || establishedGeneration != generation) return@at
                if (uiReadyForSession) return@at
                if (sceneStart != null) {
                    diagnostics("handshake: no UI_READY yet, but an appStart is already in flight - not repeating it")
                    return@at
                }
                // A live mapping is proof the resident service is there; the grace exists only for a missing service.
                if (clockSync.hasLiveMapping()) return@at
                diagnostics("handshake: no UI_READY within ${UI_READY_GRACE_MILLIS}ms - starting the glasses scene")
                start = SceneStart(sessionId, generation,
                    clock.nowNanos() + SCENE_READY_TIMEOUT_MILLIS * 1_000_000L)
                sceneStart = start
            }
            val request = start ?: return@at
            startScene(request)
        }
    }

    /** Cancels the scheduled `UI_READY` grace, if any. Idempotent; caller holds [handshakeGate]. */
    private fun cancelUiReadyGraceLocked() {
        uiReadyGrace?.cancel()
        uiReadyGrace = null
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
    private fun scheduleOpeningRetryLocked(session: String, generation: Long) {
        cancelOpeningRetryLocked()
        openingRetry = scheduler.at(clock.nowNanos() + retryDelayMillis * 1_000_000) {
            synchronized(handshakeGate) {
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
                clockSync.sessionEstablished(session, generation)
                scheduleOpeningRetryLocked(session, generation)
            }
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
            var live = false
            var transition = false
            synchronized(handshakeGate) {
                val handled = clockSync.onCommand(command)
                if (!handled) return false
                live = clockSync.hasLiveMapping()
                if (live) {
                    // D15: an answered exchange confirms the opening handshake, so nothing was lost and nothing may
                    // be repeated. A live mapping is also the readiness proof a queued panel open was waiting for.
                    openingSession = null
                    cancelOpeningRetryLocked()
                    sceneReadyDeadlineNanos = 0
                    val pending = panelRoutePending
                    val session = establishedSession
                    if (pending != null && session != null && !panelOpenAwaitingShown && !panelVisible) {
                        beginPanelOpenLocked(pending, HintIdentity(session, establishedGeneration))
                    }
                }
                transition = live && !displayWasLive
                if (transition) diagnostics("display became live")
                displayWasLive = live
            }
            if (transition) onDisplayBecameLive?.invoke()
            // Deliberately after the transition callback, and fired even when the transition just fired: this signal is
            // "a mapping is live", not "the display changed". The consumer de-duplicates, so the double report for one
            // exchange costs nothing - while missing it for a renewal in place is the D10 defect.
            if (live) onMappingLive?.invoke()
            return true
        }
        if (command is HintCommand.Stop) {
            synchronized(handshakeGate) {
                if (!isEstablishedLocked(command.sessionId, command.generation, "stop")) return false
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
            val identity = HintIdentity(command.sessionId, command.generation)
            var callback: ((HintIdentity, AssistantRoute) -> Unit)? = null
            synchronized(handshakeGate) {
                if (!isEstablishedLocked(command.sessionId, command.generation, "pin")) return false
                callback = onPin
            }
            diagnostics("pin accepted: session=${command.sessionId} route=${command.route}")
            deliver("pin") { callback?.invoke(identity, command.route) }
            return true
        }
        if (command is HintCommand.UiReady) {
            val identity = HintIdentity(command.sessionId, command.generation)
            var callback: ((HintIdentity, UiState) -> Unit)? = null
            synchronized(handshakeGate) {
                if (!isEstablishedLocked(command.sessionId, command.generation, "UI_READY")) return false
                uiReadyForSession = true
                sceneReadyDeadlineNanos = 0
                cancelUiReadyGraceLocked()
                diagnostics("UI_READY accepted: session=${command.sessionId} generation=${command.generation}")
                // The spec's order: appStart -> ... -> UI_READY -> PANEL_OPEN. A wake that asked for a panel while
                // the scene was starting is opened exactly here, once.
                val pending = panelRoutePending
                if (pending != null && !panelOpenAwaitingShown && !panelVisible) {
                    beginPanelOpenLocked(pending, identity)
                }
                callback = onUiState
            }
            deliver("UI_READY") { callback?.invoke(identity, UiState.Ready) }
            return true
        }
        if (command is HintCommand.UiClosed) {
            val identity = HintIdentity(command.sessionId, command.generation)
            var callback: ((HintIdentity, UiState) -> Unit)? = null
            synchronized(handshakeGate) {
                if (!isEstablishedLocked(command.sessionId, command.generation, "UI_CLOSED")) return false
                // The resident service is still alive; only the panel window went away. The hidden window also ends
                // any pending repeat: a panel that was shown and then closed must not be reopened by a retry.
                panelVisible = false
                panelVisibleSinceNanos = 0
                cancelPanelOpenRetryLocked()
                panelOpenAwaitingShown = false
                panelOpenRoute = null
                diagnostics("UI_CLOSED accepted: session=${command.sessionId} route=${command.route}")
                callback = onUiState
            }
            deliver("UI_CLOSED") { callback?.invoke(identity, UiState.Closed(command.route)) }
            return true
        }
        if (command is HintCommand.PanelShown) {
            val identity = HintIdentity(command.sessionId, command.generation)
            var callback: ((HintIdentity, AssistantRoute) -> Unit)? = null
            synchronized(handshakeGate) {
                if (!isEstablishedLocked(command.sessionId, command.generation, "PANEL_SHOWN")) return false
                panelVisible = true
                // The grace for "visible but no mapping yet" is anchored here: this is the moment the wearer can see
                // the window, and the first PONG may still be in flight.
                panelVisibleSinceNanos = clock.nowNanos()
                panelRoutePending = null
                panelOpenAwaitingShown = false
                panelOpenRoute = null
                panelOpenRetries = 0
                cancelPanelOpenRetryLocked()
                diagnostics("PANEL_SHOWN accepted: session=${command.sessionId} route=${command.route}")
                callback = onPanelShown
            }
            deliver("PANEL_SHOWN") { callback?.invoke(identity, command.route) }
            return true
        }
        if (command is HintCommand.ConfirmDecision) {
            val identity = HintIdentity(command.sessionId, command.generation)
            var callback: ((HintIdentity, String, Boolean) -> Unit)? = null
            synchronized(handshakeGate) {
                if (!isEstablishedLocked(command.sessionId, command.generation, "CONFIRM_DECISION")) return false
                callback = onConfirmationDecision
            }
            // The request id is the plugin's own UUID (a question address), not command text; the decision is a
            // boolean. Neither can carry user content.
            diagnostics("confirm decision accepted: session=${command.sessionId} " +
                "request=${command.requestId} approved=${command.approved}")
            deliver("CONFIRM_DECISION") { callback?.invoke(identity, command.requestId, command.approved) }
            return true
        }
        return rokidDevice.onCommand(json)
    }

    /**
     * True only for the session/generation this link introduced to the glasses; logs the refusal, because a stale
     * identity arriving here is otherwise indistinguishable from a lost message. Caller holds [handshakeGate].
     */
    private fun isEstablishedLocked(sessionId: String, generation: Long, what: String): Boolean {
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

    /** The link is gone: the mapping is dropped so no stale interval can be used for a fresh deadline. */
    fun linkLost() {
        diagnostics("link lost: session, clock mapping and panel state cleared")
        sessionEnded()
        synchronized(handshakeGate) {
            clockSync.linkLost()
            displayWasLive = false
        }
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

        /**
         * 0.24 S2 spec §5.5: how long the phone waits for `UI_READY` after establishing a session before it asks CXR
         * to start the glasses scene. Five seconds is the S2 initial value; it is a grace for a scene the SDK may
         * already be starting, not a retry period.
         */
        const val UI_READY_GRACE_MILLIS = 5_000L

        /**
         * How long an accepted `appStart` gets to report readiness before another wake may start the scene again.
         *
         * A wake in this window queues its panel instead of issuing a second `appStart`; if the scene never reports
         * ready, the next wake after the window is allowed to retry the recovery. Ten seconds covers a cold glasses
         * process start without suppressing recovery forever.
         */
        const val SCENE_READY_TIMEOUT_MILLIS = 10_000L

        /**
         * How long a `PANEL_SHOWN` may stand without a live clock mapping before a wake treats it as stale.
         *
         * `PANEL_SHOWN` is emitted on the window's first layout and needs only the session identity, so it can
         * legitimately arrive before the first `PONG` completes the exchange; the panel is genuinely visible during
         * that gap. Five seconds is the same S2 initial grace as [UI_READY_GRACE_MILLIS] and covers the measured
         * 3.45 s glasses subscription delay (see [OPENING_RETRY_DELAY_MILLIS]) with room for a slow first exchange.
         * A service that really died is still recovered: the next wake after the grace clears the phantom visibility
         * and starts the scene. Nothing is cleared or started on a wake inside the grace.
         */
        const val PANEL_VISIBLE_GRACE_MILLIS = 5_000L

        /**
         * How long a sent `PANEL_OPEN` waits for `PANEL_SHOWN` before it is repeated.
         *
         * One second is far longer than a live round trip and short enough that a single lost command does not cost
         * the wearer the whole opening. The repeats are bounded by [PANEL_OPEN_MAX_RETRIES].
         */
        const val PANEL_OPEN_RETRY_DELAY_MILLIS = 1_000L

        /**
         * The bound on `PANEL_OPEN` repeats after the first send (three repeats = four sends in ~3 s). Task 12 owns
         * the separate 3 s "panel not shown" policy; this bound only stops this link repeating into a dead scene.
         */
        const val PANEL_OPEN_MAX_RETRIES = 3
    }
}
