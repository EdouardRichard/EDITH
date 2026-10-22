package com.sheen.rokid.phone.session.confirmation
import com.sheen.rokid.plugin.ConfirmationChannel
import com.sheen.rokid.phone.app.HostCompositionRoot
import com.sheen.rokid.phone.platform.P3Log

import com.sheen.rokid.contracts.ConfirmationResult
import com.sheen.rokid.contracts.EventEnvelope
import com.sheen.rokid.plugin.CancellationHandle
import com.sheen.rokid.plugin.DeadlineScheduler
import com.sheen.rokid.plugin.MonotonicClock
import java.util.UUID

/**
 * How the action summary reaches the wearer.
 *
 * A narrow port rather than the host's display type so the presenter is a plain JVM object: the composition adapts the
 * real CXR display to it (Task 13), and every rule below - what is shown, for how long, and what a broken display means
 * - is testable without a device.
 */
fun interface SummaryDisplayer {
    /**
     * Returns `false` when nothing could be displayed; a broken display is a degradation, never a refusal.
     *
     * [hintId] is the **confirmation's own stable identity**, minted once by the presenter when the question goes up
     * (0.3 Phase 4B, B5/B4 carry-forward). The glasses file a SHOW under `say-${hintId}`, so re-announcing the same
     * question after `PANEL_SHOWN` **replaces one row**; a display adapter that minted a fresh id per send would append
     * a duplicate confirmation row. It is passed through, never invented here.
     */
    fun show(hintId: String, text: String, ttlMillis: Long): Boolean

    /**
     * 0.3 Phase 4B (B4): whether the glasses display can receive a question right now. Defaults to `true` - a display
     * that predates the check (or whose state is unknown) keeps being used; only an explicitly unavailable surface
     * answers `false`, and a throwing check counts as unavailable for that surface.
     */
    fun available(): Boolean = true
}

/**
 * 0.3 Phase 4B (B4, spec §5.4): the narrow seam that asks the glasses to **create** the panel.
 *
 * The confirmation path used to send only `SHOW`/`CONFIRM_STATE`, but the resident panel is created by `PANEL_OPEN`;
 * a hidden window receives the two announcements invisibly, and reopening it resets the glasses' own question state.
 * The presenter therefore asks for the panel **before** it announces the question. The wire contract is unchanged: the
 * production implementation is the composition's existing `requestPanelOpen` (`PANEL_OPEN`), never a new command.
 */
fun interface ConfirmationPanel {
    /** Returns whether the open was accepted for delivery; a refused open is a degradation, never a failed question. */
    fun open(): Boolean
}

/**
 * 0.25 Task 11 (R51): how the presenter tells the glasses whether a confirmation question is up.
 *
 * A separate narrow port rather than the link itself so the presenter stays a plain JVM object and a glasses build
 * that cannot receive the command is a `false`, never an exception. The link supplies the established session and
 * generation; `false` also covers "no session introduced yet", which is the honest answer rather than an invented
 * identity.
 */
fun interface ConfirmationGlasses {
    /** Returns `false` when the glasses could not be told; a broken link is a degradation, never a failed answer. */
    fun show(requestId: String, active: Boolean): Boolean

    /**
     * 0.3 Phase 4B (B4): whether the glasses' key channel can carry a question right now (an established session).
     * Defaults to `true` so a pre-4B link keeps its behaviour; the presenter opens the panel only when this is true.
     */
    fun available(): Boolean = true
}

/** The phone-side question: a card on the session page and, when notifications are allowed, the same two actions. */
interface ConfirmationUi {
    /**
     * Puts the question on the phone, on whichever rung of the B5 ladder is actually available.
     *
     * 0.3 Phase 4B (B7): returns the surface that took the question, or `null` when no surface could - the count-only
     * `P4_CONFIRM` line must record where the wearer could really see it, and the presenter is not allowed to guess
     * between the notification and the page. A pre-B7 host that cannot tell answers `null` (a surface that predates the
     * report is still used; it just cannot contribute that one field).
     */
    fun show(requestId: String, summary: String): ConfirmationChannelKind? = null
    fun hide(requestId: String)

