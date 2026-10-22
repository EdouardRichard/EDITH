package com.sheen.rokid.plugin

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ActionsContractTest {
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

    @Test fun `risk levels are exactly five and ascending`() {
        assertEquals(5, RiskLevel.entries.size)
        assertEquals(listOf("L0", "L1", "L2", "L3", "L4"), RiskLevel.entries.map { it.name })
    }

    @Test fun `action outcomes keep the declared order`() {
        assertEquals(listOf("Done", "Refused", "Failed"), ActionOutcome.entries.map { it.name })
    }

    @Test fun `descriptor revalidates the schema when copied`() {
        // `copy` runs the init block like the primary constructor, so the invariant must hold on the copy path too.
        assertFailsWith<IllegalArgumentException> { descriptor().copy(parametersJson = "[]") }
    }

    @Test fun `descriptor accepts object shaped schemas`() {
        listOf("{}", """{"a":{"b":1}}""", """  {"level": 3}  """).forEach { schema ->
            assertEquals(schema, descriptor().copy(parametersJson = schema).parametersJson)
        }
    }

    @Test fun `descriptor accepts an object shaped schema at the 16 KB limit`() {
        // The schema is deliberately not valid JSON: `plugin-api` is dependency-free and checks shape only, so this
        // layer must not parse. JSON validity is enforced when `agent-runtime`'s `ToolRegistry` registers the
        // descriptor (Task 4).
        val limit = 16 * 1024
        val atLimit = "{" + "a".repeat(limit - 2) + "}"
        assertEquals(limit, atLimit.length)
        descriptor().copy(parametersJson = atLimit) // must not throw
    }

    @Test fun `descriptor rejects an object shaped schema larger than 16 KB`() {
        val oversized = "{\"k\":\"" + "a".repeat(16_400) + "\"}"
        assertTrue(oversized.length > 16 * 1024)
        assertFailsWith<IllegalArgumentException> { descriptor().copy(parametersJson = oversized) }
    }

    @Test fun `descriptor rejects blank identity fields`() {
        listOf<() -> ActionDescriptor>(
            { descriptor().copy(name = "") },
            { descriptor().copy(name = "   ") },
            { descriptor().copy(title = "") },
            { descriptor().copy(title = " ") },
            { descriptor().copy(scope = "") },
            { descriptor().copy(scope = "  ") },
        ).forEach { create -> assertFailsWith<IllegalArgumentException> { create() } }
    }

    @Test fun `descriptor rejects blank or non object shaped schemas`() {
        listOf(
            "", "   ",            // blank
            "[]", "\"text\"", "3", "null", "not json", // not object shaped
            "{", "}", "{} trailing", "leading {}",     // wrong braces
        ).forEach { schema ->
            assertFailsWith<IllegalArgumentException>("accepted parametersJson: <$schema>") {
                descriptor().copy(parametersJson = schema)
            }
        }
    }

    @Test fun `screen observation defaults off and is copyable`() {
        // edith-0.3 Phase 2.2 Task 3: only device/open/shell/dial declare it (Tasks 4-6); every plugin tool keeps false.
        assertFalse(descriptor().screenObservation)
        assertTrue(descriptor().copy(screenObservation = true).screenObservation)
    }

    @Test fun `actions capability is addressable on a plugin descriptor`() {
        val descriptor = PluginDescriptor("x", "1.0.0", 1, "X", listOf(Capability.Actions), ConfigSchema(1))
        assertTrue(Capability.Actions in descriptor.capabilities)
        assertEquals(setOf(Capability.Actions), descriptor.capabilities)
    }
}
