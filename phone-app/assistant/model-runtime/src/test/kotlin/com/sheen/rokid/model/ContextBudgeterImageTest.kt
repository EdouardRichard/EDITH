package com.sheen.rokid.model

import com.sheen.rokid.contracts.ContractError
import com.sheen.rokid.contracts.ImageBudget
import com.sheen.rokid.plugin.ImageRef
import com.sheen.rokid.plugin.InputPart
import com.sheen.rokid.plugin.MessageRole
import com.sheen.rokid.plugin.ModelHistoryItem
import com.sheen.rokid.plugin.ModelProfile
import com.sheen.rokid.plugin.ModelRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 0.25 Task 1: images are part of the request, so the one window budget must charge for them.
 *
 * The unit is the budgeter's text unit — now token units from `TokenMeter` (0.3 design §5.2), with the profile's
 * `inputBudgetTokens` interpreted as a token cap — and one image costs [ImageBudget.UNITS_PER_IMAGE] in that same unit:
 * the deliberate coarse approximation the 0.25 spec chose instead of inventing a token formula. The constant lives in
 * core-contracts (Task 13 / R68) so `TurnTrimmer` and `ContextBudgeter` cannot drift apart. Text prices changed with
 * the unit switch (CJK is one unit per code point, ASCII one per four); the image delta did not.
 */
class ContextBudgeterImageTest {

    private val budgeter = ContextBudgeter()

    private fun image(n: Int) = ImageRef("img-$n", "image/jpeg", 768, 1664, "sha-$n")

    private fun request(input: List<InputPart> = listOf(InputPart.Text("look")),
        history: List<ModelHistoryItem> = emptyList()) = requestFixture(input = input, history = history)

    private fun cost(request: ModelRequest, profile: ModelProfile = profileFixture()) =
        budgeter.prepare(profile, request).estimatedInputTokens

    @Test fun `one image costs one thousand twenty four token units`() {
        assertEquals(1024L, ImageBudget.UNITS_PER_IMAGE)
    }

    @Test fun `an image input part is accepted and adds one image budget unit`() {
        val textOnly = request()
        val withImage = request(input = listOf(InputPart.Text("look"), InputPart.Image("artifact:user")))

        val textCost = cost(textOnly)
        val imageCost = cost(withImage)

        assertEquals(1024, imageCost - textCost)
    }

    @Test fun `each tool output image adds one image budget unit`() {
        val history = listOf(
            ModelHistoryItem.Text(MessageRole.USER, "ask"),
            ModelHistoryItem.Text(MessageRole.ASSISTANT, "calling"),
            ModelHistoryItem.ToolCall("c1", "device", "{}"),
            ModelHistoryItem.ToolOutput("c1", "captured"),
        )
        val withImages = history.dropLast(1) +
            ModelHistoryItem.ToolOutput("c1", "captured", listOf(image(1), image(2)))

        assertEquals(2 * 1024, cost(request(history = withImages)) - cost(request(history = history)))
    }

    @Test fun `text plus input and tool images costs exactly text plus one unit per image`() {
        val textOnly = request(
            history = listOf(
                ModelHistoryItem.Text(MessageRole.USER, "ask"),
                ModelHistoryItem.Text(MessageRole.ASSISTANT, "calling"),
                ModelHistoryItem.ToolCall("c1", "device", "{}"),
                ModelHistoryItem.ToolOutput("c1", "captured"),
            ),
        )
        val textOnlyHistory = textOnly.history
        val withImages = textOnly.copy(
            input = listOf(InputPart.Text("look"), InputPart.Image("artifact:user")),
            history = textOnlyHistory.dropLast(1) +
                ModelHistoryItem.ToolOutput("c1", "captured", listOf(image(1), image(2))),
        )
        // Roomy enough to keep every group: this test is about the price, not about eviction.
        val roomy = profileFixture().copy(usableWindowTokens = 8192, inputBudgetTokens = 5000)

        val result = budgeter.prepare(roomy, withImages)

        assertTrue(result.removedIds.isEmpty(), "nothing may be evicted: ${result.removedIds}")
        assertEquals(cost(textOnly, roomy) + 3 * 1024, result.estimatedInputTokens)
    }

    @Test fun `a text only request is priced in token units`() {
        val result = budgeter.prepare(profileFixture(), request(listOf(InputPart.Text("\u4e2d"))).copy(instructions = "i"))
        assertEquals(98, result.estimatedInputTokens) // 1 + 1 tokens + 32 request + 2 * 32 message framing.
    }

    @Test fun `an input image over the limit is rejected rather than truncated`() {
        val withImage = request(input = listOf(InputPart.Text("q"), InputPart.Image("artifact:user")))
        val fits = cost(withImage)
        val tooSmall = profileFixture().copy(inputBudgetTokens = fits - 1)

        assertFailsWith<ContractError.ContextWindowExceeded> { budgeter.prepare(tooSmall, withImage) }
    }

    @Test fun `an old turn carrying images is evicted whole and its image cost with it`() {
        val history = listOf(
            ModelHistoryItem.Text(MessageRole.USER, "u".repeat(10)),
            ModelHistoryItem.Text(MessageRole.ASSISTANT, "a".repeat(10)),
            ModelHistoryItem.ToolCall("c1", "device", "{}"),
            ModelHistoryItem.ToolOutput("c1", "o".repeat(10), listOf(image(1), image(2), image(3))),
        )
        val fitting = request()
        val limit = cost(fitting)
        val profile = profileFixture().copy(inputBudgetTokens = limit)

        val result = budgeter.prepare(profile, request(history = history))

        assertEquals(listOf("history:0"), result.removedIds)
        assertEquals(limit, result.estimatedInputTokens)
        assertTrue(result.request.history.isEmpty())
    }

    @Test fun `audio input is still refused as missing media support`() {
        assertFailsWith<ContractError.CapabilityMissing> {
            budgeter.prepare(profileFixture(), request(input = listOf(InputPart.Audio("local:audio"))))
        }
    }
}
