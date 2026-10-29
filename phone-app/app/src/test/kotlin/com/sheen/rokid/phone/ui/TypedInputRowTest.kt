package com.sheen.rokid.phone.ui

import com.sheen.rokid.contracts.AssistantRoute
import com.sheen.rokid.contracts.SessionState
import com.sheen.rokid.phone.app.TypedSendResult
import com.sheen.rokid.phone.audio.DictationStartResult
import com.sheen.rokid.phone.audio.DictationState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * The WAKE conversation composer's behaviour, as pure functions (0.4 Task 11, spec §5.1).
 *
 * There is no Compose test dependency in this module, so the composer is split: [TypedInputState] and the helpers pin
 * the decisions, and `TypedInputRow` in `PhoneScreen.kt` only draws them. The source guards cover the two rules a pure
 * function cannot express on its own — the redesign keeps one soft field with one circular action, and typed text never
 * reaches a log.
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
    fun `the composer offers exactly one action of microphone, send, or stop`() {
        // Blank + idle is the microphone.
        assertEquals(
            ComposerActionSlot(ComposerAction.Microphone, enabled = true),
            composerActionSlot(SessionState.Ready, TypedInputState(), DictationState.Idle, owner = null),
        )
        // Text is the send arrow while no dictation owns the field.
        assertEquals(
            ComposerActionSlot(ComposerAction.Send, enabled = true),
            composerActionSlot(SessionState.Listening, TypedInputState(draft = "你好"), DictationState.Idle, owner = null),
        )
        // A live lease is the stop square, with or without text.
        assertEquals(
            ComposerActionSlot(ComposerAction.Stop, enabled = true),
            composerActionSlot(SessionState.Listening, TypedInputState(), DictationState.Listening, owner = null),
        )
        assertEquals(
            ComposerActionSlot(ComposerAction.Stop, enabled = true),
            composerActionSlot(SessionState.Ready, TypedInputState(draft = "你好"), DictationState.Listening, owner = null),
        )
        // A terminal dictation gives the microphone back, because the next tap may start a fresh one.
        assertEquals(
            ComposerActionSlot(ComposerAction.Microphone, enabled = true),
            composerActionSlot(SessionState.Ready, TypedInputState(), DictationState.NoSpeech, owner = null),
        )
        assertEquals(
            ComposerActionSlot(ComposerAction.Microphone, enabled = true),
            composerActionSlot(SessionState.Ready, TypedInputState(), DictationState.Error("MIC_UNAVAILABLE"), owner = null),
        )
    }

    @Test
    fun `the action slot is a muted circle when its action is unavailable`() {
        // No live session: both the microphone and the send arrow stay in place but cannot be tapped.
        assertEquals(
            ComposerActionSlot(ComposerAction.Microphone, enabled = false),
            composerActionSlot(SessionState.Idle, TypedInputState(), DictationState.Idle, owner = null),
        )
        assertEquals(
            ComposerActionSlot(ComposerAction.Send, enabled = false),
            composerActionSlot(SessionState.Idle, TypedInputState(draft = "你好"), DictationState.Idle, owner = null),
        )
        // One synchronous send at a time.
        assertEquals(
            ComposerActionSlot(ComposerAction.Send, enabled = false),
            composerActionSlot(
                SessionState.Ready,
                TypedInputState(draft = "你好", sending = true),
                DictationState.Idle,
                owner = null,
            ),
        )
        // Stop must stay tappable even if the session state has already moved: a lease must always be stoppable.
        assertEquals(
            ComposerActionSlot(ComposerAction.Stop, enabled = true),
            composerActionSlot(SessionState.Idle, TypedInputState(), DictationState.Listening, owner = null),
        )
    }

    @Test
    fun `every action slot icon has a short Chinese description`() {
        assertEquals(DICTATION_START_DESCRIPTION, composerActionDescription(ComposerAction.Microphone))
        assertEquals(TYPED_SEND_LABEL, composerActionDescription(ComposerAction.Send))
        assertEquals(DICTATION_STOP_DESCRIPTION, composerActionDescription(ComposerAction.Stop))
        ComposerAction.entries.forEach { action ->
            val description = composerActionDescription(action)
            assertTrue("description must be Chinese: $description", description.any { it in '\u4e00'..'\u9fff' })
            assertTrue("description must stay short: $description", description.length <= 10)
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
    fun `the status line is absent when there is nothing to say`() {
        // The old row printed 「输入后点『发送』」 permanently. The composer must stay quiet while it is usable:
        // neither an empty draft nor a sendable draft has anything to report.
        assertNull(typedStatusText(SessionState.Ready, TypedInputState()))
        assertNull(typedStatusText(SessionState.Ready, TypedInputState(draft = "帮我看看")))
        assertNull(inputRowStatusText(SessionState.Ready, TypedInputState(), DictationState.Idle, null))
        assertNull(inputRowStatusText(SessionState.Ready, TypedInputState(draft = "帮我看看"), DictationState.Idle, null))

        // A refusal or a busy/no-session reason is something the wearer needs to read.
        val refused = TypedInputState(draft = "帮我看看", result = TypedSendResult.Busy)
        assertEquals(typedResultLabel(TypedSendResult.Busy), typedStatusText(SessionState.Ready, refused))
        assertEquals(
            "请先开始会话",
            typedStatusText(SessionState.Idle, TypedInputState(draft = "帮我看看")),
        )
        assertEquals(
            "正在发送…",
            typedStatusText(SessionState.Ready, TypedInputState(draft = "帮我看看", sending = true)),
        )
        assertEquals("已发送", typedStatusText(SessionState.Ready, TypedInputState(result = TypedSendResult.Accepted)))

        listOf(
            typedStatusText(SessionState.Idle, TypedInputState()),
            typedStatusText(SessionState.Ready, TypedInputState(sending = true)),
        ).forEach { status ->
            assertNotNull(status)
            assertTrue("status must be Chinese: $status", status!!.any { it in '\u4e00'..'\u9fff' })
        }
    }

    @Test
    fun `the status line covers live dictation, its terminal outcomes and a refused start`() {
        val typed = TypedInputState(draft = "帮我看看")
        assertEquals(
            DICTATION_LISTENING_LABEL,
            inputRowStatusText(SessionState.Ready, typed, DictationState.Listening),
        )
        assertEquals(
            DICTATION_NO_SPEECH_LABEL,
            inputRowStatusText(SessionState.Ready, typed, DictationState.NoSpeech),
        )
        assertEquals(
            DICTATION_FAILED_LABEL,
            inputRowStatusText(SessionState.Ready, typed, DictationState.Error("MIC_UNAVAILABLE")),
        )
        assertEquals(
            dictationStartStatusText(DictationStartResult.NoScheduler),
            inputRowStatusText(SessionState.Ready, typed, DictationState.Idle, DictationStartResult.NoScheduler),
        )
    }

    @Test
    fun `the placeholder is the only permanent prompt and it is Chinese`() {
        assertEquals("发消息…", TYPED_INPUT_PLACEHOLDER)
        assertTrue(TYPED_INPUT_PLACEHOLDER.any { it in '\u4e00'..'\u9fff' })
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
    fun `the dictation microphone is enabled and described in Chinese`() {
        assertTrue("Task 12b-ii wires dictation; the reserved control is live now", MIC_DICTATION_ENABLED)
        assertTrue(
            "the description must say what the button does: $DICTATION_START_DESCRIPTION",
            "语音" in DICTATION_START_DESCRIPTION || "听写" in DICTATION_START_DESCRIPTION,
        )
        assertTrue(DICTATION_STOP_DESCRIPTION.any { it in '\u4e00'..'\u9fff' })
        assertEquals("停止语音输入", DICTATION_STOP_DESCRIPTION)
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
    fun `the composer is one soft filled field with a single circular action`() {
        val source = mainSource("src/main/kotlin/com/sheen/rokid/phone/ui/PhoneScreen.kt").readText()

        // The owner's redesign (option A): a single pill, no outline, no floating label, no second button.
        assertTrue("the composer must be a borderless BasicTextField", "BasicTextField(" in source)
        assertTrue("the composer must sit above the keyboard", "imePadding()" in source)
        assertTrue("the pill's radius is fixed by the design", "RoundedCornerShape(28.dp)" in source)
        assertTrue("one circular icon action", "FilledIconButton(" in source && "CircleShape" in source)
        assertTrue("the only persistent prompt is the placeholder", "TYPED_INPUT_PLACEHOLDER" in source)
        assertFalse("no outlined field remains", "OutlinedTextField" in source)
        assertFalse("no floating field label remains", "TYPED_INPUT_LABEL" in source)
        assertFalse("no permanent idle helper remains", "TYPED_IDLE_LABEL" in source)
        assertFalse("no rounded-outline shape remains", "OutlinedTextFieldDefaults" in source)
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
