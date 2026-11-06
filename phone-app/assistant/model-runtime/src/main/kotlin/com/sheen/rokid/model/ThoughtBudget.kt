package com.sheen.rokid.model

import com.sheen.rokid.plugin.ModelProfile
import com.sheen.rokid.plugin.ThinkingEffort

/**
 * 0.53 design (revised) §T5: the provider's **official** output allowance for a thinking turn.
 *
 * The owner ruling (B) replaced the earlier invented 32K floor: the app does not send `max_tokens`/`max_output_tokens`
 * while thinking is on, so the provider applies its documented default (LOW/HIGH = 64K, MAX = 128K). The send-time
 * [ContextBudgeter] reserves the same documented value so the two stages cannot disagree; it is the only reason this
 * constant exists in this module.
 *
 * `active` is the **user's switch alone** — `profile.thinkingEnabled`. The system does not guess a model family
 * (owner ruling A); whether a provider actually honours the switch is the provider's verdict, surfaced as an HTTP error.
 */
object ThoughtBudget {
    /** Official default output allowance for a LOW/HIGH thinking turn. */
    const val DEFAULT_THINKING_TOKENS = 65_536

    /** Official default output allowance for a MAX thinking turn. */
    const val DEFAULT_MAX_EFFORT_TOKENS = 131_072

    /**
     * Review round 1: the prompt room the send-time reservation must never eat. A 131 072-token window with the MAX
     * official default would otherwise leave a negative input limit, so **every** thinking request would fail locally
     * before the provider ever saw it. The reservation is capped by `usableWindow - safety - MIN_INPUT_TOKENS`.
     */
    const val MIN_INPUT_TOKENS = 8_192

    /** True when the user asked for thinking. No capability or family inference is applied. */
    fun active(profile: ModelProfile): Boolean = profile.thinkingEnabled

    /** The documented output default for one effort level. */
    fun officialDefault(effort: ThinkingEffort): Int = when (effort) {
        ThinkingEffort.LOW, ThinkingEffort.HIGH -> DEFAULT_THINKING_TOKENS
        ThinkingEffort.MAX -> DEFAULT_MAX_EFFORT_TOKENS
    }

    /**
     * The output reservation the send-time budget uses: the official default when the switch is on (the adapters send
     * no cap, so the provider applies it), otherwise the profile's declared allowance — the pre-0.53 behaviour.
     *
     * The official value is capped so at least [MIN_INPUT_TOKENS] worth of prompt fits in the profile's window, and the
     * profile's own allowance stays the floor: the guarantee is "never reserve more than the window can spare", never
     * "reserve less than the profile declared".
     */
    fun reserve(profile: ModelProfile): Int {
        if (!active(profile)) return profile.maxOutputTokens
        val spare = profile.usableWindowTokens.toLong() - profile.safetyTokens - MIN_INPUT_TOKENS
        val capped = minOf(officialDefault(profile.thinkingEffort).toLong(), spare).toInt()
        return maxOf(profile.maxOutputTokens, capped)
    }
}
