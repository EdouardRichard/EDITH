package edith.plugins.smart.proactive

import edith.plugin.FailureReason
import edith.plugin.SystemOneAnswer
import edith.plugin.SystemOneQuestion
import edith.plugin.SystemOneQuestionType
import edith.plugin.SystemOneResult
import edith.plugin.SystemOneService
import edith.plugin.SystemOneStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Spec §4.7 / §7.3 B11 — the Laya intervention gate, and the invariant that makes it sound.
 *
 * Two properties carry this: the state has **no time feature** (C10), which is what makes Laya a pure function of it
 * and therefore makes event-driven evaluation complete rather than merely cheap; and the **trigger event set covers
 * every state input dimension**, whose check is exactly "for each field, who wakes me up?".
 */
class ProactiveGateTest {

    // ---------------------------------------------------------------- the invariant

    @Test
    fun `every state field names the event that triggers its re-evaluation`() {
        // The check the spec asks for, made executable: ask each field "who triggers you", and fail if the answer is
        // missing. A field with no event is a miss the assistant would never notice.
        assertEquals(ProactiveGateState.KEYS.toSet(), TRIGGER_EVENTS.keys)
    }

    @Test
    fun `no state field is left without a trigger event`() {
        ProactiveGateState.KEYS.forEach { key ->
            assertTrue(TRIGGER_EVENTS.getValue(key).isNotEmpty(), "$key has no trigger event")
        }
    }

    @Test
    fun `every declared event is one the host actually delivers`() {
        // The other half of the invariant: naming an event that cannot arrive would satisfy the table and still miss
        // every real change.
        val delivered = setOf("ContextChanged", "TranscriptEvent")
        TRIGGER_EVENTS.values.flatten().forEach { event ->
            assertTrue(event in delivered, "$event is not an event this host delivers")
        }
    }

    @Test
    fun `the state carries no time feature`() {
        val render = ProactiveGateState.of(
            transcript = "现在几点了",
            foreground = "微信",
            notification = "chat",
            calendar = "开会",
        ).render()

        assertFalse("time.local" in render, render)
        assertFalse("time.period" in render, render)
        assertTrue(ProactiveGateState.KEYS.none { it.startsWith("time.") }, ProactiveGateState.KEYS.toString())
    }

    @Test
    fun `the decision critical field comes first because truncation cuts from the tail`() {
        // The publisher's template places the instructions and candidates before the state and cuts an over-long state
        // at its end, so whichever field is last is the one that gets dropped.
        assertEquals("transcript", ProactiveGateState.KEYS.first())

        val render = ProactiveGateState.of("最后说的话", "微信", "chat", "开会").render()
        assertTrue(render.indexOf("最后说的话") < render.indexOf("微信"), render)
    }

    @Test
    fun `a missing signal is simply absent rather than rendered as a placeholder`() {
        val render = ProactiveGateState.of(transcript = "只说了这一句").render()

        assertEquals("transcript=只说了这一句", render)
    }

    // ---------------------------------------------------------------- the gates

    private class ScriptedSystemOne(private val result: SystemOneResult) : SystemOneService {
        val asked = mutableListOf<SystemOneQuestion>()

        /** The state string the gate actually sent — recorded because `""` is a valid-looking String. */
        val states = mutableListOf<String>()

        override fun ask(state: String, question: SystemOneQuestion): SystemOneResult {
            asked += question
            states += state
            return result
        }

        override val isAvailable = true
        override val status = SystemOneStatus.READY
        override val modelInfo: edith.plugin.ModelInfo? = null
    }

    private fun state() = ProactiveGateState.of(transcript = "这个页面卡住了", foreground = "微信")

    @Test
    fun `the gate sends the rendered state rather than an empty one`() {
        // The bug this pins: an earlier revision took the state and asked with `""`. The publisher's host serializes a
        // String state literally, so `""` encodes to zero tokens - Laya was answering from its prior alone and none
        // of the four input dimensions could influence the decision. No test could fail on it, because the fake
        // recorded only the question; `""` looks like a perfectly good String.
        val noul = ScriptedSystemOne(ok(SystemOneAnswer.Noul(0.9, 0.5)))
        NoulGate(noul, threshold = 0.6).shouldIntervene(state())

        val sent = noul.states.single()
        assertTrue(sent.contains("transcript=这个页面卡住了"), sent)
        assertTrue(sent.contains("app.foreground=微信"), sent)

        val choice = ScriptedSystemOne(ok(SystemOneAnswer.Choice("A", mapOf("A" to 0.9), 0.9)))
        ChoiceGate(choice).shouldIntervene(state())
        assertTrue(choice.states.single().contains("这个页面卡住了"), choice.states.single())
    }

