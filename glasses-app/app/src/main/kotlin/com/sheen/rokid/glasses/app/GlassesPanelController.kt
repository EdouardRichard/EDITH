package com.sheen.rokid.glasses.app

import com.sheen.rokid.contracts.AssistantRoute
import com.sheen.rokid.glasses.bridge.GlassClockResponder
import com.sheen.rokid.glasses.display.BusyState
import com.sheen.rokid.glasses.display.ConversationOrder
import com.sheen.rokid.glasses.display.GlassesPanelSpec
import com.sheen.rokid.glasses.display.SaidSequenceGate
import com.sheen.rokid.output.ConversationEntry
import com.sheen.rokid.output.ConversationLog
import com.sheen.rokid.output.ConversationRole
import com.sheen.rokid.output.DisplayedHint
import com.sheen.rokid.output.GlassHintController
import com.sheen.rokid.output.HintCommand
import com.sheen.rokid.output.HintWireCodec
import com.sheen.rokid.output.OfficialStatus
import com.sheen.rokid.plugin.DeadlineScheduler
import com.sheen.rokid.plugin.MonotonicClock

/**
 * 0.24 S2 (Task 8): everything the official panel decides, separated from the Android view that draws it.
 *
 * The Activity used to keep the conversation cache, the selected route, the `SAID` ordering gate, the busy level and
 * the BACK/pin decisions beside its `View` code. This class is that half, moved without a behaviour change: it accepts
 * the same wire bytes through the same [GlassHintController] gates, keeps the same [ConversationLog] per route, and
 * publishes a [PanelModel] for the view to draw. The Activity now only forwards input and binds the model to views.
 *
 * Two rules keep the split honest:
 *
 * - **The model is a value, not a view instruction.** It carries the entries, which one is live, the four official
 *   state booleans, the pin and the tips copy. It deliberately contains no `View`, no `TextView` text assignment and no
 *   animator state; the port decides how to show it.
 * - **No Android imports.** The clock and the scheduler are the same injected ports the hint controller uses, so the
 *   whole state machine runs in a JVM test with a fake [PanelPort]; this is what lets a decision regress without the
 *   glasses on a desk.
 *
 * The official status/idle/tool model is not produced yet (Task 12/13/17): [PanelModel.status] is null,
 * [PanelModel.toolLabel] is null, and [PanelModel.idle] is the transitional "no busy level on either route" value the
 * view already used to decide whether the panel needs to stay awake.
 */
data class PanelModel(
    val route: AssistantRoute,
    val entries: List<ConversationEntry>,
    val liveEntryId: String?,
    /**
     * Monotonic "new content" counter, owned by the controller.
     *
     * Incremented on **every accepted SHOW** and **every stored SAID for the selected route**, even when the resulting
     * [ConversationEntry] replaces an equal one (same hint id/text with a newer revision, or a replacement utterance).
     * The view reveals on a change of this field rather than on entry inequality, because the pre-extraction Activity
     * revealed on every accepted update and the content can be equal while the event is still new.
     */
    val contentRevision: Long,
    /** The official status (0–7) once Task 12/13/17 supplies one; null means "no status model yet". */
    val status: OfficialStatus?,
    /** True while no route is working (the transitional form of official `showIdleStatus`). */
    val idle: Boolean,
    val pinned: Boolean,
    /** The ASR "…"/listener indicator row. Task 8 has no separate "…" row yet, so this mirrors [hiVisible]. */
    val listeningVisible: Boolean,
    /**
     * The `Hi, 我在听` text.
     *
     * Task 8 keeps the current Hi behaviour: it is the same listening signal the Activity used before. Task 13/17
     * replaces it with `OfficialConversation.hiVisible(status, firstOpen)` so a continuation turn does not say Hi
     * again; until then this field must render exactly what the old code rendered.
     */
    val hiVisible: Boolean,
    val thinkingVisible: Boolean,
    val toolLabel: String?,
    val tips: String,
)

/** What the controller needs from the window: show/hide it, draw one model, wake the panel, tell the phone. */
interface PanelPort {
    fun show(route: AssistantRoute)
    fun hide()
    fun render(model: PanelModel)
    fun wakeScreen()
    fun sendPanelShown()
    fun sendUiClosed()
}

