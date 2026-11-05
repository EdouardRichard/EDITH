package com.sheen.rokid.phone.model.catalog

import com.sheen.rokid.contracts.ContractError
import com.sheen.rokid.plugin.CapabilityVerification
import com.sheen.rokid.plugin.InputModality
import com.sheen.rokid.plugin.OutputModality
import com.sheen.rokid.plugin.ThinkingEffort
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelCatalogMergeTest {
    private val now = 1_700_000_000_000L

    private fun entry(
        id: String = "m-1",
        displayName: String = id,
        window: Int = 131_072,
        output: Int = 8_192,
        inputs: Set<InputModality> = setOf(InputModality.TEXT),
        origin: ModelOrigin = ModelOrigin.GATEWAY,
        overrides: Set<ModelField> = emptySet(),
        missing: Boolean = false,
        lastSeenAt: Long? = null,
        thinkingSupported: Boolean = false,
        thinkingEnabled: Boolean = false,
        thinkingEffort: ThinkingEffort = ThinkingEffort.LOW,
        verifications: List<CapabilityVerification> = emptyList(),
    ): ModelEntry = ModelEntry(
        id = id,
        displayName = displayName,
        contextWindowTokens = window,
        maxOutputTokens = output,
        inputs = inputs,
        outputs = setOf(OutputModality.TEXT),
        thinkingEnabled = thinkingEnabled,
        thinkingEffort = thinkingEffort,
        thinkingSupported = thinkingSupported,
        origin = origin,
        overrides = overrides,
        missing = missing,
        lastSeenAtEpochMillis = lastSeenAt,
        verifications = verifications,
    )

    private fun provider(vararg models: ModelEntry): Provider = Provider(
        id = "p-1",
        name = "DeepSeek",
        baseUrl = "https://api.deepseek.com/v1",
        secretHandleId = "secret-1",
        revision = 3,
        models = models.toList(),
    )

    private fun catalog(vararg providers: Provider): ModelCatalog = ModelCatalog(
        activeProviderId = providers.firstOrNull()?.id,
        providers = providers.toList(),
    )

    private fun applied(
        gateway: List<GatewayModel>,
        previous: ModelCatalog = catalog(provider()),
    ): CatalogRefresh = ModelCatalogMerge.applyRefresh(previous, "p-1", gateway, now)

    @Test fun `a new gateway model gets gateway metadata before the known table and defaults`() {
        val gateway = listOf(GatewayModel("gw-1", contextWindowTokens = 65_536, maxOutputTokens = 4_096))
        val entry = applied(gateway).catalog.providers.single().models.single()
        assertEquals(65_536, entry.contextWindowTokens)
        assertEquals(4_096, entry.maxOutputTokens)
        assertEquals(ModelOrigin.GATEWAY, entry.origin)
        assertEquals(emptySet<ModelField>(), entry.overrides)
        assertEquals(now, entry.lastSeenAtEpochMillis)

        // Missing fields fall through to the built-in table, then to the conservative default.
        val known = applied(listOf(GatewayModel("deepseek-v4-pro")))
            .catalog.providers.single().models.single()
        assertEquals(1_048_576, known.contextWindowTokens)
        assertEquals(393_216, known.maxOutputTokens)
        assertEquals(setOf(InputModality.TEXT), known.inputs)

        val unknown = applied(listOf(GatewayModel("an-unknown-model")))
            .catalog.providers.single().models.single()
        assertEquals(131_072, unknown.contextWindowTokens)
        assertEquals(8_192, unknown.maxOutputTokens)
        assertEquals(setOf(InputModality.TEXT, InputModality.IMAGE), unknown.inputs)
    }

    @Test fun `refresh updates only fields the user did not override`() {
        val previous = catalog(provider(entry(
            window = 200_000,
            output = 4_000,
            overrides = setOf(ModelField.CONTEXT_WINDOW_TOKENS),
            lastSeenAt = 1,
        )))
        val refresh = applied(
            listOf(GatewayModel("m-1", contextWindowTokens = 65_536, maxOutputTokens = 4_096)),
            previous,
        )
        val refreshed = refresh.catalog.providers.single().models.single()
        assertEquals(200_000, refreshed.contextWindowTokens) // user override kept
        assertEquals(4_096, refreshed.maxOutputTokens) // untouched field updated
        assertEquals(1, refresh.counts.updated)
        assertEquals(0, refresh.counts.unchanged)
        assertEquals(now, refreshed.lastSeenAtEpochMillis)
    }

    @Test fun `manual models are never overwritten and only lose the missing flag`() {
        val previousEntry = entry(
            origin = ModelOrigin.MANUAL,
            missing = true,
            window = 123_456,
            output = 4_096,
            lastSeenAt = 5,
        )
        val previous = catalog(provider(previousEntry))
        val refreshed = applied(
            listOf(GatewayModel("m-1", contextWindowTokens = 65_536, maxOutputTokens = 4_096)),
            previous,
        ).catalog.providers.single().models.single()
        assertEquals(previousEntry.copy(missing = false), refreshed)
    }

    @Test fun `a manual model the gateway omits stays available and is never marked missing`() {
        // I3: a manual row was entered by the user, not fetched; a gateway that does not know it has not "removed" it.
        val manual = entry(
            id = "m-manual",
            origin = ModelOrigin.MANUAL,
            window = 123_456,
            output = 4_096,
            lastSeenAt = 7,
        )
        val refresh = applied(listOf(GatewayModel("gw-1")), catalog(provider(manual)))
        val models = refresh.catalog.providers.single().models

        assertEquals(listOf("gw-1", "m-manual"), models.map { it.id })
        assertFalse("a manual row must never be marked 网关已移除", models.first { it.id == "m-manual" }.missing)
        assertEquals("manual rows are not gateway-removed counts", 0, refresh.counts.missing)
        assertEquals(123_456, models.first { it.id == "m-manual" }.contextWindowTokens)
    }

    @Test fun `a missing gateway model is flagged and kept`() {
        val previous = catalog(provider(entry(lastSeenAt = 9)))
        val refresh = applied(emptyList(), previous)
        val refreshedProvider = refresh.catalog.providers.single()
        assertTrue(refreshedProvider.models.single().missing)
        assertEquals(1, refresh.counts.missing)
        assertEquals(1, refreshedProvider.models.size) // not deleted
        assertEquals(9L, refreshedProvider.models.single().lastSeenAtEpochMillis)
    }

    @Test fun `refresh clamps an output that would cross the safety reserve`() {
        val refreshed = merge(window = 10_000, output = 4_000, gatewayOutput = 20_000)
        assertEquals(10_000 - 256 - 1, refreshed.maxOutputTokens)
    }

    private fun merge(window: Int, output: Int, gatewayOutput: Int): ModelEntry {
        val previous = catalog(provider(entry(window = window, output = output)))
        return applied(listOf(GatewayModel("m-1", maxOutputTokens = gatewayOutput)), previous)
            .catalog.providers.single().models.single()
    }

    @Test fun `restore defaults clears one model overrides and reapplies metadata`() {
        val previous = catalog(provider(entry(
            window = 200_000,
            output = 4_000,
            overrides = setOf(ModelField.CONTEXT_WINDOW_TOKENS, ModelField.MAX_OUTPUT_TOKENS),
            lastSeenAt = 3,
        )))
        val restored = ModelCatalogMerge.restoreDefaults(
            previous,
            "p-1",
            "m-1",
            GatewayModel("m-1", contextWindowTokens = 65_536, maxOutputTokens = 4_096),
        ).providers.single().models.single()
        assertEquals(emptySet<ModelField>(), restored.overrides)
        assertEquals(65_536, restored.contextWindowTokens)
        assertEquals(4_096, restored.maxOutputTokens)
        assertEquals(3L, restored.lastSeenAtEpochMillis)
    }

    @Test fun `restore defaults rejects metadata that cannot fit the safety reserve`() {
        val previous = catalog(provider(entry(window = 200_000, output = 4_000)))
        assertThrows(ContractError.ConfigurationRejected::class.java) {
            ModelCatalogMerge.restoreDefaults(
                previous,
                "p-1",
                "m-1",
                GatewayModel("m-1", contextWindowTokens = 1_000, maxOutputTokens = 2_000),
            )
        }
    }

    @Test fun `a deepseek id is thinking capable on add refresh and restore`() {
        // 0.53 T8: the family rule, not only the gateway metadata, decides the capability.
        val added = applied(listOf(GatewayModel("deepseek-chat"))).catalog.providers.single().models.single()
        assertTrue("a DeepSeek-family id is derived as thinking-capable", added.thinkingSupported)
        assertFalse("the switch itself stays off", added.thinkingEnabled)

        val cased = applied(listOf(GatewayModel("DeepSeek-Chat"))).catalog.providers.single().models.single()
        assertTrue("the family rule is case-insensitive", cased.thinkingSupported)

        val other = applied(listOf(GatewayModel("an-unknown-model"))).catalog.providers.single().models.single()
        assertFalse("an unrelated id is not thinking-capable", other.thinkingSupported)

        // A legacy row stored as unsupported is repaired by the next refresh.
        val legacy = catalog(provider(entry(id = "deepseek-chat", thinkingSupported = false, lastSeenAt = 1)))
        val refreshed = applied(listOf(GatewayModel("deepseek-chat")), legacy).catalog.providers.single().models.single()
        assertTrue(refreshed.thinkingSupported)

        // Restore defaults reapplies the same derivation without gateway metadata.
        val restored = ModelCatalogMerge.restoreDefaults(legacy, "p-1", "deepseek-chat", GatewayModel("deepseek-chat"))
            .providers.single().models.single()
        assertTrue(restored.thinkingSupported)

        // An explicitly marked non-DeepSeek model keeps its manual capability through a refresh.
        val manual = catalog(
            provider(entry(id = "custom-vl", thinkingSupported = true, origin = ModelOrigin.MANUAL, lastSeenAt = 1)),
        )
        val kept = applied(listOf(GatewayModel("custom-vl")), manual).catalog.providers.single().models.single()
        assertTrue(kept.thinkingSupported)
    }

    @Test fun `blank duplicate and control-character gateway ids are skipped`() {
        val refresh = applied(listOf(
            GatewayModel(""),
            GatewayModel("m\n1", contextWindowTokens = 65_536),
            GatewayModel("m-1", contextWindowTokens = 32_768),
            GatewayModel("m-1", contextWindowTokens = 16_384),
        ))
        val models = refresh.catalog.providers.single().models
        assertEquals(listOf("m-1"), models.map { it.id })
        assertEquals(32_768, models.single().contextWindowTokens) // first valid occurrence wins
        assertEquals(1, refresh.counts.added)
        assertEquals(0, refresh.counts.updated)
        assertEquals(0, refresh.counts.unchanged)
        assertEquals(0, refresh.counts.missing)
    }
}
