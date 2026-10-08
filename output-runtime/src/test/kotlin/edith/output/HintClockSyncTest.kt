package edith.output

import edith.contracts.Assistance
import edith.contracts.Generation
import edith.contracts.OutputStatus
import edith.contracts.SessionId
import edith.plugin.CancellationHandle
import edith.plugin.DeadlineScheduler
import edith.plugin.MonotonicClock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * T18 phone-side clock handshake.
 *
 * The rule these tests protect: a hint may only be stamped with a `syncId` that names a **live** mapping, because the
 * glasses refuse a `SHOW` whose `syncId` they have no mapping for. A generated identifier is wire-safe and produces
 * exactly that silent drop, so "the syncId comes from a real exchange, and from nowhere else" is the property under
 * test - not the presence of a string.
 */
class HintClockSyncTest {
    private val clock = FakeClock()
    private val scheduler = FakeScheduler()
    private val sent = mutableListOf<HintCommand>()

    private fun sync(renewBeforeMillis: Long = HintClockSync.RENEW_BEFORE_MILLIS, ids: () -> String = syncIds()) =
        HintClockSync(clock, scheduler, { command -> sent += command; true }, ids, renewBeforeMillis)

    private fun syncIds(): () -> String {
        var next = 0
        return { "sync-${++next}" }
    }

    /** Simulates one exchange with the declared one-way latency and the glasses clock offset. */
    private fun completeExchange(latencyNanos: Long, offsetNanos: Long, handler: HintClockSync): HintCommand.Ping {
        val ping = sent.filterIsInstance<HintCommand.Ping>().last()
        clock.nanos = ping.p0 + 2 * latencyNanos
        val received = ping.p0 + offsetNanos + latencyNanos
        assertTrue(handler.onCommand(HintCommand.Pong(ping.syncId, ping.p0, received, received)),
            "the outstanding exchange must consume its own pong")
        return ping
    }

    @Test
    fun aPongPublishesTheIntervalToTheGlassesAndSuppliesTheSyncId() {
        val handler = sync()
        handler.sessionEstablished("s-1", 1L)
        assertEquals(listOf<HintCommand>(HintCommand.Hello("s-1", 1L), HintCommand.Ping("sync-1", 0L)), sent)
        assertNull(handler.syncId(), "no mapping exists before the exchange completes")

        val ping = completeExchange(latencyNanos = 5_000_000, offsetNanos = 1_000_000_000, handler = handler)

        val announcement = sent.filterIsInstance<HintCommand.Sync>().single()
        assertEquals(ping.syncId, announcement.syncId)
        assertEquals(1_000_000_000L - 5_000_000L, announcement.offsetLowerNanos)
        assertEquals(1_000_000_000L + 5_000_000L, announcement.offsetUpperNanos)
        assertTrue(announcement.validUntilGlassNanos > 0,
            "the mapping must be announced as valid in the glasses' own clock")
        assertEquals(ping.syncId, handler.syncId())
        assertTrue(handler.hasLiveMapping())
    }

    @Test
    fun aPongForAnotherExchangeIsNotAdopted() {
        val handler = sync()
        handler.sessionEstablished("s-1", 1L)
        clock.nanos = 10_000_000

        assertFalse(handler.onCommand(HintCommand.Pong("stale-sync", 0L, 1L, 1L)))

        assertTrue(sent.none { it is HintCommand.Sync }, "a foreign pong must not publish a mapping")
        assertNull(handler.syncId())
    }

    @Test
    fun anInconsistentIntervalIsRefusedAndAnotherExchangeIsStarted() {
        val handler = sync()
        handler.sessionEstablished("s-1", 1L)
        val ping = sent.filterIsInstance<HintCommand.Ping>().single()

        // The glasses' stamps imply the reply arrived before it was sent: no interval can be derived.
        clock.nanos = ping.p0 + 1_000_000
        assertTrue(handler.onCommand(HintCommand.Pong(ping.syncId, ping.p0, 1_000_000, 9_000_000)))

        assertTrue(sent.none { it is HintCommand.Sync }, "an unusable interval must never be published")
        assertNull(handler.syncId())
        assertEquals(2, sent.filterIsInstance<HintCommand.Ping>().size, "another exchange must be attempted")
    }

    @Test
    fun theMappingIsRenewedBeforeItExpiresAndStaysLiveUntilThen() {
        val handler = sync()
        handler.sessionEstablished("s-1", 1L)
        val ping = completeExchange(latencyNanos = 1_000_000, offsetNanos = 0, handler = handler)
        assertEquals(1, sent.filterIsInstance<HintCommand.Ping>().size)

        scheduler.fireAll()

        val renewals = sent.filterIsInstance<HintCommand.Ping>()
        assertEquals(2, renewals.size, "the renewal must start a new exchange")
        assertTrue(renewals.last().syncId != ping.syncId, "a renewal is a new mapping, not a reuse of the old id")
        assertTrue(handler.hasLiveMapping(), "the previous mapping is still valid right after the renewal started")
        val established = handler.liveMapping()!!
        clock.nanos = established.establishedAtPhoneNanos + ClockMapping.VALIDITY_NANOS - 1
        assertTrue(handler.hasLiveMapping(), "it stays usable until its own expiry")
        clock.nanos = established.establishedAtPhoneNanos + ClockMapping.VALIDITY_NANOS + 1
        assertFalse(handler.hasLiveMapping(), "and it is dropped rather than offered after that")
    }

