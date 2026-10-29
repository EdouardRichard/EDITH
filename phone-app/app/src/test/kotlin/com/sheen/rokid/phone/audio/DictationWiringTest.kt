package com.sheen.rokid.phone.audio

import com.sheen.rokid.audio.PcmOffer
import com.sheen.rokid.audio.VadDecision
import com.sheen.rokid.contracts.AssistantRoute
import com.sheen.rokid.contracts.SessionState
import com.sheen.rokid.phone.app.HostCompositionRoot
import com.sheen.rokid.phone.app.TypedSendResult
import com.sheen.rokid.phone.session.EchoMuteWindow
import com.sheen.rokid.phone.ui.TypedInputState
import com.sheen.rokid.phone.ui.dictationStartStatusText
import com.sheen.rokid.phone.ui.inputRowStatusText
import com.sheen.rokid.phone.ui.isNewDictationFinal
import com.sheen.rokid.phone.ui.typedResultLabel
import com.sheen.rokid.phone.withP3Sink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * 0.4 Task 12b-ii (spec §5.3, D204): the composition's phone-microphone dictation, wired through the **real**
 * `CxrAudioChain`/`SpeechPipeline` and the real `HostCompositionRoot` methods.
 *
 * The chain is the production one (fake engines, real loop/pipeline); only the Android recorder and the CXR SDK are
 * fake. That is what lets these tests pin the routing rules a device run cannot see from the screen:
 *
 * - **one lease, one source.** Detached dictation reuses the session's recorder/ingress/loop and never starts a second
 *   capture; glasses dictation opens the phone source only for the dictation and closes it afterwards.
 * - **KWS is not fed while dictating.** In detached mode the *same* loop keeps feeding the ASR turn but not the wake
 *   detector; in glasses mode the CXR loop is paused and a phone loop with the same pipeline runs without wake input.
 * - **the final goes to the input field only.** It reaches the host's dictation state and no model request / plugin
 *   turn is opened; the draft is appended by the UI helper (tested separately), never sent.
 * - **every terminal path restores the wake route.** Final, user stop, cancel, timeout and a capture failure all
 *   put the CXR loop / KWS acceptance back, and glasses mode sends no CXR command or transport restart to do it.
 * - **telemetry is count-only.** `P4_DICTATION` lines carry state/reason/character count and never the text.
 */
class DictationWiringTest {


    // ---- detached mode ------------------------------------------------------------------------------------------

    @Test
    fun ballDictationSendsTheFinalAsAUserMessageAndLeavesTheDraftAlone() {
        withRig(detached = true) { rig ->
            val text = "帮我看看这个页面"
            rig.asr.finalText = text
            val lines = withP3Sink { captured ->
                assertTrue(rig.activate())
                assertEquals(
                    "a BALL dictation starts on the same leased session",
                    DictationStartResult.Accepted,
                    rig.root.startDictation(DictationTarget.BALL),
                )
                assertEquals(DictationState.Listening, stateOf(rig))
                assertEquals("the overlay owns this lease", DictationTarget.BALL, rig.root.dictationUi.value.owner)

                rig.pipelineVad.decision = VadDecision(true, false)
                rig.feedPhone()
                rig.root.stopDictation(DictationTarget.BALL)
                captured.toList()
            }

            val ui = rig.root.dictationUi.value
            assertNull("a ball final must not enter the composer draft", ui.final)
            assertEquals(DictationState.Idle, ui.state)
            assertTrue(
                "the final must enter the production typed path: $lines",
                lines.any { it.startsWith("P4_TYPED state=accepted") },
            )
            // The conversation record is the plugin's typed-turn behavior (covered by the plugin's own tests); what this
            // host-level test proves is that the ball's final went to the typed path and not to the composer draft above.
        }
    }

    /**
     * 0.5.1 C4/C5: the manual BALL dictation is the phone-only model's next-sentence entry point, and the half-duplex
     * window is the one thing that can silently swallow its first frames. A lost TTS end callback no longer has a 120 s
     * watchdog, so `startDictation(BALL)` must drop the stale window before it listens.
     */
    @Test
    fun manualBallDictationClearsAStaleEchoWindowBeforeListening() {
        withRig(detached = true) { rig ->
            val window = EchoMuteWindow(rig.clock)
            rig.root.echoMute = window
            window.open()
            rig.clock.advance(121_000_000_000L)
            assertTrue("a lost end callback keeps the window open", window.covers(rig.clock.nowNanos()))

            assertTrue(rig.activate())
            assertEquals(
                "the BALL lease starts on the live session",
                DictationStartResult.Accepted,
                rig.root.startDictation(DictationTarget.BALL),
            )

            assertFalse(
                "a manual BALL dictation must not be swallowed by a stale echo window",
                window.covers(rig.clock.nowNanos()),
            )
        }
    }

    @Test
    fun detachedDictationReusesTheWakeLoopDisablesKwsAndHandsTheFinalToTheUiOnly() {
        withRig(detached = true) { rig ->
            val text = "帮我记一条笔记"
            rig.asr.finalText = text
            val lines = withP3Sink { captured ->
                assertTrue(rig.activate())
                assertEquals("a detached dictation starts on the armed session", DictationStartResult.Accepted, rig.root.startDictation())
                assertEquals(DictationState.Listening, stateOf(rig))

                // KWS off, ASR on: the dictation frame reaches the pipeline and not the detector.
                rig.pipelineVad.decision = VadDecision(true, false)
                val wakeBefore = rig.wakeEngine.frames.size
                rig.feedPhone()
                assertEquals("KWS must not see dictation audio", wakeBefore, rig.wakeEngine.frames.size)
                assertTrue("the shared pipeline must still transcribe it", rig.asr.frames.isNotEmpty())

                // The stop button produces the final through the same forced-end path.
                rig.root.stopDictation(DictationTarget.COMPOSER)
                captured.toList()
            }

            val ui = rig.root.dictationUi.value
            assertEquals("the final goes to the UI state", text, ui.final?.text)
            assertEquals(DictationState.Idle, ui.state)
            assertEquals("the final is consumed, not queued as a message", 0, rig.model.requests.get())
            assertTrue("no plugin conversation turn is opened", rig.root.conversationLog.entries(AssistantRoute.WAKE).isEmpty())
            assertEquals("detached dictation never creates a second recorder", 1, rig.devices.size)
            assertEquals(1, rig.devices.single().starts.get())
            assertEquals("the session's own capture is not stopped by dictation", 0, rig.devices.single().stops.get())
            assertNotNull("the session's epoch survives the dictation", rig.root.currentAudioEpoch())

            // Every terminal path restores wake acceptance on the same loop.
            rig.pipelineVad.decision = VadDecision(false, false)
            val wakeAfter = rig.wakeEngine.frames.size
            rig.feedPhone()
            assertEquals("wake acceptance must be restored after the final", wakeAfter + 1, rig.wakeEngine.frames.size)

            assertEquals(
                listOf("started", "final"),
                lines.filter { it.startsWith("P4_DICTATION") }.map { it.removePrefix("P4_DICTATION state=").substringBefore(' ') },
            )
            assertTrue(lines.first { it.startsWith("P4_DICTATION state=final") }.contains("chars=${text.length}"))
        }
    }

