package com.sheen.rokid.contracts

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class P3EventsTest {
    private val envelope = EventEnvelope(SessionId("s"), Generation(1), "e1", 0L, 1L)

    @Test fun `wake detection rejects a blank word`() {
        assertFailsWith<IllegalArgumentException> { WakeDetected(envelope, " ", 0.5) }
    }

    @Test fun `wake detection keeps the confidence inside the unit interval`() {
        assertEquals(0.0, WakeDetected(envelope, "edith", 0.0).confidence)
        assertEquals(1.0, WakeDetected(envelope, "edith", 1.0).confidence)
        assertFailsWith<IllegalArgumentException> { WakeDetected(envelope, "edith", -0.001) }
        assertFailsWith<IllegalArgumentException> { WakeDetected(envelope, "edith", 1.001) }
    }

    @Test fun `acoustic label has exactly three transitions`() {
        assertEquals(3, AcousticLabel.entries.size)
        assertEquals(
            listOf(AcousticLabel.Speech, AcousticLabel.Silence, AcousticLabel.Noisy),
            AcousticLabel.entries.toList(),
        )
    }

    @Test fun `context changed rejects a negative capture time`() {
        assertFailsWith<IllegalArgumentException> { ContextChanged(envelope, -1L, emptyMap()) }
    }

    @Test fun `context changed rejects blank or duplicate keys`() {
        assertFailsWith<IllegalArgumentException> { ContextChanged(envelope, 0L, mapOf(" " to "value")) }
        // A Map cannot hold duplicate keys; a duplicate literal is therefore rejected before construction.
        assertFailsWith<IllegalArgumentException> { ContextChanged(envelope, 0L, mapOf("base" to "a", "" to "b")) }
        assertEquals(2, ContextChanged(envelope, 0L, mapOf("a" to "1", "ab" to "1")).values.size)
    }

    @Test fun `confirmation result rejects a blank request id`() {
        assertFailsWith<IllegalArgumentException> { ConfirmationResult(envelope, "", true) }
    }

    @Test fun `new events are host events that expose their envelope`() {
        val events: List<HostEvent> = listOf(
            WakeDetected(envelope, "edith", 0.9),
            AcousticEvent(envelope, AcousticLabel.Silence),
            ContextChanged(envelope, 7L, mapOf("foreground" to "camera")),
            ConfirmationResult(envelope, "r1", false),
        )
        events.forEach { assertSame(envelope, it.envelope) }
    }
}
