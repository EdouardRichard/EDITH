package com.sheen.rokid.phone.plugin.smarthelper.memory

import com.sheen.rokid.phone.plugin.smarthelper.SmartAssistantPlugin
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Phase 7 F4 (§3.1/§3.2): the plugin's runtime measurement-form switch.
 *
 * Production must default to [MemoryMeasurementForm.PRODUCTION]; the F4 driver sets [MemoryMeasurementForm.LEGACY_RAW]
 * through the debug receiver **before** a session activates. The form is read once in `activate()` when the
 * `MemoryModule` (and therefore its renderer) is constructed, so these tests pin the default and the setter's effect on
 * the value the activation path reads.
 */
class MemoryMeasurementFormTest {

    @Test
    fun `the measurement form defaults to production`() {
        assertEquals(
            MemoryMeasurementForm.PRODUCTION,
            SmartAssistantPlugin().memoryMeasurementFormForTest(),
            "a fresh plugin must render the production memory block unless a measurement driver says otherwise",
        )
    }

    @Test
    fun `the setter selects legacy raw`() {
        val plugin = SmartAssistantPlugin()

        plugin.setMemoryMeasurementForm(MemoryMeasurementForm.LEGACY_RAW)

        assertEquals(
            MemoryMeasurementForm.LEGACY_RAW,
            plugin.memoryMeasurementFormForTest(),
            "setting LEGACY_RAW is what makes activate() construct the legacy raw renderer",
        )
    }
}
