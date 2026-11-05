package com.sheen.rokid.model

import com.sheen.rokid.plugin.ModelProfile
import com.sheen.rokid.plugin.ThinkingEffort

/**
 * 0.53 design §T5: the output allowance a thinking turn needs, in one place.
 *
 * DeepSeek charges the chain of thought against the same `max_tokens`/`max_output_tokens` budget as the answer. The
 * 0.52 measurement was concrete: at a small turn cap the deliberation consumed the whole allowance and the tool call
 * never arrived (`incomplete/max_output_tokens`). So when thinking is active the wire value is higher than the
 * profile's own reservation, and the send-time [ContextBudgeter] must reserve the **same** value — two different
 * formulas would let the budgeter prepare a request the adapter then overflows, or vice versa.
 *
 * The floor is capped by what the window can actually spare (`usableWindowTokens - safetyTokens - [MIN_INPUT_TOKENS]`)
 * so an active profile still leaves room for a minimum useful prompt. It never falls below `profile.maxOutputTokens`:
 * the profile's declared allowance is a lower bound, not something thinking may shrink. A profile whose thinking is not
 * active returns exactly its own reservation, i.e. the pre-0.53 behaviour.
 */
object ThinkingOutputBudget {
    /**
     * The prompt room the floor must never eat: even a maximal thinking reservation leaves at least this many input
     * token units for instructions, history and the current turn.
     */
    const val MIN_INPUT_TOKENS = 8_192

    /** LOW and HIGH share a floor; MAX gets more room for the longer deliberation. */
    private const val LOW_HIGH_FLOOR = 32_768
    private const val MAX_FLOOR = 65_536

    /** True when this profile asked for DeepSeek thinking and the model can do it (spec §5.7 `thinkingActive`). */
    fun active(profile: ModelProfile): Boolean = profile.thinkingSupported && profile.thinkingEnabled

    /**
     * The output token reservation for this profile: its declared `maxOutputTokens` when thinking is off, and the
     * level floor (capped by the spare window, never below `maxOutputTokens`) when thinking is active.
     */
    fun reserve(profile: ModelProfile): Int {
        if (!active(profile)) return profile.maxOutputTokens
        val floor = when (profile.thinkingEffort) {
            ThinkingEffort.LOW, ThinkingEffort.HIGH -> LOW_HIGH_FLOOR
            ThinkingEffort.MAX -> MAX_FLOOR
        }
        val spare = profile.usableWindowTokens.toLong() - profile.safetyTokens - MIN_INPUT_TOKENS
        val capped = minOf(floor.toLong(), spare).toInt()
        return maxOf(profile.maxOutputTokens, capped)
    }
}
