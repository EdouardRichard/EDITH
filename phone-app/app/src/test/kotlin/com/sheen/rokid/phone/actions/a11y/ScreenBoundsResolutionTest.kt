package com.sheen.rokid.phone.actions.a11y

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 0.5.4 T11 fix round: the display-rectangle probe must never let a failing window-metrics probe take the
 * `resources.displayMetrics` fallback down with it.
 *
 * `EdithAccessibilityService` is an `AccessibilityService`, i.e. a non-UI context; on vendor builds
 * `WindowManager.currentWindowMetrics` throws there. The old shape wrapped the metrics probe and the fallback in one
 * `runCatching`, so that throw answered `null` and every coordinate action refused `MISSING_POINT` on device. The
 * platform context cannot be instantiated in a JVM unit test, so the resolution rule is extracted into
 * [resolveScreenBounds] with both probes injected - a throwing window probe here is exactly the device failure this
 * fix is about.
 */
class ScreenBoundsResolutionTest {

    @Test
    fun `a readable window rectangle wins over the fallback`() {
        var fallbackAsked = false

        val bounds = resolveScreenBounds(
            windowProbe = { ScreenBounds(0, 0, 1080, 2400) },
            fallbackProbe = {
                fallbackAsked = true
                200 to 300
            },
        )

        assertEquals(ScreenBounds(0, 0, 1080, 2400), bounds)
        assertFalse("the fallback is only for an unusable window probe", fallbackAsked)
    }

    @Test
    fun `a throwing window probe still falls back to display metrics`() {
        // The regression this fix is about: one throw from the non-UI-context metrics probe must not discard the
        // always-present display metrics.
        val bounds = resolveScreenBounds(
            windowProbe = { throw IllegalArgumentException("currentWindowMetrics from a non-UI context") },
            fallbackProbe = { 1080 to 2400 },
        )

        assertEquals(ScreenBounds(0, 0, 1080, 2400), bounds)
    }

    @Test
    fun `a missing window rectangle falls back to display metrics`() {
        val bounds = resolveScreenBounds(
            windowProbe = { null },
            fallbackProbe = { 1080 to 2400 },
        )

        assertEquals(ScreenBounds(0, 0, 1080, 2400), bounds)
    }

    @Test
    fun `a degenerate window rectangle falls back to display metrics`() {
        listOf(
            ScreenBounds(0, 0, 0, 0),
            ScreenBounds(100, 100, 100, 100),
            ScreenBounds(10, 10, 5, 5),
        ).forEach { window ->
            val bounds = resolveScreenBounds(windowProbe = { window }, fallbackProbe = { 1080 to 2400 })
            assertEquals("window=$window", ScreenBounds(0, 0, 1080, 2400), bounds)
        }
    }

    @Test
    fun `null only when neither source is usable`() {
        assertNull(resolveScreenBounds(windowProbe = { null }, fallbackProbe = { 0 to 2400 }))
        assertNull(resolveScreenBounds(windowProbe = { null }, fallbackProbe = { 1080 to 0 }))
        assertNull(
            resolveScreenBounds(
                windowProbe = { throw IllegalStateException("non-UI context") },
                fallbackProbe = { throw IllegalStateException("no display metrics") },
            ),
        )
        assertNull(resolveScreenBounds(windowProbe = { ScreenBounds(0, 0, 0, 0) }, fallbackProbe = { -1 to -1 }))
    }
}
