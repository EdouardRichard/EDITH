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
import com.sheen.rokid.plugin.MediaState
import com.sheen.rokid.plugin.NotificationItem
import com.sheen.rokid.plugin.PerceptionFilter
import com.sheen.rokid.plugin.PerceptionProvider
import com.sheen.rokid.plugin.PerceptionSnapshot
import com.sheen.rokid.plugin.PerceptionSource
import com.sheen.rokid.plugin.RiskLevel
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
 *  - projects the kernel envelope into the §4.6 JSON with `data.screen` (R41).
 *
 * **Screenshot (R44).** This task keeps the kernel's `ImageRef("pending", ...)` and never inline
 * bytes; `region` and `annotate` are not advertised, because the artifact store and image pipeline
 * arrive in Task 13.
 *
 * **Outcome mapping.** `ok` from the kernel becomes [ActionOutcome.Done]; a failed envelope is
 * [ActionOutcome.Refused] when `error.recoverable` and [ActionOutcome.Failed] otherwise. The raw
 * code survives in the JSON `error` object either way.
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
            // A read through the perception port already answered: there is no kernel envelope to project, but every
            // `device` call keeps the same model-facing envelope shape.
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
                // Final review C2: the detail carries the compact screen (and any requested source content). It is model
                // context for this run only; the agent loop redacts it from the stored transcript.
                nonPersistent = true,
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
        val data = buildJsonObject {
            put("snapshot", CompactSnapshotRenderer.render(snapshot))
            put("coverage", PerceptionCoverageRenderer.render(snapshot.coverage))
        }
        val detail = toolEnvelope(
            tool = NAME,
            ok = false,
            channel = CHANNEL_ACCESSIBILITY,
            data = data,
            error = ToolFailure(
                code = STALE_REF,
                message = stale.reason,
                recoverable = true,
            ),
        )
        return ActionResult(ActionOutcome.Refused, detail, nonPersistent = true)
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
    private fun actResult(envelope: DeviceEnvelope, revisionBefore: Long?): ActionResult {
        val data = decorateActData(project(envelope, lastFilter), envelope)
        return result(
            envelope,
            data,
            fingerprintAfter(envelope, revisionBefore),
            stateChanging = true,
            // The decorated data carries the forced post-action compact snapshot (or the stale answer's fresh one).
            nonPersistent = perception != null,
        )
    }

    /**
     * Task 7: a forced post-action perception read.
     *
     * `perception.afterAction()` opens a new snapshot under a new id, so a ref from before the action that no longer
     * names a node is stale for the next call (R5). The compact text replaces the projector's full/diff text so the
     * model sees the same bounded shape as `observe`; the existing envelope (ok/error/applied) is untouched.
     */
    private fun decorateActData(data: JsonObject, envelope: DeviceEnvelope): JsonObject {
        val provider = perception ?: return data
        val snapshot = provider.afterAction()
        val coverage = PerceptionCoverageRenderer.render(snapshot.coverage)
        val foreground = foregroundString(snapshot)
        val compact = CompactSnapshotRenderer.render(snapshot)
        val changed = envelope.applied == true
        return buildJsonObject {
            data.forEach { (key, value) -> if (key != SCREEN_KEY) put(key, value) }
            put(SNAP_KEY, snapshot.snapshotId)
            put(COVERAGE_KEY, coverage)
            put(FOREGROUND_KEY, foreground)
            put(CHANGED_KEY, changed)
            (data[SCREEN_KEY] as? JsonObject)?.let { screen ->
                put(
                    SCREEN_KEY,
                    buildJsonObject {
                        screen.forEach { (key, value) -> put(key, value) }
                        put(SNAP_KEY, snapshot.snapshotId)
                        put(COVERAGE_KEY, coverage)
                        put(FOREGROUND_KEY, foreground)
                        put(TRUNCATED_KEY, CompactSnapshotRenderer.screenTruncated(snapshot))
                        put(TEXT_KEY, compact)
                        // The projector's full/diff lines carry `bounds=`, which is evidence-only and must not face the
                        // model (spec §7.3). The compact text above is the complete post-action screen, so the change
                        // mode is full again rather than a diff whose line format would reintroduce bounds.
                        put(
                            CHANGES_KEY,
                            buildJsonObject {
                                put("mode", "full")
                                put("added", buildJsonArray { })
                                put("updated", buildJsonArray { })
                                put("removed", buildJsonArray { })
                            },
                        )
                    },
                )
            }
            if (!changed && (envelope.applied == false ||
                    envelope.error?.code == DeviceErrorCodes.ACCEPTED_NO_EFFECT)) {
                put(NOTE_KEY, OBSERVE_FIRST_NOTE)
            }
        }
    }

    /**
     * R46: one scroll + one bounded wait per round, stopping on the first hit.
     *
     * The loop is deliberately here rather than in the kernel: the kernel's `SCROLL_TO_TEXT` is one
     * scroll step, and the tool owns the overall `timeout_ms` budget. A kernel error that is not
     * "the screen did not change" ends the loop immediately; a `VERIFY_TIMEOUT` from one round only
     * means "keep scrolling until the budget is spent", and the last envelope keeps the final
     * observation (`data.screen` is projected from it).
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
            if (!scrolled.ok && scrolled.error?.code != DeviceErrorCodes.ACCEPTED_NO_EFFECT) {
                return actResult(scrolled.copy(action = ACTION_ACT), revisionBefore)
            }
            val waited = operations.waitFor(text, null, false, remaining)
            last = waited
            if (waited.ok) {
                return actResult(waited.copy(action = ACTION_ACT), revisionBefore)
            }
            if (waited.error?.code != DeviceErrorCodes.VERIFY_TIMEOUT) {
                return actResult(waited.copy(action = ACTION_ACT), revisionBefore)
            }
        } while (clock() - started < timeoutMillis)
        val envelope = last ?: operations.waitFor(text, null, false, 1L)
        return actResult(envelope.copy(action = ACTION_ACT), revisionBefore)
    }

    private fun screenshot(): ActionResult {
        // Task 7: the vision switch is checked before the kernel, so a disabled vision never captures, stores or
        // advertises an image. The refusal names the switch, which is more actionable than a channel error.
        if (!runCatching { visionEnabled() }.getOrDefault(false)) return refused(VISION_DISABLED)
        val envelope = operations.screenshot()
        val kernelDiagnostic = envelope.data?.takeIf { it.isNotBlank() }
        val data = if (envelope.ok) {
            buildJsonObject {
                // R131: the kernel's `diagnostic=screenshot_artifact_pending; reason=…` is the only honest record that
                // the capture worked and the store did not. A generic sentence here would overwrite the failure reason
                // and make a broken artifact store indistinguishable from a normal pending reference.
                put("diagnostic", kernelDiagnostic ?: "screenshot is an artifact reference; bytes are not inlined")
            }
        } else {
            buildJsonObject {
                put("screen", JsonNull)
                // On failure the error code is the reason; never replace it with a sentence the kernel did not say.
                put("diagnostic", envelope.error?.code ?: kernelDiagnostic ?: "the screenshot failed")
            }
        }
        return result(envelope, data)
    }

    private fun wait(args: JsonObject): ActionResult {
        val text = args.stringArg("text")?.trim()?.takeIf { it.isNotEmpty() }
        val foreground = args.stringArg("foreground")?.trim()?.takeIf { it.isNotEmpty() }
        val idle = args.booleanArg("idle") ?: false
        if (text == null && foreground == null && !idle) return refused(INVALID_ARGUMENTS)
        val timeout = args.longArg("timeout_ms") ?: DEFAULT_TIMEOUT_MILLIS
        if (timeout <= 0L) return refused(INVALID_ARGUMENTS)
        val envelope = operations.waitFor(text, foreground, idle, timeout)
        return result(envelope, project(envelope, lastFilter))
    }

    private fun status(args: JsonObject): ActionResult {
        val envelope = operations.status()
        val provider = perception
        val data = if (provider == null) {
            buildJsonObject {
                // R41: keep the kernel's key=value diagnostic line verbatim, just inside a JSON object.
                put(STATUS_KEY, envelope.data ?: envelope.error?.code ?: "")
            }
        } else {
            val include = parseStatusInclude(args) ?: return refused(INVALID_ARGUMENTS)
            statusData(envelope, provider.snapshot(include, PerceptionFilter.ALL, null), include)
        }
        // Final review C2: with a provider the status detail may carry notification/media/device content.
        return result(envelope, data, nonPersistent = provider != null)
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
        return buildJsonObject {
            put(STATUS_KEY, envelope.data ?: envelope.error?.code ?: "")
            put(COVERAGE_KEY, PerceptionCoverageRenderer.render(snapshot.coverage))
            put(DEVICE_KEY, state?.let(::renderDeviceState))
            put(
                FOREGROUND_KEY,
                foregroundString(snapshot) ?: state?.let {
                    it.foregroundPackage?.let { packageName ->
                        "$packageName/${it.foregroundActivity ?: UNKNOWN_ACTIVITY}"
                    }
                },
            )
            put(MEDIA_KEY, mediaJson(snapshot.media ?: state?.media))
            put(PANEL_KEY, state?.panelVisible)
            put(GLASSES_KEY, glassesJson(state))
            if (PerceptionSource.NOTIFICATIONS in include) {
                put(NOTIFICATIONS_KEY, notificationsJson(snapshot.notifications))
            }
        }
    }

    /** One `observe` result: the compact screen object plus every non-screen source the caller asked for. */
    private fun observationData(snapshot: PerceptionSnapshot, include: Set<PerceptionSource>): JsonObject =
        buildJsonObject {
            put(
                SCREEN_KEY,
                buildJsonObject {
                    put(SNAP_KEY, snapshot.snapshotId)
                    put(COVERAGE_KEY, PerceptionCoverageRenderer.render(snapshot.coverage))
                    put(FOREGROUND_KEY, foregroundString(snapshot))
                    // Review I3: the snapshot-wide flag also covers a notification cut; the screen flag must follow the
                    // screen source only, or a complete screen claims to be truncated.
                    put(TRUNCATED_KEY, CompactSnapshotRenderer.screenTruncated(snapshot))
                    put(TEXT_KEY, CompactSnapshotRenderer.render(snapshot))
                },
            )
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

    private fun foregroundString(snapshot: PerceptionSnapshot): String? =
        snapshot.foregroundPackage?.let { packageName ->
            "$packageName/${snapshot.foregroundActivity ?: UNKNOWN_ACTIVITY}"
        }

    private fun deviceStateOrNull(): DeviceState? =
        deviceState?.let { runCatching { it.state() }.getOrNull() }

    /**
     * The compact device line (spec §7.3's `device:` row): every non-null fact, joined into one line.
     * A fact the platform did not report is omitted - the coverage line already says whether the source is complete.
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
        state.foregroundPackage?.let { append(" foreground=").append(it) }
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
     * Final review C2: [nonPersistent] marks a detail that carries perception content. The loop still shows it to the
     * model for this run; its stored transcript keeps only a safe marker. Every pre-Phase-2 path leaves it `false`.
     */
    private fun result(
        envelope: DeviceEnvelope,
        data: JsonObject,
        fingerprint: String? = null,
        stateChanging: Boolean = false,
        nonPersistent: Boolean = false,
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
        return ActionResult(
            outcome,
            renderEnvelope(envelope, data),
            fingerprint,
            stateChanging,
            images = envelope.images,
            nonPersistent = nonPersistent,
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

    private fun renderEnvelope(envelope: DeviceEnvelope, data: JsonObject): String = buildJsonObject {
        put("ok", envelope.ok)
        put("tool", envelope.tool)
        put("action", envelope.action)
        put("channel", envelope.channel)
        put("accepted", envelope.accepted)
        if (envelope.applied == null) put("applied", JsonNull) else put("applied", envelope.applied)
        val verified = envelope.verified
        if (verified == null) {
            put("verified", JsonNull)
        } else {
            put(
                "verified",
                buildJsonObject {
                    put("kind", verified.kind)
                    put("rev", verified.revision)
                },
            )
        }
        put("data", data)
        put(
            "images",
            buildJsonArray {
                envelope.images.forEach { image ->
                    add(
                        buildJsonObject {
                            put("artifactId", image.artifactId)
                            put("mime", image.mime)
                            put("width", image.width)
                            put("height", image.height)
                        },
                    )
                }
            },
        )
        put(
            "timing",
            buildJsonObject {
                put("actMs", envelope.timing.actMs)
                put("observeMs", envelope.timing.observeMs)
                put("totalMs", envelope.timing.totalMs)
            },
        )
        val error = envelope.error
        if (error == null) {
            put("error", JsonNull)
        } else {
            put(
                "error",
                buildJsonObject {
                    put("code", error.code)
                    put("message", error.message)
                    put("recoverable", error.recoverable)
                },
            )
        }
    }.toString()

    private fun refused(code: String) = ActionResult(ActionOutcome.Refused, code)

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

        /** The one-line nudge when an accepted write provably changed nothing (spec §7.6). */
        private const val OBSERVE_FIRST_NOTE = "动作未改变画面；先观察再决定"

        /** The activity placeholder when only the package is known; the model can still tell it is unresolved. */
        private const val UNKNOWN_ACTIVITY = "?"

        // Model-facing keys shared by observe/act/status. Kept private and spelled once so the three shapes cannot drift.
        private const val SCREEN_KEY = "screen"
        private const val CHANGES_KEY = "changes"
        private const val SNAP_KEY = "snap"
        private const val COVERAGE_KEY = "coverage"
        private const val FOREGROUND_KEY = "foreground"
        private const val TRUNCATED_KEY = "truncated"
        private const val TEXT_KEY = "text"
        private const val CHANGED_KEY = "changed"
        private const val NOTE_KEY = "note"
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
        )
    }
}

/** The compact snapshot's filter vocabulary mirrors the kernel's render filter exactly. */
private fun ScreenFilter.toPerceptionFilter(): PerceptionFilter = when (this) {
    ScreenFilter.INTERACTIVE -> PerceptionFilter.INTERACTIVE
    ScreenFilter.TEXT -> PerceptionFilter.TEXT
    ScreenFilter.ALL -> PerceptionFilter.ALL
}
