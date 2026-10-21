package com.sheen.rokid.phone.plugin.smarthelper.prompt

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
 * The `0.3.0` values below were captured from the first run after the resources were authored, and updated once in
 * the Phase 1 final-fix wave for the prose corrections (`400 个码点` and the tools/wake "report before acting"
 * reconciliation). Any further edit must update them explicitly.
 */
class PromptSnapshotTest {

    @Test
    fun `the committed wake and proactive plans match their golden hashes`() {
        assertEquals(WAKE_GOLDEN_HASH, PromptCatalog.plan(PromptRoute.WAKE, profileFixture()).hash)
        assertEquals(PROACTIVE_GOLDEN_HASH, PromptCatalog.plan(PromptRoute.PROACTIVE_HINT, profileFixture()).hash)
    }

    @Test
    fun `the golden hash is tied to the fixture profile not to a constant prompt`() {
        assertNotEquals(
            WAKE_GOLDEN_HASH,
            PromptCatalog.plan(PromptRoute.WAKE, profileFixture(model = "FixturePhone-B")).hash,
        )
    }

    private companion object {
        // Captured from the first failing run after `prompts/*` and `PromptCatalog` were authored.
        // Fill these explicitly on any prompt edit; never regenerate them silently.
        const val WAKE_GOLDEN_HASH = "fb54847cdbeca6d7701e0ce6a546f9df0f3013b6c49831c59f11516af74c60b5"
        const val PROACTIVE_GOLDEN_HASH = "0ff3f2bb0fa572312b25264a2dd9e8314665b1db679b34f0e55d5d6fe8e590ba"
    }
}
