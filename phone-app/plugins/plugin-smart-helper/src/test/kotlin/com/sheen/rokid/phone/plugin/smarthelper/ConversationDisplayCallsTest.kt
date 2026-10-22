package com.sheen.rokid.phone.plugin.smarthelper
import com.sheen.rokid.phone.plugin.smarthelper.proactive.GateForm
import com.sheen.rokid.phone.plugin.smarthelper.proactive.GateOutcome
import com.sheen.rokid.phone.plugin.smarthelper.proactive.ProactiveGateState
import com.sheen.rokid.phone.plugin.smarthelper.proactive.ProactiveGate

import com.sheen.rokid.contracts.AssistantRoute
import com.sheen.rokid.output.OfficialConversation
import com.sheen.rokid.plugin.Capability
import com.sheen.rokid.plugin.ConversationDisplay
import com.sheen.rokid.plugin.DeactivationReason
import com.sheen.rokid.plugin.ModelEnd
import com.sheen.rokid.plugin.ModelStreamEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Phase C (spec §6.6): what the plugin tells the host about the conversation, and when.
 *
 * The load-bearing property is the **pairing**: every `working` must be answered by an `idle`, on the same route. A
 * missing `idle` is not a cosmetic bug — it would leave the glasses' ring turning and the panel held awake on a device
 * whose wearer cannot switch it off from the app — so the assertion is written as an equality of counts rather than as
 * a list of expected calls. That way a terminal path someone adds later and forgets to publish fails this test instead
 * of shipping.
 *
 * The level is published from one place (`publishBusy`), because the alternative — an `idle` written by hand at each
 * terminal branch — is exactly how one branch gets missed. The paths are split into one test each so a failure names
 * the path it came from, and so no single test runs six turns inside the module's per-test time limit.
 */
class ConversationDisplayCallsTest {

    private class RecordingDisplay : ConversationDisplay {
        val calls = mutableListOf<String>()

        override fun userMessage(route: AssistantRoute, text: String) {
            calls += "user:${route.name}:$text"
        }

        override fun userFinal(route: AssistantRoute, turnId: String, entryId: String, text: String) {
            // The plugin's final-user path is the semantic equivalent of the old `userMessage`; the level-pairing
            // contract below is about the conversation, not which of the two ports carried the wearer's line.
            calls += "user:${route.name}:$text"
        }

        override fun assistantMessage(route: AssistantRoute, text: String) {
            calls += "said:${route.name}:$text"
        }

        override fun assistantFinal(route: AssistantRoute, turnId: String, hintId: String, text: String) {
            // 0.24 S4 Task 16: the exact-identity final is the semantic equivalent of the old `assistantMessage`; the
            // level-pairing contract below is about the conversation, not which of the two ports carried the answer.
            calls += "said:${route.name}:$text"
        }

        override fun working(route: AssistantRoute) {
            calls += "working:${route.name}"
        }

        override fun idle(route: AssistantRoute) {
            calls += "idle:${route.name}"
        }

        fun count(prefix: String) = calls.count { it.startsWith(prefix) }
    }

    private fun rig(display: ConversationDisplay): SmartTestRig {
        val rig = SmartTestRig()
        rig.activate(overrides = mapOf(Capability.ConversationDisplay to display))
        return rig
    }

    /**
     * A model that notifies once and then closes the turn.
     *
     * The loop asks the model again after a tool result, so a fake that answers with the same notification every time
     * produces one notification per iteration. That is what a model really would do if it kept calling `notify_user`,
     * and it is not what these tests are about, so the second call ends the turn cleanly.
     */
    private fun answerWithANotification(rig: SmartTestRig, text: String = "已经帮你查好了") {
        var requests = 0
        rig.model.onRequest = { request, listener ->
            if (requests++ == 0) {
                listener(smartToolCall("c1", "notify_user", """{"text":"$text","channel":"screen"}"""))
            }
            listener(ModelStreamEvent.Terminal(request.requestId, ModelEnd.COMPLETED, null, null))
        }
    }

    @Test
    fun aWakeTurnReportsWorkingThenTheUtteranceThenTheAnswerThenIdle() {
        val display = RecordingDisplay()
        val rig = rig(display)
        answerWithANotification(rig)

        rig.wakeAndFinal("帮我看看明天的天气")

        assertEquals(
            listOf(
                "working:WAKE",
                "user:WAKE:帮我看看明天的天气",
                "said:WAKE:已经帮你查好了",
                "idle:WAKE",
            ),
            display.calls,
        )
    }

