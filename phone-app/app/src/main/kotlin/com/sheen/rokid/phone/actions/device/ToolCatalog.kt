package com.sheen.rokid.phone.actions.device

import com.sheen.rokid.plugin.ActionDescriptor
import com.sheen.rokid.plugin.ActionExecutor
import com.sheen.rokid.plugin.ActionOutcome
import com.sheen.rokid.plugin.ActionPrecheck
import com.sheen.rokid.plugin.ActionResult
import com.sheen.rokid.plugin.ImageRef
import com.sheen.rokid.plugin.PerceptionScreenshotResult
import com.sheen.rokid.plugin.PrecheckDecision
import com.sheen.rokid.plugin.ToolExecution
import com.sheen.rokid.plugin.ToolObservation
import com.sheen.rokid.plugin.ToolOutcome
import com.sheen.rokid.plugin.ToolVision
import com.sheen.rokid.phone.lock.UnlockGate
import com.sheen.rokid.phone.lock.UnlockOutcome
import com.sheen.rokid.phone.platform.P3Log
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull

/**
 * R40/R47: the production `Capability.Actions` provider (since edith-0.3 Phase 4A it is joined with the atomic backends
 * by `com.sheen.rokid.phone.actions.ToolSurface`).
 *
 * It advertises exactly the four host tools — `device`, `shell`, `open`, `dial` — in a fixed order on **every** read.
 * Phase 4A Task 3 (A3) made that advertising **stable**: a channel's answer changes while the App runs (accessibility
 * switched off, Shizuku binder dead, `CALL_PHONE` revoked), but hiding a down channel's tool would change the schema
 * and the cached prompt prefix under the model mid-session, so the tool stays advertised and `execute` re-probes the
 * channel on **every** call, refusing instead of delegating. `device` was always advertised this way because its
 * `status` action must be answerable while the accessibility service is down. The plugin adds `notify_user`/`load_skill`
 * beside this catalog, which is what made the pre-Phase-4 model-visible surface exactly six (R40).
 *
 * edith-0.3 Phase 2.2 Task 6: `shell`/`dial` results also carry a post-call observation, structured
 * execution facts and at most one best-effort image. This catalog owns that enrichment (the two tools
 * keep their own outcome/detail shapes) and reuses the host observation source `OpenTool` gets;
 * `device`/`open` were migrated by Tasks 4-5 and keep their own policies. Since Task 8 the tools state
 * their own `execution` facts; the catalog only fills a missing execution for a hand-built result.
 * Phase 3 M5 extends the same enrichment to the refusals the **catalog** itself constructs for
 * `device`/`open` (accessibility off, shell down, unlock refused): no tool ever saw those calls, so the
 * catalog supplies the observation + one best-effort picture. Tool results are never re-enriched.
 *
 * `cancel`/`beginTurn`/`close` are the turn lifecycle (R42): the kernel's cancellation is
 * turn-scoped, so a new turn re-arms it and the composition closes the catalog to stop the kernel's
 * worker.
 */
