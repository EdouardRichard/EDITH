package com.sheen.rokid.agent

import com.sheen.rokid.plugin.InputModality
import com.sheen.rokid.plugin.ModelProfile
import com.sheen.rokid.plugin.OutputModality
import com.sheen.rokid.plugin.SecretHandle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 0.3 Phase 1 / design §5.2 and §6.3: all budgets derive from the configured [ModelProfile]. There is no fixed total in
 * the code, and an inconsistent profile or tuning fails closed instead of being clamped.
 */
class ContextPolicyTest {

    // ── fixture ─────────────────────────────────────────────────────────────────────────────────────

    private fun profile(
        usable: Int,
        input: Int,
        output: Int,
        safety: Int,
    ) = ModelProfile(
        id = "profile-under-test",
        revision = 1,
        name = "Profile under test",
        service = "test",
        baseUrl = "https://example.invalid",
        protocolId = "TEST",
        model = "test-model",
        secret = object : SecretHandle { override val id = "secret" },
        usableWindowTokens = usable,
        inputBudgetTokens = input,
        maxOutputTokens = output,
        safetyTokens = safety,
        timeoutMillis = 1_000,
        inputs = setOf(InputModality.TEXT),
        outputs = setOf(OutputModality.TEXT),
        verifications = emptyList(),
    )

    /** 8K-class configured profile: the window remainder and the explicit input budget are both 6,912. */
    private fun smallProfile() = profile(usable = 8_192, input = 6_912, output = 1_024, safety = 256)

    // ── exact derivation ────────────────────────────────────────────────────────────────────────────

    @Test
    fun `default tuning derives every limit from the profile`() {
        val policy = ContextPolicy.from(smallProfile())

        assertEquals(6_912, policy.hardInputLimit) // min(6_912, 8_192 - 1_024 - 256)
        assertEquals(5_184, policy.softLimit) // floor(6_912 × 0.75)
        assertEquals(691, policy.hardMargin) // floor(6_912 × 0.10)
        assertEquals(6_221, policy.hardLimit) // 6_912 - 691
        assertEquals(139, policy.summaryMaxTokens) // ceil(6_912 × 0.02)
        assertEquals(0.75, policy.compressionThreshold, 1e-9)
        assertEquals(4, policy.keepRecentTurns)
        assertEquals(4, policy.toolResultRetention)
    }

    @Test
    fun `custom tuning is applied and carried into the policy`() {
        val tuning = ContextTuning(
            compressionThreshold = 0.5,
            hardMarginRatio = 0.10,
            keepRecentTurns = 2,
            summaryRatio = 0.10,
            toolResultRetention = 1,
        )

        val policy = ContextPolicy.from(smallProfile(), tuning)

        assertEquals(3_456, policy.softLimit) // floor(6_912 × 0.5)
        assertEquals(691, policy.hardMargin)
        assertEquals(6_221, policy.hardLimit)
        assertEquals(692, policy.summaryMaxTokens) // ceil(6_912 × 0.10)
        assertEquals(0.5, policy.compressionThreshold, 1e-9)
        assertEquals(2, policy.keepRecentTurns)
        assertEquals(1, policy.toolResultRetention)
    }

    @Test
    fun `a tiny budget still yields a positive margin and a positive summary budget`() {
        // 10 + 80 + 10 = 100, so the profile itself is consistent.
        val policy = ContextPolicy.from(profile(usable = 100, input = 10, output = 80, safety = 10))

        assertEquals(10, policy.hardInputLimit)
        assertEquals(7, policy.softLimit) // floor(10 × 0.75)
        assertEquals(1, policy.hardMargin) // max(1, floor(10 × 0.10))
        assertEquals(9, policy.hardLimit)
        assertEquals(1, policy.summaryMaxTokens) // max(1, ceil(10 × 0.02))
    }

    @Test
    fun `soft and hard limits may meet exactly`() {
        // The window remainder is 11,000, above the explicit 10,000 input budget: min() keeps the budget.
        val policy = ContextPolicy.from(
            profile(usable = 20_000, input = 10_000, output = 8_000, safety = 1_000),
            ContextTuning(compressionThreshold = 0.75, hardMarginRatio = 0.25),
        )

        assertEquals(10_000, policy.hardInputLimit)
        assertEquals(2_500, policy.hardMargin)
        assertEquals(7_500, policy.hardLimit)
        assertEquals(7_500, policy.softLimit)
        assertTrue(policy.softLimit <= policy.hardLimit)
    }

    // ── profile-driven, no fixed total ──────────────────────────────────────────────────────────────

