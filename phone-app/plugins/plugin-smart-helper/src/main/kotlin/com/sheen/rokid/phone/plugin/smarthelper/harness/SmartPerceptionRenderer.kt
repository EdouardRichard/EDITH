package com.sheen.rokid.phone.plugin.smarthelper.harness

import com.sheen.rokid.agent.PerceptionPolicy
import com.sheen.rokid.output.CompactSnapshotRenderer
import com.sheen.rokid.output.PerceptionCoverageRenderer
import com.sheen.rokid.phone.plugin.smarthelper.prompt.PromptCatalog
import com.sheen.rokid.phone.plugin.smarthelper.prompt.PromptRenderer
import com.sheen.rokid.plugin.DeviceState
import com.sheen.rokid.plugin.MediaState
import com.sheen.rokid.plugin.NotificationItem
import com.sheen.rokid.plugin.PerceptionCoverage
import com.sheen.rokid.plugin.PerceptionSnapshot

/**
 * edith-0.3 Phase 2 Task 9: renders the plugin's `PERCEPTION` request-tail block and its count-only telemetry.
 *
 * **Pure by design**: no Android type, no clock, no logger. The block is model-facing text and may therefore contain
 * screen text, notification bodies, media metadata, calendar titles and the clipboard value — that is the point of the
 * perception contract (spec §7.3/§7.8). [telemetry] is the only log-facing output, and it carries counts, a snapshot
 * prefix and the coverage tokens, never content.
 *
 * The block uses the thirteen perception slots of `context/runtime-state.md` (everything before `记忆：`); memory
 * evidence, SKILL bodies and the loop state remain dedicated tail segments. `full = false` is the ambient request that a
 * continuation makes (spec §7.5): device, foreground, media and the coverage line are still rendered, while the
 * high-volume sources say `not_requested` instead of repeating a snapshot the model already read.
 */
object SmartPerceptionRenderer {

    /** The renderer default cap, kept as the lower bound: a perception slot may legitimately exceed it. */
    private const val DEFAULT_VALUE_CODE_POINTS = 500

    /** The policy-derived growth per screen element; `PerceptionPolicy.elementLimit * 120`, bounded below by 500. */
    private const val CODE_POINTS_PER_ELEMENT = 120

    /** What an ambient block says for a source it deliberately did not ask the host to re-read. */
    private const val NOT_REQUESTED = "not_requested"

    /** The coverage token that means "the read itself was bounded"; same vocabulary as the shared coverage renderer. */
    private const val SCREEN_TRUNCATED = "truncated"

    /** The one shared marker a bounded text ends with; the compact renderer defines it, this renderer reuses it. */
    private val TRUNCATION_MARKER = CompactSnapshotRenderer.TRUNCATION_MARKER

    private const val UNAVAILABLE = "unavailable"
    private const val NO_SESSION = "unavailable(no_session)"
    private const val UNKNOWN_MEDIA_STATE = "UNKNOWN"

    /** The host's own vision token: the gate is on and the policy asked for vision. */
    private const val VISION_OK = "ok"

    /**
     * edith-0.3 Phase 2 Task 10: the profile the turn is running on declares no `IMAGE` input, so a screenshot could
     * be captured but never travel with the request. This is an honest capability gap, not a transient unavailability.
     */
    private const val NO_IMAGE_PROFILE = "unavailable(no_image_profile)"

    /** The thirteen slots the perception template may use; memory/skill/loop are deliberately absent. */
    private val SLOTS: Set<String> = linkedSetOf(
        "coverage",
        "date",
        "weekday",
        "time",
        "timeZone",
        "deviceState",
        "foreground",
        "media",
        "perception",
        "notifications",
        "calendar",
        "acoustic",
        "clipboard",
    )

    /** Anything outside this set is removed from the logged snapshot prefix, so the telemetry stays ASCII. */
    private val UNSAFE_SNAPSHOT_CHARS = Regex("[^A-Za-z0-9._:-]")