    @Test
    fun `a noul probability at or above the threshold intervenes`() {
        val gate = NoulGate(ScriptedSystemOne(ok(SystemOneAnswer.Noul(0.8, 0.5))), threshold = 0.6)

        val outcome = gate.shouldIntervene(state())

        assertTrue(outcome.intervene)
        assertEquals(0.8, outcome.confidence, 0.0001)
        assertEquals(GateForm.NOUL, gate.form)
    }

    @Test
    fun `a noul probability below the threshold does not intervene`() {
        val gate = NoulGate(ScriptedSystemOne(ok(SystemOneAnswer.Noul(0.3, 0.5))), threshold = 0.6)

        assertFalse(gate.shouldIntervene(state()).intervene)
    }

    @Test
    fun `the noul question is the literal the fine-tune spec pins`() {
        // A fine-tune trains on one exact string, so the trained string and the deployed string have to be the same
        // one; the literal lives in the spec (`laya-gate-finetune-design.md` 3.3), not here.
        val service = ScriptedSystemOne(ok(SystemOneAnswer.Noul(0.9, 0.5)))
        NoulGate(service, threshold = 0.6).shouldIntervene(state())

        val question = service.asked.single()
        assertEquals(SystemOneQuestionType.NOUL, question.type)
        assertEquals("此刻是否存在一个需要大模型介入的缺口", question.instructions)
    }

    @Test
    fun `the choice gate uses neutral labels and the same proposition`() {
        // Neutral keys matter: an option label dominates the state upstream, so `介入`/`沉默` would let the label
        // decide instead of the observation.
        val service = ScriptedSystemOne(ok(SystemOneAnswer.Choice("A", mapOf("A" to 0.9, "B" to 0.1), 0.8)))
        val gate = ChoiceGate(service)

        assertTrue(gate.shouldIntervene(state()).intervene)
        val question = service.asked.single()
        assertEquals("此刻是否存在一个需要大模型介入的缺口", question.instructions)
        assertEquals(listOf("A", "B"), question.criteria.map { it.label })
    }

    @Test
    fun `a choice answer whose label is not one of the criteria does not intervene`() {
        val gate = ChoiceGate(ScriptedSystemOne(ok(SystemOneAnswer.Choice("C", mapOf("C" to 1.0), 0.9))))

        val outcome = gate.shouldIntervene(state())

        assertFalse(outcome.intervene)
        assertEquals("UNKNOWN_LABEL", outcome.reason)
    }

    @Test
    fun `the silence label does not intervene`() {
        val gate = ChoiceGate(ScriptedSystemOne(ok(SystemOneAnswer.Choice("B", mapOf("A" to 0.1, "B" to 0.9), 0.9))))

        assertFalse(gate.shouldIntervene(state()).intervene)
    }

    @Test
    fun `an answer of the wrong type does not intervene`() {
        // The engine returned something the question did not ask for; treating it as an intervention would be
        // inventing a decision the model never made.
        val gate = NoulGate(ScriptedSystemOne(ok(SystemOneAnswer.Choice("A", emptyMap(), 0.9))), threshold = 0.6)

        val outcome = gate.shouldIntervene(state())

        assertFalse(outcome.intervene)
        assertEquals("WRONG_ANSWER_TYPE", outcome.reason)
    }

    @Test
    fun `a failure never intervenes and never throws`() {
        val gate = NoulGate(
            ScriptedSystemOne(SystemOneResult.Failure(FailureReason.MODEL_NOT_LOADED, "not loaded")),
            threshold = 0.6,
        )

        val outcome = gate.shouldIntervene(state())

        assertFalse(outcome.intervene)
        assertEquals("MODEL_NOT_LOADED", outcome.reason)
    }

    @Test
    fun `an engine that throws does not intervene`() {
        val throwing = object : SystemOneService {
            override fun ask(state: String, question: SystemOneQuestion): SystemOneResult = error("boom")
            override val isAvailable = true
            override val status = SystemOneStatus.READY
            override val modelInfo: edith.plugin.ModelInfo? = null
        }

        assertFalse(NoulGate(throwing, threshold = 0.6).shouldIntervene(state()).intervene)
    }

    @Test
    fun `the outcome carries the latency the decision cost`() {
        // §4.7 needs to know what one judgement costs before it can decide whether the call fits a path.
        val gate = NoulGate(ScriptedSystemOne(ok(SystemOneAnswer.Noul(0.9, 0.5))), threshold = 0.6)

        assertEquals(231.0, gate.shouldIntervene(state()).latencyMs, 0.0001)
    }

    private fun ok(answer: SystemOneAnswer) = SystemOneResult.Success(answer, latencyMs = 231.0)
}
