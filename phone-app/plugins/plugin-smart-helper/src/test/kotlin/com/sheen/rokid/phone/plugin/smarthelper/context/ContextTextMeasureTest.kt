package com.sheen.rokid.phone.plugin.smarthelper.context

import com.sheen.rokid.agent.TokenMeter
import com.sheen.rokid.plugin.MessageRole
import com.sheen.rokid.plugin.ModelHistoryItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The plugin's text measure (0.3 design §5.2).
 *
 * Task 11 deleted the fixed six-way `TokenBudget` type; the three text helpers that lived beside it moved verbatim to
 * `ContextTextMeasure.kt`, and `estimateTokens` now delegates to [TokenMeter] so the plugin, the send-time budgeter
 * and the turn trimmer cannot price the same prompt differently. These tests pin the delegation and the per-item
 * contribution the compressor and telemetry rely on.
 */
class ContextTextMeasureTest {

    @Test
    fun `estimation is the shared TokenMeter, not a second formula`() {
        for (text in listOf("", "中", "你好吗", "abcdefghijkl", "abc", "😀", "😀😀😀", "a中b😀c", "   ")) {
            assertEquals(TokenMeter.estimate(text), estimateTokens(text), "estimate disagrees for '$text'")
        }
    }

    @Test
    fun `a non ascii code point is one token and four ascii code points are one`() {
        assertEquals(1, estimateTokens("中"))
        assertEquals(3, estimateTokens("你好吗"))
        assertEquals(3, estimateTokens("abcdefghijkl"), "twelve ASCII characters are three tokens")
        assertEquals(0, estimateTokens(""))
        assertEquals(1, estimateTokens("😀"), "one emoji is one non-ASCII code point, never zero")
        assertEquals(3, estimateTokens("😀😀😀"))
    }

    @Test
    fun `a history item contributes only the text the model reads`() {
        // Identities (call id, tool name) are bookkeeping; counting them would inflate an estimate without bounding
        // anything the provider actually prices.
        val text: ModelHistoryItem = ModelHistoryItem.Text(MessageRole.USER, "你好")
        assertEquals("你好", text.estimatedText())
        assertEquals("你好".length, text.estimatedLength())
        assertEquals(
            """{"app":"设置"}""",
            ModelHistoryItem.ToolCall("call-1", "open_app", """{"app":"设置"}""").estimatedText(),
        )
        assertEquals("已打开", ModelHistoryItem.ToolOutput("call-1", "已打开").estimatedText())
        assertTrue(
            ModelHistoryItem.ToolCall("call-1", "open_app", """{"app":"设置"}""").estimatedLength() ==
                """{"app":"设置"}""".length,
        )
    }
}
