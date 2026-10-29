package com.sheen.rokid.phone.plugin.smarthelper.turn

import com.sheen.rokid.contracts.AssistantRoute
import com.sheen.rokid.contracts.SpeechResult
import com.sheen.rokid.output.OfficialConversation
import com.sheen.rokid.output.OfficialStatus
import com.sheen.rokid.phone.plugin.smarthelper.audit.P3Log
import com.sheen.rokid.plugin.CancellationHandle
import com.sheen.rokid.plugin.ConversationDisplay
import com.sheen.rokid.plugin.DeadlineScheduler
import com.sheen.rokid.plugin.MonotonicClock

/**
 * 0.24 S3 Task 12: the official conversation's timers and idle state, per route.
 *
 * Every rule here is the decompiled official behavior, not a re-design:
 * - `AIOpenHandler` starts a **10 s** timeout when the dialog opens ([OfficialConversation.LISTEN_TIMEOUT_MILLIS]);
 *   EDITH substitutes the glasses' `PANEL_SHOWN` for "the dialog opened", so a slow overlay is not charged to the
 *   wearer's silence (spec §5.3). The wait for that first layout is **bounded** ([PANEL_WAIT_MILLIS]): a panel that
 *   never comes up records `P3_CONV panel_not_shown`, arms no 10 s window and closes this listening round.
 * - `AsrMessageHandler` removes the timeout **before** it checks whether the ASR payload is blank; every later status
 *   (2/4/3/7) therefore has no timer at all. It also removes the 1 s continue-off exit (`MSG_EXIT`) - but **not** the
 *   pinned 120 s close, which is the separate `Ai_Delay_Quit` cleared by a new wake's `clearAll`.
 * - `AudioFinishedHandler`: continue on → status 5 and a new listening round (10 s); continue off →
 *   `scheduleExit(1000)` with **no** status 5 and no new turn.
 * - `RokidAIController.onTimeout` + `showIdleStatus`: a pinned timeout idles the panel (not status 0, not a close) and
 *   the service closes it **120 s** later ([OfficialConversation.PINNED_CLOSE_MILLIS]).
 * - `AiAssistantDialog.onKeyUp`: BACK exits unless pinned while answering, where it interrupts to idle. The **glasses**
 *   own that matrix (`OfficialConversation.backAction`, Task 13); the phone's production inputs are STOP (`onStop`,
 *   keep the panel and show idle) and UI_CLOSED (`onClose`, the panel is already gone). There is deliberately no
 *   phone-side `onBack`: the wire has no BACK command, and a duplicate of the decision here was dead code.
 * - The pin belongs to the **dialog**, not to a turn: `AiAssistantDialog.isHold` is cleared by
 *   `resetDialogUiState` when the dialog is dismissed - not by the automatic continuation (`startNewTalk`, which keeps
 *   the dialog) and not by a wake on an already visible panel (`showAiDialog` then only calls `setAiItemInfo`).
 *
 * The class owns only state and deadlines: it never touches the wire, and the plugin supplies the display port, the
 * continuation opener and turn cancellation. That is what lets the whole matrix run on a JVM with a manual clock.
 *
 * Threading: the host delivers events on one dispatcher thread while the turn's answer is assembled on the task pool,
 * so every state transition is serialized by [lock]. The callbacks that can re-enter the plugin
 * ([openContinuation], [cancelTurn]) are invoked **outside** the lock and carry the turn they were decided for; the
 * display is a fire-and-forget host port and is called under the lock so each level carries a monotonically increasing
 * sequence.
 */
