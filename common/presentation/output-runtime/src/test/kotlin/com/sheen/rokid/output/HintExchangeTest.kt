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
 * The phone side of a hint exchange: encode a SHOW, send it, and turn the glasses' ACK into a status the host can act
 * on.
 *
 * Why this exists: `GlassHintController` on the glasses produces `HintCommand.Ack`, the wire codec can carry it, and
 * the controller's own tests assert it - but **nothing on the phone ever consumed an ACK**, so `OutputStatus.Displayed`
 * was unreachable in production. A hint could never be confirmed as shown, and the arbiter could only ever see the
 * optimistic status it started with. This class closes that loop, and the rules it has to get right are the ones a
 * cross-device acknowledgement always gets wrong: sequence allocation, the TTL the glasses will honour, ignoring acks
 * for other hints or stale revisions, and refusing to hang forever when no ack arrives.
 */
class HintExchangeTest {

    private class Fixture {
        var now = 1_000_000_000L
        val clock = object : MonotonicClock { override fun nowNanos() = now }
        val sent = mutableListOf<HintCommand>()
        var sendResult = true
        val exchange = HintExchange(clock = clock, send = { command -> sent += command; sendResult })
    }

    private fun hint(
        hintId: String = "h-1",
        revision: Long = 1,
        session: String = "s-1",
        generation: Long = 1,
        text: String = "先讲项目背景",
        expiresAtNanos: Long = 0L,
        route: com.sheen.rokid.contracts.AssistantRoute = com.sheen.rokid.contracts.AssistantRoute.WAKE,
    ) = Assistance(
        hintId = hintId,
        sessionId = SessionId(session),
        generation = Generation(generation),
        sourceTurnId = null,
        priority = 1,
        text = text,
        optionalSpeech = null,
        expiresAtNanos = expiresAtNanos,
        route = route,
    )

    /**
     * Phase C (spec §6.6): the glasses keep one conversation per route, so the `SHOW` has to say which conversation it
     * belongs to. The route travels on the `Assistance` because that is what the plugin built and what the arbiter
     * passed on; a second source of truth here would be a second opinion about which conversation the wearer reads.
     */
    @Test
    fun aShowCarriesTheRouteOfItsAssistance() {
        val proactive = Fixture()
        proactive.exchange.show(hint(route = com.sheen.rokid.contracts.AssistantRoute.PROACTIVE), revision = 1) { }
        assertEquals(com.sheen.rokid.contracts.AssistantRoute.PROACTIVE,
            (proactive.sent.single() as HintCommand.Show).route)

        val wake = Fixture()
        wake.exchange.show(hint(), revision = 1) { }
        assertEquals(com.sheen.rokid.contracts.AssistantRoute.WAKE, (wake.sent.single() as HintCommand.Show).route,
            "a hint built without a route is a wake hint - which is what every pre-Phase-C hint was")
    }

    @Test
    fun showingAHintSendsOneShowCarryingThatHintAndReportsItsFirstStatus() {
        val f = Fixture()
        val statuses = mutableListOf<OutputStatus>()

        val handle = f.exchange.show(hint("h-7", revision = 3), revision = 3) { statuses += it }

        val show = f.sent.single() as HintCommand.Show
        assertEquals("h-7", show.hintId)
        assertEquals(3L, show.revision)
        assertEquals("先讲项目背景", show.text)
        assertEquals("h-7", handle.hintId)
        assertTrue(statuses.isNotEmpty(), "a status must be reported")
        assertFalse(
            statuses.first() == OutputStatus.Displayed,
            "an unacknowledged hint must not claim to be displayed",
        )
    }

    @Test
    fun anAckForTheSentHintIsReportedAsDisplayed() {
        // The whole point: the glasses confirming is the only thing that may produce Displayed.
        val f = Fixture()
        val statuses = mutableListOf<OutputStatus>()
        val handle = f.exchange.show(hint("h-7", revision = 3), revision = 3) { statuses += it }
        val show = f.sent.single() as HintCommand.Show

        handle.ack(HintCommand.Ack("s-1", 1, show.sequence, "h-7", 3, OutputStatus.Displayed))

        assertEquals(OutputStatus.Displayed, statuses.last())
    }

    @Test
    fun anAckForADifferentHintIsIgnoredRatherThanReportedAsOurs() {
        // Correlated replies arrive on one channel; a matching sequence with a foreign hint id must not be adopted.
        val f = Fixture()
        val statuses = mutableListOf<OutputStatus>()
        val handle = f.exchange.show(hint("h-7"), revision = 3) { statuses += it }
        val show = f.sent.single() as HintCommand.Show

        handle.ack(HintCommand.Ack("s-1", 1, show.sequence, "h-OTHER", 3, OutputStatus.Displayed))

        assertFalse(statuses.contains(OutputStatus.Displayed), "another hint's ack must not be adopted")
    }

