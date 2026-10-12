package edith.host

import edith.contracts.Assistance

class HintPolicy(private val maxCodePoints: Int = 120) {
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
}
