package com.sheen.rokid.phone.app

import com.sheen.rokid.contracts.Assistance
import com.sheen.rokid.contracts.AssistantRoute
import com.sheen.rokid.contracts.Generation
import com.sheen.rokid.contracts.OutputStatus
import com.sheen.rokid.contracts.SessionId
import com.sheen.rokid.output.ConversationLog
import com.sheen.rokid.output.ConversationRole
import com.sheen.rokid.phone.bridge.ConversationWireDisplay
import com.sheen.rokid.phone.plugin.smarthelper.memory.MemoryExtraction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.4 Task 7 (`D201`, spec §4.3): the phone's display device while detached from the glasses.
 *
 * The device is **ack-only**. The assistant's final answer is written by the plugin's `publishAssistantFinal` into
 * the same [ConversationLog] the phone tab renders, so a display that appended here would replace the `say-<hintId>`
 * row the plugin just wrote - and a memory acknowledgement that shares the turn's hint id would then overwrite the
 * answer. What this device owes the host is exactly one `Displayed` acknowledgement: the plugin settles the answer
 * and opens the next listening round only on `Displayed`, never on `Accepted`.
 *
 * The tests here are the two halves of that contract: the ack (once, synchronously) and the absent write (the record
 * is unchanged by a show, by a clear, and by the answer + acknowledgement pair).
 */
class PhoneHintDeviceTest {

    private val session = SessionId("s-1")
    private val generation = Generation(1)

    /** The production display path, so the rows under assertion are the ones the phone tab really gets. */
    private fun display(log: ConversationLog) = ConversationWireDisplay(
        log = log,
        sendSaid = { _, _, _ -> true },
        sendBusy = { _, _ -> true },
    )

    private fun answer(hintId: String, text: String = "答案") = Assistance(
        hintId = hintId,
        sessionId = session,
        generation = generation,
        sourceTurnId = "t-1",
        priority = 0,
        text = text,
        optionalSpeech = null,
        expiresAtNanos = 0L,
    )

    @Test
    fun showReportsDisplayedOnceAndDoesNotWriteLog() {
        val log = ConversationLog()
        val device = PhoneHintDevice(log)
        val statuses = mutableListOf<OutputStatus>()

        device.show(answer("h-1"), revision = 1) { statuses += it }

        assertEquals("the plugin settles only on Displayed", listOf(OutputStatus.Displayed), statuses)
        assertEquals("the acknowledgement is delivered synchronously, not leaked to a later turn", 1, statuses.size)
        assertTrue("nothing was shown before, so nothing may be claimed visible", !device.answerRowVisible("h-1"))
        assertEquals("the phone display must not write the conversation record", 0, log.size())
    }

    /**
     * The production order is `submit` first, `publishAssistantFinal` second (`SmartAssistantPlugin.emitAnswer`), and
     * the fail-fast path is synchronous: the phone display cannot wait for that row and still answer the submit it
     * was called from. The acknowledgement is therefore unconditional and exactly once; the row is the plugin's, and
     * the tab renders it the moment it lands.
     */
    @Test
    fun showAcknowledgesTheEmitBeforeThePluginsAnswerRowLands() {
        val log = ConversationLog()
        val device = PhoneHintDevice(log)
        val statuses = mutableListOf<OutputStatus>()

        // What emitAnswer does: sink.submit (this call) then publishAssistantFinal (the append below).
        device.show(answer("h-1"), revision = 1) { statuses += it }
        display(log).assistantFinal(AssistantRoute.WAKE, "t-1", "h-1", "答案")

        assertEquals(listOf(OutputStatus.Displayed), statuses)
        assertTrue("the plugin's write is what makes the row visible", device.answerRowVisible("h-1"))
        assertEquals("the display itself added no row", 1, log.size())
    }

    @Test
    fun answerAndMemoryAckKeepOneAnswerRow() {
        val log = ConversationLog()
        val device = PhoneHintDevice(log)
        val wire = display(log)
        val statuses = mutableListOf<OutputStatus>()

        // The explicit-memory turn: the answer is emitted, then the acknowledgement is emitted in the same turn under
        // the same hint id (AnswerEmitIdentityIntegrationTest pins the ordering the real plugin produces).
        device.show(answer("h-1"), revision = 1) { statuses += it }
        wire.assistantFinal(AssistantRoute.WAKE, "t-1", "h-1", "答案")
        wire.assistantNotice(AssistantRoute.WAKE, MemoryExtraction.ACKNOWLEDGEMENT)
        device.show(answer("h-1", MemoryExtraction.ACKNOWLEDGEMENT), revision = 2) { statuses += it }

        val entries = log.entries(AssistantRoute.WAKE)
        val answerRows = entries.filter { it.entryId == "say-h-1" }
        assertEquals("the acknowledgement must not overwrite the answer row", 1, answerRows.size)
        assertEquals("答案", answerRows.single().text)
        assertEquals(ConversationRole.ASSISTANT, answerRows.single().role)
        assertEquals("both emits were acknowledged", listOf(OutputStatus.Displayed, OutputStatus.Displayed), statuses)
        assertEquals("the acknowledgement is its own row and nothing else was added", 2, log.size())
    }

    @Test
    fun clearDoesNotRemoveHistory() {
        val log = ConversationLog()
        val device = PhoneHintDevice(log)
        display(log).assistantFinal(AssistantRoute.WAKE, "t-1", "h-1", "答案")
        val before = log.entries(AssistantRoute.WAKE)

        device.clear(session, generation)

        assertEquals("clearing the live hint must never delete the conversation", before, log.entries(AssistantRoute.WAKE))
        assertEquals(1, log.size())
        assertTrue(device.answerRowVisible("h-1"))
    }

    @Test
    fun clearBeforeAnythingWasShownIsSafe() {
        val log = ConversationLog()
        val device = PhoneHintDevice(log)

        device.clear(session, generation)

        assertFalse(device.answerRowVisible("h-1"))
        assertEquals(0, log.size())
    }
}