    /**
     * 0.3 Phase 4B (B4): whether the phone card can be put in front of the wearer right now. Defaults to `true`; a host
     * whose page/surface cannot show the card overrides this with `false` so the presenter's aggregate is honest.
     *
     * Since B5 the production implementation answers through [ConfirmationPhoneSurface]: the composition existing is
     * not the same fact as the wearer being reachable.
     */
    fun available(): Boolean = true
}

/**
 * 0.3 Phase 4B (B5, spec §5.4/D191): the channels a confirmation can use, in the owner's priority order.
 *
 * [VOICE] is named because D191 names it, but it is an **answer** channel and never a presentation fallback: spec §5.4
 * says a spoken phrase only settles a confirmation that has already been presented, so voice cannot make the question
 * visible and is deliberately never selected by [confirmationPresentationChannel].
 *
 * 0.3 Phase 4B (B7): [wire] is the token the count-only `P4_CONFIRM channel=` field carries. The enum is closed, so a
 * telemetry line can only ever name one of the surfaces the system really has.
 */
enum class ConfirmationChannelKind(val wire: String) {
    GLASSES("glasses"),
    NOTIFICATION("notification"),
    VOICE("voice"),
    DIALOG("dialog"),
}

/**
 * 0.3 Phase 4B (B7, spec §6.3): the closed decision set of the count-only `P4_CONFIRM` line.
 *
 * The plugin's gate owns what a settlement means, so it reports one of these words through
 * [ConfirmationChannel.settle]; the host only renders it. `unavailable` is the one decision the **host** also takes on
 * its own (a question no surface could take, or a channel that cannot ask at all).
 */
internal enum class ConfirmationDecision(val wire: String) {
    ALLOW("allow"),
    DENY("deny"),
    TIMEOUT("timeout"),
    UNAVAILABLE("unavailable"),
    ;

    companion object {
        /** The only words accepted from a plugin; anything else is ignored rather than written to the log. */
        fun fromWire(value: String): ConfirmationDecision? = entries.firstOrNull { it.wire == value }
    }
}

/**
 * The channel a question that is not yet visible should go to next: glasses (panel + keys) -> notification action ->
 * the Edith page as the last fallback. `null` means no channel can present it, which is what the loop's early refusal
 * is for.
 *
 * Only available channels participate: each flag is a **live** availability answer. Voice is not a parameter for the
 * reason on [ConfirmationChannelKind.VOICE] - listing it as a fallback would let the host count a question as presented
 * while nothing was ever put on a screen.
 */
internal fun confirmationPresentationChannel(
    glassesServing: Boolean,
    notificationAvailable: Boolean,
    dialogAvailable: Boolean,
): ConfirmationChannelKind? = when {
    glassesServing -> ConfirmationChannelKind.GLASSES
    notificationAvailable -> ConfirmationChannelKind.NOTIFICATION
    dialogAvailable -> ConfirmationChannelKind.DIALOG
    else -> null
}

/**
 * 0.3 Phase 4B (B5, spec §5.4): whether the **phone** can actually put a confirmation in front of the wearer.
 *
 * Three independent, live ways, in the spirit of the channel ladder: the session page is on screen (it owns the
 * modal), the page can be raised over another App (overlay permission), or the high-priority notification can be
 * posted. Any one is enough. Before B5 the production `ConfirmationUi.available()` answered "this composition exists",
 * which is true for the whole session - so the presenter never reported unavailable, AgentLoop's early
 * `CONFIRMATION_UNAVAILABLE` path was unreachable, and a locked/background phone with notifications off still spent
 * D47's 120 s window. This is the pure rule; the composition supplies the three live answers.
 */
internal object ConfirmationPhoneSurface {
    fun available(pageVisible: Boolean, canRaisePage: Boolean, notificationsAvailable: Boolean): Boolean =
        pageVisible || canRaisePage || notificationsAvailable
}

