package com.sheen.rokid.phone.actions.a11y

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The one sizing rule every auto-vision screenshot goes through (0.3 Phase 2.1, Task 1), plus the
 * JPEG encoder contract that travels with it (spec §5.2, V-04).
 *
 * The points that matter are the two independent caps: a phone screen is tall, so the width cap
 * (720) normally binds, while the long-edge cap (1600) only binds for unusually tall aspect ratios
 * (e.g. 1080x2520). Small screens must never be upscaled, and degenerate input must come back as
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
    fun preservesTallPhoneAspectAtWidthCap() =
        assertEquals(720 to 1200, scaledScreenshotSize(1200, 2000))

    @Test
    fun preservesUltraTallAspectAtLongEdgeCap() =
        assertEquals(685 to 1600, scaledScreenshotSize(1080, 2520))

    @Test
    fun neverUpscalesSmallScreen() =
        assertEquals(400 to 800, scaledScreenshotSize(400, 800))

    @Test
    fun keepsOnePixelMinimumForExtremeRatio() =
        assertEquals(1 to 1600, scaledScreenshotSize(1, 4000))

    @Test
    fun invalidDimensionsReturnZero() =
        assertEquals(0 to 0, scaledScreenshotSize(0, 2400))

    @Test
    fun negativeWidthReturnsZero() =
        assertEquals(0 to 0, scaledScreenshotSize(-1, 2400))

    @Test
    fun negativeHeightReturnsZero() =
        assertEquals(0 to 0, scaledScreenshotSize(1080, -1))

    @Test
    fun nonPositiveMaxWidthReturnsZero() =
        assertEquals(0 to 0, scaledScreenshotSize(1080, 2400, maxWidth = 0))

    @Test
    fun nonPositiveMaxLongEdgeReturnsZero() =
        assertEquals(0 to 0, scaledScreenshotSize(1080, 2400, maxLongEdge = -1))

    @Test
    fun pinsJpegQuality() = assertEquals(80, SCREENSHOT_JPEG_QUALITY)

    @Test
    fun pinsJpegMime() = assertEquals("image/jpeg", SCREENSHOT_MIME)
}