    /** One notification line per item, in the policy's order. */
    private const val LINE_SEPARATOR = " | "

    /**
     * The block plus the two facts its telemetry must report honestly: the coverage that was actually rendered and
     * whether this render itself bounded a value (review I3).
     *
     * [coverage] is [PerceptionSnapshot.coverage] unless the screen text had to be cut by the value cap, in which case
     * its `screen` token is `truncated`. [truncated] is the snapshot's own flag OR a local clip of any slot. A caller
     * that logs [telemetry] must use this result: reducing the block to a [String] loses the local-clip fact.
     */
    data class RenderedPerception(
        val text: String,
        val coverage: PerceptionCoverage,
        val truncated: Boolean,
    )

    /**
     * Renders the block for [snapshot] against the current [deviceState] and session [policy]; the text-only form of
     * [renderDetailed]. A caller that also logs the block's telemetry uses [renderDetailed], because a local cap clip
     * is not visible in the text alone.
     *
     * [visionSupported] is edith-0.3 Phase 2 Task 10's profile check: `false` means the selected profile declares no
     * `IMAGE` input, so an `ok` host token is rendered as `unavailable(no_image_profile)`; `true`/`null` leave the
     * host's token (`ok` when the gate is on, `disabled` when it is off) untouched.
     */
    fun render(
        snapshot: PerceptionSnapshot,
        deviceState: DeviceState?,
        policy: PerceptionPolicy,
        full: Boolean,
        visionSupported: Boolean? = null,
    ): String = renderDetailed(snapshot, deviceState, policy, full, visionSupported).text

    /**
     * Renders the block for [snapshot] against the current [deviceState] and session [policy], with the honest flags
     * the count-only telemetry needs.
     *
     * A missing [deviceState] leaves the date/weekday/time/timeZone slots empty rather than inventing a clock; a null
     * device field is omitted from the device line. Missing sources keep the snapshot's own coverage token, so
     * "nothing there" (`empty`) and "the host cannot see it" (`unavailable(...)`) stay different statements.
     *
     * **Bounded values say so (review I3).** Every slot is measured after the same control-character stripping the
     * prompt renderer applies. A slot over the policy cap is cut deliberately to the cap minus the shared
     * [CompactSnapshotRenderer.TRUNCATION_MARKER] and ends with that marker, so the model never receives a prefix that
     * looks complete. A cut screen also flips the rendered coverage's `screen=` token to `truncated`, and any cut sets
     * [RenderedPerception.truncated] for the telemetry.
     *
     * **Vision completeness (Task 10).** [visionSupported] is the caller's profile fact: `false` means the turn's
     * profile declares no `IMAGE` input, and an `ok` host coverage token is then reported as
     * `unavailable(no_image_profile)` in both the block and [RenderedPerception.coverage]. `true` and `null` keep the
     * host's own token, so a gate that is off still reads `disabled` and a missing profile is not treated as evidence
     * that images are unsupported.
     */
    fun renderDetailed(
        snapshot: PerceptionSnapshot,
        deviceState: DeviceState?,
        policy: PerceptionPolicy,
        full: Boolean,
        visionSupported: Boolean? = null,
    ): RenderedPerception {
        val maxValueCodePoints = maxOf(DEFAULT_VALUE_CODE_POINTS, policy.elementLimit * CODE_POINTS_PER_ELEMENT)
        var truncated = snapshot.truncated

        val rawScreen = if (full) screenText(snapshot) else NOT_REQUESTED
        var screenClipped = false
        val screen = cappedValue(rawScreen, maxValueCodePoints) { screenClipped = true }
        if (screenClipped) truncated = true

        // The local coverage string must not keep saying `ok` for a screen the cap just cut short, and it must not keep
        // saying `ok` for a screenshot the selected profile could never carry (Task 10). `disabled` stays `disabled`:
        // the host's own gate is off, which is a stronger fact than the profile's missing IMAGE input.
        val coverage = when {
            screenClipped -> snapshot.coverage.copy(screen = SCREEN_TRUNCATED)
            else -> snapshot.coverage
        }.let { base ->
            if (visionSupported == false && base.vision == VISION_OK) {
                base.copy(vision = NO_IMAGE_PROFILE)
            } else {
                base
            }
        }

        fun cappedSlot(raw: String): String = cappedValue(raw, maxValueCodePoints) { truncated = true }

        val values = linkedMapOf(
            "coverage" to cappedSlot(PerceptionCoverageRenderer.render(coverage)),
            "date" to cappedSlot(deviceState?.localDate.orEmpty()),
            "weekday" to cappedSlot(deviceState?.weekday.orEmpty()),
            "time" to cappedSlot(deviceState?.localTime.orEmpty()),
            "timeZone" to cappedSlot(deviceState?.timeZone.orEmpty()),
            "deviceState" to cappedSlot(deviceLine(deviceState)),
            "foreground" to cappedSlot(foregroundLine(snapshot, deviceState)),
            "media" to cappedSlot(mediaLine(snapshot.media ?: deviceState?.media)),
            "perception" to screen,
            "notifications" to cappedSlot(if (full) notificationsText(snapshot, policy) else NOT_REQUESTED),
            "calendar" to cappedSlot(if (full) calendarText(snapshot, policy) else NOT_REQUESTED),
            "acoustic" to cappedSlot(if (full) acousticText(snapshot) else NOT_REQUESTED),
            "clipboard" to cappedSlot(if (full) clipboardText(snapshot) else NOT_REQUESTED),
        )
        val text = PromptRenderer.render(
            template = PromptCatalog.perceptionTemplate(),
            values = values,
            allowedSlots = SLOTS,
            maxValueCodePoints = maxValueCodePoints,
        )
        return RenderedPerception(text, coverage, truncated)
    }

