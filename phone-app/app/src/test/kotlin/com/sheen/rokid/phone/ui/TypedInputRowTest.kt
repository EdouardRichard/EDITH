package com.sheen.rokid.phone.ui

import com.sheen.rokid.contracts.AssistantRoute
import com.sheen.rokid.contracts.SessionState
import com.sheen.rokid.phone.app.TypedSendResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * The WAKE conversation input row's behaviour, as pure functions (0.4 Task 11, spec §5.1).
 *
 * There is no Compose test dependency in this module, so the row is split: [TypedInputState] and the helpers pin the
 * decisions, and `TypedInputRow` in `PhoneScreen.kt` only draws them. The last test is a source guard for the one rule
 * a pure function cannot express on its own — the typed text never reaches a log.
 */
class TypedInputRowTest {

    @Test
    fun `send needs a live session, a non-blank draft and no send in flight`() {
        for (session in SessionState.entries) {
            val expected = session == SessionState.Ready || session == SessionState.Listening
            assertEquals(
                "session=$session",
                expected,
                canSendTyped(session, blank = false, sending = false),
            )
        }
        assertTrue(canSendTyped(SessionState.Listening, blank = false, sending = false))
        assertFalse("blank drafts are refused locally", canSendTyped(SessionState.Ready, blank = true, sending = false))
        assertFalse("one send at a time", canSendTyped(SessionState.Ready, blank = false, sending = true))
    }

    @Test
    fun `a disabled send explains why in Chinese`() {
        assertEquals("正在发送…", typedSendBlockedReason(SessionState.Ready, blank = false, sending = true))
        assertEquals("请先开始会话", typedSendBlockedReason(SessionState.Idle, blank = false, sending = false))
        // The session reason wins over "nothing typed": the wearer has to start a session before typing can help.
        assertEquals("请先开始会话", typedSendBlockedReason(SessionState.Idle, blank = true, sending = false))
        assertEquals("输入内容后可发送", typedSendBlockedReason(SessionState.Listening, blank = true, sending = false))
        assertNull(typedSendBlockedReason(SessionState.Ready, blank = false, sending = false))

        listOf(
            typedSendBlockedReason(SessionState.Idle, blank = false, sending = false),
            typedSendBlockedReason(SessionState.Ready, blank = false, sending = true),
            typedSendBlockedReason(SessionState.Ready, blank = true, sending = false),
        ).forEach { reason ->
            assertNotNull(reason)
            assertTrue("reason must be Chinese: $reason", reason!!.any { it in '\u4e00'..'\u9fff' })
        }
    }

    @Test
    fun `accepted clears the draft while every refusal keeps it`() {
        val draft = "帮我记一条笔记"
        val sent = TypedInputState(draft = draft)

        val accepted = sent.finishSend(TypedSendResult.Accepted)
        assertEquals("", accepted.draft)
        assertEquals(TypedSendResult.Accepted, accepted.result)
        assertFalse(accepted.sending)

        for (refusal in listOf(
            TypedSendResult.Blank,
            TypedSendResult.NoSession,
            TypedSendResult.Busy,
            TypedSendResult.Stale,
        )) {
            val kept = sent.finishSend(refusal)
            assertEquals("result=$refusal must keep the draft", draft, kept.draft)
            assertEquals(refusal, kept.result)
            assertFalse("result=$refusal ends the send", kept.sending)
        }
    }

    @Test
    fun `starting a send marks it in flight and clears the previous result`() {
        val started = TypedInputState(draft = "补发", result = TypedSendResult.Busy).beginSend()

        assertTrue(started.sending)
        assertNull(started.result)
        assertEquals("补发", started.draft)
    }

