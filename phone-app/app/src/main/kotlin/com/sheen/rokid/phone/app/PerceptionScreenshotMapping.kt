package com.sheen.rokid.phone.app

import com.sheen.rokid.device.DeviceEnvelope
import com.sheen.rokid.plugin.PerceptionScreenshotResult

/**
 * 0.3 Phase 2.1 Task 4 fix: maps one kernel screenshot envelope to the perception provider's result.
 *
 * A kernel screenshot that worked but could not be stored still reports `ok = true`; its reference is the `pending`
 * placeholder (empty sha256). Attaching that to a model request would name an artifact the media source cannot resolve,
 * so it is `Unavailable("SCREENSHOT_PENDING")` — a missing image, never a broken one. The same reasoning covers any
 * blank sha. A failed envelope keeps the kernel's own error code; an imageless success is `SCREENSHOT_FAILED`.
 */
internal fun perceptionScreenshotResult(envelope: DeviceEnvelope): PerceptionScreenshotResult {
    val image = envelope.images.firstOrNull()
        ?: return PerceptionScreenshotResult.Unavailable(envelope.error?.code ?: SCREENSHOT_FAILED)
    if (image.artifactId == PENDING_ARTIFACT_ID || image.sha256.isBlank()) {
        return PerceptionScreenshotResult.Unavailable(SCREENSHOT_PENDING)
    }
    return PerceptionScreenshotResult.Image(image, image.mime, image.width, image.height)
}

/** `DeviceKernel.screenshot()`'s placeholder id while no artifact store reference exists. */
private const val PENDING_ARTIFACT_ID = "pending"

private const val SCREENSHOT_FAILED = "SCREENSHOT_FAILED"
private const val SCREENSHOT_PENDING = "SCREENSHOT_PENDING"
