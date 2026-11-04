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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * The hand-written `model-catalog` v2 wire format (0.52 design §5.3).
 *
 * The document is stored inside the encrypted blob store; secrets never enter it — only the opaque `secretRef` handle
 * id. Decoding is fail-closed: a missing key, an unknown enum, a wrong version or a value that violates the catalog's
 * invariants is reported as [ContractError.ConfigurationRejected] ("Storage unavailable; reconfigure") and the caller
 * never receives a partially decoded catalog.
 */
object ModelCatalogJson {
    const val BLOB_ID = "model-catalog"
    const val VERSION = 2

    /** The stored catalog, or a fresh empty one when the blob does not exist. */
    fun read(blobs: EncryptedBlobStore): ModelCatalog =
        readJson(blobs, BLOB_ID)?.let(::decode) ?: ModelCatalog()

    /** Atomically replaces the stored catalog document. */
    fun write(blobs: EncryptedBlobStore, catalog: ModelCatalog) =
        writeJson(blobs, BLOB_ID, encode(catalog))

    fun encode(catalog: ModelCatalog): JsonObject = buildJsonObject {
        put("version", VERSION)
        put("activeProviderId", catalog.activeProviderId)
        put("selectedModelId", catalog.selectedModelId)
        put("lastSelectedModelIdByProvider", buildJsonObject {
            catalog.lastSelectedModelIdByProvider.forEach { (providerId, modelId) -> put(providerId, modelId) }
        })
        put("providers", buildJsonArray { catalog.providers.forEach { add(encodeProvider(it)) } })
    }

    fun decode(json: JsonObject): ModelCatalog = try {
        decodeOrThrow(json)
    } catch (error: ContractError) {
        throw error
    } catch (_: Exception) {
        unavailable()
    }

    private fun decodeOrThrow(json: JsonObject): ModelCatalog {
        if (json.getValue("version").jsonPrimitive.int != VERSION) unavailable()
        return ModelCatalog(
            activeProviderId = json.nullableText("activeProviderId"),
            selectedModelId = json.nullableText("selectedModelId"),
            providers = json.getValue("providers").jsonArray.map { decodeProvider(it.jsonObject) },
            lastSelectedModelIdByProvider = json.getValue("lastSelectedModelIdByProvider").jsonObject.mapValues {
                it.value.jsonPrimitive.contentOrNull ?: throw IllegalArgumentException("missing model id")
            },
        )
    }

    private fun encodeProvider(provider: Provider) = buildJsonObject {
        put("id", provider.id)
        put("name", provider.name)
        put("baseUrl", provider.baseUrl)
        put("protocolId", provider.protocolId)
        put("timeoutMillis", provider.timeoutMillis)
        put("secretRef", provider.secretHandleId)
        put("revision", provider.revision)
        put("lastSyncedAt", provider.lastSyncedAtEpochMillis)
        put("models", buildJsonArray { provider.models.forEach { add(encodeModel(it)) } })
    }

    private fun decodeProvider(json: JsonObject) = Provider(
        id = json.requiredText("id"),
        name = json.requiredText("name"),
        baseUrl = json.requiredText("baseUrl"),
        protocolId = json.requiredText("protocolId"),
        timeoutMillis = json.long("timeoutMillis"),
        secretHandleId = json.nullableText("secretRef"),
        revision = json.long("revision"),
        lastSyncedAtEpochMillis = json.nullableLong("lastSyncedAt"),
        models = json.getValue("models").jsonArray.map { decodeModel(it.jsonObject) },
    )

    private fun encodeModel(entry: ModelEntry) = buildJsonObject {
        put("id", entry.id)
        put("displayName", entry.displayName)
        put("window", entry.contextWindowTokens)
        put("output", entry.maxOutputTokens)
        put("inputs", encodeEnumSet(entry.inputs, enumValues<InputModality>()))
        put("outputs", encodeEnumSet(entry.outputs, enumValues<OutputModality>()))
        put("thinking", buildJsonObject {
            put("enabled", entry.thinkingEnabled)
            put("effort", entry.thinkingEffort.name)
        })
        put("thinkingSupported", entry.thinkingSupported)
        put("origin", entry.origin.name)
        put("overrides", encodeEnumSet(entry.overrides, enumValues<ModelField>()))
        put("missing", entry.missing)
        put("lastSeenAt", entry.lastSeenAtEpochMillis)
        put("verifications", buildJsonArray { entry.verifications.forEach { add(encodeVerification(it)) } })
    }

    private fun decodeModel(json: JsonObject): ModelEntry {
        val thinking = json.getValue("thinking").jsonObject
        return ModelEntry(
            id = json.requiredText("id"),
            displayName = json.requiredText("displayName"),
            contextWindowTokens = json.int("window"),
            maxOutputTokens = json.int("output"),
            inputs = json.decodeEnumSet<InputModality>("inputs"),
            outputs = json.decodeEnumSet<OutputModality>("outputs"),
            thinkingEnabled = thinking.boolean("enabled"),
            thinkingEffort = ThinkingEffort.valueOf(thinking.requiredText("effort")),
            thinkingSupported = json.boolean("thinkingSupported"),
            origin = ModelOrigin.valueOf(json.requiredText("origin")),
            overrides = json.decodeEnumSet<ModelField>("overrides"),
            missing = json.boolean("missing"),
            lastSeenAtEpochMillis = json.nullableLong("lastSeenAt"),
            verifications = json.getValue("verifications").jsonArray.map { decodeVerification(it.jsonObject) },
        )
    }

    private fun encodeVerification(verification: CapabilityVerification) = buildJsonObject {
        put("revision", verification.profileRevision)
        put("operation", verification.operation)
        put("state", verification.state.name)
        put("time", verification.testedAtEpochMillis)
    }

    private fun decodeVerification(json: JsonObject) = CapabilityVerification(
        profileRevision = json.long("revision"),
        operation = json.requiredText("operation"),
        state = VerificationState.valueOf(json.requiredText("state")),
        testedAtEpochMillis = json.long("time"),
    )

    private fun <T : Enum<T>> encodeEnumSet(values: Set<T>, order: Array<T>) =
        buildJsonArray { order.filter { it in values }.forEach { add(JsonPrimitive(it.name)) } }

    private inline fun <reified T : Enum<T>> JsonObject.decodeEnumSet(key: String): Set<T> =
        getValue(key).jsonArray.map { enumValueOf<T>(it.jsonPrimitive.contentOrNull ?: error("missing enum")) }.toSet()

    private fun JsonObject.requiredText(key: String): String =
        getValue(key).jsonPrimitive.contentOrNull ?: throw IllegalArgumentException("missing $key")

    private fun JsonObject.nullableText(key: String): String? =
        getValue(key).jsonPrimitive.contentOrNull

    private fun JsonObject.int(key: String): Int = getValue(key).jsonPrimitive.int

    private fun JsonObject.long(key: String): Long = getValue(key).jsonPrimitive.long

    private fun JsonObject.boolean(key: String): Boolean = getValue(key).jsonPrimitive.boolean

    private fun JsonObject.nullableLong(key: String): Long? = getValue(key).jsonPrimitive.longOrNull
}
