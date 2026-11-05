package com.sheen.rokid.phone.debug

import com.sheen.rokid.plugin.ThinkingEffort
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.53 design §5.7: the debug creator's optional `thinkingEnabled` / `thinkingEffort` properties.
 *
 * The receiver needs an Android host, so the parsing and the capability invariant are a pure function: absent
 * properties keep 0.52's off/low defaults, the shared family rule derives the capability, and a non-DeepSeek row can
 * never be seeded with thinking enabled.
 */
class DebugThinkingTest {

    @Test
    fun `absent properties keep thinking off at low`() {
        val thinking = debugThinking(modelId = "deepseek-flash", enabled = null, effort = null)

        assertTrue("a DeepSeek id is thinking-capable", thinking.supported)
        assertFalse("thinking defaults off", thinking.enabled)
        assertEquals("the effort defaults to low", ThinkingEffort.LOW, thinking.effort)
    }

    @Test
    fun `the optional properties enable thinking at the requested effort`() {
        val thinking = debugThinking(modelId = "deepseek-flash", enabled = "true", effort = "MAX")

        assertTrue(thinking.enabled)
        assertEquals(ThinkingEffort.MAX, thinking.effort)

        assertEquals(
            "the effort parses case-insensitively",
            ThinkingEffort.HIGH,
            debugThinking(modelId = "deepseek-flash", enabled = "false", effort = "high").effort,
        )
    }

    @Test
    fun `a non-deepseek model can never be seeded with thinking enabled`() {
        val thinking = debugThinking(modelId = "custom-vl", enabled = "true", effort = "HIGH")

        assertFalse(thinking.supported)
        assertFalse("the enabled flag is derived through the capability", thinking.enabled)
        assertEquals(ThinkingEffort.HIGH, thinking.effort)
    }

    @Test
    fun `an unknown effort is refused instead of guessed`() {
        assertThrows(IllegalArgumentException::class.java) {
            debugThinking(modelId = "deepseek-flash", enabled = "true", effort = "medium")
        }
    }
}
