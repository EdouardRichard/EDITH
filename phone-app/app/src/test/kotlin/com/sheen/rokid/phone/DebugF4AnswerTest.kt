package com.sheen.rokid.phone
import com.sheen.rokid.phone.debug.F4AnswerDump

import com.sheen.rokid.contracts.AssistantRoute
import com.sheen.rokid.output.ConversationEntry
import com.sheen.rokid.output.ConversationRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The F4 assistant-output capture's pure parts (Phase 7 design §3.4).
 *
 * The receiver writes the WAKE route's last USER (the ASR final transcript) and last ASSISTANT (the assistant's final
 * answer) to files and logs only character counts and SHA-256. These tests pin the digest format the log line carries
 * and the selection rule, so a change that accidentally captured the *first* answer or a proactive entry is visible on
 * the JVM before the device campaign runs.
 */
class DebugF4AnswerTest {

    @Test
    fun `sha 256 of the empty string is the known digest`() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            F4AnswerDump.sha256(""),
        )
    }

    @Test
    fun `sha 256 of abc is the known digest`() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            F4AnswerDump.sha256("abc"),
        )
    }

    @Test
    fun `lastAssistant returns the last assistant entry and ignores the user's`() {
        val entries = listOf(
            entry("u1", ConversationRole.USER, "打开音乐"),
            entry("a1", ConversationRole.ASSISTANT, "第一句回答"),
            entry("u2", ConversationRole.USER, "播放李克勤的月半小夜曲"),
            entry("a2", ConversationRole.ASSISTANT, "第二句回答"),
            entry("u3", ConversationRole.USER, "还在吗"),
        )

        assertEquals("a2", F4AnswerDump.lastAssistant(entries)?.entryId)
    }

    @Test
    fun `lastAssistant is null when there is no assistant entry`() {
        assertNull(F4AnswerDump.lastAssistant(emptyList()))
        assertNull(F4AnswerDump.lastAssistant(listOf(entry("u1", ConversationRole.USER, "只有我说的"))))
    }

    @Test
    fun `lastUser returns the last user entry`() {
        val entries = listOf(
            entry("u1", ConversationRole.USER, "第一问"),
            entry("a1", ConversationRole.ASSISTANT, "回答"),
            entry("u2", ConversationRole.USER, "最后一问"),
        )

        assertEquals("u2", F4AnswerDump.lastUser(entries)?.entryId)
    }

    private fun entry(id: String, role: ConversationRole, text: String) =
        ConversationEntry(id, AssistantRoute.WAKE, role, text)
}
