package edith.output

import edith.contracts.OutputStatus
import edith.plugin.CancellationHandle
import edith.plugin.DeadlineScheduler
import edith.plugin.MonotonicClock

/**
 * Glasses-side hint controller: the last line of defence for a hint that arrived late, out of order, for a
 * stale session, or with a deadline that has already passed.
 *
 * It is deliberately transport-free. Feeding it decoded [HintCommand]s is a separate concern, which keeps the
 * ordering and expiry rules testable as plain JVM behaviour.
 *
 * Ordering rules:
 * - [HintCommand.Hello] establishes (or replaces) the session. A `HELLO` naming a **different** session always
 *   replaces the stored one; only a `HELLO` for the **same** session is refused when its `generation` goes backwards.
 *   The phone's generation is a per-process session counter (`HostRuntime` starts at 0 and increments on every
 *   `startSession`), so a restarted phone app legitimately sends a *lower* generation for a brand new session while
 *   this process - and the session it stored - is still alive.
 * - A `SHOW`/`CLEAR` for another session, an older generation, or a revision at or below one already shown for
 *   the same hint is refused.
 * - `CLEAR` raises a fence at its `sequence`; a late `SHOW` at or below the fence is refused. A strictly
 *   higher sequence is a new turn and is still allowed, so clearing must not permanently disable a session.
 *
 * Expiry rules:
 * - The deadline is computed from the mapping established by a `PONG` exchange and re-checked **before**
 *   rendering, so a SHOW delayed on the wire cannot revive an expired hint.
 * - The deadline also fires locally through the injected [DeadlineScheduler], so text disappears even if the
 *   phone never sends a CLEAR (the phone stops sending CLEAR but must not be relied upon to deliver it).
 *
 * Acknowledgement rule: a `Displayed` ACK is emitted only after the render callback has committed. No status
 * asserts human visibility - that needs a wearer observation, which is a separate evidence class.
 */
