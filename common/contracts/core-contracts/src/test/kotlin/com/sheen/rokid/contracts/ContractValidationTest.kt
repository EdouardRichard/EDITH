package com.sheen.rokid.contracts

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ContractValidationTest {
    private val session = SessionId("s1")
    private val generation = Generation(2)
    private val envelope = EventEnvelope(session, generation, "e1", 0L, 1_000_000L)

    @Test fun `unknown audio format round trips`() {
        assertEquals(AudioFormat.Unknown, AudioFormat.Unknown)
        assertEquals(AudioFormat.Unknown, AudioSegment(envelope, AudioFormat.Unknown, ByteArray(0)).format)
    }

    @Test fun `transcript revisions are ordered within a turn`() {
        val first = TranscriptRevision(envelope, "turn", 1, "partial", false, 0L, 1L)
        val final = TranscriptRevision(envelope, "turn", 2, "final", true, 0L, 2L)
        assertTrue(final.isNewerThan(first))
        assertFalse(first.isNewerThan(final))
    }

    @Test fun `stale generation output is rejected`() {
        val output = Assistance("h1", session, Generation(1), null, 1, "hint", null, 2_000_000L)
        assertFalse(output.isCurrent(session, generation))
    }

    /**
     * Phase C (spec §6.6): the glasses keep one conversation buffer per route, so a hint has to say which
     * conversation it belongs to. The default keeps every existing caller's meaning — a hint built without a route
     * is a wake hint, which is what the whole pre-Phase-C code base was.
     */
    @Test fun `an assistance carries the route whose conversation it belongs to`() {
        val wake = Assistance("h1", session, generation, null, 1, "hint", null, 2_000_000L)
        assertEquals(AssistantRoute.WAKE, wake.route, "the default must keep the old meaning")

        val proactive = wake.copy(route = AssistantRoute.PROACTIVE)
        assertEquals(AssistantRoute.PROACTIVE, proactive.route)
        assertFalse(proactive == wake, "the route is part of the value, not decoration")
        assertEquals("hint", proactive.text, "adding the route must not disturb the other fields")
    }
}
