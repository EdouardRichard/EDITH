package com.sheen.rokid.phone.context

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * edith-0.3 Phase 2 Task 4/6: the clipboard fact and its honest absences.
 *
 * The source takes the platform read as a lambda, so these tests drive the real mapping without Android: clipboard
 * text becomes [ClipboardRead.Text]; a missing/blank clipboard and the platform's background refusal stay two
 * distinguishable [ClipboardRead.Unavailable] answers instead of collapsing into "there is no text". Android 10+
 * refuses a background clipboard read, and that refusal is expected - the read is never widened with a new permission.
 *
 * Task 6 (R7) adds the foreground predicate: when the app is not in front the platform read is not attempted at all and
 * the answer is `background_restricted`, so a `null` the platform hands back from the background can never be misread
 * as an empty clipboard. An unknown (`null`) predicate keeps the old "try the read" behavior.
 */
class ClipboardSourceTest {

    @Test
    fun clipboardTextIsCarriedVerbatim() {
        assertEquals(
            ClipboardRead.Text("晚上一起吃饭吗？"),
            ClipboardSource(read = { "晚上一起吃饭吗？" }).read(),
        )
    }

    @Test
    fun aMissingOrBlankClipboardIsUnavailableEmpty() {
        assertEquals(ClipboardRead.Unavailable("empty"), ClipboardSource(read = { null }).read())
        assertEquals(ClipboardRead.Unavailable("empty"), ClipboardSource(read = { "" }).read())
        assertEquals(ClipboardRead.Unavailable("empty"), ClipboardSource(read = { " \n\t " }).read())
    }

    @Test
    fun aBackgroundSecurityRefusalIsUnavailableBackgroundRestricted() {
        assertEquals(
            ClipboardRead.Unavailable("background_restricted"),
            ClipboardSource(
                read = { throw SecurityException("clipboard is not readable from the background") },
            ).read(),
        )
    }

    @Test
    fun anIllegalStateReadIsUnavailableBackgroundRestricted() {
        assertEquals(
            ClipboardRead.Unavailable("background_restricted"),
            ClipboardSource(
                read = { throw IllegalStateException("clipboard service is gone") },
            ).read(),
        )
    }

    @Test
    fun anUnexpectedReadFailureDegradesInsteadOfCrashing() {
        assertEquals(
            "any failed read is the platform refusing this read, never a fabricated empty clipboard",
            ClipboardRead.Unavailable("background_restricted"),
            ClipboardSource(read = { throw RuntimeException("binder died") }).read(),
        )
    }

    @Test
    fun aBackgroundForegroundPredicateRefusesTheReadWithoutTouchingThePlatform() {
        var reads = 0
        val source = ClipboardSource(
            read = {
                reads++
                "不应该被读取"
            },
            foreground = { false },
        )

        assertEquals(ClipboardRead.Unavailable("background_restricted"), source.read())
        assertEquals("the platform clipboard must not be read from the background", 0, reads)
    }

    @Test
    fun aForegroundOrUnknownPredicateStillAttemptsTheRead() {
        assertEquals(
            ClipboardRead.Text("合成剪贴板"),
            ClipboardSource(read = { "合成剪贴板" }, foreground = { true }).read(),
        )
        assertEquals(
            ClipboardRead.Text("合成剪贴板"),
            ClipboardSource(read = { "合成剪贴板" }, foreground = { null }).read(),
        )
    }

    @Test
    fun aThrowingForegroundPredicateFallsBackToTheRead() {
        assertEquals(
            ClipboardRead.Text("合成剪贴板"),
            ClipboardSource(
                read = { "合成剪贴板" },
                foreground = { error("window state is unavailable") },
            ).read(),
        )
    }
}
