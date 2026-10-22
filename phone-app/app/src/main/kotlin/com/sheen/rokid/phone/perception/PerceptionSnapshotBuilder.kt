package com.sheen.rokid.phone.perception

import com.sheen.rokid.agent.PerceptionPolicy
import com.sheen.rokid.device.DeviceNode
import com.sheen.rokid.device.Foreground
import com.sheen.rokid.device.Observation
import com.sheen.rokid.device.ObservationWindow
import com.sheen.rokid.device.Ref
import com.sheen.rokid.phone.context.CalendarSnapshot
import com.sheen.rokid.phone.context.ClipboardRead
import com.sheen.rokid.phone.context.ClipboardSource
import com.sheen.rokid.phone.context.GlassesPerception
import com.sheen.rokid.phone.context.NotificationContent
import com.sheen.rokid.plugin.DeviceProfile
import com.sheen.rokid.plugin.DeviceState
import com.sheen.rokid.plugin.MediaState
import com.sheen.rokid.plugin.NotificationItem
import com.sheen.rokid.plugin.PerceptionCoverage
import com.sheen.rokid.plugin.PerceptionElement
import com.sheen.rokid.plugin.PerceptionFilter
import com.sheen.rokid.plugin.PerceptionSnapshot
import com.sheen.rokid.plugin.PerceptionSource

/**
 * edith-0.3 Phase 2 Task 6: turns one observation plus the live sources into a [PerceptionSnapshot].
 *
 * **Pure by construction.** Every input is a value (or a value type from the contracts); the builder performs no
 * Android call, no registry access and no I/O. The provider reads the live sources and owns the [Ref] map from
 * `RefRegistry.assign`, which keeps the snapshot shape testable on the JVM and keeps the registry out of the model.
 *
 * **Model-facing filtering (spec §7.3).** A node becomes a [PerceptionElement] only when the platform reports it
 * visible ([DeviceNode.visibleToUser]), its bounds have a positive size, and it is not duplicate screen furniture.
 * System-UI windows (`type=system`, which is where the status and navigation bars live) and any window owned by
 * `com.android.systemui` are dropped as noise. Nothing else is filtered by content category: message, mail, web and
 * input-box text always survive, because "只按噪声/尺寸过滤，不按内容类别过滤".
 *
 * **Bounds never face the model.** [PerceptionElement.bounds] stays on the contract for evidence/debugging; the
 * element's `text` is the platform text verbatim, and the model-facing projection uses [PerceptionElement.centerX]/
 * [PerceptionElement.centerY] (Task 7's compact renderer).
 *
 * **Coverage is complete even when content is not requested.** [include] selects which source *content* a caller wants
 * (the plugin asks only for what its turn needs); [PerceptionCoverage] still reports every source's honest state, so a
 * caller can tell "nothing there" from "the host cannot see that source" even for a source it projected away.
 *
 * **Truncation is reported, not hidden.** The caller's [limit] is capped by [PerceptionPolicy.elementLimit]; a cut by
 * either bound or a producer-truncated observation marks `screen=truncated`. A notification list read one past the
 * policy cap marks `notifications=truncated`.
 *
 * The [refs] map must be exactly the map `RefRegistry.assign` returned for [observation]: its iteration order is the
 * observation's node order, which is how a node is paired with the ref the registry minted for it. When the
 * accessibility channel is down [observation] is null and there are no refs, so `screen` is
 * `unavailable(no_accessibility)` and the other sources still report.
 */
internal object PerceptionSnapshotBuilder {