    @Test
    fun aSecondStartWhileListeningIsRefusedAndStartsNoSecondCapture() {
        withRig(detached = true) { rig ->
            val lines = withP3Sink { captured ->
                assertTrue(rig.activate())
                assertEquals(DictationStartResult.Accepted, rig.root.startDictation())
                val epoch = rig.epoch()
                val captureStarts = rig.devices.single().starts.get()

                assertEquals("one dictation at a time", DictationStartResult.Busy, rig.root.startDictation())
                assertEquals("the reason reaches the page as a retryable status", DictationStartResult.Busy, rig.root.dictationUi.value.refusal)
                assertEquals(DictationState.Listening, stateOf(rig))
                assertEquals("the refused start must not start a second recorder", captureStarts, rig.devices.single().starts.get())
                assertSame("the same epoch owns the lease", epoch, rig.root.currentAudioEpoch())
                captured.toList()
            }
            val refused = lines.single { it.startsWith("P4_DICTATION state=refused") }
            assertTrue("the refusal names busy: $refused", "reason=busy" in refused)
            assertTrue("no text field may appear in telemetry: $refused", "text=" !in refused)
        }
    }

    @Test
    fun aDictationWithoutALiveSessionIsRefusedAndNeverOpensTheMicrophone() {
        withRig(detached = true) { rig ->
            val lines = withP3Sink { captured ->
                assertEquals("no session means no dictation", DictationStartResult.NoSession, rig.root.startDictation())
                captured.toList()
            }
            assertEquals("the refusal is published for the input row", DictationStartResult.NoSession, rig.root.dictationUi.value.refusal)
            assertEquals(DictationState.Idle, stateOf(rig))
            assertTrue("the refused start opens no recorder", rig.devices.isEmpty())
            val refused = lines.single { it.startsWith("P4_DICTATION state=refused") }
            assertTrue("the refusal names the missing session: $refused", "reason=no_session" in refused)
        }
    }

    @Test
    fun aDictationIsRefusedWhenAWakeTurnAlreadyOwnsThePipeline() {
        withRig(detached = true) { rig ->
            assertTrue(rig.activate())
            rig.pipelineVad.decision = VadDecision(true, false)
            rig.feedPhone() // a wake request's speech turn is now in flight

            val lines = withP3Sink { captured ->
                assertEquals("a dictation must not capture a wake request's final", DictationStartResult.Busy, rig.root.startDictation())
                captured.toList()
            }
            assertEquals(DictationStartResult.Busy, rig.root.dictationUi.value.refusal)
            assertEquals(DictationState.Idle, stateOf(rig))
            assertTrue(lines.single { it.startsWith("P4_DICTATION state=refused") }.contains("reason=busy"))
        }
    }

    @Test
    fun aMicrophoneWithoutTheRecordPermissionIsRefused() {
        withRig(detached = true, microphoneAllowed = false) { rig ->
            val lines = withP3Sink { captured ->
                assertTrue(rig.activate())
                assertEquals("a missing grant is surfaced, not silently ignored", DictationStartResult.Permission, rig.root.startDictation())
                captured.toList()
            }
            assertEquals(DictationStartResult.Permission, rig.root.dictationUi.value.refusal)
            assertTrue(lines.single { it.startsWith("P4_DICTATION state=refused") }.contains("reason=permission"))
            assertTrue(rig.devices.single().starts.get() == 1) // only the arm's own capture
        }
    }

    @Test
    fun aMissingDeadlineSchedulerIsSurfacedAsNotReadyInsteadOfPretendingToListen() {
        withRig(detached = true, withScheduler = false) { rig ->
            assertTrue(rig.activate())

            assertEquals("no deadline scheduler means no dictation", DictationStartResult.NoScheduler, rig.root.startDictation())

            assertEquals(DictationStartResult.NoScheduler, rig.root.dictationUi.value.refusal)
            assertEquals(DictationState.Idle, stateOf(rig))
            assertEquals("the refused start must not open the microphone", 1, rig.devices.single().starts.get())
        }
    }

    /**
     * Review finding 4c: the start result is returned **and** published so the row can show a retryable Chinese
     * status; a newer action (the send) clears it. The permission line names the permission to grant.
     */
    @Test
    fun aRefusedStartIsRetryableOnThePageAndDismissedByTheNextAction() {
        withRig(detached = true, microphoneAllowed = false) { rig ->
            assertTrue(rig.activate())

            assertEquals(DictationStartResult.Permission, rig.root.startDictation())
            val refusal = rig.root.dictationUi.value.refusal
            assertEquals(DictationStartResult.Permission, refusal)
            val explanation = requireNotNull(dictationStartStatusText(refusal))
            assertTrue("the page explains which permission is missing: $explanation", "麦克风" in explanation && "权限" in explanation)
            assertEquals(
                "the refusal owns the row status until dismissed",
                explanation,
                inputRowStatusText(SessionState.Listening, TypedInputState(), stateOf(rig), refusal),
            )

            // Any newer action dismisses it; here the wearer sends a typed message instead.
            assertEquals(TypedSendResult.Accepted, rig.root.requestTypedMessage("改用打字"))
            assertNull("the refusal must not outlive the next action", rig.root.dictationUi.value.refusal)
        }
    }

