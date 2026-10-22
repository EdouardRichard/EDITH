package com.sheen.rokid.model

import com.sheen.rokid.storage.MemorySecretResolver
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class DevelopmentProfileLoaderTest {
    @Test fun `missing file empty key malformed budgets and conflicting aliases fail safely`() = MemorySecretResolver().use { resolver ->
        val loader = DevelopmentProfileLoader(resolver)
        assertEquals("CONFIG_MISSING", loader.load(Path.of(".local/no-such-generated-config.properties")).safeCode)
        for (config in listOf(valid.replace("apiKey=dummy-key", "apiKey="), valid.replace("inputBudgetTokens=3000", "inputBudgetTokens=invalid"),
            valid + "\ncontextBudgetTokens=2000", valid + "\npurpose=PRODUCTION", valid + "\nallowSyntheticTextUpload=anything")) {
            withConfig(config) { path ->
                val result = loader.load(path)
                assertNull(result.profile)
                assertEquals("CONFIG_INVALID", result.safeCode)
                assertFalse(result.toString().contains("dummy-key"))
            }
        }
    }

    @Test fun `valid loader stores only handle and defaults upload disabled`() = MemorySecretResolver().use { resolver ->
        withConfig(valid) { path ->
            val config = DevelopmentProfileLoader(resolver).load(path)
            assertNull(config.safeCode)
            assertFalse(config.allowSyntheticTextUpload)
            val profile = assertNotNull(config.profile)
            var received: CharArray? = null
            resolver.withSecret(profile.secret) { received = it; assertEquals("dummy-key", String(it)) }
            assertTrue(received!!.all { it == '\u0000' })
            resolver.revoke(profile.secret)
            assertFails { resolver.withSecret(profile.secret) { fail("revoked secret resolved") } }
        }
    }

    @Test fun `loader accepts explicit aliases but revokes replaced secret`() = MemorySecretResolver().use { resolver ->
        val loader = DevelopmentProfileLoader(resolver)
        withConfig(valid) { path ->
            val old = loader.load(path).profile!!
            Files.writeString(path, valid.replace("protocolId=", "protocol=").replace("inputBudgetTokens=", "contextBudgetTokens=") + "\nallowSyntheticTextUpload=true\npurpose=DEVELOPMENT_TEST")
            val new = loader.load(path)
            assertTrue(new.allowSyntheticTextUpload)
            assertNotNull(new.profile)
            assertFails { resolver.withSecret(old.secret) { fail("old secret resolved") } }
        }
    }

    private fun withConfig(body: String, block: (Path) -> Unit) {
        val path = Files.createTempFile(Path.of(".local/tmp").toAbsolutePath().also(Files::createDirectories), "dummy-profile-", ".properties")
        try { Files.writeString(path, body); block(path) } finally { Files.deleteIfExists(path) }
    }
    @Test fun `openai chat completions profile loads with audio input modality`() = MemorySecretResolver().use { resolver ->
        withConfig(openAiValid) { path ->
            val config = DevelopmentProfileLoader(resolver).load(path)
            assertNull(config.safeCode, "expected no error, got ${config.safeCode}")
            val profile = assertNotNull(config.profile)
            assertEquals("OPENAI_CHAT_COMPLETIONS", profile.protocolId)
            assertTrue(com.sheen.rokid.plugin.InputModality.AUDIO in profile.inputs, "AUDIO must be in inputs for OpenAI protocol")
            assertTrue(com.sheen.rokid.plugin.InputModality.TEXT in profile.inputs)
        }
    }

    companion object {
        val valid = """
            service=synthetic
            baseUrl=https://example.test/v1
            protocolId=DEEPSEEK_RESPONSES
            model=synthetic-model
            apiKey=dummy-key
            usableWindowTokens=4096
            inputBudgetTokens=3000
            maxOutputTokens=256
            safetyTokens=128
            timeoutMillis=10000
        """.trimIndent()
        val openAiValid = """
            service=alibaba
            baseUrl=https://example.test/compatible-mode/v1
            protocolId=OPENAI_CHAT_COMPLETIONS
            model=qwen-omni-test
            apiKey=dummy-omni-key
            usableWindowTokens=4096
            inputBudgetTokens=3000
            maxOutputTokens=256
            safetyTokens=128
            timeoutMillis=10000
            purpose=DEVELOPMENT_TEST
        """.trimIndent()
    }
}