class ToolCatalog(
    private val device: DeviceTool,
    private val shell: ShellTool,
    private val open: OpenTool,
    private val dial: DialTool,
    private val a11yAvailable: () -> Boolean,
    private val shellAvailable: () -> Boolean,
    private val callAvailable: () -> Boolean,
    /**
     * The lock-screen gate (2026-10-20). `device`/`open`/`dial` need an interactive, unlocked phone; `shell` does not,
     * because Shizuku runs a command without a UI, so raw shell stays available on a locked phone. The default keeps
     * every existing test and non-phone composition source-compatible.
     */
    private val unlock: UnlockGate = UnlockGate { UnlockOutcome.ALREADY_UNLOCKED },
    /**
     * edith-0.3 Phase 2.2 Task 6: the host's non-throwing post-call observation source for `shell`/`dial`.
     *
     * The host composition injects the `AndroidPerceptionProvider`-backed source that `OpenTool(observe = ...)` gets,
     * so both tools describe the same screen. A source that is absent, answers `null` or throws is not a silent gap: a
     * screen attempt still reports `ToolObservation(vision = unavailable, visionReason = "no_source")`. Argument
     * refusals ask it too, but take no picture (the `device`/`open` convention).
     */
    private val observe: () -> ToolObservation? = { null },
    /**
     * The best-effort capture for a `shell`/`dial` screen attempt, exactly once per real attempt.
     *
     * The user's vision switch and the profile's `IMAGE` capability are gates **inside**
     * `PerceptionProvider.screenshot`, so this catalog does not duplicate them: `Disabled` and `Unavailable` are
     * answers, never failures, and a throwing port degrades to `Unavailable("SCREENSHOT_FAILED")`. At most one image is
     * attached, and `vision = ok` is reported only when that image is really attached.
     */
    private val capture: () -> PerceptionScreenshotResult = { PerceptionScreenshotResult.Disabled },
) : ActionExecutor, AutoCloseable {

    override fun descriptors(): List<ActionDescriptor> = listOf(
        // Task 7 review I2 (R11) made `device` stay advertised for its `status` diagnosis; A3 applies the same stable
        // rule to all four: a down channel still advertises its tool and `execute` below answers the refusal. Hiding a
        // tool would change the model-visible schema (and the cached prompt prefix) mid-session.
        device.descriptors().single(),
        // Task 12 fix (R99): the shell descriptor keeps its machine cap and declares `mayAskHuman`; the plugin wraps
        // this executor in `ConfirmingActions`, which asks the wearer inside the same `execute` call, and the agent
        // loop adds the human allowance plus shifts the measured wait out of the turn (D49). Inflating
        // `timeoutMillis` here would be clamped by the turn deadline and cut a legal 120 s decision.
        shell.descriptors().single(),
        open.descriptors().single(),
        dial.descriptors().single(),
    )

    override fun execute(name: String, argsJson: String): ActionResult {
        // M1: one shape line per call, before any channel/gate decision, so a refusal and a wrong model choice are
        // distinguishable. ToolArgsShape never echoes the command, URL, package, ref, text or dialled number.
        P3Log.line(ToolArgsShape.describe(name, argsJson))
        return when (name) {
        DeviceTool.NAME ->
            // edith-0.3 Phase 2 Task 7: `status` is the one device call that must run while the phone is locked and
            // the accessibility service is down - it is the diagnostic that reports `locked=true` / `accessibility=false`,
            // and a gate would hide the condition behind the question "can you unlock?". Every other action keeps both
            // checks. The gate comes **before** the a11y probe: a locked phone can be exactly the reason the service
            // is unbound, so probing first would refuse the call without ever trying to unlock and reconnect.
            // M5: `status` returns the tool's own text-only observation untouched; the catalog never re-enriches it.
            if (device.isStatus(argsJson)) {
                device.execute(name, argsJson)
            } else {
                deviceCall(name, argsJson)
            }
        ShellTool.NAME ->
            screenCall(ShellTool.NAME) { if (shellAvailable()) shell.execute(name, argsJson) else channelRefused() }
        OpenTool.NAME ->
            openCall(name, argsJson)
        DialTool.NAME ->
            screenCall(DialTool.NAME) {
                if (!callAvailable()) {
                    callRefused()
                } else {
                    gated(unlock.ensureUnlocked(requireAccessibility = false)) { dial.execute(name, argsJson) }
                }
            }
            else -> ActionResult(
                ActionOutcome.Refused,
                // Task 8: the stable code is the execution fact; `data` repeats nothing.
                "{}",
                execution = ToolExecution(
                    outcome = ToolOutcome.Refused,
                    code = UNKNOWN_ACTION,
                    accepted = false,
                    applied = false,
                ),
            )
        }
    }

    /**
     * M5: one catalog-level `device` call.
     *
     * The unlock gate and the a11y probe are decisions of **this** catalog, so their refusals are the ones no tool
     * result describes; they go through the same [screenCall] enrichment `shell`/`dial` get (observation + at most one
     * best-effort picture + `nonPersistent=true`). Once both checks pass, the device tool's own result - success or its
     * own refusal - already carries an observation and a picture, so it is returned untouched and never re-enriched.
     */
    private fun deviceCall(name: String, argsJson: String): ActionResult {
        val unlockOutcome = unlock.ensureUnlocked(requireAccessibility = true)
        if (!unlockOutcome.usable) return screenCall(DeviceTool.NAME) { lockedRefusal(unlockOutcome) }
        if (!a11yAvailable()) return screenCall(DeviceTool.NAME) { channelRefused() }
        return device.execute(name, argsJson)
    }

    /**
     * M5: one catalog-level `open` call.
     *
     * A shell that is down and an unlock that was refused are the catalog's own refusals, enriched like `shell`/`dial`.
     * `list` is a read, not a screen operation, so its refusal observes the screen as text only and takes no picture;
     * the tool result itself is never re-enriched.
     */
    private fun openCall(name: String, argsJson: String): ActionResult {
        val list = isListCall(argsJson)
        if (!shellAvailable()) return openRefusal(list) { channelRefused() }
        val unlockOutcome = unlock.ensureUnlocked(requireAccessibility = false)
        if (!unlockOutcome.usable) return openRefusal(list) { lockedRefusal(unlockOutcome) }
        return open.execute(name, argsJson)
    }

    /** M5: one `open` catalog refusal; a `list` read is answered with a text observation and never a picture. */
    private fun openRefusal(list: Boolean, call: () -> ActionResult): ActionResult =
        screenCall(OpenTool.NAME, attachPicture = !list) { call() }

    /** Mirrors `OpenTool`'s own `list=true` reading so a non-screen read is not enriched with a picture. */
    private fun isListCall(argsJson: String): Boolean =
        parseObject(argsJson)?.booleanArg("list") == true

    /**
     * A refused unlock is reported as a refusal with a code the model can act on; the tool body is never entered, so a
     * device action cannot run against a still-locked screen and get mislabeled as an accessibility failure.
     *
     * Task 8: the catalog's own refusals now carry structured execution facts (the stable code, `accepted`/`applied` =
     * false) and an empty tool-specific `data`, instead of a bare detail string and the old `toolEnvelope` copy.
     * M5: [deviceCall]/[openCall] feed this answer through the shared screen enrichment.
     */
    private fun lockedRefusal(outcome: UnlockOutcome): ActionResult = ActionResult(
        ActionOutcome.Refused,
        "{}",
        execution = ToolExecution(
            outcome = ToolOutcome.Refused,
            code = "LOCKED_UNLOCK_FAILED:${outcome.name}",
            recoverable = true,
            accepted = false,
            applied = false,
        ),
    )

    private inline fun gated(outcome: UnlockOutcome, action: () -> ActionResult): ActionResult =
        if (outcome.usable) action() else lockedRefusal(outcome)

    /**
     * 0.25 Task 11 (R49): only the raw-command tool has a call-level risk; every other tool answers the additive
     * default so `device`/`open`/`dial` never gain a confirmation they are not supposed to have (R-03).
     *
     * Kept independent of [execute]'s channel probes on purpose: classifying a command is a pure string decision and
     * must not depend on whether Shizuku happens to be up this second.
     */
    override fun precheck(name: String, argsJson: String): ActionPrecheck = when (name) {
        ShellTool.NAME -> shell.classify(argsJson)
        else -> ActionPrecheck(PrecheckDecision.Allow)
    }

    override fun cancel() {
        device.cancel()
    }

    override fun beginTurn() {
        device.beginTurn()
    }

    /**
     * Task 12 (R59): the one runtime-state line the plugin folds into its next model request, or `null`.
     *
     * `null` while the accessibility channel is down: the device tool stays advertised for `status`, but it has no
     * screen state to describe for a channel this run cannot observe, and such a line would be noise. Every other tool
     * in this catalog keeps the interface default.
     */
    override fun runtimeState(): String? = if (a11yAvailable()) device.runtimeState() else null

    override fun close() {
        device.close()
    }

    private fun channelRefused() = ActionResult(
        ActionOutcome.Refused,
        "{}",
        execution = ToolExecution(
            outcome = ToolOutcome.Refused,
            code = CHANNEL_UNAVAILABLE,
            recoverable = true,
            accepted = false,
            applied = false,
        ),
    )

    private fun callRefused() = ActionResult(
        ActionOutcome.Refused,
        "{}",
        execution = ToolExecution(
            outcome = ToolOutcome.Refused,
            code = CALL_PERMISSION_MISSING,
            recoverable = true,
            accepted = false,
            applied = false,
        ),
    )

    // ── edith-0.3 Phase 2.2 Task 6: shell/dial post-call observation ─────────────────────────────────────

    /**
     * Adds the shared execution facts + observation + at most one picture to one `shell`/`dial` result.
     *
     * Outcome, detail and `stateChanging` are never rewritten: a channel refusal stays `CHANNEL_UNAVAILABLE`, a shell
     * argument refusal keeps its empty `data` (its code is `execution.code`), and a non-zero exit stays `Failed`. A
     * refused **argument** is the one case that takes no picture (matching the `device`/`open` convention: there is no
     * action to explain), while a real attempt - success, execution failure, channel/call/lock refusal - gets one
     * best-effort capture and the `vision = ok` only when that capture really produced an image.
     *
     * `nonPersistent` is raised whenever an observation or picture exists, because both are perception content that may
     * live only in the current model request (C2).
     */
    private fun screenCall(
        tool: String,
        attachPicture: Boolean = true,
        call: () -> ActionResult,
    ): ActionResult = enrichScreenResult(tool, call(), attachPicture)

    /**
     * The enrichment half of [screenCall], split out so the JVM gate can feed a synthetic tool result.
     *
     * Today's `shell`/`dial` return no images of their own, so [ActionResult.images] is always empty in production. A
     * future tool version may attach one; the final `vision` fact is therefore derived from the **final** image list
     * (never from the capture's marker alone) and that list is coerced to at most one image, so a tool picture can never
     * be paired with `vision = disabled/unavailable`.
     *
     * M5: [attachPicture] false is the `open list` case - a non-screen read still observes the screen as text, but never
     * takes a picture.
     */
    internal fun enrichScreenResult(
        tool: String,
        result: ActionResult,
        attachPicture: Boolean = true,
    ): ActionResult {
        val observed = if (!attachPicture || isArgumentRefusal(tool, result)) {
            ObservationAttempt(textObservation(), emptyList())
        } else {
            screenAttempt(tool, attachPicture = result.images.isEmpty())
        }
        // Task 6 review: only the final image list may claim `vision = ok`; `take(1)` keeps one result to one image even
        // if a future tool ever returns more than one.
        val images = (result.images + observed.images).take(1)
        val observation = if (images.isEmpty()) {
            observed.observation
        } else {
            observed.observation.copy(vision = ToolVision.Ok, visionReason = null)
        }
        return result.copy(
            // Task 8: the tool now states its own facts; the catalog only fills a missing execution for a hand-built
            // result (a test seam). It never parses `data` again: `accepted/applied/verified/changed` live in one place.
            execution = result.execution ?: implicitExecution(result.outcome),
            observation = observation,
            images = images,
            // Every `shell`/`dial` result now carries an observation, so it is perception content by definition (C2).
            nonPersistent = true,
        )
    }

    /** The outcome-only fallback for a result that predates Task 8; no tri-state fact is invented. */
    private fun implicitExecution(outcome: ActionOutcome): ToolExecution = ToolExecution(
        outcome = when (outcome) {
            ActionOutcome.Done -> ToolOutcome.Done
            ActionOutcome.Refused -> ToolOutcome.Refused
            ActionOutcome.Failed -> ToolOutcome.Failed
        },
    )

    /** One screen attempt's observation and the picture it kept (never more than one). */
    private data class ObservationAttempt(
        val observation: ToolObservation,
        val images: List<ImageRef>,
    )

    /**
     * The one observation + picture builder for a real `shell`/`dial` attempt.
     *
     * The picture comes from [capture] and the facts from [observe]; they are two independent reads, so the catalog
     * cannot prove they describe the exact same frame. A capture port that throws is `Unavailable("SCREENSHOT_FAILED")`
     * (never a `TOOL_THREW`), and a missing or throwing observation source is `unavailable/no_source` instead of a
     * silent null. A source that already reported the screen unavailable keeps its own reason when no image arrived
     * (for example `no_accessibility`): that reason is the more useful of the two failure strings.
     *
     * P3-9 (Phase 3 Task 4): one real attempt that captures reports one count-only `P3_OBSERVE source=tool` line with
     * the two measured latencies and the capture's result token ([tool] is the tool being enriched). A path that takes
     * no picture ([attachPicture] false - an argument refusal or a tool that already attached its own image) measures no
     * capture and emits no line, so `result=disabled` always means the capture port really answered `Disabled`.
     */
    private fun screenAttempt(tool: String, attachPicture: Boolean): ObservationAttempt {
        val captureStarted = System.nanoTime()
        val captured = if (attachPicture) {
            runCatching { capture() }.getOrElse { PerceptionScreenshotResult.Unavailable(SCREENSHOT_FAILED) }
        } else {
            PerceptionScreenshotResult.Disabled
        }
        val captureNanos = System.nanoTime() - captureStarted
        val observeStarted = System.nanoTime()
        val source = runCatching { observe() }.getOrNull()
        val observeNanos = System.nanoTime() - observeStarted
        if (attachPicture) ToolObserveTelemetry.emit(tool, observeNanos, captureNanos, captured)
        val image = (captured as? PerceptionScreenshotResult.Image)?.image
        val observation = when {
            source != null -> source.copy(
                vision = when {
                    image != null -> ToolVision.Ok
                    captured == PerceptionScreenshotResult.Disabled -> ToolVision.Disabled
                    else -> ToolVision.Unavailable
                },
                visionReason = when {
                    image != null -> null
                    captured == PerceptionScreenshotResult.Disabled -> null
                    source.vision == ToolVision.Unavailable && source.visionReason != null -> source.visionReason
                    captured is PerceptionScreenshotResult.Unavailable -> captured.reason
                    else -> null
                },
            )
            image != null -> ToolObservation(
                // No source answered, so no screen/coverage text may be invented; the picture is still real.
                vision = ToolVision.Ok,
            )
            else -> ToolObservation(vision = ToolVision.Unavailable, visionReason = VISION_NO_SOURCE)
        }
        return ObservationAttempt(observation, listOfNotNull(image))
    }

    /** Task 6: the text-only observation an argument refusal can afford - no picture is attempted. */
    private fun textObservation(): ToolObservation {
        val source = runCatching { observe() }.getOrNull()
            ?: return ToolObservation(vision = ToolVision.Unavailable, visionReason = VISION_NO_SOURCE)
        return source.copy(vision = ToolVision.Unavailable, visionReason = VISION_NOT_ATTEMPTED)
    }

    /**
     * Whether [result] is a pure argument refusal, which takes no picture.
     *
     * Task 8 migrates this classifier to the **structured** execution facts: the tool states its stable code in
     * `execution.code` (the shell rejects a malformed command (`INVALID_ARGUMENTS`) or stdin (`UNSUPPORTED`), the dial
     * tool an undialable number (`ARGUMENTS_INVALID`)). It no longer parses `data.error.code`, which was the second copy
     * of facts the envelope already carries — and an argument refusal must still take no picture.
     */
    private fun isArgumentRefusal(tool: String, result: ActionResult): Boolean {
        if (result.outcome != ActionOutcome.Refused) return false
        val code = result.execution?.code ?: return false
        return when (tool) {
            ShellTool.NAME -> code == INVALID_ARGUMENTS || code == UNSUPPORTED
            DialTool.NAME -> code == ARGUMENTS_INVALID
            else -> false
        }
    }

    companion object {
        const val CHANNEL_UNAVAILABLE = "CHANNEL_UNAVAILABLE"
        const val CALL_PERMISSION_MISSING = "CALL_PERMISSION_MISSING"
        const val UNKNOWN_ACTION = "UNKNOWN_ACTION"

        /** An argument-shaped refusal; a malformed call never became an attempt and takes no picture. */
        private const val INVALID_ARGUMENTS = "INVALID_ARGUMENTS"
        private const val ARGUMENTS_INVALID = "ARGUMENTS_INVALID"
        private const val UNSUPPORTED = "UNSUPPORTED"

        private const val VISION_NO_SOURCE = "no_source"
        private const val VISION_NOT_ATTEMPTED = "not_attempted"
        private const val SCREENSHOT_FAILED = "SCREENSHOT_FAILED"
    }
}

