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
}

/** The phone-side question: a card on the session page and, when notifications are allowed, the same two actions. */
interface ConfirmationUi {
    fun show(requestId: String, summary: String)
    fun hide(requestId: String)
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
) : ConfirmationChannel {

    private class Pending(val requestId: String, val summary: String, val envelope: EventEnvelope) {
        var timer: CancellationHandle? = null
        /** A second `present` with a live id gets this no-op handle: it never displaces the question already up. */
        val ignoredBy = object : CancellationHandle { override fun cancel() = Unit }
    }

    private val lock = Any()
    private val pending = mutableMapOf<String, Pending>()

    /** How many questions are up; 0 or 1 in practice, exposed so the invariant is testable. */
    val pendingCount: Int get() = synchronized(lock) { pending.size }

    override fun present(requestId: String, summary: String, timeoutMillis: Long): CancellationHandle {
        require(requestId.isNotBlank()) { "confirmation request id must not be blank" }
        require(timeoutMillis > 0) { "confirmation window must be positive, was $timeoutMillis" }
        val text = truncate(summary)
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
            pending[requestId] = Pending(requestId, text, envelope)
        }

        // Both screens, and neither failure is fatal: the phone is the fallback screen, and a question that cannot be
        // displayed anywhere is still settled by the gate's timeout (as a denial).
        runCatching { this.summary.show(text, timeoutMillis + SUMMARY_MARGIN_MILLIS) }
        runCatching { ui.show(requestId, text) }
        // 0.25 Task 11 (R51): after the question is up, the glasses' ENTER/BACK answer it. A send that fails (no
        // session introduced, link down) is a degradation only: the phone and the window still settle the question.
        tellGlasses(requestId, active = true)

        val timer = scheduler.at(clock.nowNanos() + timeoutMillis * 1_000_000L) { withdraw(requestId) }
        synchronized(lock) { pending[requestId]?.timer = timer }
        return object : CancellationHandle { override fun cancel() = withdraw(requestId) }
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

    companion object {
        /** The glasses hint path clips beyond its budget; a confirmation summary is short by construction. */
        const val MAX_SUMMARY_CHARS = 60

        /** The summary stays up a little past the window, so the question never vanishes before the deadline. */
        const val SUMMARY_MARGIN_MILLIS = 2_000L
    }
}