    /**
     * Builds one snapshot.
     *
     * @param observation the kernel's screen observation, or null when the accessibility read failed.
     * @param refs `RefRegistry.assign(observation)`'s path-keyed map, in node order; empty when [observation] is null.
     * @param notifications the notifications read with one item past the policy cap, so a cut is observable.
     * @param notificationsConnected whether the notification listener grant is live.
     * @param notificationsAvailable final review I3: whether the producer actually answered. `false` with a live grant
     *   is `unavailable(provider)`, never `empty`; a missing grant keeps its own `unavailable(no_access)` reason.
     * @param calendar the permission/provider/content answer; [calendarGranted] keeps no-permission apart from a
     *   provider failure.
     * @param deviceState the per-request device facts (only the IME fact feeds coverage here).
     * @param profile the stable profile; its screen dimensions are copied onto the snapshot.
     * @param clipboard the clipboard answer, or null when [PerceptionPolicy.includeClipboard] is off.
     * @param glasses the link facts, or null when the host has no glasses state.
     * @param visionEnabled the injected vision gate; only consulted when [PerceptionPolicy.includeVision] is on.
     * @param include which sources' *content* the caller asked for; coverage is always complete.
     * @param screenUnavailable the reason the screen source had no observation (`locked`, `screen_off`,
     *   `no_accessibility`, `observe_failed`). Null keeps the historical `no_accessibility` token for callers that do
     *   not know better.
     */
    fun build(
        snapshotId: String,
        capturedAtEpochMillis: Long,
        observation: Observation?,
        refs: Map<String, Ref>,
        foreground: Foreground?,
        notifications: List<NotificationContent>,
        notificationsConnected: Boolean,
        notificationsAvailable: Boolean = true,
        media: MediaState?,
        calendar: CalendarSnapshot,
        calendarGranted: Boolean,
        deviceState: DeviceState?,
        profile: DeviceProfile?,
        clipboard: ClipboardRead?,
        acoustic: String?,
        glasses: GlassesPerception?,
        visionEnabled: Boolean,
        policy: PerceptionPolicy,
        include: Set<PerceptionSource>,
        filter: PerceptionFilter,
        limit: Int?,
        screenUnavailable: String? = null,
    ): PerceptionSnapshot {
        val elementLimit = (limit ?: policy.elementLimit)
            .coerceAtMost(policy.elementLimit)
            .coerceAtLeast(0)
        val screenRequested = PerceptionSource.SCREEN in include
        val candidates = modelFacingElements(observation, refs, filter)
        val elements = if (screenRequested) candidates.take(elementLimit) else emptyList()
        val elementCut = screenRequested && candidates.size > elementLimit

        val notificationsVisible = if (notificationsConnected) {
            notifications.map { it.toItem() }
        } else {
            emptyList()
        }
        val notificationLimit = policy.notificationLimit.coerceAtLeast(0)
        val notificationCut = notificationsVisible.size > notificationLimit
        val notificationContent = if (PerceptionSource.NOTIFICATIONS in include) {
            notificationsVisible.take(notificationLimit)
        } else {
            emptyList()
        }

        val calendarContent = if (PerceptionSource.CALENDAR in include) {
            calendar.titles.take(policy.calendarTitleLimit.coerceAtLeast(0))
        } else {
            emptyList()
        }
        val clipboardText = if (policy.includeClipboard && PerceptionSource.CLIPBOARD in include) {
            (clipboard as? ClipboardRead.Text)?.value
        } else {
            null
        }

        val coverage = PerceptionCoverage(
            screen = when {
                observation == null -> "unavailable(${screenUnavailable ?: SCREEN_UNAVAILABLE_REASON})"
                observation.truncated || elementCut -> TRUNCATED
                else -> OK
            },
            notifications = when {
                !notificationsConnected -> NOTIFICATIONS_UNAVAILABLE
                !notificationsAvailable -> NOTIFICATIONS_PROVIDER_UNAVAILABLE
                notificationsVisible.isEmpty() -> EMPTY
                notificationCut -> TRUNCATED
                else -> OK
            },
            media = if (media == null) MEDIA_UNAVAILABLE else OK,
            calendar = when {
                !calendarGranted -> CALENDAR_NO_PERMISSION
                !calendar.available -> CALENDAR_UNAVAILABLE
                // Coverage describes the source, not the include projection: titles the caller did not ask for are
                // still titles that exist.
                calendar.titles.isEmpty() -> EMPTY
                else -> OK
            },
            acoustic = if (acoustic == null) ACOUSTIC_UNAVAILABLE else OK,
            clipboard = clipboardCoverage(clipboard, policy),
            vision = if (policy.includeVision && visionEnabled) OK else VISION_DISABLED,
            glasses = if (glasses.hasState()) OK else GLASSES_UNAVAILABLE,
            device = if (foreground?.packageName != null && deviceState?.imeVisible != null) OK else DEVICE_PARTIAL,
        )

        val coverageTokens = listOf(
            coverage.screen,
            coverage.notifications,
            coverage.media,
            coverage.calendar,
            coverage.acoustic,
            coverage.clipboard,
            coverage.vision,
            coverage.glasses,
            coverage.device,
        )

        return PerceptionSnapshot(
            snapshotId = snapshotId,
            capturedAtEpochMillis = capturedAtEpochMillis,
            foregroundPackage = foreground?.packageName,
            foregroundActivity = foreground?.activity,
            screenWidthPx = profile?.screenWidthPx,
            screenHeightPx = profile?.screenHeightPx,
            elements = elements,
            notifications = notificationContent,
            media = if (PerceptionSource.MEDIA in include) media else null,
            calendarTitles = calendarContent,
            acoustic = if (PerceptionSource.ACOUSTIC in include) acoustic else null,
            clipboardText = clipboardText,
            coverage = coverage,
            truncated = coverageTokens.any { it == TRUNCATED },
        )
    }

