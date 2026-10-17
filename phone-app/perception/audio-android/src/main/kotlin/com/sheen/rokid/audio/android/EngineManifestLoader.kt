package com.sheen.rokid.audio.android

import java.io.InputStream
import kotlinx.serialization.json.*

/** Parses the app's bundled manifest. Imported models cannot supply or replace this trust source. */
object EngineManifestLoader {
    fun load(input: InputStream): EngineManifest {
        val text = input.reader(Charsets.UTF_8).use { reader ->
            val buffer = CharArray(4096)
            val result = StringBuilder()
            while (true) {
                val count = reader.read(buffer)
                if (count < 0) break
                require(result.length + count <= 65_536) { "Manifest exceeds size limit" }
                result.append(buffer, 0, count)
            }
            result.toString()
        }
        val root = Json.parseToJsonElement(text).jsonObject
        require(root.integer("schemaVersion") == 1)
        require(root.field("runtime").jsonObject.string("sha256").equals(
            "633C24321E06B1FE79FEAFA03EA16CBC0F8A286641E2DA3559BAC91BDB13BD96", true))
        require(root.string("boundaryOwner") == "VAD")
        require(root.literal("recognizerEndpointEnabled").booleanOrNull == false)
        require(root.integer("vadWindowSamples") == 512)
        require(root.literal("vadThreshold").doubleOrNull == 0.5)
        require(root.literal("vadMinSpeechSeconds").doubleOrNull == 0.25)
        require(root.literal("vadMaxSpeechSeconds").doubleOrNull == 20.0)
        val manifest = EngineManifest(root.string("runtimeVersion"),
            root.field("artifacts").jsonArray.map { element ->
                val artifact = element.jsonObject
                ModelArtifact(artifact.string("role"), artifact.string("path"),
                    requireNotNull(artifact.literal("bytes").longOrNull), artifact.string("sha256"),
                    artifact.string("sourceUrl"), artifact.string("revision"), artifact.string("licensePath"))
            }, root.integer("sampleRateHz"), root.integer("threadCount"), root.string("provider"),
            root.integer("frameMillis"), root.integer("endpointMillis"))
        manifest.validate()
        return manifest
    }

    private fun JsonObject.string(key: String): String {
        val value = field(key).jsonPrimitive
        require(value.isString) { "$key must be a string" }
        return value.content
    }
    private fun JsonObject.literal(key: String): JsonPrimitive {
        val value = field(key).jsonPrimitive
        require(!value.isString) { "$key must be a literal" }
        return value
    }
    private fun JsonObject.integer(key: String) = requireNotNull(literal(key).intOrNull) { "$key must be an integer" }
    private fun JsonObject.field(key: String) = requireNotNull(get(key)) { "Missing $key" }
}
