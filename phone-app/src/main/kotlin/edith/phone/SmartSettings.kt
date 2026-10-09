package edith.phone

import edith.phone.storage.EncryptedBlobStore
import edith.phone.storage.readJson
import edith.phone.storage.writeJson
import edith.plugin.ModelProfile
import edith.plugin.PluginConfiguration
import edith.plugin.RiskLevel
import edith.storage.ProfileRepository
import kotlinx.serialization.json.*

/**
 * The persisted configuration of the passive loop (Task 14a item 6).
 *
 * The defaults are the ratified ones, not placeholders: `L1` is the risk ceiling the phase ships with, and the two
 * wake words are the ones the smart plugin itself defaults to. The store is deliberately a **value snapshot** - a
 * settings screen writes it (Task 14b), the composition reads it at assembly time - so no plugin ever observes the
 * store changing underneath it.
 */
data class SmartSettings(
    val wakeWords: List<String> = listOf("伊迪丝", "edith"),
    val maxLevel: String = "L1",
    val whitelist: List<String> = emptyList(),
    val profileId: String? = null,
    val cooldownMillis: Long = 3000L,
    /**
     * P3.2: the proactive cooldown and sensitivity (spec §16.1#4).
     *
     * The cooldown is the same kind of number as [cooldownMillis] (the wake path's), but it bounds a different thing:
     * how often the assistant may interrupt without being asked. The sensitivity picks one of the plugin's three
     * threshold pairs; the string is normalised through [effectiveProactiveSensitivity] before it is sent, because the
     * plugin refuses a level it does not know.
     */
    val proactiveCooldownMillis: Long = 60_000L,
    val proactiveSensitivity: String = "MEDIUM",
) {
    /**
     * The plugin's own cooldown parameter.
     *
     * A non-positive stored value would make the detector re-report the same word on every frame, so it is clamped to
     * zero: "fire on every match" is a decision the detector already offers, while a negative duration is not a
     * configuration at all.
     */
    val cooldownNanos: Long get() = (if (cooldownMillis > 0) cooldownMillis else 0L) * 1_000_000L

    /**
     * The profile this configuration should name: the stored choice while it still exists, otherwise the first
     * available profile.
     *
     * A profile can be deleted while the App is closed, and the plugin's configuration contract requires a non-blank
     * `profileId`, so falling back rather than refusing is what keeps the passive loop arm-able after a deletion. With
     * no profiles at all the answer is null, and the caller substitutes the contract-valid placeholder.
     */
    fun resolveProfileId(profiles: ProfileRepository): String? {
        // The repository can refuse outright: `AndroidProfileRepository.list()` throws
        // `ContractError.ConfigurationRejected("Storage unavailable; reconfigure")` when the keystore cannot be
        // opened. The composition reads the settings while it builds the plugin, so letting that propagate would take
        // the entire composition - and with it the passive loop - down over a fallback the plugin does not need.
        // Keeping the stored choice is the honest answer: the profile is still the user's selection, and
        // `profileLookup` finds nothing for it until storage returns.
        val available = runCatching { profiles.list() }.getOrNull() ?: return profileId
        profileId?.let { chosen -> if (available.any { it.id == chosen }) return chosen }
        return available.firstOrNull()?.id
    }

    /**
     * The values the smart plugin parses, with both profile fields filled in by [profile].
     *
     * `profileRevision` is the real revision so a profile swap is visible to the plugin, and `0` when the profile is
     * absent because the revision must still be a non-negative integer. The whitelist is sent even when empty: the
     * plugin's known key set is closed, so leaving a key out is allowed, but sending it pins "no grants" explicitly.
     */
    fun pluginValues(profile: ModelProfile?): Map<String, String> = mapOf(
        "profileId" to (profile?.id ?: profileId ?: UNRESOLVED_PROFILE),
        "profileRevision" to (profile?.revision ?: 0L).toString(),
        "wakeWords" to wakeWords.joinToString(","),
        "maxLevel" to effectiveMaxLevel(),
        "whitelist" to whitelist.joinToString(","),
        "proactiveCooldownMillis" to proactiveCooldownMillis.toString(),
        "proactiveSensitivity" to effectiveProactiveSensitivity(),
    )

    /** The plugin's `PluginConfiguration`, at the schema version the plugin declares. */
    fun pluginConfiguration(profile: ModelProfile?): PluginConfiguration =
        PluginConfiguration(SCHEMA_VERSION, pluginValues(profile))

    /**
     * The risk ceiling as the plugin's own enum name, or the shipped default when the stored text is not a level.
     *
     * Normalised here rather than sent raw so the record's own `maxLevel` has one meaning; the plugin would refuse an
     * unknown level and take the whole activation down with it.
     */
    fun effectiveMaxLevel(): String =
        RiskLevel.entries.firstOrNull { it.name.equals(maxLevel.trim(), ignoreCase = true) }?.name
            ?: SmartSettings().maxLevel

    /**
     * The sensitivity as the plugin's own enum name, or the shipped default when the stored text is not a level.
     *
     * The same normalisation as [effectiveMaxLevel] and for the same reason: the plugin requires one of three exact
     * names and refuses the whole configuration otherwise, so a hand-edited or corrupted blob must not be able to take
     * the activation (and with it the passive loop) down.
     */
    fun effectiveProactiveSensitivity(): String =
        SENSITIVITIES.firstOrNull { it.equals(proactiveSensitivity.trim(), ignoreCase = true) }
            ?: SmartSettings().proactiveSensitivity

    companion object {
        const val SCHEMA_VERSION = 1

        /**
         * The value used when neither a stored nor an available profile exists.
         *
         * Deliberately non-blank and unmistakable: the plugin accepts the shape, `profileLookup` finds nothing, and a
         * turn cannot run - which is the honest outcome for "no model is configured", not a crash.
         */
        const val UNRESOLVED_PROFILE = "__unconfigured__"

        /** The three levels the plugin's own enum names; kept here so the store never sends a fourth. */
        val SENSITIVITIES = listOf("LOW", "MEDIUM", "HIGH")
    }
}

