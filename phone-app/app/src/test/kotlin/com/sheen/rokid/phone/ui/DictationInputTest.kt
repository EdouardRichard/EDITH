package com.sheen.rokid.phone.ui

import com.sheen.rokid.contracts.SessionState
import com.sheen.rokid.phone.audio.DictationFinal
import com.sheen.rokid.phone.audio.DictationStartResult
import com.sheen.rokid.phone.audio.DictationState
import com.sheen.rokid.phone.app.TypedSendResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
        assertNull(
            "with nothing left to report, the composer stays quiet",
            inputRowStatusText(SessionState.Listening, typed, DictationState.Idle),
        )
    }

    // ---- finding 1: the final's lifetime is the host's, not the page's --------------------------------------------

    @Test
    fun `a final is appended only for the next host sequence and never for a consumed one`() {
        // A page re-created by a tab switch or an Activity recreation starts its counter at zero. If the host still
        // holds the final (nobody appended it yet), it is delivered once; once consumed the host clears it, so a
        // returning page has nothing to append even though its own counter is zero again.
        assertFalse("a consumed final is gone, not re-delivered", isNewDictationFinal(null, 0L))
        assertTrue(isNewDictationFinal(DictationFinal(1, "离开页面时说的话"), 0L))
        assertFalse("the same delivered value collected again is appended once", isNewDictationFinal(DictationFinal(3, "三"), 3L))
        assertFalse(isNewDictationFinal(DictationFinal(2, "旧"), 3L))
        assertTrue(isNewDictationFinal(DictationFinal(4, "四"), 3L))
    }

    @Test
    fun `a process restart's sequence starting at one is appended by a fresh page and not dropped`() {
        // A new host lifetime starts its monotonic sequence at 1 again. The fixed page never restores the old raw
        // sequence, so its counter is zero and the new lifetime's first final is appended. A page that restored the
        // previous lifetime's N would silently ignore the next N finals (the data loss this fix removes).
        assertTrue("a fresh page accepts the restarted sequence", isNewDictationFinal(DictationFinal(1, "新进程的第一条"), 0L))
        assertFalse("a page that restored the dead host's counter would drop it", isNewDictationFinal(DictationFinal(1, "新进程的第一条"), 1L))
        assertTrue(isNewDictationFinal(DictationFinal(2, "新进程的第二条"), 1L))
    }

    // ---- finding 4c: a refused start is explained on the row, not ignored ----------------------------------------

    @Test
    fun `every refused start has a retryable Chinese explanation and an accepted start has none`() {
        assertNull("nothing to say when no start was refused", dictationStartStatusText(null))
        assertNull("a started dictation speaks through its Listening state", dictationStartStatusText(DictationStartResult.Accepted))

        val refused = DictationStartResult.entries.filter { it != DictationStartResult.Accepted }
        assertTrue("the host's refusal vocabulary must stay covered", refused.size >= 5)
        refused.forEach { result ->
            val label = dictationStartStatusText(result)
            assertNotNull("refusal $result needs a status line", label)
            assertTrue("label must be Chinese: $label", label!!.any { it in '\u4e00'..'\u9fff' })
            assertTrue("label must stay short: $label", label.length <= 30)
            assertTrue("a refusal is retryable, so it asks the wearer to try again: $label", "重试" in label || "稍后" in label || "重新" in label)
        }

        val permission = requireNotNull(dictationStartStatusText(DictationStartResult.Permission))
        assertTrue("the permission refusal names the permission: $permission", "麦克风" in permission && "权限" in permission)
    }

    @Test
    fun `a start refusal is shown after listening and before the typed status`() {
        val typed = TypedInputState(draft = "已输入")
        assertEquals(
            "a live lease outranks an older refusal",
            DICTATION_LISTENING_LABEL,
            inputRowStatusText(SessionState.Listening, typed, DictationState.Listening, DictationStartResult.Busy),
        )
        assertEquals(
            "the refusal explains why the tap did nothing",
            dictationStartStatusText(DictationStartResult.NoScheduler),
            inputRowStatusText(SessionState.Listening, typed, DictationState.Idle, DictationStartResult.NoScheduler),
        )
        assertNull(
            "once dismissed there is nothing to report until the next action",
            inputRowStatusText(SessionState.Listening, typed, DictationState.Idle, null),
        )
        assertEquals(
            "a terminal dictation state still outranks a refusal",
            DICTATION_NO_SPEECH_LABEL,
            inputRowStatusText(SessionState.Listening, typed, DictationState.NoSpeech, DictationStartResult.Busy),
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
