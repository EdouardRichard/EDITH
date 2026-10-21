package com.sheen.rokid.phone.actions.a11y

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The one sizing rule every auto-vision screenshot goes through (0.3 Phase 2.1, Task 1).
 *
 * The points that matter are the two independent caps: a phone screen is tall, so the width cap
 * (720) normally binds, while the long-edge cap (1600) only binds for unusually tall aspect ratios
 * (e.g. 1200x2000). Small screens must never be upscaled, and degenerate input must come back as
 * `0 to 0` instead of a negative or zero-sized bitmap request.
 */
class ScreenshotSizingTest {

    @Test
    fun preserves20By9AspectAtWidthCap() =
        assertEquals(720 to 1600, scaledScreenshotSize(1080, 2400))

    @Test
    fun preserves3By2AspectAtWidthCap() =
        assertEquals(720 to 540, scaledScreenshotSize(2000, 1500))

    @Test
    fun preservesTallPhoneAspectAtLongEdgeCap() =
        assertEquals(720 to 1200, scaledScreenshotSize(1200, 2000))

    @Test
    fun neverUpscalesSmallScreen() =
        assertEquals(400 to 800, scaledScreenshotSize(400, 800))

    @Test
    fun keepsOnePixelMinimumForExtremeRatio() =
        assertEquals(1 to 1600, scaledScreenshotSize(1, 4000))

    @Test
    fun invalidDimensionsReturnZero() =
        assertEquals(0 to 0, scaledScreenshotSize(0, 2400))
}