/**
 * The panel's state, cache and decisions (0.24 S2 Task 8).
 *
 * The inbound path is unchanged from the Activity this was extracted from: a `HELLO` establishes the session (a
 * **new** session clears the window, a repeated one does not), every subsequent non-hint command passes the same
 * `GlassHintController` session/generation gates, `SAID` is numbered per route and dies behind the same ordering gate,
 * and `BUSY` is a bounded level. [onKey] owns the two decisions the panel makes from the ring: `ENTER` toggles the
 * reading pin, and `BACK` either sends `STOP` (busy with an established identity) or hides the panel.
 *
 * The Activity still runs every call on the UI thread; this class itself never touches a `View`.
 */
class GlassesPanelController(
    private val clock: MonotonicClock,
    private val scheduler: DeadlineScheduler,
    private val port: PanelPort,
    private val send: (HintCommand) -> Boolean,
    /** Where refusals and the BACK branch go; the Activity points it at `EdithGlasses` so the evidence lines survive. */
    private val diagnostics: (String) -> Unit = {},
) {
    /** What was said, per route. The same bounded log the Activity used; the phone renders the same shape. */
    private val log = ConversationLog()

    /** Which conversation is in front of the wearer, and the reading pin. */
    private val order = ConversationOrder()

    /** Rejects an out-of-order `SAID`; the custom-command channel promises nothing about ordering. */
    private val saidGate = SaidSequenceGate()

    /** Whether the assistant is working, per route; the level expires by itself inside the class. */
    private val busyState = BusyState()

    /**
     * The render/expiry gates for one hint.
     *
     * It stays the single authority for "may this SHOW be displayed" (session, generation, sequence, revision, clock
     * mapping, deadline). Its render callback lands in [onHintDisplayed], where the accepted hint becomes a
     * conversation entry; its ACK travels out through the same send the rest of the panel uses.
     */
    private val hints = GlassHintController(
        clock = clock,
        scheduler = scheduler,
        render = ::onHintDisplayed,
        ack = { ack -> send(ack) },
        diagnostics = diagnostics,
    )

    /** The handshake half: a `PING` must be answered immediately on the arrival path. */
    private val clockResponder = GlassClockResponder(clock) { json ->
        HintWireCodec.decode(json)?.let(send) == true
    }

    /** The entry id of the live hint; null means nothing is live now (the conversation itself is not erased). */
    private var liveEntryId: String? = null

    /**
     * How many content updates have been accepted; see [PanelModel.contentRevision].
     *
     * A SHOW or selected-route SAID that replaces an equal entry increments this even though [ConversationLog]
     * returns the same list, so the view can still tell "new content arrived" from "nothing changed".
     */
    private var contentRevision = 0L

    /** Whether a deadline is already armed for the current busy spell; a repeat of the level must not push it back. */
    private var busyDeadlineArmed = false

    /** False between [onStop] and [onStart]; mirror of the Activity's resumed flag for the retry/deadline decision. */
    private var running = false

    /**
     * Applies one inbound command. Returns whether it was understood, so the caller can record the ones that were not.
     *
     * Must be called on the UI thread (the Activity's `acceptCommand` is the production seam and posts there). The
     * decode happens here, in the controller, so the Activity no longer knows command names.
     */
    fun accept(json: String): Boolean {
        val command = HintWireCodec.decode(json) ?: run {
            // Recorded because this is the mixed-build safety net: an old glasses APK silently drops every command it
            // does not know, and without a line here "the wearer saw nothing" has no cause on this side either. The
            // length is a count, never the payload (the same rule as `HintPresentation.inboundReport`).
            diagnostics("inbound undecodable chars=${json.length}")
            return false
        }
        when (command) {
            // A PING is the one command the hint controller deliberately ignores: it is the phone's clock exchange,
            // and answering it is this side's obligation.
            is HintCommand.Ping -> {
                val answered = clockResponder.onPing(command)
                diagnostics("ping answered=$answered syncId=${command.syncId}")
            }

            // Phase C: what the wearer said. Filed under its own route, never switching the view and never
            // acknowledged (it has no deadline to confirm).
            is HintCommand.Said -> onSaid(command)

            // Phase C: whether the assistant is working. Same session gate as a SHOW.
            is HintCommand.Busy -> onBusy(command)

            // A **new** session starts clean. Only a new one: the phone repeats its opening HELLO while it waits for
            // the clock exchange, and clearing on every HELLO would wipe a conversation whose utterances had already
            // arrived.
            is HintCommand.Hello -> onHello(command)

            else -> hints.accept(command)
        }
        return true
    }

    /**
     * One key from the ring, in Android's raw `KeyEvent` numbers so this class stays Android-free.
     *
     * Returns whether the panel consumed the key. The Activity answers a cancelled BACK before delegating (a lost
     * focus mid-press is not a wearer decision) and keeps the page gestures for its scroll view; both are window glue,
     * not panel state.
     */
    fun onKey(keyCode: Int, action: Int): Boolean = when {
        // The official side-touch tap is the pin. The Activity's unhandled-key listener only forwards ACTION_DOWN,
        // exactly as it did when this branch lived there; the controller stays honest about that timing.
        keyCode == KEYCODE_ENTER && action == ACTION_DOWN -> {
            val pinned = order.togglePin()
            diagnostics("side touch: pinned=$pinned")
            applyAndRender()
            true
        }
        keyCode == KEYCODE_BACK && action == ACTION_UP -> onBack()
        else -> false
    }

    /** The Activity is visible again: re-apply the level and arm the local busy bound. */
    fun onStart() {
        running = true
        applyAndRender()
    }

    /** The Activity is no longer visible: stop arming the bound until it is back. */
    fun onStop() {
        running = false
    }

    /**
     * The screen is going away for good: drop the session identity and retire a visible hint, so no text is left
     * behind and a queued expiry cannot render into a dead Activity.
     *
     * This is the old `GlassHintController.disconnect()` the Activity called from `onDestroy`, kept as a seam here
     * because the hint controller is now private. The conversation cache itself is this object's own state and is
     * collected with it; only the already-committed view needs the retirement.
     */
    fun disconnect() {
        running = false
        hints.disconnect()
    }

    /**
     * The panel window closed (0.24 S2 Task 9): clear the **conversation cache** so nothing from the closed window can
     * reappear when the panel is opened again.
     *
     * This is deliberately not [disconnect]: the link to the phone survives a panel close, so the session identity is
     * kept and a later `SHOW` for the same session is still gated against it. Everything the next window must not
     * inherit is cleared: the per-route log, the selected route/pin, the SAID ordering gate, the busy level, the live
     * entry id and the reveal counter. The empty model is published so the view is cleared by the same path every
     * other state change uses.
     */
    fun resetForClose() = clearConversation()

    /**
     * The panel is being opened from a hidden window (Task 11 `PANEL_OPEN`): the same cache reset as a close.
     *
     * The controller accepts `SHOW`/`SAID` while the window is hidden, so waking the panel must not surface content
     * that arrived while the wearer could not see it. A `PANEL_OPEN` for an already-visible panel never reaches this
     * method - the window host treats that as a no-op, and no reset is allowed there.
     */
    fun resetForOpen() = clearConversation()

    /**
     * Clears everything the next window must not inherit: the per-route log, the selected route/pin, the `SAID`
     * ordering gate, the busy level, the live entry id and the reveal counter. The session identity is deliberately
     * kept by both callers, so a later `SHOW` for the same session is still gated against it.
     */
    private fun clearConversation() {
        // Keep the session identity (the link survives a window close), but forget the previous window's ordering and
        // revision gates so the phone may re-send the same hint id into the reopened window (Task 9 carry, Task 11).
        hints.resetForNewWindow()
        log.clear()
        order.reset()
        saidGate.reset()
        busyState.reset()
        liveEntryId = null
        contentRevision = 0L
        busyDeadlineArmed = false
        applyAndRender()
    }

    /**
     * The session and generation the next command must name, or null before a `HELLO`.
     *
     * The identity stays the hint controller's: a second copy here could disagree with the one the SHOW gate uses.
     */
    fun currentIdentity(): Pair<String, Long>? = hints.currentIdentity()

    /** Everything said in one conversation, one entry per line - the instrumentation probe's view of [ConversationLog]. */
    fun conversationText(route: AssistantRoute): String =
        log.entries(route).joinToString("\n") { it.text }

    // ---------------------------------------------------------------- inbound commands

    private fun onHello(command: HintCommand.Hello) {
        if (!hints.isCurrent(command.sessionId, command.generation)) {
            log.clear()
            order.reset()
            saidGate.reset()
            busyState.reset()
            liveEntryId = null
        }
        hints.accept(command)
        // The Activity rebuilds on every HELLO, so it stays here: a repeated HELLO is also a chance for the view to
        // re-read the log it already holds.
        applyAndRender()
    }

    /** Files one utterance the wearer made. Must run on the UI thread. */
    private fun onSaid(command: HintCommand.Said) {
        // The same gate a SHOW passes: a late utterance from the session before must not appear in this one.
        if (!hints.isCurrent(command.sessionId, command.generation)) {
            diagnostics("said refused: session or generation mismatch")
            return
        }
        if (!saidGate.accept(command.route, command.sequence)) {
            diagnostics("said refused: sequence ${command.sequence} is not newer")
            return
        }
        val stored = log.append(
            ConversationEntry(command.entryId, command.route, ConversationRole.USER, command.text))
        diagnostics("said stored=$stored route=${command.route.name} chars=${command.text.length}")
        // A SAID for the route that is not on screen is recorded but must not move the current view: only a SHOW
        // changes the route, and its render will bring the updated conversation in then.
        if (command.route == order.selected()) {
            // A stored replacement is still an accepted update even when `ConversationLog` returns the same list; the
            // old Activity revealed for every stored selected-route SAID, so the revision must move with it.
            if (stored) contentRevision++
            renderModel()
        }
    }

    /**
     * Records the level the phone stated and puts the panel in line with it.
     *
     * The level is bounded in [BusyState], so a "finished" that never arrives stops by itself rather than holding the
     * panel awake for the life of the process.
     */
    private fun onBusy(command: HintCommand.Busy) {
        if (!hints.isCurrent(command.sessionId, command.generation)) {
            diagnostics("busy refused: session or generation mismatch")
            return
        }
        busyState.set(command.route, command.busy, clock.nowNanos())
        diagnostics("busy route=${command.route.name} stated=${command.busy} " +
            "any=${busyState.any(clock.nowNanos())}")
        applyAndRender()
    }

    /**
     * One accepted hint, or `null` when nothing is live any more.
     *
     * `null` means **nothing is live**, not "forget what was shown": the entries stay, and only the emphasis is
     * removed. That distinction is the whole of spec §6.6.
     */
    private fun onHintDisplayed(displayed: DisplayedHint?) {
        if (displayed == null) {
            liveEntryId = null
        } else {
            val stored = log.append(
                ConversationEntry(displayed.hintId, displayed.route, ConversationRole.ASSISTANT, displayed.text))
            if (!stored) {
                // Refused only for a blank id or blank text, which the wire already rejects; recorded rather than
                // dropped silently because "nothing appeared" would otherwise have no cause.
                diagnostics("hint refused by the conversation log: blank id or text")
            }
            liveEntryId = displayed.hintId
            // The display follows what arrived. Without this the wearer reading one conversation would never see a
            // hint filed under the other, which is precisely the failure the route tags exist to prevent.
            order.show(displayed.route)
            // Every accepted SHOW is new content, even when the log replaces an equal entry (same hint id/text with a
            // newer revision): the old Activity revealed unconditionally, and the view needs a signal that survives
            // entry equality.
            contentRevision++
        }
        applyAndRender()
    }

    // ---------------------------------------------------------------- BACK

    /**
     * The official back decision, as the panel has always made it.
     *
     * Busy with an identity sends `STOP` and keeps the window: the phone cancels the turn and broadcasts its new level.
     * A STOP that could not be sent is not an interrupt - keeping the window open would trap the wearer until the local
     * bound expires - so that case falls through to the exit branch, as does an idle back.
     */
    private fun onBack(): Boolean {
        val identity = hints.currentIdentity()
        val busy = busyState.any(clock.nowNanos())
        if (busy && identity != null) {
            // A malformed identity/encoder failure must fall through to the exit branch rather than throw on the UI
            // thread. `encode` is total for a decoded HELLO, but this is the one path a wearer uses to get out.
            val sent = runCatching {
                send(HintCommand.Stop(identity.first, identity.second))
            }.getOrDefault(false)
            if (sent) {
                diagnostics("P5_GLASS_BACK action=stop sent=true session=${identity.first} " +
                    "generation=${identity.second}")
                return true
            }
            diagnostics("P5_GLASS_BACK action=exit reason=stop_unsent busy=true identity=true")
            port.hide()
            return true
        }
        diagnostics("P5_GLASS_BACK action=exit busy=$busy identity=${identity != null}")
        port.hide()
        return true
    }

    // ---------------------------------------------------------------- model

    /**
     * The full render pass: wake the panel if something must be seen, publish the model, and arm the busy bound.
     *
     * This is the Activity's old `applyPanelAndAnimation` plus its `rebuild`: one model is now the single description
     * of what the view should look like. The bound is armed **only while running**, exactly as the old method skipped
     * it while paused; the Activity's `render` still releases the wake lock on a paused screen.
     */
    private fun applyAndRender() {
        val now = clock.nowNanos()
        val busy = busyState.any(now)
        if (running && (busy || liveEntryId != null)) port.wakeScreen()
        port.render(buildModel(now))
        if (!running || !busy) return
        // One armed deadline per busy spell: a repeat of the same level must not keep pushing it back.
        if (busyDeadlineArmed) return
        busyDeadlineArmed = true
        scheduler.at(now + BusyState.MAX_MILLIS * 1_000_000L) {
            busyDeadlineArmed = false
            applyAndRender()
        }
    }

    /** The one render path that does not touch the wake claim or the busy bound: an utterance on the shown route. */
    private fun renderModel() {
        port.render(buildModel(clock.nowNanos()))
    }

    /**
     * The panel's state row, computed in one place so the listening line, the thinking row, the pin and the tips
     * cannot disagree with each other.
     *
     * The busy boolean is **per displayed route** for the two rows (a proactive turn must not make the wake
     * conversation look busy, and the other way round); the tips use `any`, because either route working is something
     * the wearer can interrupt. Both rules are the ones the Activity applied before the extraction.
     */
    private fun buildModel(now: Long): PanelModel {
        val route = order.selected()
        val live = liveEntryId != null
        val busyForRoute = busyState.isBusy(route, now)
        val busyAny = busyState.any(now)
        val entries = log.entries(route)
        val lastRole = entries.lastOrNull()?.role
        val listening = GlassesPanelSpec.listeningVisible(busyForRoute, live, route, lastRole)
        return PanelModel(
            route = route,
            entries = entries,
            liveEntryId = liveEntryId,
            contentRevision = contentRevision,
            // No official status model exists in Task 8; the wire statuses are wired in Task 12/13/17.
            status = null,
            idle = !busyAny,
            pinned = order.isPinned(),
            listeningVisible = listening,
            // Task 8 rule: do not change Hi behaviour. The current panel's Hi text was the listening line, so the
            // safest existing signal keeps the rendered view identical until the official first-open rule lands.
            hiVisible = listening,
            thinkingVisible = GlassesPanelSpec.thinkingVisible(busyForRoute, live, route, lastRole),
            // No tool rows yet (Task 17).
            toolLabel = null,
            tips = GlassesPanelSpec.tipsText(busyAny),
        )
    }

    private companion object {
        // Android `KeyEvent.ACTION_*` / `KEYCODE_*` values. Spelled as numbers so this class has no Android import;
        // the Activity passes the event's own `action`/`keyCode`, so the two can never disagree at the seam.
        const val ACTION_DOWN = 0
        const val ACTION_UP = 1
        const val KEYCODE_BACK = 4
        const val KEYCODE_ENTER = 66
    }
}
