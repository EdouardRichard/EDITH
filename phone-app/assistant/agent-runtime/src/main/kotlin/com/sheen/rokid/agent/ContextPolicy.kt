package com.sheen.rokid.agent

import com.sheen.rokid.plugin.ModelProfile
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Ratio and count tuning for [ContextPolicy] (design §5.2). It deliberately holds no token sizes: every limit is
 * derived from the selected [ModelProfile], so an 8K local model and a 1M-window model get different budgets.
 */
data class ContextTuning(
    val compressionThreshold: Double = 0.75,
    val hardMarginRatio: Double = 0.10,
    val keepRecentTurns: Int = 4,
    val summaryRatio: Double = 0.02,
    val toolResultRetention: Int = 4,
)

/**
 * The context limits derived from one [ModelProfile] (design §5.2 and §6.3).
 *
 * [from] never clamps and never falls back to a fixed budget. A profile or tuning that cannot produce
 * `0 < softLimit <= hardLimit <= hardInputLimit` throws [IllegalArgumentException]; callers treat that as a
 * configuration error and refuse the turn.
 */
data class ContextPolicy(
    val hardInputLimit: Int,
    val softLimit: Int,
    val hardLimit: Int,
    val compressionThreshold: Double,
    val hardMargin: Int,
    val keepRecentTurns: Int,
    val summaryMaxTokens: Int,
    val toolResultRetention: Int,
) {
    companion object {
        /** The profile lookup answered nothing: there is no configured model to derive a budget from. */
        const val PROFILE_MISSING = "PROFILE_MISSING"

        /** The profile or the policy tuning is itself invalid, so no budget may be derived from it. */
        const val PROFILE_INVALID = "PROFILE_INVALID"

        /** The profile's window is too small to produce `0 < softLimit <= hardLimit`. */
        const val PROFILE_TOO_SMALL = "PROFILE_TOO_SMALL"

        /** The fixed prompt (instructions + request-scoped tail + this turn's input) cannot fit the hard input. */
        const val PROMPT_EXCEEDS_BUDGET = "PROMPT_EXCEEDS_BUDGET"

        /**
         * The distinct fail-closed code for [profile]/[tuning], or `null` when [from] would succeed.
         *
         * The classification is structural, never a parse of the exception text:
         * - invalid surface values or tuning (a non-positive window/safety/output, arithmetic already rejected by
         *   `ModelProfile`, a threshold outside `(0,1)`, fewer than one kept turn) → [PROFILE_INVALID];
         * - a valid surface whose derived limits cannot satisfy the policy invariant → [PROFILE_TOO_SMALL].
         *
         * A caller that must refuse a turn uses this instead of `runCatching { ... }.getOrNull()`, because the latter
         * erases *why* the policy does not exist and forces the generic `PROFILE_MISSING` onto a configured profile.
         */
        fun failureCode(profile: ModelProfile, tuning: ContextTuning = ContextTuning()): String? {
            val invalidNumbers = profile.usableWindowTokens <= 0 ||
                profile.inputBudgetTokens <= 0 ||
                profile.maxOutputTokens <= 0 ||
                profile.safetyTokens <= 0 ||
                profile.inputBudgetTokens.toLong() + profile.maxOutputTokens + profile.safetyTokens >
                profile.usableWindowTokens.toLong()
            val invalidTuning = tuning.compressionThreshold <= 0.0 ||
                tuning.compressionThreshold >= 1.0 ||
                tuning.keepRecentTurns < 1
            if (invalidNumbers || invalidTuning) return PROFILE_INVALID
            return runCatching { from(profile, tuning) }.fold(
                onSuccess = { null },
                onFailure = { PROFILE_TOO_SMALL },
            )
        }

        fun from(profile: ModelProfile, tuning: ContextTuning = ContextTuning()): ContextPolicy {
            require(tuning.compressionThreshold > 0.0 && tuning.compressionThreshold < 1.0) {
                "compressionThreshold must be in (0, 1): ${tuning.compressionThreshold}"
            }
            require(tuning.keepRecentTurns >= 1) {
                "keepRecentTurns must be at least 1: ${tuning.keepRecentTurns}"
            }

            // Long arithmetic keeps the comparison exact before the result is narrowed back to Int.
            val windowRemainder = profile.usableWindowTokens.toLong() - profile.maxOutputTokens - profile.safetyTokens
            val hardInputLimit = min(profile.inputBudgetTokens.toLong(), windowRemainder).toInt()
            val softLimit = floor(hardInputLimit * tuning.compressionThreshold).toInt()
            val hardMargin = max(1, floor(hardInputLimit * tuning.hardMarginRatio).toInt())
            val hardLimit = hardInputLimit - hardMargin
            val summaryMaxTokens = max(1, ceil(hardInputLimit * tuning.summaryRatio).toInt())

            require(softLimit > 0 && softLimit <= hardLimit && hardLimit <= hardInputLimit) {
                "invalid context limits: softLimit=$softLimit hardLimit=$hardLimit hardInputLimit=$hardInputLimit"
            }
            require(summaryMaxTokens > 0) {
                "summaryMaxTokens must be positive: $summaryMaxTokens"
            }

            return ContextPolicy(
                hardInputLimit = hardInputLimit,
                softLimit = softLimit,
                hardLimit = hardLimit,
                compressionThreshold = tuning.compressionThreshold,
                hardMargin = hardMargin,
                keepRecentTurns = tuning.keepRecentTurns,
                summaryMaxTokens = summaryMaxTokens,
                toolResultRetention = tuning.toolResultRetention,
            )
        }
    }
}
