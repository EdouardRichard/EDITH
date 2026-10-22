package com.sheen.rokid.phone.plugin.smarthelper.prompt

/**
 * Strict `{{name}}` substitution for prompt templates (spec §4.1).
 *
 * This is deliberately not a template engine. It substitutes exactly the placeholders whose names
 * are both allowed for the current section and supplied with a value. Everything else fails loudly:
 *
 * - a placeholder with no supplied value is a missing slot;
 * - a supplied value whose name is not allowed is an extra slot;
 * - a placeholder outside the allowed set, a malformed `{{ name }}`, or a stray `{{`/`}}` is an
 *   unresolved slot.
 *
 * Failing instead of silently preserving a token matters because an unrendered placeholder in a
 * system prompt is a prompt-injection surface. Literal template text outside placeholders is copied
 * byte-for-byte, and values are stripped of control characters and capped before insertion.
 */
object PromptRenderer {

    /** Maximum value length in Unicode code points, so a cap never splits a surrogate pair. */
    private const val MAX_VALUE_CODE_POINTS = 500

    /** The only accepted placeholder shape: `{{name}}` with an ASCII identifier name. */
    private val PLACEHOLDER = Regex("""\{\{([A-Za-z_][A-Za-z0-9_]*)\}\}""")

    /**
     * Renders [template] with [values], permitting only names in [allowedSlots].
     *
     * [maxValueCodePoints] is the per-value cap. The default preserves every pre-existing caller byte for byte; a
     * caller whose slot legitimately carries a larger model-facing block (the perception tail, 0.3 Phase 2 Task 9)
     * passes its own policy-derived bound instead of growing the default.
     *
     * @throws IllegalArgumentException if any value names a slot outside [allowedSlots], if a
     * placeholder has no supplied value, if a placeholder names a slot outside [allowedSlots], or
     * if the template contains a placeholder that is not a strict `{{name}}` token.
     */
    fun render(
        template: String,
        values: Map<String, String>,
        allowedSlots: Set<String>,
        maxValueCodePoints: Int = MAX_VALUE_CODE_POINTS,
    ): String {
        require(maxValueCodePoints >= 0) {
            "maxValueCodePoints must not be negative, was $maxValueCodePoints"
        }
        val disallowed = values.keys - allowedSlots
        require(disallowed.isEmpty()) {
            "values supplied for slots that are not allowed: ${disallowed.sorted().joinToString(", ")}"
        }

        val sanitized = values.mapValues { (_, value) -> sanitize(value, maxValueCodePoints) }
        val output = StringBuilder(template.length)
        var cursor = 0

        for (match in PLACEHOLDER.findAll(template)) {
            val literal = template.substring(cursor, match.range.first)
            rejectUnresolved(literal)
            output.append(literal)

            val name = match.groupValues[1]
            require(name in allowedSlots) { "placeholder '{{$name}}' is not an allowed slot" }
            val value = sanitized[name]
                ?: throw IllegalArgumentException("no value supplied for placeholder '{{$name}}'")
            output.append(value)

            cursor = match.range.last + 1
        }

        val tail = template.substring(cursor)
        rejectUnresolved(tail)
        output.append(tail)

        return output.toString()
    }

    /** Any `{{`/`}}` that is not part of a matched `{{name}}` token is an unresolved placeholder. */
    private fun rejectUnresolved(literal: String) {
        require("{{" !in literal && "}}" !in literal) {
            "template contains a placeholder that is not a strict '{{name}}' token: '$literal'"
        }
    }

    /**
     * Removes the characters that could rewrite the prompt layout: ISO controls, bidi overrides and the Unicode
     * line/paragraph separators. Shared with the perception renderer, which must count the same characters before it
     * applies its own deliberate truncation (0.3 Phase 2 Task 9 review I3).
     */
    internal fun stripControlCharacters(value: String): String {
        val cleaned = StringBuilder(value.length)
        var index = 0
        while (index < value.length) {
            val codePoint = value.codePointAt(index)
            if (!isControl(codePoint)) cleaned.appendCodePoint(codePoint)
            index += Character.charCount(codePoint)
        }
        return cleaned.toString()
    }

    /**
     * Strips the unsafe characters and caps the result at [maxValueCodePoints] code points. Bidi overrides are removed
     * too: they are not ISO controls but they can visually reorder surrounding prompt text.
     */
    private fun sanitize(value: String, maxValueCodePoints: Int): String {
        val cleaned = stripControlCharacters(value)

        if (cleaned.length <= maxValueCodePoints) return cleaned

        var codePoints = 0
        var end = 0
        while (end < cleaned.length && codePoints < maxValueCodePoints) {
            end += Character.charCount(cleaned.codePointAt(end))
            codePoints++
        }
        return cleaned.substring(0, end)
    }

    private fun isControl(codePoint: Int): Boolean {
        if (Character.isISOControl(codePoint)) return true
        if (codePoint in 0x202A..0x202E || codePoint in 0x2066..0x2069) return true
        return when (Character.getType(codePoint)) {
            Character.LINE_SEPARATOR.toInt(),
            Character.PARAGRAPH_SEPARATOR.toInt(),
            -> true
            else -> false
        }
    }
}