    @Test
    fun stoppingTheSessionEndsTheDictationSealsItsTurnAndReleasesTheRecorder() {
        withRig(detached = true) { rig ->
            val lines = withP3Sink { captured ->
                assertTrue(rig.activate())
                assertEquals(DictationStartResult.Accepted, rig.root.startDictation())
                rig.pipelineVad.decision = VadDecision(true, false)
                rig.feedPhone()
                assertTrue(rig.chain.hasActiveTurn())

                rig.root.stop()

                assertEquals("a stopped session ends the dictation UI", DictationState.Idle, stateOf(rig))
                assertNull("teardown delivers no dictation final", rig.root.dictationUi.value.final)
                assertEquals("the dictation turn is sealed, not handed to the wake route", 1, rig.asr.finishes.get())
                assertFalse(rig.chain.hasActiveTurn())
                assertEquals("the detached recorder is released by the session teardown", 1, rig.devices.single().stops.get())
                assertNull(rig.root.currentAudioEpoch())
                assertEquals("no model request was opened by the dictation", 0, rig.model.requests.get())
                captured.toList()
            }
            assertTrue(lines.any { it.startsWith("P4_DICTATION state=cancel") && "reason=session_end" in it })
        }
    }

    @Test
    fun theFifteenSecondDeadlineEndsAsTimeoutAndRestoresWakeAcceptance() {        withRig(detached = true) { rig ->
            val lines = withP3Sink { captured ->
                assertTrue(rig.activate())
                assertEquals(DictationStartResult.Accepted, rig.root.startDictation())

                rig.clock.advance(15_000_000_000L)
                rig.scheduler.runDue()

                assertEquals(DictationState.NoSpeech, stateOf(rig))
                assertNull("a timeout delivers no final", rig.root.dictationUi.value.final)
                assertEquals("the call did not produce a final", 0, rig.asr.finishes.get())
                captured.toList()
            }

            assertEquals("timeout is its own count-only state", 1,
                lines.count { it.startsWith("P4_DICTATION state=timeout") })
            rig.pipelineVad.decision = VadDecision(false, false)
            val wakeBefore = rig.wakeEngine.frames.size
            rig.feedPhone()
            assertEquals("timeout must give the wake route back", wakeBefore + 1, rig.wakeEngine.frames.size)
        }
    }

    @Test
    fun cancelEndsWithoutAFinalSealsTheTurnAndRestoresWakeAcceptance() {
        withRig(detached = true) { rig ->
            val lines = withP3Sink { captured ->
                assertTrue(rig.activate())
                assertEquals(DictationStartResult.Accepted, rig.root.startDictation())
                rig.pipelineVad.decision = VadDecision(true, false)
                rig.feedPhone()
                assertTrue("a turn is open before cancel", rig.chain.hasActiveTurn())

                rig.root.cancelDictation(DictationTarget.COMPOSER)

                assertEquals(DictationState.Idle, stateOf(rig))
                assertNull("cancel delivers no final", rig.root.dictationUi.value.final)
                assertEquals("cancel seals the pipeline turn", 1, rig.asr.finishes.get())
                assertFalse("no dictation turn is left in the pipeline", rig.chain.hasActiveTurn())
                captured.toList()
            }
            assertEquals(1, lines.count { it.startsWith("P4_DICTATION state=cancel") })

            rig.pipelineVad.decision = VadDecision(false, false)
            val wakeBefore = rig.wakeEngine.frames.size
            rig.feedPhone()
            assertEquals(wakeBefore + 1, rig.wakeEngine.frames.size)
        }
    }

    @Test
    fun dictationTelemetryIsCountOnlyAndNeverCarriesTheText() {
        withRig(detached = true) { rig ->
            val secret = "这是一句绝不能进日志的听写正文"
            rig.asr.finalText = secret
            val lines = withP3Sink { captured ->
                assertTrue(rig.activate())
                assertEquals(DictationStartResult.Accepted, rig.root.startDictation())
                rig.pipelineVad.decision = VadDecision(true, false)
                rig.feedPhone()
                rig.root.stopDictation(DictationTarget.COMPOSER)
                captured.toList()
            }

            val dictation = lines.filter { it.startsWith("P4_DICTATION") }
            assertTrue(dictation.isNotEmpty())
            dictation.forEach { line ->
                assertTrue("every line carries the count: $line", "chars=" in line)
                assertTrue("every line carries a reason: $line", "reason=" in line)
                assertFalse("the dictated text must never be logged: $line", secret in line)
                assertFalse("no text field may exist: $line", "text=" in line)
                assertFalse("no digest may exist: $line", "sha" in line.lowercase() || "hash" in line.lowercase())
            }
            assertEquals(
                "the final line's count is the transcript length",
                secret.length,
                dictation.last { it.startsWith("P4_DICTATION state=final") }
                    .substringAfter("chars=").substringBefore(' ').toInt(),
            )
            assertTrue(dictation.none { secret in it })
        }
    }

