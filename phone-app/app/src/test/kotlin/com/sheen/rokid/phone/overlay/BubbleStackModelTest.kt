package com.sheen.rokid.phone.overlay

import com.sheen.rokid.contracts.AssistantRoute
import com.sheen.rokid.contracts.ToolPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.5.1 (`C6`/`C10`): the bubble timing rules, as pure JVM behaviour.
 *
 * The stack shows at most [BubbleStackModel.MAX_VISIBLE] messages: the newest plus one old one. A third message drops
 * the existing old one immediately and turns the previous newest into the old one. Per the owner's "其余逻辑不变" the
 * old message keeps the 0.5 hold/fade/removal rules and there is **no** extra accelerated fade.
 *
 * The model still returns a **relative** alpha (1.0 current, 0.42 old); `BubbleRenderMath` turns it into the
 * whole-bubble alpha the renderer applies. [VisibleBubble.newest] is the model's own answer to "which message carries
 * the tail", so the UI cannot disagree about it.
 *
 * A local Notice owns a 3–4 s lifetime in the model itself, not a `TurnIdle`: the overlay must expire refusals even
 * when no turn ever started.
 */
class BubbleStackModelTest {

    private var now = 0L
    private val model = BubbleStackModel()

    private fun user(text: String = "帮我看看这个页面", turn: String? = "t1") =
        model.onEvent(PhoneBubbleEvent.User(AssistantRoute.WAKE, turn, text), now)

    private fun thinking(turn: String? = "t1") =
        model.onEvent(PhoneBubbleEvent.Thinking(AssistantRoute.WAKE, turn), now)

    private fun tool(label: String, phase: ToolPhase, turn: String? = "t1") =
        model.onEvent(PhoneBubbleEvent.Tool(AssistantRoute.WAKE, turn, label, phase), now)

    private fun partial(text: String, hint: String = "h1") =
        model.onEvent(PhoneBubbleEvent.AnswerPartial(AssistantRoute.WAKE, "t1", hint, text), now)

    private fun final(text: String, hint: String = "h1") =
        model.onEvent(PhoneBubbleEvent.AnswerFinal(AssistantRoute.WAKE, "t1", hint, text), now)

    private fun notice(text: String = "还没有开始会话") =
        model.onEvent(PhoneBubbleEvent.Notice(AssistantRoute.WAKE, text), now)

    private fun idle() = model.onEvent(PhoneBubbleEvent.TurnIdle(AssistantRoute.WAKE), now)

    private fun visible() = model.snapshot(now).messages

    private fun newestFlags() = visible().map { it.newest }

    @Test
    fun aMessageIsItsOwnBubble() {
        user()
        now = 300

        val messages = visible()

        assertEquals(1, messages.size)
        assertEquals(BubbleKind.USER, messages.single().kind)
        assertEquals(1f, messages.single().relativeAlpha, 0.0001f)
        assertTrue("a lone message is the newest and carries the tail", messages.single().newest)
    }

    @Test
    fun aNewMessageSupersedesTheOldOneAtTheFixedRatioAndRemovesItAfterTwoSeconds() {
        user()
        now = 100
        thinking()

        // 200 ms after supersession: the old line is faded, the new line is current.
        now = 300
        var messages = visible()
        assertEquals(listOf(BubbleKind.USER, BubbleKind.THINKING), messages.map { it.kind })
        assertEquals(0.42f, messages[0].relativeAlpha, 0.0001f)
        assertEquals(1f, messages[1].relativeAlpha, 0.0001f)

        // At 2.0 s it is still retained at the faded ratio...
        now = 2_099
        messages = visible()
        assertEquals(2, messages.size)
        assertEquals(0.42f, messages[0].relativeAlpha, 0.0001f)

        // ...and after the 2 s retention plus the 250 ms fade it has been removed.
        now = 2_351
        messages = visible()
        assertEquals(1, messages.size)
        assertEquals(BubbleKind.THINKING, messages.single().kind)
    }

    @Test
    fun aToolStartAndEndUpdateTheSameBubble() {
        user(); now = 100; thinking(); now = 200
        tool("读取屏幕内容", ToolPhase.START)
        now = 300
        assertEquals("the user line is already the old one and has been dropped", 2, visible().size)
        assertEquals(BubbleKind.TOOL_RUNNING, visible().last().kind)

        tool("读取屏幕内容", ToolPhase.END)
        now = 400
        val messages = visible()
        assertEquals("END must not add a third bubble", 2, messages.size)
        assertEquals(BubbleKind.TOOL_DONE, messages.last().kind)
        assertEquals("读取屏幕内容", messages.last().text)
    }

    @Test
    fun answerPartialsUpdateTheSameBubbleAndTheFinalSettlesIt() {
        user(); now = 100; thinking(); now = 200
        partial("这是一张")
        now = 300
        assertEquals("the answer joins the faded thinking bubble", 2, visible().size)
        partial("这是一张外卖订单")
        now = 400
        assertEquals("a partial must not add a bubble", 2, visible().size)
        assertEquals("这是一张外卖订单", visible().last().text)

        final("这是一张外卖订单：预计 12:30 送达。")
        now = 500
        assertEquals(2, visible().size)
        assertEquals(BubbleKind.ANSWER, visible().last().kind)
        assertEquals("这是一张外卖订单：预计 12:30 送达。", visible().last().text)
    }

