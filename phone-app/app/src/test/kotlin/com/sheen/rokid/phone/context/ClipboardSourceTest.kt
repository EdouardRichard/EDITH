package com.sheen.rokid.phone.context

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * edith-0.3 Phase 2 Task 4: the clipboard fact and its honest absences.
 *
 * The source takes the platform read as a lambda, so these tests drive the real mapping without Android: clipboard
 * text becomes [ClipboardRead.Text]; a missing/blank clipboard and the platform's background refusal stay two
 * distinguishable [ClipboardRead.Unavailable] answers instead of collapsing into "there is no text". Android 10+
 * refuses a background clipboard read, and that refusal is expected - the read is never widened with a new permission.
 */
class ClipboardSourceTest {

    @Test
    fun clipboardTextIsCarriedVerbatim() {
        assertEquals(ClipboardRead.Text("晚上一起吃饭吗？"), ClipboardSource { "晚上一起吃饭吗？" }.read())
    }

    @Test
    fun aMissingOrBlankClipboardIsUnavailableEmpty() {
        assertEquals(ClipboardRead.Unavailable("empty"), ClipboardSource { null }.read())
        assertEquals(ClipboardRead.Unavailable("empty"), ClipboardSource { "" }.read())
        assertEquals(ClipboardRead.Unavailable("empty"), ClipboardSource { " \n\t " }.read())
    }

    @Test
    fun aBackgroundSecurityRefusalIsUnavailableBackgroundRestricted() {
        assertEquals(
            ClipboardRead.Unavailable("background_restricted"),
            ClipboardSource { throw SecurityException("clipboard is not readable from the background") }.read(),
        )
    }

    @Test
    fun anIllegalStateReadIsUnavailableBackgroundRestricted() {
        assertEquals(
            ClipboardRead.Unavailable("background_restricted"),
            ClipboardSource { throw IllegalStateException("clipboard service is gone") }.read(),
        )
    }

    @Test
    fun anUnexpectedReadFailureDegradesInsteadOfCrashing() {
        assertEquals(ClipboardRead.Unavailable("read_failed"),
            ClipboardSource { throw RuntimeException("binder died") }.read())
    }
}
