package com.sheen.rokid.phone.storage

import com.sheen.rokid.contracts.ContractError
import com.sheen.rokid.phone.model.catalog.ModelCatalog
import com.sheen.rokid.phone.model.catalog.ModelEntry
import com.sheen.rokid.phone.model.catalog.ModelField
import com.sheen.rokid.phone.model.catalog.ModelOrigin
import com.sheen.rokid.phone.model.catalog.Provider
import com.sheen.rokid.plugin.CapabilityVerification
import com.sheen.rokid.plugin.InputModality
import com.sheen.rokid.plugin.OutputModality
import com.sheen.rokid.plugin.ThinkingEffort
import com.sheen.rokid.plugin.VerificationState
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The encrypted `model-catalog` blob is written by hand with the JSON tree API (0.52 design §5.3). These tests pin the
 * v2 wire shape, the fail-closed reader and the rule that no API key ever reaches the persisted document.
 */
class ModelCatalogJsonTest {
    private class Blobs : EncryptedBlobStore {
        val values = linkedMapOf<String, ByteArray>()
        override fun read(id: String): ByteArray? = values[id]?.copyOf()
        override fun write(id: String, value: ByteArray) {
            values[id] = value.copyOf()
        }
        override fun delete(id: String) {
            values.remove(id)?.fill(0)
        }
    }

    private fun entry(
        id: String = "deepseek-flash",
        displayName: String = "DeepSeek Flash",
        overrides: Set<ModelField> = emptySet(),
        thinkingEnabled: Boolean = false,
        thinkingEffort: ThinkingEffort = ThinkingEffort.LOW,
        origin: ModelOrigin = ModelOrigin.GATEWAY,
        verifications: List<CapabilityVerification> = emptyList(),
    ) = ModelEntry(
        id = id,
        displayName = displayName,
        contextWindowTokens = 131_072,
        maxOutputTokens = 8_192,
        inputs = setOf(InputModality.TEXT, InputModality.IMAGE),
        outputs = setOf(OutputModality.TEXT),
        thinkingEnabled = thinkingEnabled,
        thinkingEffort = thinkingEffort,
        thinkingSupported = true,
        origin = origin,
        overrides = overrides,
        lastSeenAtEpochMillis = 1_700_000_000_000,
        verifications = verifications,
    )

    private fun provider(
        id: String = "11111111-1111-1111-1111-111111111111",
        name: String = "DeepSeek",
        secret: String? = "credential-22222222-2222-2222-2222-222222222222",
        revision: Long = 3,
        models: List<ModelEntry> = listOf(entry()),
    ) = Provider(
        id = id,
        name = name,
        baseUrl = "https://api.deepseek.com/v1",
        secretHandleId = secret,
        revision = revision,
        lastSyncedAtEpochMillis = 1_700_000_100_000,
        models = models,
    )

    @Test fun `v2 round trips providers models overrides thinking and selections`() {
        val original = ModelCatalog(
            activeProviderId = "11111111-1111-1111-1111-111111111111",
            selectedModelId = "deepseek-flash",
            providers = listOf(
                provider(
                    models = listOf(
                        entry(
                            overrides = setOf(ModelField.DISPLAY_NAME, ModelField.INPUTS),
                            verifications = listOf(
                                CapabilityVerification(3, "TEXT_REQUEST", VerificationState.Verified, 1_700_000_200_000),
                            ),
                        ),
                        entry(
                            id = "deepseek-v4-pro",
                            displayName = "DeepSeek V4 Pro",
                            thinkingEnabled = true,
                            thinkingEffort = ThinkingEffort.HIGH,
                        ),
                    ),
                ),
                provider(
                    id = "33333333-3333-3333-3333-333333333333",
                    name = "Local gateway",
                    secret = null,
                    revision = 1,
                    models = listOf(entry(id = "manual-model", origin = ModelOrigin.MANUAL)),
                ),
            ),
            lastSelectedModelIdByProvider = mapOf(
                "11111111-1111-1111-1111-111111111111" to "deepseek-flash",
                "33333333-3333-3333-3333-333333333333" to "manual-model",
            ),
        )

        val json = ModelCatalogJson.encode(original)

        assertEquals(2, json.getValue("version").jsonPrimitive.int)
        assertEquals(
            setOf("version", "activeProviderId", "selectedModelId", "lastSelectedModelIdByProvider", "providers"),
            json.keys,
        )
        assertEquals(original, ModelCatalogJson.decode(json))

        val encodedProvider = json.getValue("providers").jsonArray[0].jsonObject
        assertEquals(
            setOf(
                "id", "name", "baseUrl", "protocolId", "timeoutMillis", "secretRef", "revision", "lastSyncedAt",
                "models",
            ),
            encodedProvider.keys,
        )
        assertEquals(
            "credential-22222222-2222-2222-2222-222222222222",
            encodedProvider.getValue("secretRef").jsonPrimitive.content,
        )
        val encodedModel = encodedProvider.getValue("models").jsonArray[0].jsonObject
        assertEquals(
            setOf(
                "id", "displayName", "window", "output", "inputs", "outputs", "thinking", "thinkingSupported",
                "origin", "overrides", "missing", "lastSeenAt", "verifications",
            ),
            encodedModel.keys,
        )
        assertEquals(setOf("enabled", "effort"), encodedModel.getValue("thinking").jsonObject.keys)
    }

