package com.sheen.rokid.phone.actions.a11y

/**
 * Computes the size a screenshot is scaled to before it leaves the accessibility service
 * (0.3 Phase 2.1, Task 1).
 *
 * The screen is scaled down uniformly so its aspect ratio is preserved: the width never exceeds
 * [maxWidth] and the longest edge never exceeds [maxLongEdge]. Both caps apply independently and the
 * stricter one wins: a 1200x2000 screen is capped by [maxWidth] at 720x1200 (scale 0.6, not 0.8),
 * while a 1080x2520 screen is capped by [maxLongEdge] at 685x1600 (scale 0.635, not 0.667). A
 * 1080x2400 screen reaches both caps at once: 720x1600. Small screens are never upscaled: a scale
 * factor above 1 would only waste bytes on made-up pixels.
 *
 * A non-positive dimension is not something to guess at: [width], [height], [maxWidth] and
 * [maxLongEdge] must all be positive, otherwise the answer is `0 to 0` (the caller must treat that
 * as "cannot size a screenshot", never as a bitmap request).
 *
 * A ratio extreme enough for a rounded edge to reach zero still yields one pixel: an encoded image
 * with a zero-sized edge is invalid, so the minimum is clamped to 1.
 */
internal fun scaledScreenshotSize(
    width: Int,
    height: Int,
    maxWidth: Int = 720,
    maxLongEdge: Int = 1600,
): Pair<Int, Int> {
    if (width <= 0 || height <= 0 || maxWidth <= 0 || maxLongEdge <= 0) return 0 to 0
    val scale = minOf(1.0, maxWidth.toDouble() / width, maxLongEdge.toDouble() / height)
    val scaledWidth = (width * scale).toInt().coerceAtLeast(1)
    val scaledHeight = (height * scale).toInt().coerceAtLeast(1)
    return scaledWidth to scaledHeight
}

/**
 * The MIME type of every screenshot the accessibility service returns (spec §5.2, V-04).
 *
 * The encoder contract lives here, next to the sizing rule, so the JVM tests can pin it: a later
 * change from JPEG to another format must change this declaration and [SCREENSHOT_JPEG_QUALITY]
 * together, and a test fails until it does.
 */
internal const val SCREENSHOT_MIME = "image/jpeg"

/** The JPEG quality every screenshot is encoded at (spec §5.2, V-04: q80). */
internal const val SCREENSHOT_JPEG_QUALITY = 80
