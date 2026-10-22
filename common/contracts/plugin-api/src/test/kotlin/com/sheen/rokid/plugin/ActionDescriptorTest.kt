package com.sheen.rokid.plugin

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * edith-0.3 Phase 4A (A1): [ActionDescriptor.description] is the **model-facing** copy; [ActionDescriptor.title] stays
 * the short user-visible label. A descriptor that declares no description must keep the pre-4A behaviour exactly.
 */
class ActionDescriptorTest {

    private fun descriptor() = ActionDescriptor(
        name = "device.set_volume",
        title = "调节音量",
        parametersJson = """{"level": 3}""",
        risk = RiskLevel.L1,
        requiresConfirmation = false,
        idempotent = true,
        reversible = true,
        scope = "device",
    )

    @Test fun `a descriptor without a description defaults it to the title`() {
        assertEquals("调节音量", descriptor().description)
    }

    @Test fun `a blank description is rejected`() {
        // An empty description would hand the model a tool it cannot decide to call, so it is not a legal declaration.
        listOf("", "   ").forEach { blank ->
            assertFailsWith<IllegalArgumentException>("accepted description: <$blank>") {
                descriptor().copy(description = blank)
            }
        }
    }
}
