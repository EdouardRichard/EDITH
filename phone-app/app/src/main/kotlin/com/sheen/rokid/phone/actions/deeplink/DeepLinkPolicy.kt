package com.sheen.rokid.phone.actions.deeplink

import java.net.URI
import java.net.URISyntaxException

/**
 * 0.5.4 Task 14 (spec §6.4): whether one `deep_link` call is launchable at all.
 *
 * [Valid] carries the original URI and the normalized package; [Invalid.code] is the stable code the model sees. The
 * policy is pure on purpose - every rule (absolute URI, length, control characters, blocked pseudo-schemes, custom
 * schemes requiring a package, http(s) userinfo) is testable without Android or a composition.
 */
sealed interface DeepLinkRequest {
    data class Valid(val uri: String, val packageName: String?) : DeepLinkRequest
    data class Invalid(val code: String) : DeepLinkRequest
}

/**
 * The only audit-safe facts of a deep link: the scheme and (when present) the host.
 *
 * The path and the query never appear here, so a URI that carries a token cannot reach a log line through this type.
 */
data class DeepLinkAuditTarget(val scheme: String, val host: String?)

/**
 * The pure URI rule of `deep_link` (spec §6.4).
 *
 * Ordering matters:
 * 1. blank/overlong/control-character strings are [INVALID_URI] before anything else is parsed;
 * 2. the raw scheme prefix is classified **before** `java.net.URI` parses the rest, so a blocked pseudo-scheme answers
 *    [SCHEME_NOT_ALLOWED] even when its body is not RFC-3986 (`javascript:alert(1)` is the motivating case);
 * 3. only then is the URI parsed for absoluteness and http(s) userinfo.
 *
 * A custom scheme (anything other than `http`/`https`) **must** name a package: without one Android would let any
 * installed app claim the link, so the request is refused as [PACKAGE_REQUIRED] instead of launched.
 */
object DeepLinkPolicy {

    /** Spec §6.4: the longest accepted URI. */
    const val MAX_URI_LENGTH = 2048

    const val INVALID_URI = "INVALID_URI"
    const val SCHEME_NOT_ALLOWED = "SCHEME_NOT_ALLOWED"
    const val PACKAGE_REQUIRED = "PACKAGE_REQUIRED"

    /** Spec §6.4's rejected pseudo-schemes: file/content/javascript/data/intent/android-app/tel/sms/smsto/mailto/package. */
    private val BLOCKED_SCHEMES = setOf(
        "file",
        "content",
        "javascript",
        "data",
        "intent",
        "android-app",
        "tel",
        "sms",
        "smsto",
        "mailto",
        "package",
    )

    /** The scheme prefix of an absolute URI; the body may still be malformed and is checked by [URI] afterwards. */
    private val SCHEME = Regex("^([A-Za-z][A-Za-z0-9+.-]*):")

    /**
     * Validates one model-supplied URI and package.
     *
     * [packageName] is trimmed; a blank value is treated as absent, so a custom scheme with `"  "` is still
     * [PACKAGE_REQUIRED] rather than being launched unbound.
     */
    fun validate(uri: String, packageName: String?): DeepLinkRequest {
        val requestedPackage = packageName?.trim()?.takeIf { it.isNotEmpty() }
        if (uri.isEmpty() || uri.length > MAX_URI_LENGTH || hasControlCharacter(uri)) {
            return DeepLinkRequest.Invalid(INVALID_URI)
        }
        val rawScheme = SCHEME.find(uri)?.groupValues?.get(1) ?: return DeepLinkRequest.Invalid(INVALID_URI)
        val scheme = rawScheme.lowercase()
        if (scheme in BLOCKED_SCHEMES) return DeepLinkRequest.Invalid(SCHEME_NOT_ALLOWED)

        val parsed = try {
            URI(uri)
        } catch (_: URISyntaxException) {
            return DeepLinkRequest.Invalid(INVALID_URI)
        }
        val parsedScheme = parsed.scheme?.lowercase()
        if (!parsed.isAbsolute || parsedScheme.isNullOrEmpty() || parsedScheme != scheme) {
            return DeepLinkRequest.Invalid(INVALID_URI)
        }
        val http = isHttp(scheme)
        if (!http) {
            if (requestedPackage == null) return DeepLinkRequest.Invalid(PACKAGE_REQUIRED)
            return DeepLinkRequest.Valid(uri, requestedPackage)
        }
        // Spec §6.4: an http(s) URI carrying userinfo is refused; credentials must never be smuggled through a link.
        if (parsed.rawUserInfo != null) return DeepLinkRequest.Invalid(INVALID_URI)
        return DeepLinkRequest.Valid(uri, requestedPackage)
    }

    /**
     * The audit/log projection of a URI: scheme and host only.
     *
     * For a custom scheme with a hierarchical body (`imeituan://www.meituan.com/...`) the host is still a host; for an
     * opaque body (`imeituan:foo`) it is `null` and only the scheme is recorded. The path/query are never returned.
     */
    fun auditTarget(uri: String): DeepLinkAuditTarget {
        val parsed = runCatching { URI(uri) }.getOrNull()
        return DeepLinkAuditTarget(
            scheme = parsed?.scheme?.lowercase().orEmpty(),
            host = parsed?.host?.takeIf { it.isNotBlank() },
        )
    }

    /** `http`/`https` are the only schemes allowed without a package. */
    fun isHttp(scheme: String): Boolean = scheme == "http" || scheme == "https"

    private fun hasControlCharacter(value: String): Boolean =
        value.any { character -> character.code < 0x20 || character.code == 0x7F }
}
