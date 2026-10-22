package com.sheen.rokid.model

import com.sheen.rokid.plugin.*
import com.sheen.rokid.storage.MemorySecretResolver
import java.nio.file.Path
import java.nio.file.Files
import java.util.Properties

data class DevelopmentConfiguration(val profile: ModelProfile?, val allowSyntheticTextUpload: Boolean, val safeCode: String?)
class DevelopmentProfileLoader(private val resolver: MemorySecretResolver) {
    private var owned: SecretHandle? = null
    @Synchronized fun load(path: Path): DevelopmentConfiguration {
        fun failed(code: String) = DevelopmentConfiguration(null, false, code)
        if (!Files.exists(path)) return failed("CONFIG_MISSING")
        return try {
            val properties = Properties().apply { Files.newInputStream(path).use { load(it) } }
            fun value(name: String, alias: String? = null): String {
                val primary = properties.getProperty(name)?.trim()
                val alternate = alias?.let { properties.getProperty(it)?.trim() }
                require(primary == null || alternate == null || primary == alternate)
                return (primary ?: alternate).also { require(!it.isNullOrBlank()) }!!
            }
            val enabled = properties.getProperty("allowSyntheticTextUpload")?.trim()?.let {
                require(it == "true" || it == "false"); it == "true"
            } ?: false
            properties.getProperty("purpose")?.let { require(it.trim() == "DEVELOPMENT_TEST") }
            val key = value("apiKey")
            require(key.all { it.code in 33..126 })
            val placeholder = object : SecretHandle { override val id = "unregistered" }
            val protocolId = value("protocolId", "protocol")
            val inputs = if (protocolId == "OPENAI_CHAT_COMPLETIONS")
                setOf(InputModality.TEXT, InputModality.AUDIO) else setOf(InputModality.TEXT)
            val profile = ModelProfile("development", 0, "Development probe", value("service"),
                value("baseUrl"), protocolId, value("model"), placeholder,
                value("usableWindowTokens").toInt(), value("inputBudgetTokens", "contextBudgetTokens").toInt(),
                value("maxOutputTokens").toInt(), value("safetyTokens").toInt(), value("timeoutMillis").toLong(),
                inputs, setOf(OutputModality.TEXT), emptyList())
            // Validate endpoint format for known protocols.
            when (protocolId) {
                "DEEPSEEK_RESPONSES" -> ModelEndpoint.responses(profile.baseUrl)
                "OPENAI_CHAT_COMPLETIONS" -> ModelEndpoint.chatCompletions(profile.baseUrl)
            }
            val handle = resolver.put(key.toCharArray())
            owned?.let(resolver::revoke)
            owned = handle
            DevelopmentConfiguration(profile.copy(secret = handle), enabled, null)
        } catch (_: Exception) { failed("CONFIG_INVALID") }
    }
}
