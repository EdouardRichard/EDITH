package com.sheen.rokid.phone.session.confirmation
import com.sheen.rokid.phone.app.HostCompositionRoot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Spec §17.4's notification buttons end here: a tap must take the **same** path as the on-screen card
 * (`HostCompositionRoot.answerConfirmation` / `emergencyStop`), and a tap that arrives with no composition alive must
 * settle nothing at all.
 *
 * That last rule is the one worth a test of its own: a question posted before the process died is long past its 10 s
 * window, so "no answer" is the only honest outcome. Anything else would turn a stale notification into an approval.
 */
class ConfirmationActionRoutingTest {

    private class FakePort : ConfirmationActionPort {
        val answers = mutableListOf<Pair<String, Boolean>>()
        var stops = 0
        override fun answer(requestId: String, approved: Boolean): Boolean {
            answers += requestId to approved
            return true
        }
        override fun stop() { stops++ }
    }

    @Test
    fun anAllowTapAnswersThroughTheSamePathAsTheCard() {
        val port = FakePort()

        val outcome = routeConfirmationAction(ConfirmationNotifier.ACTION_ALLOW, "c1", port)

        assertEquals(ConfirmationActionOutcome.SETTLED, outcome)
        assertEquals(listOf("c1" to true), port.answers)
    }

    @Test
    fun aDenyTapAnswersDenied() {
        val port = FakePort()

        val outcome = routeConfirmationAction(ConfirmationNotifier.ACTION_DENY, "c1", port)

        assertEquals(ConfirmationActionOutcome.SETTLED, outcome)
        assertEquals(listOf("c1" to false), port.answers)
    }

    @Test
    fun aStopTapStopsTheSessionRatherThanAnsweringTheQuestion() {
        val port = FakePort()

        val outcome = routeConfirmationAction(ConfirmationNotifier.ACTION_STOP, "c1", port)

        assertEquals(ConfirmationActionOutcome.STOPPED, outcome)
        assertEquals("急停 settles every open question as denied, so it must not answer just one", 0, port.answers.size)
        assertEquals(1, port.stops)
    }

    @Test
    fun aTapWithNoCompositionAliveSettlesNothing() {
        val log = mutableListOf<String>()

        val outcome = routeConfirmationAction(ConfirmationNotifier.ACTION_ALLOW, "c1", null, log = { log += it })

        assertEquals("a question from a dead process is long past its window: it must never become a yes",
            ConfirmationActionOutcome.IGNORED, outcome)
        assertTrue("the log must say why nothing happened: $log", log.any { "NO_COMPOSITION" in it && "c1" in it })
    }

    @Test
    fun anUnknownActionOrAMissingRequestIdSettlesNothing() {
        val port = FakePort()
        val log = mutableListOf<String>()

        assertEquals(ConfirmationActionOutcome.IGNORED, routeConfirmationAction("edith.SOMETHING_ELSE", "c1", port, log = { log += it }))
        assertEquals(ConfirmationActionOutcome.IGNORED, routeConfirmationAction(ConfirmationNotifier.ACTION_ALLOW, null, port, log = { log += it }))
        assertEquals(ConfirmationActionOutcome.IGNORED, routeConfirmationAction(ConfirmationNotifier.ACTION_ALLOW, "  ", port, log = { log += it }))

        assertTrue("nothing may be answered from a malformed tap", port.answers.isEmpty())
        assertEquals(2, log.count { "MISSING_REQUEST" in it })
        assertEquals(1, log.count { "UNKNOWN_ACTION" in it })
    }
}