    /**
     * One value, sanitised exactly as [PromptRenderer] will sanitise it, then bounded deliberately by this renderer.
     *
     * A value at or under [maxCodePoints] is returned whole (control characters stripped). A longer one is cut to
     * `maxCodePoints - marker`, always on a code-point boundary, and the shared marker is appended: the result is
     * exactly the cap and the model can tell a complete value from a bounded one. [onClip] fires only when a cut
     * actually happened.
     */
    private fun cappedValue(raw: String, maxCodePoints: Int, onClip: () -> Unit): String {
        val clean = PromptRenderer.stripControlCharacters(raw)
        if (clean.codePointCount(0, clean.length) <= maxCodePoints) return clean
        onClip()
        val markerCodePoints = TRUNCATION_MARKER.codePointCount(0, TRUNCATION_MARKER.length)
        if (maxCodePoints <= markerCodePoints) return truncateToCodePoints(TRUNCATION_MARKER, maxCodePoints)
        return truncateToCodePoints(clean, maxCodePoints - markerCodePoints) + TRUNCATION_MARKER
    }

    /** The longest prefix of [value] whose code-point count is at most [maxCodePoints]; never splits a surrogate. */
    private fun truncateToCodePoints(value: String, maxCodePoints: Int): String {
        if (maxCodePoints <= 0) return ""
        if (value.codePointCount(0, value.length) <= maxCodePoints) return value
        return value.substring(0, value.offsetByCodePoints(0, maxCodePoints))
    }

