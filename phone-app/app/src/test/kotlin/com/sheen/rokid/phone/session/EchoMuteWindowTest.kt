package com.sheen.rokid.phone.session

import com.sheen.rokid.testsupport.FakeClock
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.4 detached wake acknowledgement (owner 2026-10-27): the echo-mute window that keeps the phone's own
 * acknowledgement out of the phone microphone's ASR. Half-duplex by design - while the App speaks, the capture
 * side is deaf - so the window is defined purely by playback events, never by what was played.
 *
 * 0.5.1 C5 removed the 120 s watchdog: a lost end callback is now recovered by an explicit [EchoMuteWindow.clear]
 * (manual BALL dictation, speech-off, session stop, long-press takeover), not by a silent time bound.
 */
class EchoMuteWindowTest {

    private val clock = FakeClock(1_000_000_000L)
    private val window = EchoMuteWindow(clock)

    @Test
    fun nothingIsMutedBeforeTheFirstOpen() {
        assertFalse(window.covers(clock.nowNanos()))
    }

    @Test
    fun openMutesFromTheOnsetAndCloseExtendsByTheReverbTail() {
        val onset = clock.nowNanos()
        window.open()
        assertTrue("the onset instant itself is inside the window", window.covers(onset))
        assertTrue(window.covers(clock.nowNanos()))

        window.close()
        assertTrue("the reverb tail stays muted", window.covers(clock.nowNanos()))
        clock.advance(EchoMuteWindow.DEFAULT_TAIL_NANOS)
        assertFalse("after the tail the microphone hears again", window.covers(clock.nowNanos()))
    }

    @Test
    fun framesCapturedBeforeTheOpenStayAudible() {
        val pre = clock.nowNanos()
        clock.advance(20_000_000)
        window.open()
        assertFalse("pre-ack user audio must not be muted", window.covers(pre))
    }

    @Test
    fun aLostEndCallbackKeepsMutingWithoutATimeBound() {
        // 0.5.1 C5: the 120 s watchdog is gone. The old bound silently unmuted the phone mic halfway through a long
        // answer, so the ASR transcribed the App's own voice. Only an explicit clear may release a lost callback.
        window.open()
        clock.advance(120_000_000_000L)
        assertTrue(
            "a lost onDone must keep the window open until an explicit clear, not expire by time",
            window.covers(clock.nowNanos()),
        )
    }

    @Test
    fun aManualClearReleasesALostEndCallbackImmediately() {
        window.open()
        clock.advance(120_000_000_000L)
        assertTrue("the lost callback is still muting", window.covers(clock.nowNanos()))

        window.clear()
        assertFalse("the next manual dictation / stop must release it", window.covers(clock.nowNanos()))
    }

    @Test
    fun clearIsImmediateEvenBetweenCloseAndTheEndOfTheReverbTail() {
        window.open()
        window.close()
        assertTrue("the reverb tail is still muted", window.covers(clock.nowNanos()))

        window.clear()
        assertFalse("an explicit clear must not wait for the tail", window.covers(clock.nowNanos()))
    }

    @Test
    fun clearAfterTheTailEndedIsATrueNoOp() {
        window.open()
        window.close()
        clock.advance(EchoMuteWindow.DEFAULT_TAIL_NANOS)
        assertFalse(window.covers(clock.nowNanos()))

        window.clear()
        // A stale close must not re-arm a cleared window; that is why clear resets openAt as well as endAt.
        window.close()
        assertFalse("a cleared window cannot be re-muted by a late close", window.covers(clock.nowNanos()))
    }

    @Test
    fun clearWithoutOpenIsANoOpAndALaterOpenStillWorks() {
        window.clear()
        assertFalse(window.covers(clock.nowNanos()))

        window.open()
        assertTrue("a clear before the first open must not disarm later playback", window.covers(clock.nowNanos()))
    }

    @Test
    fun openNearTheFarEndOfTheClockDoesNotWrapIntoThePast() {
        // The removed watchdog computed `now + 120 s` in `open()`. Near Long.MAX_VALUE that overflows negative and the
        // window fails to cover its own onset (and `close()` would overflow the same way); the 0.5.1 window saturates.
        clock.nowNanos = Long.MAX_VALUE - 1
        window.open()
        assertTrue("an open at the far end of the clock must cover its own instant", window.covers(clock.nowNanos()))

        window.close()
        assertTrue(
            "the reverb tail must saturate at Long.MAX_VALUE instead of overflowing into the past",
            window.covers(Long.MAX_VALUE - 1),
        )
    }

    @Test
    fun closeWithoutOpenIsANoOpAndReopenRestartsTheWindow() {
        window.close()
        assertFalse(window.covers(clock.nowNanos()))

        window.open()
        window.close()
        clock.advance(EchoMuteWindow.DEFAULT_TAIL_NANOS)
        assertFalse(window.covers(clock.nowNanos()))

        window.open()
        assertTrue(window.covers(clock.nowNanos()))
    }
}
