package com.sheen.rokid.phone.plugin.smarthelper.memory

/**
 * Phase 4 §3.9 6c: pure-template memory normalizer.
 *
 * Converts a user's original first-person speech into a third-person factual statement suitable for safe injection
 * into LLM context. This is a **pure template** — zero model calls, zero new dependencies.
 *
 * Templates by [MemoryKind]:
 * - [MemoryKind.PREFERENCE]: strips first-person prefix ("我" / "我也"), prepends "用户曾表示"
 * - [MemoryKind.FACT]: strips possessive prefix ("我的"), prepends "用户曾告知"
 * - [MemoryKind.ROUTINE]: prepends "用户曾有习惯："
 *
 * Mood sanitization (AC-9): trailing question particles ("吗", "呢", "么", "嘛") and second-person interrogative
 * prefixes ("你能", "你能不能", "你能否") are stripped so the output reads as a statement, not a question or command.
 *
 * Tag escaping (§3.9 / AC-9): ASCII `<` and `>` are replaced with fullwidth equivalents `＜` `＞` to prevent
 * boundary-tag forgery in the rendered injection block.
 *
 * Authority words ("已核实", "可靠", "权威", "确认", "验证") are never introduced by any template.
 */
fun normalize(text: String, kind: MemoryKind): String {
    val stripped = when (kind) {
        MemoryKind.PREFERENCE -> text.removePrefix("我也").removePrefix("我")
        MemoryKind.FACT -> text.removePrefix("我的")
        MemoryKind.ROUTINE -> text
    }
    val sanitized = stripQuestionMood(stripped)
    val framed = when (kind) {
        MemoryKind.PREFERENCE -> "用户曾表示$sanitized"
        MemoryKind.FACT -> "用户曾告知$sanitized"
        MemoryKind.ROUTINE -> "用户曾有习惯：$sanitized"
    }
    return escapeTags(framed)
}

/**
 * Strips question mood markers so the normalized text reads as a statement.
 *
 * Handles:
 * - Trailing question particles: 吗, 呢, 么, 嘛 (with optional trailing punctuation)
 * - Second-person interrogative prefixes: 你能不能, 你能否, 你能
 */
private fun stripQuestionMood(text: String): String {
    var result = text
    // Strip second-person interrogative prefixes (longest match first)
    result = result.removePrefix("你能不能").removePrefix("你能否").removePrefix("你能")
    // Strip trailing question particles (with optional trailing ?/？)
    result = result.replace(Regex("[吗呢么嘛][?？]?$"), "")
    return result
}

/**
 * Replaces ASCII angle brackets with fullwidth equivalents so that user text cannot forge XML-like boundary tags
 * such as `<reference_context>` or `</reference_context>`.
 */
private fun escapeTags(text: String): String =
    text.replace('<', '＜').replace('>', '＞')
