package com.sheen.rokid.phone.bridge

import com.sheen.rokid.contracts.AssistantRoute
import com.sheen.rokid.contracts.ToolPhase
import com.sheen.rokid.output.ConversationEntry
import com.sheen.rokid.output.ConversationLog
import com.sheen.rokid.output.ConversationRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase C + 0.24 S2: the one place the plugin's "someone said something / someone is working / the official
 * conversation advanced" becomes (a) the phone's own conversation record and (b) traffic for the glasses.
 *
 * The order is the contract: **record first, send second**. A link that is down must cost the wearer the *display* of
 * an utterance, never the utterance itself - and the assistant tab reads the same record, so a dropped send can never
 * make the two screens disagree about what was said.
 *
 * 0.24 adds the official commands (`STATE/IDLE/ASR/ANSWER/TOOL/PANEL_OPEN/CLOSE`) on top of the legacy `SAID`/`BUSY`
 * levels. This file pins which call goes to which command, that the phone's record is written before either send, and
 * that a partial revision keeps the same entry **and its turn id** - the log replaces the whole entry on a repeated id,
 * so a revision that forgot the turn would silently drop it.
 *
 * The rule was extracted into its own class rather than living inside `HostCompositionRoot` so it can be tested without
 * a composition, an Android context or a device.
 */
class ConversationWireDisplayTest {

    /** One official command the display asked the wire to carry. */
    private sealed interface WireCall {
        data class State(val route: AssistantRoute, val turnId: String, val status: Int, val sequence: Long) : WireCall
        data class Idle(val route: AssistantRoute, val sequence: Long) : WireCall
        data class Asr(val route: AssistantRoute, val turnId: String, val revision: Long, val text: String) : WireCall
        data class Answer(
            val route: AssistantRoute,
            val turnId: String,
            val hintId: String,
            val revision: Long,
            val text: String,
        ) : WireCall
        data class Tool(val route: AssistantRoute, val turnId: String, val phase: ToolPhase, val label: String) : WireCall
        data class PanelOpen(val route: AssistantRoute) : WireCall
        data class Close(val route: AssistantRoute) : WireCall
    }

    private class FakeWire(private val log: ConversationLog) : ConversationWire {
        var result = true
        val calls = mutableListOf<WireCall>()

        /** Both routes' entries at the instant the wire was asked to send, so "record first" is observable. */
        val snapshotAtSend = mutableListOf<List<ConversationEntry>>()

        private fun record(call: WireCall): Boolean {
            calls += call
            snapshotAtSend += AssistantRoute.entries.flatMap { log.entries(it) }
            return result
        }

        override fun state(route: AssistantRoute, turnId: String, status: Int, sequence: Long): Boolean =
            record(WireCall.State(route, turnId, status, sequence))

        override fun idle(route: AssistantRoute, sequence: Long): Boolean =
            record(WireCall.Idle(route, sequence))

        override fun asr(route: AssistantRoute, turnId: String, revision: Long, text: String): Boolean =
            record(WireCall.Asr(route, turnId, revision, text))

        override fun answer(
            route: AssistantRoute,
            turnId: String,
            hintId: String,
            revision: Long,
            text: String,
        ): Boolean = record(WireCall.Answer(route, turnId, hintId, revision, text))

        override fun tool(route: AssistantRoute, turnId: String, phase: ToolPhase, label: String): Boolean =
            record(WireCall.Tool(route, turnId, phase, label))

        override fun panelOpen(route: AssistantRoute): Boolean = record(WireCall.PanelOpen(route))

        override fun close(route: AssistantRoute): Boolean = record(WireCall.Close(route))
    }

    private class Fixture {
        val log = ConversationLog()
        val wire = FakeWire(log)
        val said = mutableListOf<Pair<AssistantRoute, String>>()
        val busy = mutableListOf<Pair<AssistantRoute, Boolean>>()
        var sendResult = true
        var logAtSaid: List<ConversationEntry> = emptyList()
        val display = ConversationWireDisplay(
            log = log,
            wire = wire,
            sendSaid = { route, text ->
                said += route to text
                logAtSaid = AssistantRoute.entries.flatMap { log.entries(it) }
                sendResult
            },
            sendBusy = { route, value ->
                busy += route to value
                sendResult
            },
        )
    }

    @Test
    fun aPartialUtteranceIsRecordedBeforeItIsSent() {
        val f = Fixture()
        f.display.userPartial(AssistantRoute.WAKE, "t1", 1, "帮我看下")

        val entry = f.log.entries(AssistantRoute.WAKE).single()
        assertEquals("asr-t1", entry.entryId)
        assertEquals(ConversationRole.USER, entry.role)
        assertEquals("t1", entry.turnId)
        assertEquals("帮我看下", entry.text)
        assertEquals(listOf<WireCall>(WireCall.Asr(AssistantRoute.WAKE, "t1", 1, "帮我看下")), f.wire.calls)
        assertEquals(
            "the phone record must exist when the wire is asked to send",
            listOf("帮我看下"),
            f.wire.snapshotAtSend.single().map { it.text },
        )
    }

