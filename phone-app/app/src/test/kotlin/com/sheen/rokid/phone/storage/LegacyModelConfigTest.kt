package com.sheen.rokid.phone.storage

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * The one-shot 0.52 upgrade notice (0.52 design §5.3): old `profiles-manifest` payloads are never read as profiles,
 * but a first launch with a manifest and no `model-catalog` offers to delete them.
 *
 * The janitor only deletes the v1 manifest and the `profile-*` payload ids it references: the catalog, namespaces,
 * speakers and credentials are other subsystems' blobs and must survive a cleanup.
 */
class LegacyModelConfigTest {
    private class Blobs : EncryptedBlobStore {
        val values = linkedMapOf<String, ByteArray>()
        var failReadId: String? = null
        override fun read(id: String): ByteArray? {
            if (id == failReadId) throw IOException("synthetic read failure")
            return values[id]?.copyOf()
        }
        override fun write(id: String, value: ByteArray) {
            values[id] = value.copyOf()
        }
        override fun delete(id: String) {
            values.remove(id)?.fill(0)
        }
    }

    private class Prompts : LegacyPromptStore {
        private var prompted = false
        override fun shown(): Boolean = prompted
        override fun markShown() {
            prompted = true
        }
    }

    private fun manifest(
        profiles: Map<String, String>,
        selections: Map<String, String> = emptyMap(),
    ): ByteArray = buildJsonObject {
        put("version", 1)
        put("highWater", 7)
        put("profiles", buildJsonObject { profiles.forEach { (id, ref) -> put(id, ref) } })
        put("selections", buildJsonObject { selections.forEach { (plugin, id) -> put(plugin, id) } })
    }.toString().toByteArray()

    @Test fun `a profiles manifest with no catalog is detected`() {
        val blobs = Blobs()
        val legacy = LegacyModelConfig(blobs, Prompts())

        assertFalse(legacy.isPresent())

        blobs.write("profiles-manifest", manifest(mapOf("a" to "profile-p1")))
        assertTrue(legacy.isPresent())

        blobs.write("model-catalog", """{"version":2}""".toByteArray())
        assertFalse(legacy.isPresent())
    }

    @Test fun `a manifest read failure counts as present`() {
        val blobs = Blobs()
        blobs.write("profiles-manifest", manifest(mapOf("a" to "profile-p1")))
        blobs.failReadId = "profiles-manifest"

        assertTrue(LegacyModelConfig(blobs, Prompts()).isPresent())
    }

    @Test fun `cleanup deletes the manifest and its payloads but keeps unrelated blobs`() {
        val blobs = Blobs()
        blobs.write(
            "profiles-manifest",
            manifest(
                profiles = mapOf("a" to "profile-p1", "b" to "profile-p2"),
                selections = mapOf("smart-assistant" to "a"),
            ),
        )
        blobs.write("profile-p1", byteArrayOf(1))
        blobs.write("profile-p2", byteArrayOf(2))
        blobs.write("profile-unreferenced", byteArrayOf(3))
        blobs.write("model-catalog", """{"version":2}""".toByteArray())
        blobs.write("namespace-smart-assistant", byteArrayOf(4))
        blobs.write("speaker-1", byteArrayOf(5))
        blobs.write("credential-1", byteArrayOf(6))
        val legacy = LegacyModelConfig(blobs, Prompts())

        assertTrue(legacy.cleanup())

        assertFalse(blobs.values.containsKey("profiles-manifest"))
        assertFalse(blobs.values.containsKey("profile-p1"))
        assertFalse(blobs.values.containsKey("profile-p2"))
        assertTrue(blobs.values.containsKey("profile-unreferenced"))
        assertTrue(blobs.values.containsKey("model-catalog"))
        assertTrue(blobs.values.containsKey("namespace-smart-assistant"))
        assertTrue(blobs.values.containsKey("speaker-1"))
        assertTrue(blobs.values.containsKey("credential-1"))
    }

    @Test fun `a malformed manifest is deleted without touching catalog namespaces`() {
        val blobs = Blobs()
        blobs.write("profile-p1", byteArrayOf(1))
        blobs.write("model-catalog", """{"version":2}""".toByteArray())
        blobs.write("namespace-smart-assistant", byteArrayOf(2))
        val legacy = LegacyModelConfig(blobs, Prompts())

        assertFalse(legacy.cleanup())
        blobs.write("profiles-manifest", "{not-json".toByteArray())
        assertTrue(legacy.cleanup())
        assertFalse(blobs.values.containsKey("profiles-manifest"))
        assertTrue(blobs.values.containsKey("profile-p1"))
        assertTrue(blobs.values.containsKey("model-catalog"))
        assertTrue(blobs.values.containsKey("namespace-smart-assistant"))

        blobs.write("profiles-manifest", """{"version":2,"profiles":{"a":"profile-p1"}}""".toByteArray())
        assertTrue(legacy.cleanup())
        assertFalse(blobs.values.containsKey("profiles-manifest"))
        assertTrue(blobs.values.containsKey("profile-p1"))

        blobs.write("profiles-manifest", manifest(mapOf("a" to "profile-p1")).copyOf())
        assertTrue(legacy.cleanup())
        assertFalse(blobs.values.containsKey("profile-p1"))
        blobs.write("profiles-manifest", manifest(mapOf("a" to "model-catalog")).copyOf())
        assertTrue(legacy.cleanup())
        assertTrue(blobs.values.containsKey("model-catalog"))
        assertTrue(blobs.values.containsKey("namespace-smart-assistant"))
    }

    @Test fun `the prompt flag survives a second detector instance`() {
        val blobs = Blobs()
        val prompts = Prompts()

        assertFalse(LegacyModelConfig(blobs, prompts).promptShown())
        LegacyModelConfig(blobs, prompts).markPromptShown()

        assertTrue(prompts.shown())
        assertTrue(LegacyModelConfig(blobs, prompts).promptShown())
        assertFalse(LegacyModelConfig(blobs, prompts).isPresent())
    }
}
