package edith.phone

import edith.output.HintClockSync
import edith.output.HintCommand
import edith.output.HintExchange
import edith.output.HintWireCodec
import edith.output.RokidHintDevice
import edith.plugin.DeadlineScheduler
import edith.plugin.HintDevice
import edith.plugin.MonotonicClock

/**
 * The App-side hint link: the object that owns the display path for a live CXR connection.
 *
 * This is T18's composition, and it answers the four points that had to be agreed before wiring:
 *
 * 1. **Ownership** - one [RokidHintDevice] is owned here, for the life of the composition. The connection is built
 *    lazily, so the [sendJson] seam reads the live connection through the composition's holder instead of capturing a
 *    connection that does not exist yet.
 * 2. **`syncId`** - supplied by [HintClockSync] from the mapping the `PING`/`PONG` exchange produced. While no
 *    mapping is live there is nothing to stamp a hint with, and the display is not advertised at all (see
 *    [liveOutput]) - so a hint can never travel with an identifier the glasses have no mapping for.
 * 3. **Scheduler** - the composition's own deadline scheduler drives the mapping renewal; nothing here creates a
 *    thread.
 * 4. **Inbound acknowledgements** - routed in from the connection's reply channel: a `PONG` feeds the clock
 *    handshake, everything else is offered to the device, which correlates acks by sequence and drops what it does
 *    not understand rather than throwing on the SDK's callback thread.
 *
 * Transport-free on purpose: it takes a `sendJson` function, so the whole path is provable in unit tests without a
 * device. The device run that would confirm the glasses' side of it is deliberately deferred.
 */
class CxrHintLink(
    clock: MonotonicClock,
    scheduler: DeadlineScheduler,
    private val sendJson: (String) -> Boolean,
    /**
     * Where the handshake's few transitions go; wired to the log by the composition.
     *
     * Deliberately narrow: only the states that would otherwise fail silently (a PING nobody answered, a PONG that was
     * not ours, an inbound message nothing understood, the display becoming real). Without them a broken handshake and
     * a broken transport look identical on a device.
     */
    private val diagnostics: (String) -> Unit = {},
) : AutoCloseable {
    /**
     * Every outbound command goes through here.
     *
     * Logged because "the wearer saw nothing" cannot be attributed between "the phone never sent a usable `SHOW`" and
     * "the glasses refused it" without both halves: the glasses report their refusals, this reports what left the phone.
     */
    private val outbound: (String) -> Boolean = { json ->
        diagnostics("outbound: ${HintWireCodec.decode(json)?.let(::describe) ?: "undecodable (${json.length} chars)"}")
        sendJson(json)
    }

    private val clockSync = HintClockSync(clock, scheduler,
        send = { command -> outbound(HintWireCodec.encode(command)) },
        diagnostics = diagnostics)
    private val rokidDevice = RokidHintDevice(clock, outbound,
        // Wire-safe placeholder only: the display is gated on a live mapping below, so this must not be able to
        // travel in a correctly composed App. The glasses refuse an unmapped syncId anyway, which is the point of
        // naming the absence instead of inventing an identifier that looks like a mapping.
        syncId = { clockSync.syncId() ?: HintExchange.UNSYNCED_SYNC_ID })

    /** The device the host shows through; the same instance for the life of the link. */
    val device: HintDevice get() = rokidDevice

    /**
     * Called once each time the display becomes real (not live -> live).
     *
     * Activation is attempted when the scene becomes ready, which is **before** the first clock mapping exists, so a
     * plugin whose only missing capability is the display is refused at that moment. Without this signal nothing would
     * ever try again and a display that has just become usable would stay unused. Renewals replace a live mapping with
     * another live mapping and therefore do **not** fire it, so activation is not retried on every renewal.
     */
    var onDisplayBecameLive: (() -> Unit)? = null

    private var displayWasLive = false

    /**
     * True only while a hint could actually be displayed: a live clock mapping exists, so the glasses would accept
     * the `SHOW`. Before the exchange completes, advertising a display would let a plugin start and then lose hints.
     */
    fun liveOutput(): HintDevice? = if (clockSync.hasLiveMapping()) rokidDevice else null

    /** Establishes the session on the glasses and starts the clock exchange. */
    fun sessionEstablished(sessionId: String, generation: Long) = clockSync.sessionEstablished(sessionId, generation)

    /** Handles one inbound message; returns true when it was understood. */
    fun onInbound(json: String): Boolean {
        val command = HintWireCodec.decode(json) ?: run {
            diagnostics("inbound: undecodable (${json.length} chars)")
            return false
        }
        diagnostics("inbound: ${command::class.simpleName}")
        if (command is HintCommand.Pong) {
            val handled = clockSync.onCommand(command)
            if (handled) notifyDisplayLiveness()
            return handled
        }
        return rokidDevice.onCommand(json)
    }

    /** Fires [onDisplayBecameLive] only on a not-live -> live transition. */
    private fun notifyDisplayLiveness() {
        val live = clockSync.hasLiveMapping()
        if (live && !displayWasLive) diagnostics("display became live")
        if (live && !displayWasLive) onDisplayBecameLive?.invoke()
        displayWasLive = live
    }

    /** The link is gone: the mapping is dropped so no stale interval can be used for a fresh deadline. */
    fun linkLost() {
        clockSync.linkLost()
        displayWasLive = false
    }

    override fun close() = clockSync.close()

    /** Enough of a command to tell a usable `SHOW` from an unusable one in a log. */
    private fun describe(command: HintCommand): String = when (command) {
        is HintCommand.Show -> "Show sequence=${command.sequence} syncId=${command.syncId} " +
            "ttl=${command.remainingTtlMillis}ms hintId=${command.hintId}"
        is HintCommand.Clear -> "Clear sequence=${command.sequence}"
        is HintCommand.Ack -> "Ack sequence=${command.sequence}"
        else -> command::class.simpleName ?: "unknown"
    }
}
