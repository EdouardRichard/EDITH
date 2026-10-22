// SPDX-License-Identifier: Apache-2.0
package com.sheen.rokid.phone.inference.laya
import com.sheen.rokid.plugin.SystemOneAnswer
import com.sheen.rokid.plugin.SystemOneQuestion
import com.sheen.rokid.plugin.SystemOneQuestionType

/**
 * Turns the publisher's answer map back into the contract's own answer.
 *
 * `LayaDecoder.decode` answers with a **map whose shape depends on the question type** — `choice`/`probabilities`/
 * `confidence` for one form, `noul` for another, `score`/`legend` for the third — so something has to read it back.
 * That something is this, and it is a pure function on purpose: it is the half of the Laya path that can be proven
 * without the 679 MB publisher set on a device.
 *
 * **`action.act_probability` is deliberately dropped.** It is decoded by the publisher but saturates at 1.0 on every
 * input measured, and the publisher's own documentation calls it "not a usable escalation signal"; carrying it into
 * the contract would invite a future caller to build on a number that carries no information.
 *
 * **A wrong shape is `null`, never a guess.** An answer the question did not ask for is not an answer, and the
 * contract's safe default downstream is silence.
 */
internal object LayaAnswerDecoder {

    fun decode(question: SystemOneQuestion, answer: Map<String, Any?>): SystemOneAnswer? =
        when (question.type) {
            SystemOneQuestionType.CHOICE -> {
                val label = answer["choice"] as? String ?: return null
                SystemOneAnswer.Choice(
                    choice = label,
                    probabilities = distribution(answer["probabilities"]),
                    confidence = confidence(answer),
                )
            }

            SystemOneQuestionType.SCORE -> {
                val level = (answer["score"] as? Number)?.toDouble() ?: return null
                SystemOneAnswer.Score(
                    level = level,
                    probabilities = distribution(answer["probabilities"]),
                    confidence = confidence(answer),
                )
            }

            SystemOneQuestionType.NOUL -> {
                val probability = (answer["noul"] as? Number)?.toDouble() ?: return null
                SystemOneAnswer.Noul(probability = probability, confidence = confidence(answer))
            }
        }

    /** The publisher keys this by label for `choice` and by level index for `score`; either way it is `String` keys. */
    private fun distribution(raw: Any?): Map<String, Double> =
        (raw as? Map<*, *>)
            ?.entries
            ?.associate { (key, value) -> key.toString() to ((value as? Number)?.toDouble() ?: 0.0) }
            ?: emptyMap()

    /** An absent confidence reads as `0.0`: an unknown confidence must never look like a certain one. */
    private fun confidence(answer: Map<String, Any?>): Double =
        (answer["confidence"] as? Number)?.toDouble() ?: 0.0
}
