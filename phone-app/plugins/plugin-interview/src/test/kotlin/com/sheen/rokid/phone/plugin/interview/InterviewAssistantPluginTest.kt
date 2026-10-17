package com.sheen.rokid.phone.plugin.interview

import kotlin.test.*

class InterviewAssistantPluginTest {
    @Test fun `descriptor declares speaker and storage capabilities`() {
        val capabilities = InterviewAssistantPlugin().descriptor.capabilities
        assertTrue(com.sheen.rokid.plugin.Capability.Speaker in capabilities)
        assertTrue(com.sheen.rokid.plugin.Capability.Storage in capabilities)
    }
}