internal class ConversationCycle(
    private val clock: MonotonicClock,
    private val scheduler: DeadlineScheduler,
    private val display: () -> ConversationDisplay?,
    private val continueDialogue: () -> Boolean,
    /**
     * 0.5.1 (`C2`, spec §3.2): whether a **displayed answer** opens the next listening round.
     *
     * This is the only continuation this class decides (`onAnswerDone`/`onSpeechFinished` -> `AudioFinishedHandler`).
     * The initial 10 s window (`onWake`/`armListening`) and the continue-off exit (`armContinueOffLocked`) are
     * untouched: the former is not a post-answer continuation, and the latter is its own official branch.
     *
     * Defaults to [continueDialogue] so every pre-0.5.1 direct caller - including this package's own tests - keeps the
     * old behaviour byte for byte. Production passes the split seam (`settings.continueDialogue && !phoneOnly()`).
     */
    private val continueAfterAnswer: () -> Boolean = continueDialogue,
    /**
     * 0.3 Phase 3 Task 7: opens the next listening round for the **route whose answer** just finished.
     *
     * The route is carried explicitly instead of being inferred by the opener: a PROACTIVE answer must never open a
     * WAKE round (P3-13), and the cycle is the only component that knows which route's answer-done decision this is.
     * The turn id is the finished turn's, so a stale continuation (the turn was stopped or replaced meanwhile) opens
     * nothing.
     */
    private val openContinuation: (route: AssistantRoute, turnId: String) -> Unit,
    /**
     * Whether the panel is visible right now. A turn that opens while it is already up must start its 10 s window
     * immediately rather than wait for a `PANEL_SHOWN` the glasses will not send again (spec §5.3; Task 11 exposes the
     * link's read-only state for exactly this). The production composition passes the host composition's accessor;
     * `{ false }` is the test/default world where every wake starts hidden.
     */
    private val isPanelVisible: () -> Boolean = { false },
    /**
     * Cancels the plugin's turn for [route] **only if it is still [turnId]**. Called after the lock, so a wake that
     * opened a new turn between the close decision and this callback is not torn down by it.
     */
    private val cancelTurn: (route: AssistantRoute, turnId: String) -> Unit = { _, _ -> },
    /** Evidence lines only; tests substitute a recorder. */
    private val log: (String) -> Unit = { line -> P3Log.line(line) },
) {

    private class RouteState {
        var turnId: String? = null
        var status: OfficialStatus = OfficialStatus.EMPTY
        var pinned = false

        /** The official `showIdleStatus` state: the panel stays, no listening row, no status level. */
        var idle = false

        /** A wake opened this turn while the panel was hidden; the 10 s window starts at `PANEL_SHOWN`. */
        var awaitingPanel = false

        /** One sequence domain shared by `STATE` and `IDLE` (spec §5.4). */
        var sequence = 0L

        /** The 10 s listening window. */
        var listenExpiry: CancellationHandle? = null

        /** The bounded wait for the first `PANEL_SHOWN` (spec §5.3). It is not a silence timer and survives an ASR. */
        var panelWaitExpiry: CancellationHandle? = null

        /** Official `scheduleExit(1000)` when continual dialogue is off. Removed by any ASR (`MSG_EXIT`). */
        var continueOffExpiry: CancellationHandle? = null

        /**
         * 0.24 S3 Task 14 (spec §5.5): the bounded wait for `SpeechFinished`.
         *
         * R106: the bound is [OfficialConversation.SPEECH_LIVENESS_MILLIS] (120 s after the answer's display window),
         * not the official 1 200 ms cue cadence. Long TTS may still be playing after the reading window ends; ending
         * the turn there cut the audio. A host that never reports the end is still bounded: it closes with
         * `P3_CONV no_display` instead of leaving the panel ANSWERING forever.
         */
        var speechWaitExpiry: CancellationHandle? = null

        /** Official `Ai_Delay_Quit`, 120 s after a pinned idle. **Never** removed by an ASR message. */
        var pinnedCloseExpiry: CancellationHandle? = null

        /**
         * 0.24 S5 final review Important #1: the bounded close for a `PANEL_SHOWN` that has **no turn** behind it.
         *
         * A wake can ask for the overlay and then open no turn (the scene cooldown refused it, or the plugin was not
         * active yet). The listening window needs a turn and is never armed in that state, so without this bound the
         * blank 480x380 panel would stay up until BACK. It is its own deadline family because no turn can own it.
         */
        var noTurnCloseExpiry: CancellationHandle? = null

        /**
         * Invalidates every already-scheduled callback for its own timer family. A cancelled [CancellationHandle] is
         * not guaranteed to prevent an already-queued task from running, so each callback checks the epoch it was
         * armed under before touching the state. The families are independent because cancelling the listening window
         * (an ASR message) must not make the already-armed pinned close or the bounded panel wait inert.
         */
        var listenEpoch = 0L
        var panelWaitEpoch = 0L
        var continueOffEpoch = 0L
        var pinnedCloseEpoch = 0L
        var speechWaitEpoch = 0L
        var noTurnCloseEpoch = 0L

        var answerDone = false
        var speechPending = false

        /**
         * A `SpeechFinished` that arrived **before** the answer-done report (a short TTS can finish while the glasses'
         * acknowledgement is still in flight). Remembered for this turn and consumed by `onAnswerDone(speech = true)`.
         */
        var earlySpeech: SpeechResult? = null

        /**
         * An ASR message arrived in this round. Official `AsrMessageHandler` removes the timeout unconditionally, so a
         * late `PANEL_SHOWN` must not invent a new one for a round that has already started listening.
         */
        var asrSeen = false
    }

    private val lock = Any()
    private val states: Map<AssistantRoute, RouteState> = AssistantRoute.entries.associateWith { RouteState() }

    private fun state(route: AssistantRoute): RouteState = states.getValue(route)

    // ---------------------------------------------------------------- listening

    /**
     * A wake opened a turn. With the panel already up the official 10 s starts now; with the panel hidden it waits for
     * [onPanelShown], because "the dialog opened" is what starts the official timeout and the overlay's own start
     * latency must not be counted against the wearer (spec §5.3). That wait is capped at [PANEL_WAIT_MILLIS].
     *
     * The pin is a property of the dialog: it survives an automatic continuation and a wake on an already visible
     * panel, and it is cleared only by a new window (a hidden-panel wake, whose dismiss reset the dialog) or a close.
     */
    fun onWake(route: AssistantRoute, turnId: String) {
        val visible = isPanelVisible()
        synchronized(lock) {
            val current = state(route)
            cancelAllTimersLocked(current)
            current.turnId = turnId
            current.status = OfficialStatus.EMPTY
            current.idle = false
            // Official `resetDialogUiState` clears `isHold` when the dialog is dismissed; a hidden-panel wake is the
            // new window. A visible-panel wake and the automatic continuation keep the same dialog (+ its pin).
            if (!visible) current.pinned = false
            current.awaitingPanel = !visible
            current.answerDone = false
            current.speechPending = false
            current.earlySpeech = null
            current.asrSeen = false
            if (visible) {
                displayStateLocked(route, current, OfficialStatus.LISTENING)
                armListeningLocked(route, current)
            } else {
                armPanelWaitLocked(route, current)
            }
        }
    }

    /**
     * The overlay's first layout. The **only** way a hidden-panel turn starts its 10 s window.
     *
     * A duplicate is idempotent by design: Task 11 re-announces `PANEL_SHOWN` (or delivers a delayed original) without
     * opening a second window, so this refreshes the same 10 s deadline, displays nothing a second time, and never
     * touches the turn.
     */
    fun onPanelShown(route: AssistantRoute) {
        synchronized(lock) {
            val current = state(route)
            val turnId = current.turnId
            if (turnId == null) {
                // 0.24 S5 final review Important #1: no turn means no listening window. `PANEL_SHOWN` here is a wake
                // that was refused (or lost the activation race) and still asked for the overlay - or a delayed
                // report from a turn that is already gone. The official idle panel is the one legitimate no-turn
                // state: it stays until its pinned close or the wearer closes it.
                if (!current.idle) armNoTurnCloseLocked(route, current)
                return
            }
            // A real turn owns the panel again, so the defensive close has nothing left to end.
            cancelNoTurnCloseLocked(current)
            if (current.idle) return
            // The panel is up, so the acquisition wait is over whatever level this round has already reached. This must
            // happen before the listening-only guard below: an early usable ASR publishes status 2 before the panel's
            // first layout, and clearing the wait only for status 1/0 would leave the 3 s cap armed to close a live
            // answering turn.
            val first = current.awaitingPanel
            current.awaitingPanel = false
            cancelPanelWaitLocked(current)
            // The guard now covers only what a late panel may still do: display STATE 1 and start/refresh the 10 s
            // window. A round already at 2/4/3/7 keeps its level and gets no listening timer.
            if (current.status != OfficialStatus.LISTENING && current.status != OfficialStatus.EMPTY) return
            if (first) displayStateLocked(route, current, OfficialStatus.LISTENING)
            // Refreshed on first and duplicate equally: the deadline really is "10 s from the panel being up" -
            // unless an ASR message already cancelled this round's window (the official `removeTimeout` is the last
            // word on it).
            if (!current.asrSeen) armListeningLocked(route, current)
            log(
                "P3_CONV listen route=${route.name} turn=$turnId " +
                    if (first) "shown=true" else "shown=refresh",
            )
        }
    }

    /**
     * Any ASR message. The official handler removes the timeout **before** the blank check, so a blank or
     * wake-word-only revision cancels the window just like a real one; no status transition is decided here because
     * the plugin owns which of 2/4/3 is displayed.
     *
     * It also removes the 1 s continue-off exit - official `removeExit()` removes `MSG_EXIT` and nothing else. The
     * 120 s pinned close is a **separate** timer (`Ai_Delay_Quit`, cleared by a new wake's `clearAll`), so an ASR
     * message in the idle window must not cancel it. The bounded `PANEL_SHOWN` wait is not a silence timer either: the
     * wake segment's blank echo is the *expected* first ASR message (D12), so the panel-acquisition cap survives it.
     */
    fun onAsr(route: AssistantRoute) {
        synchronized(lock) {
            val current = state(route)
            current.asrSeen = true
            cancelListenLocked(current)
            cancelContinueOffLocked(current)
        }
    }

    /**
     * A status level the plugin decided to display (2, 4, 3, 7, ...).
     *
     * Status 1 is normally [onWake]'s; a caller may still use it to restart a listening level. Every non-listening
     * level has **no** listening timer (spec §5.3: "status 2/3/4/7 不设计时"), which is also what makes an ASR message
     * cancel the window even before the answer exists.
     */
    fun onStatus(route: AssistantRoute, status: OfficialStatus) {
        synchronized(lock) {
            val current = state(route)
            if (current.turnId == null) return
            if (status == OfficialStatus.LISTENING || status == OfficialStatus.EMPTY) {
                displayStateLocked(route, current, status)
                if (!current.asrSeen) armListeningLocked(route, current)
            } else {
                cancelListenLocked(current)
                displayStateLocked(route, current, status)
            }
        }
    }

    /**
     * The ASR final adopted its own turn id; the wake placeholder is no longer the identity the wire should carry.
     *
     * Deliberately not [onWake]: adopting the id must not restart the window or reset the pin. A blank id is ignored
     * because a legacy producer may have none, and so is an id for a round a close already ended (a race at the
     * timeout boundary must not resurrect a conversation with no window behind it).
     */
    fun onTurnId(route: AssistantRoute, turnId: String) {
        if (turnId.isBlank()) return
        synchronized(lock) {
            val current = state(route)
            if (current.turnId == null) return
            current.turnId = turnId
        }
    }

    // ---------------------------------------------------------------- answer completion

    /**
     * 0.24 S4 Task 16 (Task 14 carry): a new answer emit starts a new speech identity for this route/turn.
     *
     * [RouteState.earlySpeech] is keyed by route/turn, so without this a `SpeechFinished` remembered for a superseded
     * emit could satisfy the newer answer's speech wait. The plugin already drops a stale result by emit id when it
     * arrives; this closes the window where it arrived **before** the superseding emit existed. Clearing is not a
     * decision to continue: the new answer still has to be displayed and its own speech still has to end.
     */
    fun onAnswerEmit(route: AssistantRoute) {
        synchronized(lock) {
            state(route).earlySpeech = null
        }
    }

    /**
     * The answer reached the wearer ([displayed] is the host's `Displayed` acknowledgement).
     *
     * Official `AudioFinishedHandler`:
     * - continue on → status 5, then a new listening round (the plugin's [openContinuation]);
     * - continue off → `scheduleExit(1000)`, no status 5, no new turn;
     * - not displayed → the answer the next round would continue does not exist, so close and open nothing.
     *
     * When [speech] is true the answer is still playing, so the decision waits for [onSpeechFinished] exactly as the
     * official TTS path does - unless the result already arrived (see [onSpeechFinished]); then it is consumed here.
     */
    fun onAnswerDone(
        route: AssistantRoute,
        displayed: Boolean,
        speech: Boolean,
        /**
         * 0.24 S3 Task 14: when the answer stops being on screen (the hint's own display window). The bounded wait for
         * [onSpeechFinished] never ends before this instant plus [OfficialConversation.SPEECH_LIVENESS_MILLIS] (R106:
         * long TTS may still be speaking), because the phone's TTS legitimately keeps speaking a long answer while it is
         * still displayed. Zero means "unknown"; the wait then starts at the report itself.
         */
        answerVisibleUntilNanos: Long = 0L,
    ) {
        val continueOn = continueAfterAnswer()
        var continuation: String? = null
        var cancelledTurn: String? = null
        synchronized(lock) {
            val current = state(route)
            val turnId = current.turnId ?: return
            if (current.answerDone) return
            current.answerDone = true
            cancelListenLocked(current)
            when {
                !displayed -> {
                    log("P3_CONV no_display route=${route.name} reason=not_displayed")
                    cancelledTurn = turnId
                    closeLocked(route, current)
                }
                speech -> {
                    val early = current.earlySpeech
                    current.earlySpeech = null
                    if (early != null) {
                        log("P3_CONV speech route=${route.name} result=$early early=true")
                        continuation = settleSpokenAnswerLocked(route, current, continueOn)
                    } else {
                        current.speechPending = true
                        armSpeechWaitLocked(route, current, answerVisibleUntilNanos)
                    }
                }
                continueOn -> {
                    displayStateLocked(route, current, OfficialStatus.AUDIO_FINISHED)
                    continuation = turnId
                }
                else -> armContinueOffLocked(route, current)
            }
        }
        // Outside the lock: the opener re-enters the plugin and eventually this cycle's `onWake`.
        continuation?.let { openContinuation(route, it) }
        cancelledTurn?.let { cancelTurn(route, it) }
    }

    /**
     * The phone's speech playback for the displayed answer ended.
     *
     * `FINISHED` and `FAILED` take the same branch: a playback that failed still finished, and official
     * `AudioFinishedHandler` does not distinguish them. A result that arrives for a turn whose answer-done report is
     * still in flight is **remembered** for that turn instead of being dropped - a short TTS can finish before the
     * glasses' acknowledgement reaches this state machine. A result for an already-settled answer is stale and ignored.
     */
    fun onSpeechFinished(route: AssistantRoute, result: SpeechResult) {
        val continueOn = continueAfterAnswer()
        var continuation: String? = null
        synchronized(lock) {
            val current = state(route)
            val turnId = current.turnId ?: return
            // The bounded wait exists only while the result is missing.
            cancelSpeechWaitLocked(current)
            if (!current.speechPending) {
                if (!current.answerDone) {
                    current.earlySpeech = result
                    log("P3_CONV speech route=${route.name} result=$result early=true")
                }
                return
            }
            current.speechPending = false
            current.earlySpeech = null
            log("P3_CONV speech route=${route.name} result=$result")
            continuation = settleSpokenAnswerLocked(route, current, continueOn)
        }
        continuation?.let { openContinuation(route, it) }
    }

    /**
     * The official `AudioFinishedHandler` decision once the answer's speech really ended. Returns the finished turn's
     * id when a continuation is owed, or `null` when the continue-off exit was armed instead.
     */
    private fun settleSpokenAnswerLocked(
        route: AssistantRoute,
        current: RouteState,
        continueOn: Boolean,
    ): String? = if (continueOn) {
        displayStateLocked(route, current, OfficialStatus.AUDIO_FINISHED)
        current.turnId
    } else {
        armContinueOffLocked(route, current)
        null
    }

    // ---------------------------------------------------------------- pin / STOP / close

    /** Pin is one-way: a second click is not an unpin; only a new window or a close clears it. */
    fun onPin(route: AssistantRoute) {
        synchronized(lock) {
            state(route).pinned = true
        }
    }

    /**
     * The phone's `Stop` (the glasses' double-click arrived as `UserAction.Stop`): cancel the round and show idle.
     * The panel stays — spec §5.5's "取消当前 turn、发 IDLE、不关闭面板". An explicit STOP keeps the pre-fix behavior of
     * releasing every timer, including a pending pinned close (spec §10 item 18 leaves that unproven).
     *
     * This is also the **production mapping of the glasses' pins/ANSWERING BACK**: the glasses decide the official
     * three-way matrix and send STOP for that branch, keeping the panel (Task 13); there is no phone-side BACK command.
     */
    fun onStop(route: AssistantRoute) {
        var cancelledTurn: String? = null
        synchronized(lock) {
            val current = state(route)
            if (current.turnId == null && !current.idle) return
            val turnId = current.turnId
            cancelAllTimersLocked(current)
            if (!current.idle) {
                log("P3_CONV idle route=${route.name} reason=stop")
                showIdleLocked(route, current)
                current.turnId = null
                current.awaitingPanel = false
                cancelledTurn = turnId
            }
        }
        cancelledTurn?.let { cancelTurn(route, it) }
    }

    /**
     * The panel went hidden (`UI_CLOSED`) or the session is closing. Timers are released and the pin is cleared; the
     * panel is already gone, so no `CLOSE` is sent. The current turn is cancelled because it can no longer be seen.
     */
    fun onClose(route: AssistantRoute) {
        var cancelledTurn: String? = null
        synchronized(lock) {
            val current = state(route)
            cancelledTurn = current.turnId
            cancelAllTimersLocked(current)
            resetRouteLocked(current)
        }
        cancelledTurn?.let { cancelTurn(route, it) }
    }

    /**
     * 0.3 Phase 3 Task 9d: closes the round **only if it is still [turnId]**.
     *
     * The proactive hint path pre-arms a hidden-panel round so the glasses' `PANEL_SHOWN` can arm the 10 s window
     * before the hint is submitted. When that acquisition is abandoned (a refused open, a timeout, a rejected submit)
     * the round it pre-armed must be released, but a round that was replaced by a newer attempt meanwhile belongs to
     * that attempt and must not be closed by the old one's cleanup.
     */
    fun closeIfTurn(route: AssistantRoute, turnId: String) {
        synchronized(lock) {
            val current = state(route)
            if (current.turnId != turnId) return
            closeLocked(route, current)
        }
    }

    /**
     * 0.3 Phase 3 Task 9d: releases the round **only if it is still [turnId]**, without a display call.
     *
     * A hidden-panel acquisition that was refused or timed out pre-armed a round for a panel that never reported its
     * first layout: there is no window to close, so sending `CLOSE` would be a command about a panel the phone never
     * opened. This releases the timers and the turn identity and leaves the wire alone, while [closeIfTurn] stays the
     * path for a round whose panel really was open.
     */
    fun releaseIfTurn(route: AssistantRoute, turnId: String) {
        synchronized(lock) {
            val current = state(route)
            if (current.turnId != turnId) return
            cancelAllTimersLocked(current)
            resetRouteLocked(current)
        }
    }

    /**
     * 0.3 Phase 3 Task 9d fix (review Important 1): the official [onStop] for a round **only if it is still [turnId]**.
     *
     * A hint that acquired its panel pre-armed this round, but its plugin window does not exist until the hint's
     * `Displayed` ACK. A STOP in that gap must not leave the pending round to time out into a `CLOSE`: when the panel
     * really reported its first layout the official STOP applies (idle, keep the panel, cancel only this turn); while
     * the round is still waiting for `PANEL_SHOWN` there is no panel to paint, so it is released like [releaseIfTurn].
     * The identity check is what keeps a newer attempt's round safe from the old hint's cleanup.
     */
    fun stopIfTurn(route: AssistantRoute, turnId: String) {
        var cancelledTurn: String? = null
        synchronized(lock) {
            val current = state(route)
            if (current.turnId != turnId) return
            cancelAllTimersLocked(current)
            if (current.awaitingPanel) {
                // No window is up yet: idle would be a command about a panel the phone never opened.
                resetRouteLocked(current)
            } else {
                log("P3_CONV idle route=${route.name} reason=stop")
                showIdleLocked(route, current)
                current.turnId = null
                current.awaitingPanel = false
                cancelledTurn = turnId
            }
        }
        cancelledTurn?.let { cancelTurn(route, it) }
    }

    /** Session end: release every deadline and forget every route, without calling the host (spec §5.7). */
    fun reset() {
        synchronized(lock) {
            states.values.forEach { current ->
                cancelAllTimersLocked(current)
                resetRouteLocked(current)
            }
        }
    }

    /**
     * 0.4 Task 10 (`D203`, spec §5.2): release **one** route's deadline and forget its round, leaving the other route
     * untouched.
     *
     * `Help`'s session-wide [reset] is not what a typed message needs: the typed path is a WAKE turn only, and a
     * pending PROACTIVE round (its window and its not-yet-shown hint panel) belongs to a different conversation that
     * the wearer's typing had nothing to do with.
     */
    fun reset(route: AssistantRoute) {
        synchronized(lock) {
            val current = state(route)
            cancelAllTimersLocked(current)
            resetRouteLocked(current)
        }
    }

    fun isPinned(route: AssistantRoute): Boolean = synchronized(lock) { state(route).pinned }

    /**
     * True when [route] currently owns a conversation turn (0.24 S5 final review Important #1).
     *
     * The plugin asks this before handing [onPanelShown] an event: a turn the cycle does not own (the non-wake `Help`
     * path resets the cycle and runs immediately) still owns the panel it opened, so the turn-less defensive close
     * must not treat it as an orphan.
     */
    fun hasTurn(route: AssistantRoute): Boolean = synchronized(lock) { state(route).turnId != null }

    // ---------------------------------------------------------------- deadlines

    private fun armPanelWaitLocked(route: AssistantRoute, current: RouteState) {
        cancelPanelWaitLocked(current)
        val epoch = current.panelWaitEpoch
        current.panelWaitExpiry = scheduler.at(
            deadlineAfter(PANEL_WAIT_MILLIS),
        ) { onPanelWaitTimeout(route, epoch) }
    }

    private fun armListeningLocked(route: AssistantRoute, current: RouteState) {
        cancelListenLocked(current)
        val epoch = current.listenEpoch
        current.listenExpiry = scheduler.at(
            deadlineAfter(OfficialConversation.LISTEN_TIMEOUT_MILLIS),
        ) { onListenTimeout(route, epoch) }
    }

    private fun armContinueOffLocked(route: AssistantRoute, current: RouteState) {
        cancelContinueOffLocked(current)
        val epoch = current.continueOffEpoch
        current.continueOffExpiry = scheduler.at(
            deadlineAfter(CONTINUE_OFF_CLOSE_MILLIS),
        ) { onContinueOffTimeout(route, epoch) }
    }

    /**
     * 0.24 S5 final review Important #1: the bound on a panel that was shown with no turn behind it.
     *
     * Deliberately not the listening window: that one requires a turn and would never be armed here. It is bounded
     * short because the state it repairs is already a defect - the overlay is up but nothing behind it can close it.
     */
    private fun armNoTurnCloseLocked(route: AssistantRoute, current: RouteState) {
        cancelNoTurnCloseLocked(current)
        val epoch = current.noTurnCloseEpoch
        current.noTurnCloseExpiry = scheduler.at(
            deadlineAfter(NO_TURN_PANEL_CLOSE_MILLIS),
        ) { onNoTurnCloseTimeout(route, epoch) }
    }

    private fun armPinnedCloseLocked(route: AssistantRoute, current: RouteState) {
        cancelPinnedCloseLocked(current)
        val epoch = current.pinnedCloseEpoch
        current.pinnedCloseExpiry = scheduler.at(
            deadlineAfter(OfficialConversation.PINNED_CLOSE_MILLIS),
        ) { onPinnedCloseTimeout(route, epoch) }
    }

    /**
     * 0.24 S3 Task 14 / R106: the terminal liveness bound for the missing `SpeechFinished`.
     *
     * The wait starts at the later of "now" and the answer's own display window ([answerVisibleUntilNanos]): the phone
     * may legitimately still be speaking a long answer while it is on screen, so the bound must never fire before the
     * answer stops being displayed. R106 separates this from the official 1 200 ms cue cadence: the terminal bound is
     * [OfficialConversation.SPEECH_LIVENESS_MILLIS] (120 s), because a long TTS outliving the display window is normal
     * playback, not a lost event. The wait remains bounded so a host that never reports the end still cannot leave the
     * panel ANSWERING forever.
     */
    private fun armSpeechWaitLocked(
        route: AssistantRoute,
        current: RouteState,
        answerVisibleUntilNanos: Long,
    ) {
        cancelSpeechWaitLocked(current)
        val epoch = current.speechWaitEpoch
        val anchor = maxOf(clock.nowNanos(), answerVisibleUntilNanos)
        val delta = OfficialConversation.SPEECH_LIVENESS_MILLIS * 1_000_000L
        val deadline = if (anchor > Long.MAX_VALUE - delta) Long.MAX_VALUE else anchor + delta
        current.speechWaitExpiry = scheduler.at(deadline) { onSpeechWaitTimeout(route, epoch) }
    }

    private fun cancelListenLocked(current: RouteState) {
        current.listenExpiry?.cancel()
        current.listenExpiry = null
        current.listenEpoch++
    }

    private fun cancelPanelWaitLocked(current: RouteState) {
        current.panelWaitExpiry?.cancel()
        current.panelWaitExpiry = null
        current.panelWaitEpoch++
    }

    private fun cancelContinueOffLocked(current: RouteState) {
        current.continueOffExpiry?.cancel()
        current.continueOffExpiry = null
        current.continueOffEpoch++
    }

    private fun cancelPinnedCloseLocked(current: RouteState) {
        current.pinnedCloseExpiry?.cancel()
        current.pinnedCloseExpiry = null
        current.pinnedCloseEpoch++
    }

    private fun cancelSpeechWaitLocked(current: RouteState) {
        current.speechWaitExpiry?.cancel()
        current.speechWaitExpiry = null
        current.speechWaitEpoch++
    }

    private fun cancelNoTurnCloseLocked(current: RouteState) {
        current.noTurnCloseExpiry?.cancel()
        current.noTurnCloseExpiry = null
        current.noTurnCloseEpoch++
    }

    private fun cancelAllTimersLocked(current: RouteState) {
        cancelListenLocked(current)
        cancelPanelWaitLocked(current)
        cancelContinueOffLocked(current)
        cancelPinnedCloseLocked(current)
        cancelSpeechWaitLocked(current)
        cancelNoTurnCloseLocked(current)
    }

    private fun deadlineAfter(millis: Long): Long {
        val delta = millis * 1_000_000L
        val now = clock.nowNanos()
        return if (now > Long.MAX_VALUE - delta) Long.MAX_VALUE else now + delta
    }

    private fun onPanelWaitTimeout(route: AssistantRoute, epoch: Long) {
        var cancelledTurn: String? = null
        synchronized(lock) {
            val current = state(route)
            if (current.panelWaitEpoch != epoch) return
            current.panelWaitExpiry = null
            val turnId = current.turnId ?: return
            if (!current.awaitingPanel) return
            // Spec §5.3: the phone waits only briefly for the overlay's first layout. "The panel did not come up" is
            // not the wearer's silence, so no 10 s window is armed and the listening round is closed here.
            log("P3_CONV panel_not_shown route=${route.name} turn=$turnId")
            cancelledTurn = turnId
            closeLocked(route, current)
        }
        cancelledTurn?.let { cancelTurn(route, it) }
    }

    private fun onListenTimeout(route: AssistantRoute, epoch: Long) {
        var cancelledTurn: String? = null
        synchronized(lock) {
            val current = state(route)
            if (current.listenEpoch != epoch) return
            current.listenExpiry = null
            val turnId = current.turnId ?: return
            if (current.awaitingPanel || current.idle) return
            if (current.status != OfficialStatus.LISTENING && current.status != OfficialStatus.EMPTY) return
            cancelledTurn = turnId
            if (!current.pinned) {
                log("P3_CONV close route=${route.name} reason=timeout")
                closeLocked(route, current)
            } else {
                // Official `onTimeout` with the auto-exit suppressed: `aiIdle` now, the service closes 120 s later.
                log("P3_CONV idle route=${route.name} reason=timeout")
                showIdleLocked(route, current)
                current.turnId = null
                current.awaitingPanel = false
                armPinnedCloseLocked(route, current)
            }
        }
        cancelledTurn?.let { cancelTurn(route, it) }
    }

    private fun onContinueOffTimeout(route: AssistantRoute, epoch: Long) {
        var cancelledTurn: String? = null
        synchronized(lock) {
            val current = state(route)
            if (current.continueOffEpoch != epoch) return
            current.continueOffExpiry = null
            val turnId = current.turnId ?: return
            log("P3_CONV close route=${route.name} reason=continue_off")
            cancelledTurn = turnId
            closeLocked(route, current)
        }
        cancelledTurn?.let { cancelTurn(route, it) }
    }

    /**
     * 0.24 S5 final review Important #1: no turn appeared behind the panel inside the bound.
     *
     * A turn that arrived in the meantime cancelled this deadline and owns the panel, so the re-check under the lock
     * is what keeps the close from tearing down a real conversation. The official idle panel also stays: it is a
     * legitimate visible panel, not an orphan wake.
     */
    private fun onNoTurnCloseTimeout(route: AssistantRoute, epoch: Long) {
        synchronized(lock) {
            val current = state(route)
            if (current.noTurnCloseEpoch != epoch) return
            current.noTurnCloseExpiry = null
            if (current.turnId != null || current.idle) return
            log("P3_CONV close route=${route.name} reason=no_turn_panel")
            closeLocked(route, current)
        }
    }

    private fun onPinnedCloseTimeout(route: AssistantRoute, epoch: Long) {
        var cancelledTurn: String? = null
        synchronized(lock) {
            val current = state(route)
            if (current.pinnedCloseEpoch != epoch) return
            current.pinnedCloseExpiry = null
            log("P3_CONV close route=${route.name} reason=pinned_timeout")
            cancelledTurn = current.turnId
            closeLocked(route, current)
        }
        cancelledTurn?.let { cancelTurn(route, it) }
    }

    /**
     * 0.24 S3 Task 14 / R106: no `SpeechFinished` arrived inside the liveness bound.
     *
     * The bound is [OfficialConversation.SPEECH_LIVENESS_MILLIS] measured from the later of now and the answer's display
     * window, never the official 1 200 ms cue cadence; a long TTS outliving the window is normal playback and must not
     * be cut. Reaching this bound at all means the host never confirmed the end, so spec §5.5 line 324 applies: it must
     * **not** open the next round; close the panel and record `P3_CONV no_display`. This is the one path that ends the
     * ANSWERING level without a status 5, exactly like the not-displayed branch.
     */
    private fun onSpeechWaitTimeout(route: AssistantRoute, epoch: Long) {
        var cancelledTurn: String? = null
        synchronized(lock) {
            val current = state(route)
            if (current.speechWaitEpoch != epoch) return
            current.speechWaitExpiry = null
            if (!current.speechPending) return
            val turnId = current.turnId ?: return
            current.speechPending = false
            log("P3_CONV no_display route=${route.name} reason=speech_timeout")
            cancelledTurn = turnId
            closeLocked(route, current)
        }
        cancelledTurn?.let { cancelTurn(route, it) }
    }

    // ---------------------------------------------------------------- state helpers (must hold [lock])

    private fun displayStateLocked(route: AssistantRoute, current: RouteState, status: OfficialStatus) {
        val turnId = current.turnId ?: return
        current.status = status
        current.idle = false
        val sequence = ++current.sequence
        display()?.state(route, turnId, status.wire, sequence)
    }

    private fun showIdleLocked(route: AssistantRoute, current: RouteState) {
        current.idle = true
        current.status = OfficialStatus.EMPTY
        val sequence = ++current.sequence
        display()?.showIdle(route, sequence)
    }

    private fun closeLocked(route: AssistantRoute, current: RouteState) {
        cancelAllTimersLocked(current)
        resetRouteLocked(current)
        display()?.closePanel(route)
    }

    private fun resetRouteLocked(current: RouteState) {
        current.turnId = null
        current.status = OfficialStatus.EMPTY
        current.pinned = false
        current.idle = false
        current.awaitingPanel = false
        current.answerDone = false
        current.speechPending = false
        current.earlySpeech = null
        current.asrSeen = false
    }

    private companion object {
        /** Official `AudioFinishedHandler.scheduleExit(1000L)` when continual dialogue is off. */
        const val CONTINUE_OFF_CLOSE_MILLIS = 1_000L

        /**
         * Spec §5.3's short cap on waiting for the overlay's first `PANEL_SHOWN` (initial value 3 s, to be tuned
         * after S2 measurement). A panel that never comes up closes this listening round and logs
         * `P3_CONV panel_not_shown`; it does not become the wearer's 10 s silence.
         */
        const val PANEL_WAIT_MILLIS = 3_000L

        /**
         * 0.24 S5 final review Important #1: how long a panel shown **without a turn** may stay up.
         *
         * Separate from [PANEL_WAIT_MILLIS] on purpose: that one measures the panel's acquisition for a hidden-panel
         * turn and closes the turn, while this one repairs the state where no turn exists at all. Both are short, but
         * they are different facts and must not share a deadline family.
         */
        const val NO_TURN_PANEL_CLOSE_MILLIS = 3_000L
    }
}