/**
 * Presents a confirmation question on both screens and turns the phone's tap into the event that settles it
 * (spec §17.4).
 *
 * The authority split is the design:
 * - the **plugin's** `ConfirmationGate` decides what an unanswered question means (deny), on its own monotonic clock;
 * - the **host** only shows the question, arms a matching timer to take it down, and delivers the tap. Its timer
 *   therefore emits **nothing**: a phone that went to sleep must not become an approval, and the gate's timeout will
 *   settle the question on its own.
 *
 * The summary is truncated to [MAX_SUMMARY_CHARS] because it is rendered by the glasses hint path, which clips beyond
 * its budget (P3.2's capacity measurement: ~72 visible characters). Truncation is visible (`…`) rather than silent.
 */
class ConfirmationPresenter(
    private val summary: SummaryDisplayer,
    private val ui: ConfirmationUi,
    private val deliver: (ConfirmationResult) -> Unit,
    private val clock: MonotonicClock,
    private val scheduler: DeadlineScheduler,
    private val envelopeFor: (String) -> EventEnvelope,
    /**
     * 0.25 Task 11 (R51): the glasses' key channel. Optional by default so a host without a glasses link (and every
     * test that predates the dual channel) keeps its exact behaviour; a question that cannot be announced there is
     * still fully answerable on the phone.
     */
    private val glasses: ConfirmationGlasses = ConfirmationGlasses { _, _ -> false },
    /**
     * 0.3 Phase 4B (B4, spec §5.4): the panel-creation seam (the composition's `requestPanelOpen` -> `PANEL_OPEN`).
     *
     * A no-op by default, so every pre-4B construction (and every host without a glasses link) is unchanged. When the
     * glasses surface can serve, [present] asks for the panel **before** it sends `SHOW`/`CONFIRM_STATE`, because a
     * hidden resident window receives neither visibly.
     */
    private val panel: ConfirmationPanel = ConfirmationPanel { false },
    /**
     * 0.3 Phase 4B (B7, spec §6.3): where the count-only `P4_CONFIRM` lines go. The default is the phone's single `P3_`
     * sink (logcat in production), so the production composition needs no new argument; a JVM test installs a collector.
     *
     * Only counts and closed tokens are ever formatted here - the tool's wire name, a decision word and a channel word -
     * never the question's summary or an argument. Telemetry is never a verdict: a throwing sink must not turn a
     * settlement into a failure, so every emit is wrapped.
     */
    private val log: (String) -> Unit = { P3Log.line(it) },
) : ConfirmationChannel {

    private class Pending(
        val requestId: String,
        /**
         * 0.3 Phase 4B (B5/B4 carry-forward): the **question's** stable identity on the glasses hint path, minted once
         * here and reused by every announcement of the same question, so a re-send replaces `say-${hintId}` instead of
         * appending a second confirmation row.
         */
        val hintId: String,
        val summary: String,
        /**
         * 0.3 Phase 4B (B7): the action's wire name, carried only for the count-only `P4_CONFIRM` line. It is never
         * shown and never used to address the question; [UNKNOWN_TOOL] keeps a pre-B7 caller honest without inventing
         * a name.
         */
        val tool: String,
        val envelope: EventEnvelope,
        /** When the plugin's window closes, so a re-announcement carries a fresh, honest TTL rather than a new one. */
        val deadlineNanos: Long,
    ) {
        var timer: CancellationHandle? = null

        /**
         * Whether the glasses channel actually took the question: the SHOW was accepted **and** the keys were armed.
         * The composition's fallback ladder reads this; a display that refused the text must not suppress the
         * notification or the Edith fallback.
         */
        var servingOnGlasses = false

        /**
         * 0.3 Phase 4B (B7, spec §5.4): where the question was really put in front of the wearer, captured **when it
         * went up** - a settlement cannot ask the screen afterwards (the surfaces are being torn down at that moment).
         * `null` means no surface took it. Guarded by the presenter's lock.
         */
        var channel: ConfirmationChannelKind? = null

        /**
         * B7: whether this question's one `P4_CONFIRM` line has already been written. Exactly one line per presented
         * question, whichever settlement path wins the race (an answer, the plugin's gate, or this host's own timer).
         * Guarded by the presenter's lock.
         */
        var reported = false

        /** A second `present` with a live id gets this no-op handle: it never displaces the question already up. */
        val ignoredBy = object : CancellationHandle { override fun cancel() = Unit }
    }

    private val lock = Any()
    private val pending = mutableMapOf<String, Pending>()

    /** How many questions are up; 0 or 1 in practice, exposed so the invariant is testable. */
    val pendingCount: Int get() = synchronized(lock) { pending.size }

    /**
     * 0.3 Phase 4B (B4/B5, spec §5.4): whether **any** of this presenter's surfaces can put the question to the wearer.
     *
     * The answer paths are aggregated because the wearer may answer on any of them: the glasses (SHOW **and** the
     * CONFIRM_STATE keys - a key map with no displayed question is not an answerable surface, so the keys alone do not
     * count), or the phone card/dialog. Each check is live and a throwing one counts as unavailable **for that surface
     * only** - a broken display must not hide a working phone card. The panel is not aggregated: opening it is an aid
     * to the glasses surface, not an answer path by itself.
     *
     * B5: the production phone answer now comes from [ConfirmationPhoneSurface] rather than "the composition exists",
     * which is what makes the no-surface case (`CONFIRMATION_UNAVAILABLE`) reachable without a device.
     */
    override fun available(): Boolean = uiCanServe() || (summaryCanServe() && glassesCanServe())

    /**
     * 0.3 Phase 4B (B5/B4 carry-forward): puts the same question up without a tool name. Kept because it is the
     * interface's original contract; the B7 count-only line records [UNKNOWN_TOOL] rather than inventing a name.
     */
    override fun present(requestId: String, summary: String, timeoutMillis: Long): CancellationHandle =
        present(requestId, summary, timeoutMillis, tool = UNKNOWN_TOOL)

    /**
     * 0.3 Phase 4B (B7): the question plus the action's wire name, which only the `P4_CONFIRM` line reads.
     *
     * The presentation channel is captured **here**, from what each surface reports it really took (glasses keys,
     * the composition's notification/dialog rung), and stored on the pending question: by settlement time the screens
     * are being torn down and the truth is no longer askable. A question no surface took is the one present-time
     * decision this method takes on its own: `unavailable`/`none`, written once.
     */
    override fun present(requestId: String, summary: String, timeoutMillis: Long, tool: String): CancellationHandle {
        require(requestId.isNotBlank()) { "confirmation request id must not be blank" }
        require(timeoutMillis > 0) { "confirmation window must be positive, was $timeoutMillis" }
        val text = truncate(summary)
        val deadlineNanos = clock.nowNanos() + timeoutMillis * 1_000_000L
        // A question that is **already up** keeps its slot and does not even ask for an identity it will not use. (A
        // duplicate that lands between this check and the registration below still reads one identity and throws it
        // away; that is the deliberate price of not holding our lock across `envelopeFor`.)
        synchronized(lock) { pending[requestId]?.let { return it.ignoredBy } }
        // The identity is read **while the question goes up** - on the turn's own thread - and never on the answer path:
        // `answer` runs on the session page's UI thread, and a host round trip there is the same stack frame the P3.3
        // ANR was caught in (`HostCompositionRoot.answerConfirmation` -> `ConfirmationPresenter.answer` ->
        // `HostRuntime.command`). A question therefore carries the session that asked it, and the answer names that
        // session. (The composition's own `SummaryDisplayer` still reads the host identity once per question for the
        // glasses hint: a second round trip, but on this same turn thread, never on the UI thread.) Deliberately
        // outside the lock: `envelopeFor` reaches back into the composition, and no lock of ours may be held across that.
        val envelope = envelopeFor(requestId)
        // B5/B4 carry-forward: one id per question, reused by the re-announcement on PANEL_SHOWN. Minted here (not per
        // send) so the glasses replace `say-${hintId}` instead of appending a second row when the panel appears late.
        val hintId = "confirm-${UUID.randomUUID()}"
        val entry = Pending(requestId, hintId, text, tool, envelope, deadlineNanos)
        synchronized(lock) {
            val live = pending[requestId]
            if (live != null) return live.ignoredBy   // lost the race for this id: the live question keeps it
            pending[requestId] = entry
        }

        // 0.3 Phase 4B (B4): the panel is created by PANEL_OPEN, not by SHOW. Ask for it first when the glasses surface
        // can serve - a hidden window would otherwise receive the two announcements invisibly, and the panel's own
        // `resetForOpen` would erase them as it comes up. A refused open is a degradation, never a failed question.
        val glassesAvailable = glassesCanServe()
        if (glassesAvailable) {
            runCatching { panel.open() }
        }
        // 0.3 Phase 4B (B5, spec §5.4): only available channels participate, in priority order. The glasses go first;
        // the composition's own ladder (notification, then the Edith page) runs when it is told the question is up, and
        // reads [isServingOnGlasses] so a refused SHOW cannot suppress the fallback. Neither failure is fatal: a
        // question that cannot be displayed anywhere is still settled by the gate's timeout (as a denial).
        val displayAccepted = summaryCanServe() &&
            runCatching { this.summary.show(hintId, text, timeoutMillis + SUMMARY_MARGIN_MILLIS) }.getOrDefault(false)
        val keysAccepted = displayAccepted && glassesAvailable && tellGlasses(requestId, active = true)
        synchronized(lock) { if (pending[requestId] === entry) entry.servingOnGlasses = keysAccepted }
        // B7: the composition reports which rung it really used (notification or page); `null` is "no surface took it".
        // The glasses are read from this presenter's own SHOW+keys answer instead, so the two can never disagree about
        // which surface can settle the question.
        val uiChannel = if (uiCanServe()) runCatching { ui.show(requestId, text) }.getOrNull() else null
        val presentedOn = if (keysAccepted) ConfirmationChannelKind.GLASSES else uiChannel
        synchronized(lock) { if (pending[requestId] === entry) entry.channel = presentedOn }

        val timer = scheduler.at(deadlineNanos) { withdraw(requestId, ConfirmationDecision.TIMEOUT) }
        synchronized(lock) { pending[requestId]?.timer = timer }
        // No surface took the question: the one present-time decision this side owns. The question still exists (the
        // plugin's gate owns its window), so `reported` stops its later settlement from writing a second line.
        if (presentedOn == null) emitConfirm(entry, ConfirmationDecision.UNAVAILABLE)
        return object : CancellationHandle { override fun cancel() = withdraw(requestId) }
    }

    /**
     * 0.3 Phase 4B (B5): whether the glasses channel has the live question — the display accepted the summary **and**
     * the keys were armed. The composition's priority ladder reads this to decide whether the notification or the
     * Edith page still has to run; `false` also covers an unknown/settled id.
     */
    fun isServingOnGlasses(requestId: String): Boolean =
        synchronized(lock) { pending[requestId]?.servingOnGlasses == true }

    /**
     * 0.3 Phase 4B (B4, spec §5.4): the glasses reported that the panel's first layout completed.
     *
     * The panel may have been created **after** [present] already sent `SHOW`/`CONFIRM_STATE` (a cold `appStart`, or a
     * resident window that was hidden). Its first layout is therefore the moment to announce the same question again:
     * repeat `SHOW` and the active `CONFIRM_STATE` for every question still pending.
     *
     * This is an announcement, **not** a settlement: an unsettled question keeps its slot (only an answer, a timeout or
     * an explicit withdrawal removes one), so reopening the panel can never silently drop the question the plugin is
     * blocked on. The repeat deliberately does not raise the phone card a second time.
     *
     * 0.3 Phase 4B (B5/B4 carry-forward): the repeat **reuses the question's hint id** ([Pending.hintId]), so the panel
     * replaces its one row instead of appending another "确认" row.
     */
    fun panelShownArrived() {
        val live = synchronized(lock) { pending.values.toList() }
        if (live.isEmpty()) return
        live.forEach { entry ->
            val remainingMillis = synchronized(lock) {
                // A settlement racing this pass must not leave a withdrawn question armed on the glasses.
                if (pending[entry.requestId] !== entry) return@forEach
                // The TTL is the question's **remaining** window plus the usual margin, not a second full window.
                ((entry.deadlineNanos - clock.nowNanos()) / 1_000_000L).coerceAtLeast(0L) +
                    SUMMARY_MARGIN_MILLIS
            }
            // The glasses surface may have become unusable while the panel was opening; only an available surface is
            // allowed to announce (B5: "only available channels participate").
            if (!summaryCanServe() || !glassesCanServe()) return@forEach
            val accepted = runCatching { summary.show(entry.hintId, entry.summary, remainingMillis) }
                .getOrDefault(false)
            if (!accepted) return@forEach
            tellGlasses(entry.requestId, active = true)
            synchronized(lock) { if (pending[entry.requestId] === entry) entry.servingOnGlasses = true }
        }
    }

    /**
     * The phone's answer. `true` only when this settles the question that is actually up, so a stale tap - one that
     * arrives after a timeout - delivers nothing.
     *
     * 0.3 Phase 4B (B7): this is also where a tap/ENTER/BACK settlement is recorded - the question is already off the
     * screens by the time the plugin's gate hears about it, so the presenter writes its one `P4_CONFIRM` line here.
     * The channel is the one captured when the question went up; the answer's own surface is not re-derived.
     */
    fun answer(requestId: String, approved: Boolean): Boolean {
        val live = synchronized(lock) { pending.remove(requestId) } ?: return false
        live.timer?.cancel()
        runCatching { ui.hide(requestId) }
        tellGlasses(requestId, active = false)
        emitConfirm(live, if (approved) ConfirmationDecision.ALLOW else ConfirmationDecision.DENY)
        deliver(ConfirmationResult(live.envelope, requestId, approved))
        return true
    }

    /**
     * 0.25 Task 11 (R51): the glasses' ENTER/BACK answer.
     *
     * Deliberately the **same** path as [answer] - one remove from `pending`, one `ConfirmationResult`, one delivery -
     * so which screen wins a race cannot change what the plugin is told. A second answer for the same id settles
     * nothing and sends the glasses no second state.
     */
    fun answerFromGlasses(requestId: String, approved: Boolean): Boolean = answer(requestId, approved)

    /**
     * 0.3 Phase 4B (B7): the plugin's settlement, reported by [ConfirmationChannel.settle].
     *
     * The plugin's gate is the authority on a timeout, a STOP/cancellation and a voice answer, and it knows which of
     * those happened; the host knows where the question was shown. This method joins the two: it takes the question
     * down (the returned handle's `cancel` will then find nothing, so no settlement is repeated) and writes the one
     * count-only line, using the captured presentation channel unless the answer arrived on `voice`.
     *
     * It deliberately does **not** deliver a `ConfirmationResult`: the plugin already settled the question, and a
     * second answer would be a fabricated wearer decision. An unknown decision word or an unknown request id returns
     * `false` and writes nothing.
     */
    override fun settle(requestId: String, decision: String, answerChannel: String?): Boolean {
        val outcome = ConfirmationDecision.fromWire(decision) ?: return false
        val live = synchronized(lock) { pending.remove(requestId) } ?: return false
        live.timer?.cancel()
        runCatching { ui.hide(requestId) }
        tellGlasses(requestId, active = false)
        // Only a closed answer channel is honoured: a plugin cannot smuggle arbitrary text into a log line.
        emitConfirm(live, outcome, answerChannel?.takeIf { it == ConfirmationChannelKind.VOICE.wire })
        return true
    }

    /**
     * Takes a question off both screens without answering it: used by the plugin's withdrawal, by the timer, by 急停 and
     * by session teardown.
     *
     * [decision] is non-null only where the withdrawal is itself a settlement the host can honestly name: its own
     * window closing (`timeout`) or an explicit 急停/teardown (`deny`, which is how the plugin's gate reports a
     * cancellation). The plugin's own settlement path calls [settle] first, so the handle's bare `cancel` stays silent
     * and one question can never produce two lines.
     */
    private fun withdraw(requestId: String, decision: ConfirmationDecision? = null) {
        val live = synchronized(lock) { pending.remove(requestId) } ?: return
        live.timer?.cancel()
        runCatching { ui.hide(requestId) }
        tellGlasses(requestId, active = false)
        if (decision != null) emitConfirm(live, decision)
    }

    /** Withdraws every open question; called when the session ends so a later tap cannot answer a dead turn. */
    fun withdrawAll() {
        synchronized(lock) { pending.keys.toList() }.forEach { withdraw(it, ConfirmationDecision.DENY) }
    }

    /**
     * 0.3 Phase 4B (B7, spec §6.3): writes the question's one count-only `P4_CONFIRM` line.
     *
     * Field order is the contract: `P4_CONFIRM tool=<name> decision=<allow|deny|timeout|unavailable> channel=<...>`.
     * The tool is the action's wire name, the decision a closed word, the channel the surface captured at present time
     * (or `none`). The `reported` flag is claimed under the lock **before** the sink runs, so two settlement paths that
     * race still write exactly one line; a throwing sink is swallowed because telemetry is never a verdict.
     */
    private fun emitConfirm(
        entry: Pending,
        decision: ConfirmationDecision,
        answerChannel: String? = null,
    ) {
        synchronized(lock) {
            if (entry.reported) return
            entry.reported = true
        }
        val channel = answerChannel ?: entry.channel?.wire ?: NO_CHANNEL
        runCatching { log("P4_CONFIRM tool=${entry.tool} decision=${decision.wire} channel=$channel") }
    }

    private fun truncate(summary: String): String {
        val collapsed = summary.replace('\n', ' ').replace('\r', ' ').trim()
        return if (collapsed.length <= MAX_SUMMARY_CHARS) collapsed
        else collapsed.take(MAX_SUMMARY_CHARS - 1) + "…"
    }

    /** One glasses state change; `true` when the link accepted it (a refused/failed send is only a degradation). */
    private fun tellGlasses(requestId: String, active: Boolean): Boolean =
        runCatching { glasses.show(requestId, active) }.getOrDefault(false)

    /**
     * 0.3 Phase 4B (B4): whether the glasses surface can carry the question now. A throwing check is `false`: the panel
     * is an aid, and a broken availability probe must not be treated as proof that a panel exists to open.
     */
    private fun glassesCanServe(): Boolean = runCatching { glasses.available() }.getOrDefault(false)

    /** 0.3 Phase 4B (B5): a throwing probe is unavailable for **that surface only**. */
    private fun summaryCanServe(): Boolean = runCatching { summary.available() }.getOrDefault(false)

    /** 0.3 Phase 4B (B5): a throwing probe is unavailable for **that surface only**. */
    private fun uiCanServe(): Boolean = runCatching { ui.available() }.getOrDefault(false)

    companion object {
        /** The glasses hint path clips beyond its budget; a confirmation summary is short by construction. */
        const val MAX_SUMMARY_CHARS = 60

        /** The summary stays up a little past the window, so the question never vanishes before the deadline. */
        const val SUMMARY_MARGIN_MILLIS = 2_000L

        /**
         * 0.3 Phase 4B (B7): the `tool=` token of a question presented through the original 3-arg path. An honest
         * "the caller did not name the action", never an invented tool name.
         */
        const val UNKNOWN_TOOL = "unknown"

        /** B7: the `channel=` token of a question no surface took. */
        const val NO_CHANNEL = "none"
    }
}
