package com.sheen.rokid.phone.perception

import com.sheen.rokid.agent.PerceptionPolicy
import com.sheen.rokid.device.Foreground
import com.sheen.rokid.device.Observation
import com.sheen.rokid.device.Ref
import com.sheen.rokid.device.RefRegistry
import com.sheen.rokid.device.ScreenFilter
import com.sheen.rokid.phone.context.AcousticSource
import com.sheen.rokid.phone.context.CalendarSnapshot
import com.sheen.rokid.phone.context.CalendarSource
import com.sheen.rokid.phone.context.ClipboardRead
import com.sheen.rokid.phone.context.ClipboardSource
import com.sheen.rokid.phone.context.GlassesStateSource
import com.sheen.rokid.phone.context.MediaSessionSource
import com.sheen.rokid.phone.context.NotificationContent
import com.sheen.rokid.phone.context.NotificationContentSource
import com.sheen.rokid.phone.context.NotificationRead
import com.sheen.rokid.plugin.DeviceStateProvider
import com.sheen.rokid.plugin.PerceptionFilter
import com.sheen.rokid.plugin.PerceptionProvider
import com.sheen.rokid.plugin.PerceptionScreenshotResult
import com.sheen.rokid.plugin.PerceptionSnapshot
import com.sheen.rokid.plugin.PerceptionSource
import java.util.UUID

/**
 * edith-0.3 Phase 2 Task 6: the Android host's [PerceptionProvider].
 *
 * **Turn lifecycle (load-bearing order).**
 * - [beginTurn] always refreshes the screen through the injected [observe] lambda (Task 7 wires it to the device
 *   kernel, so the kernel cache is current before the host can act) and *never* reuses a pre-turn `latestObservation`.
 *   It then reads the foreground, opens a new registry snapshot and **re-assigns** the observation under it - the
 *   adapter may already have assigned the same observation under the previous snapshot, and only an assign under the
 *   new snapshot makes the refs actionable. Finally it builds and caches the snapshot.
 * - [refresh] performs the same real read and new-snapshot sequence as [beginTurn] **without** starting a turn: it is
 *   the explicit `device.observe` read (final review C1). The cached [snapshot] cannot serve that call, because a manual
 *   screen change after the turn's opening read would be reported as the old screen and re-stamped refs would pass
 *   TTL/foreground. [refresh] is guarded exactly like [beginTurn] (a failed read degrades only the screen).
 * - [afterAction] takes the kernel's post-action observation ([latestObservation], falling back to a fresh [observe]),
 *   reads the foreground, opens a **new** snapshot and re-assigns. That new snapshot id is what makes a ref from
 *   before the action fail [RefRegistry.resolveForAction] as `CROSS_SNAPSHOT` when its node is gone, while a surviving
 *   node keeps its ref and is re-stamped.
 * - [snapshot] rebuilds from the cached observation and refs plus the live sources. It never observes, assigns or
 *   re-opens a snapshot, so the refs it reports stay valid for the turn's current snapshot.
 * - [endTurn] drops the cache and resets the registry: refs never survive a turn.
 *
 * **Honest degradation.** Every source read is guarded, so a dead source only degrades that source's coverage/content
 * (a failed accessibility read is `screen=unavailable(no_accessibility)`, a throwing calendar provider is
 * `calendar=unavailable(provider)`, ...). A failed turn never throws at the plugin.
 *
 * **Content privacy.** Snapshot content exists only in memory and is never logged here (spec §7.8): this class has no
 * log line at all. Screen dimensions come from the injected [DeviceStateProvider.profile], a platform fact.
 *
 * @param observe the kernel-backed whole-screen read. Called with [ScreenFilter.ALL]; a throw means the channel is
 *   down and only the screen source degrades.
 * @param foreground the app in front, or null when the channel cannot tell. A throw is treated as unknown.
 * @param profileSupportsImage whether this turn's request may carry an image: the profile declares the `IMAGE` input
 *   **and** its current revision's latest `IMAGE_REQUEST` verification is `Verified` (the same admission rule the
 *   plugin's `CapabilityGate` applies on the wire). The default is `false`, so an unwired provider never captures. A
 *   throw is treated as "no image": an automatic capture must never attach an image the capability gate would reject.
 * @param screenshot the injected capture (Task 7 maps the kernel's `ImageRef` or failure code); only called when the
 *   user's vision switch **and** [profileSupportsImage] are both on, and a throw becomes
 *   `Unavailable("SCREENSHOT_FAILED")`.
 * @param latestObservation the kernel's newest observation - the post-action read [afterAction] must use.
 */
