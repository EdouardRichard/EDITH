package com.sheen.rokid.phone.plugin.smarthelper.context

import com.sheen.rokid.plugin.MessageRole
import com.sheen.rokid.plugin.ModelHistoryItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * One route's transcript. Spec §4.2: the two routes are completely independent — separate histories, separate token
 * counts, separate compression state — and share only the memory module.
 */
class ConversationContextTest {

    @Test
    fun `two routes keep independent histories`() {
        val wake = ConversationContext(ContextRoute.WAKE)
        val proactive = ConversationContext(ContextRoute.PROACTIVE)

        wake.appendUser("开灯")
        proactive.appendUser("该提醒他了")

        assertEquals(1, wake.size())
        assertEquals(1, proactive.size())
        assertEquals("开灯", (wake.history().single() as ModelHistoryItem.Text).text)
        assertEquals("该提醒他了", (proactive.history().single() as ModelHistoryItem.Text).text)
    }

    @Test
    fun `history is handed out as an independent copy`() {
        val context = ConversationContext(ContextRoute.WAKE)
        context.appendUser("一")

        val snapshot = context.history()
        context.appendUser("二")

        // The property that matters is detachment: the snapshot must not be the context's own list. (It is not
        // asserted to be *mutable* — `toList()` returns an immutable singleton for size <= 1, and mutability of the
        // copy was never part of the contract.)
        assertEquals(1, snapshot.size, "the snapshot must not grow with the context")
        assertEquals(2, context.size())
    }

    @Test
    fun `a tool round keeps its call and its output adjacent and in order`() {
        val context = ConversationContext(ContextRoute.WAKE)
        context.appendUser("帮我打开微信")
        context.appendAssistant("好的。")
        context.appendToolCall(callId = "call-1", name = "open_app", argsJson = """{"app":"微信"}""")
        context.appendToolOutput(callId = "call-1", output = "ok")

        val history = context.history()
        assertEquals(4, history.size)
        val call = history[2] as ModelHistoryItem.ToolCall
        val output = history[3] as ModelHistoryItem.ToolOutput
        assertEquals("call-1", call.callId)
        assertEquals("open_app", call.name)
        // The provider's own item identity rides along; the echo needs it on the wire.
        assertEquals("call-1", call.itemId)
        assertEquals("call-1", output.callId)
        assertEquals(MessageRole.USER, (history[0] as ModelHistoryItem.Text).role)
        assertEquals(MessageRole.ASSISTANT, (history[1] as ModelHistoryItem.Text).role)
    }

    @Test
    fun `a tool call may carry an item id distinct from its call id`() {
        val context = ConversationContext(ContextRoute.PROACTIVE)
        context.appendToolCall(callId = "call-7", name = "notify_user", argsJson = "{}", itemId = "item-7")

        assertEquals("item-7", (context.history().single() as ModelHistoryItem.ToolCall).itemId)
    }

    @Test
    fun `a summary placeholder is appended like any other assistant message`() {
        val context = ConversationContext(ContextRoute.WAKE)
        context.appendUser("一")
        context.appendSummaryPlaceholder("（此前对话已摘要）")

        assertEquals(2, context.size())
        assertEquals(
            MessageRole.ASSISTANT,
            (context.history().last() as ModelHistoryItem.Text).role,
        )
    }

    @Test
    fun `blank text is refused by the contract rather than silently stored`() {
        val context = ConversationContext(ContextRoute.WAKE)

        // ModelHistoryItem.Text already requires non-blank text; the context must not paper over that.
        assertFailsWith<IllegalArgumentException> { context.appendUser("   ") }
        assertEquals(0, context.size())
    }

    @Test
    fun `clear empties only this route`() {
        val wake = ConversationContext(ContextRoute.WAKE)
        val proactive = ConversationContext(ContextRoute.PROACTIVE)
        wake.appendUser("一")
        proactive.appendUser("二")

        wake.clear()

        assertEquals(0, wake.size())
        assertEquals(1, proactive.size())
    }

    @Test
    fun `going over the message cap is reportable so compression can be triggered`() {
        // Spec §4.10: at most 200 messages per context, and exceeding it triggers compression. The per-instance cap
        // keeps that rule testable without building 201 messages.
        assertEquals(200, ConversationContext.MAX_MESSAGES)

        val small = ConversationContext(ContextRoute.WAKE, maxMessages = 2)
        small.appendUser("一")
        assertFalse(small.overCap())
        small.appendUser("二")
        small.appendUser("三")
        assertTrue(small.overCap())
    }

    @Test
    fun `a cap below one is refused`() {
        assertFailsWith<IllegalArgumentException> { ConversationContext(ContextRoute.WAKE, maxMessages = 0) }
    }
}