/**
 * The settings store over the App's encrypted blob store.
 *
 * Every failure path returns [SmartSettings] defaults: a missing blob, unreadable bytes, malformed JSON, a wrong
 * version, or a field of the wrong type. That is not leniency - the next activation validates this configuration, so
 * a store that threw would take the loop down, while the defaults keep the app in a state the user can configure.
 */
class SmartSettingsStore(private val blobs: EncryptedBlobStore) {

    fun load(profiles: ProfileRepository? = null): SmartSettings {
        val parsed = safelyRead() ?: SmartSettings()
        val resolved = if (profiles == null) parsed.profileId else parsed.resolveProfileId(profiles)
        return parsed.copy(profileId = resolved)
    }

    fun save(settings: SmartSettings) {
        writeJson(blobs, BLOB_ID, buildJsonObject {
            put("version", VERSION)
            put("wakeWords", buildJsonArray { settings.wakeWords.forEach(::add) })
            put("maxLevel", settings.maxLevel)
            put("whitelist", buildJsonArray { settings.whitelist.forEach(::add) })
            settings.profileId?.let { put("profileId", it) }
            put("cooldownMillis", settings.cooldownMillis)
            put("proactiveCooldownMillis", settings.proactiveCooldownMillis)
            put("proactiveSensitivity", settings.effectiveProactiveSensitivity())
        })
    }

    private fun safelyRead(): SmartSettings? {
        val json = runCatching { readJson(blobs, BLOB_ID) }.getOrNull() ?: return null
        return runCatching {
            require(json["version"]?.jsonPrimitive?.int == VERSION) { "unsupported settings version" }
            SmartSettings(
                wakeWords = strings(json, "wakeWords") ?: SmartSettings().wakeWords,
                maxLevel = json["maxLevel"]?.jsonPrimitive?.content ?: SmartSettings().maxLevel,
                whitelist = strings(json, "whitelist") ?: emptyList(),
                profileId = json["profileId"]?.jsonPrimitive?.contentOrNull,
                cooldownMillis = json["cooldownMillis"]?.jsonPrimitive?.longOrNull ?: SmartSettings().cooldownMillis,
                // Absent in a blob written before P3.2: the field takes its default, exactly like every other
                // missing key, so an old store keeps loading instead of being treated as corrupt.
                proactiveCooldownMillis = json["proactiveCooldownMillis"]?.jsonPrimitive?.longOrNull
                    ?: SmartSettings().proactiveCooldownMillis,
                proactiveSensitivity = json["proactiveSensitivity"]?.jsonPrimitive?.content
                    ?: SmartSettings().proactiveSensitivity,
            )
        }.getOrNull()
    }

    /** A field that is absent takes its default; a field of the wrong shape is a corrupt blob and fails the whole read. */
    private fun strings(json: JsonObject, key: String): List<String>? {
        val element = json[key] ?: return null
        return element.jsonArray.map { it.jsonPrimitive.content }
    }

    companion object {
        const val BLOB_ID = "smart-settings"
        private const val VERSION = 1
    }
}