    @Test
    fun `8k 128k and 1m profiles derive different limits rather than a fixed budget`() {
        val small = ContextPolicy.from(smallProfile())
        val medium = ContextPolicy.from(
            profile(usable = 131_072, input = 110_592, output = 16_384, safety = 4_096),
        )
        val large = ContextPolicy.from(
            profile(usable = 1_000_000, input = 735_808, output = 256_000, safety = 8_192),
        )

        assertEquals(6_912, small.hardInputLimit)
        assertEquals(110_592, medium.hardInputLimit)
        assertEquals(735_808, large.hardInputLimit)
        assertEquals(5_184, small.softLimit)
        assertEquals(82_944, medium.softLimit)
        assertEquals(551_856, large.softLimit)

        assertTrue(small.hardInputLimit < medium.hardInputLimit)
        assertTrue(medium.hardInputLimit < large.hardInputLimit)
        assertTrue(small.softLimit < medium.softLimit)
        assertTrue(medium.softLimit < large.softLimit)
        assertTrue(small.hardLimit < medium.hardLimit)
        assertTrue(medium.hardLimit < large.hardLimit)
        assertTrue(listOf(small.hardInputLimit, medium.hardInputLimit, large.hardInputLimit).none { it == 4_096 })
        for (policy in listOf(small, medium, large)) {
            assertTrue(policy.softLimit <= policy.hardLimit)
            assertTrue(policy.hardLimit <= policy.hardInputLimit)
        }
    }

    // ── fail closed ─────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a compression threshold outside zero to one fails closed`() {
        for (threshold in listOf(0.0, 1.0, 1.5, -0.25, Double.NaN)) {
            assertFailsWith<IllegalArgumentException>("threshold=$threshold") {
                ContextPolicy.from(smallProfile(), ContextTuning(compressionThreshold = threshold))
            }
        }
    }

    @Test
    fun `a threshold that rounds the soft limit down to zero fails closed`() {
        assertFailsWith<IllegalArgumentException> {
            ContextPolicy.from(smallProfile(), ContextTuning(compressionThreshold = 0.0001)) // floor(0.69) = 0
        }
    }

    @Test
    fun `a margin that pushes the hard limit below the soft limit fails closed`() {
        assertFailsWith<IllegalArgumentException> {
            ContextPolicy.from(smallProfile(), ContextTuning(hardMarginRatio = 0.90)) // 6,912 - 6,220 < 5,184
        }
    }

    @Test
    fun `fewer than one kept recent turn fails closed`() {
        for (keepRecentTurns in listOf(0, -1)) {
            assertFailsWith<IllegalArgumentException>("keepRecentTurns=$keepRecentTurns") {
                ContextPolicy.from(smallProfile(), ContextTuning(keepRecentTurns = keepRecentTurns))
            }
        }
        // One kept turn is the valid lower boundary.
        assertEquals(1, ContextPolicy.from(smallProfile(), ContextTuning(keepRecentTurns = 1)).keepRecentTurns)
    }

    @Test
    fun `an invalid profile fails closed before a policy exists`() {
        // ModelProfile already rejects input budget + output + safety > usable window; ContextPolicy must never fall
        // back to a guessed budget for such a profile.
        assertFailsWith<IllegalArgumentException> {
            ContextPolicy.from(profile(usable = 8_192, input = 9_000, output = 1_024, safety = 256))
        }
        assertFailsWith<IllegalArgumentException> {
            ContextPolicy.from(profile(usable = 1_000, input = 100, output = 900, safety = 100))
        }
    }

    // ── distinct fail-closed codes (final-fix wave) ───────────────────────────────────────────────

    @Test
    fun `a valid profile has no failure code`() {
        assertNull(ContextPolicy.failureCode(smallProfile()))
    }

    @Test
    fun `a profile too small for the policy invariant is PROFILE_TOO_SMALL`() {
        // 1 + 1 + 1 = 3 <= 100, so the profile is consistent; the derived hard input is 1 and the 0.75 soft limit
        // rounds to zero. Before the final-fix wave this was swallowed and surfaced as `PROFILE_MISSING`.
        assertEquals(
            ContextPolicy.PROFILE_TOO_SMALL,
            ContextPolicy.failureCode(profile(usable = 100, input = 1, output = 1, safety = 1)),
        )
    }

    @Test
    fun `a threshold that rounds the soft limit down to zero is PROFILE_TOO_SMALL`() {
        assertEquals(
            ContextPolicy.PROFILE_TOO_SMALL,
            ContextPolicy.failureCode(smallProfile(), ContextTuning(compressionThreshold = 0.0001)),
        )
    }

    @Test
    fun `invalid tuning is PROFILE_INVALID`() {
        // The profile surface is valid; the configuration that shapes it is not. The two must not share a code:
        // `PROFILE_INVALID` says "fix the configuration", `PROFILE_TOO_SMALL` says "this model cannot hold a turn".
        assertEquals(
            ContextPolicy.PROFILE_INVALID,
            ContextPolicy.failureCode(smallProfile(), ContextTuning(compressionThreshold = 1.0)),
        )
        assertEquals(
            ContextPolicy.PROFILE_INVALID,
            ContextPolicy.failureCode(smallProfile(), ContextTuning(keepRecentTurns = 0)),
        )
    }
}