internal class AndroidPerceptionProvider(
    private val observe: (ScreenFilter) -> Observation,
    private val foreground: () -> Foreground?,
    private val registry: RefRegistry,
    private val notifications: NotificationContentSource,
    private val calendar: CalendarSource,
    private val media: MediaSessionSource,
    private val deviceState: DeviceStateProvider,
    private val clipboard: ClipboardSource,
    private val acoustic: AcousticSource,
    private val glasses: GlassesStateSource,
    private val policy: () -> PerceptionPolicy,
    private val visionEnabled: () -> Boolean,
    private val profileSupportsImage: () -> Boolean = { false },
    screenshot: () -> PerceptionScreenshotResult,
    private val latestObservation: () -> Observation?,
    private val clock: () -> Long,
) : PerceptionProvider {

    // Named apart from the interface's `screenshot()` so the injected lambda can never be mistaken for a recursive call.
    private val capture = screenshot

    /** One turn's cached read: the snapshot id, its capture time, the observation and the refs assigned under it. */
    private data class CachedTurn(
        val snapshotId: String,
        val capturedAtEpochMillis: Long,
        val observation: Observation?,
        val refs: Map<String, Ref>,
        val foreground: Foreground?,
        val screenUnavailable: String?,
    )

    /** The screen source's answer: an observation, or the reason it has none ([screenUnavailable]). */
    private data class ScreenRead(val observation: Observation?, val screenUnavailable: String?)

    private var cached: CachedTurn? = null

    @Synchronized
    override fun beginTurn(): PerceptionSnapshot = openFreshSnapshot()

    /**
     * 0.3 Phase 2 final review (C1): the explicit-read half of [beginTurn].
     *
     * Same order and the same guards as the turn opening (observe -> foreground -> new snapshot id -> beginSnapshot ->
     * assign -> build/cache), but no turn state is reset: it is a read of the device, not a lifecycle event. The new
     * snapshot id is what makes a ref the caller held before this read a cross-snapshot ref (stale when its node moved)
     * instead of letting it be re-stamped by a cache-only rebuild.
     */
    @Synchronized
    override fun refresh(): PerceptionSnapshot = openFreshSnapshot()

    @Synchronized
    override fun afterAction(): PerceptionSnapshot {
        val latest = latestObservationOrNull()
        val read = if (latest != null) ScreenRead(latest, null) else observeAll()
        return openSnapshot(read.observation, read.screenUnavailable)
    }

    private fun openFreshSnapshot(): PerceptionSnapshot {
        val read = observeAll()
        return openSnapshot(read.observation, read.screenUnavailable)
    }

    /** Opens a new snapshot over [observation] and caches the result; the one fresh-build body for begin/refresh/action. */
    private fun openSnapshot(observation: Observation?, screenUnavailable: String?): PerceptionSnapshot {
        val foregroundFacts = foregroundOrNull()
        val now = safeClock()
        val snapshotId = UUID.randomUUID().toString()
        val refs = beginAndAssign(observation, snapshotId, now, foregroundFacts)
        val snapshot = build(
            snapshotId = snapshotId,
            capturedAtEpochMillis = now,
            observation = observation,
            refs = refs,
            foregroundFacts = foregroundFacts,
            include = ALL_SOURCES,
            filter = PerceptionFilter.ALL,
            limit = null,
            screenUnavailable = screenUnavailable,
        )
        cached = CachedTurn(snapshotId, now, observation, refs, foregroundFacts, screenUnavailable)
        return snapshot
    }

    @Synchronized
    override fun snapshot(
        include: Set<PerceptionSource>,
        filter: PerceptionFilter,
        limit: Int?,
    ): PerceptionSnapshot {
        val turn = cached
        if (turn != null) {
            return build(
                snapshotId = turn.snapshotId,
                capturedAtEpochMillis = turn.capturedAtEpochMillis,
                observation = turn.observation,
                refs = turn.refs,
                foregroundFacts = turn.foreground,
                include = include,
                filter = filter,
                limit = limit,
                screenUnavailable = turn.screenUnavailable,
            )
        }
        // No open turn (snapshot() after endTurn, or before beginTurn): rebuild from what is already cached only. No
        // observe, no assign and no beginSnapshot - a caller can never resurrect refs this way.
        val current = registry.currentSnapshot()
        return build(
            snapshotId = current?.snapshotId.orEmpty(),
            capturedAtEpochMillis = current?.issuedAtEpochMillis ?: safeClock(),
            observation = latestObservationOrNull(),
            refs = emptyMap(),
            foregroundFacts = foregroundOrNull(),
            include = include,
            filter = filter,
            limit = limit,
        )
    }

    /**
     * The screenshot fallback (spec §7.7, 0.3 auto-vision): two gates run in order and the injected capture is only
     * called when both pass.
     *
     * 1. The user's vision switch off is an explicit [PerceptionScreenshotResult.Disabled] - the user said no, so the
     *    profile is irrelevant.
     * 2. A profile that does not pass the auto-vision image gate (no declared `IMAGE`, or no current-revision
     *    `IMAGE_REQUEST=Verified`) is an explicit `Unavailable("no_image_profile")`. Task 3's automatic capture would
     *    otherwise attach an image the plugin's `CapabilityGate` rejects for the whole request.
     *
     * A capture failure stays an explicit `Unavailable`, never an exception; both gates fail closed on a throw.
     */
    override fun screenshot(): PerceptionScreenshotResult {
        if (!runCatching { visionEnabled() }.getOrDefault(false)) {
            return PerceptionScreenshotResult.Disabled
        }
        if (!runCatching { profileSupportsImage() }.getOrDefault(false)) {
            return PerceptionScreenshotResult.Unavailable(NO_IMAGE_PROFILE)
        }
        return runCatching { capture() }
            .getOrElse { PerceptionScreenshotResult.Unavailable(SCREENSHOT_FAILED) }
    }

    @Synchronized
    override fun endTurn() {
        cached = null
        registry.reset()
    }

    /**
     * Reads every live source (each guarded) and hands the values to the pure [PerceptionSnapshotBuilder].
     *
     * All sources are read even when [include] projects their content away: the coverage line must describe what the
     * host *can* see, and "I did not ask for it" must not masquerade as "the source is unavailable".
     */
    private fun build(
        snapshotId: String,
        capturedAtEpochMillis: Long,
        observation: Observation?,
        refs: Map<String, Ref>,
        foregroundFacts: Foreground?,
        include: Set<PerceptionSource>,
        filter: PerceptionFilter,
        limit: Int?,
        screenUnavailable: String? = null,
    ): PerceptionSnapshot {
        val effectivePolicy = runCatching { policy() }.getOrDefault(PerceptionPolicy())
        val profile = runCatching { deviceState.profile() }.getOrNull()
        val state = runCatching { deviceState.state() }.getOrNull()
        val notificationsConnected = notifications.isConnected()
        val notificationRead = readNotifications(effectivePolicy, notificationsConnected)
        val mediaState = runCatching { media.current() }.getOrNull()
        val calendarGranted = calendar.isGranted
        val calendarSnapshot = runCatching { calendar.snapshot() }
            .getOrElse { CalendarSnapshot(available = false, titles = emptyList()) }
        val clipboardRead = if (effectivePolicy.includeClipboard) {
            // R7: the source itself refuses a background read without touching the platform. A failure that escapes it
            // is still "the platform would not answer", never an empty clipboard.
            runCatching { clipboard.read() }
                .getOrElse { ClipboardRead.Unavailable(ClipboardSource.REASON_BACKGROUND_RESTRICTED) }
        } else {
            null
        }
        val acousticLabel = acoustic.snapshot()
        val glassesFacts = runCatching { glasses.snapshot() }.getOrNull()
        val visionOn = runCatching { visionEnabled() }.getOrDefault(false)

        return PerceptionSnapshotBuilder.build(
            snapshotId = snapshotId,
            capturedAtEpochMillis = capturedAtEpochMillis,
            observation = observation,
            refs = refs,
            foreground = foregroundFacts,
            notifications = notificationRead.items,
            notificationsConnected = notificationsConnected,
            notificationsAvailable = notificationRead.available,
            media = mediaState,
            calendar = calendarSnapshot,
            calendarGranted = calendarGranted,
            deviceState = state,
            profile = profile,
            clipboard = clipboardRead,
            acoustic = acousticLabel,
            glasses = glassesFacts,
            visionEnabled = visionOn,
            policy = effectivePolicy,
            include = include,
            filter = filter,
            limit = limit,
            screenUnavailable = screenUnavailable,
        )
    }

    /**
     * Reads one item past the policy cap so the builder can tell "there are exactly N" from "the cap cut the list".
     * A missing listener grant is not read at all. Final review I3: the read also carries whether the producer answered,
     * so a failed binder is `unavailable(provider)` rather than `empty`.
     */
    private fun readNotifications(
        effectivePolicy: PerceptionPolicy,
        connected: Boolean,
    ): NotificationRead {
        if (!connected) return NotificationRead(emptyList(), available = false)
        val cap = effectivePolicy.notificationLimit
        val probe = if (cap == Int.MAX_VALUE) Int.MAX_VALUE else cap + 1
        return notifications.read(probe)
    }

    /**
     * Opens the registry snapshot and re-assigns the observation under it.
     *
     * `assign` is the only place that stamps a binding's snapshot id, and [RefRegistry.beginSnapshot] only records
     * metadata: without this assign the refs would still belong to the previous snapshot. When the observation is
     * missing (a dead accessibility channel) the snapshot is still opened, so every earlier ref is pushed out of the
     * snapshot and cannot be acted on.
     */
    private fun beginAndAssign(
        observation: Observation?,
        snapshotId: String,
        issuedAtEpochMillis: Long,
        foregroundFacts: Foreground?,
    ): Map<String, Ref> {
        registry.beginSnapshot(
            snapshotId = snapshotId,
            issuedAtEpochMillis = issuedAtEpochMillis,
            foregroundPackage = foregroundFacts?.packageName,
            foregroundActivity = foregroundFacts?.activity,
        )
        if (observation == null) return emptyMap()
        return runCatching { registry.assign(observation) }.getOrDefault(emptyMap())
    }

    private fun observeAll(): ScreenRead = runCatching {
        ScreenRead(observe(ScreenFilter.ALL), null)
    }.getOrElse { failure ->
        ScreenRead(null, screenUnavailable(failure))
    }

    /**
     * Maps the kernel's failure to the coverage token the model reads.
     *
     * `DEVICE_LOCKED` / `DEVICE_SCREEN_OFF` are not accessibility failures: the service is bound, there is simply no
     * readable window. Reporting them as `no_accessibility` made the assistant tell the wearer the screen-reading
     * permission was off (2026-10-22 K70 acceptance). Unknown failures keep their own honest token so the prompt no
     * longer blames the permission for everything.
     */
    private fun screenUnavailable(failure: Throwable): String =
        when ((failure as? ScreenReadUnavailable)?.reason) {
            "DEVICE_LOCKED" -> "locked"
            "DEVICE_SCREEN_OFF" -> "screen_off"
            "CHANNEL_UNAVAILABLE" -> "no_accessibility"
            else -> "observe_failed"
        }

    private fun foregroundOrNull(): Foreground? = runCatching { foreground() }.getOrNull()

    private fun latestObservationOrNull(): Observation? = runCatching { latestObservation() }.getOrNull()

    private fun safeClock(): Long = runCatching { clock() }.getOrDefault(0L)

    private companion object {
        val ALL_SOURCES: Set<PerceptionSource> = PerceptionSource.entries.toSet()

        /** The injected capture failed; reported as an explicit absence, never thrown at the plugin. */
        const val SCREENSHOT_FAILED = "SCREENSHOT_FAILED"

        /** The selected profile has no `IMAGE` input; an attached image would fail the plugin's capability gate. */
        const val NO_IMAGE_PROFILE = "no_image_profile"
    }
}
