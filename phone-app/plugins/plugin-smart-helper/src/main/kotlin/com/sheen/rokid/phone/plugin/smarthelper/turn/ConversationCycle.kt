package com.sheen.rokid.phone.plugin.smarthelper.turn

import com.sheen.rokid.contracts.AssistantRoute
import com.sheen.rokid.contracts.SpeechResult
import com.sheen.rokid.output.BackAction
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
 *   wearer's silence (spec §5.3).
 * - `AsrMessageHandler` removes the timeout **before** it checks whether the ASR payload is blank; every later status
 *   (2/4/3/7) therefore has no timer at all.
 * - `AudioFinishedHandler`: continue on → status 5 and a new listening round (10 s); continue off →
 *   `scheduleExit(1000)` with **no** status 5 and no new turn.
 * - `RokidAIController.onTimeout` + `showIdleStatus`: a pinned timeout idles the panel (not status 0, not a close) and
 *   the service closes it **120 s** later ([OfficialConversation.PINNED_CLOSE_MILLIS]).
 * - `AiAssistantDialog.onKeyUp`: BACK exits unless pinned while answering, where it interrupts to idle.
 *
 * The class owns only state and deadlines: it never touches the wire, and the plugin supplies the display port, the
 * continuation opener and turn cancellation. That is what lets the whole matrix run on a JVM with a manual clock.
 *
 * Threading: the host delivers events on one dispatcher thread while the turn's answer is assembled on the task pool,
 * so every state transition is serialized by [lock]. The callbacks that can re-enter the plugin
 * ([openContinuation], [cancelTurn]) are invoked **outside** the lock; the display is a fire-and-forget host port and
 * is called under the lock so each level carries a monotonically increasing sequence.
 */
