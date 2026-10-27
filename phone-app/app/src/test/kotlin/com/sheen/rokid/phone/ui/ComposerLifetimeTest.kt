package com.sheen.rokid.phone.ui

import com.sheen.rokid.contracts.SessionState
import com.sheen.rokid.phone.app.TypedSendResult
import com.sheen.rokid.phone.audio.DictationState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * 0.4 Task 13e-prep: the composer's lifetime across the phone's bottom-tab switches.
 *
 * The two device findings this pins:
 *
 * - a draft (including a dictated final appended to it) must survive 助手 -> 技能/记忆 -> 助手, because the composer
 *   belongs to the phone page, not to the assistant tab's composition;
 * - a typed result (`助手正在处理` and friends) is an answer about the session that was live when the send happened,
 *   so a stop/restart must clear it instead of leaving it on screen until the next send.
 *
 * The state transitions are pure functions so they are JVM-testable; the hoisting itself is a Compose fact, guarded by
 * source structure at the bottom of this file.
 */
class ComposerLifetimeTest {

    private val live = listOf(SessionState.Ready, SessionState.Listening)

    @Test
    fun `a stopped session clears the stale typed result and keeps the draft`() {
        val typed = TypedInputState(draft = "还没发的草稿", result = TypedSendResult.Busy)
        for (previous in live) {
            for (stopped in SessionState.entries.filterNot { it in live }) {
                val after = typed.afterSessionChange(previous, stopped)
                assertNull("$previous -> $stopped must clear the stale result", after.result)
                assertEquals("$previous -> $stopped must keep the wearer's words", "还没发的草稿", after.draft)
            }
        }
    }

    @Test
    fun `a started session clears a refusal left over from the idle page`() {
        val typed = TypedInputState(draft = "还没发的草稿", result = TypedSendResult.NoSession)
        for (started in live) {
            val after = typed.afterSessionChange(SessionState.Idle, started)
            assertNull("Idle -> $started must clear the old refusal", after.result)
            assertEquals("Idle -> $started must keep the wearer's words", "还没发的草稿", after.draft)
        }
    }

    @Test
    fun `ready and listening are one live session so its result survives the move`() {
        val typed = TypedInputState(draft = "还没发的草稿", result = TypedSendResult.Accepted)
        assertEquals(typed, typed.afterSessionChange(SessionState.Ready, SessionState.Listening))
        assertEquals(typed, typed.afterSessionChange(SessionState.Listening, SessionState.Ready))
        assertEquals(typed, typed.afterSessionChange(SessionState.Ready, SessionState.Ready))
    }

    @Test
    fun `the typed result survives only moves inside the live set`() {
        val typed = TypedInputState(draft = "草稿", result = TypedSendResult.Busy)
        for (previous in SessionState.entries) {
            for (current in SessionState.entries) {
                val kept = (previous in live) == (current in live)
                val after = typed.afterSessionChange(previous, current)
                assertEquals(
                    "$previous -> $current: result ${if (kept) "must survive" else "must clear"}",
                    if (kept) typed else typed.copy(result = null),
                    after,
                )
            }
        }
    }

    @Test
    fun `a listening status outlives the stale typed result's clear`() {
        val after = TypedInputState(draft = "草稿", result = TypedSendResult.Busy)
            .afterSessionChange(SessionState.Listening, SessionState.Idle)
        assertNull(after.result)
        assertEquals(
            "a session change must not clear the live dictation status",
            DICTATION_LISTENING_LABEL,
            inputRowStatusText(SessionState.Idle, after, DictationState.Listening),
        )
    }

    @Test
    fun `a dictated final appends to the draft the session change kept`() {
        val afterSession = TypedInputState(draft = "已有的话", result = TypedSendResult.Busy)
            .afterSessionChange(SessionState.Ready, SessionState.Idle)
        val afterFinal = afterSession.afterDictationFinal("听写的话")
        assertEquals("已有的话 听写的话", afterFinal.draft)
        assertNull("the newest action replaces the older result", afterFinal.result)
    }

    @Test
    fun `a blank dictated final keeps the draft and still clears the stale result`() {
        val afterFinal = TypedInputState(draft = "已有的话", result = TypedSendResult.Busy)
            .afterDictationFinal("  ")
        assertEquals("已有的话", afterFinal.draft)
        assertNull(afterFinal.result)
    }

    // ---- the state's owner: a source guard, because Compose disposal is not JVM-testable -------------------------

    @Test
    fun `the composer state lives on the phone page above the bottom-tab switch`() {
        val source = phoneScreenSource()
        val declaration = "var typedInput by rememberSaveable(stateSaver = typedInputSaver)"
        val declaredAt = source.indexOf(declaration)
        assertTrue("PhoneScreen must own the composer state", declaredAt >= 0)

        val tabSwitchAt = source.indexOf("when (phoneTabFor(tab.ordinal))")
        assertTrue("the bottom-tab switch must exist", tabSwitchAt >= 0)
        assertTrue(
            "the composer state must be hoisted above the bottom-tab switch, not owned by AssistantTab",
            declaredAt < tabSwitchAt,
        )

        // The trailing comma is the call argument; without it this would also match the session-change assignment
        // `typedInput = typedInput.afterSessionChange(...)` and stop proving the value is passed on.
        assertTrue("the assistant tab must receive the hoisted value", "typedInput = typedInput," in source)
        assertTrue(
            "the assistant tab must receive the hoisted setter",
            "onTypedInputChange = { typedInput = it }" in source,
        )
    }

    @Test
    fun `the assistant tab only consumes the hoisted composer state`() {
        // A tab switch disposes AssistantTab. If the state is declared there (as it was before Task 13e-prep), both the
        // draft and a final waiting to be appended are lost even though PhoneScreen never left the composition.
        val assistant = phoneScreenSource().substringAfter("internal fun AssistantTab(")
        assertTrue("AssistantTab must accept the hoisted value", "typedInput: TypedInputState" in assistant)
        assertTrue(
            "AssistantTab must accept the hoisted setter",
            "onTypedInputChange: (TypedInputState) -> Unit" in assistant,
        )
        assertFalse("AssistantTab must not own the composer state", "var typedInput" in assistant)
        assertTrue(
            "the dictated final must append through the hoisted transition",
            "afterDictationFinal(" in assistant,
        )
    }

    @Test
    fun `the page clears a stale typed result when the session changes`() {
        val page = phoneScreenSource().substringBefore("internal fun AssistantTab(")
        assertTrue("the page must observe session changes", "LaunchedEffect(state.session)" in page)
        assertTrue(
            "the stale result must be cleared through the pure transition, keeping the draft",
            "typedInput = typedInput.afterSessionChange(previousSession, state.session)" in page,
        )
    }

    private fun phoneScreenSource(): String =
        mainSource("src/main/kotlin/com/sheen/rokid/phone/ui/PhoneScreen.kt").readText()

    private fun mainSource(relative: String): File {
        val candidates = listOf(File(relative), File("phone-app/app/$relative"))
        val file = candidates.firstOrNull { it.isFile }
        if (file == null) fail("source not found; looked at ${candidates.joinToString { it.absolutePath }}")
        return file!!
    }
}
