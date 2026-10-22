package com.sheen.rokid.phone.session

import com.sheen.rokid.audio.WakeWordDetector
import com.sheen.rokid.contracts.Generation
import com.sheen.rokid.contracts.InputEpoch
import com.sheen.rokid.contracts.SampleRange
import com.sheen.rokid.contracts.SessionId
import com.sheen.rokid.testsupport.FakeClock
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Task 14b: the suppression seam that stops the App's own audio from re-triggering the wake word.
 *
 * The gate is a depth counter rather than a flag because a playback can be nested (a hint whose speech is followed
 * by a second one before the first finishes): the gate must stay closed until the **last** playback ended. It is
 * built over an atomic because it is written by the audio/playback thread and read by the detection loop.
 *
 * The last test is the wiring, not the flag: the seam only works if `suppressWhile` is handed the real gate, so the
 * real detector is constructed the way production constructs it and driven with frames.
 */
class PlaybackGateTest {

    private val epoch = InputEpoch(SessionId("gate-session"), Generation(1), 1)
    private fun frame(index: Int) = com.sheen.rokid.audio.PcmFrame(
        epoch, FloatArray(320) { it.toFloat() / 320f }, 16000, SampleRange("gate", index * 320L, (index + 1) * 320L),
        index * 20_000_000L, (index + 1) * 20_000_000L)

    private class AlwaysSpeechVad : com.sheen.rokid.audio.VadEngine {
        var frames = 0
        override fun accept(frame: com.sheen.rokid.audio.PcmFrame) = com.sheen.rokid.audio.VadDecision(true, false).also { frames++ }
        override fun reset() = Unit
        override fun close() = Unit
    }

    /** Reports one configured word from every frame, so "was the engine reached" is observable. */
    private class EveryFrameWord(private val word: String) : com.sheen.rokid.audio.WakeWordEngine {
        var frames = 0
        override fun accept(frame: com.sheen.rokid.audio.PcmFrame): List<String> = listOf(word).also { frames++ }
        override fun reset() = Unit
        override fun close() = Unit
    }

    // ---- the flag -----------------------------------------------------------------------------------

    @Test
    fun theGateIsOpenBeforeAnyPlaybackAndClosesWhileOneIsInFlight() {
        val gate = PlaybackGate()

        assertFalse("nothing is playing before anything began", gate.isPlaying)
        gate.begin()
        assertTrue(gate.isPlaying)
        gate.end()
        assertFalse("the playback ended, so the microphone may be trusted again", gate.isPlaying)
    }

    @Test
    fun anUnmatchedEndNeverMakesTheGateLookPlaying() {
        val gate = PlaybackGate()

        gate.end()
        gate.end()

        assertFalse("an unbalanced end must not make the gate look busy", gate.isPlaying)
        // The old form of this test also demanded that "the next real playback must still close the gate", i.e. that a
        // stray end be *discarded*. That is the requirement `beginAndEndFromTwoThreadsLeaveTheGateOpenAgain` cannot
        // live with: with the clamp in place, an `end` that runs ahead of its matching `begin` is a lost update and a
        // balanced pair can leave the gate closed for the rest of the session. The clamp is gone; a stray end is now
        // credit, and `aStrayEndIsRecordedAsCreditRatherThanSwallowingTheNextRealBegin` pins that direction.
    }

    @Test
    fun nestedPlaybacksKeepTheGateClosedUntilTheLastOneEnds() {
        val gate = PlaybackGate()

        gate.begin()
        gate.begin()
        gate.end()
        assertTrue("one playback is still running, so the gate must stay closed", gate.isPlaying)
        gate.end()
        assertFalse(gate.isPlaying)
    }

    @Test
    fun beginAndEndFromTwoThreadsLeaveTheGateOpenAgain() {
        val gate = PlaybackGate()
        val iterations = 20_000
        val start = CountDownLatch(1)
        val done = CountDownLatch(2)

        val starter = Thread {
            start.await()
            repeat(iterations) { gate.begin() }
            done.countDown()
        }
        val ender = Thread {
            start.await()
            repeat(iterations) { gate.end() }
            done.countDown()
        }
        starter.start(); ender.start()
        start.countDown()

        assertTrue("the two threads must finish rather than deadlock", done.await(30, TimeUnit.SECONDS))
        starter.join(); ender.join()

        assertFalse("balanced begin/end from two threads leave nothing playing", gate.isPlaying)
    }

    @Test
    fun aStrayEndIsRecordedAsCreditRatherThanSwallowingTheNextRealBegin() {
        // The defect behind this test (found in the P3.2 full regression, 2026-10-09): `end` used to clamp the depth at
        // zero, so when `end` ran ahead of `begin` - which is what threads do - those decrements were **discarded** and
        // a balanced pair could still leave the gate closed forever, silently muting the wake word from then on.
        // The accepted trade-off is the opposite one: one stray `end` leaves a credit that swallows the *next* begin,
        // which costs one un-suppressed playback (mild, transient) instead of a gate that never opens (severe,
        // permanent). This test pins that documented direction.
        val gate = PlaybackGate()

        gate.end()
        assertFalse("an unmatched end never makes the gate look busy", gate.isPlaying)

        // ...and the credit it left is exactly one, so the next playback is not counted: the depth is -1 + 1 = 0.
        gate.begin()
        assertFalse("the stray end is a credit, not a lost update", gate.isPlaying)

        gate.begin()
        assertTrue("a second real playback closes the gate again", gate.isPlaying)
        gate.end()
        assertFalse(gate.isPlaying)
    }

    // ---- the wiring: the real detector over the real gate --------------------------------------------

    @Test
    fun theRealDetectorSuppressesForwardingAndEmissionWhileTheGateIsClosed() {
        val gate = PlaybackGate()
        val vad = AlwaysSpeechVad()
        val engine = EveryFrameWord("伊迪丝")
        val emitted = mutableListOf<String>()
        // Exactly the lambda the composition passes to the detector factory; the detector evaluates it per frame.
        val detector = WakeWordDetector(vad, engine, emit = { word, _ -> emitted += word }, clock = FakeClock(0),
            cooldownNanos = 0, suppressWhile = { gate.isPlaying })
        try {
            gate.begin()
            detector.accept(frame(0))
            assertEquals("a suppressed frame must not reach the detector's VAD", 0, vad.frames)
            assertEquals("a suppressed frame must not reach the wake engine", 0, engine.frames)
            assertTrue("a suppressed frame must emit nothing", emitted.isEmpty())

            gate.end()
            detector.accept(frame(1))
            assertEquals(1, vad.frames)
            assertEquals(1, engine.frames)
            assertEquals(listOf("伊迪丝"), emitted)

            gate.begin()
            detector.accept(frame(2))
            assertEquals("a playback that starts again must close the gate again", 1, vad.frames)
            assertEquals(listOf("伊迪丝"), emitted)
        } finally {
            detector.close()
        }
    }
}
