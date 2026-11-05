package com.sheen.rokid.phone.settings

import com.sheen.rokid.phone.model.catalog.ModelEntry
import com.sheen.rokid.phone.model.catalog.ModelOrigin
import com.sheen.rokid.phone.model.catalog.Provider
import com.sheen.rokid.phone.ui.settings.ModelCatalogUi
import com.sheen.rokid.plugin.InputModality
import com.sheen.rokid.plugin.OutputModality
import com.sheen.rokid.plugin.ThinkingEffort
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.53 design §5.7 / ruling A: the model row's chip reports what the user switched on, never a capability guess.
 */
class ModelCatalogChipsTest {

    private fun provider() = Provider(
        id = "p-1",
        name = "Provider",
        baseUrl = "https://example.test/v1",
    )

    private fun entry(thinkingEnabled: Boolean, thinkingSupported: Boolean) = ModelEntry(
        id = "m-1",
        contextWindowTokens = 131_072,
        maxOutputTokens = 8_192,
        inputs = setOf(InputModality.TEXT),
        outputs = setOf(OutputModality.TEXT),
        thinkingEnabled = thinkingEnabled,
        thinkingEffort = ThinkingEffort.LOW,
        thinkingSupported = thinkingSupported,
        origin = ModelOrigin.GATEWAY,
    )

    @Test
    fun `the thinking chip follows the switch alone`() {
        assertTrue(
            "a switched-on row shows the chip even when the legacy bit is false",
            ModelCatalogUi.THINKING_CHIP in ModelCatalogUi.chips(entry(thinkingEnabled = true, thinkingSupported = false), provider()),
        )
        assertFalse(
            "the legacy bit alone must not show the chip",
            ModelCatalogUi.THINKING_CHIP in ModelCatalogUi.chips(entry(thinkingEnabled = false, thinkingSupported = true), provider()),
        )
    }
}