    @Test
    fun theBusyLevelIsNotRepublishedForAStateThatHasNotChanged() {
        // `working` is a level: a turn that tells the wearer two things is still one turn's worth of "working", and a
        // host that forwarded each notification as a fresh level would make the glasses' ring stutter at every message.
        val display = RecordingDisplay()
        val rig = rig(display)
        var requests = 0
        rig.model.onRequest = { request, listener ->
            val nth = ++requests
            if (nth <= 2) {
                listener(smartToolCall("c$nth", "notify_user", """{"text":"第 $nth 条","channel":"screen"}"""))
            }
            listener(ModelStreamEvent.Terminal(request.requestId, ModelEnd.COMPLETED, null, null))
        }

        rig.wakeAndFinal("问一句")

        assertEquals(1, display.count("working:WAKE"), display.calls.toString())
        assertEquals(1, display.count("idle:WAKE"), display.calls.toString())
        assertEquals(2, display.count("said:WAKE"), "both messages are part of the conversation: ${display.calls}")
    }

    @Test
    fun aDeclinedGateStillClearsTheLevelItRaised() {
        val display = RecordingDisplay()
        val rig = SmartTestRig(interventionGate = DecliningGate())
        rig.activate(overrides = mapOf(Capability.ConversationDisplay to display))
        rig.model.onRequest = { _, _ -> error("a declined proactive turn must not reach the model") }

        // Arm the proactive path (the acoustic label is what does it) and hand it a candidate.
        rig.plugin.handle(com.sheen.rokid.contracts.AcousticEvent(rig.envelope(), com.sheen.rokid.contracts.AcousticLabel.Speech))
        rig.plugin.handle(com.sheen.rokid.contracts.TranscriptEvent(rig.transcript("这个页面怎么一直转圈")))

        assertEquals(display.count("working:PROACTIVE"), display.count("idle:PROACTIVE"),
            "a gate that refuses must not leave the ring turning: ${display.calls}")
        assertEquals(display.count("working:WAKE"), display.count("idle:WAKE"), display.calls.toString())
    }

    /** The pairing contract, for one terminal path. One test per path, so a failure names the path it came from. */
    private fun assertLevelsArePaired(path: String, drive: (SmartTestRig) -> Unit) {
        val display = RecordingDisplay()
        val rig = rig(display)
        drive(rig)
        // Both halves matter: the pairing alone would pass vacuously for a path that published nothing at all.
        assertTrue(display.count("working:WAKE") >= 1, "path $path never raised the level: ${display.calls}")
        assertEquals(display.count("working:WAKE"), display.count("idle:WAKE"), "path $path -> ${display.calls}")
        assertEquals(display.count("working:PROACTIVE"), display.count("idle:PROACTIVE"),
            "path $path -> ${display.calls}")
    }

    @Test
    fun aDisplayedNotificationLeavesTheLevelsPaired() = assertLevelsArePaired("displayed") { rig ->
        answerWithANotification(rig)
        rig.wakeAndFinal("问一句")
    }

    @Test
    fun aRefusedHintLeavesTheLevelsPaired() = assertLevelsArePaired("refused") { rig ->
        answerWithANotification(rig)
        rig.output.submission = com.sheen.rokid.plugin.OutputSubmission.Rejected
        rig.wakeAndFinal("问一句")
    }

    @Test
    fun aFailedModelLeavesTheLevelsPaired() = assertLevelsArePaired("model failed") { rig ->
        rig.model.onRequest = { request, listener ->
            listener(ModelStreamEvent.Terminal(request.requestId, ModelEnd.FAILED, null, null))
        }
        rig.wakeAndFinal("问一句")
    }

    @Test
    fun aStoppedTurnLeavesTheLevelsPaired() {
        // A turn whose level is **live** when `Stop` arrives: the wake has opened it and it is still waiting for its
        // utterance, which is the state the emergency brake is for. The earlier version drove the turn to completion
        // first, by which time the level was already down, so it passed without exercising the path at all.
        val display = RecordingDisplay()
        val rig = rig(display)
        rig.plugin.handle(rig.wakeEvent())
        assertTrue(display.count("working:WAKE") >= 1, display.calls.toString())

        rig.plugin.handle(com.sheen.rokid.contracts.UserAction(rig.envelope(), com.sheen.rokid.contracts.UserAction.Action.Stop))

        assertEquals(display.count("working:WAKE"), display.count("idle:WAKE"), display.calls.toString())
    }

