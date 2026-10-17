package com.sheen.rokid.phone.inference.laya

import com.sheen.rokid.plugin.SystemOneAnswer
import com.sheen.rokid.plugin.SystemOneCriterion
import com.sheen.rokid.plugin.SystemOneQuestion
import com.sheen.rokid.plugin.SystemOneQuestionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The publisher's decoder answers with a **map whose shape depends on the question type**; this turns it back into
 * the contract's own answer. Kept pure so it is provable without the 679 MB publisher set: the fixtures below are
 * the shapes `LayaDecoder.decode` produces.
 */
class LayaAnswerDecoderTest {

    private val choiceQuestion = SystemOneQuestion(
        SystemOneQuestionType.CHOICE,
        "此刻是否存在一个需要大模型介入的缺口",
        listOf(SystemOneCriterion("A", "yes"), SystemOneCriterion("B", "no")),
    )

    @Test
    fun `a choice answer keeps its label, its distribution and its confidence`() {
        val answer = LayaAnswerDecoder.decode(
            choiceQuestion,
            mapOf(
                "type" to "choice",
                "choice" to "A",
                "probabilities" to linkedMapOf("A" to 0.5608, "B" to 0.4392),
                "confidence" to 0.0107,
                "action" to mapOf("act_probability" to 1.0),
            ),
        )

        val choice = answer as SystemOneAnswer.Choice
        assertEquals("A", choice.choice)
        assertEquals(0.5608, choice.probabilities.getValue("A"), 0.0001)
        assertEquals(0.0107, choice.confidence, 0.0001)
        // `act_probability` is deliberately dropped: it saturates at 1.0 and the publisher calls it unusable.
        assertEquals(setOf("A", "B"), choice.probabilities.keys)
    }

    @Test
    fun `a noul answer is the probability and the confidence`() {
        val answer = LayaAnswerDecoder.decode(
            SystemOneQuestion(SystemOneQuestionType.NOUL, "proposition"),
            mapOf("type" to "noul", "noul" to 0.8708, "confidence" to 0.42),
        )

        val noul = answer as SystemOneAnswer.Noul
        assertEquals(0.8708, noul.probability, 0.0001)
        assertEquals(0.42, noul.confidence, 0.0001)
    }

    @Test
    fun `a score answer keeps its expectation and its distribution`() {
        val answer = LayaAnswerDecoder.decode(
            SystemOneQuestion(
                SystemOneQuestionType.SCORE,
                "how much help is needed",
                listOf(SystemOneCriterion("0", "none"), SystemOneCriterion("1", "some")),
            ),
            mapOf(
                "type" to "score",
                "score" to 0.75,
                "legend" to linkedMapOf("0" to "none", "1" to "some"),
                "probabilities" to linkedMapOf("0" to 0.25, "1" to 0.75),
                "confidence" to 0.5,
            ),
        )

        val score = answer as SystemOneAnswer.Score
        assertEquals(0.75, score.level, 0.0001)
        assertEquals(0.75, score.probabilities.getValue("1"), 0.0001)
    }

    @Test
    fun `an answer of the wrong shape is refused rather than guessed at`() {
        // The model answered something the question did not ask for; inventing an answer here would be inventing a
        // decision, and the caller's safe default is silence.
        assertNull(LayaAnswerDecoder.decode(choiceQuestion, mapOf("type" to "noul", "noul" to 0.9)))
        assertNull(LayaAnswerDecoder.decode(choiceQuestion, emptyMap()))
        assertNull(LayaAnswerDecoder.decode(choiceQuestion, mapOf("choice" to 7)))
    }

    @Test
    fun `a missing probability map yields an empty distribution rather than a crash`() {
        val answer = LayaAnswerDecoder.decode(choiceQuestion, mapOf("choice" to "A", "confidence" to 0.1))

        assertTrue((answer as SystemOneAnswer.Choice).probabilities.isEmpty())
    }

    @Test
    fun `a confidence that is absent reads as zero rather than as certain`() {
        // Zero is the honest default: an unknown confidence must never look like a strong one.
        val answer = LayaAnswerDecoder.decode(choiceQuestion, mapOf("choice" to "A")) as SystemOneAnswer.Choice

        assertEquals(0.0, answer.confidence, 0.0)
    }
}
