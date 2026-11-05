package com.sheen.rokid.model

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ModelFamiliesTest {
    @Test fun `the id and the owner marker both identify the deepseek family`() {
        assertTrue(ModelFamilies.isDeepSeekFamily("deepseek-flash"))
        assertTrue(ModelFamilies.isDeepSeekFamily("DeepSeek-V4-Pro"))
        assertTrue(ModelFamilies.isDeepSeekFamily("custom-model", ownedBy = "deepseek"))
        assertTrue(ModelFamilies.isDeepSeekFamily("my-deepseek-proxy"))
    }

    @Test fun `an unrelated model is not the deepseek family`() {
        assertFalse(ModelFamilies.isDeepSeekFamily("qwen-omni"))
        assertFalse(ModelFamilies.isDeepSeekFamily("custom-vl", ownedBy = "openai"))
        assertFalse(ModelFamilies.isDeepSeekFamily(""))
        assertFalse(ModelFamilies.isDeepSeekFamily("   ", ownedBy = "  "))
    }
}