    /**
     * 0.4 Task 13d: audio that follows a dictation final is still the wearer's dictation, not a conversation turn.
     *
     * Device evidence (2026-10-27 D8): after `P4_DICTATION state=final chars=14`, the restored wake route transcribed
     * the dictation tail into a new pipeline turn (`P3_ASR revision=0..3`, no `P3_WAKE`), and the plugin queued that
     * final as the previous answer's continuation (`P3_CONV queue route=WAKE chars=18`). The queued utterance made the
     * typed-send predicate busy, so the next send answered `P4_TYPED refused reason=busy`. The wake **frames** stay
     * restored (KWS must still hear a real wake word); what must not flow is the transcript, until an explicit input
     * re-opens the route.
     */
    @Test
    fun postDictationSpeechDoesNotReachThePluginUntilAnExplicitInput() {
        withRig(detached = true) { rig ->
            assertTrue(rig.activate())
            val lines = withP3Sink { captured ->
                dictatedFinal(rig, "这是一段听写")
                // The dictation's own final is delivered through its redirect, so the host transcript route saw nothing.
                assertEquals(0, rig.delivered.filterIsInstance<com.sheen.rokid.contracts.TranscriptEvent>().size)

                // The wake route's frames are still polled, so a new pipeline turn really does open and finalise...
                rig.pipelineVad.decision = VadDecision(true, true)
                rig.feedPhone()
                // ...but its transcript is the wearer's dictation, not a conversation turn.
                captured.toList()
            }

            assertEquals("the dictation final itself still reaches the field", "这是一段听写",
                rig.root.dictationUi.value.final?.text)
            assertEquals(
                "no dictation-tail transcript may reach the host/plugin: " +
                    rig.delivered.filterIsInstance<com.sheen.rokid.contracts.TranscriptEvent>(),
                0, rig.delivered.filterIsInstance<com.sheen.rokid.contracts.TranscriptEvent>().size,
            )
            assertTrue("the drop is visible in count-only telemetry; lines=" + lines.joinToString(" | "),
                lines.any { it.startsWith("P3_TRANSCRIPT_ROUTE state=drop") && "reason=dictation" in it })
            assertTrue("the live route is closed for the input row",
                lines.any { it.startsWith("P3_TRANSCRIPT_ROUTE state=closed") && "reason=dictation" in it })
            assertEquals("no model request may be started by dictation audio", 0, rig.model.requests.get())

            // An explicit input re-opens the route: the conversation's next sentence is accepted again.
            val after = withP3Sink { captured ->
                assertEquals(TypedSendResult.Accepted, rig.root.requestTypedMessage("改用打字"))
                rig.pipelineVad.decision = VadDecision(true, true)
                rig.feedPhone()
                captured.toList()
            }
            assertTrue("an explicit input must re-open the transcript route",
                after.any { it.startsWith("P3_TRANSCRIPT_ROUTE state=open") && "reason=typed" in it })
            assertTrue("after the explicit input the next final reaches the host/plugin",
                rig.delivered.filterIsInstance<com.sheen.rokid.contracts.TranscriptEvent>().isNotEmpty())
            assertTrue("and no drop is recorded against it",
                after.none { it.startsWith("P3_TRANSCRIPT_ROUTE state=drop") })
        }
    }

    /**
     * 0.4 Task 13d, observation 2: a provider fault pauses the shared pipeline, and an in-process session stop/start
     * must re-arm it instead of leaving ASR silent until the App is force-stopped.
     *
     * Device evidence (2026-10-27): after one in-process stop/start, two dictations ended `timeout chars=0` and a
     * KWS-hit wake turn produced no `P3_ASR`, while frames and KWS kept working; force-stop + cold launch restored
     * ASR. The pipeline is process-lifetime (one ~240 MB model load), and `fault()` had no re-arm path (R-CD-3), so
     * the first `fault` after a session boundary silenced every later session.
     */
    @Test
    fun aProviderFaultPausesAudioAndTheNextSessionReArmsIt() {
        withRig(detached = true) { rig ->
            assertTrue(rig.activate())
            val firstEpoch = rig.epoch()

            // One transient provider failure is enough to pause the pipeline for this session.
            rig.pipelineVad.failure = IllegalStateException("synthetic provider failure")
            rig.feedPhone()
            rig.pipelineVad.failure = null
            val acceptedAtFault = rig.asr.frames.size
            rig.feedPhone()
            assertEquals("a paused pipeline rejects the rest of its session's audio", acceptedAtFault, rig.asr.frames.size)

            // The production stop/start paths, in-process: a new session must recover the same pipeline.
            val recovery = withP3Sink { captured ->
                rig.root.stop()
                rig.root.start("smart-assistant")
                rig.pipelineVad.decision = VadDecision(true, true)
                rig.feedPhone()
                captured.toList()
            }
            assertTrue("a start creates a new audio epoch", rig.epoch() != firstEpoch)
            assertTrue("a session re-arm is visible as a count-only state line",
                recovery.any { it.startsWith("P3_PIPELINE state=rearmed reason=session") })
            assertTrue("the new session's frame must reach the shared recognizer; lines=" +
                recovery.joinToString(" | "),
                rig.asr.frames.isNotEmpty() && rig.delivered.filterIsInstance<
                    com.sheen.rokid.contracts.TranscriptEvent>().isNotEmpty())
        }
    }

    /**
     * The keyword-spotting hit is the other explicit input that re-opens the route: the composition's `wakeEmit`
     * calls exactly this chain method on the same frame the detector fires, before the wake turn's ASR final lands.
     */
    @Test
    fun aWakeReopensTheTranscriptRouteADictationClosed() {
        withRig(detached = true) { rig ->
            assertTrue(rig.activate())
            dictatedFinal(rig, "听写")
            rig.pipelineVad.decision = VadDecision(true, true)
            rig.feedPhone()
            assertEquals("a closed route delivers nothing", 0,
                rig.delivered.filterIsInstance<com.sheen.rokid.contracts.TranscriptEvent>().size)

            rig.chain.openTranscriptRoute("wake")
            rig.pipelineVad.decision = VadDecision(true, true)
            rig.feedPhone()
            assertEquals("after a wake the next final reaches the host/plugin", 1,
                rig.delivered.filterIsInstance<com.sheen.rokid.contracts.TranscriptEvent>().size)
        }
    }

    // ---- glasses mode -------------------------------------------------------------------------------------------

