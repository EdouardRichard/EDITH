package edith.model

import edith.contracts.ContractError
import edith.plugin.*
import kotlin.test.*

class ContextBudgeterTest {
    private val budgeter = ContextBudgeter()
    private fun smallProfile(budget: Int = 220) = profileFixture().copy(inputBudgetTokens = budget)
    private fun required() = requestFixture(listOf(InputPart.Text("q".repeat(60)))).copy(instructions = "i".repeat(40))

    @Test fun `old complete history removed while hundred byte required text stays intact`() {
        val request = required().copy(history = listOf(TextMessage(MessageRole.USER, "u".repeat(100)),
            TextMessage(MessageRole.ASSISTANT, "a".repeat(100))))
        val result = budgeter.prepare(smallProfile(), request)
        assertEquals(listOf("history:0"), result.removedIds)
        assertEquals(emptyList(), result.request.history)
        assertEquals(request.instructions, result.request.instructions)
        assertEquals(request.input, result.request.input)
        assertTrue(result.estimated)
        assertTrue(result.estimatedInputTokens <= 220)
        assertTrue(result.estimatedInputTokens.toLong() + 256 + 128 <= 4096)
        assertEquals(2, request.history.size)
    }

    @Test fun `oldest pair ids remain original after multiple removals`() {
        val history = (0..2).flatMap { listOf(TextMessage(MessageRole.USER, "u".repeat(50)), TextMessage(MessageRole.ASSISTANT, "a".repeat(50))) }
        val result = budgeter.prepare(smallProfile(365), required().copy(history = history))
        assertEquals(listOf("history:0", "history:1"), result.removedIds)
        assertEquals(history.takeLast(2), result.request.history)
    }

    @Test fun `history is removed before context then lowest importance with original ties`() {
        val context = listOf(ContextPart("high", "h".repeat(40), 10), ContextPart("low-a", "a".repeat(40), 1), ContextPart("low-b", "b".repeat(40), 1))
        val request = required().copy(context = context, history = listOf(TextMessage(MessageRole.USER, "old"), TextMessage(MessageRole.ASSISTANT, "old")))
        val result = budgeter.prepare(smallProfile(270), request)
        assertEquals(listOf("history:0", "low-a", "low-b"), result.removedIds)
        assertEquals(listOf(context[0]), result.request.context)
        assertEquals(request.input, result.request.input)
    }

    @Test fun `required input over limit is rejected without truncation`() {
        assertFailsWith<ContractError.ConfigurationRejected> { budgeter.prepare(smallProfile(99), required()) }
    }

    @Test fun `utf8 bytes and framing count conservatively even for empty optional context`() {
        val result = budgeter.prepare(profileFixture(), requestFixture(listOf(InputPart.Text("\u4e2d"))).copy(instructions = "i"))
        assertEquals(100, result.estimatedInputTokens) // 1 + 3 bytes + 32 request + 2 * 32 message framing.
        assertTrue(result.estimated)
        assertFailsWith<ContractError.ConfigurationRejected> {
            budgeter.prepare(smallProfile(99), result.request)
        }
    }

    @Test fun `malformed history is never half cropped`() {
        for (history in listOf(listOf(TextMessage(MessageRole.USER, "x")),
            listOf(TextMessage(MessageRole.ASSISTANT, "x"), TextMessage(MessageRole.USER, "y")),
            listOf(TextMessage(MessageRole.USER, "x"), TextMessage(MessageRole.USER, "y")))) {
            assertFailsWith<ContractError.ConfigurationRejected> { budgeter.prepare(smallProfile(), required().copy(history = history)) }
        }
    }

    @Test fun `media without estimator is missing even when optional history is removable`() {
        for (part in listOf(InputPart.Image("local:dummy"), InputPart.Audio("local:dummy"))) {
            assertFailsWith<ContractError.CapabilityMissing> {
                budgeter.prepare(profileFixture(inputs = InputModality.entries.toSet()), requestFixture(listOf(part)))
            }
        }
    }

    @Test fun `large valid int window does not overflow combined accounting`() {
        val profile = profileFixture().copy(usableWindowTokens = Int.MAX_VALUE, inputBudgetTokens = 1,
            maxOutputTokens = Int.MAX_VALUE - 2, safetyTokens = 1)
        assertFailsWith<ContractError.ConfigurationRejected> { budgeter.prepare(profile, requestFixture()) }
    }

    @Test fun `profile mismatch is rejected by budget preparation`() {
        assertFailsWith<ContractError.ConfigurationRejected> {
            budgeter.prepare(profileFixture(), requestFixture().copy(profileRevision = 0))
        }
    }

    @Test fun `prepared request owns list snapshots`() {
        val input = mutableListOf<InputPart>(InputPart.Text("synthetic"))
        val history = mutableListOf(TextMessage(MessageRole.USER, "old"), TextMessage(MessageRole.ASSISTANT, "reply"))
        val context = mutableListOf(ContextPart("c", "data", 1))
        val result = budgeter.prepare(profileFixture(), requestFixture(input).copy(history = history, context = context))
        input.clear(); history.clear(); context.clear()
        assertEquals(listOf(InputPart.Text("synthetic")), result.request.input)
        assertEquals(2, result.request.history.size)
        assertEquals(listOf("c"), result.request.context.map { it.id })
    }
}
