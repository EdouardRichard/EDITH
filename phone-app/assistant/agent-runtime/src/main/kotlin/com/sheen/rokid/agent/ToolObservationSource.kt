package com.sheen.rokid.agent

import com.sheen.rokid.plugin.ImageRef
import com.sheen.rokid.plugin.ToolObservation
import com.sheen.rokid.plugin.ToolVision

/**
 * One source's answer: [observation] is what the attempt could observe (`null` when it produced none) and [images]
 * carries at most one really-produced [ImageRef] (M4) — never a fabricated or borrowed picture.
 */
data class ToolObservationCapture(
    val observation: ToolObservation?,
    val images: List<ImageRef> = emptyList(),
)

/**
 * edith-0.3 Phase 2.2 Task 3 / Phase 3 Task 3 (M4): the host-neutral source of a **best-effort post-call observation**,
 * used when a screen tool's failure/timeout/throw/refusal left no observation of its own.
 *
 * The agent loop calls this only for a non-`Done` result whose descriptor declared `screenObservation = true`. A pure
 * tool never reaches it, so its result keeps `observation = null` instead of a fabricated screen.
 *
 * [deadlineMillis] is the whole budget for one attempt (the loop passes a short failure deadline). An implementation
 * must return by then rather than block the run. When it knows the screen cannot be observed, it returns an explicit
 * `ToolObservation(vision = ToolVision.Unavailable, visionReason = ...)` — that reason is more useful to the model than
 * the loop's generic one. Returning `null` means the attempt timed out or the cause is unknown, not "the screen was
 * fine"; the loop maps `null` to `vision = unavailable` with `visionReason = "observation_timeout"`. A thrown
 * [Exception] gets that same mapping and is not an error path for the run, while a non-[Exception] `Throwable` is not
 * swallowed. The source must never return an observation containing invented facts, and it must not carry user content
 * into logs.
 *
 * **M4 (images).** [observeWithImages] is the image-aware form the loop now calls. A source that can capture a picture
 * returns at most **one** [ImageRef] beside its observation; the loop appends it to the failed result's images and marks
 * that result non-persistent, exactly as it does any observation. A source that predates M4 keeps the default: the
 * pre-M4 [observe] answer plus no image, so every existing caller compiles and behaves unchanged.
 */
fun interface ToolObservationSource {
    fun observe(deadlineMillis: Long): ToolObservation?

    /**
     * The image-aware form of [observe], defaulting to the pre-M4 answer plus no image.
     *
     * A source that returns a capture with [ToolObservationCapture.images] must have really produced those images (a
     * screenshot it took, or one its host returned); a fabricated or borrowed reference would claim a picture the loop
     * never saw. `null` — and a capture whose observation is `null` — keeps the loop's explicit timeout mapping.
     */
    fun observeWithImages(deadlineMillis: Long): ToolObservationCapture? =
        observe(deadlineMillis)?.let { ToolObservationCapture(it) }
}