    @Test
    fun manualHelpRaisesAndClearsTheLevelLikeAWake() {
        // `Help` is an acceptance row of its own (P3's manual entry point) and it opens a real turn, so it must pair
        // like one. It was the path the busy publisher missed when the level moved out of the turn object.
        val display = RecordingDisplay()
        val rig = rig(display)
        answerWithANotification(rig)
        rig.plugin.handle(com.sheen.rokid.contracts.TranscriptEvent(rig.transcript("记住我喜欢喝乌龙茶")))
        rig.tick()

        rig.plugin.handle(com.sheen.rokid.contracts.UserAction(rig.envelope(), com.sheen.rokid.contracts.UserAction.Action.Help))

        assertTrue(display.count("working:WAKE") >= 1, "manual help must raise the level: ${display.calls}")
        assertEquals(display.count("working:WAKE"), display.count("idle:WAKE"), display.calls.toString())
    }

    @Test
    fun theOfficialTimeoutLeavesTheLevelsPaired() {
        // 0.24 S3: the official 10 s window closes the conversation (no failure sentence, no turn outcome). The busy
        // level raised at the wake must still be cleared, or the ring would keep turning on a panel that just closed.
        val display = RecordingDisplay()
        val rig = SmartTestRig(panelVisible = true)
        rig.activate(overrides = mapOf(Capability.ConversationDisplay to display))

        rig.plugin.handle(rig.wakeEvent())
        assertTrue(display.count("working:WAKE") >= 1, display.calls.toString())

        rig.clock.advance(OfficialConversation.LISTEN_TIMEOUT_MILLIS * 1_000_000L)
        rig.scheduler.runDue()

        assertTrue(rig.hintTexts.isEmpty(), "the official timeout closes the panel; it does not answer the wearer")
        assertEquals(
            display.count("working:WAKE"),
            display.count("idle:WAKE"),
            "a closed conversation must clear its level: ${display.calls}",
        )
    }

    @Test
    fun aDeactivatedSessionLeavesTheLevelsPaired() = assertLevelsArePaired("session ended") { rig ->
        answerWithANotification(rig)
        rig.wakeAndFinal("问一句")
        rig.plugin.deactivate(DeactivationReason.Stopped)
    }

    @Test
    fun deactivateClearsWhateverLevelWasStillRaised() {
        // A turn that has opened and is still waiting for its utterance: the wake alone raises the level, and no model
        // is involved, so this cannot block.
        val display = RecordingDisplay()
        val rig = rig(display)

        rig.plugin.handle(rig.wakeEvent())
        assertTrue(display.count("working:WAKE") >= 1, display.calls.toString())

        rig.plugin.deactivate(DeactivationReason.Stopped)

        assertEquals(display.count("working:WAKE"), display.count("idle:WAKE"), display.calls.toString())
        assertEquals(display.count("working:PROACTIVE"), display.count("idle:PROACTIVE"), display.calls.toString())
    }

    /**
     * The port must be called **without** the plugin's lock held.
     *
     * The port is the host's, it reaches the CXR wire, and the host delivers its own events from another thread. If a
     * level were reported while `gate` was held, that other thread would block on the lock for as long as the wire
     * took - and the plugin's own `emit` already documents this hazard for the output submit ("the submit itself
     * happens outside it, so a synchronous `OutputStateEvent` … can re-enter `handle` without deadlocking").
     *
     * The probe makes the hazard observable: the first port call asks a **second thread** to take the same lock and
     * joins it. With the lock held that join cannot complete, so the assertion fails instead of the report silently
     * becoming a stall on a device.
     */
    @Test
    fun thePortIsNeverCalledWhileThePluginsLockIsHeld() {
        val rig = SmartTestRig()
        val plugin = rig.plugin
        val probed = java.util.concurrent.atomic.AtomicBoolean(false)
        val completed = java.util.concurrent.atomic.AtomicBoolean(false)

        fun probe() {
            if (!probed.compareAndSet(false, true)) return
            val worker = Thread({
                // A plain host event, so the other thread only has to take the lock - what it does with it is
                // irrelevant to the question being asked.
                plugin.handle(com.sheen.rokid.contracts.AcousticEvent(rig.envelope(), com.sheen.rokid.contracts.AcousticLabel.Speech))
            }, "port-probe").apply { isDaemon = true }
            worker.start()
            worker.join(2_000)
            completed.set(!worker.isAlive)
        }

        val display = object : ConversationDisplay {
            override fun userMessage(route: AssistantRoute, text: String) = probe()
            override fun userPartial(route: AssistantRoute, turnId: String, revision: Long, text: String) = probe()
            override fun userFinal(route: AssistantRoute, turnId: String, entryId: String, text: String) = probe()
            override fun assistantMessage(route: AssistantRoute, text: String) = probe()
            override fun assistantFinal(route: AssistantRoute, turnId: String, hintId: String, text: String) = probe()
            override fun working(route: AssistantRoute) = probe()
            override fun idle(route: AssistantRoute) = probe()
        }

        rig.activate(overrides = mapOf(Capability.ConversationDisplay to display))
        answerWithANotification(rig)
        rig.wakeAndFinal("问一句")

        assertTrue(completed.get(), "the host's port was called while the plugin's lock was held")
    }