    /**
     * The one `P3_PERCEPTION` line: counts, a snapshot prefix, the rendered block's size and the coverage tokens.
     *
     * It never carries a title, text, package, activity, calendar title or clipboard value — only the fields named in
     * the shape, all of which are numbers, booleans, an ASCII token or the coverage vocabulary. `images=0` because
     * Phase 2 Task 9 attaches no image; Task 10's screenshot path owns the count.
     *
     * [truncated] and [coverage] default to the snapshot's own values so a direct call keeps the pre-fix shape; a
     * caller that rendered through [renderDetailed] passes its local-clip result, which is what makes a cap cut visible
     * as `truncated=true` even when the snapshot itself was complete (review I3).
     */
    fun telemetry(
        snapshot: PerceptionSnapshot,
        renderedText: String,
        truncated: Boolean = snapshot.truncated,
        coverage: PerceptionCoverage = snapshot.coverage,
    ): String = buildString {
        append("P3_PERCEPTION snap=")
        append(snapshot.snapshotId.take(8).replace(UNSAFE_SNAPSHOT_CHARS, "").ifBlank { "-" })
        append(" elements=").append(snapshot.elements.size)
        append(" notifications=").append(snapshot.notifications.size)
        append(" calendar=").append(snapshot.calendarTitles.size)
        append(" images=0")
        append(" screenChars=").append(renderedText.length)
        append(" truncated=").append(truncated)
        append(" coverage=").append(PerceptionCoverageRenderer.render(coverage))
    }

    // ---------------------------------------------------------------- slot values

    /** The compact screen lines on one prompt line; empty screen content falls back to the screen coverage token. */
    private fun screenText(snapshot: PerceptionSnapshot): String {
        val compact = CompactSnapshotRenderer.render(snapshot)
        return if (compact.isBlank()) snapshot.coverage.screen else compact.replace("\n", LINE_SEPARATOR)
    }

    /**
     * Newest-first notification lines, capped by the policy. An empty list keeps the coverage token, and so does a
     * cap of zero: a list the policy suppressed is not an invented notification.
     */
    private fun notificationsText(snapshot: PerceptionSnapshot, policy: PerceptionPolicy): String {
        if (snapshot.notifications.isEmpty()) return snapshot.coverage.notifications
        return snapshot.notifications
            .sortedByDescending { it.postedAtEpochMillis }
            .take(policy.notificationLimit.coerceAtLeast(0))
            .joinToString(LINE_SEPARATOR) { notificationLine(it) }
            .ifBlank { snapshot.coverage.notifications }
    }

    private fun notificationLine(item: NotificationItem): String = buildString {
        append(item.packageName)
        item.appLabel?.takeIf { it.isNotBlank() }?.let { append(' ').append(it) }
        item.title?.takeIf { it.isNotBlank() }?.let { append(" | title=").append(it) }
        item.text?.takeIf { it.isNotBlank() }?.let { append(" | text=").append(it) }
        item.subText?.takeIf { it.isNotBlank() }?.let { append(" | subText=").append(it) }
        item.bigText?.takeIf { it.isNotBlank() }?.let { append(" | bigText=").append(it) }
        if (item.actionLabels.isNotEmpty()) {
            append(" | actions=").append(item.actionLabels.joinToString(","))
        }
    }

    /** Calendar titles, capped by the policy; no title (or a zero cap) keeps the coverage token. */
    private fun calendarText(snapshot: PerceptionSnapshot, policy: PerceptionPolicy): String {
        val titles = snapshot.calendarTitles.take(policy.calendarTitleLimit.coerceAtLeast(0))
        return if (titles.isEmpty()) snapshot.coverage.calendar else titles.joinToString(LINE_SEPARATOR)
    }

    private fun acousticText(snapshot: PerceptionSnapshot): String =
        snapshot.acoustic?.takeIf { it.isNotBlank() } ?: snapshot.coverage.acoustic

    private fun clipboardText(snapshot: PerceptionSnapshot): String =
        snapshot.clipboardText?.takeIf { it.isNotBlank() } ?: snapshot.coverage.clipboard

