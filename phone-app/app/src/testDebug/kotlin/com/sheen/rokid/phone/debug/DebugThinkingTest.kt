package com.sheen.rokid.phone.debug

import com.sheen.rokid.plugin.ThinkingEffort
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.53 design §5.7 (ruling A): the debug creator's explicit `thinkingEnabled` / `thinkingEffort` properties.
 *
 * The receiver needs an Android host, so the parsing is a pure function. Absent properties keep 0.52's off/low
 * defaults and there is no family judgement: the instrument seeds exactly what the properties say, so a device run can
 * exercise thinking on any model.
 */
class DebugThinkingTest {

    @Test
    fun `absent properties keep thinking off at low`() {
        val thinking = debugThinking(enabled = null, effort = null)

        assertFalse("thinking defaults off", thinking.enabled)
        assertEquals("the effort defaults to low", ThinkingEffort.LOW, thinking.effort)
    }

    @Test
    fun `the explicit properties drive the seed`() {
        val thinking = debugThinking(enabled = "true", effort = "MAX")

        assertTrue(thinking.enabled)
        assertEquals(ThinkingEffort.MAX, thinking.effort)

        assertEquals(
            "the effort parses case-insensitively",
            ThinkingEffort.HIGH,
            debugThinking(enabled = "false", effort = "high").effort,
        )
        assertFalse(debugThinking(enabled = "false", effort = "high").enabled)
    }

    @Test
    fun `an unknown effort is refused instead of guessed`() {
        assertThrows(IllegalArgumentException::class.java) {
            debugThinking(enabled = "true", effort = "medium")
        }
    }
}