    @Test fun `a selection absent from its provider models is dropped on decode`() {
        // I2: a stale id must not survive a reload and resurrect a deleted model on the next save/refresh.
        val encoded = ModelCatalogJson.encode(
            ModelCatalog(
                activeProviderId = "11111111-1111-1111-1111-111111111111",
                selectedModelId = "deleted-model",
                providers = listOf(provider()),
                lastSelectedModelIdByProvider = mapOf(
                    "11111111-1111-1111-1111-111111111111" to "deleted-model",
                    "33333333-3333-3333-3333-333333333333" to "also-gone",
                ),
            ),
        )

        val decoded = ModelCatalogJson.decode(encoded)

        assertNull("a selected id that is not in the active provider's models is not restored", decoded.selectedModelId)
        assertFalse(decoded.lastSelectedModelIdByProvider.containsKey("11111111-1111-1111-1111-111111111111"))
        assertFalse("entries for providers that no longer exist are dropped too",
            decoded.lastSelectedModelIdByProvider.containsKey("33333333-3333-3333-3333-333333333333"))
    }

    @Test fun `a v1 or wrong version blob is refused without being rewritten`() {
        val blobs = Blobs()
        for (version in listOf(1, 3)) {
            val stored = """{"version":$version,"profiles":{},"selections":{}}""".toByteArray()
            blobs.write(ModelCatalogJson.BLOB_ID, stored.copyOf())

            val error = assertThrows(ContractError.ConfigurationRejected::class.java) {
                ModelCatalogJson.read(blobs)
            }

            assertEquals("Storage unavailable; reconfigure", error.message)
            assertArrayEquals(stored, blobs.read(ModelCatalogJson.BLOB_ID))
        }
    }

    @Test fun `unknown enums and missing required keys are refused safely`() {
        val valid = ModelCatalogJson.encode(ModelCatalog(providers = listOf(provider())))
        val providerJson = valid.getValue("providers").jsonArray[0].jsonObject
        val modelJson = providerJson.getValue("models").jsonArray[0].jsonObject

        fun withProvider(value: JsonObject) = JsonObject(valid + ("providers" to JsonArray(listOf(value))))
        fun withModel(value: JsonObject) = JsonObject(providerJson + ("models" to JsonArray(listOf(value))))

        val cases = listOf(
            "missing provider name" to withProvider(JsonObject(providerJson - "name")),
            "missing model window" to withProvider(withModel(JsonObject(modelJson - "window"))),
            "unknown origin" to withProvider(withModel(JsonObject(modelJson + ("origin" to JsonPrimitive("FUTURE"))))),
            "unknown thinking effort" to withProvider(
                withModel(
                    JsonObject(
                        modelJson + (
                            "thinking" to buildJsonObject {
                                put("enabled", true)
                                put("effort", "TURBO")
                            }
                            ),
                    ),
                ),
            ),
            "missing providers" to JsonObject(valid - "providers"),
        )

        cases.forEach { (label, json) ->
            val error = assertThrows(label, ContractError.ConfigurationRejected::class.java) {
                ModelCatalogJson.decode(json)
            }
            assertEquals(label, "Storage unavailable; reconfigure", error.message)
        }
    }

    @Test fun `the encoded json never contains an api key`() {
        val json = ModelCatalogJson.encode(ModelCatalog(providers = listOf(provider())))
        val text = json.toString()

        assertFalse(text.contains("apiKey", ignoreCase = true))
        assertFalse(text.contains("sk-"))
        assertTrue(text.contains("\"secretRef\":\"credential-22222222-2222-2222-2222-222222222222\""))

        val encodedProvider = json.getValue("providers").jsonArray[0].jsonObject
        assertEquals(
            "credential-22222222-2222-2222-2222-222222222222",
            encodedProvider.getValue("secretRef").jsonPrimitive.content,
        )
        assertEquals(
            0,
            encodedProvider.keys.count { it.contains("secret", ignoreCase = true) && it != "secretRef" },
        )
    }
}
