package com.sheen.rokid.phone.bridge
import com.sheen.rokid.phone.app.HostCompositionRoot

import com.sheen.rokid.contracts.AssistantRoute
import com.sheen.rokid.output.ConversationLog
import com.sheen.rokid.output.ConversationRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase C: the one place the plugin's "someone said something / someone is working" becomes (a) the phone's own
 * conversation record and (b) traffic for the glasses.
 *
 * The order is the contract: **record first, send second**. A link that is down must cost the wearer the *display* of
 * an utterance, never the utterance itself - and the assistant tab reads the same record, so a dropped send can never
 * make the two screens disagree about what was said.
 *
 * The rule was extracted into its own class rather than living inside `HostCompositionRoot` so it can be tested without
 * a composition, an Android context or a device.
 */
class ConversationWireDisplayTest {

    private class Fixture {
        val log = ConversationLog()
        val said = mutableListOf<Pair<AssistantRoute, String>>()
        val busy = mutableListOf<Pair<AssistantRoute, Boolean>>()
        var sendResult = true
        val display = ConversationWireDisplay(
            log = log,
            sendSaid = { route, text -> said += route to text; sendResult },
            sendBusy = { route, value -> busy += route to value; sendResult },
        )
    }

    @Test
    fun anUtteranceIsRecordedAndForwarded() {
        val f = Fixture()
        f.display.userMessage(AssistantRoute.WAKE, "帮我看下日程")

        assertEquals(listOf("帮我看下日程"), f.log.entries(AssistantRoute.WAKE).map { it.text })
        assertEquals(ConversationRole.USER, f.log.entries(AssistantRoute.WAKE).single().role)
        assertEquals(listOf(AssistantRoute.WAKE to "帮我看下日程"), f.said)
    }

    @Test
    fun aRefusedSendStillLeavesTheUtteranceInTheRecord() {
        // The wearer said it whether or not the glasses heard about it, and the phone's tab is a record of the
        // conversation rather than a log of successful transmissions.
        val f = Fixture()
        f.sendResult = false
        f.display.userMessage(AssistantRoute.WAKE, "链接断了也要记住")

        assertEquals(listOf("链接断了也要记住"), f.log.entries(AssistantRoute.WAKE).map { it.text })
    }

    @Test
    fun anAssistantMessageIsRecordedOnItsOwnRouteAndNotSentAsAnUtterance() {
        // The assistant's side reaches the glasses as the hint itself (`SHOW`), which already carries the route; sending
        // it again as a `SAID` would show the wearer their own assistant's words as if they had spoken them.
        val f = Fixture()
        f.display.assistantMessage(AssistantRoute.PROACTIVE, "你有一条未读的会议邀请")

        val entries = f.log.entries(AssistantRoute.PROACTIVE)
        assertEquals(listOf("你有一条未读的会议邀请"), entries.map { it.text })
        assertEquals(ConversationRole.ASSISTANT, entries.single().role)
        assertTrue("no SAID may be produced for an assistant message", f.said.isEmpty())
    }

    @Test
    fun theTwoRoutesStayApartInTheRecord() {
        val f = Fixture()
        f.display.userMessage(AssistantRoute.WAKE, "唤醒的")
        f.display.assistantMessage(AssistantRoute.PROACTIVE, "主动的")

        assertEquals(listOf("唤醒的"), f.log.entries(AssistantRoute.WAKE).map { it.text })
        assertEquals(listOf("主动的"), f.log.entries(AssistantRoute.PROACTIVE).map { it.text })
    }

    @Test
    fun workingAndIdleAreSentAsALevelAndAreNotRecorded() {
        val f = Fixture()
        f.display.working(AssistantRoute.WAKE)
        f.display.idle(AssistantRoute.WAKE)

        assertEquals(listOf(AssistantRoute.WAKE to true, AssistantRoute.WAKE to false), f.busy)
        assertEquals("a transient level is not part of the conversation", 0, f.log.size())
    }

    @Test
    fun aBlankMessageIsNeitherRecordedNorSent() {
        val f = Fixture()
        f.display.userMessage(AssistantRoute.WAKE, "   ")
        f.display.assistantMessage(AssistantRoute.WAKE, "")

        assertEquals(0, f.log.size())
        assertTrue(f.said.isEmpty())
    }

    @Test
    fun twoUtterancesOfTheSameTextBothSurvive() {
        // The wearer can say the same thing twice; a log keyed on the text would lose the second one, which is why the
        // entry id is minted per statement rather than derived from it.
        val f = Fixture()
        f.display.userMessage(AssistantRoute.WAKE, "再说一遍")
        f.display.userMessage(AssistantRoute.WAKE, "再说一遍")

        assertEquals(listOf("再说一遍", "再说一遍"), f.log.entries(AssistantRoute.WAKE).map { it.text })
        assertFalse(f.log.entries(AssistantRoute.WAKE).first().entryId == f.log.entries(AssistantRoute.WAKE).last().entryId)
    }
}
