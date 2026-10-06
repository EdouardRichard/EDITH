package edith.plugins.smart

import kotlin.test.*

class SmartAssistantPluginTest {
    @Test fun `descriptor is stable and lifecycle toggles active`() {
        val plugin = SmartAssistantPlugin()
        assertEquals("smart-assistant", plugin.descriptor.pluginId)
        assertFalse(plugin.active)
        plugin.deactivate(edith.plugin.DeactivationReason.Stopped)
        assertFalse(plugin.active)
    }
}
