package com.sheen.rokid.phone.plugin.smarthelper
import com.sheen.rokid.phone.plugin.smarthelper.audit.P3Log

import com.sheen.rokid.contracts.AcousticEvent
import com.sheen.rokid.contracts.AcousticLabel
import com.sheen.rokid.contracts.ContextChanged
import com.sheen.rokid.contracts.TranscriptEvent
import com.sheen.rokid.phone.plugin.smarthelper.prompt.PromptCatalog
import com.sheen.rokid.phone.plugin.smarthelper.proactive.GateForm
import com.sheen.rokid.phone.plugin.smarthelper.proactive.GateOutcome
import com.sheen.rokid.phone.plugin.smarthelper.proactive.ProactiveGate
import com.sheen.rokid.phone.plugin.smarthelper.proactive.ProactiveGateState
import com.sheen.rokid.plugin.Capability
import com.sheen.rokid.plugin.ModelEnd
import com.sheen.rokid.plugin.ModelInfo
import com.sheen.rokid.plugin.ModelStreamEvent
import com.sheen.rokid.plugin.SystemOneAnswer
import com.sheen.rokid.plugin.SystemOneCriterion
import com.sheen.rokid.plugin.SystemOneQuestion
import com.sheen.rokid.plugin.SystemOneQuestionType
import com.sheen.rokid.plugin.SystemOneResult
import com.sheen.rokid.plugin.SystemOneService
import com.sheen.rokid.plugin.SystemOneStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Spec §4.7: the gate sits **after** P3's own admission and throttle — arming, sensitivity, cooldown, dedup — so it
 * only sees a candidate that already passed them, and it replaces the cloud model's decision rather than adding to it.
 *
 * The other half is the C10 invariant's teeth: because the state contains no time feature, a `ContextChanged` is the
 * only thing that can re-open a question the transcript alone had settled.
 */
class ProactiveGateWiringTest {

    private class RecordingGate(private val intervene: Boolean) : ProactiveGate {
        val seen = mutableListOf<ProactiveGateState>()
        override val form = GateForm.NOUL
        override fun shouldIntervene(state: ProactiveGateState): GateOutcome {
            seen += state
            return GateOutcome(intervene, 0.9, 231.0)
        }
    }

    /** The host's decision model, recording the exact question the production gate built. */
    private class RecordingSystemOne(private val answer: SystemOneAnswer) : SystemOneService {
        val asked = mutableListOf<SystemOneQuestion>()
        override fun ask(state: String, question: SystemOneQuestion): SystemOneResult {
            asked += question
            return SystemOneResult.Success(answer, latencyMs = 1.0)
        }
        override val isAvailable = true
        override val status = SystemOneStatus.READY
        override val modelInfo: ModelInfo? = null
    }

    private fun rig(gate: ProactiveGate?): SmartTestRig {
        val rig = SmartTestRig(interventionGate = gate)
        rig.activate()
        rig.model.onRequest = { request, listener ->
            listener(smartToolCall("c1", "propose_hint", """{"text":"要不要我帮你？"}"""))
            listener(
                com.sheen.rokid.plugin.ModelStreamEvent.Terminal(
                    request.requestId, com.sheen.rokid.plugin.ModelEnd.COMPLETED, null, null,
                ),
            )
        }
        return rig
    }

    private fun arm(rig: SmartTestRig) {
        rig.plugin.handle(AcousticEvent(rig.envelope(), AcousticLabel.Speech))
    }

    @Test
    fun `a disarmed trigger never reaches the gate`() {
        val gate = RecordingGate(intervene = true)
        val rig = rig(gate)

        // No AcousticEvent: the host produced no perception, so this session is not armed.
        rig.plugin.handle(TranscriptEvent(rig.transcript("现在几点了")))

        assertEquals(emptyList(), gate.seen, "the first stage must refuse before the gate is consulted")
        assertTrue(rig.model.requests.isEmpty())
    }

    @Test
    fun `the gate is consulted for a trigger the first stage admitted and it decides`() {
        val gate = RecordingGate(intervene = true)
        val rig = rig(gate)

        arm(rig)
        rig.plugin.handle(TranscriptEvent(rig.transcript("这个页面怎么一直转圈")))

        assertEquals(1, gate.seen.size, "the admitted trigger reaches the gate once")
        assertEquals(1, rig.model.requests.size, "an intervening gate lets the turn proceed")
    }

    @Test
    fun `a gate that declines keeps the cloud model out of it`() {
        val gate = RecordingGate(intervene = false)
        val rig = rig(gate)

        arm(rig)
        rig.plugin.handle(TranscriptEvent(rig.transcript("这个页面怎么一直转圈")))

        assertEquals(1, gate.seen.size)
        assertTrue(rig.model.requests.isEmpty(), "a declined trigger must cost nothing")
        assertTrue(rig.hintTexts.isEmpty())
    }

