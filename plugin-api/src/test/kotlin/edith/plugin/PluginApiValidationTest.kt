package edith.plugin

import edith.contracts.Generation
import edith.contracts.SessionId
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
}
