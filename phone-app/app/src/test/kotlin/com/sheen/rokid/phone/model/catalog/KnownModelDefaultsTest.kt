package com.sheen.rokid.phone.model.catalog

import com.sheen.rokid.plugin.InputModality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KnownModelDefaultsTest {
    @Test fun `deepseek flash and v4 pro use their documented windows`() {
        val flash = KnownModelDefaults.forId("deepseek-flash")
        assertEquals(1_048_576, flash?.contextWindowTokens)
        assertEquals(393_216, flash?.maxOutputTokens)
        assertEquals(setOf(InputModality.TEXT, InputModality.IMAGE), flash?.inputs)
        assertTrue(flash?.thinkingSupported == true)

        val pro = KnownModelDefaults.forId("deepseek-v4-pro")
        assertEquals(1_048_576, pro?.contextWindowTokens)
        assertEquals(393_216, pro?.maxOutputTokens)
        assertEquals(setOf(InputModality.TEXT), pro?.inputs)
        assertTrue(pro?.thinkingSupported == true)

        assertNull(KnownModelDefaults.forId("an-unknown-model"))
    }

    @Test fun `an unknown model gets the conservative 128k 8k text and image defaults`() {
        val defaults = KnownModelDefaults.conservativeDefaults()
        assertEquals(131_072, defaults.contextWindowTokens)
        assertEquals(8_192, defaults.maxOutputTokens)
        assertEquals(setOf(InputModality.TEXT, InputModality.IMAGE), defaults.inputs)
        assertFalse(defaults.thinkingSupported)
    }
}
