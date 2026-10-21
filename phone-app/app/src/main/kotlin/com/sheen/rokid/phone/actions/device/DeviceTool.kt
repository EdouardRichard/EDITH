package com.sheen.rokid.phone.actions.device

import com.sheen.rokid.device.ActKind
import com.sheen.rokid.device.Bounds
import com.sheen.rokid.device.DeviceEnvelope
import com.sheen.rokid.device.DeviceErrorCodes
import com.sheen.rokid.device.DeviceKernel
import com.sheen.rokid.device.Foreground
import com.sheen.rokid.device.Observation
import com.sheen.rokid.device.Ref
import com.sheen.rokid.device.RefRegistry
import com.sheen.rokid.device.RefResult
import com.sheen.rokid.device.ScreenFilter
import com.sheen.rokid.device.Timing
import com.sheen.rokid.output.CompactSnapshotRenderer
import com.sheen.rokid.output.PerceptionCoverageRenderer
import com.sheen.rokid.plugin.ActionDescriptor
import com.sheen.rokid.plugin.ActionExecutor
import com.sheen.rokid.plugin.ActionOutcome
import com.sheen.rokid.plugin.ActionResult
import com.sheen.rokid.plugin.DeviceState
import com.sheen.rokid.plugin.DeviceStateProvider
import com.sheen.rokid.plugin.ImageRef
import com.sheen.rokid.plugin.MediaState
import com.sheen.rokid.plugin.NotificationItem
import com.sheen.rokid.plugin.PerceptionFilter
import com.sheen.rokid.plugin.PerceptionProvider
import com.sheen.rokid.plugin.PerceptionScreenshotResult
import com.sheen.rokid.plugin.PerceptionSnapshot
import com.sheen.rokid.plugin.PerceptionSource
import com.sheen.rokid.plugin.RiskLevel
import com.sheen.rokid.plugin.ToolExecution
import com.sheen.rokid.plugin.ToolObservation
import com.sheen.rokid.plugin.ToolOutcome
import com.sheen.rokid.plugin.ToolVision
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.security.MessageDigest

/**
 * The kernel surface [DeviceTool] needs, as a seam.
 *
 * The production implementation is [DeviceKernelOperations]; tests script it directly, so the
 * tool's argument handling, projection and envelope mapping are exercised without the kernel's
 * worker thread and real-time settle.
 */
interface DeviceOperations {
    val latestObservation: Observation?

    /**
     * Task 12 (R59/R57 fix): the kernel's monotonic observation counter, or `null` when the implementation has none.
     *
     * Additive default so every hand-scripted test implementation keeps compiling. It renders the one runtime-state
     * line and, more importantly, is the proof that a call really observed something: `fingerprintAfter` only reports a
     * post-call fingerprint when this counter advanced during the call. A `null` counter therefore means "cannot prove
     * an observation" and yields no fingerprint, never a stale one.
     */
    val revision: Long? get() = null

    /** Task 12 (R59): the kernel's monotonic write counter, or `null` when the implementation has none. */
    val generation: Long? get() = null

    fun observe(filter: ScreenFilter): DeviceEnvelope

    fun act(
        kind: ActKind,
        ref: Ref?,
        text: String?,
        value: String?,
        direction: String?,
        key: String?,
        timeoutMillis: Long,
    ): DeviceEnvelope

    fun screenshot(): DeviceEnvelope

    fun waitFor(text: String?, foreground: String?, idle: Boolean, timeoutMillis: Long): DeviceEnvelope

    fun status(): DeviceEnvelope

    fun cancel()

    fun beginTurn()

    fun close()
}

/** The production [DeviceOperations] over one [DeviceKernel]. */
class DeviceKernelOperations(private val kernel: DeviceKernel) : DeviceOperations {

    override val latestObservation: Observation?
        get() = kernel.latestObservation

    override val revision: Long?
        get() = kernel.revision

    override val generation: Long?
        get() = kernel.generation

    override fun observe(filter: ScreenFilter): DeviceEnvelope = kernel.observe(filter)

    override fun act(
        kind: ActKind,
        ref: Ref?,
        text: String?,
        value: String?,
        direction: String?,
        key: String?,
        timeoutMillis: Long,
    ): DeviceEnvelope = kernel.act(kind, ref, text, value, direction, key, timeoutMillis)

    override fun screenshot(): DeviceEnvelope = kernel.screenshot()

    override fun waitFor(text: String?, foreground: String?, idle: Boolean, timeoutMillis: Long): DeviceEnvelope =
        kernel.waitFor(text, foreground, idle, timeoutMillis)

    override fun status(): DeviceEnvelope = kernel.status()

    override fun cancel() = kernel.cancel()

    override fun beginTurn() = kernel.beginTurn()

    override fun close() = kernel.close()
}

/**
 * R40/R41/R46: the `device` host tool (spec §4.7).
 *
 * It is a **thin projection**, never a second device implementation: every operation goes through
 * [DeviceOperations], the kernel owns the channels and the settle, and this class only
 *  - parses the model-facing arguments (a schema without `via`/coordinates, D84),
 *  - runs the one tool-layer loop the kernel refuses to own (`scroll_to_text`, R46),
 *  - projects the kernel envelope into the §4.6 screen JSON: with a perception port the tree is
 *    [ActionResult.observation], and the legacy no-provider path keeps it in `data` as the only screen carrier
 *    (Task 8 removed every duplicated observation/execution field from `data`). The legacy path is a non-production
 *    composition — `HostCompositionRoot` always injects perception — and TurnTrimmer's ⓪ does not treat its `data.screen`
 *    as a UI tree.
 *
 * **Screenshot (R44).** This task keeps the kernel's `ImageRef("pending", ...)` and never inline
 * bytes; `region` and `annotate` are not advertised, because the artifact store and image pipeline
 * arrive in Task 13.
 *
 * **Outcome mapping.** `ok` from the kernel becomes [ActionOutcome.Done]; a failed envelope is
 * [ActionOutcome.Refused] when `error.recoverable` and [ActionOutcome.Failed] otherwise. The raw code survives in
 * [ToolExecution.code], with the outcome in [ToolExecution.outcome]. That three-state legacy projection cannot
 * express "the platform may still have applied it", so [ToolExecution] is the authoritative fact:
 * a kernel `ACTION_TIMEOUT` is [ToolOutcome.Uncertain] with `uncertain=true` and every tri-state
 * field unknown, and a kernel `CANCELLED` is [ToolOutcome.Cancelled].
 */
