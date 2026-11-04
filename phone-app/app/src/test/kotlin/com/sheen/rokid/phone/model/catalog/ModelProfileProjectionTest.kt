package com.sheen.rokid.phone.model.catalog

import com.sheen.rokid.plugin.InputModality
import com.sheen.rokid.plugin.OutputModality
import com.sheen.rokid.plugin.ThinkingEffort
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelProfileProjectionTest {
    private fun provider(
        revision: Long = 1,
        secret: String? = "secret-1",
    ): Provider = Provider(
        id = "p-1",
        name = "DeepSeek",
        baseUrl = "https://api.deepseek.com/v1",
        secretHandleId = secret,
        revision = revision,
    )

    private fun entry(
        id: String = "m-1",
        window: Int = 131_072,
        output: Int = 8_192,
        thinkingEnabled: Boolean = false,
        thinkingEffort: ThinkingEffort = ThinkingEffort.LOW,
        thinkingSupported: Boolean = true,
    ): ModelEntry = ModelEntry(
        id = id,
        displayName = "Model $id",
        contextWindowTokens = window,
        maxOutputTokens = output,
        inputs = setOf(InputModality.TEXT, InputModality.IMAGE),
        outputs = setOf(OutputModality.TEXT),
        thinkingEnabled = thinkingEnabled,
        thinkingEffort = thinkingEffort,
        thinkingSupported = thinkingSupported,
        origin = ModelOrigin.GATEWAY,
    )

    @Test fun `a projected profile carries the derived id and provider revision`() {
        val p = ModelProfileProjection.toProfile(provider(revision = 7), entry(id = "m-1"))
        assertEquals("p-1:m-1", p.id)
        assertEquals(7L, p.revision)
        assertEquals(setOf(OutputModality.TEXT), p.outputs)
        assertEquals("Model m-1", p.name)
        assertEquals("DeepSeek", p.service)
        assertEquals("https://api.deepseek.com/v1", p.baseUrl)
        assertEquals("m-1", p.model)
        assertEquals("secret-1", p.secret.id)
        assertEquals(setOf(InputModality.TEXT, InputModality.IMAGE), p.inputs)
    }

    @Test fun `a projected profile obeys the budget identity`() {
        val p = ModelProfileProjection.toProfile(provider(), entry(window = 131_072, output = 8_192))
        assertEquals(131_072 - 8_192 - 256, p.inputBudgetTokens)
        assertTrue(p.inputBudgetTokens + p.maxOutputTokens + p.safetyTokens <= p.usableWindowTokens)
        assertEquals(131_072, p.usableWindowTokens)
        assertEquals(8_192, p.maxOutputTokens)
        assertEquals(256, p.safetyTokens)
    }

    @Test fun `profile ids split only on the first colon`() {
        assertEquals("p-1" to "model:with:colons", ModelProfileProjection.parseProfileId("p-1:model:with:colons"))
        assertEquals("p-1:m-1", ModelProfileProjection.profileId("p-1", "m-1"))
        assertNull(ModelProfileProjection.parseProfileId("no-colon"))
        assertNull(ModelProfileProjection.parseProfileId("p-1:"))
        assertNull(ModelProfileProjection.parseProfileId(":m-1"))
        assertNull(ModelProfileProjection.parseProfileId("p-1"))
    }

    @Test fun `thinking settings come from the entry`() {
        val p = ModelProfileProjection.toProfile(
            provider(),
            entry(thinkingEnabled = true, thinkingEffort = ThinkingEffort.MAX),
        )
        assertTrue(p.thinkingEnabled)
        assertEquals(ThinkingEffort.MAX, p.thinkingEffort)
        assertTrue(p.thinkingSupported)
    }

    @Test fun `a projected profile propagates the thinking capability of its model`() {
        // R21: the adapter gate consumes this bit, so a non-DeepSeek entry must project as unsupported.
        assertTrue(ModelProfileProjection.toProfile(provider(), entry(thinkingSupported = true)).thinkingSupported)
        assertFalse(ModelProfileProjection.toProfile(provider(), entry(thinkingSupported = false)).thinkingSupported)
    }

    @Test fun `a catalog projects only its active provider and selected model`() {
        val first = Provider(
            id = "p-1",
            name = "DeepSeek",
            baseUrl = "https://api.deepseek.com/v1",
            secretHandleId = "secret-1",
            models = listOf(entry(id = "m-1")),
        )
        val second = Provider(
            id = "p-2",
            name = "Other",
            baseUrl = "https://other.example.test/v1",
            secretHandleId = "secret-2",
            revision = 4,
            models = listOf(entry(id = "m-2")),
        )
        val catalog = ModelCatalog(
            activeProviderId = "p-2",
            selectedModelId = "m-2",
            providers = listOf(first, second),
            lastSelectedModelIdByProvider = mapOf("p-1" to "m-1"),
        )
        assertEquals("p-2", catalog.activeProvider()?.id)
        assertEquals("m-2", catalog.activeModel()?.id)
        assertEquals("p-2:m-2", catalog.activeProfile()?.id)
        assertEquals(4L, catalog.activeProfile()?.revision)

        assertNull(catalog.copy(activeProviderId = "missing").activeProvider())
        assertNull(catalog.copy(activeProviderId = "missing").activeProfile())
        assertNull(catalog.copy(selectedModelId = null).activeModel())
        assertNull(catalog.copy(selectedModelId = null).activeProfile())
        assertNull(catalog.copy(providers = listOf(second.copy(secretHandleId = null))).activeProfile())
    }
}
