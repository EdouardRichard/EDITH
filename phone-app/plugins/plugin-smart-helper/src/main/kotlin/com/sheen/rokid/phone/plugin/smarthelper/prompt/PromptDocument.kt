package com.sheen.rokid.phone.plugin.smarthelper.prompt

import java.nio.charset.StandardCharsets

/**
 * Loads prompt resources from the JVM classpath (spec §4.1).
 *
 * Prompts are plain UTF-8 Markdown under `src/main/resources/prompts/`, so they travel in the plugin
 * jar and do not depend on Android resources. A missing resource is an error: silently substituting
 * an empty prompt would ship a session with no system instructions at all.
 */
object PromptDocument {

    /**
     * Returns the UTF-8 text of the classpath resource at [resource].
     *
     * A single leading `/` is accepted for callers that think in classpath-absolute terms, though
     * `ClassLoader.getResourceAsStream` itself expects the relative form.
     *
     * @throws IllegalArgumentException if [resource] is blank or absent from the classpath.
     */
    fun load(resource: String): String {
        require(resource.isNotBlank()) { "prompt resource name must not be blank" }

        val path = resource.removePrefix("/")
        val stream = PromptDocument::class.java.classLoader.getResourceAsStream(path)
            ?: throw IllegalArgumentException("prompt resource not found on classpath: $path")

        return stream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
    }
}
