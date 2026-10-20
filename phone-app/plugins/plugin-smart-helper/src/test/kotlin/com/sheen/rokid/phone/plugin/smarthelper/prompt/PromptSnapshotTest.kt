package com.sheen.rokid.phone.plugin.smarthelper.prompt

import com.sheen.rokid.phone.plugin.smarthelper.context.ContextRoute
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Golden hashes for the two Phase 1 routes (spec §4.6).
 *
 * The hashes are computed from `PromptCatalog.plan(route, profileFixture())`: the fixture profile is
 * part of the golden, not a default prompt value. Any prose or section-order edit must update these
 * constants in the same commit, which makes an accidental prompt change show up as a failing test
 * instead of a silent cache-behaviour change.
 *
 * The `0.3.0` values below were captured from the first run after the resources were authored.
 */
class PromptSnapshotTest {

    @Test
    fun `the committed wake and proactive plans match their golden hashes`() {
        assertEquals(WAKE_GOLDEN_HASH, PromptCatalog.plan(ContextRoute.WAKE, profileFixture()).hash)
        assertEquals(PROACTIVE_GOLDEN_HASH, PromptCatalog.plan(ContextRoute.PROACTIVE, profileFixture()).hash)
    }

    @Test
    fun `the golden hash is tied to the fixture profile not to a constant prompt`() {
        assertNotEquals(
            WAKE_GOLDEN_HASH,
            PromptCatalog.plan(ContextRoute.WAKE, profileFixture(model = "FixturePhone-B")).hash,
        )
    }

    private companion object {
        // Captured from the first failing run after `prompts/*` and `PromptCatalog` were authored.
        // Fill these explicitly on any prompt edit; never regenerate them silently.
        const val WAKE_GOLDEN_HASH = "7c0ed456907ed2a8e14ae6fd51ed0e0adc6667dc084d1da287a8fc127a0a86f6"
        const val PROACTIVE_GOLDEN_HASH = "7a5f8342b1d1a873e14be7be042a867abc3afe9861faf151a9ae0c74f8480ff2"
    }
}
