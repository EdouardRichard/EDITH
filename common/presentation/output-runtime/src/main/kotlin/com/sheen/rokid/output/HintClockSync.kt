package com.sheen.rokid.output

import com.sheen.rokid.plugin.CancellationHandle
import com.sheen.rokid.plugin.DeadlineScheduler
import com.sheen.rokid.plugin.MonotonicClock
import java.util.UUID

/**
 * Phone side of the T18 clock handshake, and the owner of the `syncId` the wire requires.
 *
 * Why it has to exist before a hint can be displayed: `HintExchange` refuses a blank `syncId`, and
 * `GlassHintController` refuses a `SHOW` whose `syncId` has no mapping on the glasses. A generated identifier is
 * wire-safe but maps to nothing, so every hint would be dropped on the glasses while the phone reported "queued".
 * This drives the `(p0, g1, g2, p3)` exchange, publishes the resulting [ClockMapping] to the glasses as a `SYNC`,
 * and keeps it fresh.
 *
 * Deliberately transport-free - it takes a `send` function, a clock and a scheduler - so the whole handshake is
 * provable in-process against the real glasses-side controller, which is what the tests do.
 *
 * Cross-device honesty: the glasses only ever receive a mapping interval and a validity instant expressed in their
 * own clock domain. Phone nanoseconds never cross the boundary, and a hint's TTL therefore means the same thing on
 * both sides only for as long as the mapping lives.
 */