    @Test
    fun glassesDictationPausesOnlyTheFrameLoopAndRestoresItWithoutCxrCommands() {
        withRig(detached = false) { rig ->
            val text = "眼镜模式用手机听写"
            rig.asr.finalText = text
            val lines = withP3Sink { captured ->
                assertTrue(rig.activate())
                assertEquals("the session's transport started once", 1, rig.transportPort.started.size)

                // Silence only reaches pre-roll, but the detector's own VAD is always speech: the CXR source is live.
                rig.feedCxr()
                val asrBefore = rig.asr.frames.size
                val wakeBefore = rig.wakeEngine.frames.size
                assertEquals(0, asrBefore)
                assertEquals(1, wakeBefore)

                assertEquals(DictationStartResult.Accepted, rig.root.startDictation())
                assertEquals("a glasses dictation opens exactly one phone capture", 1, rig.devices.size)

                // The CXR loop is paused: a CXR chunk stays queued, and the phone source reaches the same pipeline.
                assertEquals(com.sheen.rokid.audio.PcmOffer.ACCEPTED,
                    rig.cxrIngress.offer(rig.epoch(), ByteArray(PhoneMicFormat.CHUNK_BYTES)))
                rig.clock.advance(20_000_000)
                rig.scheduler.runDue()
                assertEquals("the paused CXR loop must not poll", asrBefore, rig.asr.frames.size)
                assertTrue("the CXR chunk is waiting, not consumed", rig.cxrIngress.queuedBytes > 0)

                rig.pipelineVad.decision = VadDecision(true, false)
                rig.feedPhone()
                assertEquals("the dictation frame reaches the shared pipeline", asrBefore + 1, rig.asr.frames.size)
                assertEquals("KWS must not see the phone dictation", wakeBefore, rig.wakeEngine.frames.size)

                rig.root.stopDictation(DictationTarget.COMPOSER)

                assertEquals("the final goes to the UI", text, rig.root.dictationUi.value.final?.text)
                assertEquals("the dictation capture is released once", 1, rig.devices.single().stops.get())
                assertEquals("the phone ingress is closed with the dictation", com.sheen.rokid.audio.PcmOffer.STALE_EPOCH,
                    rig.phoneIngress.offer(rig.epoch(), ByteArray(PhoneMicFormat.CHUNK_BYTES)))
                assertEquals("stale CXR audio is dropped on resume", 0, rig.cxrIngress.queuedBytes)
                assertEquals("no transport restart is needed", 1, rig.transportPort.started.size)
                assertTrue("the transport is not stopped", rig.transportPort.stopped.isEmpty())
                assertEquals("a dictation sends no CXR command", 0, rig.connectionCommands.get())
                captured.toList()
            }

            // The normal CXR loop and the wake route are back: a fresh CXR chunk reaches both.
            val asrAfter = rig.asr.frames.size
            val wakeAfter = rig.wakeEngine.frames.size
            rig.pipelineVad.decision = VadDecision(true, false)
            rig.feedCxr()
            assertEquals(asrAfter + 1, rig.asr.frames.size)
            assertEquals("wake acceptance is restored on the CXR loop", wakeAfter + 1, rig.wakeEngine.frames.size)
            assertTrue(lines.any { it.startsWith("P4_DICTATION state=final") })
        }
    }

    @Test
    fun aGlassesDictationCaptureFailureEndsAsAnErrorAndRestoresTheCxrLoop() {
        withRig(detached = false, queueDispatches = true, configurePhoneDevice = { failReadAt = 1 }) { rig ->
            assertTrue(rig.activate())
            val lines = withP3Sink { captured ->
                assertEquals(DictationStartResult.Accepted, rig.root.startDictation())
                val capture = rig.devices.single()
                awaitCondition { rig.pendingDispatches() >= 1 }
                rig.runDispatches()
                captured.toList()
            }

            assertEquals(DictationState.Error(PhoneMicCapture.MIC_UNAVAILABLE), stateOf(rig))
            assertEquals("the failed capture is released", 1, rig.devices.single().stops.get())
            assertNull(rig.root.dictationUi.value.final)
            assertTrue(lines.any { it.startsWith("P4_DICTATION state=error") && "reason=${PhoneMicCapture.MIC_UNAVAILABLE}" in it })

            // The CXR loop is back: a fresh chunk is consumed and reaches the wake path again.
            val asrBefore = rig.asr.frames.size
            val wakeBefore = rig.wakeEngine.frames.size
            rig.pipelineVad.decision = VadDecision(true, false)
            rig.feedCxr()
            assertEquals(asrBefore + 1, rig.asr.frames.size)
            assertEquals(wakeBefore + 1, rig.wakeEngine.frames.size)
            assertEquals("no transport restart from the failure", 1, rig.transportPort.started.size)
            assertTrue(rig.transportPort.stopped.isEmpty())
            assertEquals(0, rig.connectionCommands.get())
        }
    }

    // ---- fix 1: the paused CXR transport must not leak into a dictation ------------------------------------------

    /**
     * Fix-1 regression: the CXR transport keeps offering PCM while a glasses-mode dictation has paused its loop.
     *
     * Those outcomes belong to the paused route, not to the dictation. Before the fix, the first post-pause ACCEPTED
     * offer reached `resolved()`, built a normal CXR loop, and the OVERFLOW then called `onOffer` on it - which
     * `framer.reset()`s the **same** `PcmFramer` the phone dictation loop is framing with. The next dictation chunk
     * was framed from offset 0 with an unchanged continuity id, so `SpeechPipeline.process` saw a range gap and
     * revoked the in-flight turn (truncated/lost speech). This drives the real `HostCompositionRoot.onPcmOffer` seam
     * per datagram, as `RokidSessionTransport.received` does, and asserts the whole final and its framing survive.
     */
    @Test
    fun cxrOverflowDuringGlassesDictationBuildsNoLoopAndTheDictationFinalStaysIntact() {
        withRig(detached = false) { rig ->
            val text = "溢出之后仍要完整识别"
            rig.asr.finalText = text
            val lines = withP3Sink { captured ->
                assertTrue(rig.activate())
                assertNotNull("the armed session runs one normal CXR loop", rig.chain.normalLoopForProbe)
                assertEquals(DictationStartResult.Accepted, rig.root.startDictation())
                assertNull("the dictation pauses the normal CXR loop", rig.chain.normalLoopForProbe)

                rig.pipelineVad.decision = VadDecision(true, false)
                rig.feedPhone()
                assertTrue("a dictation turn is in flight", rig.chain.hasActiveTurn())
                val frames = rig.asr.frames

                // Every datagram the transport receives while no loop polls, verbatim through the host seam.
                assertEquals("the paused 2 s CXR ingress must overflow",
                    PcmOffer.OVERFLOW, rig.offerCxrUntilOverflow())
                assertTrue("the overflow stays visible to diagnostics", rig.offers.contains(PcmOffer.OVERFLOW))
                assertNull("a CXR offer must not rebuild a loop during a dictation",
                    rig.chain.normalLoopForProbe)
                assertTrue("the overflow is not a fault of the dictation route",
                    rig.faults.none { it == "INGRESS_OVERFLOW" })

                // More dictation audio: the shared framer must continue at the same offset, not restart.
                rig.feedPhone()
                assertEquals("both dictation frames reach the recognizer", 2, frames.size)
                assertEquals("the shared framer must not restart mid-dictation",
                    frames[0].range.endExclusive, frames[1].range.first)
                assertTrue("the in-flight turn must not be revoked", rig.revokedTurns.isEmpty())
                assertTrue("the dictation turn survives the overflow", rig.chain.hasActiveTurn())

                rig.root.stopDictation(DictationTarget.COMPOSER)
                captured.toList()
            }

            assertEquals("the gap-reset turn must not truncate the final",
                text, rig.root.dictationUi.value.final?.text)
            assertEquals(DictationState.Idle, rig.root.dictationUi.value.state)
            assertEquals("the final is forced exactly once", 1, rig.asr.finishes.get())
            assertEquals("one final line", 1, lines.count { it.startsWith("P4_DICTATION state=final") })
            assertNotNull("the normal CXR loop is restored", rig.chain.normalLoopForProbe)

            // Restored exactly one loop: one fresh CXR chunk reaches both consumers once.
            rig.pipelineVad.decision = VadDecision(true, false)
            val asrBefore = rig.asr.frames.size
            val wakeBefore = rig.wakeEngine.frames.size
            rig.feedCxr()
            assertEquals("the restored CXR loop polls the fresh chunk", asrBefore + 1, rig.asr.frames.size)
            assertEquals("wake acceptance is restored on the CXR loop", wakeBefore + 1, rig.wakeEngine.frames.size)
        }
    }

