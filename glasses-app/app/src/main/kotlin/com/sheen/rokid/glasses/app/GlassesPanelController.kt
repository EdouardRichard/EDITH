package com.sheen.rokid.glasses.app

import com.sheen.rokid.contracts.AssistantRoute
import com.sheen.rokid.contracts.ToolPhase
import com.sheen.rokid.glasses.bridge.GlassClockResponder
import com.sheen.rokid.glasses.display.AnswerRevisionGate
import com.sheen.rokid.glasses.display.AsrRevisionGate
import com.sheen.rokid.glasses.display.BusyState
import com.sheen.rokid.glasses.display.ConversationOrder
import com.sheen.rokid.glasses.display.GlassesPanelSpec
import com.sheen.rokid.glasses.display.OrbState
import com.sheen.rokid.glasses.display.SaidSequenceGate
import com.sheen.rokid.glasses.display.ToolRowState
import com.sheen.rokid.output.BackAction
import com.sheen.rokid.output.ConversationEntry
import com.sheen.rokid.output.ConversationLog
import com.sheen.rokid.output.ConversationRole
import com.sheen.rokid.output.DisplayedHint
import com.sheen.rokid.output.GlassHintController
import com.sheen.rokid.output.HintCommand
import com.sheen.rokid.output.HintWireCodec
import com.sheen.rokid.output.OfficialConversation
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
 * The official status/idle/tool model is not produced yet (Task 17): [PanelModel.toolLabel] is null and per-turn rows
 * are still one flat log. Task 13 wires the status levels the phone publishes (`STATE`/`IDLE`) into the two official
 * decisions that are visible today: the back/tips matrix and the first-open Hi rule. An old phone that only has
 * `BUSY` keeps the legacy listening/thinking rows as the spec's degraded mode, but never claims the official
 * "interrupt" copy.
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
    /** The official status (0–7) for the selected route; null before a `STATE`/`IDLE` or while the route is idle. */
    val status: OfficialStatus?,
    /** Whether nothing is in progress for the selected route; true before the first status and for official idle. */
    val idle: Boolean,
    /** True only for the official `showIdleStatus`/`IDLE` command; false for "no status yet" and no work. */
    val officialIdle: Boolean,
    val pinned: Boolean,
    /**
     * The ASR "…"/listener indicator row.
     *
     * With an official status this is `OfficialConversation.listeningVisible` (status 0/1 only); without one it keeps
     * the legacy `BUSY`-derived row for an old phone. It is **not** the Hi text.
     */
    val listeningVisible: Boolean,
    /**
     * 0.24 S4 Task 19: the ASR "…" waiting GIF row.
     *
     * With an official status this is `OfficialConversation.asrLoadingVisible` (status 0/1 only), except in the
     * official idle state where it is hidden. An old phone without a status keeps the legacy listening signal so the
     * degraded panel still shows that it is waiting.
     */
    val asrLoadingVisible: Boolean,
    /**
     * The `Hi, 我在听` text.
     *
     * With an official status this is `OfficialConversation.hiVisible(status, firstOpen)`, so the automatic next turn
     * (status 5 → 1) listens without saying Hi again. An old phone without a status keeps the legacy signal.
     */
    val hiVisible: Boolean,
    val thinkingVisible: Boolean,
    /** The turn the thinking row belongs to; null whenever [thinkingVisible] is false. */
    val thinkingTurnId: String?,
    val toolLabel: String?,
    /** The turn and phase of [toolLabel]; both null whenever the tool row is not on screen (not status 7). */
    val toolTurnId: String?,
    val toolPhase: ToolPhase?,
    val tips: String,
    /**
     * 0.24 S4 Task 19: the assistant entry ids whose authoritative `SHOW` already arrived.
     *
     * Only these answer rows are Markwon-rendered; the streamed partials stay plain (spec §5.6). The set is per route
     * and is cleared with the window/session, exactly like the conversation log it describes.
     */
    val finalAnswerEntryIds: Set<String> = emptySet(),
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

    /** Rejects an out-of-order `ASR` revision, and any revision after the final `SAID` replaced that line. */
    private val asrGate = AsrRevisionGate()

    /** Rejects an out-of-order `ANSWER` revision, and any revision after the final `SHOW` replaced that line. */
    private val answerGate = AnswerRevisionGate()

    /** Whether the assistant is working, per route; the level expires by itself inside the class. */
    private val busyState = BusyState()

    /**
     * The official status for each route, as last published by `STATE`.
     *
     * Null means no official level has arrived for that route (an old phone, or a fresh window); the model then keeps
     * the legacy `BUSY` interpretation. `IDLE` removes the status rather than storing status 0, because the official
     * `showIdleStatus` is a separate state (spec §3.3/§5.3).
     */
    private val officialStatus: MutableMap<AssistantRoute, OfficialStatus> = mutableMapOf()

    /** Whether each route is in the official idle state. A later `STATE` clears it. */
    private val officialIdle: MutableMap<AssistantRoute, Boolean> = mutableMapOf()

    /**
     * The turn an accepted `STATE` named for each route (0.24 S4 Task 17).
     *
     * The per-turn grouping needs to know which item a transient row (thinking, tool) belongs to; `STATE` is the only
     * command that names the current turn without creating an entry. It is cleared with the window/new session.
     */
    private val officialTurn: MutableMap<AssistantRoute, String> = mutableMapOf()

    /** The tool row each route last reported: only visible while that route is at status 7 (spec §5.6). */
    private val toolRows: MutableMap<AssistantRoute, ToolRowState> = mutableMapOf()

    /**
     * 0.24 S4 Task 19: the answer entries whose final `SHOW` was accepted, per route.
     *
     * This is the Markdown authority: `onAnswer` revisions are partials (plain text), and only the `SHOW` that passed
     * every gate becomes the parsed Markdown row. The id (`say-${hintId}`) survives a later status/turn, so an old
     * final does not fall back to plain text when the list is rebuilt.
     */
    private val finalAnswerEntries: MutableMap<AssistantRoute, MutableSet<String>> = mutableMapOf()

    /**
     * The first-open latch for Hi, per route.
     *
     * True until the first listening turn (status 0/1) has ended, an idle arrives, or the window closes. Official
     * `showListenerUI` never restores `idv_tv_speak`, so the automatic status-5-to-1 turn must not say Hi again.
     */
    private val hiFirstOpen: MutableMap<AssistantRoute, Boolean> =
        AssistantRoute.entries.associateWithTo(mutableMapOf()) { true }

    /**
     * The last official sequence applied per route.
     *
     * `STATE` and `IDLE` share one sequence domain (spec §5.4): a late status older than the applied level must not
     * overwrite it. The domain is reset only by a new session identity, not by a panel close, so stale commands queued
     * across a hidden window still lose.
     */
    private val lastStateSequence: MutableMap<AssistantRoute, Long> =
        AssistantRoute.entries.associateWithTo(mutableMapOf()) { Long.MIN_VALUE }

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

    /**
     * 0.25 Task 11 (R51): the confirmation question the ring's keys currently answer, or null outside confirm mode.
     *
     * One id only, because the plugin asks one question at a time. It is **not** the panel's pin or the selected
     * route: confirmation is a modal key state layered over the ordinary panel, and leaving it restores that panel's
     * own decisions untouched.
     */
    private var confirmationRequestId: String? = null

    /** Read-only view of [confirmationRequestId] for the Activity and the JVM tests. */
    val confirmingRequestId: String? get() = confirmationRequestId

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

            // 0.24 S4: one streaming user revision. It updates the row `asr-${turnId}` in place; the final still
            // arrives as `SAID` under that same id.
            is HintCommand.Asr -> onAsr(command)

            // 0.24 S4 Task 16: one streaming answer revision. It updates the row `say-${hintId}` in place; the final
            // still arrives as the hint `SHOW` under that same id.
            is HintCommand.Answer -> onAnswer(command)

            // 0.24 S4 Task 17: the tool row's START/END (the row is visible only at the official status 7).
            is HintCommand.Tool -> onTool(command)

            // Phase C: whether the assistant is working. Same session gate as a SHOW.
            is HintCommand.Busy -> onBusy(command)

            // 0.24 S3: the official status level and the separate showIdleStatus state. The two share one sequence
            // domain, and the visible back/tips/Hi rules are derived from them in buildModel.
            is HintCommand.State -> onState(command)
            is HintCommand.Idle -> onIdle(command)

            // A **new** session starts clean. Only a new one: the phone repeats its opening HELLO while it waits for
            // the clock exchange, and clearing on every HELLO would wipe a conversation whose utterances had already
            // arrived.
            is HintCommand.Hello -> onHello(command)

            // 0.25 Task 11: the phone says whether a confirmation question is up. It changes the ring's key map, not
            // the conversation.
            is HintCommand.ConfirmState -> onConfirmState(command)

            else -> hints.accept(command)
        }
        return true
    }

    /**
     * One key from the ring, in Android's raw `KeyEvent` numbers so this class stays Android-free.
     *
     * The timing is the official one (`AiAssistantDialog.onKeyUp` plus `ErAIRecyclerView.dispatchKeyEvent`): ENTER and
     * BACK are acted on **`ACTION_UP`**, while their `ACTION_DOWN` is consumed so no other handler can claim the
     * gesture. The direction keys belong to the host (which pages on `ACTION_DOWN`); the controller never consumes them,
     * so left/right stay available to the window.
     *
     * Returns whether the panel consumed the key. The Activity answers a cancelled BACK before delegating (a lost
     * focus mid-press is not a wearer decision) and keeps the page gestures for its scroll view; both are window glue,
     * not panel state.
     */
    fun onKey(keyCode: Int, action: Int): Boolean {
        // 0.25 Task 11 (R51): confirmation is a modal key state. ENTER allows and BACK denies, both on ACTION_UP with
        // their ACTION_DOWN consumed; neither may fall through to the pin or the STOP/exit matrix, and direction keys
        // stay the host's so the wearer can still page while deciding.
        if (confirmationRequestId != null) {
            return when {
                (keyCode == KEYCODE_ENTER || keyCode == KEYCODE_BACK) && action == ACTION_DOWN -> true
                keyCode == KEYCODE_ENTER && action == ACTION_UP -> answerConfirmation(approved = true)
                keyCode == KEYCODE_BACK && action == ACTION_UP -> answerConfirmation(approved = false)
                else -> false
            }
        }
        return when {
            keyCode == KEYCODE_ENTER && action == ACTION_DOWN -> true
            keyCode == KEYCODE_BACK && action == ACTION_DOWN -> true
            keyCode == KEYCODE_ENTER && action == ACTION_UP -> onEnter()
            keyCode == KEYCODE_BACK && action == ACTION_UP -> onBack()
            else -> false
        }
    }

    /**
     * One `CONFIRM_STATE` level (0.25 Task 11, R51).
     *
     * The session/generation gate is the hint controller's, so a queued state from a previous session cannot map the
     * ring's keys to a question the wearer can no longer see. `active=true` enters the mode and renders (the summary
     * itself arrived as a normal hint); a repeat is the same statement. `active=false` only leaves the mode when it
     * names the question that is actually live - a late withdrawal for an older question must not disarm a newer one.
     */
    private fun onConfirmState(command: HintCommand.ConfirmState) {
        if (!hints.isCurrent(command.sessionId, command.generation)) {
            diagnostics("confirm refused: session or generation mismatch")
            return
        }
        if (command.requestId.isBlank()) {
            diagnostics("confirm refused: blank request id")
            return
        }
        if (command.active) {
            confirmationRequestId = command.requestId
            diagnostics("confirm active request=${command.requestId}")
            applyAndRender()
        } else if (confirmationRequestId == command.requestId) {
            confirmationRequestId = null
            diagnostics("confirm inactive request=${command.requestId}")
            applyAndRender()
        }
    }

    /**
     * Sends one decision for the live question and leaves confirm mode immediately, whatever the send answers.
     *
     * The send result is diagnostic only: the keys must not stay mapped to a question that the phone's own 120 s
     * window will settle anyway, and a decision that could not be sent is the phone's timeout-as-denial, not a second
     * chance to press a key under a different meaning.
     */
    private fun answerConfirmation(approved: Boolean): Boolean {
        val requestId = confirmationRequestId ?: return true
        confirmationRequestId = null
        val identity = hints.currentIdentity()
        val sent = if (identity == null) {
            diagnostics("confirm decision deferred: no session identity")
            false
        } else {
            runCatching {
                send(HintCommand.ConfirmDecision(identity.first, identity.second, requestId, approved))
            }.getOrDefault(false)
        }
        diagnostics("P5_GLASS_CONFIRM action=${if (approved) "allow" else "deny"} sent=$sent " +
            "request=$requestId")
        return true
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
        // 0.25 Task 11: a destroyed screen cannot answer a question either; the phone's window settles it.
        confirmationRequestId = null
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
        asrGate.reset()
        answerGate.reset()
        busyState.reset()
        resetOfficialState(resetSequence = false)
        finalAnswerEntries.clear()
        liveEntryId = null
        contentRevision = 0L
        busyDeadlineArmed = false
        // 0.25 Task 11: a confirmation question belongs to the window that showed it; the next window starts with the
        // ordinary ring semantics and learns about a still-live question only from a new CONFIRM_STATE.
        confirmationRequestId = null
        applyAndRender()
    }

    /**
     * Drops the official status/idle/hi-latch of the previous window.
     *
     * A panel close keeps [lastStateSequence] (a late level queued across the hidden window must still lose); a new
     * session identity resets it too, because the phone starts a new sequence domain with the new session.
     */
    private fun resetOfficialState(resetSequence: Boolean) {
        officialStatus.clear()
        officialTurn.clear()
        toolRows.clear()
        AssistantRoute.entries.forEach { route ->
            officialIdle[route] = false
            hiFirstOpen[route] = true
            if (resetSequence) lastStateSequence[route] = Long.MIN_VALUE
        }
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
            asrGate.reset()
            answerGate.reset()
            busyState.reset()
            resetOfficialState(resetSequence = true)
            finalAnswerEntries.clear()
            liveEntryId = null
            // 0.25 Task 11: the keys must not keep answering the previous session's question.
            confirmationRequestId = null
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
        // A final replacing a streamed ASR line must keep the turn, or the glasses lose the per-turn grouping the
        // partial established. `SAID` carries no turn field, so take it from the replaced entry; a final with no
        // preceding partial recovers it from the documented `asr-${turnId}` id (spec §6).
        val previousTurn = log.entries(command.route)
            .firstOrNull { it.entryId == command.entryId }
            ?.turnId
        val turnId = previousTurn ?: AsrRevisionGate.turnIdOf(command.entryId)
        val stored = log.append(
            ConversationEntry(command.entryId, command.route, ConversationRole.USER, command.text, turnId))
        if (stored) asrGate.finalize(command.route, command.entryId)
        diagnostics("said stored=$stored route=${command.route.name} chars=${command.text.length} " +
            "turn=${turnId ?: "none"}")
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
     * One streaming ASR revision (0.24 spec §5.4/§6). Must run on the UI thread.
     *
     * The row is `asr-${turnId}`, the same id the final `SAID` replaces, so revisions update **one** user row in
     * place. Revisions are monotonic per route/turn; an out-of-order one is dropped. A final already replaced the
     * line, so any later revision for that entry is stale and dropped too while the gate still remembers that final
     * (a bounded history per route) - the final text is authoritative.
     *
     * Like `SAID`, a revision for the route that is not on screen is stored but does not move the view: only a `SHOW`
     * changes the route.
     */
    private fun onAsr(command: HintCommand.Asr) {
        if (!hints.isCurrent(command.sessionId, command.generation)) {
            diagnostics("asr refused: session or generation mismatch")
            return
        }
        if (command.text.isBlank()) {
            diagnostics("asr refused: blank text")
            return
        }
        if (asrGate.isFinalized(command.route, command.turnId)) {
            diagnostics("asr refused: final already stored route=${command.route.name} turn=${command.turnId}")
            return
        }
        if (!asrGate.accept(command.route, command.turnId, command.revision)) {
            diagnostics("asr refused: revision ${command.revision} is not newer")
            return
        }
        val entryId = AsrRevisionGate.userEntryId(command.turnId)
        val stored = log.append(
            ConversationEntry(entryId, command.route, ConversationRole.USER, command.text, command.turnId))
        diagnostics("asr stored=$stored route=${command.route.name} rev=${command.revision} " +
            "chars=${command.text.length}")
        if (stored && command.route == order.selected()) {
            contentRevision++
            renderModel()
        }
    }

    /**
     * One streaming answer revision (0.24 spec §5.6/§6, Task 16). Must run on the UI thread.
     *
     * The row is `say-${hintId}`, the same id the final `SHOW` replaces, so revisions update **one** assistant row in
     * place. Revisions are monotonic per route/hint; an out-of-order one is dropped. A final already replaced the
     * line, so any later revision for that entry is stale and dropped too while the gate still remembers that final
     * (a bounded history per route) - the final text is authoritative.
     *
     * Like `SAID`/`ASR`, a revision for the route that is not on screen is stored but does not move the view: only a
     * `SHOW` changes the route.
     */
    private fun onAnswer(command: HintCommand.Answer) {
        if (!hints.isCurrent(command.sessionId, command.generation)) {
            diagnostics("answer refused: session or generation mismatch")
            return
        }
        if (command.text.isBlank()) {
            diagnostics("answer refused: blank text")
            return
        }
        if (answerGate.isFinalized(command.route, command.hintId)) {
            diagnostics("answer refused: final already stored route=${command.route.name} hint=${command.hintId}")
            return
        }
        if (!answerGate.accept(command.route, command.hintId, command.revision)) {
            diagnostics("answer refused: revision ${command.revision} is not newer")
            return
        }
        val entryId = AnswerRevisionGate.answerEntryId(command.hintId)
        val stored = log.append(
            ConversationEntry(entryId, command.route, ConversationRole.ASSISTANT, command.text, command.turnId))
        diagnostics("answer stored=$stored route=${command.route.name} rev=${command.revision} " +
            "chars=${command.text.length}")
        if (stored && command.route == order.selected()) {
            contentRevision++
            renderModel()
        }
    }

    /**
     * One tool row's START/END (0.24 S4 Task 17, spec §5.6). Must run on the UI thread.
     *
     * A tool row belongs to the turn the current `STATE 7` named: the plugin publishes the status before the row, and
     * the custom-command channel promises no ordering, so a row for a different turn is a stray from a previous turn
     * and is refused rather than replacing the live one. There is no sequence on `TOOL`, so a delayed END for a turn
     * whose row no longer exists is stored (it is the only information there is) and simply stays hidden unless the
     * route is back at status 7.
     *
     * The row is **not** a conversation entry: it exists only while status 7 is on screen. A new START replaces the
     * label (a second tool call is a new row, not an append), and END only restyles the same row.
     */
    private fun onTool(command: HintCommand.Tool) {
        if (!hints.isCurrent(command.sessionId, command.generation)) {
            diagnostics("tool refused: session or generation mismatch")
            return
        }
        if (command.turnId.isBlank() || command.label.isBlank()) {
            diagnostics("tool refused: blank turn or label")
            return
        }
        val currentTurn = officialTurn[command.route]
        val currentStatus = officialStatus[command.route]
        // Only a row for a turn other than the **displayed tool turn** is a stray. A TOOL that overtakes its STATE is
        // stored (still hidden) rather than refused: the unordered channel may deliver the two in either order, and the
        // row becomes visible when its status 7 arrives.
        if (currentStatus == OfficialStatus.TOOL && currentTurn != null && currentTurn != command.turnId) {
            diagnostics("tool refused: turn=${command.turnId} is not the displayed turn=$currentTurn")
            return
        }
        toolRows[command.route] = ToolRowState(command.turnId, command.label, command.phase)
        diagnostics("tool route=${command.route.name} turn=${command.turnId} phase=${command.phase.name} " +
            "chars=${command.label.length}")
        if (command.route == order.selected()) {
            // The row appearing/restyling is content the view must reveal, like a conversation revision.
            contentRevision++
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
     * One official status level (0.24 spec §5.4).
     *
     * A status for a route that is not on screen is stored but does not move the view: only a `SHOW` brings a
     * conversation forward (Task 8's rule, unchanged). The first listening turn leaves [hiFirstOpen] true so a status
     * 0 → 1 sequence keeps the greeting; any non-listening status ends that turn and latches Hi off.
     */
    private fun onState(command: HintCommand.State) {
        if (!hints.isCurrent(command.sessionId, command.generation)) {
            diagnostics("state refused: session or generation mismatch")
            return
        }
        if (!acceptStateSequence(command.route, command.sequence)) {
            diagnostics("state refused: sequence ${command.sequence} is not newer")
            return
        }
        val status = OfficialStatus.entries.firstOrNull { it.wire == command.status }
        if (status == null) {
            // Status 6 is the official JSUI card, which is out of EDITH's scope (plan Tasks 8/12/15). It is consumed
            // and its sequence is spent, but it must not repaint the panel or invent a level.
            diagnostics("state status=${command.status} ignored")
            return
        }
        officialStatus[command.route] = status
        officialTurn[command.route] = command.turnId
        officialIdle[command.route] = false
        if (status != OfficialStatus.EMPTY && status != OfficialStatus.LISTENING) {
            hiFirstOpen[command.route] = false
        }
        diagnostics("state route=${command.route.name} status=${status.name} sequence=${command.sequence}")
        if (command.route == order.selected()) {
            // 0.24 S4 Task 16 (Task 13 carry I1): a level that names something other than "an answer is in front of
            // the wearer" retires the previous answer's live emphasis. Without this a live answer left over from
            // `SHOW` kept the orb ANSWERING through status 5 -> 1 and could mask status 4's thinking orb.
            if (retiresLiveAnswer(status)) liveEntryId = null
            applyAndRender()
        }
    }

    /**
     * Whether [status] ends the previous answer's live emphasis.
     *
     * `AUDIO_FINISHED` is the official `AudioFinishedHandler`: the answer item is finalised and the following status 1
     * is a new listening round. Status 0/1/2/4 each name a state in which no answer is the current item (listening,
     * ASR, thinking), so they outrank a prior answer too. Status 3 (answering) and 7 (tool work) keep the live row.
     */
    private fun retiresLiveAnswer(status: OfficialStatus): Boolean = when (status) {
        OfficialStatus.EMPTY,
        OfficialStatus.LISTENING,
        OfficialStatus.ASR,
        OfficialStatus.ASR_END,
        OfficialStatus.AUDIO_FINISHED -> true
        OfficialStatus.ANSWERING, OfficialStatus.TOOL -> false
    }

    /** The official `showIdleStatus`: not status 0, and it ends the window's first listening turn. */
    private fun onIdle(command: HintCommand.Idle) {
        if (!hints.isCurrent(command.sessionId, command.generation)) {
            diagnostics("idle refused: session or generation mismatch")
            return
        }
        if (!acceptStateSequence(command.route, command.sequence)) {
            diagnostics("idle refused: sequence ${command.sequence} is not newer")
            return
        }
        officialStatus.remove(command.route)
        officialIdle[command.route] = true
        hiFirstOpen[command.route] = false
        diagnostics("idle route=${command.route.name} sequence=${command.sequence}")
        if (command.route == order.selected()) {
            // showIdleStatus finalises the current item; the content stays for read-back, the emphasis does not.
            liveEntryId = null
            applyAndRender()
        }
    }

    /**
     * One sequence domain shared by `STATE` and `IDLE`, per route. A duplicate or late level loses; a new session
     * identity starts a new domain, while a panel close deliberately keeps it so a command queued across the hidden
     * window cannot repaint a fresh one.
     */
    private fun acceptStateSequence(route: AssistantRoute, sequence: Long): Boolean {
        val last = lastStateSequence.getValue(route)
        if (sequence <= last) return false
        lastStateSequence[route] = sequence
        return true
    }

    /**
     * The status the visible decisions use.
     *
     * An official status wins. In the old-phone world (no `STATE`/`IDLE` at all) the legacy `BUSY` level is the only
     * evidence of "answering"; an idle old phone maps to the listening/exit copy. The fallback is deliberately
     * conservative: without a status the glasses never claim the official interrupt copy unless they are also pinned.
     */
    private fun effectiveStatus(route: AssistantRoute, now: Long): OfficialStatus {
        officialStatus[route]?.let { return it }
        if (officialIdle[route] == true) return OfficialStatus.EMPTY
        return if (busyState.isBusy(route, now)) OfficialStatus.ANSWERING else OfficialStatus.LISTENING
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
            // 0.24 S4 Task 16 (spec §6): the answer row is `say-${hintId}` on both screens. The final SHOW replaces
            // the row the streamed `ANSWER` revisions opened instead of appending a second assistant line; the turn
            // the partial carried survives the replacement (SHOW itself has no turn field).
            val entryId = AnswerRevisionGate.answerEntryId(displayed.hintId)
            val previousTurn = log.entries(displayed.route)
                .firstOrNull { it.entryId == entryId }
                ?.turnId
            // 0.24 S4 Task 17 (I1): the production notify_user answer has no streamed partial, so the turn comes from
            // the accepted STATE instead (see [finalOnlyAnswerTurn]).
            val turnId = previousTurn ?: finalOnlyAnswerTurn(displayed.route)
            val stored = log.append(
                ConversationEntry(entryId, displayed.route, ConversationRole.ASSISTANT, displayed.text, turnId))
            if (!stored) {
                // Refused only for a blank id or blank text, which the wire already rejects; recorded rather than
                // dropped silently because "nothing appeared" would otherwise have no cause.
                diagnostics("hint refused by the conversation log: blank id or text")
            } else {
                answerGate.finalize(displayed.route, entryId)
                // 0.24 S4 Task 19: the accepted final SHOW is the Markdown row; partials never enter this set. Bounded
                // like the conversation log: a final whose entry the log has evicted does not need a marker.
                val finals = finalAnswerEntries.getOrPut(displayed.route) { linkedSetOf() }
                finals += entryId
                while (finals.size > ConversationLog.MAX_ENTRIES) {
                    finals.remove(finals.first())
                }
            }
            liveEntryId = entryId
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

    /**
     * The turn a final-only answer belongs to (0.24 S4 Task 17 I1).
     *
     * The product answer is `notify_user`, emitted mid-run, and it streams **no** `ANSWER` partial: its final `SHOW`
     * carries no turn id either, so without this the answer becomes a turn-less item and the wearer's line never drops
     * to 70%. The phone publishes one `STATE` for the turn immediately before submitting the hint (status 3, or 4 when
     * the SHOW overtakes 3), and [officialTurn] records it.
     *
     * Two guards keep the unordered channel from inventing a pairing:
     * - the accepted level must still belong to the answering round (3/4/7), and
     * - the named turn must be the newest turn this route has logged and must not have an answer yet.
     * A `SHOW` that overtook its own `STATE` can only see the previous turn, which fails both facts, so the entry
     * stays turn-less (the documented legacy item) instead of joining an older question. The residual case - an old
     * hint delivered after a new turn's `ASR` while the new turn's `STATE 3` is still in flight - cannot be told apart
     * from the new answer without a turn on `SHOW`, so it remains a wire limitation (Task 22 candidate).
     */
    private fun finalOnlyAnswerTurn(route: AssistantRoute): String? {
        val status = officialStatus[route]
        if (status != OfficialStatus.ANSWERING &&
            status != OfficialStatus.ASR_END &&
            status != OfficialStatus.TOOL
        ) {
            return null
        }
        val named = officialTurn[route] ?: return null
        val entries = log.entries(route)
        if (entries.lastOrNull { it.turnId != null }?.turnId != named) return null
        if (entries.any { it.role == ConversationRole.ASSISTANT && it.turnId == named }) return null
        return named
    }

    // ---------------------------------------------------------------- BACK and pin

    /**
     * The official hold: one-way, acted on ENTER's `ACTION_UP`, and announced to the phone **once**.
     *
     * The official `holdAIAssistant()` sets `isHold = true` with no cancel path and tells the service "not-auto-exit".
     * EDITH's `PIN` command is set-only on the wire (no boolean), so a second press is a no-op, not a duplicate event.
     * The pin is applied locally even before an identity exists: the wearer's reading position does not depend on the
     * link, and only the announcement to the phone does.
     */
    private fun onEnter(): Boolean {
        if (order.isPinned()) {
            diagnostics("side touch: pinned=true already_pinned=true")
            return true
        }
        order.pin()
        val identity = hints.currentIdentity()
        val sent = if (identity == null) {
            diagnostics("pin deferred: no session identity")
            false
        } else {
            runCatching {
                send(HintCommand.Pin(identity.first, identity.second, order.selected()))
            }.getOrDefault(false)
        }
        diagnostics("side touch: pinned=true route=${order.selected().name} pinSent=$sent")
        applyAndRender()
        return true
    }

    /**
     * The official back decision (spec §3.3/§5.3), as `OfficialConversation.backAction` states it.
     *
     * Unpinned always exits; pinned while answering sends `STOP` and keeps the window (the phone shows its idle); pinned
     * in any other status exits. A STOP that could not be sent is not an interrupt - keeping the window would trap the
     * wearer until the local bound expires - so that case falls through to the exit branch, as does a missing identity.
     *
     * [PanelPort.hide] is the host's visible-to-hidden transition; the production service sends `UI_CLOSED` there
     * (Task 9's window ownership), so the controller must not emit a duplicate event of its own.
     */
    private fun onBack(): Boolean {
        val identity = hints.currentIdentity()
        val pinned = order.isPinned()
        val status = effectiveStatus(order.selected(), clock.nowNanos())
        when (OfficialConversation.backAction(status, pinned)) {
            BackAction.STOP -> {
                if (identity != null) {
                    // A malformed identity/encoder failure must fall through to the exit branch rather than throw on
                    // the UI thread; this is the one path a wearer uses to get out.
                    val sent = runCatching {
                        send(HintCommand.Stop(identity.first, identity.second))
                    }.getOrDefault(false)
                    if (sent) {
                        diagnostics("P5_GLASS_BACK action=stop sent=true session=${identity.first} " +
                            "generation=${identity.second} pinned=$pinned status=${status.name}")
                        return true
                    }
                    diagnostics("P5_GLASS_BACK action=exit reason=stop_unsent pinned=$pinned " +
                        "status=${status.name}")
                } else {
                    diagnostics("P5_GLASS_BACK action=exit reason=no_identity pinned=$pinned " +
                        "status=${status.name}")
                }
                port.hide()
                return true
            }
            BackAction.EXIT -> {
                diagnostics("P5_GLASS_BACK action=exit reason=matrix pinned=$pinned status=${status.name} " +
                    "identity=${identity != null}")
                port.hide()
                return true
            }
        }
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
     * The panel's state row, computed in one place so the listening line, the Hi line, the thinking row, the pin and
     * the tips cannot disagree with each other.
     *
     * With an official status the rows and the back/tips copy are the official rules. Without one (an old phone, or a
     * fresh hidden window) the two rows keep the legacy `BUSY` interpretation; that is the spec's degraded mode, not a
     * second status source. The legacy rows are per displayed route so a proactive turn cannot paint the wake view.
     */
    private fun buildModel(now: Long): PanelModel {
        val route = order.selected()
        val live = liveEntryId != null
        val busyForRoute = busyState.isBusy(route, now)
        val busyAny = busyState.any(now)
        val entries = log.entries(route)
        val lastRole = entries.lastOrNull()?.role

        val official = officialStatus[route]
        val idleForRoute = officialIdle[route] == true
        val officialKnown = official != null || idleForRoute
        val statusForRules = official ?: OfficialStatus.EMPTY

        val legacyListening = GlassesPanelSpec.listeningVisible(busyForRoute, live, route, lastRole)
        val legacyThinking = GlassesPanelSpec.thinkingVisible(busyForRoute, live, route, lastRole)
        val listening = if (officialKnown) {
            OfficialConversation.listeningVisible(statusForRules, idleForRoute, hiSeen = !hiFirstOpen.getValue(route))
        } else {
            legacyListening
        }
        val hi = if (officialKnown) {
            OfficialConversation.hiVisible(statusForRules, firstOpen = hiFirstOpen.getValue(route))
        } else {
            legacyListening
        }
        // 0.24 S4 Task 19: the official "…" GIF is status 0/1 only and never the separate idle state; without a status
        // the legacy busy level keeps the old-phone indicator (the degraded mode, not a second official rule).
        val asrLoading = if (officialKnown) {
            !idleForRoute && OfficialConversation.asrLoadingVisible(statusForRules)
        } else {
            legacyListening
        }
        // 0.24 S4 Task 17 (spec §5.6): the thinking row is the "before the answer" row. Status 4 owns it, and status 3
        // keeps it until this turn's answer text actually exists - "答案到达隐藏", not merely "status 3 arrived". A live
        // final `SHOW` is an answer even when the hint carried no turn id (the legacy/first-answer shape), so the live
        // emphasis is checked as well as the per-turn entry.
        val statusTurn = officialTurn[route]
        val thinking = if (officialKnown) {
            (statusForRules == OfficialStatus.ASR_END || statusForRules == OfficialStatus.ANSWERING) &&
                !live && !answerArrived(entries, statusTurn)
        } else {
            legacyThinking
        }
        val thinkingTurn = if (thinking) statusTurn ?: entries.lastOrNull()?.turnId else null
        // The tool row exists only at the official tool level, and only for the turn that level names.
        val tool = if (officialKnown && !idleForRoute && statusForRules == OfficialStatus.TOOL) {
            toolRows[route]?.takeIf { statusTurn == null || it.turnId == statusTurn }
        } else {
            null
        }
        val effective = effectiveStatus(route, now)
        val pinned = order.isPinned()

        return PanelModel(
            route = route,
            entries = entries,
            liveEntryId = liveEntryId,
            contentRevision = contentRevision,
            status = official,
            idle = if (officialKnown) idleForRoute else !busyAny,
            officialIdle = idleForRoute,
            pinned = pinned,
            listeningVisible = listening,
            asrLoadingVisible = asrLoading,
            hiVisible = hi,
            thinkingVisible = thinking,
            thinkingTurnId = thinkingTurn,
            toolLabel = tool?.label,
            toolTurnId = tool?.turnId,
            toolPhase = tool?.phase,
            tips = OfficialConversation.tipsText(effective, pinned),
            finalAnswerEntryIds = finalAnswerEntries[route]?.toSet().orEmpty(),
        )
    }

    /**
     * Whether an answer row already exists for [turnId] (or, for a legacy turn-less log, after the last user line).
     *
     * This is the only fact that retires the thinking row before status 3: the status says the model is answering, but
     * the wearer must keep seeing "正在思考" until there is an answer line to read.
     */
    private fun answerArrived(entries: List<ConversationEntry>, turnId: String?): Boolean {
        if (turnId != null) {
            return entries.any { it.role == ConversationRole.ASSISTANT && it.turnId == turnId }
        }
        val lastUser = entries.indexOfLast { it.role == ConversationRole.USER }
        return entries.drop(lastUser + 1).any { it.role == ConversationRole.ASSISTANT }
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

/**
 * The orb state from one published model (0.24 S4 Task 16, Task 13 carry I1).
 *
 * An official status outranks a leftover live answer: the status names what the panel is doing **now**. Status 0/1/2
 * are the listener segments, 4 is the thinking segment, and 3/5/7 are the answer/finish segments (spec §3.4's PAG
 * table). Only when no official status exists - an old phone, or a fresh window - does the legacy
 * live/listening/thinking order decide. This is a pure decision so the orb priority fails a JVM test instead of only
 * being visible on a worn screen; `GlassesPanelHost.applyVisualState` renders it.
 */
internal fun orbStateOf(model: PanelModel): OrbState =
    // One status→state table for both the probe and the PAG fallback (GlassesPanelSpec.orbStateForStatus, Task 18).
    model.status?.let { GlassesPanelSpec.orbStateForStatus(it) } ?: when {
        model.liveEntryId != null -> OrbState.ANSWERING
        model.listeningVisible -> OrbState.LISTENING
        model.thinkingVisible -> OrbState.THINKING
        else -> OrbState.IDLE
    }

/** What the PAG orb should do for one published model (0.24 S4 Task 18 review I1). */
internal sealed interface OrbRequest {
    /** An official status names the exact PAG segment. */
    data class Status(val status: OfficialStatus) : OrbRequest

    /** The official `showIdleStatus`/`IDLE` command: play the finish tail once, then stop. */
    data object OfficialIdle : OrbRequest

    /** No official status: the legacy busy-level path; [state] `IDLE` means stop/hold, never `finish_last`. */
    data class Legacy(val state: OrbState) : OrbRequest
}

/**
 * The orb request for one model, kept as a pure decision so the "no work" and official-idle cases fail a JVM test.
 *
 * `PanelModel.idle` is "nothing is happening" and is true both before the first `STATE` and after the official `IDLE`
 * command; only `PanelModel.officialIdle` is the command that plays `finish_last` (spec §5.6:353).
 */
internal fun orbRequestOf(model: PanelModel): OrbRequest = when {
    model.status != null -> OrbRequest.Status(model.status)
    model.officialIdle -> OrbRequest.OfficialIdle
    else -> OrbRequest.Legacy(orbStateOf(model))
}
