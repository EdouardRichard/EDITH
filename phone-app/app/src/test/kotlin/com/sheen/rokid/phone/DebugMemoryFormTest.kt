package com.sheen.rokid.phone
import com.sheen.rokid.phone.debug.DebugMemoryForm

import com.sheen.rokid.phone.plugin.smarthelper.memory.MemoryMeasurementForm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The debug memory-form receiver's parser (Phase 7 F4, design §3.2).
 *
 * The receiver carries the form as the short `form` extra (`A` = legacy raw, `B` = production) because the whole
 * command must fit an `adb shell am broadcast` line and be easy to read in a transcript. Anything other than those two
 * letters is refused rather than guessed at: silently defaulting a typo would turn an A-slot into a B-slot and corrupt
 * the pre-registered alternation.
 */
class DebugMemoryFormTest {

    @Test
    fun `a and A select legacy raw`() {
        assertEquals(MemoryMeasurementForm.LEGACY_RAW, DebugMemoryForm.parse("A"))
        assertEquals(MemoryMeasurementForm.LEGACY_RAW, DebugMemoryForm.parse("a"))
    }

    @Test
    fun `b and B select production`() {
        assertEquals(MemoryMeasurementForm.PRODUCTION, DebugMemoryForm.parse("B"))
        assertEquals(MemoryMeasurementForm.PRODUCTION, DebugMemoryForm.parse("b"))
    }

    @Test
    fun `an absent empty or unknown value is refused`() {
        assertNull(DebugMemoryForm.parse(null))
        assertNull(DebugMemoryForm.parse(""))
        assertNull(DebugMemoryForm.parse("C"))
    }
}
