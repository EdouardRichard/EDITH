package edith.phone

import com.sheen.rokid.contracts.Generation
import com.sheen.rokid.contracts.SessionId
import com.sheen.rokid.plugin.CancellationHandle
import com.sheen.rokid.plugin.SpeechPlayback
import com.sheen.rokid.plugin.SpeechPlayer
import com.sheen.rokid.plugin.MonotonicClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase C (spec §6.4): the wake is answered out loud, at once, without waiting for the model.
 *
 * The fixed phrase is the whole point of the flow: `唤醒词检测成功 → 【同步】TTS 播报"在的"（固定短语，不等 LLM）`.
 * The two things this must not become are (a) another hint on the glasses — the wearer is looking at the loading ring,
 * not at a sentence saying the assistant heard them — and (b) something that waits for the model, which is exactly the
 * latency the fixed phrase exists to cover.
 *
 * The user's 「语音朗读」 switch is respected: it is a persisted choice (P5), and speaking to someone who turned speech
 * off would be the app deciding for them. That is why [speakWakeAcknowledgement] takes the player rather than building
 * one.
 */
class WakeAcknowledgementTest {

    private class RecordingPlayer : SpeechPlayer {
        val spoken = mutableListOf<String>()
        val routes = mutableListOf<com.sheen.rokid.contracts.AssistantRoute>()
        override fun play(
            assistance: com.sheen.rokid.contracts.Assistance,
            onState: (SpeechPlayback) -> Unit,
        ): CancellationHandle {
            spoken += assistance.text
            routes += assistance.route
            onState(SpeechPlayback.STARTED)
            onState(SpeechPlayback.FINISHED)
            return object : CancellationHandle { override fun cancel() = Unit }
        }
    }

    private val clock = object : MonotonicClock { override fun nowNanos() = 1_000_000_000L }

    @Test
    fun wakingSpeaksTheFixedPhraseExactlyOnce() {
        val player = RecordingPlayer()
        speakWakeAcknowledgement(player, SessionId("s-1"), Generation(2), clock)

        assertEquals(listOf(WakeAcknowledgement.PHRASE), player.spoken)
        assertEquals("在的", WakeAcknowledgement.PHRASE)
    }

    @Test
    fun theAcknowledgementIsSpeechOnlyAndCarriesNoRouteOfItsOwn() {
        // It is not a message in either conversation: it is the assistant saying "I am here", and putting it in the
        // record would show the wearer a sentence they never received as an answer.
        val player = RecordingPlayer()
        val assistance = speakWakeAcknowledgement(player, SessionId("s-1"), Generation(2), clock)

        assertEquals("wake-ack", assistance.hintId)
        assertEquals(SessionId("s-1"), assistance.sessionId)
        assertEquals(Generation(2), assistance.generation)
        assertTrue("speech must be requested, or the player is never asked to play", assistance.optionalSpeech != null)
        assertNull("no turn produced this, so it must not claim one", assistance.sourceTurnId)
    }

    @Test
    fun itExpiresQuicklySoItCannotHoldAnythingOpen() {
        // A fixed phrase outlives its usefulness in a couple of seconds, and a longer window would let a stale
        // acknowledgement be the reason something stays held.
        val player = RecordingPlayer()
        val assistance = speakWakeAcknowledgement(player, SessionId("s-1"), Generation(1), clock)

        assertTrue("the acknowledgement must expire, not linger", assistance.expiresAtNanos > clock.nowNanos())
        assertTrue(assistance.expiresAtNanos - clock.nowNanos() <= 5_000_000_000L)
    }

    @Test
    fun withTheSpeechSwitchOffNothingIsSpokenAndNothingBreaks() {
        // P5: the switch is off by default, and off means off. The wake still lands - the coordinator above this call
        // is what delivers the event - so the only thing that changes is the silence.
        val returned = speakWakeAcknowledgement(null, SessionId("s-1"), Generation(1), clock)

        assertEquals(WakeAcknowledgement.PHRASE, returned.text)
    }

    @Test
    fun aPlayerThatThrowsDoesNotTakeTheWakeDown() {
        // This runs on the path that emits `WakeDetected`; an exception here would lose the wake itself, which is the
        // opposite of what an acknowledgement is for.
        val exploding = object : SpeechPlayer {
            override fun play(
                assistance: com.sheen.rokid.contracts.Assistance,
                onState: (SpeechPlayback) -> Unit,
            ): CancellationHandle = throw IllegalStateException("the engine died")
        }

        val returned = speakWakeAcknowledgement(exploding, SessionId("s-1"), Generation(1), clock)

        assertEquals(WakeAcknowledgement.PHRASE, returned.text)
    }
}
