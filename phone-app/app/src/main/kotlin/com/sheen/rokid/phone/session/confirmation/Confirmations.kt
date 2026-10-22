package com.sheen.rokid.phone.session.confirmation
import com.sheen.rokid.plugin.ConfirmationChannel
import com.sheen.rokid.phone.app.HostCompositionRoot

import com.sheen.rokid.contracts.ConfirmationResult
import com.sheen.rokid.contracts.EventEnvelope
import com.sheen.rokid.plugin.CancellationHandle
import com.sheen.rokid.plugin.DeadlineScheduler
import com.sheen.rokid.plugin.MonotonicClock

/**
 * How the action summary reaches the wearer.
 *
 * A narrow port rather than the host's display type so the presenter is a plain JVM object: the composition adapts the
 * real CXR display to it (Task 13), and every rule below - what is shown, for how long, and what a broken display means
 * - is testable without a device.
 */
fun interface SummaryDisplayer {
    /** Returns `false` when nothing could be displayed; a broken display is a degradation, never a refusal. */
    fun show(text: String, ttlMillis: Long): Boolean

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
    fun show(requestId: String, summary: String)
    fun hide(requestId: String)

    /**
     * 0.3 Phase 4B (B4): whether the phone card can be put in front of the wearer right now. Defaults to `true`; a host
     * whose page/surface cannot show the card overrides this with `false` so the presenter's aggregate is honest.
     */
    fun available(): Boolean = true
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
) : ConfirmationChannel {

    private class Pending(
        val requestId: String,
        val summary: String,
        val envelope: EventEnvelope,
        /** When the plugin's window closes, so a re-announcement carries a fresh, honest TTL rather than a new one. */
        val deadlineNanos: Long,
    ) {
        var timer: CancellationHandle? = null
        /** A second `present` with a live id gets this no-op handle: it never displaces the question already up. */
        val ignoredBy = object : CancellationHandle { override fun cancel() = Unit }
    }

    private val lock = Any()
    private val pending = mutableMapOf<String, Pending>()

    /** How many questions are up; 0 or 1 in practice, exposed so the invariant is testable. */
    val pendingCount: Int get() = synchronized(lock) { pending.size }

    /**
     * 0.3 Phase 4B (B4, spec §5.4): whether **any** of this presenter's surfaces can put the question to the wearer.
     *
     * The surfaces are aggregated because the wearer may answer on any of them: the glasses display (SHOW), the phone
     * card, or the glasses keys (CONFIRM_STATE). Each check is live and a throwing one counts as unavailable **for that
     * surface only** - a broken display must not hide a working phone card. The panel is not aggregated: opening it is
     * an aid to the glasses surface, not an answer path by itself.
     */
    override fun available(): Boolean =
        runCatching { summary.available() }.getOrDefault(false) ||
            runCatching { ui.available() }.getOrDefault(false) ||
            runCatching { glasses.available() }.getOrDefault(false)

    override fun present(requestId: String, summary: String, timeoutMillis: Long): CancellationHandle {
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
        synchronized(lock) {
            val live = pending[requestId]
            if (live != null) return live.ignoredBy   // lost the race for this id: the live question keeps it
            pending[requestId] = Pending(requestId, text, envelope, deadlineNanos)
        }

        // 0.3 Phase 4B (B4): the panel is created by PANEL_OPEN, not by SHOW. Ask for it first when the glasses surface
        // can serve - a hidden window would otherwise receive the two announcements invisibly, and the panel's own
        // `resetForOpen` would erase them as it comes up. A refused open is a degradation, never a failed question.
        if (glassesCanServe()) {
            runCatching { panel.open() }
        }
        // Both screens, and neither failure is fatal: the phone is the fallback screen, and a question that cannot be
        // displayed anywhere is still settled by the gate's timeout (as a denial).
        runCatching { this.summary.show(text, timeoutMillis + SUMMARY_MARGIN_MILLIS) }
        runCatching { ui.show(requestId, text) }
        // 0.25 Task 11 (R51): after the question is up, the glasses' ENTER/BACK answer it. A send that fails (no
        // session introduced, link down) is a degradation only: the phone and the window still settle the question.
        tellGlasses(requestId, active = true)

        val timer = scheduler.at(deadlineNanos) { withdraw(requestId) }
        synchronized(lock) { pending[requestId]?.timer = timer }
        return object : CancellationHandle { override fun cancel() = withdraw(requestId) }
    }

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
            runCatching { summary.show(entry.summary, remainingMillis) }
            tellGlasses(entry.requestId, active = true)
        }
    }

    /**
     * The phone's answer. `true` only when this settles the question that is actually up, so a stale tap - one that
     * arrives after a timeout - delivers nothing.
     */
    fun answer(requestId: String, approved: Boolean): Boolean {
        val live = synchronized(lock) { pending.remove(requestId) } ?: return false
        live.timer?.cancel()
        runCatching { ui.hide(requestId) }
        tellGlasses(requestId, active = false)
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

    /** Takes a question off both screens without answering it: used by the timer, by 急停 and by session teardown. */
    private fun withdraw(requestId: String) {
        val live = synchronized(lock) { pending.remove(requestId) } ?: return
        live.timer?.cancel()
        runCatching { ui.hide(requestId) }
        tellGlasses(requestId, active = false)
    }

    /** Withdraws every open question; called when the session ends so a later tap cannot answer a dead turn. */
    fun withdrawAll() {
        synchronized(lock) { pending.keys.toList() }.forEach(::withdraw)
    }

    private fun truncate(summary: String): String {
        val collapsed = summary.replace('\n', ' ').replace('\r', ' ').trim()
        return if (collapsed.length <= MAX_SUMMARY_CHARS) collapsed
        else collapsed.take(MAX_SUMMARY_CHARS - 1) + "…"
    }

    /** One glasses state change; a refused/failed send is recorded as a degradation, never as a failed question. */
    private fun tellGlasses(requestId: String, active: Boolean) {
        runCatching { glasses.show(requestId, active) }
    }

    /**
     * 0.3 Phase 4B (B4): whether the glasses surface can carry the question now. A throwing check is `false`: the panel
     * is an aid, and a broken availability probe must not be treated as proof that a panel exists to open.
     */
    private fun glassesCanServe(): Boolean = runCatching { glasses.available() }.getOrDefault(false)

    companion object {
        /** The glasses hint path clips beyond its budget; a confirmation summary is short by construction. */
        const val MAX_SUMMARY_CHARS = 60

        /** The summary stays up a little past the window, so the question never vanishes before the deadline. */
        const val SUMMARY_MARGIN_MILLIS = 2_000L
    }
}
