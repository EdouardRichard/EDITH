package com.sheen.rokid.glasses.display

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.23 P3 (spec §5.1.4): the part of the voice orb that can be decided without Android.
 *
 * The official orb is a PAG binary whose curves are not available (D level), so EDITH redraws it: the **state** is the
 * contract and the drawing is an approximation. What a state changes - the head scale, how many wave arcs exist, and
 * the breathing alpha range - is fixed here so a regression fails a JVM test; only the `Canvas` calls remain untested.
 */
class OrbGeometryTest {

    @Test
    fun thinkingShrinksTheHeadAndEveryOtherStateKeepsFullSize() {
        assertEquals(0.75f, OrbGeometry.headScale(OrbState.THINKING), 0.001f)
        assertEquals(1f, OrbGeometry.headScale(OrbState.IDLE), 0.001f)
        assertEquals(1f, OrbGeometry.headScale(OrbState.LISTENING), 0.001f)
        assertEquals(1f, OrbGeometry.headScale(OrbState.ANSWERING), 0.001f)
    }

    @Test
    fun theWaveArcsMatchTheObservedOfficialStates() {
        // The listening capture shows one arc per side; the answering capture shows two; thinking has none.
        assertEquals(1, OrbGeometry.waveCount(OrbState.LISTENING))
        assertEquals(2, OrbGeometry.waveCount(OrbState.ANSWERING))
        assertEquals(0, OrbGeometry.waveCount(OrbState.IDLE))
        assertEquals(0, OrbGeometry.waveCount(OrbState.THINKING))
    }

    @Test
    fun theBreathingAlphaStaysVisibleAndMovesWithThePhase() {
        val low = OrbGeometry.waveAlpha(0f)
        val high = OrbGeometry.waveAlpha(1f)
        assertTrue("a wave that reaches zero alpha would disappear mid-breath", low > 0)
        assertTrue("alpha must stay inside a byte", high <= 255)
        assertTrue("the breath must be visible as motion", high > low)
        assertEquals(low, OrbGeometry.waveAlpha(-1f))
        assertEquals(high, OrbGeometry.waveAlpha(2f))
    }

    @Test
    fun theOrbBoxMatchesTheOfficialPixels() {
        assertEquals(55, OrbGeometry.WIDTH_PX)
        assertEquals(45, OrbGeometry.HEIGHT_PX)
        assertEquals(GlassesPanelSpec.VOICE_ORB_WIDTH_PX, OrbGeometry.WIDTH_PX)
        assertEquals(GlassesPanelSpec.VOICE_ORB_HEIGHT_PX, OrbGeometry.HEIGHT_PX)
    }

    @Test
    fun theBreathPeriodIsSlowEnoughToReadAsBreathing() {
        assertTrue("a sub-second breath would read as flicker on the waveguide",
            OrbGeometry.BREATH_MILLIS >= 800L)
        assertTrue(OrbGeometry.BREATH_MILLIS <= 2_000L)
    }
}
