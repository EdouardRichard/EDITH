package com.sheen.rokid.phone.audio

import com.sheen.rokid.contracts.Assistance
import com.sheen.rokid.contracts.Generation
import com.sheen.rokid.contracts.SessionId
import com.sheen.rokid.contracts.SpeechRequest
import com.sheen.rokid.plugin.CancellationHandle
import com.sheen.rokid.plugin.SpeechPlayback
import com.sheen.rokid.plugin.SpeechPlayer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 0.5 (`D213`): the stable router that makes「语音朗读」effective at runtime.
 *
 * The composition's output path captures one player at assembly; these tests pin the three things that replace it: a
 * no-player request fails loudly instead of hanging the conversation, an installed player is delegated to, and disabling
 * stops the utterance in flight.
 */
class SpeechPlayerRouterTest {

    private fun assistance() = Assistance(
        hintId = "h-1",
        sessionId = SessionId("s-1"),
        generation = Generation(1),
        sourceTurnId = null,
        priority = 0,
        text = "答案",
        optionalSpeech = SpeechRequest(),
        expiresAtNanos = Long.MAX_VALUE,
    )

    private class FakePlayer : SpeechPlayer {
        var plays = 0
        var cancels = 0

        override fun play(assistance: Assistance, onState: (SpeechPlayback) -> Unit): CancellationHandle {
            plays++
            return object : CancellationHandle {
                override fun cancel() {
                    cancels++
                    onState(SpeechPlayback.FINISHED)
                }
            }
        }
    }

    @Test
    fun aRequestWithNoPlayerFailsInsteadOfBeingSilentlyAccepted() {
        val router = SpeechPlayerRouter()
        var state: SpeechPlayback? = null

        router.play(assistance()) { state = it }.cancel()

        assertEquals(SpeechPlayback.FAILED, state)
        assertNull("the router must not invent a target", router.current())
    }

    @Test
    fun anInstalledPlayerReceivesTheRequestAndTheHandleIsForwarded() {
        val router = SpeechPlayerRouter()
        val player = FakePlayer()
        router.install(player)

        val handle = router.play(assistance()) { }
        handle.cancel()

        assertEquals(1, player.plays)
        assertEquals(1, player.cancels)
    }

    @Test
    fun stopCurrentCancelsTheUtteranceInFlightOnce() {
        val router = SpeechPlayerRouter()
        val player = FakePlayer()
        router.install(player)
        router.play(assistance()) { }

        router.stopCurrent()
        router.stopCurrent()

        assertEquals(1, player.cancels)
    }

    @Test
    fun detachingMakesLaterRequestsFail() {
        val router = SpeechPlayerRouter()
        val player = FakePlayer()
        router.install(player)
        router.install(null)
        var state: SpeechPlayback? = null

        router.play(assistance()) { state = it }

        assertEquals(SpeechPlayback.FAILED, state)
        assertEquals(0, player.plays)
    }
}
