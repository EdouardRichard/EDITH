package com.sheen.rokid.agent

import kotlin.math.ceil
import kotlin.math.max

/**
 * Dependency-free prompt-size estimate (0.3 design §5.2), shared by the context budgeter, the turn trimmer and the
 * plugin. It is not a tokenizer: it has to be stable and conservative enough for pre-send budget checks.
 *
 * Weights: ASCII code points count one token per four; every non-ASCII code point (CJK Unified ideographs, Hiragana,
 * Katakana, Hangul, emoji and any other script) counts one token. A non-empty text therefore estimates at least one
 * token; an empty text estimates zero.
 */
object TokenMeter {

    fun estimate(text: String): Int {
        if (text.isEmpty()) return 0

        var asciiCodePoints = 0
        var nonAsciiCodePoints = 0
        var index = 0
        while (index < text.length) {
            val codePoint = text.codePointAt(index)
            if (codePoint < ASCII_END) asciiCodePoints++ else nonAsciiCodePoints++
            index += Character.charCount(codePoint)
        }

        val weighted = asciiCodePoints.toDouble() / ASCII_CODE_POINTS_PER_TOKEN + nonAsciiCodePoints
        return max(1, ceil(weighted).toInt())
    }

    private const val ASCII_END = 0x80
    private const val ASCII_CODE_POINTS_PER_TOKEN = 4.0
}