    @Test
    fun `the production choice gate sends the pinned question and options`() {
        // 0.3 Phase 4A A7: production builds the gate from `PromptCatalog.pinned(...)` for both the question and the
        // two neutral options; a fine-tune trained on the old literals must see the resource's bytes, not a paraphrase.
        val service = RecordingSystemOne(SystemOneAnswer.Choice("A", mapOf("A" to 0.9), 0.9))
        val rig = SmartTestRig(gateForm = GateForm.CHOICE)
        rig.activate(overrides = mapOf(Capability.SystemOne to service))
        rig.model.onRequest = { request, listener ->
            listener(smartToolCall("c1", "propose_hint", """{"text":"要不要我帮你？"}"""))
            listener(ModelStreamEvent.Terminal(request.requestId, ModelEnd.COMPLETED, null, null))
        }

        arm(rig)
        rig.plugin.handle(TranscriptEvent(rig.transcript("这个页面怎么一直转圈")))

        val question = service.asked.single()
        assertEquals(PromptCatalog.pinned("proactive-gate"), question.instructions)
        assertEquals(
            listOf(
                SystemOneCriterion("A", "yes — 存在一个需要大模型介入的缺口"),
                SystemOneCriterion("B", "no — 此刻不需要大模型介入"),
            ),
            question.criteria,
        )
        assertEquals(1, rig.model.requests.size, "an intervening pinned gate lets the hint turn proceed")
    }

    @Test
    fun `the production noul gate sends the pinned question`() {
        val service = RecordingSystemOne(SystemOneAnswer.Noul(0.9, 0.5))
        val rig = SmartTestRig(gateForm = GateForm.NOUL)
        rig.activate(overrides = mapOf(Capability.SystemOne to service))
        rig.model.onRequest = { request, listener ->
            listener(smartToolCall("c1", "propose_hint", """{"text":"要不要我帮你？"}"""))
            listener(ModelStreamEvent.Terminal(request.requestId, ModelEnd.COMPLETED, null, null))
        }

        arm(rig)
        rig.plugin.handle(TranscriptEvent(rig.transcript("这个页面怎么一直转圈")))

        val question = service.asked.single()
        assertEquals(PromptCatalog.pinned("proactive-gate"), question.instructions)
        assertEquals(SystemOneQuestionType.NOUL, question.type)
        assertEquals(emptyList(), question.criteria)
    }

    @Test
    fun `a context change after an utterance re-evaluates the gate`() {
        // This is what the C10 invariant buys: with no time feature in the state, the only way the answer can change
        // is a change in an input dimension - and a foreground switch is one.
        val gate = RecordingGate(intervene = true)
        val rig = rig(gate)
        arm(rig)
        rig.plugin.handle(TranscriptEvent(rig.transcript("这个页面怎么一直转圈")))
        val afterTranscript = gate.seen.size

        // The first stage's cooldown is part of the contract (C11), so the clock has to clear it before the second
        // evaluation is even a candidate; the dedup key includes the foreground, which the change moves.
        rig.tick(61_000)
        rig.plugin.handle(ContextChanged(rig.envelope(), 2, mapOf("app.foreground" to "设置")))

        assertEquals(afterTranscript + 1, gate.seen.size, "a context change must re-open the question")
    }

    @Test
    fun `a context change with no utterance behind it is not a trigger`() {
        // The state's leading field is the transcript; without one there is nothing to judge, and the gate would be
        // called once per context change to answer the same empty question.
        val gate = RecordingGate(intervene = true)
        val rig = rig(gate)
        arm(rig)

        rig.plugin.handle(ContextChanged(rig.envelope(), 2, mapOf("app.foreground" to "设置")))

        assertEquals(emptyList(), gate.seen)
    }

    @Test
    fun `a plugin with no gate behaves exactly as before`() {
        val rig = rig(null)

        arm(rig)
        rig.plugin.handle(TranscriptEvent(rig.transcript("这个页面怎么一直转圈")))

        assertEquals(1, rig.model.requests.size)
    }

    @Test
    fun `the gate log line carries the verdict and never the state`() {
        val rig = rig(RecordingGate(intervene = true))
        val lines = mutableListOf<String>()
        val previous = P3Log.sink
        P3Log.sink = { lines += it }
        try {
            arm(rig)
            rig.plugin.handle(TranscriptEvent(rig.transcript("这个页面怎么一直转圈")))
        } finally {
            P3Log.sink = previous
        }

        val gateLine = lines.single { it.startsWith("P3_PROACTIVE gate") }
        assertTrue(gateLine.contains("intervene=true"), gateLine)
        assertTrue("转圈" !in gateLine, "the log carries the verdict, never the state: $gateLine")
    }
}