    /**
     * `package/activity`, or an honest `unavailable`.
     *
     * The snapshot's own pair is used **as a pair** whenever it names a package: its activity stays null when the
     * snapshot did not see one, rather than being borrowed from the live [DeviceState]. Mixing the two sources would
     * fabricate a `package/activity` combination that never existed (review I2). The live state is the fallback only
     * when the snapshot has no package at all, and then both halves come from that one state read.
     */
    private fun foregroundLine(snapshot: PerceptionSnapshot, deviceState: DeviceState?): String {
        val snapshotPackage = snapshot.foregroundPackage?.takeIf { it.isNotBlank() }
        if (snapshotPackage != null) {
            val snapshotActivity = snapshot.foregroundActivity?.takeIf { it.isNotBlank() }
            return if (snapshotActivity == null) snapshotPackage else "$snapshotPackage/$snapshotActivity"
        }
        val statePackage = deviceState?.foregroundPackage?.takeIf { it.isNotBlank() } ?: return UNAVAILABLE
        val stateActivity = deviceState.foregroundActivity?.takeIf { it.isNotBlank() }
        return if (stateActivity == null) statePackage else "$statePackage/$stateActivity"
    }

    /** Media state and metadata; only the fields the platform reported, or `unavailable(no_session)`. */
    private fun mediaLine(media: MediaState?): String {
        if (media == null) return NO_SESSION
        return buildString {
            append(media.state.ifBlank { UNKNOWN_MEDIA_STATE })
            media.title?.takeIf { it.isNotBlank() }?.let { append(" title=").append(it) }
            media.artist?.takeIf { it.isNotBlank() }?.let { append(" artist=").append(it) }
            media.album?.takeIf { it.isNotBlank() }?.let { append(" album=").append(it) }
            val position = media.positionMs
            val duration = media.durationMs
            if (position != null && duration != null) {
                append(" progress=").append(formatMillis(position)).append('/').append(formatMillis(duration))
            }
        }
    }

    /** Device facts: only the non-null fields, so an unavailable fact is absent instead of a fabricated zero. */
    private fun deviceLine(deviceState: DeviceState?): String {
        if (deviceState == null) return ""
        val parts = buildList {
            deviceState.batteryPercent?.let { add("battery=$it%") }
            deviceState.charging?.let { add("charging=${yesNo(it)}") }
            deviceState.network?.takeIf { it.isNotBlank() }?.let { add("network=$it") }
            deviceState.screenOn?.let { add("screenOn=${yesNo(it)}") }
            deviceState.locked?.let { add("locked=${yesNo(it)}") }
            deviceState.brightness?.let { add("brightness=$it") }
            deviceState.volumePercent?.let { add("volume=$it%") }
            deviceState.imeVisible?.let { add("ime=$it") }
            // 0.3 Phase 2 Task 9 review I1: the glasses and panel facts existed on DeviceState but were never rendered.
            deviceState.panelVisible?.let { add("panel=${visibleHidden(it)}") }
            deviceState.glassesConnected?.let { add("glasses=${connectedDisconnected(it)}") }
            deviceState.glassesWearing?.let { add("wearing=${yesNo(it)}") }
        }
        return parts.joinToString(" ")
    }

    private fun yesNo(value: Boolean): String = if (value) "yes" else "no"

    private fun visibleHidden(value: Boolean): String = if (value) "visible" else "hidden"

    private fun connectedDisconnected(value: Boolean): String = if (value) "connected" else "disconnected"

    /** A media position/duration as `mm:ss`, or `h:mm:ss` once it reaches an hour. Negative values clamp to zero. */
    private fun formatMillis(millis: Long): String {
        val totalSeconds = millis.coerceAtLeast(0L) / 1_000L
        val hours = totalSeconds / 3_600L
        val minutes = (totalSeconds % 3_600L) / 60L
        val seconds = totalSeconds % 60L
        val minutesText = minutes.toString().padStart(2, '0')
        val secondsText = seconds.toString().padStart(2, '0')
        return if (hours > 0L) "$hours:$minutesText:$secondsText" else "$minutesText:$secondsText"
    }
}
