package edith.plugins.smart.proactive

import edith.plugin.SystemOneAnswer
import edith.plugin.SystemOneCriterion
import edith.plugin.SystemOneQuestion
import edith.plugin.SystemOneQuestionType
import edith.plugin.SystemOneResult
import edith.plugin.SystemOneService

/** Which of Laya's two forms this gate asks. The spec leaves the choice to measurement (see the fine-tune design). */
enum class GateForm { NOUL, CHOICE }

/**
 * One gate decision.
 *
 * [reason] is `null` on a real decision and a stable code otherwise, so a caller can log why the gate did not
 * intervene without the answer's own content.
 */
data class GateOutcome(
    val intervene: Boolean,
    val confidence: Double,
    val latencyMs: Double,
    val reason: String? = null,
)

/**
 * The intervention gate: "should the model step in right now?" (spec §4.7).
 *
 * It sits **after** P3's own admission and throttle — arming, sensitivity, cooldown, dedup — so it only ever sees a
 * candidate that has already passed those, and it replaces the cloud model's decision rather than adding to it.
 *
 * **A gate never throws and never intervenes on doubt.** Every failure path — an unavailable model, a failure
 * result, an answer of the wrong shape, a label the question never offered, an engine that throws — answers
 * "do not intervene" with a reason code. Intervening is the expensive action, so the safe default is silence.
 */
interface ProactiveGate {
    fun shouldIntervene(state: ProactiveGateState): GateOutcome

    val form: GateForm
}

/**
 * The proposition both forms ask. **Pinned by the fine-tune spec** (`2026-10-13-laya-gate-finetune-design.md` §3.3),
 * not chosen here: a fine-tune trains on one exact string, so the trained string and the deployed string must be the
 * same one or the gain is unclaimable on the deployed question.
 */
const val INTERVENTION_PROPOSITION = "此刻是否存在一个需要大模型介入的缺口"

/**
 * Neutral option keys, pinned by the same spec.
 *
 * `介入`/`沉默` would be worse than neutral keys: an option label dominates the state in this model's prompt, so a
 * label that names the decision lets the label make it. `A`/`B` carry the meaning in the descriptions instead.
 */
val INTERVENTION_CRITERIA = listOf(
    SystemOneCriterion("A", "yes — 存在一个需要大模型介入的缺口"),
    SystemOneCriterion("B", "no — 此刻不需要大模型介入"),
)

/** The `noul` form: one Bernoulli question, and a threshold the caller calibrates. */
class NoulGate(
    private val service: SystemOneService,
    private val threshold: Double = 0.5,
) : ProactiveGate {

    override val form = GateForm.NOUL

    override fun shouldIntervene(state: ProactiveGateState): GateOutcome =
        decide(
            service,
            state,
            SystemOneQuestion(SystemOneQuestionType.NOUL, INTERVENTION_PROPOSITION),
        ) { answer, latency ->
            when (answer) {
                is SystemOneAnswer.Noul ->
                    GateOutcome(answer.probability >= threshold, answer.probability, latency)

                else -> wrongType(latency)
            }
        }
}

/** The `choice` form: the same proposition with two named options. */
class ChoiceGate(private val service: SystemOneService) : ProactiveGate {
    override val form = GateForm.CHOICE

    override fun shouldIntervene(state: ProactiveGateState): GateOutcome =
        decide(
            service,
            state,
            SystemOneQuestion(SystemOneQuestionType.CHOICE, INTERVENTION_PROPOSITION, INTERVENTION_CRITERIA),
        ) { answer, latency ->
            when (answer) {
                is SystemOneAnswer.Choice -> when (answer.choice) {
                    INTERVENTION_CRITERIA.first().label ->
                        GateOutcome(intervene = true, confidence = answer.confidence, latencyMs = latency)

                    INTERVENTION_CRITERIA.last().label ->
                        GateOutcome(intervene = false, confidence = answer.confidence, latencyMs = latency)

                    else -> GateOutcome(false, 0.0, latency, "UNKNOWN_LABEL")
                }

                else -> wrongType(latency)
            }
        }
}

/** An answer whose shape does not match the question: the model was asked one thing and answered another. */
private fun wrongType(latency: Double) = GateOutcome(false, 0.0, latency, "WRONG_ANSWER_TYPE")

/**
 * The shared shape of both forms: ask once, translate, and turn every failure into silence.
 *
 * **[state] is rendered and sent as the model's state.** Both gates take it and both must pass it on — an earlier
 * revision took the parameter and asked with `""`, which the publisher's host serializes to zero tokens, so Laya was
 * answering from its prior alone and none of the four input dimensions could influence the decision. The test that
 * catches it asserts the string the service actually received.
 */
private fun decide(
    service: SystemOneService,
    state: ProactiveGateState,
    question: SystemOneQuestion,
    translate: (SystemOneAnswer, Double) -> GateOutcome,
): GateOutcome = try {
    when (val result = service.ask(state.render(), question)) {
        is SystemOneResult.Success -> translate(result.answer, result.latencyMs)
        is SystemOneResult.Failure -> GateOutcome(false, 0.0, 0.0, result.reason.name)
    }
} catch (_: Exception) {
    // An engine that throws is a failed judgement, not a failed turn: the caller keeps its silence and logs a code.
    GateOutcome(false, 0.0, 0.0, "GATE_THREW")
}
