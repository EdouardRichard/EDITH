package edith.plugins.smart

import edith.contracts.AssistantRoute
import edith.plugin.Capability
import edith.plugin.ConversationDisplay
import edith.plugin.DeactivationReason
import edith.plugin.ModelEnd
import edith.plugin.ModelStreamEvent
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

        override fun assistantMessage(route: AssistantRoute, text: String) {
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
        rig.plugin.handle(edith.contracts.AcousticEvent(rig.envelope(), edith.contracts.AcousticLabel.Speech))
        rig.plugin.handle(edith.contracts.TranscriptEvent(rig.transcript("这个页面怎么一直转圈")))

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
        rig.output.submission = edith.plugin.OutputSubmission.Rejected
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
    fun aStoppedTurnLeavesTheLevelsPaired() = assertLevelsArePaired("stopped") { rig ->
        answerWithANotification(rig)
        rig.wakeAndFinal("问一句")
        rig.plugin.handle(edith.contracts.UserAction(rig.envelope(), edith.contracts.UserAction.Action.Stop))
    }

    @Test
    fun anExpiredListeningWindowLeavesTheLevelsPaired() {
        // The scheduler only runs what has come due, so the clock moves past the listening window first and the rig's
        // own `tick` then fires it. The fixed "did not catch it" sentence is the turn's answer, which is what ends
        // "working" - so the hints and the plugin's own terminal line are asserted too, to separate "the path did not
        // run" from "the level was not cleared".
        val display = RecordingDisplay()
        val lines = mutableListOf<String>()
        val previous = P3Log.sink
        P3Log.sink = { lines += it }
        try {
            val rig = rig(display)
            rig.plugin.handle(rig.wakeEvent())
            rig.clock.advance(60_000_000_000L)
            // Two releases: the first fires the listening window, and the sentence it produces is itself deferred by
            // the assembler's hint grace, which is scheduled from inside that first callback.
            rig.tick()
            rig.tick()

            assertTrue(rig.hintTexts.isNotEmpty(),
                "the expired window must still answer the wearer, hints=${rig.hintTexts} log=$lines")
            assertEquals(display.count("working:WAKE"), display.count("idle:WAKE"), display.calls.toString())
        } finally {
            P3Log.sink = previous
        }
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

    @Test
    fun aHostWithoutThePortStillRunsTheTurn() {
        // The port is optional: a host with no display never provides it, and a missing conversation view must not stop
        // a turn from producing a hint.
        val rig = SmartTestRig()
        rig.activate()
        answerWithANotification(rig, text = "没有会话记录也要能答")

        rig.wakeAndFinal("问一句")

        assertEquals(listOf("没有会话记录也要能答"), rig.hintTexts)
    }

    private class DecliningGate : edith.plugins.smart.proactive.ProactiveGate {
        override val form = edith.plugins.smart.proactive.GateForm.NOUL
        override fun shouldIntervene(state: edith.plugins.smart.proactive.ProactiveGateState) =
            edith.plugins.smart.proactive.GateOutcome(intervene = false, confidence = 0.1, latencyMs = 1.0)
    }
}
