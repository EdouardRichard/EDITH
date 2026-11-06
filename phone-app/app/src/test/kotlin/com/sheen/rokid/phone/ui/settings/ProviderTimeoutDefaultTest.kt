package com.sheen.rokid.phone.ui.settings

import com.sheen.rokid.phone.model.catalog.Provider
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 0.5.4 Task 6 Finding 2: the Settings provider form used to default a new provider to 30 s while the catalog default
 * and the client's hard cap are 90 s. The form is the path a real provider takes, so its starting value must be the
 * same 90 s. An existing stored provider keeps its own value - this default is not a migration.
 *
 * The form is a Compose screen with no JVM/Compose rig in this module, so the two branches are pinned through the
 * extracted text helper rather than a rendered field.
 */
class ProviderTimeoutDefaultTest {

    private fun provider(timeoutMillis: Long) = Provider(
        id = "p-1",
        name = "DeepSeek",
        baseUrl = "https://api.deepseek.com/v1",
        timeoutMillis = timeoutMillis,
    )

    @Test
    fun `a new provider form starts at the 90 second catalog default`() {
        // The pinning: the form's constant and the domain default are the same value, which is the 90 s hard cap.
        assertEquals(
            Provider(id = "p-1", name = "DeepSeek", baseUrl = "https://api.deepseek.com/v1").timeoutMillis,
            PROVIDER_TIMEOUT_DEFAULT_MILLIS,
        )
        assertEquals(90_000L, PROVIDER_TIMEOUT_DEFAULT_MILLIS)
        assertEquals("90000", providerTimeoutText(stored = null))
    }

    @Test
    fun `an edited provider keeps the timeout it was stored with`() {
        // No migration: a provider stored at the old 30 s default keeps 30 s in the form, and its untouched text is
        // therefore not dirty.
        assertEquals("30000", providerTimeoutText(provider(timeoutMillis = 30_000)))
        assertEquals("120000", providerTimeoutText(provider(timeoutMillis = 120_000)))
    }
}
