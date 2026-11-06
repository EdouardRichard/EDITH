package com.sheen.rokid.phone.storage

import android.content.Context
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 0.52 design §5.3: the one-shot upgrade notice for the pre-catalog model profiles.
 *
 * Old `profiles-manifest` / `profile-*` blobs are never read as profiles. A first launch that finds a manifest and no
 * `model-catalog` offers to delete the old payloads once; [cleanup] touches only the manifest and the `profile-*`
 * payload ids it references, never the catalog, namespaces, speakers or credentials.
 */
class LegacyModelConfig(
    private val blobs: EncryptedBlobStore,
    private val prompts: LegacyPromptStore,
) {
    /** True when legacy profiles exist and the v2 catalog does not; a manifest read failure counts as present. */
    fun isPresent(): Boolean {
        val catalogExists = try {
            blobs.read(CATALOG) != null
        } catch (_: Exception) {
            true
        }
        if (catalogExists) return false
        return try {
            blobs.read(MANIFEST) != null
        } catch (_: Exception) {
            true
        }
    }

    fun promptShown(): Boolean = prompts.shown()

    fun markPromptShown() = prompts.markShown()

    /** Deletes the v1 manifest and its referenced payloads. Returns false when there was no manifest to clean. */
    fun cleanup(): Boolean {
        val bytes = try {
            blobs.read(MANIFEST)
        } catch (_: Exception) {
            return false
        } ?: return false
        val payloads = try {
            val manifest = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
            require(manifest.getValue("version").jsonPrimitive.int == 1)
            manifest.getValue("profiles").jsonObject.values.mapNotNull { it.jsonPrimitive.contentOrNull }
        } catch (_: Exception) {
            // A malformed manifest deletes only the manifest: its references cannot be trusted.
            emptyList()
        } finally {
            bytes.fill(0)
        }
        payloads.filter { it.startsWith(PAYLOAD_PREFIX) }.forEach { id -> runCatching { blobs.delete(id) } }
        safely { blobs.delete(MANIFEST) }
        return true
    }

    private companion object {
        const val CATALOG = "model-catalog"
        const val MANIFEST = "profiles-manifest"
        const val PAYLOAD_PREFIX = "profile-"
    }
}

/** The persisted one-time prompt flag (ruling R9). Tests use an in-memory fake. */
interface LegacyPromptStore {
    fun shown(): Boolean
    fun markShown()
}

/** Production prompt flag: SharedPreferences `edith_model_upgrade`, key `prompted`. */
class SharedPreferencesLegacyPromptStore(context: Context) : LegacyPromptStore {
    private val prefs by lazy {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    override fun shown(): Boolean = prefs.getBoolean(KEY_PROMPTED, false)

    override fun markShown() {
        prefs.edit().putBoolean(KEY_PROMPTED, true).apply()
    }

    private companion object {
        const val PREFS = "edith_model_upgrade"
        const val KEY_PROMPTED = "prompted"
    }
}
