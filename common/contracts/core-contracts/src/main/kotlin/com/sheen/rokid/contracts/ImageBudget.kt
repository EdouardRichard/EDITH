package com.sheen.rokid.contracts

/**
 * The one price of an image in the context budget (0.25 spec §4.9, ruling R68).
 *
 * The budget's unit is UTF-8 bytes, the unit `ModelProfile.inputBudgetTokens` is already treated as. DeepSeek publishes
 * roughly 1024 tokens per image; this project does not invent a token-to-byte formula, so that published ceiling is
 * used directly as the coarse per-image byte unit. It is deliberately **one** shared constant: `ContextBudgeter`
 * (which sends) and `TurnTrimmer` (which pre-trims) must not be able to disagree about what an image costs.
 *
 * Long (not Int) because it is multiplied into the same `Long` arithmetic the budgeter already uses; the values are
 * small but `Math.multiplyExact`/`Math.addExact` must see the same type on both sides.
 */
object ImageBudget {
    const val UNITS_PER_IMAGE: Long = 1024L
}
