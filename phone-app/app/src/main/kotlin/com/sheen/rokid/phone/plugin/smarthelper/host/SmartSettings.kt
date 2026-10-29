package com.sheen.rokid.phone.plugin.smarthelper.host
import kotlinx.serialization.json.JsonObject
import com.sheen.rokid.phone.storage.unavailable
import com.sheen.rokid.phone.plugin.smarthelper.host.ProactiveArming
import com.sheen.rokid.phone.storage.AndroidProfileRepository

import com.sheen.rokid.phone.storage.EncryptedBlobStore
import com.sheen.rokid.phone.storage.readJson
import com.sheen.rokid.phone.storage.writeJson
import com.sheen.rokid.plugin.ModelProfile
import com.sheen.rokid.plugin.PluginConfiguration
import com.sheen.rokid.plugin.RiskLevel
import com.sheen.rokid.storage.ProfileRepository
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
     *
     * **The sensitivity default was tightened to `LOW` on 2026-10-09** after two ambient windows measured ~1.8
     * admitted triggers per ten minutes against the spec's ceiling of one (`P3.2-FT`): at `MEDIUM` only ten of
     * twenty-two ambient finals were refused as too short, and both admitted triggers were 9-12 character phrases.
     * `LOW` raises that floor to twelve characters. **The cooldown stays at its original 60 s by the owner's decision**
     * — only the length threshold changed, so the assistant's *rhythm* is unchanged and just its tolerance for short
     * phrases is lower. The trade-off belongs to the nuisance side: a short but genuine request is now more likely to
     * be missed than to interrupt.
     */
    val proactiveCooldownMillis: Long = 60_000L,
    val proactiveSensitivity: String = "LOW",
    /**
     * `D53`② (2026-10-11): the 「主动辅助」 switch, now a **persisted global setting** rather than per-session state.
     *
     * **On by default**: the owner's ruling is that it takes effect as soon as the App is installed, holds across
     * sessions, reconnects, App restarts and phone restarts, and - once the user turns it off - is **not** turned back
     * on by any path. The default lives here (so a fresh install and a pre-P3.5 blob both get it) and the switch writes
     * through [ProactiveArming] to this same field.
     */
    val proactiveEnabled: Boolean = true,
    /**
     * P5 (spec §S5): the 「语音朗读」 switch, persisted globally and **off by default**.
     *
     * When off, `SpeechOutput` is not advertised and any in-progress playback is stopped. Once the user turns it off,
     * no path may turn it back on automatically (`D53`② same-shape rule). A blob written before P5 lacks this key
     * and takes the default (off), which is the safe upgrade path.
     */
    val speechEnabled: Boolean = false,
    /**
     * 0.24 S3 (spec §5.5): the 「持续对话」 switch, persisted globally and **on by default**.
     *
     * It is the official `settings_ai_assistant_continue_dialogue` (default `"open"`): a displayed answer opens the
     * next listening round. Off takes the official `scheduleExit(1000)` branch instead - no status 5, no new turn,
     * the panel closes one second after the answer/speech end. A blob written before 0.24 lacks this key and takes the
     * default (on); once the user turns it off, no path may turn it back on automatically.
     */
    val continueDialogue: Boolean = true,
    /**
     * 0.3 Phase 2 Task 10: the 「感知截图」 switch, persisted globally and **on by default**.
     *
     * It is the host's live vision gate: when off, the perception provider answers `PerceptionScreenshotResult.Disabled`
     * and the device tool refuses `screenshot` with `VISION_DISABLED` before touching the kernel. The field is only
     * the persisted half - the composition owns a live `VisionGate` that mirrors it and is updated on save, so the
     * switch takes effect without an App restart.
     *
     * A blob written before this key exists takes the default (on), because the phase ships with vision on. Once the
     * user turns it off, no path may turn it back on automatically (`D53`② same-shape rule).
     */
    val visionEnabled: Boolean = true,
    /**
     * 0.3 Phase 4B (`D189`): the global 「不拦截」 switch, persisted and **off by default**.
     *
     * When on, the host's live [NoInterceptionGate] makes the policy allow every tool call (L0–L4), bypassing both
     * `maxLevel` and any per-action confirmation. It is a **persisted global mode**, not a per-session grant: closing,
     * killing or restarting the App does not reset it, and only the user's explicit off exits it. A blob written before
     * 4B lacks this key and takes the default (off) - the only safe upgrade path for a switch that removes confirmations.
     *
     * This field is the persisted half only; `HostCompositionRoot` owns the live gate and updates it on a successful
     * save.
     */
    val noInterception: Boolean = false,
    /**
     * 0.4 Task 5 (`D199`/`D202`): the host-only 「纯手机模式」 switch, persisted and **off by default**.
     *
     * It is not a plugin setting and deliberately does not appear in [pluginValues]: the plugin only ever sees the
     * **effective** arming value derived from it (`proactiveEnabled && !glassesDetached`), never the mode itself. A
     * blob written before 0.4 lacks the key and plays through the glasses, the existing behaviour; once the user
     * detaches, no path may re-attach them automatically.
     *
     * Kept as the **last** field so positional construction of older call sites is unchanged.
     */
    val glassesDetached: Boolean = false,
    /**
     * 0.5 (`D210`): the floating ball's opacity in percent, persisted globally.
     *
     * The ball is a translucent EDITH-icon overlay; the owner asked for it to be adjustable. [clampedBallOpacity]
     * enforces the shipped 20–100 range at every use site (the stored value is clamped on read as well), because a
     * 0% ball would be invisible and unusable rather than merely transparent. Kept after [glassesDetached] so older
     * positional construction of this data class stays valid.
     */
    val ballOpacityPercent: Int = DEFAULT_BALL_OPACITY,
    /**
     * 0.5 (`D211`): the message bubble's card opacity in percent, persisted globally.
     *
     * Only the card background uses this value; the system-default text stays fully opaque. Old messages multiply
     * this by a fixed [OLD_MESSAGE_ALPHA_RATIO] - there is deliberately no separate old-message setting.
     */
    val bubbleOpacityPercent: Int = DEFAULT_BUBBLE_OPACITY,
    /**
     * 0.5 (`D213`): whether the user has ever explicitly changed 「语音朗读」.
     *
     * The phone-only model turns speech on by default, but only while this is false: a user who turned speech off
     * must never have it silently re-enabled (the `D53`② same-shape rule). [setSpeechEnabled] sets it true.
     */
    val speechConfigured: Boolean = false,
) {
    /**
     * The plugin's own cooldown parameter.
     *
     * A non-positive stored value would make the detector re-report the same word on every frame, so it is clamped to
     * zero: "fire on every match" is a decision the detector already offers, while a negative duration is not a
     * configuration at all.
     */
    val cooldownNanos: Long get() = (if (cooldownMillis > 0) cooldownMillis else 0L) * 1_000_000L

    /** The ball opacity every renderer must use; a stored value outside the shipped range is clamped, never trusted. */
    fun clampedBallOpacity(): Int = ballOpacityPercent.coerceIn(MIN_BALL_OPACITY, MAX_OPACITY)

    /** The bubble card opacity every renderer must use; see [clampedBallOpacity] for the same clamp rule. */
    fun clampedBubbleOpacity(): Int = bubbleOpacityPercent.coerceIn(MIN_BUBBLE_OPACITY, MAX_OPACITY)

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
        // 0.3 Phase 4B (`D189`): the persisted 「不拦截」 value travels in the closed configuration shape. The plugin's
        // live behavior comes from the host gate provider (spec §5.1), never from this turn snapshot; sending the key
        // is what keeps a restarted App's configuration complete and the plugin's key set in agreement with the store.
        "noInterception" to noInterception.toString(),
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

        /** 0.5 (`D210`): the shipped ball opacity and the bounds the slider/renderer enforce. */
        const val DEFAULT_BALL_OPACITY = 82
        const val MIN_BALL_OPACITY = 20
        const val MAX_OPACITY = 100

        /** 0.5 (`D211`): the shipped bubble card opacity and its minimum. */
        const val DEFAULT_BUBBLE_OPACITY = 75
        const val MIN_BUBBLE_OPACITY = 30

        /**
         * 0.5 (`D211`): the fixed extra fade applied to a superseded message, multiplied by the configured bubble
         * opacity. Deliberately a constant rather than a setting - the owner ruled that old messages do not get their
         * own configuration.
         */
        const val OLD_MESSAGE_ALPHA_RATIO = 0.42f
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
            put("proactiveEnabled", settings.proactiveEnabled)
            put("speechEnabled", settings.speechEnabled)
            put("continueDialogue", settings.continueDialogue)
            put("visionEnabled", settings.visionEnabled)
            put("noInterception", settings.noInterception)
            // 0.4 Task 5 (`D199`): host-only; never part of `pluginValues()`.
            put("glassesDetached", settings.glassesDetached)
            // 0.5 (`D210`/`D211`/`D213`): host-only appearance and the explicit-speech flag. The opacity values are
            // clamped on write so a hand-edited blob cannot store a value the renderers would then have to trust.
            put("ballOpacityPercent", settings.clampedBallOpacity())
            put("bubbleOpacityPercent", settings.clampedBubbleOpacity())
            put("speechConfigured", settings.speechConfigured)
        })
    }

    /**
     * The value a **write-modify-write** may safely build on (0.4 Task 5).
     *
     * [load] answers with the defaults for every failure, which is right for a reader that must keep the App
     * configurable - but wrong for a writer: saving `load().copy(one field)` over a blob that exists and cannot be
     * read would silently erase the user's other choices. This variant therefore distinguishes the two cases:
     * a **missing** blob is a first install and yields the defaults; a blob that exists but cannot be read or parsed
     * yields `null`, and the caller must refuse the write.
     */
    internal fun loadForWrite(): SmartSettings? {
        val bytes = try { blobs.read(BLOB_ID) } catch (_: Exception) { return null }
            ?: return SmartSettings()
        return try {
            parseJson(Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject)
        } catch (_: Exception) {
            null
        } finally {
            bytes.fill(0)
        }
    }

    private fun safelyRead(): SmartSettings? =
        runCatching { readJson(blobs, BLOB_ID) }.getOrNull()?.let(::parseJson)

    private fun parseJson(json: JsonObject): SmartSettings? = runCatching {
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
                proactiveSensitivity = json["proactiveSensitivity"]?.jsonPrimitive?.contentOrNull
                    ?: SmartSettings().proactiveSensitivity,
                // Absent in a blob written before P3.5: like every other missing key it takes its default, which is
                // **on** (`D53`②). A pre-P3.5 blob therefore comes up armed, which is the ruling's intent - the switch
                // is a global default, not a per-session grant the user has to re-give.
                proactiveEnabled = json["proactiveEnabled"]?.jsonPrimitive?.booleanOrNull
                    ?: SmartSettings().proactiveEnabled,
                // Absent in a blob written before P5: takes the default (off), which is the safe upgrade path.
                speechEnabled = json["speechEnabled"]?.jsonPrimitive?.booleanOrNull
                    ?: SmartSettings().speechEnabled,
                // Absent in a blob written before 0.24: takes the official default (on). The key is new, so a pre-0.24
                // blob must continue conversations; only the user's explicit off is stored as false.
                continueDialogue = json["continueDialogue"]?.jsonPrimitive?.booleanOrNull
                    ?: SmartSettings().continueDialogue,
                // Absent in a blob written before Phase 2 Task 10: takes the default (on). The switch ships on, and an
                // explicit off is the user's act - it must never be inferred from an older blob.
                visionEnabled = json["visionEnabled"]?.jsonPrimitive?.booleanOrNull
                    ?: SmartSettings().visionEnabled,
                // Absent in a blob written before 4B: takes the default (**off**). This is the safe upgrade path - an
                // old blob must never remove confirmation by omission. `strictBoolean` additionally refuses a string
                // "true"/"false" (which kotlinx's `booleanOrNull` would coerce): a corrupt or hand-edited value must not
                // arm the mode. A non-primitive still fails the whole read, the store's existing wrong-shape rule.
                noInterception = strictBoolean(json, "noInterception")
                    ?: SmartSettings().noInterception,
                // 0.4 Task 5 (`D199`): absent in a blob written before 0.4, which must keep playing through the
                // glasses. `strictBoolean` keeps a string "true"/"false" from being coerced into a mode change.
                glassesDetached = strictBoolean(json, "glassesDetached")
                    ?: SmartSettings().glassesDetached,
                // 0.5 (`D210`/`D211`): absent in a pre-0.5 blob takes the shipped defaults. A present value is kept raw
                // here and clamped by `clampedBallOpacity()`/`clampedBubbleOpacity()` at every use site, so an
                // out-of-range blob still loads (the store's "missing/bad key takes a default" rule).
                ballOpacityPercent = json["ballOpacityPercent"]?.jsonPrimitive?.intOrNull
                    ?: SmartSettings().ballOpacityPercent,
                bubbleOpacityPercent = json["bubbleOpacityPercent"]?.jsonPrimitive?.intOrNull
                    ?: SmartSettings().bubbleOpacityPercent,
                // 0.5 (`D213`): absent means the user never touched the speech switch, so the phone-only model may turn
                // it on once. `strictBoolean` keeps a string from being coerced into "already configured".
                speechConfigured = strictBoolean(json, "speechConfigured")
                    ?: SmartSettings().speechConfigured,
            )
        }.getOrNull()

    /** A field that is absent takes its default; a field of the wrong shape is a corrupt blob and fails the whole read. */
    private fun strings(json: JsonObject, key: String): List<String>? {
        val element = json[key] ?: return null
        return element.jsonArray.map { it.jsonPrimitive.content }
    }

    /**
     * A boolean that only honours a real JSON boolean.
     *
     * kotlinx's `booleanOrNull` also accepts the JSON **strings** `"true"`/`"false"`. For the 「不拦截」 switch that
     * coercion is unsafe - a corrupt or hand-edited blob must never be able to arm a mode that removes confirmations -
     * so a string primitive is treated as absent and the safe default (off) applies. A non-primitive still throws out of
     * `jsonPrimitive`, which the caller's `runCatching` turns into the whole-read default, the store's existing rule.
     */
    private fun strictBoolean(json: JsonObject, key: String): Boolean? {
        val element = json[key] ?: return null
        val primitive = element.jsonPrimitive
        if (primitive.isString) return null
        return primitive.booleanOrNull
    }

    companion object {
        const val BLOB_ID = "smart-settings"
        private const val VERSION = 1
    }
}