    @Test
    fun `the status line shows the refusal reason and the idle hint only when sendable`() {
        val blocked = TypedInputState(draft = "帮我看看", result = TypedSendResult.Busy)
        assertEquals(typedResultLabel(TypedSendResult.Busy), typedStatusText(SessionState.Ready, blocked))

        // No result: the reason the button is disabled is the status.
        assertEquals(
            "请先开始会话",
            typedStatusText(SessionState.Idle, TypedInputState(draft = "帮我看看")),
        )
        assertEquals(
            "输入内容后可发送",
            typedStatusText(SessionState.Ready, TypedInputState()),
        )
        // An accepted send is reported while the cleared draft would otherwise say "type something".
        assertEquals("已发送", typedStatusText(SessionState.Ready, TypedInputState(result = TypedSendResult.Accepted)))

        val idle = typedStatusText(SessionState.Ready, TypedInputState(draft = "帮我看看"))
        assertEquals(TYPED_IDLE_LABEL, idle)
        assertTrue("idle hint must be Chinese: $idle", idle.any { it in '\u4e00'..'\u9fff' })
    }

    @Test
    fun `every send result has its own short Chinese label and no result has none`() {
        assertNull(typedResultLabel(null))

        val labels = TypedSendResult.entries.map { result ->
            val label = typedResultLabel(result)
            assertNotNull("result=$result needs a label", label)
            assertTrue("label must be Chinese: $label", label!!.any { it in '\u4e00'..'\u9fff' })
            assertTrue("label must stay short: $label", label.length <= 20)
            label
        }
        assertEquals("each result explains itself differently", labels.size, labels.toSet().size)
    }

    @Test
    fun `the input row is rendered on the wake view only`() {
        assertTrue(showsTypedInputRow(AssistantRoute.WAKE))
        assertFalse(showsTypedInputRow(AssistantRoute.PROACTIVE))
    }

    @Test
    fun `the draft is capped at two thousand characters`() {
        assertEquals(2000, TYPED_DRAFT_MAX_CHARS)
        val atLimit = "汉".repeat(TYPED_DRAFT_MAX_CHARS)
        assertEquals(atLimit, typedDraftAfterInput(atLimit))

        val overLimit = typedDraftAfterInput(atLimit + "汉")
        assertEquals(TYPED_DRAFT_MAX_CHARS, overLimit.length)
        assertEquals(atLimit, overLimit)
        assertEquals("", typedDraftAfterInput(""))
    }

    @Test
    fun `the dictation microphone is shown but disabled until task 12b`() {
        assertFalse("Task 12b wires dictation; this task only reserves the control", MIC_DICTATION_ENABLED)
        assertTrue(MIC_DICTATION_DESCRIPTION.any { it in '\u4e00'..'\u9fff' })
        assertTrue(
            "the description must say what the button will do: $MIC_DICTATION_DESCRIPTION",
            "语音" in MIC_DICTATION_DESCRIPTION || "听写" in MIC_DICTATION_DESCRIPTION,
        )
    }

    @Test
    fun `the draft and result survive a page recreation`() {
        val state = TypedInputState(draft = "未发送的文字", result = TypedSendResult.Busy)
        assertEquals(state, typedInputRestore(typedInputSaveValues(state)))

        assertEquals(TypedInputState(draft = "只有草稿"), typedInputRestore(listOf("只有草稿", "")))
        // A removed/renamed enum value must not crash a restored page.
        assertEquals(TypedInputState(draft = "旧结果"), typedInputRestore(listOf("旧结果", "NoSuchResult")))
        assertEquals(TypedInputState(), typedInputRestore(emptyList()))
    }

    @Test
    fun `the input row and its helpers never log`() {
        val forbidden = listOf("P3Log", "android.util.Log", "println(", "System.out")
        listOf(
            "src/main/kotlin/com/sheen/rokid/phone/ui/TypedInput.kt",
            "src/main/kotlin/com/sheen/rokid/phone/ui/PhoneScreen.kt",
        ).forEach { relative ->
            val source = mainSource(relative).readText()
            forbidden.forEach { marker ->
                assertFalse("$relative must not log typed text through $marker", marker in source)
            }
        }
    }

    private fun mainSource(relative: String): File {
        val candidates = listOf(File(relative), File("phone-app/app/$relative"))
        val file = candidates.firstOrNull { it.isFile }
        if (file == null) fail("source not found; looked at ${candidates.joinToString { it.absolutePath }}")
        return file!!
    }
}
