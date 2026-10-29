package com.sheen.rokid.phone.audio

import com.sheen.rokid.audio.VadDecision
import com.sheen.rokid.contracts.SessionState
import com.sheen.rokid.phone.overlay.BallPressLease
import com.sheen.rokid.phone.overlay.ballDictationRefusalNotice
import com.sheen.rokid.phone.ui.ComposerAction
import com.sheen.rokid.phone.ui.TypedInputState
import com.sheen.rokid.phone.ui.composerActionSlot
import com.sheen.rokid.phone.ui.composerDictationView
import com.sheen.rokid.phone.withP3Sink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.5.1 C3 (`review Important #2`): one dictation lease, two surfaces, and each surface only gets its own.
 *
 * The composer page and the floating ball share `HostCompositionRoot`'s single dictation lease and its one
 * `DictationUiState` flow. Before this task the ball's release called the owner-less `stopDictation()`, so a long press
 * refused because the composer was already listening still stopped the composer's lease on release; a BALL final set
 * `final = null` and wiped a composer final that had not reached a page yet; and a BALL refusal was published into the
 * composer's status line. These tests pin the split:
 *
 * - only the surface that actually holds the lease can stop/cancel it;
 * - a refused BALL press changes nothing the composer owns (its lease, refusal, status or pending final);
 * - a BALL final goes to the typed/send path and leaves a composer final in place;
 * - the composer page renders a BALL-owned listening/terminal state, final or refusal as nothing of its own, and its
 *   one action is Stop only for a COMPOSER lease;
 * - the ball's own press record decides the mic and the release, so a refused press shows no microphone and stops
 *   nothing.
 *
 * The real composition rig is shared with `DictationWiringTest` (`DictationRig.kt`), so the lease is the production one
 * (real `CxrAudioChain`/`SpeechPipeline`, fake recorder/CXR transport), not a mock of the host methods under test.
 */
class DictationOwnerTest {

    @Test
    fun `a ball press while composer is listening does not stop the composer lease`() {
        withRig(detached = true) { rig ->
            assertTrue(rig.activate())
            assertEquals("the composer starts its own lease", DictationStartResult.Accepted, rig.root.startDictation())
            assertEquals(DictationState.Listening, stateOf(rig))
            assertEquals(DictationTarget.COMPOSER, rig.root.dictationUi.value.owner)

            // The ball asks for a lease while the composer holds it: one lease, one source - refused busy.
            assertEquals(
                "a ball press must never steal the composer's lease",
                DictationStartResult.Busy,
                rig.root.startDictation(DictationTarget.BALL),
            )
            assertEquals("the composer keeps listening", DictationState.Listening, stateOf(rig))
            assertEquals(DictationTarget.COMPOSER, rig.root.dictationUi.value.owner)
            assertNull(
                "a BALL refusal belongs to the ball's notice, not to the composer's status line",
                rig.root.dictationUi.value.refusal,
            )

            // Releasing the ball press asks to stop the BALL lease; the owner-aware stop must not touch the composer's.
            rig.root.stopDictation(DictationTarget.BALL)
            assertEquals(
                "a BALL release must not stop a COMPOSER lease",
                DictationState.Listening,
                stateOf(rig),
            )
            assertNull("and it must not deliver the composer's final early", rig.root.dictationUi.value.final)

            // The same guard applies to cancel: a BALL cancel must not cancel the composer's lease either.
            rig.root.cancelDictation(DictationTarget.BALL)
            assertEquals(
                "a BALL cancel must not cancel a COMPOSER lease",
                DictationState.Listening,
                stateOf(rig),
            )

            // The composer's own stop still ends its lease and delivers its final.
            rig.asr.finalText = "合成器说的话"
            rig.pipelineVad.decision = VadDecision(true, false)
            rig.feedPhone()
            rig.root.stopDictation(DictationTarget.COMPOSER)
            assertEquals("合成器说的话", rig.root.dictationUi.value.final?.text)
            assertEquals(DictationState.Idle, stateOf(rig))
        }
    }

    @Test
    fun `a ball refusal does not consume the composer final`() {
        withRig(detached = true) { rig ->
            assertTrue(rig.activate())
            // A composer dictation delivered its final; no page is composed, so the host still owns it (0.4 fix 1).
            val delivered = dictatedFinal(rig, "先说的话")
            assertEquals(delivered, rig.root.dictationUi.value.final)

            // The session stops; the ball's next press has no session to start under and is refused.
            rig.root.stop()
            assertEquals(DictationStartResult.NoSession, rig.root.startDictation(DictationTarget.BALL))

            assertEquals(
                "a refused BALL press must not consume the composer's pending final",
                delivered,
                rig.root.dictationUi.value.final,
            )
            assertNull(
                "a BALL refusal belongs to the ball's own notice, not the composer's status line",
                rig.root.dictationUi.value.refusal,
            )
            assertEquals(
                "the composer page still receives the pending final after the BALL refusal",
                delivered,
                composerDictationView(rig.root.dictationUi.value).final,
            )
        }
    }