/**
 * P3-9 (edith-0.3 Phase 3 Task 4): the one count-only `P3_OBSERVE source=tool` line per real observe/capture attempt.
 *
 * `source=loop` (the best-effort failure capture) is emitted by the plugin's observation source; this line is the tool
 * side, so acceptance can tell a slow platform read from a slow model/turn. Both emitters are the **tool call sites**
 * (`ToolCatalog` and `OpenTool`), never the shared source lambdas in the composition: those lambdas are consumed by two
 * tools, and timing them there would either lose the tool name or count the same capture twice. Each tool emits exactly
 * once per attempt that really captured.
 *
 * Only milliseconds, the tool name and the capture's own result token are carried; the reason lives in the observation
 * (and, for the loop source, in `P3_OBSERVE source=loop reason=`), and no screen, package, title, command or number can
 * reach this line.
 */
internal object ToolObserveTelemetry {

    /** The exact line shape the Phase 3 device acceptance greps. */
    fun line(tool: String, observeNanos: Long, captureNanos: Long, capture: PerceptionScreenshotResult): String =
        "P3_OBSERVE source=tool tool=$tool observeMs=${millis(observeNanos)} " +
            "captureMs=${millis(captureNanos)} result=${resultToken(capture)}"

    /** Emits one line through the phone-side sink; the call sites are already inside the turn's failure path. */
    fun emit(tool: String, observeNanos: Long, captureNanos: Long, capture: PerceptionScreenshotResult) {
        P3Log.line(line(tool, observeNanos, captureNanos, capture))
    }

