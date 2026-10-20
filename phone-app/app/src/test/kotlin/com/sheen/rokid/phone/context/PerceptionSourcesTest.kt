package com.sheen.rokid.phone.context

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * edith-0.3 Phase 2 Task 4: the acoustic and glasses sources.
 *
 * Both are pure mapping over injected lambdas. The acoustic source must not invent a label when no host event has
 * arrived (spec §7.2: `acoustic=unavailable`, never a fabricated `speech`), and the glasses source must keep every
 * tri-state fact as reported - "no event yet" is `null`, not `false`. A throwing producer degrades only its own fact.
 */
class PerceptionSourcesTest {

    @Test
    fun withoutAnAcousticEventThereIsNoAcousticFact() {
        assertNull("no event must not become a fabricated speech label", AcousticSource { null }.snapshot())
    }

    @Test
    fun anAcousticLabelIsPublishedAsItsStableName() {
        assertEquals("Speech", AcousticSource { "Speech" }.snapshot())
        assertEquals("Noisy", AcousticSource { "Noisy" }.snapshot())
    }

    @Test
    fun aBlankAcousticLabelIsNoFact() {
        assertNull(AcousticSource { "   " }.snapshot())
    }

    @Test
    fun aThrowingAcousticReadIsUnavailable() {
        assertNull(AcousticSource { error("host closed") }.snapshot())
    }

    @Test
    fun everyGlassesFactCanStayUnknown() {
        assertEquals(
            GlassesPerception(connected = null, wearing = null, panelVisible = null),
            GlassesStateSource({ null }, { null }, { null }).snapshot(),
        )
    }

    @Test
    fun glassesFactsAreCarriedExactlyAsReported() {
        assertEquals(
            GlassesPerception(connected = true, wearing = false, panelVisible = true),
            GlassesStateSource({ true }, { false }, { true }).snapshot(),
        )
        assertEquals(
            GlassesPerception(connected = false, wearing = true, panelVisible = false),
            GlassesStateSource({ false }, { true }, { false }).snapshot(),
        )
    }

    @Test
    fun oneThrowingGlassesFactOnlyNullsThatFact() {
        val snapshot = GlassesStateSource(
            connected = { error("link died") },
            wearing = { false },
            panelVisible = { null },
        ).snapshot()

        assertEquals(GlassesPerception(connected = null, wearing = false, panelVisible = null), snapshot)
    }
}
