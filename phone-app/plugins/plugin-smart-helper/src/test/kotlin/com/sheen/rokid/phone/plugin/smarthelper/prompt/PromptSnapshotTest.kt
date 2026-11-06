package com.sheen.rokid.phone.plugin.smarthelper.prompt

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Golden hashes for the three prompt routes (spec §4.6, brief Task 16 Step 1).
 *
 * The hashes are computed from `PromptCatalog.plan(route, profileFixture())`: the fixture profile is part of the
 * golden, not a default prompt value. Any prose or section-order edit must update these constants in the same commit,
 * which makes an accidental prompt change show up as a failing test instead of a silent cache-behaviour change.
 *
 * The phase-1 snapshot test was retired in `8aa6e078`; 0.5.4 Task 16 reinstates it for all three routes (WAKE,
 * PROACTIVE_HINT, PROACTIVE_FOLLOWUP — the "continue" route). The values below were re-captured after the
 * confirmation-authority alignment in 30-safety/15-app-entry/20-tools and the exact two-failure/awaiting wording.
 * Fill them explicitly on any prompt edit; never regenerate them silently.
 */
class PromptSnapshotTest {

    @Test
    fun `the committed wake plan matches its golden hash`() {
        assertEquals(
            WAKE_GOLDEN_HASH,
            PromptCatalog.plan(PromptRoute.WAKE, profileFixture()).hash,
            "the WAKE plan changed; update WAKE_GOLDEN_HASH in this commit if the prose edit is intentional",
        )
    }

    @Test
    fun `the committed proactive hint plan matches its golden hash`() {
        assertEquals(
            PROACTIVE_GOLDEN_HASH,
            PromptCatalog.plan(PromptRoute.PROACTIVE_HINT, profileFixture()).hash,
            "the PROACTIVE_HINT plan changed; update PROACTIVE_GOLDEN_HASH if intentional",
        )
    }

    @Test
    fun `the committed continue plan matches its golden hash`() {
        assertEquals(
            CONTINUE_GOLDEN_HASH,
            PromptCatalog.plan(PromptRoute.PROACTIVE_FOLLOWUP, profileFixture()).hash,
            "the PROACTIVE_FOLLOWUP (continue) plan changed; update CONTINUE_GOLDEN_HASH if intentional",
        )
    }

    @Test
    fun `the three route goldens are distinct`() {
        assertEquals(
            3,
            setOf(WAKE_GOLDEN_HASH, PROACTIVE_GOLDEN_HASH, CONTINUE_GOLDEN_HASH).size,
            "each route posture must fingerprint its own plan",
        )
    }

    @Test
    fun `a golden hash is tied to the fixture profile not to constant prose`() {
        assertNotEquals(
            WAKE_GOLDEN_HASH,
            PromptCatalog.plan(PromptRoute.WAKE, profileFixture(model = "FixturePhone-B")).hash,
            "the rendered device profile is part of every golden",
        )
    }

    private companion object {
        // Re-captured from the first failing run after the 0.5.4 Task 16 prompt fix. Never regenerate silently.
        const val WAKE_GOLDEN_HASH = "cb9f1d109d82b9995ac296a78af7a80ee140a237bda5839a355759dcd340942a"
        const val PROACTIVE_GOLDEN_HASH = "41a4d8c0ee4f436d795f696d56b05a639f3127941eeedb3f70975f6a8abd8443"
        const val CONTINUE_GOLDEN_HASH = "421a4600e738b22058665ef76c6209c1f3f5f39b1912ab0e2d6bf164cafb88e3"
    }
}