    /**
     * The route a hint is submitted under is **the route it will be filed under on the glasses**.
     *
     * Owner report, 2026-10-15: the two conversations were not isolated. A proactive answer appeared in the 唤醒
     * conversation, because `Assistance.route` was added in Phase C with `AssistantRoute.WAKE` as its default and
     * **neither construction site passed it** - so every hint, proactive included, claimed to belong to the wake
     * route. The glasses file a `SHOW` under the route the wire carries, which is why the device log read
     * `conversation route=WAKE entries=2` for two proactive answers while the wearer's own words were filed under
     * `route=PROACTIVE`.
     */
    @Test
    fun aWakeAnswerIsSubmittedUnderTheWakeRoute() {
        val display = RecordingDisplay()
        val rig = rig(display)
        answerWithANotification(rig)
        rig.wakeAndFinal("现在几点了")

        assertEquals(AssistantRoute.WAKE, rig.output.hints.last().route)
    }

    @Test
    fun aHostWithoutThePortStillRunsTheTurn() {        // The port is optional: a host with no display never provides it, and a missing conversation view must not stop
        // a turn from producing a hint.
        val rig = SmartTestRig()
        rig.activate()
        answerWithANotification(rig, text = "没有会话记录也要能答")

        rig.wakeAndFinal("问一句")

        assertEquals(listOf("没有会话记录也要能答"), rig.hintTexts)
    }

    @Test
    fun aStopDuringAnAcceptedProactiveHintCancelsItsOutput() {
        // 0.23 P5 (D145): the official "double-click interrupt" stops the speech and takes the hint off the screen.
        // The wake turn already went through `cancelOutputTurn`; a proactive hint that is already displayed must too.
        val display = RecordingDisplay()
        val rig = rig(display)
        rig.model.onRequest = { request, listener ->
            if (request.tools.any { it.name == "propose_hint" }) {
                listener(smartToolCall("c1", "propose_hint", """{"text":"主动提醒一句"}""", requestId = request.requestId))
            }
            listener(ModelStreamEvent.Terminal(request.requestId, ModelEnd.COMPLETED, null, null))
        }

        rig.plugin.handle(com.sheen.rokid.contracts.AcousticEvent(rig.envelope(),
            com.sheen.rokid.contracts.AcousticLabel.Speech))
        rig.plugin.handle(com.sheen.rokid.contracts.TranscriptEvent(rig.transcript("现在几点了")))
        rig.tick()
        val hintTurn = rig.output.hints.single().sourceTurnId
        assertTrue(hintTurn != null, "the proactive hint must have been submitted")

        rig.plugin.handle(com.sheen.rokid.contracts.UserAction(rig.envelope(),
            com.sheen.rokid.contracts.UserAction.Action.Stop))

        assertTrue(rig.cancels.contains(Triple(rig.sessionId, rig.generation, hintTurn)),
            "a displayed proactive hint must be cancelled with its turn: ${rig.cancels}")
    }