    @Test
    fun aSecondFinalForANewHintAddsANewBubble() {
        user(); now = 100; thinking(); now = 200
        final("第一条回答", hint = "h1")
        now = 300
        final("第二条通知", hint = "h2")
        now = 400

        val messages = visible()
        assertEquals(2, messages.size)
        assertEquals(BubbleKind.ANSWER, messages[0].kind)
        assertEquals("第一条回答", messages[0].text)
        assertEquals(BubbleKind.ANSWER, messages[1].kind)
        assertEquals("第二条通知", messages[1].text)
    }

    @Test
    fun `at most two messages survive and the third drops the old one`() {
        user("one"); now = 100
        thinking(); now = 300
        tool("读取屏幕内容", ToolPhase.START)
        now = 500

        val messages = visible()
        assertEquals("the stack keeps the newest plus one old", 2, messages.size)
        assertEquals("the existing old (the wearer's line) is dropped immediately", "正在思考", messages[0].text)
        assertFalse("the dropped old line is gone, not just reordered", messages.any { it.text == "one" })
        assertEquals("the previous newest becomes the old one", 0.42f, messages[0].relativeAlpha, 0.0001f)
        assertEquals(BubbleKind.TOOL_RUNNING, messages[1].kind)
        assertEquals("only the newest carries the tail", listOf(false, true), messages.map { it.newest })

        // The new old message keeps the unchanged 2 s + 250 ms removal clock.
        now = 300 + BubbleStackModel.KEEP_MILLIS + BubbleStackModel.FADE_MILLIS + 1
        val remaining = visible()
        assertEquals("the old one is removed on the 0.5 clock", 1, remaining.size)
        assertEquals(BubbleKind.TOOL_RUNNING, remaining.single().kind)
        assertTrue(remaining.single().newest)
    }

    @Test
    fun `a new message is in the snapshot at its creation instant`() {
        notice()

        // The newest renders at the configured opacity without waiting for the entry ramp, but the row must already be
        // in the snapshot: for a local Notice that snapshot is the only signal the overlay ever gets (C10).
        val messages = visible()

        assertEquals(1, messages.size)
        assertTrue(messages.single().newest)
        assertEquals(0f, messages.single().relativeAlpha, 0.0001f)
    }

    @Test
    fun `notice disappears after its lifetime without TurnIdle`() {
        notice()
        now = 300
        assertEquals("the local notice is visible while it lasts", 1, visible().size)

        now = BubbleStackModel.NOTICE_DISMISS_AFTER_MILLIS - 1
        assertEquals("the notice is still there just before its lifetime", 1, visible().size)

        // No TurnIdle is emitted: the notice's own deadline is what removes it.
        now = BubbleStackModel.NOTICE_DISMISS_AFTER_MILLIS + 1
        assertTrue("the notice expires without any TurnIdle", visible().isEmpty())
        assertTrue(
            "the local Notice lifetime is 3–4 s",
            BubbleStackModel.NOTICE_DISMISS_AFTER_MILLIS in 3_000L..4_000L,
        )
    }

    @Test
    fun `old message two second removal and newest-only tail are unchanged`() {
        user("one"); now = 100
        thinking()
        now = 300

        var messages = visible()
        assertEquals(2, messages.size)
        assertEquals(listOf("one", BubbleStackModel.THINKING_TEXT), messages.map { it.text })
        assertEquals("only the newest message is flagged for the tail", listOf(false, true), newestFlags())
        assertEquals(0.42f, messages[0].relativeAlpha, 0.0001f)

        now = 100 + BubbleStackModel.KEEP_MILLIS
        assertEquals("the old message is still retained at 2 s", 2, visible().size)

        now = 100 + BubbleStackModel.KEEP_MILLIS + BubbleStackModel.FADE_MILLIS + 1
        messages = visible()
        assertEquals("the old message is removed after the 250 ms fade", 1, messages.size)
        assertTrue("the survivor is the newest", messages.single().newest)
    }

    @Test
    fun turnIdleStartsTheThreeSecondDismissalAndANewMessageCancelsIt() {
        user(); now = 100; thinking()
        idle()

        now = 3_099
        assertTrue("before the delay the stack is still visible", visible().isNotEmpty())

        now = 3_400
        assertTrue("after the delay the stack is gone", visible().isEmpty())

        // A new message after the idle event starts a new stack and cancels the dismissal.
        now = 1_000
        user()
        idle()
        now = 1_500
        // A non-expiring user line: the assertion must land after the 3 s dismissal clock (4,000) and still have a row
        // to see. A local Notice here would die on its own 3.5 s deadline and mask a broken cancellation.
        user("still here")
        now = 5_000
        assertTrue("the new message must cancel the dismissal", visible().isNotEmpty())
    }

    @Test
    fun blankLinesAreIgnored() {
        model.onEvent(PhoneBubbleEvent.User(AssistantRoute.WAKE, "t1", "   "), now)
        assertTrue(visible().isEmpty())
    }

    @Test
    fun anUnmatchedToolEndAddsNothing() {
        tool("读取屏幕内容", ToolPhase.END)
        assertTrue(visible().isEmpty())
    }
}
