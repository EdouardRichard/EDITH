package com.sheen.rokid.plugin

import com.sheen.rokid.contracts.Generation
import com.sheen.rokid.contracts.SessionId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PluginApiValidationTest {
    @Test fun `descriptor rejects duplicate capabilities`() {
        assertFailsWith<IllegalArgumentException> {
            PluginDescriptor("smart", "1.0.0", 1, "Smart", listOf(Capability.Model, Capability.Model), ConfigSchema(1))
        }
    }

    @Test fun `context exposes generation scope`() {
        val context = PluginContext(SessionId("s"), Generation(3), emptySet(), EmptyHostServices)
        assertEquals(Generation(3), context.generation)
    }

    @Test fun `deactivation reason is explicit`() {
        assertEquals(DeactivationReason.Switched, DeactivationReason.Switched)
    }

    @Test fun `capability enum keeps the declared order`() {
        // The guard is that a new capability may only be **appended**: reordering or inserting would renumber every
        // existing entry for a plugin that compared ordinals. P3.3 (spec §17.6) appended `Confirmation`, edith-0.2
        // Phase A appended `SystemOne` and `Embedding`, edith-0.2 Phase B appended `StorageAccess`, edith-0.2
        // Phase C appended `ConversationDisplay`, edith-0.3 Phase 1 appended `DeviceState`, and edith-0.3 Phase 2
        // appended `Perception`.
        assertEquals(
            listOf(
                "Audio", "Asr", "Speaker", "Model", "Storage", "TextOutput", "SpeechOutput",
                "Actions", "Context", "WakeWord", "Confirmation", "SystemOne", "Embedding", "StorageAccess",
                "ConversationDisplay", "DeviceState", "Perception",
            ),
            Capability.entries.map { it.name },
        )
    }

    @Test fun `descriptor accepts passive loop capabilities and still rejects duplicates`() {
        val requested = listOf(Capability.Actions, Capability.Context, Capability.WakeWord)
        assertEquals(requested.toSet(),
            PluginDescriptor("smart", "1.0.0", 1, "Smart", requested, ConfigSchema(1)).capabilities)
        assertFailsWith<IllegalArgumentException> {
            PluginDescriptor("smart", "1.0.0", 1, "Smart",
                listOf(Capability.WakeWord, Capability.WakeWord), ConfigSchema(1))
        }
    }
}
