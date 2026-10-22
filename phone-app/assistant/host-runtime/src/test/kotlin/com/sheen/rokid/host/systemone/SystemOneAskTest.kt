package com.sheen.rokid.host.systemone

import com.sheen.rokid.plugin.FailureReason
import com.sheen.rokid.plugin.LayaInferenceEngine
import com.sheen.rokid.plugin.SystemOneAnswer
import com.sheen.rokid.plugin.SystemOneCriterion
import com.sheen.rokid.plugin.SystemOneQuestion
import com.sheen.rokid.plugin.SystemOneQuestionType
import com.sheen.rokid.plugin.SystemOneResult
import com.sheen.rokid.plugin.SystemOneStatus
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The reshaped service: one `ask`, a reportable failure, and a latency the caller does not have to measure.
 *
 * The retired service answered an unavailable model with a zeroed result — `ChoiceResult(0, FloatArray(n))`,
 * `NoulResult(0f)` — so "the model is missing" and "the model answered zero" were literally the same value and the
 * D60 proactive gate could not tell them apart. These tests pin the replacement.
 */
class SystemOneAskTest {

    private val choice = SystemOneQuestion(
        type = SystemOneQuestionType.CHOICE,
        instructions = "此刻该不该让大模型介入？",
        criteria = listOf(
            SystemOneCriterion("介入", "用户此刻需要帮助"),
            SystemOneCriterion("沉默", "用户不需要帮助"),
        ),
    )

    @Test
    fun `ask on an unavailable service reports MODEL_NOT_LOADED rather than a zeroed answer`() {
        val service = SystemOneServiceImpl(modelPath = "/nonexistent/path")
        awaitStatus(service, SystemOneStatus.UNAVAILABLE)

        val result = service.ask("state", choice)

        val failure = assertIs<SystemOneResult.Failure>(result)
        assertEquals(FailureReason.MODEL_NOT_LOADED, failure.reason)
    }

    @Test
    fun `ask on a ready service returns the engine's answer with a latency`() {
        val service = readyService()

        val result = service.ask("前台应用=微信", choice)

        val success = assertIs<SystemOneResult.Success>(result)
        val answer = assertIs<SystemOneAnswer.Choice>(success.answer)
        assertEquals("介入", answer.choice)
        assertTrue(success.latencyMs >= 0.0, "latency must be measured, was ${success.latencyMs}")
    }

    @Test
    fun `an engine that throws is reported as ENGINE_UNAVAILABLE rather than escaping`() {
        val service = readyService(engine = ThrowingEngine())

        val result = service.ask("state", choice)

        val failure = assertIs<SystemOneResult.Failure>(result)
        assertEquals(FailureReason.ENGINE_UNAVAILABLE, failure.reason)
        // The cause is named by class only: `message` is a contract field a caller may log, and the publisher's
        // decoder embeds criterion labels in its own error text.
        assertTrue(failure.message.isNotBlank())
        assertTrue(
            "boom" !in failure.message,
            "the engine's own message must not reach the contract, was '${failure.message}'",
        )
    }

    @Test
    fun `an engine that refuses to initialize leaves the service unavailable`() {
        val service = SystemOneServiceImpl(modelPath = tempModelFile().absolutePath, engine = RefusingEngine())
        awaitStatus(service, SystemOneStatus.UNAVAILABLE)

        assertEquals(null, service.modelInfo)
        assertIs<SystemOneResult.Failure>(service.ask("state", choice))
    }

    @Test
    fun `warm-up puts one choice question to the engine before the service reports READY`() {
        val engine = RecordingEngine()
        val service = readyService(engine = engine)

        assertEquals(1, engine.questions.size, "warm-up must run exactly once")
        assertEquals(
            SystemOneQuestionType.CHOICE,
            engine.questions.single().type,
            "warm-up must use a form the graph can answer without a real state",
        )
    }

    @Test
    fun `a valid model file with the stub engine still reaches READY`() {
        // The pre-existing lifecycle expectation, kept so the reshape cannot quietly change provisioning.
        val service = readyService()
        assertTrue(service.isAvailable)
        assertNotNull(service.modelInfo)
    }

    @Test
    fun `the stub spreads a choice evenly over its criteria`() {
        val answer = assertIs<SystemOneAnswer.Choice>(StubLayaEngine().asked(choice))
        assertEquals(2, answer.probabilities.size)
        assertEquals(1.0, answer.probabilities.values.sum(), 0.0001)
        assertEquals(0.5, answer.probabilities.getValue("介入"), 0.0001)
    }

    @Test
    fun `the stub answers a noul with a coin flip`() {
        val question = SystemOneQuestion(SystemOneQuestionType.NOUL, "用户此刻需要帮助")
        val answer = assertIs<SystemOneAnswer.Noul>(StubLayaEngine().asked(question))
        assertEquals(0.5, answer.probability, 0.0001)
    }

    @Test
    fun `the stub returns one probability per score level`() {
        val question = SystemOneQuestion(
            SystemOneQuestionType.SCORE,
            "用户有多需要帮助？",
            listOf(SystemOneCriterion("不需要"), SystemOneCriterion("有点"), SystemOneCriterion("很需要")),
        )
        val answer = assertIs<SystemOneAnswer.Score>(StubLayaEngine().asked(question))
        assertEquals(3, answer.probabilities.size)
        assertEquals(1.0, answer.probabilities.values.sum(), 0.0001)
    }

    // ------------------------------------------------------------------ helpers

    private fun StubLayaEngine.asked(question: SystemOneQuestion): SystemOneAnswer {
        initialize()
        return ask("state", question)
    }

    private fun tempModelFile(): File =
        File.createTempFile("test-model", ".bin").apply { writeBytes(ByteArray(100)); deleteOnExit() }

    private fun readyService(engine: LayaInferenceEngine = StubLayaEngine()): SystemOneServiceImpl {
        val service = SystemOneServiceImpl(modelPath = tempModelFile().absolutePath, engine = engine)
        awaitStatus(service, SystemOneStatus.READY)
        return service
    }

    /**
     * Waits for the async loader. Bounded rather than `sleep(500)`: a fixed sleep is both slower than it needs to
     * be and a flake waiting to happen on a loaded machine.
     */
    private fun awaitStatus(service: SystemOneServiceImpl, wanted: SystemOneStatus, timeoutMillis: Long = 5_000) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (service.status != wanted && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertEquals(wanted, service.status, "service did not reach $wanted within ${timeoutMillis}ms")
    }

    private class RefusingEngine : LayaInferenceEngine {
        override fun initialize() = false
        override fun ask(state: String, question: SystemOneQuestion): SystemOneAnswer =
            error("must not be asked when initialize() refused")
        override fun close() = Unit
    }

    private class ThrowingEngine : LayaInferenceEngine {
        override fun initialize() = true
        override fun ask(state: String, question: SystemOneQuestion): SystemOneAnswer =
            error("boom: criterion 介入 was missing")
        override fun close() = Unit
    }

    private class RecordingEngine : LayaInferenceEngine {
        val questions = mutableListOf<SystemOneQuestion>()
        override fun initialize() = true
        override fun ask(state: String, question: SystemOneQuestion): SystemOneAnswer {
            questions += question
            return SystemOneAnswer.Noul(probability = 0.5, confidence = 0.5)
        }
        override fun close() = Unit
    }
}