    /** Nanoseconds -> whole milliseconds, never negative (integer division keeps the field deterministic). */
    private fun millis(nanos: Long): Long = (nanos / NANOS_PER_MILLI).coerceAtLeast(0L)

    private fun resultToken(capture: PerceptionScreenshotResult): String = when (capture) {
        is PerceptionScreenshotResult.Image -> "ok"
        PerceptionScreenshotResult.Disabled -> "disabled"
        is PerceptionScreenshotResult.Unavailable -> "unavailable"
    }

    private const val NANOS_PER_MILLI = 1_000_000L
}

/** The lenient parser model-authored arguments need (unquoted members are accepted). */
internal val toolArgumentsJson = Json { isLenient = true }

/** One argument object, or null when the model's string is not a JSON object. */
internal fun parseObject(argsJson: String): JsonObject? =
    runCatching { toolArgumentsJson.parseToJsonElement(argsJson) as? JsonObject }.getOrNull()

/** A member that must be a JSON string; a number, null or a missing member is not a string argument. */
internal fun JsonObject.stringArg(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

/** A member that must be a JSON boolean (a quoted `"true"` is a wrong type, not true). */
internal fun JsonObject.booleanArg(key: String): Boolean? =
    (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull

/** A member that must be a JSON integer; a quoted `"10"` is a wrong type, not 10. */
internal fun JsonObject.longArg(key: String): Long? =
    (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull
