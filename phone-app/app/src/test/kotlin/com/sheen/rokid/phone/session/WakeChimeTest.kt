package com.sheen.rokid.phone.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.23 P5 (spec §5.3.4, U12/D135): the wake answer is a short chime, not the fixed TTS phrase.
 *
 * The two properties that matter are the ones a person notices: a second wake inside 800 ms must not double-chime
 * (the official `aiWakeSound` is debounced), and the 「语音朗读」 switch is the only thing that can silence it. The
 * Android tone generator is the untestable half; this pins the decision.
 */
class WakeChimeTest {

    @Test
    fun disabledNeverChimes() {
        assertFalse(WakeChime().onWake(1_000_000_000L, enabled = false))
    }

    @Test
    fun theFirstEnabledWakeChimes() {
        assertTrue(WakeChime().onWake(1_000_000_000L, enabled = true))
    }

    @Test
    fun aSecondWakeInside800msIsDebounced() {
        val chime = WakeChime()
        assertTrue(chime.onWake(1_000_000_000L, true))
        assertFalse("500 ms after the first is inside the debounce", chime.onWake(1_500_000_000L, true))
        assertTrue("800 ms exactly is the first instant that may chime again", chime.onWake(1_800_000_000L, true))
    }

    @Test
    fun resetForgetsTheDebounceWindow() {
        val chime = WakeChime()
        assertTrue(chime.onWake(1_000_000_000L, true))
        chime.reset()
        assertTrue(chime.onWake(1_000_000_100L, true))
    }

    @Test
    fun playAsksTheSinkOnlyWhenTheWakeIsAccepted() {
        val chime = WakeChime()
        var plays = 0
        val sink = WakeChimeSink { plays++; true }

        assertFalse("disabled means no sound and no sink call", chime.play(1_000_000_000L, false, sink))
        assertEquals(0, plays)

        assertTrue(chime.play(1_100_000_000L, true, sink))
        assertEquals(1, plays)
    }

    @Test
    fun aThrowingSinkDoesNotEscapePlay() {
        val chime = WakeChime()
        val sink = WakeChimeSink { throw IllegalStateException("audio died") }

        assertTrue("the wake still counts as handled", chime.play(1_000_000_000L, true, sink))
        assertFalse("and the debounce still applies", chime.play(1_200_000_000L, true, sink))
    }

    @Test
    fun theOutcomeSeparatesDisabledDebouncedPlayedAndFailed() {
        val chime = WakeChime()
        val sink = WakeChimeSink { true }
        assertEquals(WakeChimeOutcome.DISABLED, chime.playOutcome(1_000_000_000L, false, sink))
        assertEquals(WakeChimeOutcome.PLAYED, chime.playOutcome(1_100_000_000L, true, sink))
        assertEquals(WakeChimeOutcome.DEBOUNCED, chime.playOutcome(1_200_000_000L, true, sink))

        assertEquals(WakeChimeOutcome.FAILED,
            WakeChime().playOutcome(1_000_000_000L, true, WakeChimeSink { false }))
        assertEquals(WakeChimeOutcome.FAILED,
            WakeChime().playOutcome(1_000_000_000L, true, WakeChimeSink { error("engine died") }))
    }

    @Test
    fun theDebounceIsTheOfficialEightHundredMilliseconds() {
        assertTrue(WakeChime.DEBOUNCE_MILLIS == 800L)
    }
}