    /**
     * The clipboard token (spec §7.2): disabled by policy, no text, or the platform's restrictive answer - never an
     * empty clipboard for a read that did not happen (R7).
     */
    private fun clipboardCoverage(clipboard: ClipboardRead?, policy: PerceptionPolicy): String = when {
        !policy.includeClipboard -> CLIPBOARD_DISABLED
        clipboard is ClipboardRead.Text -> OK
        clipboard is ClipboardRead.Unavailable && clipboard.reason == ClipboardSource.REASON_EMPTY -> EMPTY
        clipboard is ClipboardRead.Unavailable -> "unavailable(${clipboard.reason})"
        else -> CLIPBOARD_BACKGROUND_RESTRICTED
    }

    /** True when the glasses source reported at least one fact; an all-null answer is no state at all. */
    private fun GlassesPerception?.hasState(): Boolean =
        this != null && (connected != null || wearing != null || panelVisible != null)

    /**
     * Maps the observation's nodes to model-facing elements, preserving the observation's pre-order.
     *
     * Pairing uses the documented order contract of [RefRegistry.assign]: its map iterates in node order. A node
     * without a ref (a failed assign, or a caller passing no refs) is omitted rather than rendered without a handle.
     */
    private fun modelFacingElements(
        observation: Observation?,
        refs: Map<String, Ref>,
        filter: PerceptionFilter,
    ): List<PerceptionElement> {
        if (observation == null) return emptyList()
        val refList = refs.values.toList()
        if (refList.isEmpty()) return emptyList()
        val windows = observation.windows.associateBy { it.id }
        val seen = HashSet<NodeKey>()
        val elements = ArrayList<PerceptionElement>(minOf(observation.nodes.size, refList.size))

        observation.nodes.forEachIndexed { index, node ->
            val ref = refList.getOrNull(index) ?: return@forEachIndexed
            val window = windows[node.windowId]
            if (!isModelFacing(node, window)) return@forEachIndexed
            if (!matchesFilter(node, filter)) return@forEachIndexed
            if (!seen.add(NodeKey(node, window?.id))) return@forEachIndexed
            elements += node.toElement(ref, window)
        }
        return elements
    }

    /**
     * Spec §7.3's noise rules: invisible, zero-size, duplicate, and system-UI/status/nav-bar nodes are dropped.
     * Content categories are never filtered here - a message, mail, web or input-box text node is model-facing
     * whenever the platform reports it visible and sized.
     */
    private fun isModelFacing(node: DeviceNode, window: ObservationWindow?): Boolean {
        if (!node.visibleToUser) return false
        if (node.bounds.right - node.bounds.left <= 0) return false
        if (node.bounds.bottom - node.bounds.top <= 0) return false
        if (window != null) {
            if (window.type == WINDOW_TYPE_SYSTEM) return false
            if (window.packageName == SYSTEM_UI_PACKAGE) return false
        }
        return true
    }

