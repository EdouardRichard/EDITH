package com.sheen.rokid.host

import com.sheen.rokid.contracts.Assistance

/**
 * The phone's hint bound (P3.2-CLIP and the owner's 2026-10-14 ruling).
 *
 * A hint taller than the glasses' six-line window is **scrolled** through it (`HintAutoScroll`), so this policy exists
 * to keep the wire from carrying a document — not to refuse a notification that needs two or three screens. The
 * reported defect was exactly that: the phone refused a long notification, the turn said nothing at all, and the
 * scrolling the glasses already implement never got a chance to run.
 */
class HintPolicy(private val maxCodePoints: Int = DEFAULT_MAX_CODE_POINTS) {
    init { require(maxCodePoints > 0) }
    // Spec §S2: structural eligibility is independent of speech capability. A hint with optionalSpeech is accepted
    // as long as its text fits the budget and has well-formed surrogate pairs; whether it actually gets spoken is
    // decided later by the speech capability gate (player present AND user switch on).
    fun accepts(hint: Assistance): Boolean =
        hint.text.codePointCount(0, hint.text.length) <= maxCodePoints &&
        hint.text.indices.none { index ->
            val c = hint.text[index]
            (c.isHighSurrogate() && (index + 1 >= hint.text.length || !hint.text[index + 1].isLowSurrogate())) ||
                (c.isLowSurrogate() && (index == 0 || !hint.text[index - 1].isHighSurrogate()))
        }

    companion object {
        /**
         * How long a hint may be before the phone refuses it (codepoints).
         *
         * Far past a single window on purpose: 400 codepoints is about 35 lines at the measured 12 characters per
         * line, i.e. roughly five screens of scrolling, while still being a sentence or a short paragraph rather than
         * a document. The glasses' `HintWireCodec` bound is kept equal to this one so the two sides cannot disagree
         * about what a hint may contain.
         */
        const val DEFAULT_MAX_CODE_POINTS = 400
    }
}
