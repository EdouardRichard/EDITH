package com.sheen.rokid.phone.plugin.smarthelper.confirmation
import com.sheen.rokid.phone.plugin.smarthelper.audit.P3Log

import com.sheen.rokid.agent.ConfirmationGate
import com.sheen.rokid.agent.ConfirmationOutcome
import com.sheen.rokid.agent.ConfirmationPort
import com.sheen.rokid.agent.ConfirmationSource
import com.sheen.rokid.contracts.ConfirmationResult
import com.sheen.rokid.contracts.TranscriptRevision
import com.sheen.rokid.plugin.CancellationHandle
import com.sheen.rokid.plugin.ConfirmationChannel
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * The plugin half of spec §17.4: it owns the question's *policy* - who may answer, what counts as an answer, and what
 * silence means - while the host owns presenting it.
 *
 * Three rules are the whole point:
 * 1. **silence is not consent.** The [gate] denies on timeout, so a user who walked away, a screen that is off and a
 *    phone out of range all mean "no". Nothing here treats an unanswered question as approval;
 * 2. **a spoken approval must be an explicit phrase in a later segment.** Only a final revision whose trimmed text is
 *    exactly one of [APPROVALS] or [DENIALS] counts; `嗯`/`好`/`可以` are *not* answers (the question stays open), and
 *    the segment that carried the instruction is excluded via [excludeSegment] so a user cannot confirm by having asked;
 * 3. **one question at a time.** A second [request] while one is live is refused - never queued in front of it - which
 *    is what keeps an answer addressable to exactly one action.
 *
 * The [gate] must be built with the same window as [timeoutMillis]; the plugin builds both from one constant. The
 * blocking [request] is called from the turn's own thread (the loop is synchronous), and everything else
 * ([onConfirmationResult], [onTranscript], [cancelAll]) is called from the host's event dispatcher, so the two meet only
 * on the tiny locked section that swaps the pending entry.
 */