    @Test
    fun anAckForAStaleRevisionIsIgnoredSoAnOldRenderCannotOverwriteANewerHint() {
        val f = Fixture()
        val statuses = mutableListOf<OutputStatus>()
        val handle = f.exchange.show(hint("h-7", revision = 4), revision = 4) { statuses += it }
        val show = f.sent.single() as HintCommand.Show

        handle.ack(HintCommand.Ack("s-1", 1, show.sequence, "h-7", 2, OutputStatus.Displayed))

        assertFalse(statuses.contains(OutputStatus.Displayed), "a stale revision's ack must not be adopted")
    }

    @Test
    fun eachShowGetsItsOwnSequenceSoTwoHintsCannotBeConfused() {
        val f = Fixture()
        f.exchange.show(hint("h-1"), revision = 1) { }
        f.exchange.show(hint("h-2"), revision = 1) { }

        val sequences = f.sent.filterIsInstance<HintCommand.Show>().map { it.sequence }
        assertEquals(sequences.size, sequences.toSet().size, "sequences must be distinct")
    }

    @Test
    fun theTtlSentIsTheRemainingLifeOfTheHintNotItsAbsoluteDeadline() {
        // The glasses hold a different clock, so an absolute phone instant would be meaningless to them. What travels
        // is how long is left.
        val f = Fixture()
        val deadline = f.now + 5_000_000_000L
        f.exchange.show(hint("h-7", expiresAtNanos = deadline), revision = 1) { }

        assertEquals(5_000L, (f.sent.single() as HintCommand.Show).remainingTtlMillis)
    }

    @Test
    fun showingAHintWithNoDeadlineSendsAPositiveTtlRatherThanZero() {
        // Zero would mean "already expired" to the glasses, silently dropping a hint that has no deadline at all.
        val f = Fixture()
        f.exchange.show(hint("h-7", expiresAtNanos = 0L), revision = 1) { }

        assertTrue(
            (f.sent.single() as HintCommand.Show).remainingTtlMillis > 0L,
            "an unbounded hint must not be sent as already-expired",
        )
    }

    @Test
    fun aRefusedSendIsReportedAsRejectedInsteadOfLookingQueued() {
        // Reporting Queued for a hint that never left the phone would be a silent loss.
        val f = Fixture()
        f.sendResult = false
        val statuses = mutableListOf<OutputStatus>()

        f.exchange.show(hint("h-7"), revision = 1) { statuses += it }

        assertEquals(OutputStatus.Rejected, statuses.last())
    }

    @Test
    fun cancellingStopsFurtherStatusSoAFinishedHintCannotBeRevivedByALateAck() {
        val f = Fixture()
        val statuses = mutableListOf<OutputStatus>()
        val handle = f.exchange.show(hint("h-7", revision = 3), revision = 3) { statuses += it }
        val show = f.sent.single() as HintCommand.Show
        val before = statuses.size

        handle.cancel()
        handle.ack(HintCommand.Ack("s-1", 1, show.sequence, "h-7", 3, OutputStatus.Displayed))

        assertEquals(before, statuses.size, "no status may be emitted after cancellation")
        assertEquals(1, handle.cancellationCount())
    }

    @Test
    fun aHintThatExpiresBeforeAnyAckIsReportedExpiredExactlyOnce() {
        // Nothing else will ever arrive for it, so the host must be told the wait is over - once, not repeatedly.
        val f = Fixture()
        val statuses = mutableListOf<OutputStatus>()
        val handle = f.exchange.show(hint("h-7", expiresAtNanos = f.now + 1_000_000_000L), revision = 1) {
            statuses += it
        }

        handle.expireIfDue(f.now + 2_000_000_000L)
        handle.expireIfDue(f.now + 3_000_000_000L)

        assertEquals(1, statuses.count { it == OutputStatus.Expired })
    }

    @Test
    fun aHintWithNoDeadlineNeverExpiresOnItsOwn() {
        val f = Fixture()
        val statuses = mutableListOf<OutputStatus>()
        val handle = f.exchange.show(hint("h-7", expiresAtNanos = 0L), revision = 1) { statuses += it }

        handle.expireIfDue(f.now + 600_000_000_000L)

        assertFalse(statuses.contains(OutputStatus.Expired), "an unbounded hint must not expire")
    }
}