    @Test
    fun `an accepted ball lease leaves composer untouched and stops only BALL on release`() {
        withRig(detached = true) { rig ->
            assertTrue(rig.activate())

            // A composer final is waiting for a page that is not composed (tab switch); the host owns it until consumed.
            val composerFinal = dictatedFinal(rig, "草稿里的话")

            // The ball acquires its own lease on the live session.
            rig.asr.finalText = "球说的话"
            assertEquals(DictationStartResult.Accepted, rig.root.startDictation(DictationTarget.BALL))
            assertEquals(DictationTarget.BALL, rig.root.dictationUi.value.owner)
            assertEquals(DictationState.Listening, stateOf(rig))
            assertEquals(
                "an accepted BALL lease must not drop the composer's pending final",
                composerFinal,
                rig.root.dictationUi.value.final,
            )

            // A stop for the composer must not stop the ball's lease.
            rig.root.stopDictation(DictationTarget.COMPOSER)
            assertEquals(
                "a COMPOSER stop must not stop a BALL lease",
                DictationState.Listening,
                stateOf(rig),
            )

            // Releasing the ball stops only the ball: its words go to the send path, never to the composer's final.
            val lines = withP3Sink { captured ->
                rig.pipelineVad.decision = VadDecision(true, false)
                rig.feedPhone()
                rig.root.stopDictation(DictationTarget.BALL)
                captured.toList()
            }
            assertTrue(
                "the ball's final is sent as a user message: $lines",
                lines.any { it.startsWith("P4_TYPED state=accepted") },
            )
            assertEquals(
                "the composer's final is still the one waiting for its page",
                composerFinal,
                rig.root.dictationUi.value.final,
            )
            assertEquals(
                "the composer page still receives the composer's own final",
                composerFinal,
                composerDictationView(rig.root.dictationUi.value).final,
            )
        }
    }

    @Test
    fun `the composer page ignores a BALL lease's final state and refusal`() {
        // A BALL-owned value is foreign to the composer page even if the shared flow carries one. (Production never
        // publishes a BALL final into this flow; the page rule is defensive and must hold anyway.)
        val ballOwned = DictationUiState(
            state = DictationState.Listening,
            final = DictationFinal(7, "球说的话"),
            refusal = DictationStartResult.Busy,
            owner = DictationTarget.BALL,
        )
        val view = composerDictationView(ballOwned)
        assertEquals("the ball's listening state is not the composer's", DictationState.Idle, view.state)
        assertNull("the ball's final must not be appended to the draft", view.final)
        assertNull("the ball's refusal must not take the composer's status line", view.refusal)

        // Consequently the composer's one action is not Stop while another surface owns the lease.
        assertEquals(
            ComposerAction.Microphone,
            composerActionSlot(SessionState.Listening, TypedInputState(), view.state, owner = ballOwned.owner).action,
        )
        assertNotEquals(
            "the composer must never offer a Stop for someone else's lease",
            ComposerAction.Stop,
            composerActionSlot(SessionState.Listening, TypedInputState(), view.state, owner = ballOwned.owner).action,
        )

        // A COMPOSER lease is passed through untouched, including its Stop action.
        val composerOwned = DictationUiState(state = DictationState.Listening, owner = DictationTarget.COMPOSER)
        assertEquals(DictationState.Listening, composerDictationView(composerOwned).state)
        assertEquals(
            ComposerAction.Stop,
            composerActionSlot(
                SessionState.Listening,
                TypedInputState(),
                composerDictationView(composerOwned).state,
                owner = composerOwned.owner,
            ).action,
        )

        // A terminal composer state after the lease settled (owner cleared by the host's cleanup) is still the page's:
        // the wearer must see the no-speech/timeout/error explanation of the dictation it ran.
        val composerTerminal = DictationUiState(
            state = DictationState.NoSpeech,
            final = DictationFinal(8, "清理之后才被页面看到"),
            owner = null,
        )
        assertEquals(DictationState.NoSpeech, composerDictationView(composerTerminal).state)
        assertEquals(8L, composerDictationView(composerTerminal).final?.sequence)
    }

    @Test
    fun `a BALL dictation renders no composer Stop and delivers no composer final`() {
        // The same rule against the real host flow: an accepted BALL lease publishes owner=BALL + Listening, and the
        // composer page must render neither a Stop nor a final from it.
        withRig(detached = true) { rig ->
            assertTrue(rig.activate())
            assertEquals(DictationStartResult.Accepted, rig.root.startDictation(DictationTarget.BALL))
            val ui = rig.root.dictationUi.value
            assertEquals(DictationTarget.BALL, ui.owner)
            assertEquals(DictationState.Listening, ui.state)

            val view = composerDictationView(ui)
            assertEquals(DictationState.Idle, view.state)
            assertNull(view.final)
            assertNotEquals(
                ComposerAction.Stop,
                composerActionSlot(SessionState.Listening, TypedInputState(), view.state, owner = ui.owner).action,
            )

            rig.asr.finalText = "球的听写"
            rig.pipelineVad.decision = VadDecision(true, false)
            rig.feedPhone()
            rig.root.stopDictation(DictationTarget.BALL)
            assertNull("a BALL final is a message, not a composer draft final", rig.root.dictationUi.value.final)
        }
    }

