package com.sheen.rokid.phone.plugin.smarthelper.proactive

import com.sheen.rokid.plugin.SystemOneAnswer
import com.sheen.rokid.plugin.SystemOneCriterion
import com.sheen.rokid.plugin.SystemOneQuestion
import com.sheen.rokid.plugin.SystemOneQuestionType
import com.sheen.rokid.plugin.SystemOneResult
import com.sheen.rokid.plugin.SystemOneService

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
 *
 * Since 0.3 Phase 4A A7 the production bytes live in `prompts/pinned/proactive-gate.md`; this constant remains the
 * constructor default so existing callers and tests keep the calibrated string.
 */
const val INTERVENTION_PROPOSITION = "此刻是否存在一个需要大模型介入的缺口"

/**
 * Neutral option keys, pinned by the same spec.
 *
 * `介入`/`沉默` would be worse than neutral keys: an option label dominates the state in this model's prompt, so a
 * label that names the decision lets the label make it. `A`/`B` carry the meaning in the descriptions instead.
 *
 * A7 moves the production bytes to `prompts/pinned/proactive-gate-options.md`, parsed by [parseGateOptions]; this list
 * stays the constructor default and the frozen reference the resource is compared against.
 */
val INTERVENTION_CRITERIA = listOf(
    SystemOneCriterion("A", "yes — 存在一个需要大模型介入的缺口"),
    SystemOneCriterion("B", "no — 此刻不需要大模型介入"),
)

/**
 * Parses the pinned gate-options resource (0.3 Phase 4A A7): one option per line, `label<TAB>description`.
 *
 * The format is deliberately a literal serialization of [SystemOneCriterion]: option labels are neutral keys, the
 * description is what the model reads, and both are frozen by the fine-tune calibration, so a line's fields are kept
 * verbatim (no trimming inside a field) and every malformed line fails loudly at construction time.
 */
internal fun parseGateOptions(text: String): List<SystemOneCriterion> {
    val options = text.lines()
        .map { line -> line.removeSuffix("\r") }
        .filter { line -> line.isNotEmpty() }
        .mapIndexed { index, line ->
            val fields = line.split('\t')
            require(fields.size == 2) {
                "gate option ${index + 1} must be 'label<TAB>description', was '$line'"
            }
            require(fields[0].isNotBlank() && fields[1].isNotBlank()) {
                "gate option ${index + 1} needs a non-blank label and description, was '$line'"
            }
            SystemOneCriterion(fields[0], fields[1])
        }
    require(options.size >= 2) { "the gate needs at least two options, found ${options.size}" }
    require(options.map { it.label }.distinct().size == options.size) { "gate option labels must be unique" }
    return options
}

/** The `noul` form: one Bernoulli question, and a threshold the caller calibrates. */
class NoulGate(
    private val service: SystemOneService,
    private val threshold: Double = NoulThreshold.VALUE,
    /** The calibrated proposition; production passes `PromptCatalog.pinned("proactive-gate")`. */
    private val question: String = INTERVENTION_PROPOSITION,
) : ProactiveGate {

    override val form = GateForm.NOUL

    override fun shouldIntervene(state: ProactiveGateState): GateOutcome =
        decide(
            service,
            state,
            SystemOneQuestion(SystemOneQuestionType.NOUL, question),
        ) { answer, latency ->
            when (answer) {
                is SystemOneAnswer.Noul ->
                    GateOutcome(answer.probability >= threshold, answer.probability, latency)

                else -> wrongType(latency)
            }
        }
}

/** The `choice` form: the same proposition with two named options. */
class ChoiceGate(
    private val service: SystemOneService,
    /** The calibrated proposition; production passes `PromptCatalog.pinned("proactive-gate")`. */
    private val question: String = INTERVENTION_PROPOSITION,
    /** The calibrated neutral options, parsed from `pinned/proactive-gate-options.md` in production. */
    private val options: List<SystemOneCriterion> = INTERVENTION_CRITERIA,
) : ProactiveGate {
    override val form = GateForm.CHOICE

    override fun shouldIntervene(state: ProactiveGateState): GateOutcome =
        decide(
            service,
            state,
            SystemOneQuestion(SystemOneQuestionType.CHOICE, question, options),
        ) { answer, latency ->
            when (answer) {
                is SystemOneAnswer.Choice -> when (answer.choice) {
                    options.first().label ->
                        GateOutcome(intervene = true, confidence = answer.confidence, latencyMs = latency)

                    options.last().label ->
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
 * The `noul` gate's operating point (Phase C), chosen on a selection set rather than left at the default.
 *
 * The gate shipped with `0.5` for two phases, and every document said so out loud: the fine-tune spec requires an
 * operating point picked where the model's labels are not the thing being reported on, and no such set existed.
 * The owner's 2026-10-15 ruling was to produce one and to pick **precision first**, because a proactive assistant
 * that interrupts wrongly costs the wearer attention, while a missed intervention costs one offer of help.
 *
 * The number comes from `phase_c_select_threshold.py` over the delivered artifact set
 * (`laya-ml-embeds-bnd-seed20261014`) on the synthetic validation split `layaft` held out: among thresholds whose
 * precision reached 0.90 there, this is the one with the greatest recall. Full readings, including the
 * **selection-to-report gap that this value does not escape**, are in
 * `docs/project/evidence/2026-10-15-phase-c-noul-threshold.md`; the short version is that on the 210-row report set
 * this point reads precision 0.686 where the old default read 0.956, so it is a calibrated starting point on a
 * synthetic distribution, **not** a validated one.
 */
object NoulThreshold {
    /** How the point was chosen, recorded with it: a number without its rule cannot be re-examined. */
    const val CHOICE_RULE = "precision >= 0.90, then max recall (synthetic validation split)"

    /** The operating point itself, from the delivered graph's own probabilities. */
    const val VALUE = 0.069
}

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
