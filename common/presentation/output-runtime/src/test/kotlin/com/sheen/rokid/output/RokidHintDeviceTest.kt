package com.sheen.rokid.output

import com.sheen.rokid.contracts.Assistance
import com.sheen.rokid.contracts.Generation
import com.sheen.rokid.contracts.OutputStatus
import com.sheen.rokid.contracts.SessionId
import com.sheen.rokid.plugin.MonotonicClock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The production `HintDevice`: what the App actually calls to put a hint on the glasses.
 *
 * `HintExchange` had the acknowledgement rules but nothing used it, so `OutputStatus.Displayed` was unreachable in the
 * running App even though it was reachable in the library. This adapter composes the exchange with the CXR
 * custom-command channel and routes inbound acknowledgements back to the right in-flight hint.
 *
 * It takes the send function and the inbound-command entry point as parameters, so the wire itself stays outside and
 * these rules are provable without a device.
 */
class RokidHintDeviceTest {

    private class Fixture {
        var now = 1_000_000_000L
        val clock = object : MonotonicClock { override fun nowNanos() = now }
        val sentJson = mutableListOf<String>()
        var sendResult = true
        val device = RokidHintDevice(clock = clock, sendJson = { json -> sentJson += json; sendResult })
    }

    private fun hint(
        hintId: String = "h-1",
        session: String = "s-1",
        generation: Long = 1,
        text: String = "先讲项目背景",
        expiresAtNanos: Long = 0L,
    ) = Assistance(
        hintId = hintId,
        sessionId = SessionId(session),
        generation = Generation(generation),
        sourceTurnId = null,
        priority = 1,
        text = text,
        optionalSpeech = null,
        expiresAtNanos = expiresAtNanos,
    )

    /**
     * A same-process or very fast loopback link can answer on the **sending** thread.
     *
     * The hint must already be registered when that happens: registering it only after `show` returns let the ack for
     * a zero-latency link be dropped, so the hint ended as `Expired` even though the glasses had confirmed it. Found
     * by driving this device against the real glasses-side controller in `HintClockSyncTest`.
     */
    @Test
    fun anAckThatArrivesBeforeShowReturnsIsStillAdopted() {
        val sent = mutableListOf<String>()
        lateinit var device: RokidHintDevice
        device = RokidHintDevice(clock = object : MonotonicClock { override fun nowNanos() = 1_000_000_000L },
            sendJson = { json ->
                sent += json
                val show = HintWireCodec.decode(json) as? HintCommand.Show
                if (show != null) {
                    device.onCommand(HintWireCodec.encode(HintCommand.Ack(show.sessionId, show.generation,
                        show.sequence, show.hintId, show.revision, OutputStatus.Displayed)))
                }
                true
            })
        val statuses = mutableListOf<OutputStatus>()

        device.show(hint("h-7"), revision = 1) { statuses += it }

        assertEquals(listOf(OutputStatus.Queued, OutputStatus.Displayed), statuses)
        assertEquals(0, device.inFlightCount, "a confirmed hint must leave the in-flight set")
    }

    @Test
    fun showPutsAnEncodedShowOnTheWire() {
        val f = Fixture()
        f.device.show(hint("h-7"), revision = 3) { }

        val decoded = HintWireCodec.decode(f.sentJson.single())
        assertTrue(decoded is HintCommand.Show, "a SHOW must go out, was $decoded")
        assertEquals("h-7", (decoded as HintCommand.Show).hintId)
        assertEquals("先讲项目背景", decoded.text)
    }

    @Test
    fun anInboundAckCompletesTheMatchingHintWithDisplayed() {
        // The whole reason this adapter exists: the App must learn the glasses actually showed it.
        val f = Fixture()
        val statuses = mutableListOf<OutputStatus>()
        f.device.show(hint("h-7"), revision = 3) { statuses += it }
        val show = HintWireCodec.decode(f.sentJson.single()) as HintCommand.Show
        val ackJson = HintWireCodec.encode(HintCommand.Ack("s-1", 1, show.sequence, "h-7", 3, OutputStatus.Displayed))

        f.device.onCommand(ackJson)

        assertEquals(OutputStatus.Displayed, statuses.last())
        assertFalse(statuses.first() == OutputStatus.Displayed, "Displayed must only follow a real ack")
    }

    @Test
    fun anAckForAHintThisDeviceNeverShowedIsIgnoredRatherThanAdopted() {
        val f = Fixture()
        val statuses = mutableListOf<OutputStatus>()
        f.device.show(hint("h-7"), revision = 1) { statuses += it }
        val foreign = HintWireCodec.encode(HintCommand.Ack("s-1", 1, 999L, "h-OTHER", 1, OutputStatus.Displayed))

        f.device.onCommand(foreign)

        assertFalse(statuses.contains(OutputStatus.Displayed), "another hint's ack must not be adopted")
    }

    @Test
    fun anUndecodableInboundMessageIsIgnoredInsteadOfCrashingTheCallbackThread() {
        // This runs on the SDK's callback thread; a throw here would take the session down.
        val f = Fixture()
        f.device.show(hint("h-7"), revision = 1) { }

        f.device.onCommand("not json at all")
        f.device.onCommand("")

        assertEquals(1, f.sentJson.size, "garbage inbound must not trigger outbound traffic")
    }

    @Test
    fun clearSendsAClearForThatSessionAndGeneration() {
        val f = Fixture()
        f.device.clear(SessionId("s-9"), Generation(4))

        val decoded = HintWireCodec.decode(f.sentJson.single())
        assertTrue(decoded is HintCommand.Clear, "a CLEAR must go out, was $decoded")
        assertEquals("s-9", (decoded as HintCommand.Clear).sessionId)
        assertEquals(4L, decoded.generation)
    }

    @Test
    fun cancellingTheReturnedHandleStopsStatusAndClearsTheHintOnTheGlasses() {
        // Cancelling must actually take the hint off the display, not merely stop reporting about it.
        val f = Fixture()
        val statuses = mutableListOf<OutputStatus>()
        val handle = f.device.show(hint("h-7"), revision = 1) { statuses += it }
        val show = HintWireCodec.decode(f.sentJson.single()) as HintCommand.Show
        val before = statuses.size

        handle.cancel()

        assertTrue(
            f.sentJson.drop(1).any { HintWireCodec.decode(it) is HintCommand.Clear },
            "cancelling must clear the hint on the glasses",
        )
        f.device.onCommand(HintWireCodec.encode(HintCommand.Ack("s-1", 1, show.sequence, "h-7", 1, OutputStatus.Displayed)))
        assertEquals(before, statuses.size, "no status may be reported after cancellation")
    }

    @Test
    fun aRefusedSendReportsRejectedAndLeavesNothingInFlight() {
        val f = Fixture()
        f.sendResult = false
        val statuses = mutableListOf<OutputStatus>()

        f.device.show(hint("h-7"), revision = 1) { statuses += it }

        assertEquals(OutputStatus.Rejected, statuses.last())
        // A hint that never left must not be left waiting for an ack that cannot come.
        f.device.onCommand(HintWireCodec.encode(HintCommand.Ack("s-1", 1, 1L, "h-7", 1, OutputStatus.Displayed)))
        assertEquals(1, statuses.size, "a rejected hint must not accept a later ack")
    }
}