    @Test
    fun anExpiredMappingIsNoLongerOffered() {
        val handler = sync()
        handler.sessionEstablished("s-1", 1L)
        completeExchange(latencyNanos = 1_000_000, offsetNanos = 0, handler = handler)
        val established = handler.liveMapping()!!

        clock.nanos = established.establishedAtPhoneNanos + ClockMapping.VALIDITY_NANOS + 1

        assertNull(handler.syncId(), "a dead mapping must not be used to stamp a hint")
        assertFalse(handler.hasLiveMapping())
    }

    @Test
    fun losingTheLinkInvalidatesTheMapping() {
        val handler = sync()
        handler.sessionEstablished("s-1", 1L)
        completeExchange(latencyNanos = 1_000_000, offsetNanos = 0, handler = handler)
        assertNotNull(handler.syncId())

        handler.linkLost()

        assertNull(handler.syncId())
    }

    @Test
    fun theHandshakeReportsItsTransitionsSoAFailureOnDeviceIsNotSilent() {
        // A device run cannot tell a handshake that never got an answer from a transport that never sent: these lines
        // are the difference. Only transitions are reported, so a working session stays quiet.
        val messages = mutableListOf<String>()
        val handler = HintClockSync(clock, scheduler, { command -> sent += command; true }, syncIds(),
            HintClockSync.RENEW_BEFORE_MILLIS) { messages += it }

        handler.sessionEstablished("s-1", 1L)
        assertTrue(messages.any { it.contains("session=s-1") && it.contains("helloSent=true") },
            "the start of the handshake must be visible: $messages")
        assertTrue(messages.any { it.contains("ping") && it.contains("sent=true") }, "the ping result must be visible")

        completeExchange(latencyNanos = 1_000_000, offsetNanos = 0, handler = handler)
        assertTrue(messages.any { it.contains("mapping live") && it.contains("syncSent=true") },
            "a published mapping must be visible: $messages")
    }

    @Test
    fun aForeignPongIsReportedRatherThanQuietlyIgnored() {
        val messages = mutableListOf<String>()
        val handler = HintClockSync(clock, scheduler, { command -> sent += command; true }, syncIds(),
            HintClockSync.RENEW_BEFORE_MILLIS) { messages += it }
        handler.sessionEstablished("s-1", 1L)

        handler.onCommand(HintCommand.Pong("someone-else", 0L, 1L, 1L))

        assertTrue(messages.any { it.contains("pong ignored") }, "an unusable answer must be visible: $messages")
    }

    /**
     * The four points, proved end to end in-process against the **real** glasses-side controller.
     *
     * One phone clock and one glasses clock with a 1 s offset and 5 ms of one-way latency, so the mapping has to do
     * real work: nothing here would pass if the phone leaked its own instants to the glasses. A hint is shown, the
     * glasses render it, and the phone learns `Displayed` - the status that was unreachable in production before the
     * acknowledgement loop existed.
     */
    @Test
    fun theHandshakeCarriesAHintAcrossTwoClocksAndConfirmsItAsDisplayed() {
        val phoneClock = FakeClock()
        val glassesClock = FakeClock()
        val latency = 5_000_000L
        val offset = 1_000_000_000L
        val rendered = mutableListOf<String?>()
        val statuses = mutableListOf<OutputStatus>()
        val wire = mutableListOf<HintCommand>()

        lateinit var device: RokidHintDevice
        lateinit var link: HintClockSync
        val controller = GlassHintController(glassesClock, FakeScheduler(), { rendered += it }, { ack ->
            // The glasses' reply travels back on the same channel; the phone correlates it by sequence.
            wire += ack
            device.onCommand(HintWireCodec.encode(ack))
        })
        link = HintClockSync(phoneClock, FakeScheduler(), { command ->
            wire += command
            controller.accept(command)
            // The exchange is the only message that needs a synthesised answer, because it is the network itself.
            if (command is HintCommand.Ping) {
                val received = command.p0 + offset + latency
                phoneClock.nanos = command.p0 + 2 * latency
                link.onCommand(HintCommand.Pong(command.syncId, command.p0, received, received))
            }
            true
        }, syncIds())
        device = RokidHintDevice(phoneClock, { json ->
            val command = requireNotNull(HintWireCodec.decode(json)) { "the phone must only emit decodable commands" }
            wire += command
            controller.accept(command)
            true
        }, syncId = { link.syncId() ?: HintExchange.UNSYNCED_SYNC_ID })

        link.sessionEstablished("s-1", 1L)
        assertNotNull(link.syncId(), "the exchange must have produced a live mapping")
        glassesClock.nanos = phoneClock.nanos + offset

        device.show(Assistance(
            hintId = "h-1",
            sessionId = SessionId("s-1"),
            generation = Generation(1),
            sourceTurnId = null,
            priority = 1,
            text = "先讲结论",
            optionalSpeech = null,
            expiresAtNanos = phoneClock.nanos + 2_000_000_000,
        ), revision = 1L) { statuses += it }

        assertEquals(listOf(OutputStatus.Queued, OutputStatus.Displayed), statuses)
        assertEquals(listOf<String?>("先讲结论"), rendered)
    }

    private class FakeClock(var nanos: Long = 0L) : MonotonicClock {
        override fun nowNanos(): Long = nanos
    }

    private class FakeScheduler : DeadlineScheduler {
        private val tasks = mutableListOf<Pair<Long, () -> Unit>>()
        override fun at(deadlineNanos: Long, task: () -> Unit): CancellationHandle {
            val entry = deadlineNanos to task
            tasks += entry
            return object : CancellationHandle { override fun cancel() { tasks.remove(entry) } }
        }

        fun fireAll() {
            val ready = tasks.toList()
            tasks.clear()
            ready.forEach { it.second() }
        }
    }
}
