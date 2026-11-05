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
     */
    fun reserve(profile: ModelProfile): Int =
        if (active(profile)) officialDefault(profile.thinkingEffort) else profile.maxOutputTokens
}
