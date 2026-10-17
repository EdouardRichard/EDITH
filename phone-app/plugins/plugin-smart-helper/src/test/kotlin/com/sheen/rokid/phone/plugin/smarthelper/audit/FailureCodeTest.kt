package com.sheen.rokid.phone.plugin.smarthelper.audit

import com.sheen.rokid.contracts.ContractError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `P3_RUN`'s failure code: enough to tell two rejections apart, bounded and payload-free.
 *
 * The device turn that failed logged `P3_RUN error=ConfigurationRejected`, which cannot distinguish a profile revision
 * mismatch from an invalid endpoint from an over-budget context — all four are the same class. This is the instrument
 * that makes the next occurrence readable, and these tests are the privacy half of it: nothing that looks like a URL, a
 * path, a credential or an id may survive.
 */
class FailureCodeTest {

    @Test
    fun `a reason shaped message keeps its reason`() {
        // The real messages in the codebase are plain reason phrases; the code is the reason itself.
        assertEquals(
            "Explicit context exceeds safe budget or is malformed",
            ContractError.ConfigurationRejected("Explicit context exceeds safe budget or is malformed")
                .safeFailureCode("UNKNOWN"),
        )
        assertEquals(
            "Profile revision mismatch",
            ContractError.ConfigurationRejected("Profile revision mismatch").safeFailureCode("UNKNOWN"),
        )
    }

    @Test
    fun `a detail after a colon is dropped, because the detail is where a payload would be`() {
        assertEquals(
            "Model configuration unavailable",
            ContractError.ConfigurationRejected("Model configuration unavailable: profile=deepseek").safeFailureCode("UNKNOWN"),
        )
    }

    @Test
    fun `a url, a path, an at sign or a credential-shaped token is refused outright`() {
        val fallback = "UNKNOWN"
        assertEquals(fallback, IllegalStateException("https://api.example.com/v1").safeFailureCode(fallback))
        assertEquals(fallback, IllegalStateException("open /sdcard/EDITH/x failed").safeFailureCode(fallback))
        assertEquals(fallback, IllegalStateException("token=abc").safeFailureCode(fallback))
        assertEquals(fallback, IllegalStateException("sk-ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789").safeFailureCode(fallback))
        assertEquals(fallback, IllegalStateException("user@host refused").safeFailureCode(fallback))
        // A very long single token cannot be a code, so it is refused rather than truncated into one.
        assertEquals(fallback, IllegalStateException("A".repeat(64)).safeFailureCode(fallback))
    }

    @Test
    fun `a message with no text falls back rather than logging an empty code`() {
        assertEquals("UNKNOWN", IllegalStateException().safeFailureCode("UNKNOWN"))
        assertEquals("UNKNOWN", IllegalStateException("   ").safeFailureCode("UNKNOWN"))
    }

    @Test
    fun `the code is always bounded`() {
        val long = "Reason " + "word ".repeat(40)
        val code = IllegalStateException(long).safeFailureCode("UNKNOWN")
        assertTrue(code.length <= 48, "the code must stay bounded, was ${code.length}")
        assertFalse(code.contains("  "), "the code must not carry runs of whitespace")
    }
}