class GlassHintController(
    private val clock: MonotonicClock,
    private val scheduler: DeadlineScheduler,
    private val render: (String?) -> Unit,
    private val ack: (HintCommand.Ack) -> Unit,
    /**
     * Where the refusals go.
     *
     * Every gate below drops a `SHOW` silently, so on a device "the wearer saw nothing" has eight possible causes and no
     * evidence. Injected rather than logged directly to keep this a JVM-pure class; the glasses app points it at its log.
     */
    private val diagnostics: (String) -> Unit = {},
) {
    private var sessionId: String? = null
    private var generation: Long = Long.MIN_VALUE
    private var clearFence: Long = Long.MIN_VALUE
    private var lastSequence: Long = Long.MIN_VALUE
    private val shownRevisions = HashMap<String, Long>()
    private val mappings = HashMap<String, ClockMapping>()
    private var expiry: CancellationHandle? = null
    private var visible: Boolean = false

    fun accept(command: HintCommand) {
        when (command) {
            is HintCommand.Hello -> onHello(command)
            is HintCommand.Sync -> onSync(command)
            is HintCommand.Show -> onShow(command)
            is HintCommand.Clear -> onClear(command)
            // PING is answered by the phone side, and a PONG is that answer: it carries the glasses' stamps to
            // the phone, which is the side that can compute the interval. The glasses learn the result from
            // SYNC, so neither message changes local state here.
            //
            // SAID and BUSY are Phase C's conversation traffic. They are deliberately **not** this class's business:
            // it owns the render/expiry state machine for one hint, while the conversation buffers and the working
            // indicator live in the view (see `edith.glasses`). Keeping them out is what lets every ordering and
            // expiry rule above stay testable as plain JVM behaviour with no notion of a conversation.
            is HintCommand.Ping, is HintCommand.Pong, is HintCommand.Ack,
            is HintCommand.Said, is HintCommand.Busy -> Unit
        }
    }

    /** Losing the link must never leave text on the glasses. */
    fun disconnect() {
        sessionId = null
        generation = Long.MIN_VALUE
        clearFence = Long.MIN_VALUE
        lastSequence = Long.MIN_VALUE
        shownRevisions.clear()
        mappings.clear()
        cancelExpiry()
        if (visible) {
            visible = false
            render(null)
        }
    }

    private fun onHello(command: HintCommand.Hello) {
        // Stale means "the same session, an older generation" - the case this ordering rule was written for. A HELLO
        // for a **different** session is a new session and must be adopted even when its generation is lower: the
        // phone's generation is a per-process counter that restarts at 1, while this process (and the session it
        // stored) outlives the phone's app. Measured on the K40 + glasses on 2026-10-09: after one phone restart this
        // process refused every SHOW for the rest of its life (`Show refused: session 393e1238-… != 49dedb25-…`),
        // because every later HELLO carried generation 1 against a stored generation that was already higher (≥2:
        // the phone had sent generations 2 and 3 in one earlier app run, and only a later app run could send 1).
        if (command.sessionId == sessionId && command.generation < generation) {
            return refuse("Hello", "generation ${command.generation} < $generation for session ${command.sessionId}")
        }
        // Reported because a HELLO that is silently ignored and a HELLO that never arrived were indistinguishable
        // here, which is exactly what made this gate impossible to attribute on the device.
        val replaced = sessionId?.let { " (replaced session=$it generation=$generation)" }.orEmpty()
        sessionId = command.sessionId
        generation = command.generation
        clearFence = Long.MIN_VALUE
        lastSequence = Long.MIN_VALUE
        shownRevisions.clear()
        mappings.clear()
        cancelExpiry()
        if (visible) {
            visible = false
            render(null)
        }
        diagnostics("Hello accepted: session=${command.sessionId} generation=${command.generation}$replaced")
    }

    private fun onSync(command: HintCommand.Sync) {
        if (sessionId == null) return refuse("Sync", "no session yet")
        val now = clock.nowNanos()
        // The phone tells us how long its interval stays valid; clamp to what is left of it.
        val validFor = command.validUntilGlassNanos - now
        if (validFor <= 0) {
            mappings.remove(command.syncId)
            return refuse("Sync", "already expired (${validFor}ns left)")
        }
        val mapping = runCatching {
            // Anchored at the local instant it was accepted: `isExpired` measures elapsed local time, and the
            // glasses' own monotonic clock is far past zero, so any other anchor would read as already expired.
            ClockMapping.fromSync(command.syncId, command.offsetLowerNanos, command.offsetUpperNanos,
                establishedAtNanos = now, validForNanos = validFor)
        }.getOrElse { thrown ->
            return refuse("Sync", "unusable interval [${command.offsetLowerNanos},${command.offsetUpperNanos}] " +
                "validFor=${validFor}ns: ${thrown.message}")
        }
        mappings[command.syncId] = mapping
        diagnostics("Sync accepted: syncId=${command.syncId} validFor=${validFor / 1_000_000}ms")
    }

    private fun onShow(command: HintCommand.Show) {
        // Session, generation and ordering gates first: none of these depend on the clock.
        if (command.sessionId != sessionId) return refuse("Show", "session ${command.sessionId} != $sessionId")
        if (command.generation != generation) return refuse("Show", "generation ${command.generation} != $generation")
        if (command.sequence <= clearFence) return refuse("Show", "sequence ${command.sequence} <= clearFence $clearFence")
        if (command.sequence < lastSequence) return refuse("Show", "sequence ${command.sequence} < lastSequence $lastSequence")
        val previousRevision = shownRevisions[command.hintId]
        if (previousRevision != null && command.revision <= previousRevision) {
            return refuse("Show", "revision ${command.revision} <= shown $previousRevision for ${command.hintId}")
        }

        // The deadline must come from a live mapping; an unmapped or stale syncId is not a deadline.
        val mapping = mappings[command.syncId]
            ?: return refuse("Show", "no mapping for syncId ${command.syncId} (known: ${mappings.keys})")
        val now = clock.nowNanos()
        if (mapping.isExpired(now)) return refuse("Show", "mapping expired for syncId ${command.syncId}")
        val deadline = mapping.earliestGlassExpiry(command.phoneSentNanos, command.remainingTtlMillis)
        if (deadline <= now) {
            return refuse("Show", "deadline $deadline <= now $now (ttl ${command.remainingTtlMillis}ms)")
        }

        lastSequence = command.sequence
        shownRevisions[command.hintId] = command.revision
        cancelExpiry()
        render(command.text)
        visible = true
        diagnostics("Show displayed: sequence=${command.sequence} hintId=${command.hintId} " +
            "ttl=${command.remainingTtlMillis}ms")
        // Scheduled after the render committed, so the ACK cannot get ahead of what is on screen.
        ack(HintCommand.Ack(command.sessionId, command.generation, command.sequence, command.hintId,
            command.revision, OutputStatus.Displayed))
        expiry = scheduler.at(deadline) {
            if (!visible) return@at
            visible = false
            render(null)
        }
    }

    /** Reported rather than dropped: a refused command is otherwise indistinguishable from one that never arrived. */
    private fun refuse(command: String, reason: String) {
        diagnostics("$command refused: $reason")
    }

    private fun onClear(command: HintCommand.Clear) {
        if (command.sessionId != sessionId) return
        if (command.generation != generation) return
        if (command.sequence < lastSequence) return
        clearFence = maxOf(clearFence, command.sequence)
        lastSequence = maxOf(lastSequence, command.sequence)
        cancelExpiry()
        if (visible) {
            visible = false
            render(null)
        }
    }

    private fun cancelExpiry() {
        expiry?.cancel()
        expiry = null
    }
}
