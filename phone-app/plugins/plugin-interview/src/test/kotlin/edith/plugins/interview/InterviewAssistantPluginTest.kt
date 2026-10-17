package edith.plugins.interview

import kotlin.test.*

class InterviewAssistantPluginTest {
    @Test fun `descriptor declares speaker and storage capabilities`() {
        val capabilities = InterviewAssistantPlugin().descriptor.capabilities
        assertTrue(edith.plugin.Capability.Speaker in capabilities)
        assertTrue(edith.plugin.Capability.Storage in capabilities)
    }
}
