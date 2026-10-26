package com.sheen.rokid.phone.ui

import com.sheen.rokid.contracts.SessionState
import com.sheen.rokid.phone.audio.DictationState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.4 Task 12b-ii (spec §5.3, D204): the input row's dictation decisions, as pure functions.
 *
 * The module has no Compose UI test dependency, so the row's behaviour is data + functions here and `TypedInputRow`
 * only draws them. What is pinned:
 *
 * - the delivered final is **appended** to the draft (a space between existing text and the new sentence), never sent;
 * - the status line shows Listening/NoSpeech/Error while a dictation is happening and falls back to the send status
 *   once it is Idle;
 * - the microphone is enabled only for a live session (the same `Ready`/`Listening` rule as typing) and its Chinese
 *   description says 开始 or 停止 depending on the lease state.
 */
class DictationInputTest {

    @Test
    fun `a delivered final is appended to the draft and never replaces what was typed`() {
        assertEquals("你好", appendDictation("", "你好"))
        assertEquals("已有 你好", appendDictation("已有", "你好"))
        assertEquals("已有 你好", appendDictation("已有 ", "你好"))
        assertEquals("已有 你好", appendDictation("已有", " 你好 "))
        assertEquals("只有草稿", appendDictation("只有草稿", "   "))
        assertEquals("只有草稿", appendDictation("只有草稿", ""))
    }

    @Test
    fun `the appended draft keeps the field cap and never exceeds two thousand characters`() {
        val atLimit = "汉".repeat(TYPED_DRAFT_MAX_CHARS)
        val appended = appendDictation(atLimit, "听写")
        assertEquals(TYPED_DRAFT_MAX_CHARS, appended.length)
        assertEquals(atLimit, appended)

        val room = "汉".repeat(TYPED_DRAFT_MAX_CHARS - 3)
        assertEquals(room + " 听写", appendDictation(room, "听写"))
    }

    @Test
    fun `the microphone is available only for a live session`() {
        for (session in SessionState.entries) {
            val live = session == SessionState.Ready || session == SessionState.Listening
            assertEquals("session=$session", live, canDictate(session))
            assertEquals("session=$session", live, dictationMicEnabled(session))
        }
    }

    @Test
    fun `listening, no speech and error each have a Chinese status line`() {
        assertNull("Idle falls through to the typed status", dictationStatusText(DictationState.Idle))
        assertEquals(DICTATION_LISTENING_LABEL, dictationStatusText(DictationState.Listening))
        assertEquals(DICTATION_NO_SPEECH_LABEL, dictationStatusText(DictationState.NoSpeech))
        assertEquals(DICTATION_FAILED_LABEL, dictationStatusText(DictationState.Error("DICTATION_STOP_FAILED")))

        listOf(DICTATION_LISTENING_LABEL, DICTATION_NO_SPEECH_LABEL, DICTATION_FAILED_LABEL).forEach { label ->
            assertTrue("label must be Chinese: $label", label.any { it in '\u4e00'..'\u9fff' })
            assertTrue("label must stay short: $label", label.length <= 20)
        }
    }

    @Test
    fun `the dictation status wins over the send status while it lasts and then falls back`() {
        val typed = TypedInputState(draft = "已输入")
        assertEquals(
            DICTATION_LISTENING_LABEL,
            inputRowStatusText(SessionState.Listening, typed, DictationState.Listening),
        )
        assertEquals(
            DICTATION_NO_SPEECH_LABEL,
            inputRowStatusText(SessionState.Listening, typed, DictationState.NoSpeech),
        )
        assertEquals(
            "回到普通输入状态",
            typedStatusText(SessionState.Listening, typed),
            inputRowStatusText(SessionState.Listening, typed, DictationState.Idle),
        )
    }

    @Test
    fun `the microphone description says start or stop in Chinese`() {
        assertEquals(DICTATION_START_DESCRIPTION, dictationMicDescription(DictationState.Idle))
        assertEquals(DICTATION_STOP_DESCRIPTION, dictationMicDescription(DictationState.Listening))
        assertEquals(DICTATION_START_DESCRIPTION, dictationMicDescription(DictationState.NoSpeech))
        assertEquals(DICTATION_START_DESCRIPTION, dictationMicDescription(DictationState.Error("MIC_UNAVAILABLE")))

        listOf(DICTATION_START_DESCRIPTION, DICTATION_STOP_DESCRIPTION).forEach { description ->
            assertTrue("description must be Chinese: $description", description.any { it in '\u4e00'..'\u9fff' })
        }
        assertFalse("start and stop must not read the same", DICTATION_START_DESCRIPTION == DICTATION_STOP_DESCRIPTION)
    }
}
