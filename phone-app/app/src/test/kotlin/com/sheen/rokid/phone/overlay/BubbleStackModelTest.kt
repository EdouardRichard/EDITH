package com.sheen.rokid.phone.overlay

import com.sheen.rokid.contracts.AssistantRoute
import com.sheen.rokid.contracts.ToolPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.5 (`D211`/`D215`/`D216`/`D217`): the bubble timing rules, as pure JVM behaviour.
 *
 * The model returns a **relative** alpha (1.0 current, 0.42 superseded); the UI multiplies the old value by the user's
 * configured bubble opacity. These tests therefore pin the relation, not a rendered alpha.
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

    private fun idle() = model.onEvent(PhoneBubbleEvent.TurnIdle(AssistantRoute.WAKE), now)

    private fun visible() = model.snapshot(now).messages

    @Test
    fun aMessageIsItsOwnBubble() {
        user()
        now = 300

        val messages = visible()

        assertEquals(1, messages.size)
        assertEquals(BubbleKind.USER, messages.single().kind)
        assertEquals(1f, messages.single().relativeAlpha, 0.0001f)
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
        assertEquals(3, visible().size)
        assertEquals(BubbleKind.TOOL_RUNNING, visible().last().kind)

        tool("读取屏幕内容", ToolPhase.END)
        now = 400
        val messages = visible()
        assertEquals("END must not add a fourth bubble", 3, messages.size)
        assertEquals(BubbleKind.TOOL_DONE, messages.last().kind)
        assertEquals("读取屏幕内容", messages.last().text)
    }

    @Test
    fun answerPartialsUpdateTheSameBubbleAndTheFinalSettlesIt() {
        user(); now = 100; thinking(); now = 200
        partial("这是一张")
        now = 300
        assertEquals("the answer joins the faded user and thinking bubbles", 3, visible().size)
        partial("这是一张外卖订单")
        now = 400
        assertEquals("a partial must not add a bubble", 3, visible().size)
        assertEquals("这是一张外卖订单", visible().last().text)

        final("这是一张外卖订单：预计 12:30 送达。")
        now = 500
        assertEquals(3, visible().size)
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
        assertEquals(3, messages.size)
        assertEquals(BubbleKind.ANSWER, messages[1].kind)
        assertEquals("第一条回答", messages[1].text)
        assertEquals(BubbleKind.ANSWER, messages[2].kind)
        assertEquals("第二条通知", messages[2].text)
    }

    @Test
    fun atMostThreeBubblesAreKeptAndTheOldestIsDropped() {
        user("one"); now = 100
        thinking(); now = 200
        tool("读取屏幕内容", ToolPhase.START); now = 300
        final("answer one"); now = 400
        assertEquals(3, visible().size)

        now = 500
        model.onEvent(PhoneBubbleEvent.Notice(AssistantRoute.WAKE, "notice"), now)
        // Give the new bubble its 200 ms entry fade before asserting what is on screen; at the exact creation instant its
        // alpha is 0 by design.
        now = 700

        val messages = visible()
        assertEquals(3, messages.size)
        assertEquals("the oldest bubble is gone", "读取屏幕内容", messages[0].text)
        assertEquals("notice", messages[2].text)
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
        model.onEvent(PhoneBubbleEvent.Notice(AssistantRoute.WAKE, "still here"), now)
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
