package com.sheen.rokid.output

import com.sheen.rokid.plugin.PerceptionSnapshot
import com.sheen.rokid.plugin.ToolObservation
import com.sheen.rokid.plugin.ToolVision

/**
 * edith-0.3 Phase 2.2 Task 6: the one projection from a [PerceptionSnapshot] onto the model-facing [ToolObservation].
 *
 * Both observation channels use it: the host composition (the `open` tool and the `shell`/`dial` catalog) and the
 * plugin's loop-level failure source. Keeping the mapping in one place is what stops the two from disagreeing about what
 * `screen`/`coverage` mean for the same screen.
 *
 * **Never invent a screen.** A snapshot whose screen source did not answer (its coverage token is
 * `unavailable(reason)`) carries `screen = null` instead of an empty string that could be mistaken for a real, empty
 * screen; the coverage line still says why. A real observation with no elements renders as the empty string and stays a
 * real observation. [vision] defaults to [ToolVision.NotRequested] because this type carries **no image**: a caller that
 * actually captured a picture must overwrite it with [ToolVision.Ok] only after attaching that picture (the host tools
 * do that). `rootPackage` is not part of a snapshot, so a tool with its own root probe keeps supplying it.
 */
object PerceptionToolObservation {

    /** The stable reason the composition reports when the accessibility channel cannot observe at all. */
    const val NO_ACCESSIBILITY = "no_accessibility"

    /** An explicit unavailable observation with [reason]; never a silent null and never a fabricated screen. */
    fun unavailable(reason: String): ToolObservation =
        ToolObservation(vision = ToolVision.Unavailable, visionReason = reason)

    /**
     * Projects [snapshot] onto [ToolObservation]. [vision]/[visionReason] describe the picture channel; the caller owns
     * them because only it knows whether an image was attached.
     */
    fun render(
        snapshot: PerceptionSnapshot,
        vision: ToolVision = ToolVision.NotRequested,
        visionReason: String? = null,
    ): ToolObservation {
        val screenUnavailable = unavailableReason(snapshot.coverage.screen) != null
        return ToolObservation(
            snapshotId = snapshot.snapshotId.takeIf { it.isNotEmpty() },
            capturedAtEpochMillis = snapshot.capturedAtEpochMillis,
            foregroundPackage = snapshot.foregroundPackage,
            foregroundActivity = snapshot.foregroundActivity,
            coverage = PerceptionCoverageRenderer.render(snapshot.coverage),
            screen = if (screenUnavailable) null else CompactSnapshotRenderer.render(snapshot),
            truncated = !screenUnavailable && CompactSnapshotRenderer.screenTruncated(snapshot),
            vision = vision,
            visionReason = visionReason,
        )
    }

    /**
     * The reason inside an `unavailable(reason)` coverage token, or `null` when the source answered.
     *
     * The token vocabulary is the snapshot builder's ([PerceptionCoverageRenderer] never invents one), so the same
     * source that says `unavailable(no_accessibility)` is what supplies this reason - a caller that wants an explicit
     * `visionReason` does not have to hard-code a second guess.
     */
    fun unavailableReason(coverageToken: String): String? {
        if (!coverageToken.startsWith(UNAVAILABLE_PREFIX) || !coverageToken.endsWith(")")) return null
        return coverageToken.substring(UNAVAILABLE_PREFIX.length, coverageToken.length - 1).takeIf { it.isNotBlank() }
    }

    private const val UNAVAILABLE_PREFIX = "unavailable("
}
