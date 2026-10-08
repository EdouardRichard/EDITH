package edith.host

import edith.contracts.Assistance

class HintPolicy(private val maxCodePoints: Int = 120) {
    init { require(maxCodePoints > 0) }
    fun accepts(hint: Assistance): Boolean = hint.optionalSpeech == null &&
        hint.text.codePointCount(0, hint.text.length) <= maxCodePoints &&
        hint.text.indices.none { index ->
            val c = hint.text[index]
            (c.isHighSurrogate() && (index + 1 >= hint.text.length || !hint.text[index + 1].isLowSurrogate())) ||
                (c.isLowSurrogate() && (index == 0 || !hint.text[index - 1].isHighSurrogate()))
        }
}