class HintClockSync(
    private val clock: MonotonicClock,
    private val scheduler: DeadlineScheduler,
    private val send: (HintCommand) -> Boolean,
    /** Injectable so tests are deterministic and so a caller could reuse a mapping identity if it ever needed to. */
    private val syncIdFactory: () -> String = { UUID.randomUUID().toString() },
    /** How long before a mapping expires the next exchange starts; must be shorter than its validity. */
    private val renewBeforeMillis: Long = RENEW_BEFORE_MILLIS,
    /**
     * How long an exchange waits for its `PONG` before the exchange is started again (D5).
     *
     * Without it a lost `PING` left the exchange outstanding forever: no renewal is ever scheduled from a mapping that
     * never existed, so the display stayed dead for the rest of the session and only the wearer stopping and
     * restarting it brought the display back (measured on the K40, 2026-10-09). Deliberately much shorter than
     * [renewBeforeMillis], because the point is to notice a lost exchange long before the 30 s mapping would expire.
     *
     * **Armed only once this session has published a mapping** (see [startExchange]): the opening window is
     * `CxrHintLink`'s bounded `HELLO`+`PING` plan (D15), and an unbounded retry here would quietly turn "gives up
     * after five tries" into "never gives up" - while a `PING` without a preceding `HELLO` cannot introduce a session
     * the glasses never learned about anyway.
     */
    private val pingTimeoutMillis: Long = PING_TIMEOUT_MILLIS,
    /**
     * Where the handful of handshake transitions go.
     *
     * Injected rather than logged directly so this stays a JVM-pure class, and used only for the states that would
     * otherwise fail **silently** - a `PING` nobody answered, a `PONG` that was not ours, an interval that could not
     * be published. Without those lines a broken handshake is indistinguishable from a broken transport on a device.
     */
    private val diagnostics: (String) -> Unit = {},
) : AutoCloseable {
    init {
        require(renewBeforeMillis in 1 until VALIDITY_MILLIS) { "RENEW_WINDOW_OUT_OF_RANGE" }
        require(pingTimeoutMillis > 0) { "PING_TIMEOUT_OUT_OF_RANGE" }
    }

    private var sessionId: String? = null
    private var outstanding: HintCommand.Ping? = null
    private var outstandingTimeout: CancellationHandle? = null
    /** Whether this session ever published a mapping: what separates a lost renewal (retry) from the opening (D15). */
    private var mappingEstablished = false
    /** Consecutive exchanges nobody answered; resets on every answer, so a later loss is reported immediately again. */
    private var unansweredExchanges = 0
    private var mapping: ClockMapping? = null
    private var renewal: CancellationHandle? = null

    /**
     * Establishes the session on the glasses and starts the first exchange.
     *
     * The `HELLO` goes first because the glasses refuse a `SYNC` or a `SHOW` for a session they have not been told
     * about, so the order is part of the protocol rather than a preference.
     */
    fun sessionEstablished(sessionId: String, generation: Long) {
        require(sessionId.isNotBlank()) { "SESSION_ID_REQUIRED" }
        this.sessionId = sessionId
        mapping = null
        mappingEstablished = false
        cancelRenewal()
        cancelOutstandingTimeout()
        unansweredExchanges = 0
        val hello = send(HintCommand.Hello(sessionId, generation))
        diagnostics("handshake: session=$sessionId generation=$generation helloSent=$hello")
        if (hello) startExchange()
    }

    /** Losing the link invalidates the mapping: a stale interval must not be used for a fresh deadline. */
    fun linkLost() {
        sessionId = null
        outstanding = null
        mapping = null
        mappingEstablished = false
        cancelRenewal()
        cancelOutstandingTimeout()
        unansweredExchanges = 0
    }

    /**
     * Consumes the `PONG` that answers our `PING`.
     *
     * Returns true only when the message was ours: a `PONG` for another exchange (an old one that arrived late, or
     * one from another phone) must not publish a mapping, and the caller still needs the message for other handlers.
     *
     * An exchange that cannot be turned into a consistent interval is **not** published, and another one is started
     * instead - a bad interval is worse than a late hint, because every deadline computed from it would be wrong.
     */
    fun onCommand(command: HintCommand): Boolean {
        if (command !is HintCommand.Pong) return false
        val ping = outstanding ?: return false
        if (command.syncId != ping.syncId || command.p0 != ping.p0) {
            diagnostics("handshake: pong ignored (not this exchange)")
            return false
        }
        outstanding = null
        cancelOutstandingTimeout()
        unansweredExchanges = 0
        val p3 = clock.nowNanos()
        val established = runCatching {
            ClockMapping.fromExchange(ping.syncId, ping.p0, command.g1, command.g2, p3)
        }.getOrNull()
        if (established == null) {
            diagnostics("handshake: pong unusable (inconsistent interval) - re-exchanging")
            startExchange()
            return true
        }
        mapping = established
        mappingEstablished = true
        val syncSent = send(HintCommand.Sync(ping.syncId, established.offsetLowerNanos, established.offsetUpperNanos,
            established.earliestGlassExpiry(established.establishedAtPhoneNanos, VALIDITY_MILLIS)))
        diagnostics("handshake: mapping live syncId=${ping.syncId} " +
            "offset=[${established.offsetLowerNanos},${established.offsetUpperNanos}] syncSent=$syncSent")
        scheduleRenewal(established)
        return true
    }

    /** The mapping a fresh hint may be stamped against, or null when there is none that is still valid. */
    fun liveMapping(): ClockMapping? {
        val current = mapping ?: return null
        if (!current.isExpired(clock.nowNanos())) return current
        // An expired mapping is dropped rather than reported, so no hint can be stamped with a dead interval.
        mapping = null
        return null
    }

    /** The identifier the wire needs, or null when nothing is live; callers must not substitute a generated id. */
    fun syncId(): String? = liveMapping()?.syncId

    /** True once a mapping exists and is still valid. */
    fun hasLiveMapping(): Boolean = liveMapping() != null

    private fun startExchange() {
        val session = sessionId ?: return
        require(session.isNotBlank())
        cancelOutstandingTimeout()
        val syncId = syncIdFactory()
        val ping = HintCommand.Ping(syncId, clock.nowNanos())
        outstanding = ping
        // Armed before the send, so an exchange that is answered synchronously (a fake transport in a test, or a very
        // fast link) cancels this timer instead of leaving one armed for an exchange that is already settled. Armed
        // only after this session published a mapping: the opening window belongs to `CxrHintLink`'s bounded repeat.
        if (mappingEstablished) {
            outstandingTimeout = scheduler.at(clock.nowNanos() + pingTimeoutMillis * 1_000_000) { onPingTimeout(ping) }
        }
        val sent = send(ping)
        diagnostics("handshake: ping syncId=$syncId p0=${ping.p0} sent=$sent")
    }

    /**
     * D5: an exchange that a **live session** lost is abandoned and a fresh one is started, for as long as the session
     * and the link last.
     *
     * Unbounded on purpose. The alternative - give up after a few attempts - leaves exactly the state this exists to
     * remove: a live session that can never display, whose only recovery is the wearer stopping and restarting it. Two
     * small commands every [pingTimeoutMillis] cost nothing, [linkLost] ends the retries with the link, and the report
     * is thinned to transitions so a link that stays down does not fill logcat.
     *
     * Reachable only after a mapping was published, so it never competes with D15's bounded opening plan - see the
     * field docs on [pingTimeoutMillis] and [mappingEstablished].
     */
    private fun onPingTimeout(ping: HintCommand.Ping) {
        if (outstanding !== ping || sessionId == null) return
        unansweredExchanges++
        if (unansweredExchanges == 1 || unansweredExchanges % UNANSWERED_REPORT_EVERY == 0) {
            diagnostics("handshake: ping unanswered after ${pingTimeoutMillis}ms - re-exchanging " +
                "(attempt $unansweredExchanges)")
        }
        startExchange()
    }

    private fun cancelOutstandingTimeout() {
        outstandingTimeout?.cancel()
        outstandingTimeout = null
    }

    private fun scheduleRenewal(established: ClockMapping) {
        cancelRenewal()
        val dueAt = established.establishedAtPhoneNanos + ClockMapping.VALIDITY_NANOS -
            renewBeforeMillis * 1_000_000
        renewal = scheduler.at(dueAt) { if (sessionId != null) startExchange() }
    }

    private fun cancelRenewal() {
        renewal?.cancel()
        renewal = null
    }

    override fun close() {
        linkLost()
    }

    companion object {
        /** A mapping is valid for 30 s, so the next one starts a little before that. */
        const val RENEW_BEFORE_MILLIS = 10_000L
        const val VALIDITY_MILLIS = ClockMapping.VALIDITY_NANOS / 1_000_000

        /** How long a `PING` may go unanswered before the exchange is started again (D5). */
        const val PING_TIMEOUT_MILLIS = 3_000L

        /** One report per this many consecutive unanswered exchanges, after the first. */
        private const val UNANSWERED_REPORT_EVERY = 10
    }
}
