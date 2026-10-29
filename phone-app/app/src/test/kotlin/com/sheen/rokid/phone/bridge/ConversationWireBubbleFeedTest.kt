package com.sheen.rokid.phone.bridge

import com.sheen.rokid.contracts.AssistantRoute
import com.sheen.rokid.contracts.ToolPhase
import com.sheen.rokid.output.ConversationLog
import com.sheen.rokid.phone.overlay.PhoneBubbleEvent
import com.sheen.rokid.phone.overlay.PhoneBubbleFeed
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 0.5: the display→bubble forwarding seam.
 *
 * The existing [ConversationWireDisplayTest] pins the record/wire behavior; this class pins only which app-internal
 * event each official display call emits. A wrong mapping here compiles fine and silently shows the wrong bubble, so it
 * gets its own explicit assertions.
 */
class ConversationWireBubbleFeedTest {

    private val events = mutableListOf<PhoneBubbleEvent>()
    private val feed = PhoneBubbleFeed { events += it }

    private val display = ConversationWireDisplay(
        log = ConversationLog(),
        wire = object : ConversationWire {
            override fun state(route: AssistantRoute, turnId: String, status: Int, sequence: Long) = true
            override fun idle(route: AssistantRoute, sequence: Long) = true
            override fun asr(route: AssistantRoute, turnId: String, revision: Long, text: String) = true
            override fun answer(
                route: AssistantRoute,
                turnId: String,
                hintId: String,
                revision: Long,
                text: String,
            ) = true
            override fun tool(route: AssistantRoute, turnId: String, phase: ToolPhase, label: String) = true
            override fun panelOpen(route: AssistantRoute) = true
            override fun close(route: AssistantRoute) = true
        },
        sendSaid = { _, _, _ -> true },
        sendBusy = { _, _ -> true },
        bubbleFeed = feed,
    )

    @Test
    fun aUserFinalBecomesAUserBubbleWithItsTurn() {
        display.userFinal(AssistantRoute.WAKE, "t1", "asr-t1", "帮我看看这个页面")

        assertEquals(listOf(PhoneBubbleEvent.User(AssistantRoute.WAKE, "t1", "帮我看看这个页面")), events)
    }

    @Test
    fun aLegacyUserMessageBecomesAUserBubbleWithoutATurn() {
        display.userMessage(AssistantRoute.WAKE, "你好")

        assertEquals(listOf(PhoneBubbleEvent.User(AssistantRoute.WAKE, null, "你好")), events)
    }

    @Test
    fun answerPartialsAndTheFinalMapToTheSameHint() {
        display.assistantPartial(AssistantRoute.WAKE, "t1", "h1", 1, "一半")
        display.assistantFinal(AssistantRoute.WAKE, "t1", "h1", "完整回答")

        assertEquals(
            listOf(
                PhoneBubbleEvent.AnswerPartial(AssistantRoute.WAKE, "t1", "h1", "一半"),
                PhoneBubbleEvent.AnswerFinal(AssistantRoute.WAKE, "t1", "h1", "完整回答"),
            ),
            events,
        )
    }

    @Test
    fun toolStatusCarriesTheSameLabelAndPhaseTheWireGets() {
        display.toolStatus(AssistantRoute.WAKE, "t1", ToolPhase.START, "读取屏幕内容")
        display.toolStatus(AssistantRoute.WAKE, "t1", ToolPhase.END, "读取屏幕内容")

        assertEquals(
            listOf(
                PhoneBubbleEvent.Tool(AssistantRoute.WAKE, "t1", "读取屏幕内容", ToolPhase.START),
                PhoneBubbleEvent.Tool(AssistantRoute.WAKE, "t1", "读取屏幕内容", ToolPhase.END),
            ),
            events,
        )
    }

    @Test
    fun aNoticeIsItsOwnEvent() {
        display.assistantNotice(AssistantRoute.WAKE, "已记住")

        assertEquals(listOf(PhoneBubbleEvent.Notice(AssistantRoute.WAKE, "已记住")), events)
    }

    @Test
    fun showIdleAndCloseBothEndTheBubbleTurn() {
        display.showIdle(AssistantRoute.WAKE, 7)
        display.closePanel(AssistantRoute.WAKE)

        assertEquals(
            listOf(
                PhoneBubbleEvent.TurnIdle(AssistantRoute.WAKE),
                PhoneBubbleEvent.TurnIdle(AssistantRoute.WAKE),
            ),
            events,
        )
    }

    @Test
    fun blankTextEmitsNothing() {
        display.userFinal(AssistantRoute.WAKE, "t1", "asr-t1", "   ")
        display.assistantNotice(AssistantRoute.WAKE, "")
        display.toolStatus(AssistantRoute.WAKE, "t1", ToolPhase.START, "")

        assertEquals(emptyList<PhoneBubbleEvent>(), events)
    }
}
