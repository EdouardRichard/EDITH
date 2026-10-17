package com.sheen.rokid.glasses.bridge

import com.sheen.rokid.output.HintCommand
import com.sheen.rokid.output.HintWireCodec
import com.sheen.rokid.plugin.MonotonicClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The glasses' half of the T18 clock handshake.
 *
 * A `PING` that goes unanswered means the phone never publishes a `SYNC`, the glasses hold no mapping, and every
 * `SHOW` is correctly refused as unmapped - so this is the difference between a display path that can work and one
 * that silently never does. Off-device, the rule is just two stamps and the exchange identity.
 */
class GlassClockResponderTest {

    private class FakeClock(var nanos: Long = 1_000L) : MonotonicClock {
        override fun nowNanos(): Long = nanos
    }

    @Test
    fun aPingIsAnsweredWithTheExchangeIdentityAndBothArrivalStamps() {
        val clock = FakeClock(nanos = 7_000_000L)
        val sent = mutableListOf<String>()
        val responder = GlassClockResponder(clock) { json -> sent += json; true }

        assertTrue(responder.onPing(HintCommand.Ping(syncId = "sync-1", p0 = 5_000_000L)))

        val pong = HintWireCodec.decode(sent.single())
        assertTrue("a PING must be answered with a PONG, was $pong", pong is HintCommand.Pong)
        pong as HintCommand.Pong
        assertEquals("the exchange identity must be carried back verbatim", "sync-1", pong.syncId)
        assertEquals("the phone's send instant must be echoed", 5_000_000L, pong.p0)
        assertEquals(7_000_000L, pong.g1)
        assertEquals(7_000_000L, pong.g2)
    }

    @Test
    fun theTwoStampsAreReadFromTheGlassesOwnClock() {
        val clock = FakeClock(nanos = 100L)
        val sent = mutableListOf<String>()
        val responder = GlassClockResponder(clock) { json -> sent += json; true }

        responder.onPing(HintCommand.Ping("s", p0 = 1L))
        clock.nanos = 200L
        responder.onPing(HintCommand.Ping("s", p0 = 2L))

        val stamps = sent.map { (HintWireCodec.decode(it) as HintCommand.Pong) }
        assertEquals(listOf(100L, 200L), stamps.map { it.g1 })
        assertEquals(listOf(100L, 200L), stamps.map { it.g2 })
    }

    @Test
    fun aRefusedReplyIsReportedRatherThanPretended() {
        val responder = GlassClockResponder(FakeClock()) { false }

        assertFalse("a reply the channel refused must not look like a successful handshake",
            responder.onPing(HintCommand.Ping("s", p0 = 1L)))
    }
}
