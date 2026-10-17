package com.sheen.rokid.phone.plugin.smarthelper.audit

/**
 * The one thing a plugin may log about a failure that escaped a turn: a **shape-validated code**, never a payload.
 *
 * `P3_RUN` used to carry the exception's class name only (`ConfigurationRejected`, `CapabilityMissing`), which is safe
 * but says nothing about *which* rejection it was: `ContractError.ConfigurationRejected` alone is thrown for a profile
 * revision mismatch, an invalid endpoint, an over-budget explicit context and an unavailable credential. A device turn
 * that failed logged `error=ConfigurationRejected` and there was no way to tell those apart without a second build.
 *
 * The contract here is deliberately narrow, because the message of a rejected configuration is exactly where a URL, a
 * profile id or a file path would leak:
 *
 * - a token must start with a letter and be at most [MAX_TOKEN_CHARS] long, so a base64 blob or a path cannot survive;
 * - the message is cut at the first `:` (a `reason: detail` shape keeps the reason), then at [MAX_CODE_CHARS];
 * - anything carrying a URL scheme, an `@`, a `/` or a `\` is dropped entirely.
 *
 * The result is bounded, greppable and comparable between runs; the caller logs it **instead of** the message.
 */
internal fun Throwable.safeFailureCode(fallback: String): String {
    val message = message.orEmpty()
    // A URL is checked on the whole message: `https://host` splits at the first `:` into "https", which would
    // otherwise look exactly like a code.
    if (message.contains("://")) return fallback
    // The `:` cut comes next: a real message is a `reason` and then a detail, and the detail is where the payload
    // lives. The checks below then run on the reason alone, so a detail that happens to contain `=` or a path cannot
    // suppress an otherwise usable code.
    val head = message.substringBefore(':').trim()
    if (head.isEmpty()) return fallback
    if (head.any { it == '/' || it == '\\' || it == '@' || it == '=' }) return fallback
    val tokens = head.split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (tokens.isEmpty()) return fallback
    // Every token must be an ordinary **word**: a leading letter (or a CJK character) and nothing but letters,
    // digits and CJK after it. That is what refuses a credential-shaped token (`sk-ABC…`), a base64 blob and a
    // dotted host in one rule, while the messages this codebase actually throws are plain reason phrases.
    if (tokens.any { token -> token.length > MAX_TOKEN_CHARS || !WORD.matches(token) }) return fallback
    val code = tokens.joinToString(" ")
    return if (code.length <= MAX_CODE_CHARS) code else fallback
}

private val WORD = Regex("^[\\p{L}][\\p{L}\\p{N}]*$")
private const val MAX_TOKEN_CHARS = 40
private const val MAX_CODE_CHARS = 80