    @Test
    fun revisingAPartialKeepsTheSameEntryAndResendsItsTurn() {
        val f = Fixture()
        f.display.userPartial(AssistantRoute.WAKE, "t1", 1, "帮")
        f.display.userPartial(AssistantRoute.WAKE, "t1", 2, "帮我看")

        // Same id: the log replaces the whole entry, so forgetting `turnId` on the revision would clear it.
        val entry = f.log.entries(AssistantRoute.WAKE).single()
        assertEquals("asr-t1", entry.entryId)
        assertEquals("t1", entry.turnId)
        assertEquals("帮我看", entry.text)
        assertEquals(
            listOf<WireCall>(
                WireCall.Asr(AssistantRoute.WAKE, "t1", 1, "帮"),
                WireCall.Asr(AssistantRoute.WAKE, "t1", 2, "帮我看"),
            ),
            f.wire.calls,
        )
    }

    @Test
    fun aPartialAnswerIsRecordedUnderTheHintIdBeforeItIsSent() {
        val f = Fixture()
        f.display.assistantPartial(AssistantRoute.PROACTIVE, "t2", "h2", 1, "正在查找")

        val entry = f.log.entries(AssistantRoute.PROACTIVE).single()
        assertEquals("say-h2", entry.entryId)
        assertEquals(ConversationRole.ASSISTANT, entry.role)
        assertEquals("t2", entry.turnId)
        assertEquals("正在查找", entry.text)
        assertEquals(
            listOf<WireCall>(WireCall.Answer(AssistantRoute.PROACTIVE, "t2", "h2", 1, "正在查找")),
            f.wire.calls,
        )
        assertTrue(f.said.isEmpty())
        assertEquals(
            "the phone record must exist when the wire is asked to send",
            listOf("正在查找"),
            f.wire.snapshotAtSend.single().map { it.text },
        )
    }

    @Test
    fun revisingAnAnswerKeepsTheSameEntryAndResendsItsTurn() {
        val f = Fixture()
        f.display.assistantPartial(AssistantRoute.WAKE, "t1", "h1", 1, "答案")
        f.display.assistantPartial(AssistantRoute.WAKE, "t1", "h1", 2, "答案完成")

        val entry = f.log.entries(AssistantRoute.WAKE).single()
        assertEquals("say-h1", entry.entryId)
        assertEquals("t1", entry.turnId)
        assertEquals("答案完成", entry.text)
        assertEquals(
            listOf<WireCall>(
                WireCall.Answer(AssistantRoute.WAKE, "t1", "h1", 1, "答案"),
                WireCall.Answer(AssistantRoute.WAKE, "t1", "h1", 2, "答案完成"),
            ),
            f.wire.calls,
        )
    }

    @Test
    fun aRefusedPartialSendStillLeavesTheUtteranceInTheRecord() {
        val f = Fixture()
        f.wire.result = false
        f.display.userPartial(AssistantRoute.WAKE, "t1", 1, "链接断了也要记住")

        assertEquals(listOf("链接断了也要记住"), f.log.entries(AssistantRoute.WAKE).map { it.text })
        assertEquals("the attempt is still made", 1, f.wire.calls.size)
    }

    @Test
    fun aRefusedAnswerSendStillLeavesTheAnswerInTheRecord() {
        val f = Fixture()
        f.wire.result = false
        f.display.assistantPartial(AssistantRoute.PROACTIVE, "t2", "h2", 1, "链接断了也要记住")

        assertEquals(listOf("链接断了也要记住"), f.log.entries(AssistantRoute.PROACTIVE).map { it.text })
        assertEquals("the attempt is still made", 1, f.wire.calls.size)
    }

    @Test
    fun theOfficialStatusIsForwardedWithItsWireFieldsAndIsNotAConversationEntry() {
        val f = Fixture()
        f.display.state(AssistantRoute.PROACTIVE, "t3", 4, 17)

        assertEquals(
            listOf<WireCall>(WireCall.State(AssistantRoute.PROACTIVE, "t3", 4, 17)),
            f.wire.calls,
        )
        assertEquals("a status level is not part of the conversation", 0, f.log.size())
    }

    @Test
    fun theOfficialIdleIsForwardedAndIsNotTheLegacyBusyLevel() {
        val f = Fixture()
        f.display.showIdle(AssistantRoute.PROACTIVE, 21)

        assertEquals(listOf<WireCall>(WireCall.Idle(AssistantRoute.PROACTIVE, 21)), f.wire.calls)
        assertTrue("showIdleStatus must not travel as BUSY=false", f.busy.isEmpty())
        assertEquals(0, f.log.size())
    }