    /** [PerceptionFilter] mirrors the device-control render filter: interactive targets, text, or everything visible. */
    private fun matchesFilter(node: DeviceNode, filter: PerceptionFilter): Boolean = when (filter) {
        PerceptionFilter.ALL -> true
        PerceptionFilter.TEXT ->
            node.text?.isNotBlank() == true ||
                node.contentDescription?.isNotBlank() == true ||
                node.hint?.isNotBlank() == true
        PerceptionFilter.INTERACTIVE ->
            node.actions.isNotEmpty() || node.states.any { it in INTERACTION_STATES }
    }

    private fun DeviceNode.toElement(ref: Ref, window: ObservationWindow?): PerceptionElement =
        PerceptionElement(
            ref = ref.value,
            role = role.ifBlank { UNKNOWN_ROLE },
            text = text?.takeIf { it.isNotBlank() },
            contentDescription = contentDescription?.takeIf { it.isNotBlank() },
            packageName = window?.packageName?.takeIf { it.isNotBlank() },
            centerX = (bounds.left + bounds.right) / 2,
            centerY = (bounds.top + bounds.bottom) / 2,
            bounds = "${bounds.left},${bounds.top},${bounds.right},${bounds.bottom}",
            clickable = "clickable" in states,
            editable = "editable" in states,
            scrollable = "scrollable" in states,
            // Platform booleans are only encoded when true in DeviceNode.states; "absent" is unknown, not false.
            checked = if ("checkable" in states) "checked" in states else null,
            selected = if ("selected" in states) true else null,
            enabled = if ("enabled" in states) true else null,
        )

    private fun NotificationContent.toItem(): NotificationItem = NotificationItem(
        packageName = packageName,
        appLabel = appLabel,
        title = title,
        text = text,
        subText = subText,
        bigText = bigText,
        category = category,
        postedAtEpochMillis = postedAtEpochMillis,
        ongoing = ongoing,
        actionLabels = actionLabels,
    )

    /**
     * One rendered element's identity for duplicate removal: same window, role, text/description and geometry. Bounds
     * are included because two different nodes at different positions are not duplicates of each other.
     */
    private data class NodeKey(
        val windowId: Int?,
        val role: String,
        val text: String?,
        val description: String?,
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
    ) {
        constructor(node: DeviceNode, windowId: Int?) : this(
            windowId = windowId,
            role = node.role,
            text = node.text,
            description = node.contentDescription,
            left = node.bounds.left,
            top = node.bounds.top,
            right = node.bounds.right,
            bottom = node.bounds.bottom,
        )
    }

    private const val OK = "ok"
    private const val EMPTY = "empty"
    private const val TRUNCATED = "truncated"
    private const val SCREEN_UNAVAILABLE_REASON = "no_accessibility"
    private const val NOTIFICATIONS_UNAVAILABLE = "unavailable(no_access)"

    /** Final review I3: the listener grant is live but the producer failed; a failed read is not an empty result. */
    private const val NOTIFICATIONS_PROVIDER_UNAVAILABLE = "unavailable(provider)"
    private const val MEDIA_UNAVAILABLE = "unavailable(no_session)"
    private const val CALENDAR_NO_PERMISSION = "unavailable(no_permission)"
    private const val CALENDAR_UNAVAILABLE = "unavailable(provider)"
    private const val ACOUSTIC_UNAVAILABLE = "unavailable(no_event)"
    private const val CLIPBOARD_DISABLED = "unavailable(disabled)"
    private const val CLIPBOARD_BACKGROUND_RESTRICTED = "unavailable(background_restricted)"
    private const val VISION_DISABLED = "disabled"
    private const val GLASSES_UNAVAILABLE = "unavailable(no_state)"
    private const val DEVICE_PARTIAL = "partial"
    private const val WINDOW_TYPE_SYSTEM = "system"
    private const val SYSTEM_UI_PACKAGE = "com.android.systemui"
    private const val UNKNOWN_ROLE = "unknown"

    /**
     * Interaction-relevant state tokens, mirroring `ObservationRenderer`'s INTERACTIVE list so the two filters cannot
     * disagree about what "interactive" means. Private there, restated here because this module-level builder must not
     * couple to a renderer.
     */
    private val INTERACTION_STATES = setOf(
        "clickable",
        "long_clickable",
        "editable",
        "scrollable",
        "checkable",
        "checked",
        "selected",
        "focused",
    )
}
