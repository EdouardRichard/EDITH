package com.sheen.rokid.contracts

/**
 * The one price of an image in the context budget (0.25 spec §4.9, ruling R68).
 *
 * The budget's unit is a **budget unit** — token units after 0.3 design §5.2, when text is priced by `TokenMeter`.
 * DeepSeek publishes roughly 1024 tokens per image; rather than inventing a conversion, this project uses that
 * published ceiling directly as the coarse per-image budget unit. It is deliberately **one** shared constant:
 * `ContextBudgeter` (which sends) and `TurnTrimmer` (which pre-trims) must not be able to disagree about what an image
 * costs.
 *
 * Long (not Int) because it is multiplied into the same `Long` arithmetic the budgeter already uses; the values are
 * small but `Math.multiplyExact`/`Math.addExact` must see the same type on both sides.
 */
object ImageBudget {
    const val UNITS_PER_IMAGE: Long = 1024L
}
