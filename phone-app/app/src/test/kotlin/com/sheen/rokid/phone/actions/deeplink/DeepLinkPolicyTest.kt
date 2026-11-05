package com.sheen.rokid.phone.actions.deeplink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.5.4 Task 14 (spec §6.4): the pure URI rule of `deep_link`.
 *
 * The policy is the only place that decides whether a string is a launchable deep link, so these tests pin the exact
 * codes the model sees: a rejected pseudo-scheme is `SCHEME_NOT_ALLOWED` (never a silent fallback), a custom scheme
 * without a package is `PACKAGE_REQUIRED`, and every malformed/unsafe string - userinfo, control characters, an
 * overlong URI - is `INVALID_URI`. http(s) is the one scheme family allowed without a package.
 */
class DeepLinkPolicyTest {

    @Test
    fun `a custom scheme with a package is valid`() {
        val uri = "imeituan://www.meituan.com/restaurant?id=1"

        assertEquals(
            DeepLinkRequest.Valid(uri, "com.sankuai.meituan"),
            DeepLinkPolicy.validate(uri, "com.sankuai.meituan"),
        )
    }

    @Test
    fun `a custom scheme without a package is PACKAGE_REQUIRED`() {
        assertEquals(
            DeepLinkRequest.Invalid("PACKAGE_REQUIRED"),
            DeepLinkPolicy.validate("imeituan://www.meituan.com/restaurant", null),
        )
    }

    @Test
    fun `a custom scheme with a blank package is PACKAGE_REQUIRED`() {
        assertEquals(
            DeepLinkRequest.Invalid("PACKAGE_REQUIRED"),
            DeepLinkPolicy.validate("imeituan://www.meituan.com/restaurant", "   "),
        )
    }

    @Test
    fun `the rejected pseudo schemes are SCHEME_NOT_ALLOWED even with a package`() {
        val rejected = listOf(
            "file:///sdcard/picture.jpg",
            "content://com.example.provider/item/1",
            "javascript:alert(1)",
            "data:text/html;base64,PHNjcmlwdD4=",
            "intent://scan/#Intent;scheme=zxing;package=com.example;end",
            "android-app://com.example.app",
            "tel:10086",
            "sms:10086",
            "smsto:10086",
            "mailto:someone@example.com",
            "package:com.example.app",
        )

        rejected.forEach { uri ->
            assertEquals(
                "the rejected pseudo scheme must not become a launch: $uri",
                DeepLinkRequest.Invalid("SCHEME_NOT_ALLOWED"),
                DeepLinkPolicy.validate(uri, "com.example.app"),
            )
        }
    }

    @Test
    fun `http and https are valid with or without a package`() {
        assertEquals(
            DeepLinkRequest.Valid("https://example.com/a?b=1", null),
            DeepLinkPolicy.validate("https://example.com/a?b=1", null),
        )
        assertEquals(
            DeepLinkRequest.Valid("http://example.com/a", "com.example.app"),
            DeepLinkPolicy.validate("http://example.com/a", "com.example.app"),
        )
    }

    @Test
    fun `the scheme comparison is case insensitive`() {
        assertEquals(
            DeepLinkRequest.Invalid("SCHEME_NOT_ALLOWED"),
            DeepLinkPolicy.validate("TEL:10086", null),
        )
        assertTrue(DeepLinkPolicy.validate("HTTPS://example.com/a", null) is DeepLinkRequest.Valid)
    }

    @Test
    fun `an empty relative or malformed URI is INVALID_URI`() {
        listOf(
            "",
            "   ",
            "example.com/path",
            "://example.com",
            "https://exa mple.com/a",
            "not a uri",
        ).forEach { uri ->
            assertEquals(
                "not an absolute URI: '$uri'",
                DeepLinkRequest.Invalid("INVALID_URI"),
                DeepLinkPolicy.validate(uri, null),
            )
        }
    }

    @Test
    fun `http userinfo is INVALID_URI`() {
        listOf(
            "https://user:pass@example.com/a",
            "http://user@example.com/a",
        ).forEach { uri ->
            assertEquals(
                "userinfo must not be accepted: $uri",
                DeepLinkRequest.Invalid("INVALID_URI"),
                DeepLinkPolicy.validate(uri, null),
            )
        }
    }

    @Test
    fun `control characters are INVALID_URI`() {
        listOf(
            "https://example.com/a\u0000b",
            "https://example.com/a\nb",
            "https://example.com/a\tb",
            "imeituan://www.meituan.com/a\u0007b",
        ).forEach { uri ->
            assertEquals(
                "a control character must be refused: ${uri.toByteArray().toList()}",
                DeepLinkRequest.Invalid("INVALID_URI"),
                DeepLinkPolicy.validate(uri, "com.sankuai.meituan"),
            )
        }
    }

    @Test
    fun `the length boundary is 2048 characters`() {
        val prefix = "https://e.co/"
        val longest = prefix + "a".repeat(DeepLinkPolicy.MAX_URI_LENGTH - prefix.length)
        assertEquals(DeepLinkPolicy.MAX_URI_LENGTH, longest.length)

        assertTrue(
            "a URI at the limit is still valid",
            DeepLinkPolicy.validate(longest, null) is DeepLinkRequest.Valid,
        )
        assertEquals(
            "one character past the limit is refused",
            DeepLinkRequest.Invalid("INVALID_URI"),
            DeepLinkPolicy.validate(longest + "a", null),
        )
    }
}
