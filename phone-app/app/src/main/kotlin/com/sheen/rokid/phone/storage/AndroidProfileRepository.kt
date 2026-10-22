package com.sheen.rokid.phone.storage
import kotlinx.serialization.json.JsonObject
import com.sheen.rokid.contracts.ContractError
import com.sheen.rokid.plugin.*
import com.sheen.rokid.storage.ProfileRepository
import kotlinx.serialization.json.*
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

class AndroidProfileRepository(private val blobs: EncryptedBlobStore, private val vault: KeystoreVault) : ProfileRepository {
    private val lock = Any()
    private data class Manifest(val highWater: Long, val profiles: Map<String, String>, val selections: Map<String, String>)
    private class Subscription<T>(val callback: T) { val active = AtomicBoolean(true) }
    private val changes = linkedSetOf<Subscription<(String) -> Unit>>()
    private val errors = linkedSetOf<Subscription<(String, Long) -> Unit>>()
    private val failedEvidence = mutableSetOf<Pair<String, Long>>()
    private var recovered = false
    init { vault.authorize { handle -> list().any { it.secret.id == handle } } }

    private fun manifest(): Manifest {
        val json = readJson(blobs, "profiles-manifest")
        val result = if (json == null) Manifest(0, emptyMap(), emptyMap()) else safely {
            require(json.int("version") == 1)
            val highWater = json.long("highWater"); require(highWater >= 0)
            Manifest(highWater, json["profiles"]!!.jsonObject.strings(), json["selections"]!!.jsonObject.strings())
        }
        if (!recovered) {
            safely {
                val keep = result.profiles.values.toMutableSet()
                result.profiles.forEach { (id, ref) ->
                    val profile = load(id, ref); require(profile.revision <= result.highWater)
                    keep += profile.secret.id
                }
                keep += vault.directHandles()
                (blobs as? ProfileBlobRecovery)?.pruneProfilePayloads(keep)
                recovered = true
            }
        }
        return result
    }
    private fun publish(manifest: Manifest) = writeJson(blobs, "profiles-manifest", buildJsonObject {
        put("version", 1); put("highWater", manifest.highWater)
        put("profiles", buildJsonObject { manifest.profiles.forEach { (id, ref) -> put(id, ref) } })
        put("selections", buildJsonObject { manifest.selections.forEach { (id, ref) -> put(id, ref) } })
    })
    private fun load(id: String, path: String): ModelProfile = safely {
        val profile = decodeProfile(readJson(blobs, path) ?: unavailable())
        require(profile.id == id)
        if ((id to profile.revision) in failedEvidence) profile.copy(verifications = emptyList()) else profile
    }
    override fun list(): List<ModelProfile> = synchronized(lock) { manifest().profiles.map { (id, path) -> load(id, path) } }
    override fun get(id: String): ModelProfile? = synchronized(lock) { manifest().profiles[id]?.let { load(id, it) } }
    override fun save(profile: ModelProfile): ModelProfile = mutate(profile, null, false)
    fun saveWithSecret(profile: ModelProfile, secret: CharArray?): ModelProfile = mutate(profile, secret, true)

