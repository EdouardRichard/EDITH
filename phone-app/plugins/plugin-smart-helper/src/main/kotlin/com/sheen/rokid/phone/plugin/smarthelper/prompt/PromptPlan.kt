package com.sheen.rokid.phone.plugin.smarthelper.prompt

import java.security.MessageDigest
import kotlin.text.Charsets.UTF_8

/**
 * The immutable prompt plan pinned for one session (spec §4.1).
 *
 * [version] is the catalog semver, [sections] keeps the authored order, [systemText] is what the
 * model receives as system instructions, and [hash] fingerprints the ordered section texts so a
 * `P3_PROMPT` log line can prove which prompt actually ran without logging its prose.
 *
 * The plan is intentionally a value object: a version or device-profile change produces a new plan
 * (and a new session), never an in-place mutation of a live one.
 */
data class PromptPlan(
    val version: String,
    val sections: List<PromptSection>,
    val systemText: String,
    val hash: String,
) {
    companion object {
        private const val SECTION_SEPARATOR = "\n"

        /**
         * Deterministic SHA-256, lowercase hex, over the section texts in [sections] order joined by
         * a single newline. Section ids and kinds are deliberately not hashed: two plans with the same
         * visible system text must fingerprint the same, and any prose edit must change the hash.
         */
        fun hashOf(sections: List<PromptSection>): String {
            val orderedText = sections.joinToString(SECTION_SEPARATOR) { it.text }
            val digest = MessageDigest.getInstance("SHA-256").digest(orderedText.toByteArray(UTF_8))
            return buildString(digest.size * 2) {
                for (byte in digest) {
                    append("%02x".format(byte.toInt() and 0xff))
                }
            }
        }
    }
}
