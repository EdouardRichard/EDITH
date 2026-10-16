package edith.plugins.smart.memory

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
    val framed = when (kind) {
        MemoryKind.PREFERENCE -> "用户曾表示$stripped"
        MemoryKind.FACT -> "用户曾告知$stripped"
        MemoryKind.ROUTINE -> "用户曾有习惯：$stripped"
    }
    return escapeTags(framed)
}

/**
 * Replaces ASCII angle brackets with fullwidth equivalents so that user text cannot forge XML-like boundary tags
 * such as `<reference_context>` or `</reference_context>`.
 */
private fun escapeTags(text: String): String =
    text.replace('<', '＜').replace('>', '＞')