    @Test
    fun `the ball shows a short notice and no mic when refused`() {
        // A refused press owns nothing: no microphone indicator, and its release must not stop any lease.
        val refused = BallPressLease()
        assertFalse("a refused start does not acquire the BALL lease", refused.begin(DictationStartResult.NoSession))
        assertFalse("a refused press shows no microphone", refused.micVisible(ballListening = false))
        assertFalse("a refused press stops nothing on release", refused.end())

        // An accepted press shows the microphone, stops its lease exactly once, and forgets it on release.
        val accepted = BallPressLease()
        assertTrue(accepted.begin(DictationStartResult.Accepted))
        assertTrue("an accepted press shows the microphone", accepted.micVisible(ballListening = false))
        assertTrue("releasing an accepted press stops the BALL lease", accepted.end())
        assertFalse("a second release must not stop anything", accepted.end())
        assertFalse("after release the microphone is gone", accepted.micVisible(ballListening = false))

        // A live BALL listening state is shown even when this press did not acquire it (the state, not the press, says).
        assertTrue("a live BALL listening state shows the microphone", BallPressLease().micVisible(ballListening = true))

        // The refusal notice is short, Chinese, and tells the wearer how to get a session (C15 wording direction).
        val noSession = ballDictationRefusalNotice(DictationStartResult.NoSession)
        assertTrue("the no-session notice must point at the App: $noSession", "打开 App 开始会话" in noSession)
        assertTrue("the notice must stay short: $noSession", noSession.length <= 30)
        assertTrue("the notice must be Chinese: $noSession", noSession.any { it in '\u4e00'..'\u9fff' })
        listOf(
            DictationStartResult.Busy,
            DictationStartResult.Permission,
            DictationStartResult.NoSource,
            DictationStartResult.NoScheduler,
            DictationStartResult.StartFailed,
            DictationStartResult.Closed,
        ).forEach { result ->
            val notice = ballDictationRefusalNotice(result)
            assertTrue("refusal $result needs a notice", notice.isNotBlank())
            assertTrue("the notice must stay short: $notice", notice.length <= 30)
        }
    }

    @Test
    fun `a BALL lease that ends without speech clears its owner and leaves nothing on the composer page`() {
        // The controller's stop with no speech is a terminal NoSpeech and delivers no final. When that lease belongs to
        // the ball, `finishDictation` must clear the owner and reset the shared state: the ratified `owner == null`
        // projection rule only stays safe because a settled BALL lease carries no BALL state or final into the composer.
        withRig(detached = true) { rig ->
            assertTrue(rig.activate())
            assertEquals(DictationStartResult.Accepted, rig.root.startDictation(DictationTarget.BALL))
            assertEquals(DictationTarget.BALL, rig.root.dictationUi.value.owner)
            assertEquals(DictationState.Listening, stateOf(rig))

            // Release the ball with no speech: no transcript, so no final; the lease still settles and is cleaned up.
            rig.root.stopDictation(DictationTarget.BALL)

            val settled = rig.root.dictationUi.value
            assertNull("a settled BALL lease must release the flow's owner", settled.owner)
            assertEquals(
                "the BALL terminal state belongs to its local notice, not the shared flow",
                DictationState.Idle,
                settled.state,
            )
            assertNull("a BALL lease never publishes a draft final", settled.final)
            val view = composerDictationView(settled)
            assertEquals("the composer page sees the settled lease as idle", DictationState.Idle, view.state)
            assertNull("and has no stale BALL final to append", view.final)
            assertNull("and no BALL refusal to explain", view.refusal)
            assertEquals(
                "the composer's action is the microphone again, never a Stop for a BALL lease",
                ComposerAction.Microphone,
                composerActionSlot(SessionState.Listening, TypedInputState(), view.state, owner = settled.owner).action,
            )
        }
    }

    @Test
    fun `aborting the session clears the BALL owner and the composer page sees an idle flow`() {
        // Session teardown aborts the lease without a final. The owner must go with it: a stale BALL marker would make
        // the page ignore a later composer final, and a stale Listening would offer it a Stop that owns nothing.
        withRig(detached = true) { rig ->
            assertTrue(rig.activate())
            assertEquals(DictationStartResult.Accepted, rig.root.startDictation(DictationTarget.BALL))
            assertEquals(DictationTarget.BALL, rig.root.dictationUi.value.owner)

            rig.root.stop()

            val settled = rig.root.dictationUi.value
            assertNull("aborting the session must clear the lease's owner", settled.owner)
            assertEquals("an aborted lease ends idle", DictationState.Idle, settled.state)
            assertNull("an aborted BALL lease delivers no final", settled.final)
            val view = composerDictationView(settled)
            assertEquals(DictationState.Idle, view.state)
            assertNull(view.final)
            assertNull(view.refusal)
            assertEquals(
                "the settled flow gives the composer its microphone back, never a Stop",
                ComposerAction.Microphone,
                composerActionSlot(SessionState.Listening, TypedInputState(), view.state, owner = settled.owner).action,
            )
        }
    }
}
