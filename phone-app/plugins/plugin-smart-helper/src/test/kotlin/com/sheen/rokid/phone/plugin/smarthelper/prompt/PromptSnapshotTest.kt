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
 * The values below were captured from the first run after the resources were authored, updated once in the Phase 1
 * final-fix wave for the prose corrections (`400 个码点` and the tools/wake "report before acting" reconciliation), and
 * re-captured for the Phase 4A Task 6 24-tool rewrite and its catalog version `0.4.0`. Any further edit must update them
 * explicitly.
 *
 * Phase 4A Task 7 (A7) is deliberately **not** a re-capture: the pinned gate options and the structured `summarizer.md`
 * are independent fixed prompts outside every system plan, and [PromptPlan.hash] fingerprints only the ordered section
 * texts (not the catalog version), so the plan bytes are unchanged. The catalog version moves to `0.4.1`, which
 * `PromptTelemetryTest` pins.
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
        // Captured from the first failing run after `prompts/*` and `PromptCatalog` were authored, then
        // re-captured explicitly in Phase 4A Task 6 for the 24-tool `core/20-tools.md` rewrite.
        // Fill these explicitly on any prompt edit; never regenerate them silently.
        const val WAKE_GOLDEN_HASH = "ed245aa207d757aaf69a8f2d899843f8b4ed435544d702d844b20e34b2ff1be0"
        const val PROACTIVE_GOLDEN_HASH = "7ffca0e3c3cdcd33be784658abfe98970548c6e5c8fad8fbaa8ac319f2e90793"
    }
}