    /**
     * Fix-1 requirement 2: ending a glasses dictation drops the CXR audio (and the OVERFLOW gap) that queued while
     * the loop was paused, **before** the normal loop is restored. Otherwise the first tick of the restored loop
     * drains up to [AudioIngressLoop.MAX_CHUNKS_PER_TICK] stale chunks in one batch and can open a wake-route turn
     * from dictation-era audio. `finishDictation` closes the phone source first, then `CxrAudioChain.endDictation`
     * drains the CXR ingress; this test pins that order and its effect.
     */
    @Test
    fun endingGlassesDictationDropsQueuedCxrAudioBeforeTheNormalLoopReturns() {
        withRig(detached = false) { rig ->
            val text = "停止后不能补播旧音频"
            rig.asr.finalText = text
            assertTrue(rig.activate())
            assertEquals(DictationStartResult.Accepted, rig.root.startDictation())
            rig.pipelineVad.decision = VadDecision(true, false)
            rig.feedPhone()
            assertEquals("the paused CXR ingress starts empty", 0, rig.cxrIngress.queuedBytes)

            assertEquals("fill the paused ingress to its overflow point",
                PcmOffer.OVERFLOW, rig.offerCxrUntilOverflow())
            repeat(5) {
                assertEquals("post-overflow CXR audio queues up", PcmOffer.ACCEPTED, rig.offerCxr())
            }
            assertTrue("stale CXR audio is waiting", rig.cxrIngress.queuedBytes > 0)

            rig.root.stopDictation(DictationTarget.COMPOSER)

            assertEquals("queued CXR chunks must not survive the restore", 0, rig.cxrIngress.queuedBytes)
            assertNull("the overflow gap must be dropped with them", rig.cxrIngress.pollGap())
            assertTrue("a deliberate pause is not a gap of the restored loop",
                rig.faults.none { it.startsWith("GAP:") })

            // Exactly one fresh chunk: no burst of the stale audio through the restored loop.
            val asrBefore = rig.asr.frames.size
            val wakeBefore = rig.wakeEngine.frames.size
            rig.feedCxr()
            assertEquals("only the fresh CXR chunk reaches the pipeline", asrBefore + 1, rig.asr.frames.size)
            assertEquals("and reaches the wake route once", wakeBefore + 1, rig.wakeEngine.frames.size)
            assertEquals("the final is still delivered", text, rig.root.dictationUi.value.final?.text)
        }
    }

    // ---- fix 2: the final's lifetime is the host's, not the page's ------------------------------------------------

    /**
     * The page acknowledges a delivered final instead of remembering a raw sequence. Once the host has cleared it, a
     * returning/recreated page has nothing to append; the sequence nonetheless stays monotonic for the next final.
     */
    @Test
    fun aConsumedFinalIsClearedHostOwnedAndTheNextDictationGetsANewSequence() {
        withRig(detached = true) { rig ->
            assertTrue(rig.activate())

            val first = dictatedFinal(rig, "第一次")
            assertEquals("the first final of a composition is sequence 1", 1L, first.sequence)
            assertEquals("a final stays available until the page appends it", first, rig.root.dictationUi.value.final)

            rig.root.consumeDictationFinal(first.sequence)
            assertNull("the host must not hand the appended final out again", rig.root.dictationUi.value.final)

            // A repeated acknowledgement (a recomposition collecting a stale value, a second page) is a no-op.
            rig.root.consumeDictationFinal(first.sequence)
            assertNull(rig.root.dictationUi.value.final)

            val second = dictatedFinal(rig, "第二次")
            assertEquals("the sequence keeps counting across dictations", 2L, second.sequence)
            rig.root.consumeDictationFinal(first.sequence)
            assertEquals("a stale acknowledgement must not clear a newer final", second, rig.root.dictationUi.value.final)
            rig.root.consumeDictationFinal(second.sequence)
            assertNull(rig.root.dictationUi.value.final)
        }
    }

    /**
     * Tab switch (review finding 1a): the assistant page is disposed while the host keeps its flow. A final that
     * arrives while the page is away is delivered **once** to the fresh page that returns - not again on the next
     * return, because the host cleared it when that page acknowledged it.
     */
    @Test
    fun aFinalDeliveredWhileTheAssistantPageIsAwayIsAppendedOnceWhenAPageReturns() {
        withRig(detached = true) { rig ->
            assertTrue(rig.activate())

            // No page is composed for the assistant tab when the final arrives; nothing consumes it yet.
            val delivered = dictatedFinal(rig, "切走之后说的话")
            assertEquals(delivered, rig.root.dictationUi.value.final)

            // Returning creates a fresh page: its own counter starts at zero, and the host's final is still the one
            // value it may append.
            assertTrue(isNewDictationFinal(rig.root.dictationUi.value.final, 0L))
            rig.root.consumeDictationFinal(delivered.sequence)

            // Switching away and back again must not insert the same words twice: a fresh page sees no final.
            assertFalse(isNewDictationFinal(rig.root.dictationUi.value.final, 0L))
            assertNull(rig.root.dictationUi.value.final)
        }
    }

