package com.sheen.rokid.plugin

/**
 * edith-0.3 Phase 2 (spec §3.2, §7.1–§7.4): what the device perceives, as a read-only port for the plugin.
 *
 * The host owns capture and the turn lifecycle; the plugin only asks for a snapshot and reads it. Every source is
 * reported through [PerceptionCoverage], so "nothing there" and "the host cannot see that source" stay different
 * statements, and [PerceptionSnapshot.truncated] marks a snapshot that had to be bounded.
 */

/** One source a snapshot may include. The plugin asks only for the sources its turn needs (spec §7.2). */
enum class PerceptionSource { SCREEN, NOTIFICATIONS, MEDIA, CALENDAR, ACOUSTIC, CLIPBOARD, GLASSES, DEVICE }

/** What a screen read keeps: only interactive targets, only readable text, or everything observed. */
enum class PerceptionFilter { INTERACTIVE, TEXT, ALL }

/** One notification the host can read (spec §7.2). A field the platform does not supply stays `null`. */
data class NotificationItem(
    val packageName: String,
    val appLabel: String? = null,
    val title: String? = null,
    val text: String? = null,
    val subText: String? = null,
    val bigText: String? = null,
    val category: String? = null,
    val postedAtEpochMillis: Long,
    val ongoing: Boolean = false,
    val actionLabels: List<String> = emptyList(),
)

/**
 * One node of the current screen (spec §7.1).
 *
 * [ref] is the stable in-snapshot handle the plugin passes back to an action; [bounds] exists for evidence and
 * debugging, while model-facing text uses [centerX]/[centerY].
 */
data class PerceptionElement(
    val ref: String,
    val role: String,
    val text: String? = null,
    val contentDescription: String? = null,
    val packageName: String? = null,
    val centerX: Int? = null,
    val centerY: Int? = null,
    val bounds: String? = null,
    val clickable: Boolean = false,
    val editable: Boolean = false,
    val scrollable: Boolean = false,
    val checked: Boolean? = null,
    val selected: Boolean? = null,
    val enabled: Boolean? = null,
)

/**
 * Per-source state of one snapshot (spec §7.1): the host reports what each source could answer, not just what it did,
 * so the plugin can tell an empty source from an unavailable one.
 */
data class PerceptionCoverage(
    val screen: String,
    val notifications: String,
    val media: String,
    val calendar: String,
    val acoustic: String,
    val clipboard: String,
    val vision: String,
    val glasses: String,
    val device: String,
)

/** One bounded read of the device's current perception (spec §7.1). Every absent fact is `null` or empty, never guessed. */
data class PerceptionSnapshot(
    val snapshotId: String,
    val capturedAtEpochMillis: Long,
    val foregroundPackage: String? = null,
    val foregroundActivity: String? = null,
    val screenWidthPx: Int? = null,
    val screenHeightPx: Int? = null,
    val elements: List<PerceptionElement> = emptyList(),
    val notifications: List<NotificationItem> = emptyList(),
    val media: MediaState? = null,
    val calendarTitles: List<String> = emptyList(),
    val acoustic: String? = null,
    val clipboardText: String? = null,
    val coverage: PerceptionCoverage,
    val truncated: Boolean = false,
)

/** A screenshot the host produced, or an explicit reason it did not (spec §7.4). */
sealed interface PerceptionScreenshotResult {
    data class Image(val image: ImageRef, val mime: String, val width: Int, val height: Int) :
        PerceptionScreenshotResult
    data object Disabled : PerceptionScreenshotResult
    data class Unavailable(val reason: String) : PerceptionScreenshotResult
}

/**
 * The host's per-turn perception port, reached through [Capability.Perception].
 *
 * **Optional by design**: a host with no perception layer never grants the capability, and the plugin treats that as
 * "no perception block" — `runCatching { services.perception() }.getOrNull()` — never as a failed turn. The host owns
 * the turn boundary: [beginTurn] and [endTurn] fence one turn's captures, [afterAction] re-reads what an action
 * changed, and [screenshot] is requested separately because it is expensive and not always enabled.
 */
interface PerceptionProvider {
    fun beginTurn(): PerceptionSnapshot
    fun snapshot(include: Set<PerceptionSource>, filter: PerceptionFilter, limit: Int?): PerceptionSnapshot

    /**
     * 0.3 Phase 2 final review (C1): a **real** read of the device, for a caller that explicitly asked to perceive now
     * (`device.observe`, and the fresh snapshot a `STALE_REF` answer returns).
     *
     * [snapshot] is deliberately cache-only: the turn's opening read and the post-action read are the turn's captures,
     * and rebuilding them from a stale cache after the user changed the screen by hand would serve the old screen as if
     * it were current. [refresh] re-observes the screen, opens a new snapshot with a new id, re-assigns its refs and
     * caches it, so the answer is a real read and any ref minted before it is bound to an older snapshot.
     *
     * The default keeps every provider written before this method compiling, but does **not** refresh: a provider that
     * cannot observe on demand answers its current snapshot (the pre-fix behaviour). The Android host overrides it.
     */
    fun refresh(): PerceptionSnapshot =
        snapshot(setOf(PerceptionSource.SCREEN), PerceptionFilter.ALL, null)

    fun afterAction(): PerceptionSnapshot
    fun screenshot(): PerceptionScreenshotResult
    fun endTurn()
}
