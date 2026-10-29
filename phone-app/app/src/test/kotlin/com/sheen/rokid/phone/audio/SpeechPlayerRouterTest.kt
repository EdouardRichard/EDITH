package com.sheen.rokid.phone.audio

import com.sheen.rokid.contracts.Assistance
import com.sheen.rokid.contracts.Generation
import com.sheen.rokid.contracts.SessionId
import com.sheen.rokid.contracts.SpeechRequest
import com.sheen.rokid.plugin.CancellationHandle
import com.sheen.rokid.plugin.SpeechPlayback
import com.sheen.rokid.plugin.SpeechPlayer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

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

    /**
     * A player whose [play] blocks, so the test can drive an off/stop in the window between the router reading its
     * target and the platform player returning a handle. That window is where the review found the untracked handle.
     */
    private class BlockingPlayer : SpeechPlayer, AutoCloseable {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val cancels = AtomicInteger(0)
        var closes = 0

        override fun play(assistance: Assistance, onState: (SpeechPlayback) -> Unit): CancellationHandle {
            entered.countDown()
            release.await(5, TimeUnit.SECONDS)
            return object : CancellationHandle {
                override fun cancel() { cancels.incrementAndGet() }
            }
        }

        override fun close() { closes++ }
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

    // ---- 0.5.1 C5: the active owner -------------------------------------------------------------

    @Test
    fun detachingCancelsTheUtteranceInFlight() {
        val router = SpeechPlayerRouter()
        val player = FakePlayer()
        router.install(player)
        router.play(assistance()) { }

        router.install(null)

        assertEquals("detaching must stop what the router was tracking", 1, player.cancels)
        assertNull("and must not keep the detached player", router.current())
    }

    @Test
    fun detachingWhilePlayIsInFlightCancelsTheHandleInsteadOfTrackingIt() {
        // The review's race: `play` read the target, then `setSpeechEnabled(false)` ran before the handle was
        // published. The old code stored that handle as `active` with no player left to stop it, so disabling speech
        // left an utterance running and an echo window open.
        val router = SpeechPlayerRouter()
        val player = BlockingPlayer()
        router.install(player)
        val caller = thread(name = "router-play") { router.play(assistance()) { } }

        assertTrue("the player must be inside play() before the detach", player.entered.await(2, TimeUnit.SECONDS))
        router.install(null)
        player.release.countDown()
        caller.join(2_000)

        assertFalse("play must not stay blocked after the detach released the player", caller.isAlive)
        assertEquals("the raced handle must be cancelled exactly once", 1, player.cancels.get())
        assertEquals("the router must never close a player - its composition owner does", 0, player.closes)
        router.stopCurrent()
        assertEquals("the raced handle must not be left tracked for a later stop", 1, player.cancels.get())
        var state: SpeechPlayback? = null
        router.play(assistance()) { state = it }
        assertEquals("a later request fails instead of hanging on a detached router", SpeechPlayback.FAILED, state)
    }

    @Test
    fun stoppingWhilePlayIsInFlightCancelsTheHandleInsteadOfTrackingIt() {
        val router = SpeechPlayerRouter()
        val player = BlockingPlayer()
        router.install(player)
        val caller = thread(name = "router-play") { router.play(assistance()) { } }

        assertTrue("the player must be inside play() before the stop", player.entered.await(2, TimeUnit.SECONDS))
        router.stopCurrent()
        player.release.countDown()
        caller.join(2_000)

        assertFalse("play must not stay blocked after the stop released the player", caller.isAlive)
        assertEquals("the raced handle must be cancelled exactly once", 1, player.cancels.get())
        assertEquals("the router must never close a player - its composition owner does", 0, player.closes)
        router.stopCurrent()
        assertEquals("a second stop must not cancel it again", 1, player.cancels.get())
        assertEquals("the target itself is still installed", player, router.current())
    }
}