    @Test
    fun aToolStatusIsForwardedWithItsPhaseAndLabelAndIsNotAConversationEntry() {
        val f = Fixture()
        f.display.toolStatus(AssistantRoute.WAKE, "t1", ToolPhase.START, "search")
        f.display.toolStatus(AssistantRoute.WAKE, "t1", ToolPhase.END, "search")

        assertEquals(
            listOf<WireCall>(
                WireCall.Tool(AssistantRoute.WAKE, "t1", ToolPhase.START, "search"),
                WireCall.Tool(AssistantRoute.WAKE, "t1", ToolPhase.END, "search"),
            ),
            f.wire.calls,
        )
        assertEquals("a transient tool row is not part of the conversation", 0, f.log.size())
    }

    @Test
    fun openingAndClosingThePanelAreForwardedWithoutTouchingTheRecord() {
        val f = Fixture()
        f.display.panelOpen(AssistantRoute.WAKE)
        f.display.closePanel(AssistantRoute.PROACTIVE)

        assertEquals(
            listOf<WireCall>(WireCall.PanelOpen(AssistantRoute.WAKE), WireCall.Close(AssistantRoute.PROACTIVE)),
            f.wire.calls,
        )
        assertEquals(0, f.log.size())
    }

    @Test
    fun theFinalUtteranceStillTravelsAsASaidAndReplacesThePartialEntry() {
        val f = Fixture()
        f.display.userPartial(AssistantRoute.WAKE, "t1", 1, "帮我看")
        f.display.userMessage(AssistantRoute.WAKE, "帮我看下日程")

        assertEquals(listOf(AssistantRoute.WAKE to "帮我看下日程"), f.said)
        // One entry per turn: the final replaces the line the wearer already saw, keeping its turn.
        val entry = f.log.entries(AssistantRoute.WAKE).single()
        assertEquals("asr-t1", entry.entryId)
        assertEquals("t1", entry.turnId)
        assertEquals("帮我看下日程", entry.text)
        assertEquals("the record is written before the SAID leaves", "帮我看下日程", f.logAtSaid.single().text)
        assertEquals(
            "the final utterance is not sent as an official command",
            listOf<WireCall>(WireCall.Asr(AssistantRoute.WAKE, "t1", 1, "帮我看")),
            f.wire.calls,
        )
    }

    @Test
    fun theFinalAnswerReplacesThePartialEntryWithoutAFinalSendOfItsOwn() {
        val f = Fixture()
        f.display.assistantPartial(AssistantRoute.WAKE, "t1", "h1", 1, "答案")
        f.display.assistantMessage(AssistantRoute.WAKE, "答案完成")

        // The final answer reaches the glasses as the hint (`SHOW`), so this class only records it - but it must be
        // the same entry and keep the turn the partial opened.
        val entry = f.log.entries(AssistantRoute.WAKE).single()
        assertEquals("say-h1", entry.entryId)
        assertEquals("t1", entry.turnId)
        assertEquals("答案完成", entry.text)
        assertTrue("the assistant final is not an utterance", f.said.isEmpty())
        assertEquals("no second official send for the final", 1, f.wire.calls.size)
        assertEquals(WireCall.Answer(AssistantRoute.WAKE, "t1", "h1", 1, "答案"), f.wire.calls.single())
    }

    @Test
    fun theTurnCarryingOverloadsRecordTheFinalStatementUnderTheConventionId() {
        val f = Fixture()
        f.display.userMessage(AssistantRoute.WAKE, "t7", "最终的用户话")
        f.display.assistantMessage(AssistantRoute.WAKE, "t7", "h7", "最终的答案")

        val user = f.log.entries(AssistantRoute.WAKE).first { it.role == ConversationRole.USER }
        assertEquals("asr-t7", user.entryId)
        assertEquals("t7", user.turnId)
        assertEquals("最终的用户话", user.text)
        assertEquals(listOf(AssistantRoute.WAKE to "最终的用户话"), f.said)

        val assistant = f.log.entries(AssistantRoute.WAKE).first { it.role == ConversationRole.ASSISTANT }
        assertEquals("say-h7", assistant.entryId)
        assertEquals("t7", assistant.turnId)
        assertEquals("最终的答案", assistant.text)
        assertTrue("neither final is an official command", f.wire.calls.isEmpty())
    }