class DeviceTool(
    private val operations: DeviceOperations,
    private val projector: ScreenProjector,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000L },
    /**
     * edith-0.3 Phase 2 Task 7: the host's perception port, or null for every pre-Phase-2 composition.
     *
     * With a provider the tool's screens are the compact [PerceptionSnapshot] (see [CompactSnapshotRenderer]), an action
     * is gated by the turn's ref binding ([RefRegistry.resolveForAction]) and followed by a forced post-action read,
     * and `status` reports coverage/device facts instead of a screen tree. With null it keeps the pre-Phase-2
     * projection path byte-for-byte, which is what keeps every older caller and test unchanged.
     */
    private val perception: PerceptionProvider? = null,
    /** The device-state channel `status`/`observe(include=["device"])` read; null keeps those fields absent. */
    private val deviceState: DeviceStateProvider? = null,
    /**
     * The registry the provider binds refs into (the kernel's own, R36). Required for the act-time ref gate: when it
     * is null the tool cannot prove the ref still belongs to the current screen, so the kernel makes that decision.
     */
    private val registry: RefRegistry? = null,
    /** The app in front, read at act time; the same lambda the provider and composition use. */
    private val foreground: () -> Foreground? = { null },
    /** Epoch millis for the ref TTL check; injectable so the TTL boundary is testable without sleeping. */
    private val nowMillis: () -> Long = { System.currentTimeMillis() },
    /** The turn's ref TTL policy; the composition passes `PerceptionPolicy.refTtlMillis`. */
    private val refTtlMillis: () -> Long = { 20_000L },
    /** edith-0.3 Phase 2: the vision switch. Off answers `VISION_DISABLED` without capturing anything. */
    private val visionEnabled: () -> Boolean = { true },
) : ActionExecutor {

    /** The filter the last observe used; the kernel's auto-observation inherits it. */
    private var lastFilter: ScreenFilter = ScreenFilter.INTERACTIVE

    override fun descriptors(): List<ActionDescriptor> = listOf(DESCRIPTOR)

    override fun cancel() = operations.cancel()

    /**
     * R42: a new turn re-arms the kernel's turn-scoped cancellation **and** starts the model-facing
     * projection from a full observation again. The previous turn's diff baseline is not useful to a
     * model that has not seen that screen in this turn.
     */
    override fun beginTurn() {
        projector.reset()
        operations.beginTurn()
    }

    fun close() = operations.close()

    /**
     * Task 12 (R59): one plain-state line for the loop's non-persistent `RUNTIME_STATE` segment, or `null` before the
     * first observation.
     *
     * Only state that the kernel really knows is reported — foreground package/window, the latest observation revision
     * and the write generation. Steps/observations spent and typed-but-unsent text belong to the loop/IME, not here, so
     * they are not invented. `null` is the honest answer when there is no observation yet.
     */
    override fun runtimeState(): String? {
        val observation = operations.latestObservation ?: return null
        val foregroundWindow = observation.windows.firstOrNull { it.packageName != null }
            ?: observation.windows.firstOrNull()
        val text = buildString {
            append("foreground=").append(foregroundWindow?.packageName ?: "?")
            append(" window=").append(foregroundWindow?.id ?: 0)
            append(" revision=").append(operations.revision ?: observation.revision)
            operations.generation?.let { append(" generation=").append(it) }
        }
        return text.takeIf { it.length <= RUNTIME_STATE_MAX_CHARS }
    }

    /** The tool's own call shape (one argument object); [ActionExecutor.execute] adds the name. */
    fun execute(argsJson: String): ActionResult = execute(NAME, argsJson)

    /**
     * edith-0.3 Phase 2 Task 7: true only when this call is `device.status`.
     *
     * [ToolCatalog] uses this answer to keep `status` outside the unlock gate and the accessibility refusal: a locked
     * phone with a dead accessibility service is exactly the state `status` exists to report, so gating it would hide
     * the diagnosis behind the thing it is meant to diagnose. The parse mirrors [execute]'s own rule (an object with an
     * `action` token, trimmed/lower-cased), so a status call with extra members - e.g. the `include` this task adds -
     * is still exempt; a malformed or non-status call is not.
     */
    fun isStatus(argsJson: String): Boolean =
        parseObject(argsJson)?.stringArg("action")?.trim()?.lowercase() == ACTION_STATUS

    override fun execute(name: String, argsJson: String): ActionResult {
        if (name != NAME) return refused(UNKNOWN_ACTION)
        val args = parseObject(argsJson) ?: return refused(INVALID_ARGUMENTS)
        val action = args.stringArg("action")?.trim()?.lowercase() ?: return refused(INVALID_ARGUMENTS)
        return when (action) {
            ACTION_OBSERVE -> observe(args)
            ACTION_ACT -> act(args)
            ACTION_SCREENSHOT -> screenshot()
            ACTION_WAIT -> wait(args)
            ACTION_STATUS -> status(args)
            else -> refused(INVALID_ARGUMENTS)
        }
    }

    private fun observe(args: JsonObject): ActionResult {
        val filter = when (args.stringArg("filter")?.trim()?.lowercase() ?: FILTER_INTERACTIVE) {
            FILTER_INTERACTIVE -> ScreenFilter.INTERACTIVE
            FILTER_TEXT -> ScreenFilter.TEXT
            FILTER_ALL -> ScreenFilter.ALL
            else -> return refused(INVALID_ARGUMENTS)
        }
        lastFilter = filter
        val provider = perception
        return if (provider == null) {
            val revisionBefore = operations.revision
            val envelope = operations.observe(filter)
            // Legacy non-production composition: there is no perception port, so the projector's tree is the only screen
            // carrier and stays in `data` (it is not duplicated — no observation exists). Task 4's ruling stands: no
            // observation is fabricated without a real source. Production always injects perception.
            result(envelope, project(envelope, filter), fingerprintAfter(envelope, revisionBefore))
        } else {
            val include = parseInclude(args, DEFAULT_OBSERVE_INCLUDE, OBSERVE_SOURCES)
                ?: return refused(INVALID_ARGUMENTS)
            val limit = if (args.containsKey(LIMIT_KEY)) {
                parseLimit(args) ?: return refused(INVALID_ARGUMENTS)
            } else {
                null
            }
            // Final review C1: an explicit observe is a real read. `snapshot()` is the turn's cache-only projection, so
            // after a manual screen change it would answer the stale opening screen (and re-stamp its refs as current).
            // Refresh first, then project the caller's include/filter/limit from the new snapshot.
            provider.refresh()
            val snapshot = provider.snapshot(include, filter.toPerceptionFilter(), limit)
            // 0.3 Phase 2.1 Task 3: only a screen read has something to look at; any other include returns a snapshot
            // whose `vision` would be a capture the model never asked for.
            val wantsScreen = PerceptionSource.SCREEN in include
            val capture = if (wantsScreen) captureVision() else null
            // 0.3 Phase 2.2 Task 4: the same snapshot and the same single capture are the structured observation, so
            // the text, the picture and `vision` can never describe two different reads. M2: a non-screen include has
            // no screen to report (`screen=null`), and an unavailable screen read is never rendered as an empty screen.
            val observation = observeForResult(
                capture = wantsScreen,
                visionRequested = wantsScreen,
                snapshot = snapshot,
                captureResult = capture,
                wantsScreen = wantsScreen,
            )
            // Task 8: the screen tree, coverage, foreground and vision live in `observation` only; `data` keeps the
            // tool-specific non-screen sources the caller asked for.
            result(
                DeviceEnvelope.success(
                    action = ACTION_OBSERVE,
                    channel = CHANNEL_ACCESSIBILITY,
                    accepted = true,
                    applied = null,
                    verified = null,
                    timing = Timing(0, 0, 0),
                ),
                observationData(snapshot, include),
                extraImages = capturedImage(capture),
                observation = observation,
            )
        }
    }

    private fun act(args: JsonObject): ActionResult {
        val kindToken = args.stringArg("kind")?.trim()?.lowercase() ?: return refused(INVALID_ARGUMENTS)
        val kind = KIND_BY_TOKEN[kindToken] ?: return refused(INVALID_ARGUMENTS)
        val timeout = args.longArg("timeout_ms") ?: DEFAULT_TIMEOUT_MILLIS
        if (timeout <= 0L) return refused(INVALID_ARGUMENTS)
        if (kind == ActKind.SCROLL_TO_TEXT) return scrollToText(args, timeout)
        val ref = args.stringArg("ref")?.trim()?.takeIf { it.isNotEmpty() }?.let(::normalizedRef)
        val resolution = ref?.let(::resolveForAction)
        if (resolution is RefResult.Stale) return staleRefAnswer(resolution)
        val resolvedRef = (resolution as? RefResult.Ok)?.ref ?: ref
        val revisionBefore = operations.revision
        val envelope = operations.act(
            kind = kind,
            ref = resolvedRef,
            text = args.stringArg("text"),
            value = args.stringArg("value"),
            direction = args.stringArg("direction"),
            key = args.stringArg("key"),
            timeoutMillis = timeout,
        )
        return actResult(envelope, revisionBefore)
    }

    /**
     * Task 7's action-time ref gate, in the brief's exact order.
     *
     * Before anything reaches the kernel, the latest observation is resolved through the registry bound by the turn
     * (ref identity, foreground, TTL and snapshot id). When the kernel has not observed yet, one `observe(ALL)` is made so the gate has
     * something to resolve against; if it still cannot observe, the gate is skipped and the kernel's own readiness /
     * ref checks decide. A [RefResult.Stale] is answered with `STALE_REF` plus a fresh compact snapshot and the action
     * is **not** dispatched; [RefResult.Ok] returns the (possibly re-bound) ref the kernel should act on.
     *
     * The fresh snapshot is a real [PerceptionProvider.refresh] read (final review C1), not `afterAction()` and not the
     * cached `snapshot()`: the stale ref may have gone stale because the user changed the screen by hand, and a
     * cache-only answer would re-serve the old screen and re-stamp its refs as current. `afterAction` stays the
     * post-act read, where the action itself produced the observation.
     */
    private fun staleRefAnswer(stale: RefResult.Stale): ActionResult {
        val provider = checkNotNull(perception) {
            "STALE_REF is only reachable when the perception provider owns the ref gate"
        }
        val snapshot = provider.refresh()
        // Task 3: the refreshed screen is exactly what the model needs to re-target, so the refusal carries its image
        // and the same vision marker an observe would. Task 8: that screen is the observation; `data` repeats none of it.
        val capture = captureVision()
        // Task 4: the action was never dispatched (accepted/applied false), and the refreshed read is the observation.
        val observation = observeForResult(
            capture = true,
            visionRequested = true,
            snapshot = snapshot,
            captureResult = capture,
        )
        return ActionResult(
            ActionOutcome.Refused,
            "{}",
            images = capturedImage(capture),
            nonPersistent = true,
            execution = ToolExecution(
                outcome = ToolOutcome.Refused,
                code = STALE_REF,
                message = stale.reason,
                recoverable = true,
                accepted = false,
                applied = false,
                verified = null,
                changed = null,
            ),
            observation = observation,
        )
    }

    /** The [RefResult.Ok] resolution when this call is gated, or null when the gate does not apply/cannot decide. */
    private fun resolveForAction(ref: Ref): RefResult? {
        if (perception == null) return null
        val actionRegistry = registry ?: return null
        var observation = operations.latestObservation
        if (observation == null) {
            operations.observe(ScreenFilter.ALL)
            observation = operations.latestObservation
        }
        if (observation == null) return null
        val currentForeground = runCatching { foreground() }.getOrNull()
        return actionRegistry.resolveForAction(
            observation = observation,
            ref = ref,
            nowEpochMillis = nowMillis(),
            foregroundPackage = currentForeground?.packageName,
            foregroundActivity = currentForeground?.activity,
            ttlMillis = refTtlMillis(),
        )
    }

    /**
     * One dispatched act's result, exactly like the plain action path: project the envelope, then replace the
     * model-facing screen with the forced [PerceptionProvider.afterAction] read.
     *
     * Shared by [act] and [scrollToText] so every reachable act return gets the same post-processing (review C1: the
     * scroll loop used to bypass it, leaking the projector's `bounds=` text and leaving the provider cache one screen
     * behind). `changed` is derived from the envelope this call actually returns - never assumed from the dispatch.
     */
    private fun actResult(envelope: DeviceEnvelope, revisionBefore: Long?, partial: Boolean = false): ActionResult {
        val snapshot = perception?.afterAction()
        val projected = project(envelope, lastFilter)
        // Task 3: the capture follows `afterAction()`, so the image is the screen the action produced. `scrollToText`
        // reaches this through its single returned envelope, which is why the loop never captures per inner scroll.
        val capture = captureVision()
        // Task 4: the forced post-action read and the one capture above are this result's observation. A dispatched act
        // always carries one, on success and on failure alike; without a perception port (a non-production composition)
        // there is no honest observation and the legacy projector's tree stays the only screen carrier in `data`.
        val observation = snapshot?.let {
            observeForResult(
                capture = true,
                visionRequested = true,
                snapshot = it,
                captureResult = capture,
            )
        }
        return result(
            envelope,
            if (snapshot == null) projected else actData(projected, envelope),
            fingerprintAfter(envelope, revisionBefore),
            stateChanging = true,
            extraImages = capturedImage(capture),
            observation = observation,
            // Spec §7.7: an accepted write that provably changed nothing is a partial execution, not a clean success.
            partial = partial || envelope.error?.code == DeviceErrorCodes.ACCEPTED_NO_EFFECT,
        )
    }

    /**
     * Task 3: the automatic vision capture, or `null` when this composition has no perception port.
     *
     * The user's switch and the profile's image capability are gates inside [PerceptionProvider.screenshot] (Task 2),
     * so the tool does not duplicate them: [PerceptionScreenshotResult.Disabled] and
     * [PerceptionScreenshotResult.Unavailable] are answers, never exceptions. The caller captures **after** the
     * observation it decorates, so the image and the text describe the same screen.
     *
     * Final fix: a provider that throws anyway is still only "no image" (`Unavailable("SCREENSHOT_FAILED")`). The
     * provider contract says it never throws, but a broken capture port must not turn a successful observe/act into
     * `TOOL_THREW`; the operation itself already succeeded.
     */
    private fun captureVision(): PerceptionScreenshotResult? = perception?.let { provider ->
        runCatching { provider.screenshot() }
            .getOrElse { PerceptionScreenshotResult.Unavailable(SCREENSHOT_FAILED) }
    }

    /**
     * Task 8: the tool-specific metadata `data` keeps for one kernel projection when the observation already carries
     * the screen (a perception composition).
     *
     * The rendered tree is **not** here: [ToolObservation.screen] is its one canonical carrier. Only the metadata the
     * observation model cannot express — the kernel revision, the IME flag and the full/diff change marker — survives,
     * and `app` is deliberately read from `observation.foregroundPackage` instead of being repeated.
     *
     * The legacy composition (no perception port; non-production) keeps the whole projection instead: there the tree is
     * the **only** screen carrier, so dropping it would lose the screen rather than de-duplicate it (Task 4's "no
     * fabricated observation" ruling stands). TurnTrimmer's ⓪ only reads `observation.screen`, so this path's tree is
     * deliberately not compressed; production never reaches it.
     */
    private fun legacyData(projected: JsonObject): JsonObject {
        val screen = projected[SCREEN_KEY] as? JsonObject
            ?: return buildJsonObject {
                (projected[DIAGNOSTIC_KEY] as? JsonPrimitive)?.let { put(DIAGNOSTIC_KEY, it) }
            }
        return buildJsonObject {
            (screen[REV_KEY] as? JsonPrimitive)?.let { put(REV_KEY, it) }
            (screen[IME_KEY] as? JsonPrimitive)?.let { put(IME_KEY, it) }
            (screen[CHANGES_KEY] as? JsonObject)?.let { put(CHANGES_KEY, it) }
        }
    }

    /**
     * Task 8: the tool-specific half of one `act` result that has a perception observation.
     *
     * The compact observation is the post-action screen and a kernel diff would describe a frame the model no longer
     * receives, so the change marker is the explicit full/empty one. `data.changed` is gone: [ToolExecution.changed] is
     * the one authoritative carrier and an unknown value must never be written as `false`.
     */
    private fun actData(projected: JsonObject, envelope: DeviceEnvelope): JsonObject {
        val screen = projected[SCREEN_KEY] as? JsonObject
        return buildJsonObject {
            if (screen == null) {
                (projected[DIAGNOSTIC_KEY] as? JsonPrimitive)?.let { put(DIAGNOSTIC_KEY, it) }
            } else {
                (screen[REV_KEY] as? JsonPrimitive)?.let { put(REV_KEY, it) }
                (screen[IME_KEY] as? JsonPrimitive)?.let { put(IME_KEY, it) }
                put(CHANGES_KEY, emptyChanges())
            }
            val changed = envelope.applied == true
            if (!changed && (envelope.applied == false ||
                    envelope.error?.code == DeviceErrorCodes.ACCEPTED_NO_EFFECT)) {
                put(NOTE_KEY, OBSERVE_FIRST_NOTE)
            }
        }
    }

    /** The empty `mode=full` marker the post-action observation needs (no diff lines to describe). */
    private fun emptyChanges(): JsonObject = buildJsonObject {
        put("mode", "full")
        put("added", buildJsonArray { })
        put("updated", buildJsonArray { })
        put("removed", buildJsonArray { })
    }

    /** Task 3: the captured artifact, if the capture produced one; a disabled/unavailable capture adds no image. */
    private fun capturedImage(capture: PerceptionScreenshotResult?): List<ImageRef> =
        listOfNotNull((capture as? PerceptionScreenshotResult.Image)?.image)

    /**
     * edith-0.3 Phase 2.2 Task 4: the one post-call observation builder for every `device` result.
     *
     * It is deliberately **non-throwing** and returns `null` only when this composition has no perception port (or the
     * cache projection is unavailable): a tool that cannot observe must say nothing rather than invent a screen. The
     * observation is rendered from a [PerceptionSnapshot] - normally the exact read the result already projected - so
     * its text is the same compact screen the model sees and never a second, disagreeing capture. It carries no image:
     * pictures stay on [ActionResult.images], and [visionOf]/[visionReasonOf] only say what happened to the picture.
     *
     * [visionRequested] distinguishes "this call does not want a picture" (`status`/`wait` success, a
     * notification-only observe: `vision=not_requested`, no picture) from "a picture was wanted but was not taken"
     * (argument refusals: `vision=unavailable(not_attempted)`). `vision=ok` is reported only when an image really is
     * attached, and a disabled or failed capture keeps its own honest [ToolVision] value instead.
     */
    private fun observeForResult(capture: Boolean, visionRequested: Boolean): ToolObservation? =
        observeForResult(capture, visionRequested, snapshot = null, captureResult = null)

    /**
     * The capture-aware half of [observeForResult]: [snapshot] is the read this result already made (or `null` to
     * project the provider's current cache), and [captureResult] is the one capture the caller attaches. Passing the
     * capture in is what keeps a result to **at most one image**: the helper never takes a second picture.
     *
     * edith-0.3 Phase 3 M2 adds the two facts that keep a broken or blind read from looking like a good one:
     *  - a provider that throws while projecting answers an **explicit** `unavailable(provider_threw)` observation
     *    instead of `null` ("no observation" must mean "no perception port", not "the port failed quietly"), unless
     *    this result already attached a usable picture: [ToolVision] describes the picture channel, so that fallback
     *    reports `ok` rather than contradicting its own image ([projectionFallbackObservation]);
     *  - [wantsScreen] false, or a screen whose coverage is neither `ok` nor `truncated`, clears `screen` and
     *    `truncated` instead of rendering an unavailable read as an empty (or stale) screen.
     *
     * [projectionFallback] is the one caller that wants `null` back from a broken cache projection: the explicit
     * screenshot path first tries the cache, then a real [PerceptionProvider.refresh] read (review important 2), and
     * only a second failure becomes the explicit `provider_threw` observation.
     */
    private fun observeForResult(
        capture: Boolean,
        visionRequested: Boolean,
        snapshot: PerceptionSnapshot?,
        captureResult: PerceptionScreenshotResult?,
        wantsScreen: Boolean = true,
        projectionFallback: Boolean = true,
    ): ToolObservation? {
        val provider = perception ?: return null
        val current = try {
            // Cache-only on purpose: the operation above already made this result's real read; this projects it.
            snapshot ?: provider.snapshot(OBSERVATION_SOURCES, lastFilter.toPerceptionFilter(), null)
        } catch (t: Throwable) {
            return if (projectionFallback) projectionFallbackObservation(capture, captureResult) else null
        }
        val screenReadable = current.coverage.screen == SCREEN_COVERAGE_OK ||
            current.coverage.screen == SCREEN_COVERAGE_TRUNCATED
        val includeScreen = wantsScreen && screenReadable
        return try {
            val shot = if (capture) (captureResult ?: captureVision()) else null
            ToolObservation(
                snapshotId = current.snapshotId.takeIf { it.isNotEmpty() },
                capturedAtEpochMillis = current.capturedAtEpochMillis,
                foregroundPackage = current.foregroundPackage,
                foregroundActivity = current.foregroundActivity,
                coverage = PerceptionCoverageRenderer.render(current.coverage),
                screen = if (includeScreen) CompactSnapshotRenderer.render(current) else null,
                truncated = includeScreen && CompactSnapshotRenderer.screenTruncated(current),
                vision = visionOf(capture, visionRequested, shot),
                visionReason = visionReasonOf(capture, visionRequested, shot),
            )
        } catch (t: Throwable) {
            if (projectionFallback) projectionFallbackObservation(capture, captureResult) else null
        }
    }

    /**
     * M2 + review important: the fallback for a provider that threw while projecting, from the picture channel's side.
     *
     * The projection failed, so there is no screen to report (`screen = null`, `truncated = false`). What the result
     * *does* know is whether it already attached the one capture it took: [ToolVision] says "whether the observation
     * carries a picture the model can use", so an attached [PerceptionScreenshotResult.Image] must answer
     * `ok` with no reason instead of `unavailable(provider_threw)` beside that same image - the contradiction the
     * explicit screenshot path already reconciles. Every fallback without a picture keeps the mandated
     * `unavailable(provider_threw)`.
     */
    private fun projectionFallbackObservation(
        capture: Boolean,
        captureResult: PerceptionScreenshotResult?,
    ): ToolObservation = if (capture && captureResult is PerceptionScreenshotResult.Image) {
        ToolObservation(
            vision = ToolVision.Ok,
            visionReason = null,
            screen = null,
            truncated = false,
        )
    } else {
        providerThrewObservation()
    }

    /**
     * M2: a provider that exists but threw is an explicit observation, never a silent `null`.
     *
     * `screen = null`/`truncated = false` are the honest facts: nothing was read. The reason is the stable
     * `provider_threw` token, so the model can tell it from `no_accessibility` or a screenshot failure.
     */
    private fun providerThrewObservation(): ToolObservation = ToolObservation(
        vision = ToolVision.Unavailable,
        visionReason = VISION_PROVIDER_THREW,
        screen = null,
        truncated = false,
    )

    /** Task 4: the vision fact of one observation; [ToolVision.Ok] is impossible without a real captured image. */
    private fun visionOf(
        capture: Boolean,
        visionRequested: Boolean,
        shot: PerceptionScreenshotResult?,
    ): ToolVision = when {
        capture && shot is PerceptionScreenshotResult.Image -> ToolVision.Ok
        capture && shot == PerceptionScreenshotResult.Disabled -> ToolVision.Disabled
        capture -> ToolVision.Unavailable
        visionRequested -> ToolVision.Unavailable
        else -> ToolVision.NotRequested
    }

    /**
     * Task 4: the stable reason that accompanies a non-[ToolVision.Ok] observation, or `null` when none is needed.
     *
     * A `capture` with a `null` shot is unreachable: [observeForResult] only builds an observation for a non-null
     * provider, and [captureVision] answers for every such provider (a throw becomes `Unavailable(SCREENSHOT_FAILED)`),
     * so there is no separate "capture was wanted but the port gave no answer" reason to report.
     */
    private fun visionReasonOf(
        capture: Boolean,
        visionRequested: Boolean,
        shot: PerceptionScreenshotResult?,
    ): String? = when {
        capture && shot is PerceptionScreenshotResult.Unavailable -> shot.reason
        capture -> null
        visionRequested -> VISION_NOT_ATTEMPTED
        else -> null
    }

    /**
     * R46: one scroll + one bounded wait per round, stopping on the first hit.
     *
     * The loop is deliberately here rather than in the kernel: the kernel's `SCROLL_TO_TEXT` is one
     * scroll step, and the tool owns the overall `timeout_ms` budget. A kernel error that is not
     * "the screen did not change" ends the loop immediately; a `VERIFY_TIMEOUT` from one round only
     * means "keep scrolling until the budget is spent", and the last envelope keeps the final
     * observation (`observation.screen` is projected from it, Task 8).
     */
    /**
     * A model sometimes sends the rendered ref as `n12` instead of `#n12`; the registry only knows the `#` form. Accept
     * both for the exact `n<number>` shape (and leave any other value untouched so an invalid ref is still refused by
     * the kernel rather than silently rewritten).
     */
    private fun normalizedRef(raw: String): Ref =
        Ref(if (raw.startsWith("#")) raw else if (BARE_REF.matches(raw)) "#$raw" else raw)

    private fun scrollToText(args: JsonObject, timeoutMillis: Long): ActionResult {
        val text = args.stringArg("text")?.trim()?.takeIf { it.isNotEmpty() }
            ?: return refused(INVALID_ARGUMENTS)
        val direction = args.stringArg("direction")?.trim()?.takeIf { it.isNotEmpty() } ?: "forward"
        val started = clock()
        val revisionBefore = operations.revision
        var last: DeviceEnvelope? = null
        // Task 4 (spec §7.7): a scroll that the platform accepted but whose target was never verified is a *partial*
        // execution, not a clean failure. Only an accepted dispatch proves that scroll work actually happened.
        var dispatched = false
        do {
            val remaining = (timeoutMillis - (clock() - started)).coerceAtLeast(1L)
            val scrolled = operations.act(
                kind = ActKind.SCROLL_TO_TEXT,
                ref = null,
                text = text,
                value = null,
                direction = direction,
                key = null,
                timeoutMillis = remaining,
            )
            if (scrolled.accepted || scrolled.ok) dispatched = true
            if (!scrolled.ok && scrolled.error?.code != DeviceErrorCodes.ACCEPTED_NO_EFFECT) {
                return actResult(scrolled.copy(action = ACTION_ACT), revisionBefore, partial = dispatched)
            }
            val waited = operations.waitFor(text, null, false, remaining)
            last = waited
            if (waited.ok) {
                return actResult(waited.copy(action = ACTION_ACT), revisionBefore)
            }
            if (waited.error?.code != DeviceErrorCodes.VERIFY_TIMEOUT) {
                return actResult(waited.copy(action = ACTION_ACT), revisionBefore, partial = dispatched)
            }
        } while (clock() - started < timeoutMillis)
        val envelope = last ?: operations.waitFor(text, null, false, 1L)
        return actResult(envelope.copy(action = ACTION_ACT), revisionBefore, partial = dispatched)
    }

    private fun screenshot(): ActionResult {
        // Task 7: the vision switch is checked before the kernel, so a disabled vision never captures, stores or
        // advertises an image. The refusal names the switch, which is more actionable than a channel error.
        if (!runCatching { visionEnabled() }.getOrDefault(false)) {
            return refused(VISION_DISABLED, vision = ToolVision.Disabled, visionReason = null)
        }
        val envelope = operations.screenshot()
        val kernelDiagnostic = envelope.data?.takeIf { it.isNotBlank() }
        // Task 8: `data` keeps only the tool-specific diagnostic. The pictures are [ActionResult.images] and the screen
        // state is [ActionResult.observation]; `data.screen=null` was a third, redundant copy of both.
        val data = if (envelope.ok) {
            buildJsonObject {
                // R131: the kernel's `diagnostic=screenshot_artifact_pending; reason=…` is the only honest record that
                // the capture worked and the store did not. A generic sentence here would overwrite the failure reason
                // and make a broken artifact store indistinguishable from a normal pending reference.
                put(DIAGNOSTIC_KEY, kernelDiagnostic
                    ?: "screenshot is an artifact reference; bytes are not inlined")
            }
        } else {
            buildJsonObject {
                // Fix review: the failure's reason is `execution.code`; repeating it as `data.diagnostic` was a second
                // copy. Only a kernel diagnostic that adds information beyond the code is kept here.
                val errorCode = envelope.error?.code
                if (kernelDiagnostic != null && kernelDiagnostic != errorCode) {
                    put(DIAGNOSTIC_KEY, kernelDiagnostic)
                }
            }
        }
        // Task 4: the explicit screenshot is its own image, so it never runs the auto-vision capture. Its observation
        // reports that image as `vision=ok` only when the reference is really resolvable: the kernel reports a failed
        // artifact store as a successful screenshot with the `pending` placeholder (empty sha256), and naming that a
        // usable image would be the same lie `PerceptionScreenshotMapping` already refuses to tell. A failed action
        // reports `unavailable(<kernel code>)` and never fabricates a picture.
        val observation = observationForScreenshot()?.let { base ->
            val image = resolvableImage(envelope)
            when {
                image != null -> base.copy(vision = ToolVision.Ok, visionReason = null)
                envelope.ok && envelope.images.isNotEmpty() ->
                    base.copy(vision = ToolVision.Unavailable, visionReason = SCREENSHOT_PENDING)
                envelope.ok -> base.copy(vision = ToolVision.Unavailable, visionReason = SCREENSHOT_FAILED)
                else -> base.copy(
                    vision = ToolVision.Unavailable,
                    visionReason = envelope.error?.code ?: SCREENSHOT_FAILED,
                )
            }
        }
        return result(envelope, data, observation = observation)
    }

    /**
     * Fix review important 2: the screenshot's observation, from the cached projection normally.
     *
     * A provider that throws from its cache projection is retried with one real [PerceptionProvider.refresh] read
     * (the screenshot has a picture either way, so the screen facts must be a real read rather than a gap). M2: a
     * second throw no longer leaves the observation `null` - it answers the explicit `unavailable(provider_threw)`
     * observation, because a `Done` result with a provider present must not silently drop its observation.
     */
    private fun observationForScreenshot(): ToolObservation? {
        observeForResult(
            capture = false,
            visionRequested = false,
            snapshot = null,
            captureResult = null,
            // The cache projection may be retried with a real read below; only that second failure is reported.
            projectionFallback = false,
        )?.let { return it }
        val provider = perception ?: return null
        val snapshot = runCatching { provider.refresh() }.getOrNull()
            ?: return providerThrewObservation()
        return observeForResult(
            capture = false,
            visionRequested = false,
            snapshot = snapshot,
            captureResult = null,
        )
    }

    /** The first image whose artifact the media source can resolve, or `null` for a failed/pending capture. */
    private fun resolvableImage(envelope: DeviceEnvelope): ImageRef? =
        envelope.images.firstOrNull()
            ?.takeIf { it.artifactId != PENDING_ARTIFACT_ID && it.sha256.isNotBlank() }

    private fun wait(args: JsonObject): ActionResult {
        val text = args.stringArg("text")?.trim()?.takeIf { it.isNotEmpty() }
        val foreground = args.stringArg("foreground")?.trim()?.takeIf { it.isNotEmpty() }
        val idle = args.booleanArg("idle") ?: false
        if (text == null && foreground == null && !idle) return refused(INVALID_ARGUMENTS)
        val timeout = args.longArg("timeout_ms") ?: DEFAULT_TIMEOUT_MILLIS
        if (timeout <= 0L) return refused(INVALID_ARGUMENTS)
        val envelope = operations.waitFor(text, foreground, idle, timeout)
        // Task 4: a successful wait answers the current screen as *text* (`vision=not_requested`) and takes no picture;
        // a failed wait is a screen-tool failure and gets the current read plus one best-effort image. The kernel's wait
        // does not update the perception cache, so the observation is a real read instead of the turn's opening screen.
        // Fix review important 2: a throwing refresh must not lose the observation - the helper falls back to the cache
        // projection - and the failure's picture is best-effort even when that refresh threw.
        val provider = perception
        val snapshot = provider?.let { runCatching { it.refresh() }.getOrNull() }
        val capture = if (provider != null && !envelope.ok) captureVision() else null
        val observation = if (provider == null) {
            null
        } else {
            observeForResult(
                capture = !envelope.ok,
                visionRequested = !envelope.ok,
                snapshot = snapshot,
                captureResult = capture,
            )
        }
        return result(
            envelope,
            if (provider == null) project(envelope, lastFilter) else legacyData(project(envelope, lastFilter)),
            extraImages = capturedImage(capture),
            observation = observation,
        )
    }

    private fun status(args: JsonObject): ActionResult {
        val envelope = operations.status()
        val provider = perception
        val data: JsonObject
        val snapshot: PerceptionSnapshot?
        if (provider == null) {
            // R41: keep the kernel's key=value diagnostic line verbatim, just inside a JSON object.
            data = buildJsonObject {
                put(STATUS_KEY, envelope.data ?: envelope.error?.code ?: "")
            }
            snapshot = null
        } else {
            val include = parseStatusInclude(args) ?: return refused(INVALID_ARGUMENTS)
            snapshot = provider.snapshot(include, PerceptionFilter.ALL, null)
            data = statusData(envelope, snapshot, include)
        }
        // Task 4: `status` is not a screen read, so its screen text stays null - coverage/foreground/device facts are
        // the observation. Success takes no image; a failed status still answers with a real read and one best-effort
        // image, like every other screen-tool failure.
        val fresh = if (provider != null && snapshot != null && !envelope.ok) {
            runCatching { provider.refresh() }.getOrNull() ?: snapshot
        } else {
            snapshot
        }
        val capture = if (fresh != null && !envelope.ok) captureVision() else null
        val observation = fresh?.let {
            observeForResult(
                capture = !envelope.ok,
                visionRequested = !envelope.ok,
                snapshot = it,
                captureResult = capture,
                // A status success reports no screen, so no `screen` fact and no screen truncation may survive: M2
                // folds the old copy-after-the-fact into the one observation builder.
                wantsScreen = !envelope.ok,
            )
        }
        // Final review C2 (re-review): [result] marks every device result non-persistent, including this one and the
        // provider-less diagnostic-only branch above.
        return result(envelope, data, extraImages = capturedImage(capture), observation = observation)
    }

    /**
     * Task 7's `status`: device state + coverage, never the screen tree.
     *
     * `status` is the cheap "where am I" question; repeating the screen would make it an `observe` with the wrong name
     * and could cost the model the whole element budget for facts it already has. The kernel's one-line diagnostic is
     * kept verbatim (it is what reports `locked=`/`accessibility=`), and the requested notification/media content is
     * added beside it. All content stays in the model-facing result only - this class never logs a value.
     */
    private fun statusData(
        envelope: DeviceEnvelope,
        snapshot: PerceptionSnapshot,
        include: Set<PerceptionSource>,
    ): JsonObject {
        val state = deviceStateOrNull()
        // Task 8: coverage and foreground are observation facts; `data` keeps the ambient sources status really adds.
        return buildJsonObject {
            put(STATUS_KEY, envelope.data ?: envelope.error?.code ?: "")
            put(DEVICE_KEY, state?.let(::renderDeviceState))
            put(MEDIA_KEY, mediaJson(snapshot.media ?: state?.media))
            put(PANEL_KEY, state?.panelVisible)
            put(GLASSES_KEY, glassesJson(state))
            if (PerceptionSource.NOTIFICATIONS in include) {
                put(NOTIFICATIONS_KEY, notificationsJson(snapshot.notifications))
            }
        }
    }

    /** One `observe` result's non-screen sources. Task 8: the screen itself is `observation.screen` only. */
    private fun observationData(snapshot: PerceptionSnapshot, include: Set<PerceptionSource>): JsonObject =
        buildJsonObject {
            if (PerceptionSource.NOTIFICATIONS in include) {
                put(NOTIFICATIONS_KEY, notificationsJson(snapshot.notifications))
            }
            if (PerceptionSource.MEDIA in include) {
                put(MEDIA_KEY, mediaJson(snapshot.media))
            }
            if (PerceptionSource.CALENDAR in include) {
                put(
                    CALENDAR_KEY,
                    buildJsonArray { snapshot.calendarTitles.forEach { add(JsonPrimitive(it)) } },
                )
            }
            if (PerceptionSource.ACOUSTIC in include) {
                put(ACOUSTIC_KEY, snapshot.acoustic)
            }
            if (PerceptionSource.CLIPBOARD in include) {
                put(CLIPBOARD_KEY, snapshot.clipboardText)
            }
            if (PerceptionSource.GLASSES in include) {
                put(GLASSES_KEY, glassesJson(deviceStateOrNull()))
            }
            if (PerceptionSource.DEVICE in include) {
                put(DEVICE_KEY, deviceStateOrNull()?.let(::renderDeviceState))
            }
        }

    private fun deviceStateOrNull(): DeviceState? =
        deviceState?.let { runCatching { it.state() }.getOrNull() }

    /**
     * The compact device line (spec §7.3's `device:` row): every non-null fact, joined into one line.
     * A fact the platform did not report is omitted - the coverage line already says whether the source is complete.
     *
     * M3: `foreground=` is deliberately **not** here. The observation already carries `foregroundPackage` /
     * `foregroundActivity`, so repeating it in `data.device` was a second copy of one fact (and could disagree with the
     * observation). [runtimeState] keeps its own `foreground=` because that line is the loop's `RUNTIME_STATE`, a
     * different carrier, not `data`.
     */
    private fun renderDeviceState(state: DeviceState): String = buildString {
        append("time=").append(state.localTime)
        append(" date=").append(state.localDate)
        append(" weekday=").append(state.weekday)
        append(" tz=").append(state.timeZone)
        state.batteryPercent?.let { append(" battery=").append(it).append('%') }
        state.charging?.let { append(" charging=").append(it) }
        state.network?.let { append(" network=").append(it) }
        state.screenOn?.let { append(" screen=").append(if (it) "on" else "off") }
        state.locked?.let { append(" locked=").append(it) }
        state.brightness?.let { append(" brightness=").append(it) }
        state.volumePercent?.let { append(" volume=").append(it).append('%') }
        state.imeVisible?.let { append(" ime=").append(it) }
    }

    /** The media fact as structured JSON; absent metadata stays `null`, never a fabricated value. */
    private fun mediaJson(media: MediaState?): JsonElement =
        if (media == null) {
            JsonNull
        } else {
            buildJsonObject {
                put("state", media.state)
                put("title", media.title)
                put("artist", media.artist)
                put("album", media.album)
                put("positionMs", media.positionMs)
                put("durationMs", media.durationMs)
            }
        }

    /**
     * The notification content (spec §7.2): title/text/subText/bigText/category/posted time/ongoing/action labels.
     * This is model context only; the tool never logs it and the provider keeps it in memory only.
     */
    private fun notificationsJson(notifications: List<NotificationItem>): JsonArray = buildJsonArray {
        notifications.forEach { item ->
            add(
                buildJsonObject {
                    put("package", item.packageName)
                    put("app", item.appLabel)
                    put("title", item.title)
                    put("text", item.text)
                    put("subText", item.subText)
                    put("bigText", item.bigText)
                    put("category", item.category)
                    put("postedAt", item.postedAtEpochMillis)
                    put("ongoing", item.ongoing)
                    put(
                        "actions",
                        buildJsonArray { item.actionLabels.forEach { add(JsonPrimitive(it)) } },
                    )
                },
            )
        }
    }

    /** Glasses facts as structured JSON; a host that cannot tell leaves the field `null`. */
    private fun glassesJson(state: DeviceState?): JsonObject = buildJsonObject {
        put("connected", state?.glassesConnected)
        put("wearing", state?.glassesWearing)
        put("panel", state?.panelVisible)
    }

    /**
     * Parses the optional `include` array.
     *
     * `all` expands to every source the action accepts. A malformed array, an unknown token, or a token the action does
     * not serve is invalid arguments: silently ignoring it would answer a different question than the model asked.
     */
    private fun parseInclude(
        args: JsonObject,
        default: Set<PerceptionSource>,
        allowed: Set<PerceptionSource>,
    ): Set<PerceptionSource>? {
        val raw = args["include"] ?: return default
        val array = raw as? JsonArray ?: return null
        if (array.isEmpty()) return default
        val included = mutableSetOf<PerceptionSource>()
        array.forEach { element ->
            val token = (element as? JsonPrimitive)
                ?.takeIf { it.isString }
                ?.content
                ?.trim()
                ?.lowercase()
                ?: return null
            if (token == INCLUDE_ALL) {
                included += allowed
            } else {
                val source = SOURCE_BY_TOKEN[token] ?: return null
                if (source !in allowed) return null
                included += source
            }
        }
        return included
    }

    /**
     * The `status` include, shared-schema aware (review minor 6).
     *
     * The one schema advertises every `observe` source, so a model may legally send `screen`/`calendar` for status.
     * Those are known tokens `status` does not serve, and refusing them would answer INVALID_ARGUMENTS to a legal
     * tool-call shape; they are ignored instead, while the answer itself still names exactly what was read. An unknown
     * token stays invalid, so a typo is never silently answered with the default. `limit` is deliberately not parsed:
     * status returns no element list, and the schema documents that it applies to `observe`.
     */
    private fun parseStatusInclude(args: JsonObject): Set<PerceptionSource>? {
        val raw = args["include"] ?: return DEFAULT_STATUS_INCLUDE
        val array = raw as? JsonArray ?: return null
        if (array.isEmpty()) return DEFAULT_STATUS_INCLUDE
        val included = mutableSetOf<PerceptionSource>()
        array.forEach { element ->
            val token = (element as? JsonPrimitive)
                ?.takeIf { it.isString }
                ?.content
                ?.trim()
                ?.lowercase()
                ?: return null
            if (token == INCLUDE_ALL) {
                included += STATUS_SOURCES
            } else {
                val source = SOURCE_BY_TOKEN[token] ?: return null
                if (source in STATUS_SOURCES) included += source
            }
        }
        return included
    }

    /** The optional element cap; a non-positive/non-integer value is invalid rather than a silent default. */
    private fun parseLimit(args: JsonObject): Int? {
        val raw = args.longArg("limit") ?: return null
        if (raw <= 0L) return null
        return raw.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    private fun project(envelope: DeviceEnvelope, filter: ScreenFilter): JsonObject {
        // R32: the kernel writes a one-line `diagnostic=...` in `data` when a write succeeded but the
        // result could not be observed. There is no screen for that envelope, so keep the line and do
        // not project a stale observation as if it were the write's result.
        val kernelDiagnostic = envelope.data?.takeIf { it.isNotBlank() }
        val observation = operations.latestObservation
        return if (observation != null && kernelDiagnostic == null) {
            projector.project(observation, filter)
        } else {
            buildJsonObject {
                put("screen", JsonNull)
                put("diagnostic", kernelDiagnostic ?: envelope.error?.code ?: "no observation")
            }
        }
    }

    /**
     * One device call's result.
     *
     * R98: [stateChanging] is `true` for `act` (a write, including `scroll_to_text`) and `false` for the read-only
     * actions. The `device` descriptor declares `observation = true` for all of them, so this per-call flag is the only
     * way the loop can tell the write that returns a post-call observation from the read that does.
     *
     * Final review C2 (re-review): **every** result produced by this projection is marked
     * [ActionResult.nonPersistent]. The perception-backed actions carry the compact screen and the requested sources;
     * the legacy projector path still renders raw kernel screen text into `observe`/`wait`/`act`; and `screenshot`
     * carries artifact references. None of that may outlive the run, so the flag is set here rather than at each
     * caller (which is how `wait` used to slip through). The loop still shows the full detail to the model for this
     * run; only the stored transcript keeps the safe marker. It remains an additive contract with the default `false`
     * for every other tool. Argument-validation refusals do not pass through this projection: [refused] builds them and
     * sets `nonPersistent` itself whenever an observation is present.
     */
    private fun result(
        envelope: DeviceEnvelope,
        data: JsonObject,
        fingerprint: String? = null,
        stateChanging: Boolean = false,
        extraImages: List<ImageRef> = emptyList(),
        observation: ToolObservation? = null,
        partial: Boolean = false,
    ): ActionResult {
        val outcome = when {
            envelope.ok -> ActionOutcome.Done
            envelope.error?.recoverable == true -> ActionOutcome.Refused
            else -> ActionOutcome.Failed
        }
        // 0.25 Task 24 (R133): the envelope's image references are the call's result, not a detail of the rendered
        // JSON. A screenshot answers a real stored `ImageRef` (or the `pending` placeholder with its diagnostic), and
        // this projection is what lets the agent loop carry it to the model request. Every other action's envelope
        // carries an empty list, so their results keep the old shape.
        // 0.3 Phase 2.1 Task 3: automatic vision appends its capture here rather than through the kernel envelope, so the
        // JSON `images` and the model request stay one list. When no capture ran, `extraImages` is empty and the shape
        // is exactly the 0.25 one.
        // 0.3 Phase 2.2 Task 4: the same result carries the structured execution facts and post-call observation, so the
        // model reads machine facts instead of parsing `detail`. A read's `changed` stays null: only a write can claim
        // its own screen change, and even then only from the kernel's applied evidence.
        return ActionResult(
            outcome,
            renderData(envelope, data),
            fingerprint,
            stateChanging,
            images = envelope.images + extraImages,
            nonPersistent = true,
            execution = executionOf(
                envelope,
                changed = if (stateChanging) envelope.applied else null,
                partial = partial,
            ),
            observation = observation,
        )
    }

    /**
     * edith-0.3 Phase 2.2 Task 4: one kernel envelope's execution facts, projected without inventing any.
     *
     * The tri-state fields keep the kernel's own honesty: [ToolExecution.accepted]/[ToolExecution.applied] are copied
     * verbatim, `verified` is `true` only when the kernel named the post-condition it checked, and `changed` is the
     * write's applied evidence (a read or an unproven write leaves it `null`). `TOOL_THREW` is a loop-level fact and
     * never fabricated here.
     *
     * **Timeout and cancellation (fix review critical 1).** A kernel `ACTION_TIMEOUT` means the caller stopped waiting
     * while the platform call may still have been applied. It is therefore [ToolOutcome.Uncertain] with `uncertain=true`
     * and `accepted`/`applied`/`verified`/`changed` all `null` - the envelope's `accepted=false` is "no answer was
     * received", not "the platform refused". A kernel `CANCELLED` is [ToolOutcome.Cancelled] with its facts copied, so a
     * dropped turn is never misreported as a refusal.
     */
    private fun executionOf(envelope: DeviceEnvelope, changed: Boolean?, partial: Boolean): ToolExecution {
        val code = envelope.error?.code
        val timedOut = code == DeviceErrorCodes.ACTION_TIMEOUT
        val outcome = when {
            timedOut -> ToolOutcome.Uncertain
            code == DeviceErrorCodes.CANCELLED -> ToolOutcome.Cancelled
            envelope.ok -> ToolOutcome.Done
            envelope.error?.recoverable == true -> ToolOutcome.Refused
            else -> ToolOutcome.Failed
        }
        return ToolExecution(
            outcome = outcome,
            code = code,
            message = envelope.error?.message,
            recoverable = envelope.error?.recoverable == true,
            accepted = if (timedOut) null else envelope.accepted,
            applied = if (timedOut) null else envelope.applied,
            verified = if (timedOut) null else envelope.verified?.let { true },
            changed = if (timedOut) null else changed,
            uncertain = timedOut,
            partial = partial,
        )
    }

    /**
     * Task 12 (R57): the post-call observation fingerprint, or `null` when this call observed no new screen.
     *
     * The kernel observes after every `observe`/`act` (a successful write, a refusal, an `ACCEPTED_NO_EFFECT`), so the
     * newest [DeviceOperations.latestObservation] is the state the call left behind — but only if the kernel's
     * monotonic observation revision actually advanced during **this** call. A refused observe and an act rejected
     * before it ran leave the previous snapshot in place; reporting that stale screen as this call's post-state would
     * make the loop's poll/global detectors see "no change" for a call that never looked, and would credit a failed
     * write with evidence it does not have. A kernel diagnostic in `data` means the observation itself failed (R32):
     * a diagnostic is not a screen either. The fingerprint excludes the revision and all timing: the same screen must
     * hash identically across observations.
     */
    private fun fingerprintAfter(envelope: DeviceEnvelope, revisionBefore: Long?): String? {
        if (!envelope.data.isNullOrBlank()) return null
        val revisionAfter = operations.revision ?: return null
        if (revisionBefore == null || revisionAfter <= revisionBefore) return null
        val observation = operations.latestObservation ?: return null
        return observationFingerprint(observation)
    }

    /**
     * A stable SHA-256 of the screen's **content**: every window and node field the model can act on, with the
     * revision, timing and artifact ids left out. Two observations of the same screen therefore produce one
     * fingerprint, which is what lets the loop's poll/global detectors see "nothing changed" without ever reading a
     * payload body.
     */
    private fun observationFingerprint(observation: Observation): String {
        val content = StringBuilder()
        content.append(observation.windows.size).append('|')
        observation.windows.forEach { window ->
            content.field(window.id).field(window.type).field(window.layer)
                .field(window.bounds).field(window.packageName).field(window.title)
        }
        content.append(observation.nodes.size).append('|')
        observation.nodes.forEach { node ->
            content.field(node.role).field(node.text).field(node.contentDescription).field(node.viewId)
                .field(node.bounds).field(node.windowId).field(node.depth)
                .field(node.states.sorted().joinToString(","))
                .field(node.actions.joinToString(","))
                .field(node.hint).field(node.paneTitle).field(node.tooltip).field(node.drawingOrder)
                .field(node.collection?.toString()).field(node.range?.toString())
        }
        content.field(observation.truncated)
        return MessageDigest.getInstance("SHA-256")
            .digest(content.toString().toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private fun StringBuilder.field(value: Int?): StringBuilder = append(value ?: -1).append('|')

    private fun StringBuilder.field(value: Boolean): StringBuilder = append(if (value) 1 else 0).append('|')

    private fun StringBuilder.field(value: Bounds): StringBuilder =
        append(value.left).append(',').append(value.top).append(',')
            .append(value.right).append(',').append(value.bottom).append('|')

    /** Length-prefixed so content containing the separator cannot make two different screens hash identically. */
    private fun StringBuilder.field(value: String?): StringBuilder =
        append(value?.length ?: -1).append(':').append(value.orEmpty()).append('|')

    /**
     * Task 8: one device result's model-facing `data` — tool-specific facts only.
     *
     * The pre-Task-8 `renderEnvelope` repeated the whole result here: `ok`, `tool`, `channel`, `accepted`, `applied`,
     * `verified`, the image list and the `error` object all restated the envelope's `execution`/`images` members, and
     * the nested `data.screen` restated `observation`. Now `execution` is the one execution-fact carrier, `images` the
     * one picture carrier and `observation` the one screen carrier; `data` keeps the device sub-action, the kernel
     * projection metadata each action really adds (revision/IME/changes/diagnostic, requested non-screen sources) and
     * the kernel timing, which nothing else carries.
     */
    private fun renderData(envelope: DeviceEnvelope, fields: JsonObject): String = buildJsonObject {
        put(ACTION_KEY, envelope.action)
        fields.forEach { (key, value) -> put(key, value) }
        put(
            TIMING_KEY,
            buildJsonObject {
                put("actMs", envelope.timing.actMs)
                put("observeMs", envelope.timing.observeMs)
                put("totalMs", envelope.timing.totalMs)
            },
        )
    }.toString()

    /**
     * edith-0.3 Phase 2.2 Task 4: one tool-level refusal (unknown action, invalid arguments, disabled vision).
     *
     * The action was never dispatched, so the execution facts are unambiguous: `accepted=false`, `applied=false`;
     * `verified`/`changed` stay `null` because nothing was checked or written. A refusal still answers with the current
     * screen **as text** (a cached read, no device call), because the model needs to know what it is looking at when its
     * call was rejected; [vision] plus [visionReason] say why no picture accompanies it. Argument refusals use
     * `unavailable(not_attempted)`; a disabled vision switch uses the explicit [ToolVision.Disabled].
     */
    private fun refused(
        code: String,
        vision: ToolVision = ToolVision.Unavailable,
        visionReason: String? = VISION_NOT_ATTEMPTED,
    ): ActionResult {
        // M2: a provider that threw already answered `unavailable(provider_threw)`, which is the honest reason for the
        // missing screen. The refusal's own `not_attempted`/`disabled` marker must not overwrite it, or a broken port
        // would read exactly like a refusal that chose not to read.
        val observation = observeForResult(capture = false, visionRequested = false)
            ?.let { observation ->
                if (observation.visionReason == VISION_PROVIDER_THREW) {
                    observation
                } else {
                    observation.copy(vision = vision, visionReason = visionReason)
                }
            }
        return ActionResult(
            ActionOutcome.Refused,
            // Task 8: the stable code is `execution.code`; repeating it as the whole `data` was a second copy.
            "{}",
            // A refusal now carries screen text, so it may not outlive the run either.
            nonPersistent = observation != null,
            execution = ToolExecution(
                outcome = ToolOutcome.Refused,
                code = code,
                recoverable = false,
                accepted = false,
                applied = false,
                verified = null,
                changed = null,
            ),
            observation = observation,
        )
    }

    companion object {
        const val NAME = "device"

        /** Default per-operation budget, matching [DeviceKernel.DEFAULT_OPERATION_TIMEOUT_MILLIS]. */
        const val DEFAULT_TIMEOUT_MILLIS = 30_000L

        /** R59: the same cap `TurnRuntimeState` enforces; the provider must never hand over a line it would reject. */
        const val RUNTIME_STATE_MAX_CHARS = 300

        private const val ACTION_OBSERVE = "observe"
        private const val ACTION_ACT = "act"
        private const val ACTION_SCREENSHOT = "screenshot"
        private const val ACTION_WAIT = "wait"
        private const val ACTION_STATUS = "status"

        private const val FILTER_INTERACTIVE = "interactive"
        private const val FILTER_TEXT = "text"
        private const val FILTER_ALL = "all"

        private const val INVALID_ARGUMENTS = "INVALID_ARGUMENTS"
        private const val UNKNOWN_ACTION = "UNKNOWN_ACTION"

        /** The channel a perception-backed read/act is reported under (the kernel's own accessibility channel). */
        private const val CHANNEL_ACCESSIBILITY = "accessibility"

        /** edith-0.3 Phase 2 Task 7: the two tool-level refusals the model can branch on. */
        private const val STALE_REF = "STALE_REF"
        private const val VISION_DISABLED = "VISION_DISABLED"

        /** The auto-vision marker reason when the injected capture throws despite the provider contract. */
        private const val SCREENSHOT_FAILED = "SCREENSHOT_FAILED"

        /** `DeviceKernel.screenshot()`'s placeholder id while no artifact store reference exists. */
        private const val PENDING_ARTIFACT_ID = "pending"

        /** The kernel captured bytes but the artifact store did not: an unresolvable reference, never an image. */
        private const val SCREENSHOT_PENDING = "SCREENSHOT_PENDING"

        /**
         * The vision reason of an observation that deliberately took no picture (argument refusals): `vision=unavailable`
         * must never be bare, or the model cannot tell "not attempted" from "the device could not".
         */
        private const val VISION_NOT_ATTEMPTED = "not_attempted"

        /** M2: the stable reason for a provider that exists but threw while projecting the observation. */
        private const val VISION_PROVIDER_THREW = "provider_threw"

        /** M2: the two screen coverage tokens that mean the screen text is a real, model-usable read. */
        private const val SCREEN_COVERAGE_OK = "ok"
        private const val SCREEN_COVERAGE_TRUNCATED = "truncated"

        /** The one source an observation reads when the caller did not already project one from a snapshot. */
        private val OBSERVATION_SOURCES: Set<PerceptionSource> = setOf(PerceptionSource.SCREEN)

        /** The one-line nudge when an accepted write provably changed nothing (spec §7.6). */
        private const val OBSERVE_FIRST_NOTE = "动作未改变画面；先观察再决定"

        // Model-facing `data` keys. Task 8 keeps only what the envelope/observation do not already carry.
        private const val SCREEN_KEY = "screen"
        private const val CHANGES_KEY = "changes"
        private const val NOTE_KEY = "note"
        private const val ACTION_KEY = "action"
        private const val TIMING_KEY = "timing"
        private const val REV_KEY = "rev"
        private const val IME_KEY = "ime"
        private const val DIAGNOSTIC_KEY = "diagnostic"
        private const val STATUS_KEY = "status"
        private const val DEVICE_KEY = "device"
        private const val MEDIA_KEY = "media"
        private const val PANEL_KEY = "panel"
        private const val GLASSES_KEY = "glasses"
        private const val NOTIFICATIONS_KEY = "notifications"
        private const val CALENDAR_KEY = "calendar"
        private const val ACOUSTIC_KEY = "acoustic"
        private const val CLIPBOARD_KEY = "clipboard"
        private const val LIMIT_KEY = "limit"
        private const val INCLUDE_ALL = "all"

        private val OBSERVE_SOURCES: Set<PerceptionSource> = PerceptionSource.entries.toSet()

        /** `status` is the ambient question; the screen tree is explicitly not one of its sources. */
        private val STATUS_SOURCES: Set<PerceptionSource> = setOf(
            PerceptionSource.DEVICE,
            PerceptionSource.NOTIFICATIONS,
            PerceptionSource.MEDIA,
        )

        private val DEFAULT_OBSERVE_INCLUDE: Set<PerceptionSource> = setOf(PerceptionSource.SCREEN)
        private val DEFAULT_STATUS_INCLUDE: Set<PerceptionSource> = setOf(PerceptionSource.DEVICE)

        private val SOURCE_BY_TOKEN: Map<String, PerceptionSource> = mapOf(
            "screen" to PerceptionSource.SCREEN,
            "notifications" to PerceptionSource.NOTIFICATIONS,
            "media" to PerceptionSource.MEDIA,
            "calendar" to PerceptionSource.CALENDAR,
            "acoustic" to PerceptionSource.ACOUSTIC,
            "clipboard" to PerceptionSource.CLIPBOARD,
            "glasses" to PerceptionSource.GLASSES,
            "device" to PerceptionSource.DEVICE,
        )

        private val BARE_REF = Regex("n\\d+")

        private val KIND_BY_TOKEN: Map<String, ActKind> = mapOf(
            "tap" to ActKind.TAP,
            "long_press" to ActKind.LONG_PRESS,
            "double_tap" to ActKind.DOUBLE_TAP,
            "swipe" to ActKind.SWIPE,
            "scroll" to ActKind.SCROLL,
            "scroll_to_text" to ActKind.SCROLL_TO_TEXT,
            "type" to ActKind.TYPE,
            "press_key" to ActKind.PRESS_KEY,
            "home" to ActKind.HOME,
            "back" to ActKind.BACK,
            "recents" to ActKind.RECENTS,
            "notifications" to ActKind.NOTIFICATIONS,
        )

        /**
         * The model-facing schema (spec §4.7): one action selector plus the union of the per-action
         * parameters. Deliberately no `via`, `point`, `region` or `annotate` (D84, R44).
         *
         * Spec §4.7 also lists `observe.window` / `observe.since_rev` and
         * `act.from` / `act.to` / `act.duration_ms`. They are deliberately omitted in 0.25 (R64):
         * the frozen kernel API ([DeviceKernel.observe] / [DeviceKernel.act]) has no such
         * parameters, and `from`/`to` would be model-supplied coordinates, which D84 forbids.
         * Advertising them would add arguments the tool silently ignores; they can only arrive
         * together with the kernel capability, not as schema alone.
         */
        val DESCRIPTOR: ActionDescriptor = ActionDescriptor(
            name = NAME,
            title = "设备控制",
            description = "结构化的设备感知与操作总入口，用 action 选择：observe 读屏幕并给出可引用的 ref；act 按 ref 执行 tap/long_press/double_tap/swipe/scroll/scroll_to_text/type/press_key/home/back/recents/notifications；screenshot 截图；wait 等界面稳定；status 读当前状态。需要 ref 引用、一次读取后连续多步操作、截图、等待，或读通知/媒体/日历/剪贴板等多来源时用它；只读一次屏幕后马上按索引动作时用 read_screen、tap、long_press、scroll、input_text 更省一步，按键可以用 system_action。ref 过期返回 STALE_REF 并附最新观察，此时必须重新 observe 再操作；act 的 ref 必须来自本回合 observe，不要编造索引或坐标。通道掉线返回 CHANNEL_UNAVAILABLE，等通道恢复后重试。",
            parametersJson = """{"type":"object","properties":{""" +
                """"action":{"type":"string","enum":["observe","act","screenshot","wait","status"]},""" +
                """"filter":{"type":"string","enum":["interactive","text","all"]},""" +
                """"include":{"type":"array","description":"observe 可请求全部来源；status 只返回 device/notifications/media，其他合法来源会被忽略","items":{"type":"string","enum":["screen","notifications","media","calendar","acoustic","clipboard","glasses","device","all"]}},""" +
                """"limit":{"type":"integer","minimum":1,"description":"observe 返回的元素上限；status 不使用该参数"},""" +
                """"kind":{"type":"string","enum":["tap","long_press","double_tap","swipe","scroll",""" +
                """"scroll_to_text","type","press_key","home","back","recents","notifications"]},""" +
                """"ref":{"type":"string"},"text":{"type":"string"},"value":{"type":"string"},""" +
                """"direction":{"type":"string"},"key":{"type":"string","description":"press_key 的 Android keycode 名，如 MEDIA_PLAY_PAUSE / MEDIA_NEXT / MEDIA_PREVIOUS；打开播放器后用它在设备上开始播放。"},"foreground":{"type":"string"},""" +
                """"idle":{"type":"boolean"},"timeout_ms":{"type":"integer","minimum":1}},""" +
                """"required":["action"],"additionalProperties":false}""",
            risk = RiskLevel.L1,
            requiresConfirmation = false,
            idempotent = false,
            reversible = false,
            scope = "app.ui",
            // Task 12 (R57/R61): every `device` call comes back with an observation (the kernel's post-action
            // snapshot), so the loop counts it against the observation budget and fingerprints it. R98: the descriptor
            // cannot distinguish `act` from `observe`, so each result declares `stateChanging` per call — the loop feeds
            // ProgressGuard from an act (when it has a fingerprint) and never from a read.
            observation = true,
            // edith-0.3 Phase 2.2 Task 4: every `device` result also carries the structured post-call observation
            // (`ActionResult.observation`), including failures and refusals. This is what lets the loop skip its own
            // failure capture for this tool: the tool already described the screen it left behind.
            screenObservation = true,
        )
    }
}

/** The compact snapshot's filter vocabulary mirrors the kernel's render filter exactly. */
private fun ScreenFilter.toPerceptionFilter(): PerceptionFilter = when (this) {
    ScreenFilter.INTERACTIVE -> PerceptionFilter.INTERACTIVE
    ScreenFilter.TEXT -> PerceptionFilter.TEXT
    ScreenFilter.ALL -> PerceptionFilter.ALL
}
