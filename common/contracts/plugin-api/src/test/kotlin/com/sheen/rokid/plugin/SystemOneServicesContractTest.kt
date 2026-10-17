package com.sheen.rokid.plugin

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The reshaped `SystemOneService` contract (approved proposal
 * `docs/project/2026-10-13-systemone-interface-change-proposal.md` §2).
 *
 * The point of the reshape is that Laya is not "a function of positional arguments" but
 * "(a state, a question carrying instructions and named criteria) -> a labelled, probabilistic answer".
 * These tests pin the second shape: the answer has to carry the label, the distribution and the confidence,
 * and a failure has to be reportable at all (the retired signature answered an unavailable model with
 * zeroed results, which the caller could not tell apart from a real answer).
 */
class SystemOneServicesContractTest {

    @Test
    fun `Choice answer carries the label, its distribution and the confidence`() {
        val answer = SystemOneAnswer.Choice(
            choice = "介入",
            probabilities = mapOf("介入" to 0.82, "沉默" to 0.18),
            confidence = 0.64,
        )
        assertEquals("介入", answer.choice)
        assertEquals(1.0, answer.probabilities.values.sum(), 0.0001)
        assertTrue(answer.confidence in 0.0..1.0)
    }

    @Test
    fun `Noul answer is a probability in 0-1`() {
        val answer = SystemOneAnswer.Noul(probability = 0.75, confidence = 0.55)
        assertTrue(answer.probability in 0.0..1.0)
        assertTrue(answer.confidence in 0.0..1.0)
    }

    @Test
    fun `Score answer carries the expectation and the per-level distribution`() {
        val answer = SystemOneAnswer.Score(
            level = 1.5,
            probabilities = mapOf("0" to 0.25, "1" to 0.5, "2" to 0.25),
            confidence = 0.5,
        )
        assertEquals(1.5, answer.level)
        assertEquals(3, answer.probabilities.size)
    }

    @Test
    fun `a question keeps its criteria in the order it was given them`() {
        val question = SystemOneQuestion(
            type = SystemOneQuestionType.CHOICE,
            instructions = "此刻该不该让大模型介入？",
            criteria = listOf(
                SystemOneCriterion("介入", "用户此刻需要帮助"),
                SystemOneCriterion("沉默", "用户不需要帮助"),
            ),
        )
        // Order is the contract: Laya's decoder maps marker positions back to criteria positions, so a
        // reordering here would silently swap the labels of every answer.
        assertEquals(listOf("介入", "沉默"), question.criteria.map { it.label })
        assertEquals("用户此刻需要帮助", question.criteria.first().description)
    }

    @Test
    fun `a criterion description is optional because the SCORE form uses levels not labels`() {
        val criterion = SystemOneCriterion("0")
        assertEquals(null, criterion.description)
    }

    @Test
    fun `a choice question with no candidates is refused`() {
        // Laya renders one marker per criterion; an empty criteria list asks for zero markers and the decoder's
        // "Logits/criteria option count mismatch" would only fire long after the question left the caller's hands.
        assertFailsWith<IllegalArgumentException> {
            SystemOneQuestion(SystemOneQuestionType.CHOICE, "此刻该不该介入？")
        }
    }

    @Test
    fun `a score question with no scale levels is refused`() {
        assertFailsWith<IllegalArgumentException> {
            SystemOneQuestion(SystemOneQuestionType.SCORE, "用户有多需要帮助？")
        }
    }

    @Test
    fun `a noul question carries no criteria`() {
        // The noul form is a Bernoulli judgement: the official host passes `criteria = null` and renders its own
        // default true/false option text, so a caller has nothing to supply here.
        val question = SystemOneQuestion(SystemOneQuestionType.NOUL, "用户此刻需要帮助")
        assertTrue(question.criteria.isEmpty())
    }

    @Test
    fun `blank instructions are refused`() {
        assertFailsWith<IllegalArgumentException> {
            SystemOneQuestion(SystemOneQuestionType.NOUL, "   ")
        }
    }

    @Test
    fun `a blank criterion label is refused`() {
        assertFailsWith<IllegalArgumentException> {
            SystemOneCriterion("  ", "描述")
        }
    }

    @Test
    fun `Success carries the latency so the caller does not have to time the call itself`() {
        val result: SystemOneResult =
            SystemOneResult.Success(SystemOneAnswer.Noul(probability = 0.7, confidence = 0.5), latencyMs = 231.0)
        val success = result as SystemOneResult.Success
        assertEquals(231.0, success.latencyMs)
    }

    @Test
    fun `a negative latency is refused`() {
        assertFailsWith<IllegalArgumentException> {
            SystemOneResult.Success(SystemOneAnswer.Noul(0.7, 0.5), latencyMs = -1.0)
        }
    }

    @Test
    fun `Failure reports through the vocabulary both local model services share`() {
        val failure = SystemOneResult.Failure(
            reason = FailureReason.MODEL_NOT_LOADED,
            message = "Laya weights are not provisioned",
        )
        assertEquals(FailureReason.MODEL_NOT_LOADED, failure.reason)
        assertTrue(failure.message.isNotBlank())
    }

    @Test
    fun `an unavailable model is reportable rather than silently answered with zeroes`() {
        // This is the behaviour the reshape exists to make expressible: the retired three verbs returned
        // FloatArray(size){0f} / 0f when the model was not READY, so "the model is missing" and "the model
        // answered zero" were the same value.
        val refusal: SystemOneResult = SystemOneResult.Failure(FailureReason.MODEL_NOT_LOADED, "not loaded")
        assertTrue(refusal is SystemOneResult.Failure)
    }

    @Test
    fun `SystemOneQuestionType has the three Laya forms`() {
        assertEquals(3, SystemOneQuestionType.entries.size)
        assertNotNull(SystemOneQuestionType.valueOf("CHOICE"))
        assertNotNull(SystemOneQuestionType.valueOf("SCORE"))
        assertNotNull(SystemOneQuestionType.valueOf("NOUL"))
    }

    @Test
    fun `SystemOneStatus has three values`() {
        assertEquals(3, SystemOneStatus.entries.size)
        assertNotNull(SystemOneStatus.valueOf("LOADING"))
        assertNotNull(SystemOneStatus.valueOf("READY"))
        assertNotNull(SystemOneStatus.valueOf("UNAVAILABLE"))
    }
}