    private fun mutate(profile: ModelProfile, secret: CharArray?, coordinated: Boolean): ModelProfile {
        var staged: SecretHandle? = null; var payload: String? = null; var committed = false
        val saved: ModelProfile; val old: ModelProfile?; val oldPath: String?; val listeners: List<Subscription<(String) -> Unit>>
        try {
            synchronized(lock) {
                val previous = manifest(); oldPath = previous.profiles[profile.id]; old = oldPath?.let { load(profile.id, it) }
                if (coordinated && secret == null && old == null) unavailable()
                val handle = when {
                    secret != null -> vault.stage(secret).also { staged = it }
                    coordinated -> requireNotNull(old).secret
                    else -> profile.secret.also { vault.withSecret(it) {} }
                }
                val revision = Math.addExact(previous.highWater, 1)
                saved = profile.copy(revision = revision, secret = handle, inputs = profile.inputs.toSet(), outputs = profile.outputs.toSet(), verifications = emptyList())
                payload = "profile-${UUID.randomUUID()}"
                writeJson(blobs, payload!!, encodeProfile(saved))
                // Immutable payloads become authoritative only at this atomic manifest replacement.
                publish(previous.copy(highWater = revision, profiles = previous.profiles + (profile.id to payload!!)))
                committed = true; failedEvidence.removeAll { it.first == profile.id }; listeners = changes.toList()
            }
        } catch (error: Exception) {
            if (!committed) { staged?.let { runCatching { vault.revoke(it) } }; payload?.let { runCatching { blobs.delete(it) } } }
            if (error is ContractError.ConfigurationRejected) throw error
            unavailable()
        } finally { secret?.fill('\u0000') }
        oldPath?.let { runCatching { blobs.delete(it) } }
        try { if (old != null && old.secret.id != saved.secret.id) vault.revoke(old.secret) }
        finally { notifyChanges(profile.id, listeners) }
        return saved
    }
    override fun delete(id: String) {
        val old: ModelProfile; val path: String; val listeners: List<Subscription<(String) -> Unit>>
        synchronized(lock) {
            val previous = manifest(); path = previous.profiles[id] ?: return
            old = load(id, path)
            publish(previous.copy(profiles = previous.profiles - id, selections = previous.selections.filterValues { it != id }))
            failedEvidence.removeAll { it.first == id }; listeners = changes.toList()
        }
        runCatching { blobs.delete(path) }
        try { vault.revoke(old.secret) } finally { notifyChanges(id, listeners) }
    }
    override fun verify(id: String, result: CapabilityVerification) {
        var failure: Exception? = null; var deliveries = emptyList<Subscription<(String, Long) -> Unit>>()
        synchronized(lock) {
            var path: String? = null
            try {
                val previous = manifest(); val oldPath = previous.profiles[id] ?: return
                val old = load(id, oldPath); if (old.revision != result.profileRevision) return
                path = "profile-${UUID.randomUUID()}"
                writeJson(blobs, path, encodeProfile(old.copy(verifications = old.verifications + result)))
                publish(previous.copy(profiles = previous.profiles + (id to path)))
                failedEvidence.remove(id to result.profileRevision); runCatching { blobs.delete(oldPath) }
            } catch (error: Exception) {
                path?.let { runCatching { blobs.delete(it) } }; failedEvidence += id to result.profileRevision
                failure = error; deliveries = errors.toList()
            }
        }
        if (failure != null) {
            deliveries.forEach { if (it.active.get()) runCatching { it.callback(id, result.profileRevision) } }
            unavailable()
        }
    }
    override fun listenChanges(listener: (String) -> Unit): CancellationHandle = synchronized(lock) {
        val entry = Subscription(listener); changes += entry
        object : CancellationHandle { override fun cancel() { entry.active.set(false); synchronized(lock) { changes -= entry } } }
    }
    private fun notifyChanges(id: String, listeners: List<Subscription<(String) -> Unit>>) {
        var failure: Throwable? = null
        listeners.forEach { entry -> if (entry.active.get()) try { entry.callback(id) } catch (error: Throwable) {
            if (failure == null) failure = error else if (failure !== error) failure!!.addSuppressed(error)
        } }
        failure?.let { throw it }
    }
    fun select(pluginId: String, profileId: String) = synchronized(lock) {
        require(pluginId in setOf("smart-assistant", "interview-assistant", "p2-diagnostic"))
        val previous = manifest(); require(profileId in previous.profiles)
        publish(previous.copy(selections = previous.selections + (pluginId to profileId)))
    }
    fun selections(): Map<String, String> = synchronized(lock) { manifest().selections.toMap() }
    fun listenStorageErrors(listener: (String, Long) -> Unit): CancellationHandle = synchronized(lock) {
        val entry = Subscription(listener); errors += entry
        object : CancellationHandle { override fun cancel() { entry.active.set(false); synchronized(lock) { errors -= entry } } }
    }
}

internal inline fun <T> safely(block: () -> T): T = try { block() } catch (_: Exception) { unavailable() }
internal fun readJson(blobs: EncryptedBlobStore, id: String): JsonObject? {
    val bytes = safely { blobs.read(id) } ?: return null
    return try { safely { Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject } } finally { bytes.fill(0) }
}
internal fun writeJson(blobs: EncryptedBlobStore, id: String, value: JsonObject) {
    val bytes = value.toString().toByteArray(Charsets.UTF_8)
    try { safely { blobs.write(id, bytes) } } finally { bytes.fill(0) }
}
private fun JsonObject.strings() = mapValues { it.value.jsonPrimitive.content }
private fun JsonObject.text(key: String) = getValue(key).jsonPrimitive.content
private fun JsonObject.int(key: String) = getValue(key).jsonPrimitive.int
private fun JsonObject.long(key: String) = getValue(key).jsonPrimitive.long
private fun encodeProfile(p: ModelProfile) = buildJsonObject {
    put("version", 1); put("id", p.id); put("revision", p.revision); put("name", p.name); put("service", p.service)
    put("baseUrl", p.baseUrl); put("protocolId", p.protocolId); put("model", p.model); put("secret", p.secret.id)
    put("window", p.usableWindowTokens); put("budget", p.inputBudgetTokens); put("output", p.maxOutputTokens); put("safety", p.safetyTokens); put("timeout", p.timeoutMillis)
    put("inputs", buildJsonArray { p.inputs.forEach { add(it.name) } }); put("outputs", buildJsonArray { p.outputs.forEach { add(it.name) } })
    put("verifications", buildJsonArray { p.verifications.forEach { v -> add(buildJsonObject {
        put("revision", v.profileRevision); put("operation", v.operation); put("state", v.state.name); put("time", v.testedAtEpochMillis)
    }) } })
}
private fun decodeProfile(j: JsonObject): ModelProfile {
    require(j.int("version") == 1)
    return ModelProfile(j.text("id"), j.long("revision"), j.text("name"), j.text("service"), j.text("baseUrl"), j.text("protocolId"), j.text("model"), opaqueSecret(j.text("secret")),
        j.int("window"), j.int("budget"), j.int("output"), j.int("safety"), j.long("timeout"),
        j["inputs"]!!.jsonArray.map { InputModality.valueOf(it.jsonPrimitive.content) }.toSet(),
        j["outputs"]!!.jsonArray.map { OutputModality.valueOf(it.jsonPrimitive.content) }.toSet(),
        j["verifications"]!!.jsonArray.map { it.jsonObject.let { v -> CapabilityVerification(v.long("revision"), v.text("operation"), VerificationState.valueOf(v.text("state")), v.long("time")) } })
}
