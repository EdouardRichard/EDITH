package com.sheen.rokid.phone.session

import com.sheen.rokid.testsupport.FakeClock
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.4 detached wake acknowledgement (owner 2026-10-27): the echo-mute window that keeps the phone's own
 * acknowledgement out of the phone microphone's ASR. Half-duplex by design - while the App speaks, the capture
 * side is deaf - so the window is defined purely by playback events, never by what was played.
 */
class EchoMuteWindowTest {

    private val clock = FakeClock(1_000_000_000L)
    private val window = EchoMuteWindow(clock)

    @Test
    fun nothingIsMutedBeforeTheFirstOpen() {
        assertFalse(window.covers(clock.nowNanos()))
    }

    @Test
    fun openMutesFromTheRequestMomentAndCloseExtendsByTheReverbTail() {
        val before = clock.nowNanos()
        window.open()
        assertTrue("the request moment itself is inside the window", window.covers(before))
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
    fun aMissingEndCallbackIsBoundedByTheWatchdog() {
        window.open()
        clock.advance(EchoMuteWindow.DEFAULT_WATCHDOG_NANOS)
        assertFalse("a lost onDone must not deafen the session forever", window.covers(clock.nowNanos()))
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