internal class SmartConfirmation(
    channel: ConfirmationChannel?,
    private val gate: ConfirmationGate,
    private val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
    private val log: (String) -> Unit = { P3Log.line(it) },
) : ConfirmationPort {

    /** The host's channel, attached at activation - a plugin is constructed before any host has spoken to it. */
    @Volatile
    private var channel: ConfirmationChannel? = channel

    /** Points this question-asker at the host channel of the session being activated. */
    fun attach(channel: ConfirmationChannel?) {
        this.channel = channel
    }

    /**
     * 0.25 Task 11 (R52): whether a host channel can present a question right now.
     *
     * [request] answers `CANCEL` for a live question withdrawn by the wearer's STOP/急停, but a question nobody could
     * present is `UNAVAILABLE` instead. The caller that has to tell "unavailable" from "denied" asks this **before**
     * requesting: false means there is nobody to ask. Since 0.3 Phase 4B (B4) this is the channel's **live** availability
     * check, so an attached host whose surface is gone (no page, no glasses, no notification) is not "somebody to ask"
     * either - the answer can change while a question is in flight (deactivation detaches the channel and cancels the
     * pending question), which is why this is a read-only property rather than a promise; [request] itself reports
     * `UNAVAILABLE` whenever the channel cannot present.
     */
    val canAsk: Boolean get() = available()

    /**
     * 0.3 Phase 4B (B4, spec §5.4): whether this port can put a question to the wearer at all.
     *
     * Delegates to the attached host channel's own [ConfirmationChannel.available] and defaults to `false` when no
     * channel is attached. The agent loop reads it before blocking, so a plugin with nobody to ask refuses the action
     * with `CONFIRMATION_UNAVAILABLE` at once instead of waiting out D47's 120 s window.
     */
    override fun available(): Boolean = channel?.let { runCatching { it.available() }.getOrDefault(false) } ?: false

    /** One live question; [outcome] is set exactly once, by whichever resolver gets there first. */
    private class Pending(val requestId: String, val latch: CountDownLatch = CountDownLatch(1)) {
        val outcome = AtomicReference<ConfirmationOutcome?>(null)
    }

    private val lock = Any()
    private var pending: Pending? = null
    private var presentation: CancellationHandle? = null
    private var excludedSegment: String? = null

    /** How many questions are live; 0 or 1 by construction, exposed so a test can prove the invariant. */
    val pendingCount: Int get() = synchronized(lock) { if (pending == null) 0 else 1 }

    /**
     * Marks the transcript segment that carried the instruction behind the current turn.
     *
     * That segment has already been consumed by the turn, so in practice it cannot arrive again - but the rule is
     * explicit because "the sentence that asked for it must not also be the sentence that confirmed it" is the exact
     * failure the spec's independent-phrase requirement exists to prevent.
     */
    fun excludeSegment(turnId: String) {
        synchronized(lock) { excludedSegment = turnId }
    }

    override fun request(requestId: String, summary: String): ConfirmationOutcome =
        requestInternal(requestId, summary, tool = null)

    /**
     * 0.3 Phase 4B (B7): the same question with the action's wire name. The name is passed through to the host's
     * channel unchanged, so its count-only `P4_CONFIRM` line can count confirmations per tool; this class never reads
     * it and it is never shown to the wearer.
     */
    override fun request(requestId: String, summary: String, tool: String): ConfirmationOutcome =
        requestInternal(requestId, summary, tool)

    private fun requestInternal(requestId: String, summary: String, tool: String?): ConfirmationOutcome {
        // 0.3 Phase 4B (B4, spec §5.4): check the channel's own availability **before** arming the gate and before
        // `present`, so a surface that is gone refuses at once instead of blocking for the decision window. The check
        // is read live here rather than trusting the caller's earlier `canAsk`, and a throwing check is unavailable.
        // BUSY keeps precedence: a live question is still the answer when the second request cannot be queued.
        val canPresent = channel?.let { runCatching { it.available() }.getOrDefault(false) } ?: false
        val entry = synchronized(lock) {
            if (pending != null) {
                log("P3_CONFIRM request=$requestId outcome=unavailable source=none reason=BUSY")
                return ConfirmationOutcome(false, ConfirmationSource.CANCEL)
            }
            if (canPresent) Pending(requestId).also { pending = it } else null
        }
        if (entry == null) {
            // No channel, or a channel whose surface is explicitly unavailable: nobody was asked, so report UNAVAILABLE
            // rather than a denial - and never a silent approval. Nothing was armed, so nothing has to be released.
            log("P3_CONFIRM request=$requestId outcome=unavailable source=none")
            return ConfirmationOutcome(false, ConfirmationSource.UNAVAILABLE)
        }

        // The gate is armed **before** the question is shown, because `onConfirmationResult` resolves *through* the
        // gate: a question that is already on screen but not yet armed would swallow the wearer's answer and then
        // settle as a timeout-as-denial - the exact inversion of what they said. A human hand is far too slow to hit
        // that window (microseconds), which is why it survived until a test answered programmatically; arming first
        // makes "a tap settles the question" hold without depending on how fast the answer arrives.
        gate.request(requestId, summary) { approved -> settleByGate(requestId, approved) }

        val target = channel
        val presented = target?.let {
            runCatching {
                if (tool != null) {
                    it.present(requestId, summary, timeoutMillis, tool)
                } else {
                    // A caller that cannot name the action keeps the pre-B7 wire exactly: no invented name.
                    it.present(requestId, summary, timeoutMillis)
                }
            }
        }
        if (presented == null || presented.isFailure) {
            // The channel detached, or a host that could not show the question: nobody was asked, so report UNAVAILABLE
            // rather than a denial - and never a silent approval.
            log("P3_CONFIRM request=$requestId outcome=unavailable source=none")
            entry.outcome.set(ConfirmationOutcome(false, ConfirmationSource.UNAVAILABLE))
            finish(requestId)
            return entry.outcome.get()!!
        }
        val handle = presented.getOrNull()
        synchronized(lock) {
            // Only a question that is still live keeps a presentation to withdraw later. One that was already settled
            // while `present` was still running (an answer can now land at any moment) must not leave a stale handle
            // armed behind it, so it is cancelled here instead.
            if (pending === entry) presentation = handle else runCatching { handle?.cancel() }
        }

        entry.latch.await(timeoutMillis + LATCH_SLACK_MILLIS, TimeUnit.MILLISECONDS)
        // The gate's own deadline is the authority on a timeout, and it always settles *through* [finish]. Waking up with
        // no outcome means that deadline never arrived (a scheduler that lost it), so this slack is what releases the
        // question - and it has to release it the same way, or `pending` stays armed forever and every later question is
        // refused as BUSY. `finish` is the only exit from `pending`, so the fallback goes through it too; it is
        // idempotent, so a gate that settles concurrently still logs and counts down exactly once.
        if (entry.outcome.get() == null) finish(requestId)
        return entry.outcome.get() ?: ConfirmationOutcome(false, ConfirmationSource.TIMEOUT)
    }

    /** A phone tap: the host's own answer to the question it presented. `true` when it settled the live question. */
    fun onConfirmationResult(result: ConfirmationResult): Boolean {
        val entry = synchronized(lock) { pending?.takeIf { it.requestId == result.requestId } } ?: return false
        entry.outcome.compareAndSet(null, ConfirmationOutcome(result.approved, ConfirmationSource.PHONE))
        return gate.resolve(result.requestId, result.approved)
    }

    /**
     * A final transcript arriving while a question is live. `true` when it was consumed **as an answer** - the caller
     * must then keep it away from the wake and proactive paths, so a confirmation phrase never opens a second turn.
     */
    fun onTranscript(revision: TranscriptRevision): Boolean {
        if (!revision.final) return false
        val entry = synchronized(lock) {
            val live = pending ?: return false
            if (revision.turnId == excludedSegment) return false
            live
        }
        val approved = when (revision.text.trim()) {
            in APPROVALS -> true
            in DENIALS -> false
            else -> return false
        }
        entry.outcome.compareAndSet(null, ConfirmationOutcome(approved, ConfirmationSource.VOICE))
        return gate.resolve(entry.requestId, approved)
    }

    /** 急停: withdraws the host's question and settles the live one as denied. */
    fun cancelAll() {
        val entry = synchronized(lock) { pending }
        entry?.outcome?.compareAndSet(null, ConfirmationOutcome(false, ConfirmationSource.CANCEL))
        gate.cancelAll()
        entry?.let { finish(it.requestId) }
    }

    /**
     * The gate settled - by timeout (deny by default) or through a resolver that already recorded its source. The
     * source is only defaulted to `TIMEOUT` when nobody set one, so a late timeout can never overwrite a real answer.
     */
    private fun settleByGate(requestId: String, approved: Boolean) {
        val entry = synchronized(lock) { pending?.takeIf { it.requestId == requestId } } ?: return
        entry.outcome.compareAndSet(null, ConfirmationOutcome(approved, ConfirmationSource.TIMEOUT))
        finish(requestId)
    }

    private fun finish(requestId: String) {
        val entry = synchronized(lock) {
            val live = pending?.takeIf { it.requestId == requestId } ?: return
            pending = null
            val handle = presentation
            presentation = null
            handle to live
        }
        val outcome = entry.second.outcome.get() ?: ConfirmationOutcome(false, ConfirmationSource.TIMEOUT)
        // 0.3 Phase 4B (B7, spec §6.3): report the plugin-owned decision to the host **before** withdrawing it, so the
        // host can pair it with the channel it captured when the question went up. This is the only settlement point
        // that knows a timeout/cancellation from the gate, and a voice answer from a tap; a phone tap was already
        // answered (and recorded) by the host's presenter, whose question is gone by now, so this reports nothing new
        // for it. Telemetry only: a legacy channel ignores the call and a throwing one changes nothing.
        runCatching {
            channel?.settle(
                requestId,
                when {
                    outcome.approved -> "allow"
                    outcome.source == ConfirmationSource.TIMEOUT -> "timeout"
                    outcome.source == ConfirmationSource.UNAVAILABLE -> "unavailable"
                    else -> "deny"
                },
                // §5.4: a spoken phrase never presents a question, so this is the one answer channel the host cannot
                // capture at present time. Every other source is the channel the host itself presented on.
                if (outcome.source == ConfirmationSource.VOICE) "voice" else null,
            )
        }
        // Withdrawing the host's question is right on every settlement: the UI must not outlive the decision.
        runCatching { entry.first?.cancel() }
        log("P3_CONFIRM request=$requestId outcome=${if (outcome.approved) "approved" else "denied"} " +
            "source=${outcome.source.name.lowercase()}")
        entry.second.latch.countDown()
    }

    companion object {
        /**
         * How long a question waits for an answer, and the window the phone UI counts down.
         *
         * **Owner directive 2026-10-10: 10 s → 120 s.** Measured on the phone: with 10 s a person had no realistic
         * chance to notice the notification (which on this device showed neither sound nor vibration) and act on it -
         * the three device attempts all settled as `timeout`. The window is the *plugin's* to own (§17.4: the gate is
         * the authority on what silence means), and the host builds its presenter with the same value.
         */
        const val DEFAULT_TIMEOUT_MILLIS = 120_000L

        /** The exact words that answer a question. Deliberately closed: a paraphrase is not an approval. */
        val APPROVALS = setOf("确认", "允许")
        val DENIALS = setOf("拒绝", "取消")

        /** A little longer than the gate's own window, so the gate is always the one that decides a timeout. */
        private const val LATCH_SLACK_MILLIS = 1_000L
    }
}