    @Test
    fun aStopWhileProactiveIsInFlightDropsTheLateHintAndClearsTheLevel() {
        val display = RecordingDisplay()
        val rig = rig(display)
        rig.model.onRequest = { request, listener ->
            // The proactive request is in flight; the wearer's STOP arrives before the model answers.
            rig.plugin.handle(com.sheen.rokid.contracts.UserAction(rig.envelope(),
                com.sheen.rokid.contracts.UserAction.Action.Stop))
            listener(smartToolCall("c1", "propose_hint", """{"text":"迟到的主动提醒"}""",
                requestId = request.requestId))
            listener(ModelStreamEvent.Terminal(request.requestId, ModelEnd.COMPLETED, null, null))
        }

        rig.plugin.handle(com.sheen.rokid.contracts.AcousticEvent(rig.envelope(),
            com.sheen.rokid.contracts.AcousticLabel.Speech))
        rig.plugin.handle(com.sheen.rokid.contracts.TranscriptEvent(rig.transcript("现在几点了")))
        rig.tick()

        assertTrue(display.calls.contains("working:PROACTIVE"), display.calls.toString())
        assertTrue(display.calls.contains("idle:PROACTIVE"), display.calls.toString())
        assertTrue(rig.output.hints.isEmpty(), "a cancelled proactive hint must never be submitted")
    }

    @Test
    fun aStopDuringSubmitCancelsAHintTheHostAlreadyAccepted() {
        // The STOP can land between the plugin's "not cancelled" check and the host's `submit` returning Accepted.
        // The hint must then be cancelled explicitly; marking it unreadable is not enough because the host may already
        // have put it on screen.
        val display = RecordingDisplay()
        val rig = rig(display)
        rig.model.onRequest = { request, listener ->
            if (request.tools.any { it.name == "propose_hint" }) {
                listener(smartToolCall("c1", "propose_hint", """{"text":"迟到的主动提醒"}""",
                    requestId = request.requestId))
            }
            listener(ModelStreamEvent.Terminal(request.requestId, ModelEnd.COMPLETED, null, null))
        }
        rig.output.onSubmit = {
            rig.plugin.handle(com.sheen.rokid.contracts.UserAction(rig.envelope(),
                com.sheen.rokid.contracts.UserAction.Action.Stop))
        }

        rig.plugin.handle(com.sheen.rokid.contracts.AcousticEvent(rig.envelope(),
            com.sheen.rokid.contracts.AcousticLabel.Speech))
        rig.plugin.handle(com.sheen.rokid.contracts.TranscriptEvent(rig.transcript("现在几点了")))
        rig.tick()

        val hintTurn = rig.output.hints.single().sourceTurnId
        assertTrue(rig.cancels.contains(Triple(rig.sessionId, rig.generation, hintTurn)),
            "the accepted-then-stopped hint must be cancelled: ${rig.cancels}")
    }

    @Test
    fun aProactiveHintIsRecordedUnderItsOwnSayEntryIdentity() {
        // 0.24 S4 Task 16: the proactive route has no streamed revisions, so its one statement is the final. It must
        // still use the documented `say-${hintId}` identity (and its own turn) rather than a minted UUID, so the two
        // screens' records agree about which hint the sentence belongs to.
        val display = SmartRecordingDisplay()
        val rig = SmartTestRig()
        rig.activate(overrides = mapOf(Capability.ConversationDisplay to display))
        rig.model.onRequest = { request, listener ->
            if (request.tools.any { it.name == "propose_hint" }) {
                listener(smartToolCall("c1", "propose_hint", """{"text":"主动提醒一句"}""",
                    requestId = request.requestId))
            }
            listener(ModelStreamEvent.Terminal(request.requestId, ModelEnd.COMPLETED, null, null))
        }

        rig.plugin.handle(com.sheen.rokid.contracts.AcousticEvent(rig.envelope(),
            com.sheen.rokid.contracts.AcousticLabel.Speech))
        rig.plugin.handle(com.sheen.rokid.contracts.TranscriptEvent(rig.transcript("现在几点了")))
        rig.tick()

        val hint = rig.output.hints.single()
        val recorded = display.assistantFinals.single()
        assertEquals(AssistantRoute.PROACTIVE, recorded.route)
        assertEquals("主动提醒一句", recorded.text)
        assertEquals(hint.hintId, recorded.hintId)
        assertEquals(hint.sourceTurnId, recorded.turnId)
    }

    private class DecliningGate : com.sheen.rokid.phone.plugin.smarthelper.proactive.ProactiveGate {
        override val form = com.sheen.rokid.phone.plugin.smarthelper.proactive.GateForm.NOUL
        override fun shouldIntervene(state: com.sheen.rokid.phone.plugin.smarthelper.proactive.ProactiveGateState) =
            com.sheen.rokid.phone.plugin.smarthelper.proactive.GateOutcome(intervene = false, confidence = 0.1, latencyMs = 1.0)
    }
}
