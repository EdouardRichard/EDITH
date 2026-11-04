package com.sheen.rokid.phone.model.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogValidationTest {
    @Test fun `provider names are trimmed bounded and single line`() {
        assertNotNull(CatalogValidation.providerNameError(""))
        assertNotNull(CatalogValidation.providerNameError("   "))
        assertNotNull(CatalogValidation.providerNameError("a".repeat(CatalogValidation.MAX_PROVIDER_NAME + 1)))
        assertNotNull(CatalogValidation.providerNameError("line\nbreak"))
        assertNull(CatalogValidation.providerNameError("  DeepSeek  "))
        assertNull(CatalogValidation.providerNameError("a".repeat(CatalogValidation.MAX_PROVIDER_NAME)))
        assertEquals(40, CatalogValidation.MAX_PROVIDER_NAME)
    }

    @Test fun `base urls must be https without userinfo query or fragment and keep v1`() {
        assertNull(CatalogValidation.baseUrlError("https://api.deepseek.com/v1"))
        assertNotNull(CatalogValidation.baseUrlError("http://api.deepseek.com/v1"))
        assertNotNull(CatalogValidation.baseUrlError("https://user:pass@api.deepseek.com/v1"))
        assertNotNull(CatalogValidation.baseUrlError("https://api.deepseek.com/v1?q=1"))
        assertNotNull(CatalogValidation.baseUrlError("https://api.deepseek.com/v1#fragment"))
        assertNull(CatalogValidation.baseUrlError("http://127.0.0.1:8080/v1", allowLocalHttp = true))
        assertNotNull(CatalogValidation.baseUrlError("http://api.deepseek.com/v1", allowLocalHttp = true))

        // The rejection message is safe: it must never echo what the user typed (credentials, host, query).
        val entered = "https://user:key@example.test/v1?token=abc"
        val message = CatalogValidation.baseUrlError(entered)
        assertNotNull(message)
        assertFalse(message!!.contains("example.test"))
        assertFalse(message.contains("key"))
        assertFalse(message.contains("token"))
    }

    @Test fun `the output must leave room for the safety reserve`() {
        assertNull(CatalogValidation.budgetError(131_072, 8_192))
        val tooLarge = CatalogValidation.budgetError(131_072, 131_072)
        assertNotNull(tooLarge)
        assertTrue(tooLarge!!.contains("最大输出"))
        assertEquals(256, CatalogValidation.SAFETY_TOKENS)

        val reserve = CatalogValidation.budgetError(1_000, 800)
        assertNotNull(reserve)
        assertTrue(reserve!!.contains("安全余量"))
        assertNotEquals(tooLarge, reserve)
    }

    @Test fun `a model id may not be blank or contain control characters`() {
        assertNotNull(CatalogValidation.modelIdError(""))
        assertNotNull(CatalogValidation.modelIdError("   "))
        assertNotNull(CatalogValidation.modelIdError("model\nname"))
        assertNotNull(CatalogValidation.modelIdError("model\u0000name"))
        assertNull(CatalogValidation.modelIdError("deepseek-flash"))
        assertNull(CatalogValidation.modelIdError("model:with:colons"))
    }
}