    /**
     * Process death (review finding 1b): a new composition restarts the sequence at 1. The fixed page keeps no restored
     * raw sequence, so it appends the new lifetime's final; comparing against a restored old N is what used to drop
     * finals silently.
     */
    @Test
    fun aFreshCompositionRestartsTheSequenceAtOneSoThePageMustNotRestoreIt() {
        val firstLifetime = firstFinalOfAFreshComposition()
        val secondLifetime = firstFinalOfAFreshComposition()

        assertEquals("each composition's sequence is its own, starting at 1", 1L, firstLifetime.sequence)
        assertEquals("a restarted composition does not continue the dead one's count", 1L, secondLifetime.sequence)
        assertTrue(
            "the page after a process death has no restored counter, so it appends the new final",
            isNewDictationFinal(secondLifetime, 0L),
        )
        assertFalse(
            "a page that restored the dead composition's sequence would silently drop the final",
            isNewDictationFinal(secondLifetime, 1L),
        )
    }

    private fun firstFinalOfAFreshComposition(): DictationFinal {
        val rig = DictationRig(detached = true)
        try {
            assertTrue(rig.activate())
            return dictatedFinal(rig, "新进程的第一条")
        } finally {
            rig.close()
        }
    }

    // ---- fix 2: a terminal status is about one action, not a permanent label --------------------------------------

    /**
     * Review finding 2: a timeout/error status belongs to the action that produced it. It must be dismissable by the
     * wearer's next action instead of outranking every later result. The host clears it on a send (the page also calls
     * [HostCompositionRoot.clearDictationStatus] on a draft edit); the row then shows the newer typed result.
     */
    @Test
    fun aTimeoutStatusIsDismissedByTheNextSendAndTheTypedResultWins() {
        withRig(detached = true) { rig ->
            assertTrue(rig.activate())
            assertEquals(DictationStartResult.Accepted, rig.root.startDictation())
            rig.clock.advance(15_000_000_000L)
            rig.scheduler.runDue()
            assertEquals(DictationState.NoSpeech, stateOf(rig))

            // The wearer types/sends instead of retrying the microphone. The send is the newer action, so its
            // result takes the row's one status line; the timeout must not outrank it any more.
            assertEquals(TypedSendResult.Accepted, rig.root.requestTypedMessage("改用打字"))

            assertEquals("the terminal dictation state must not survive the send", DictationState.Idle, stateOf(rig))
            assertEquals(
                "the typed result now owns the row status",
                typedResultLabel(TypedSendResult.Accepted),
                inputRowStatusText(SessionState.Listening, TypedInputState(result = TypedSendResult.Accepted), stateOf(rig)),
            )
        }
    }

    @Test
    fun anExplicitDismissTurnsATerminalOutcomeBackToIdleWithoutTouchingALiveLease() {
        withRig(detached = true) { rig ->
            assertTrue(rig.activate())

            // While a dictation is listening, its status is live information: dismissing cannot silence it.
            assertEquals(DictationStartResult.Accepted, rig.root.startDictation())
            rig.root.clearDictationStatus()
            assertEquals("a live lease keeps its listening status", DictationState.Listening, stateOf(rig))

            // A stop with no speech is terminal; the wearer's next action (a draft edit in the page) dismisses it.
            rig.root.stopDictation(DictationTarget.COMPOSER)
            assertEquals(DictationState.NoSpeech, stateOf(rig))
            rig.root.clearDictationStatus()
            assertEquals("the dismissed status falls back to the ordinary row status", DictationState.Idle, stateOf(rig))

            // The 15 s deadline is the other terminal non-result; it is dismissable the same way.
            assertEquals(DictationStartResult.Accepted, rig.root.startDictation())
            rig.clock.advance(15_000_000_000L)
            rig.scheduler.runDue()
            assertEquals(DictationState.NoSpeech, stateOf(rig))
            rig.root.clearDictationStatus()
            assertEquals(DictationState.Idle, stateOf(rig))
        }
    }

    /**
     * Review finding 2, error half + session change: a capture failure's [DictationState.Error] must not outlive the
     * session it happened in. Stopping the session clears it (the component observes the session change).
     */
    @Test
    fun aTerminalErrorIsDismissedWhenTheSessionChanges() {
        withRig(detached = false, queueDispatches = true, configurePhoneDevice = { failReadAt = 1 }) { rig ->
            assertTrue(rig.activate())
            withP3Sink { captured ->
                assertEquals(DictationStartResult.Accepted, rig.root.startDictation())
                awaitCondition { rig.pendingDispatches() >= 1 }
                rig.runDispatches()
                captured.toList()
            }
            assertEquals(DictationState.Error(PhoneMicCapture.MIC_UNAVAILABLE), stateOf(rig))

            rig.root.stop()

            assertEquals("an error of the stopped session must not remain on the page", DictationState.Idle, stateOf(rig))
        }
    }

    /** A still-unconsumed final is a delivery, not a status: dismissing the status must not lose the words. */
    @Test
    fun dismissingAStatusDoesNotConsumeAPendingFinal() {
        withRig(detached = true) { rig ->
            assertTrue(rig.activate())
            val delivered = dictatedFinal(rig, "先说完再改字")
            rig.root.clearDictationStatus()
            assertEquals("the final is still there for the page to append", delivered, rig.root.dictationUi.value.final)
            rig.root.consumeDictationFinal(delivered.sequence)
        }
    }

    // ---- fix 2: the lease transition must not let a CXR tail steal a wake turn --------------------------------

