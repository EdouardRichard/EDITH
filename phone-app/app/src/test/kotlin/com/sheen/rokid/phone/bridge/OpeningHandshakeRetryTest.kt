package com.sheen.rokid.phone.bridge

import com.sheen.rokid.output.HintCommand
import com.sheen.rokid.output.HintWireCodec
import com.sheen.rokid.testsupport.FakeClock
import com.sheen.rokid.testsupport.ManualScheduler
import org.junit.Assert.*
import org.junit.Test

/**
 * D15: a `HELLO`/`PING` sent before the glasses app has subscribed is lost and never retried.
 *
 * Measured on 2026-10-09: `onOpenAppResult:true` at 11:43:29.854, `Hello`+`Ping` at .868/.869, but the glasses only
 * `subscribed` at 11:43:33.318 - so that whole session had no display and no audio, and only the *next* session
 * recovered. The opening handshake is unacknowledged, so the only observable signal that it cannot have been seen is
 * that no clock mapping ever becomes live.
 *
 * These tests pin the bounded repeat: it re-sends the same two commands (never a new wire shape), stops as soon as the
 * handshake is live, stops when the session ends, and stops itself at its bound.
 */
class OpeningHandshakeRetryTest {
    private class Rig(retryDelayMillis: Long = 1_000, maxRetries: Int = 5) : AutoCloseable {
        val clock = FakeClock()
        val scheduler = ManualScheduler(clock)
        private val sent = mutableListOf<String>()
        val link = CxrHintLink(clock, scheduler, sendJson = { json -> sent += json; true },
            retryDelayMillis = retryDelayMillis, maxRetries = maxRetries)
        val commands: List<HintCommand> get() = sent.mapNotNull(HintWireCodec::decode)
        val hellos: List<HintCommand.Hello> get() = commands.filterIsInstance<HintCommand.Hello>()
        val pings: List<HintCommand.Ping> get() = commands.filterIsInstance<HintCommand.Ping>()

        /** Moves the clock and runs everything that has come due, as the composition's real scheduler would. */
        fun elapse(millis: Long) { clock.nowNanos += millis * 1_000_000; scheduler.runDue() }

        /** One exchange that makes a mapping live, exactly as the glasses answer it. */
        fun completeExchange() {
            val ping = pings.last()
            clock.nowNanos = ping.p0 + 10_000_000
            link.onInbound(HintWireCodec.encode(HintCommand.Pong(ping.syncId, ping.p0,
                ping.p0 + 1_005_000_000, ping.p0 + 1_005_000_000)))
        }

        override fun close() = link.close()
    }

    @Test fun `an unanswered opening handshake is repeated over the same session`() {
        Rig().use { rig ->
            rig.link.sessionEstablished("s-1", 1)
            assertEquals(1, rig.hellos.size)
            assertEquals(1, rig.pings.size)

            rig.elapse(1_000)

            assertEquals("the session must be told again, because the first HELLO cannot have been seen",
                2, rig.hellos.size)
            assertEquals(2, rig.pings.size)
            assertEquals(listOf("s-1", "s-1"), rig.hellos.map { it.sessionId })
            assertNotEquals("the repeat starts a fresh exchange rather than replaying a dead ping",
                rig.pings[0].syncId, rig.pings[1].syncId)
        }
    }

    @Test fun `the repeat is bounded and stops itself`() {
        Rig(retryDelayMillis = 1_000, maxRetries = 2).use { rig ->
            rig.link.sessionEstablished("s-1", 1)

            repeat(6) { rig.elapse(1_000) }

            assertEquals("one opening HELLO plus exactly the bounded repeats", 3, rig.hellos.size)
            assertEquals(3, rig.pings.size)
        }
    }

    @Test fun `the repeat stops as soon as the handshake is live`() {
        Rig().use { rig ->
            rig.link.sessionEstablished("s-1", 1)

            rig.completeExchange()
            assertNotNull("the exchange must have made the display real", rig.link.liveOutput())

            repeat(3) { rig.elapse(1_000) }

            assertEquals("no repeat may follow a live handshake", 1, rig.hellos.size)
            assertEquals(1, rig.pings.size)
        }
    }

    @Test fun `ending the session stops the repeat`() {
        Rig().use { rig ->
            rig.link.sessionEstablished("s-1", 1)

            rig.link.sessionEnded()
            repeat(4) { rig.elapse(1_000) }

            assertEquals(1, rig.hellos.size)
            assertEquals(1, rig.pings.size)
        }
    }

    @Test fun `a later session gets its own bounded repeat`() {
        Rig().use { rig ->
            rig.link.sessionEstablished("s-1", 1)
            rig.link.sessionEnded()

            rig.link.sessionEstablished("s-2", 2)
            rig.elapse(1_000)

            assertEquals(listOf("s-1", "s-2", "s-2"), rig.hellos.map { it.sessionId })
        }
    }
}