internal class ConversationCycle(
    private val clock: MonotonicClock,
    private val scheduler: DeadlineScheduler,
    private val display: () -> ConversationDisplay?,
    private val continueDialogue: () -> Boolean,
    private val openContinuation: (turnId: String) -> Unit,
    /**
     * Whether the panel is visible right now. A turn that opens while it is already up must start its 10 s window
     * immediately rather than wait for a `PANEL_SHOWN` the glasses will not send again (spec §5.3; Task 11 exposes the
     * link's read-only state for exactly this).
     */
    private val isPanelVisible: () -> Boolean = { false },
    /** Cancels the plugin's current turn for [route]; called when this cycle ends the conversation. */
    private val cancelTurn: (AssistantRoute) -> Unit = {},
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

        var listenExpiry: CancellationHandle? = null
        var closeExpiry: CancellationHandle? = null

        /**
         * Invalidates every already-scheduled callback for this route. A cancelled [CancellationHandle] is not
         * guaranteed to prevent an already-queued task from running, so each callback checks the epoch it was armed
         * under before touching the state.
         */
        var timerEpoch = 0L

        var answerDone = false
        var speechPending = false

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
     * A wake opened a turn. With the panel already up the official 10 s starts now; with the panel hidden it waits
     * for [onPanelShown], because "the dialog opened" is what starts the official timeout and the overlay's own start
     * latency must not be counted against the wearer (spec §5.3).
     *
     * A new round is a new window: the official pin is one-way **within** a window and is reset by a new one
     * (`resetDialogUiState`/`clearAll`).
     */
    fun onWake(route: AssistantRoute, turnId: String) {
        val visible = isPanelVisible()
        synchronized(lock) {
            val current = state(route)
            cancelTimersLocked(current)
            current.turnId = turnId
            current.status = OfficialStatus.EMPTY
            current.idle = false
            current.pinned = false
            current.awaitingPanel = !visible
            current.answerDone = false
            current.speechPending = false
            current.asrSeen = false
            if (visible) {
                displayStateLocked(route, current, OfficialStatus.LISTENING)
                armListeningLocked(route, current)
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
            val turnId = current.turnId ?: return
            if (current.idle) return
            if (current.status != OfficialStatus.LISTENING && current.status != OfficialStatus.EMPTY) return
            val first = current.awaitingPanel
            current.awaitingPanel = false
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
     */
    fun onAsr(route: AssistantRoute) {
        synchronized(lock) {
            val current = state(route)
            current.asrSeen = true
            cancelTimersLocked(current)
        }
    }

    /**
     * A status level the plugin decided to display (2, 4, 3, 7, ...).
     *
     * Status 1 is normally [onWake]'s; a caller may still use it to restart a listening level. Every non-listening
     * level has **no** timer (spec §5.3: "status 2/3/4/7 不设计时"), which is also what makes an ASR message cancel
     * the window even before the answer exists.
     */
    fun onStatus(route: AssistantRoute, status: OfficialStatus) {
        synchronized(lock) {
            val current = state(route)
            if (current.turnId == null) return
            if (status == OfficialStatus.LISTENING || status == OfficialStatus.EMPTY) {
                displayStateLocked(route, current, status)
                if (!current.asrSeen) armListeningLocked(route, current)
            } else {
                cancelTimersLocked(current)
                displayStateLocked(route, current, status)
            }
        }
    }

    /**
     * The ASR final adopted its own turn id; the wake placeholder is no longer the identity the wire should carry.
     *
     * Deliberately not [onWake]: adopting the id must not restart the window or reset the pin. A blank id is ignored
     * because a legacy producer may have none.
     */
    fun onTurnId(route: AssistantRoute, turnId: String) {
        if (turnId.isBlank()) return
        synchronized(lock) {
            state(route).turnId = turnId
        }
    }

    // ---------------------------------------------------------------- answer completion

    /**
     * The answer reached the wearer ([displayed] is the host's `Displayed` acknowledgement).
     *
     * Official `AudioFinishedHandler`:
     * - continue on → status 5, then a new listening round (the plugin's [openContinuation]);
     * - continue off → `scheduleExit(1000)`, no status 5, no new turn;
     * - not displayed → the answer the next round would continue does not exist, so close and open nothing.
     *
     * When [speech] is true the answer is still playing, so the decision waits for [onSpeechFinished] exactly as the
     * official TTS path does.
     */
    fun onAnswerDone(route: AssistantRoute, displayed: Boolean, speech: Boolean) {
        val continueOn = continueDialogue()
        var continuation: String? = null
        var cancelled: AssistantRoute? = null
        synchronized(lock) {
            val current = state(route)
            val turnId = current.turnId ?: return
            if (current.answerDone) return
            current.answerDone = true
            cancelTimersLocked(current)
            when {
                !displayed -> {
                    log("P3_CONV close route=${route.name} reason=no_display")
                    closeLocked(route, current)
                    cancelled = route
                }
                speech -> current.speechPending = true
                continueOn -> {
                    displayStateLocked(route, current, OfficialStatus.AUDIO_FINISHED)
                    continuation = turnId
                }
                else -> {
                    log("P3_CONV close route=${route.name} reason=continue_off")
                    armCloseLocked(route, current, CONTINUE_OFF_CLOSE_MILLIS, "continue_off")
                }
            }
        }
        // Outside the lock: the opener re-enters the plugin and eventually this cycle's `onWake`.
        continuation?.let(openContinuation)
        cancelled?.let(cancelTurn)
    }

    /**
     * The phone's speech playback for the displayed answer ended.
     *
     * `FINISHED` and `FAILED` take the same branch: a playback that failed still finished, and official
     * `AudioFinishedHandler` does not distinguish them. A callback with no pending spoken answer is stale and ignored.
     */
    fun onSpeechFinished(route: AssistantRoute, result: SpeechResult) {
        val continueOn = continueDialogue()
        var continuation: String? = null
        synchronized(lock) {
            val current = state(route)
            if (!current.speechPending) return
            val turnId = current.turnId ?: return
            current.speechPending = false
            cancelTimersLocked(current)
            log("P3_CONV speech route=${route.name} result=$result")
            if (continueOn) {
                displayStateLocked(route, current, OfficialStatus.AUDIO_FINISHED)
                continuation = turnId
            } else {
                log("P3_CONV close route=${route.name} reason=continue_off")
                armCloseLocked(route, current, CONTINUE_OFF_CLOSE_MILLIS, "continue_off")
            }
        }
        continuation?.let(openContinuation)
    }

    // ---------------------------------------------------------------- pin / BACK / close

    /** Pin is one-way: a second click is not an unpin; only a new window or a close clears it. */
    fun onPin(route: AssistantRoute) {
        synchronized(lock) {
            state(route).pinned = true
        }
    }

    /**
     * The glasses' BACK key, as the official three-way matrix.
     *
     * `OfficialConversation.backAction` is the one authority: pinned + ANSWERING stops to idle and keeps the panel;
     * everything else exits and closes. Unlike the pin timeout, STOP does **not** arm the 120 s close — the official
     * service-side delayed exit for `sendExitToService(2)` has not been confirmed on device (spec §10 item 18).
     */
    fun onBack(route: AssistantRoute) {
        var cancelled: AssistantRoute? = null
        synchronized(lock) {
            val current = state(route)
            if (current.turnId == null && !current.idle) return
            cancelTimersLocked(current)
            when (OfficialConversation.backAction(current.status, current.pinned)) {
                BackAction.STOP -> {
                    log("P3_CONV idle route=${route.name} reason=back_stop")
                    showIdleLocked(route, current)
                    current.turnId = null
                    current.awaitingPanel = false
                }
                BackAction.EXIT -> {
                    log("P3_CONV close route=${route.name} reason=back_exit")
                    closeLocked(route, current)
                }
            }
            cancelled = route
        }
        cancelled?.let(cancelTurn)
    }

    /**
     * The phone's `Stop` (the glasses' double-click arrived as `UserAction.Stop`): cancel the round and show idle.
     * The panel stays — spec §5.5's "取消当前 turn、发 IDLE、不关闭面板".
     */
    fun onStop(route: AssistantRoute) {
        var cancelled: AssistantRoute? = null
        synchronized(lock) {
            val current = state(route)
            if (current.turnId == null && !current.idle) return
            cancelTimersLocked(current)
            if (!current.idle) {
                log("P3_CONV idle route=${route.name} reason=stop")
                showIdleLocked(route, current)
                current.turnId = null
                current.awaitingPanel = false
            }
            cancelled = route
        }
        cancelled?.let(cancelTurn)
    }

    /**
     * The panel went hidden (`UI_CLOSED`) or the session is closing. Timers are released and the pin is cleared; the
     * panel is already gone, so no `CLOSE` is sent. The current turn is cancelled because it can no longer be seen.
     */
    fun onClose(route: AssistantRoute) {
        var cancelled: AssistantRoute? = null
        synchronized(lock) {
            val current = state(route)
            val hadConversation = current.turnId != null || current.idle || current.pinned || current.awaitingPanel
            cancelTimersLocked(current)
            resetRouteLocked(current)
            if (hadConversation) cancelled = route
        }
        cancelled?.let(cancelTurn)
    }

    /** Session end: release every deadline and forget every route, without calling the host (spec §5.7). */
    fun reset() {
        synchronized(lock) {
            states.values.forEach { current ->
                cancelTimersLocked(current)
                resetRouteLocked(current)
            }
        }
    }

    fun isPinned(route: AssistantRoute): Boolean = synchronized(lock) { state(route).pinned }

    // ---------------------------------------------------------------- deadlines

    private fun armListeningLocked(route: AssistantRoute, current: RouteState) {
        current.closeExpiry?.cancel()
        current.closeExpiry = null
        current.listenExpiry?.cancel()
        val epoch = ++current.timerEpoch
        current.listenExpiry = scheduler.at(
            deadlineAfter(OfficialConversation.LISTEN_TIMEOUT_MILLIS),
        ) { onListenTimeout(route, epoch) }
    }

    private fun armCloseLocked(route: AssistantRoute, current: RouteState, delayMillis: Long, reason: String) {
        current.listenExpiry?.cancel()
        current.listenExpiry = null
        current.closeExpiry?.cancel()
        val epoch = ++current.timerEpoch
        current.closeExpiry = scheduler.at(deadlineAfter(delayMillis)) { onCloseTimeout(route, epoch, reason) }
    }

    private fun deadlineAfter(millis: Long): Long {
        val delta = millis * 1_000_000L
        val now = clock.nowNanos()
        return if (now > Long.MAX_VALUE - delta) Long.MAX_VALUE else now + delta
    }

    private fun onListenTimeout(route: AssistantRoute, epoch: Long) {
        var cancelled: AssistantRoute? = null
        synchronized(lock) {
            val current = state(route)
            if (current.timerEpoch != epoch) return
            current.listenExpiry = null
            current.turnId ?: return
            if (current.awaitingPanel || current.idle) return
            if (current.status != OfficialStatus.LISTENING && current.status != OfficialStatus.EMPTY) return
            cancelled = route
            if (!current.pinned) {
                log("P3_CONV close route=${route.name} reason=timeout")
                closeLocked(route, current)
            } else {
                // Official `onTimeout` with the auto-exit suppressed: `aiIdle` now, the service closes 120 s later.
                log("P3_CONV idle route=${route.name} reason=timeout")
                showIdleLocked(route, current)
                current.turnId = null
                current.awaitingPanel = false
                armCloseLocked(route, current, OfficialConversation.PINNED_CLOSE_MILLIS, "pinned_timeout")
            }
        }
        cancelled?.let(cancelTurn)
    }

    private fun onCloseTimeout(route: AssistantRoute, epoch: Long, reason: String) {
        var expired = false
        synchronized(lock) {
            val current = state(route)
            if (current.timerEpoch != epoch) return
            current.closeExpiry = null
            log("P3_CONV close route=${route.name} reason=$reason")
            closeLocked(route, current)
            expired = true
        }
        if (expired) cancelTurn(route)
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
        cancelTimersLocked(current)
        resetRouteLocked(current)
        display()?.closePanel(route)
    }

    private fun cancelTimersLocked(current: RouteState) {
        current.listenExpiry?.cancel()
        current.listenExpiry = null
        current.closeExpiry?.cancel()
        current.closeExpiry = null
        current.timerEpoch++
    }

    private fun resetRouteLocked(current: RouteState) {
        current.turnId = null
        current.status = OfficialStatus.EMPTY
        current.pinned = false
        current.idle = false
        current.awaitingPanel = false
        current.answerDone = false
        current.speechPending = false
        current.asrSeen = false
    }

    private companion object {
        /** Official `AudioFinishedHandler.scheduleExit(1000L)` when continual dialogue is off. */
        const val CONTINUE_OFF_CLOSE_MILLIS = 1_000L
    }
}
