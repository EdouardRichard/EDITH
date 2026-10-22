package com.sheen.rokid.phone.app

import com.sheen.rokid.device.DeviceEnvelope
import com.sheen.rokid.device.DeviceError
import com.sheen.rokid.device.Timing
import com.sheen.rokid.plugin.ImageRef
import com.sheen.rokid.plugin.PerceptionScreenshotResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.3 Phase 2.1 Task 4 fix: the composition's capture lambda maps a kernel screenshot envelope to the provider result.
 *
 * The kernel reports a failed artifact store as a **successful** screenshot whose reference is the `pending`
 * placeholder with an empty sha256. Attaching that placeholder to a model request would send an unresolvable image
 * id, so it is `Unavailable("SCREENSHOT_PENDING")` here; only a real stored artifact becomes an image.
 */
class PerceptionScreenshotMappingTest {

    private val stored = ImageRef("artifact-1", "image/jpeg", 720, 1600, "sha256-artifact-1")

    @Test
    fun `a stored screenshot becomes an image`() {
        val result = perceptionScreenshotResult(envelope(images = listOf(stored)))

        assertTrue("a real artifact reference is attached", result is PerceptionScreenshotResult.Image)
        assertEquals(stored, (result as PerceptionScreenshotResult.Image).image)
    }

    @Test
    fun `the kernel pending placeholder is unavailable, never an image`() {
        val pending = ImageRef("pending", "image/jpeg", 720, 1600, "")

        assertEquals(
            PerceptionScreenshotResult.Unavailable("SCREENSHOT_PENDING"),
            perceptionScreenshotResult(envelope(images = listOf(pending))),
        )
    }

    @Test
    fun `a blank sha is treated as pending even under another id`() {
        val blankSha = ImageRef("artifact-2", "image/jpeg", 720, 1600, "")

        assertEquals(
            PerceptionScreenshotResult.Unavailable("SCREENSHOT_PENDING"),
            perceptionScreenshotResult(envelope(images = listOf(blankSha))),
        )
    }

    @Test
    fun `a failed screenshot keeps the kernel error code`() {
        val failed = DeviceEnvelope.failure(
            action = "screenshot",
            channel = "accessibility",
            error = DeviceError("UNSUPPORTED", "no screenshot support", recoverable = false),
            timing = Timing(1, 2, 3),
        )

        assertEquals(
            PerceptionScreenshotResult.Unavailable("UNSUPPORTED"),
            perceptionScreenshotResult(failed),
        )
    }

    @Test
    fun `an imageless success is a screenshot failure`() {
        assertEquals(
            PerceptionScreenshotResult.Unavailable("SCREENSHOT_FAILED"),
            perceptionScreenshotResult(envelope(images = emptyList())),
        )
    }

    private fun envelope(images: List<ImageRef>) = DeviceEnvelope.success(
        action = "screenshot",
        channel = "accessibility",
        accepted = true,
        applied = null,
        verified = null,
        timing = Timing(1, 2, 3),
        images = images,
    )
}