    @Test
    fun aFinalWithoutAPartialKeepsTheOldEntryIdBehavior() {
        val f = Fixture()
        f.display.userMessage(AssistantRoute.WAKE, "没有部分")
        f.display.assistantMessage(AssistantRoute.WAKE, "答案")

        val user = f.log.entries(AssistantRoute.WAKE).first { it.role == ConversationRole.USER }
        assertTrue(user.entryId.startsWith("said-"))
        assertNull(user.turnId)
        val assistant = f.log.entries(AssistantRoute.WAKE).first { it.role == ConversationRole.ASSISTANT }
        assertTrue(assistant.entryId.startsWith("say-"))
        assertNull(assistant.turnId)
    }

    @Test
    fun aBlankPartialIsNeitherRecordedNorSent() {
        val f = Fixture()
        f.display.userPartial(AssistantRoute.WAKE, "t1", 1, "   ")
        f.display.assistantPartial(AssistantRoute.WAKE, "t1", "h1", 1, "")

        assertEquals(0, f.log.size())
        assertTrue(f.wire.calls.isEmpty())
    }

    @Test
    fun aPartialWithoutATurnOrHintIdIsNeitherRecordedNorSent() {
        val f = Fixture()
        f.display.userPartial(AssistantRoute.WAKE, " ", 1, "有字")
        f.display.assistantPartial(AssistantRoute.WAKE, "t1", "", 1, "有字")

        assertEquals(0, f.log.size())
        assertTrue(f.wire.calls.isEmpty())
    }

    @Test
    fun partialsStayOnTheirOwnRoute() {
        val f = Fixture()
        f.display.userPartial(AssistantRoute.WAKE, "t1", 1, "唤醒的")
        f.display.assistantPartial(AssistantRoute.PROACTIVE, "t2", "h2", 1, "主动的")

        assertEquals(listOf("唤醒的"), f.log.entries(AssistantRoute.WAKE).map { it.text })
        assertEquals(listOf("主动的"), f.log.entries(AssistantRoute.PROACTIVE).map { it.text })
        assertEquals(
            listOf<WireCall>(
                WireCall.Asr(AssistantRoute.WAKE, "t1", 1, "唤醒的"),
                WireCall.Answer(AssistantRoute.PROACTIVE, "t2", "h2", 1, "主动的"),
            ),
            f.wire.calls,
        )
    }

    @Test
    fun aStatusForTheSameTurnKeepsThePartialLine() {
        val f = Fixture()
        f.display.assistantPartial(AssistantRoute.WAKE, "t1", "h1", 1, "答案")
        f.display.state(AssistantRoute.WAKE, "t1", 5, 9)
        f.display.assistantMessage(AssistantRoute.WAKE, "答案完成")

        val entry = f.log.entries(AssistantRoute.WAKE).single()
        assertEquals("say-h1", entry.entryId)
        assertEquals("t1", entry.turnId)
        assertEquals("答案完成", entry.text)
    }

    @Test
    fun aStatusForANewTurnForgetsThePartialThatNeverGotItsFinal() {
        val f = Fixture()
        f.display.assistantPartial(AssistantRoute.WAKE, "t1", "h1", 1, "旧答案")
        f.display.state(AssistantRoute.WAKE, "t2", 1, 10)
        f.display.assistantMessage(AssistantRoute.WAKE, "新答案")

        // The abandoned turn keeps its streamed line; the final of the new turn must not overwrite it.
        val entries = f.log.entries(AssistantRoute.WAKE)
        assertEquals(2, entries.size)
        assertEquals("say-h1", entries.first().entryId)
        assertEquals("t1", entries.first().turnId)
        assertEquals("新答案", entries.last().text)
        assertTrue(entries.last().entryId.startsWith("say-"))
        assertFalse(entries.last().entryId == "say-h1")
        assertNull(entries.last().turnId)
    }

    @Test
    fun closingThePanelForgetsThePartialThatNeverGotItsFinal() {
        val f = Fixture()
        f.display.userPartial(AssistantRoute.WAKE, "t1", 1, "半句")
        f.display.closePanel(AssistantRoute.WAKE)
        f.display.userMessage(AssistantRoute.WAKE, "另一句")

        val entries = f.log.entries(AssistantRoute.WAKE)
        assertEquals(2, entries.size)
        assertEquals("asr-t1", entries.first().entryId)
        assertEquals("t1", entries.first().turnId)
        assertEquals("另一句", entries.last().text)
        assertTrue(entries.last().entryId.startsWith("said-"))
        assertNull(entries.last().turnId)
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
        assertTrue("no official command may be produced for a final answer", f.wire.calls.isEmpty())
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
        assertTrue("the busy level is not the official IDLE", f.wire.calls.isEmpty())
    }

    @Test
    fun aBlankMessageIsNeitherRecordedNorSent() {
        val f = Fixture()
        f.display.userMessage(AssistantRoute.WAKE, "   ")
        f.display.assistantMessage(AssistantRoute.WAKE, "")

        assertEquals(0, f.log.size())
        assertTrue(f.said.isEmpty())
        assertTrue(f.wire.calls.isEmpty())
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
