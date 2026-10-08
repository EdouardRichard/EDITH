package edith.output

import edith.plugin.CancellationHandle
import edith.plugin.DeadlineScheduler
import edith.plugin.MonotonicClock
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
    }

    private var sessionId: String? = null
    private var outstanding: HintCommand.Ping? = null
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
        cancelRenewal()
        val hello = send(HintCommand.Hello(sessionId, generation))
        diagnostics("handshake: session=$sessionId generation=$generation helloSent=$hello")
        if (hello) startExchange()
    }

    /** Losing the link invalidates the mapping: a stale interval must not be used for a fresh deadline. */
    fun linkLost() {
        sessionId = null
        outstanding = null
        mapping = null
        cancelRenewal()
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
        val syncId = syncIdFactory()
        val ping = HintCommand.Ping(syncId, clock.nowNanos())
        outstanding = ping
        val sent = send(ping)
        diagnostics("handshake: ping syncId=$syncId p0=${ping.p0} sent=$sent")
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
    }
}
