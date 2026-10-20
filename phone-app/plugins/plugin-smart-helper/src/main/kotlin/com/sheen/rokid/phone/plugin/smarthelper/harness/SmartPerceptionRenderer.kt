package com.sheen.rokid.phone.plugin.smarthelper.harness

import com.sheen.rokid.agent.PerceptionPolicy
import com.sheen.rokid.output.CompactSnapshotRenderer
import com.sheen.rokid.output.PerceptionCoverageRenderer
import com.sheen.rokid.phone.plugin.smarthelper.prompt.PromptCatalog
import com.sheen.rokid.phone.plugin.smarthelper.prompt.PromptRenderer
import com.sheen.rokid.plugin.DeviceState
import com.sheen.rokid.plugin.MediaState
import com.sheen.rokid.plugin.NotificationItem
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

    private const val UNAVAILABLE = "unavailable"
    private const val NO_SESSION = "unavailable(no_session)"
    private const val UNKNOWN_MEDIA_STATE = "UNKNOWN"

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
     * Renders the block for [snapshot] against the current [deviceState] and session [policy].
     *
     * A missing [deviceState] leaves the date/weekday/time/timeZone slots empty rather than inventing a clock; a null
     * device field is omitted from the device line. Missing sources keep the snapshot's own coverage token, so
     * "nothing there" (`empty`) and "the host cannot see it" (`unavailable(...)`) stay different statements.
     */
    fun render(
        snapshot: PerceptionSnapshot,
        deviceState: DeviceState?,
        policy: PerceptionPolicy,
        full: Boolean,
    ): String {
        val values = linkedMapOf(
            "coverage" to PerceptionCoverageRenderer.render(snapshot.coverage),
            "date" to deviceState?.localDate.orEmpty(),
            "weekday" to deviceState?.weekday.orEmpty(),
            "time" to deviceState?.localTime.orEmpty(),
            "timeZone" to deviceState?.timeZone.orEmpty(),
            "deviceState" to deviceLine(deviceState),
            "foreground" to foregroundLine(snapshot, deviceState),
            "media" to mediaLine(snapshot.media ?: deviceState?.media),
            "perception" to if (full) screenText(snapshot) else NOT_REQUESTED,
            "notifications" to if (full) notificationsText(snapshot, policy) else NOT_REQUESTED,
            "calendar" to if (full) calendarText(snapshot, policy) else NOT_REQUESTED,
            "acoustic" to if (full) acousticText(snapshot) else NOT_REQUESTED,
            "clipboard" to if (full) clipboardText(snapshot) else NOT_REQUESTED,
        )
        return PromptRenderer.render(
            template = PromptCatalog.perceptionTemplate(),
            values = values,
            allowedSlots = SLOTS,
            maxValueCodePoints = maxOf(DEFAULT_VALUE_CODE_POINTS, policy.elementLimit * CODE_POINTS_PER_ELEMENT),
        )
    }

    /**
     * The one `P3_PERCEPTION` line: counts, a snapshot prefix, the rendered block's size and the coverage tokens.
     *
     * It never carries a title, text, package, activity, calendar title or clipboard value — only the fields named in
     * the shape, all of which are numbers, booleans, an ASCII token or the coverage vocabulary. `images=0` because
     * Phase 2 Task 9 attaches no image; Task 10's screenshot path owns the count.
     */
    fun telemetry(snapshot: PerceptionSnapshot, renderedText: String): String = buildString {
        append("P3_PERCEPTION snap=")
        append(snapshot.snapshotId.take(8).replace(UNSAFE_SNAPSHOT_CHARS, "").ifBlank { "-" })
        append(" elements=").append(snapshot.elements.size)
        append(" notifications=").append(snapshot.notifications.size)
        append(" calendar=").append(snapshot.calendarTitles.size)
        append(" images=0")
        append(" screenChars=").append(renderedText.length)
        append(" truncated=").append(snapshot.truncated)
        append(" coverage=").append(PerceptionCoverageRenderer.render(snapshot.coverage))
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

    /** `package/activity`, falling back to the device state's own foreground facts, or an honest `unavailable`. */
    private fun foregroundLine(snapshot: PerceptionSnapshot, deviceState: DeviceState?): String {
        val packageName = snapshot.foregroundPackage ?: deviceState?.foregroundPackage
        if (packageName.isNullOrBlank()) return UNAVAILABLE
        val activity = snapshot.foregroundActivity ?: deviceState?.foregroundActivity
        return if (activity.isNullOrBlank()) packageName else "$packageName/$activity"
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
        }
        return parts.joinToString(" ")
    }

    private fun yesNo(value: Boolean): String = if (value) "yes" else "no"

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