    /**
     * Review finding 4a: the CXR route is paused **before** the dictation opens the phone capture, and the old loop's
     * two consumer taps are closed before it is stopped. Otherwise a CXR tick already in flight (the loop delivers its
     * frames outside the loop lock) could open a wake turn while the dictation is switching sources, and the redirect
     * installed for the dictation would then capture that turn's final into the input field.
     *
     * The hook runs on the thread opening the capture, i.e. exactly between "route switched" and "dictation source
     * live", which is the window the fix closes.
     */
    @Test
    fun aGlassesDictationPausesTheCxrLoopBeforeItOpensThePhoneCapture() {
        withRig(detached = false) { rig ->
            assertTrue(rig.activate())
            val oldLoop = requireNotNull(rig.chain.normalLoopForProbe)
            assertTrue("the CXR loop is polling the session source", oldLoop.wakeEnabled)
            assertTrue(oldLoop.pipelineEnabled)

            val loopAtCaptureOpen = AtomicReference(oldLoop)
            val oldWakeAtCaptureOpen = AtomicBoolean(true)
            val oldPipelineAtCaptureOpen = AtomicBoolean(true)
            rig.onPhoneDeviceCreated = {
                loopAtCaptureOpen.set(rig.chain.normalLoopForProbe)
                oldWakeAtCaptureOpen.set(oldLoop.wakeEnabled)
                oldPipelineAtCaptureOpen.set(oldLoop.pipelineEnabled)
            }

            assertEquals(DictationStartResult.Accepted, rig.root.startDictation())

            assertNull("the CXR loop must be paused before the dictation capture opens", loopAtCaptureOpen.get())
            assertFalse("the old loop's KWS tap must be closed before it is stopped", oldWakeAtCaptureOpen.get())
            assertFalse("and so must its pipeline feed", oldPipelineAtCaptureOpen.get())
            assertNotNull("the phone dictation loop owns the route", rig.chain.dictationLoopForProbe)

            rig.pipelineVad.decision = VadDecision(true, false)
            rig.feedPhone()
            rig.root.stopDictation(DictationTarget.COMPOSER)
            assertNull("the dictation loop is released with the lease", rig.chain.dictationLoopForProbe)
            assertNotNull("the normal CXR loop is restored", rig.chain.normalLoopForProbe)
        }
    }

    // ---- fix 2: quiesce the tail before the forced final ------------------------------------------------------

    /**
     * Review finding 4b: stop quiesces the loop **before** the controller forces the final. The ordering is observed
     * from inside `asr.finish()` - the exact moment the forced final is produced. Without the fix the loop is still
     * accepting frames then, so a frame from the same drained batch could start a second ASR turn after the final and
     * leak its partials onto the restored wake route.
     */
    @Test
    fun stoppingADictationQuiescesTheLoopBeforeTheFinalIsForced() {
        withRig(detached = true) { rig ->
            assertTrue(rig.activate())
            assertEquals(DictationStartResult.Accepted, rig.root.startDictation())
            rig.pipelineVad.decision = VadDecision(true, false)
            rig.feedPhone()
            assertTrue(rig.chain.hasActiveTurn())

            val enabledWhileFinishing = AtomicBoolean(true)
            rig.asr.onFinish = {
                val loop = rig.chain.dictationLoopForProbe ?: rig.chain.normalLoopForProbe
                enabledWhileFinishing.set(loop?.pipelineEnabled == true)
            }

            rig.root.stopDictation(DictationTarget.COMPOSER)

            assertFalse("the loop must stop feeding the pipeline before the final is forced", enabledWhileFinishing.get())
            assertEquals(DictationState.Idle, stateOf(rig))
        }
    }

    /** Cancel seals the turn instead of finalising it, but the tail must be quiesced for the same reason. */
    @Test
    fun cancellingADictationQuiescesTheLoopBeforeSealingTheTurn() {
        withRig(detached = false) { rig ->
            assertTrue(rig.activate())
            assertEquals(DictationStartResult.Accepted, rig.root.startDictation())
            rig.pipelineVad.decision = VadDecision(true, false)
            rig.feedPhone()
            assertTrue(rig.chain.hasActiveTurn())

            val enabledWhileFinishing = AtomicBoolean(true)
            rig.asr.onFinish = {
                enabledWhileFinishing.set(rig.chain.dictationLoopForProbe?.pipelineEnabled == true)
            }

            rig.root.cancelDictation(DictationTarget.COMPOSER)

            assertFalse("a cancelled dictation must not accept tail frames while sealing", enabledWhileFinishing.get())
            assertNull("cancel delivers no final", rig.root.dictationUi.value.final)
            assertEquals(DictationState.Idle, stateOf(rig))
        }
    }

    /**
     * One scheduled tick drains a whole multi-frame batch and delivers it outside the loop lock. When that batch's
     * first frame is the endpoint, the dictation ends there: the frames behind it belong to no turn and must not open
     * a second one on the route the terminal callback is restoring. The deferred cleanup keeps the quiesce window
     * observable in this rig.
     */
    @Test
    fun aMultiFrameBatchEndsAtItsFirstFinalAndTheTailFramesStartNoSecondTurn() {
        withRig(detached = true, queueDispatches = true) { rig ->
            assertTrue(rig.activate())
            assertEquals(DictationStartResult.Accepted, rig.root.startDictation())
            rig.asr.finalText = "批次里的第一句"
            rig.pipelineVad.decision = VadDecision(true, false)
            rig.pipelineVad.endpointAccepts = 1
            repeat(3) {
                assertEquals(PcmOffer.ACCEPTED, rig.phoneIngress.offer(rig.epoch(), ByteArray(PhoneMicFormat.CHUNK_BYTES)))
            }

            rig.clock.advance(20_000_000)
            rig.scheduler.runDue()

            assertEquals("the endpoint ends the turn on the batch's first frame", 1, rig.asr.frames.size)
            assertEquals("exactly one final is produced", 1, rig.asr.finishes.get())
            assertEquals("批次里的第一句", rig.root.dictationUi.value.final?.text)
            assertFalse("the tail frames must not open a second turn", rig.chain.hasActiveTurn())
            assertEquals("the endpoint ended the dictation", DictationState.Idle, stateOf(rig))
            assertTrue("the cleanup is deferred by this rig", rig.pendingDispatches() >= 1)
            rig.runDispatches()
            assertNotNull("the normal route is back after the deferred cleanup", rig.chain.normalLoopForProbe)
        }
    }
}
