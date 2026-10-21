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
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * R40/R47: the production `Capability.Actions` provider.
 *
 * It advertises exactly the four host tools — `device`, `shell`, `open`, `dial` — and re-probes
 * each channel on **every** `descriptors()`/`execute()` call, because a channel's answer changes
 * while the App runs (accessibility switched off, Shizuku binder dead, `CALL_PHONE` revoked). An
 * unavailable channel's tool is not advertised, and a call that arrives after its channel went down
 * is refused instead of being delegated. The one exception is `device`: it stays advertised because
 * its `status` action must be answerable while the accessibility service is down, and the other
 * actions are still refused at execution time. The plugin adds `notify_user`/`load_skill` beside
 * this catalog, which is what makes the model-visible surface exactly six (R40).
 *
 * edith-0.3 Phase 2.2 Task 6: `shell`/`dial` results also carry a post-call observation, structured
 * execution facts and at most one best-effort image. This catalog owns that enrichment (the two tools
 * keep their own outcome/envelope shapes) and reuses the host observation source `OpenTool` gets;
 * `device`/`open` were migrated by Tasks 4-5 and keep their own policies.
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

    override fun descriptors(): List<ActionDescriptor> = buildList {
        // Task 7 review I2 (R11): `device` stays advertised even while the accessibility channel is down. Its `status`
        // action is the diagnosis that reports `accessibility=false`/`locked=true`; hiding the whole tool behind the
        // condition it exists to report made that answer unreachable on a blind phone. Every non-status action is still
        // refused by `execute`'s a11y check below, so the advertisement cannot run anything the channel cannot serve.
        add(device.descriptors().single())
        if (shellAvailable()) {
            // Task 12 fix (R99): the shell descriptor keeps its machine cap and declares `mayAskHuman`; the plugin wraps
            // this executor in `ConfirmingActions`, which asks the wearer inside the same `execute` call, and the agent
            // loop adds the human allowance plus shifts the measured wait out of the turn (D49). Inflating
            // `timeoutMillis` here would be clamped by the turn deadline and cut a legal 120 s decision.
            add(shell.descriptors().single())
            add(open.descriptors().single())
        }
        if (callAvailable()) add(dial.descriptors().single())
    }

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
            if (device.isStatus(argsJson)) {
                device.execute(name, argsJson)
            } else {
                gated(unlock.ensureUnlocked(requireAccessibility = true)) {
                    if (a11yAvailable()) device.execute(name, argsJson) else channelRefused()
                }
            }
        ShellTool.NAME ->
            screenCall(ShellTool.NAME) { if (shellAvailable()) shell.execute(name, argsJson) else channelRefused() }
        OpenTool.NAME ->
            if (!shellAvailable()) channelRefused()
            else gated(unlock.ensureUnlocked(requireAccessibility = false)) { open.execute(name, argsJson) }
        DialTool.NAME ->
            screenCall(DialTool.NAME) {
                if (!callAvailable()) {
                    callRefused()
                } else {
                    gated(unlock.ensureUnlocked(requireAccessibility = false)) { dial.execute(name, argsJson) }
                }
            }
            else -> ActionResult(ActionOutcome.Refused, UNKNOWN_ACTION)
        }
    }

    /**
     * A refused unlock is reported as a refusal with a code the model can act on; the tool body is never entered, so a
     * device action cannot run against a still-locked screen and get mislabeled as an accessibility failure.
     */
    private inline fun gated(outcome: UnlockOutcome, action: () -> ActionResult): ActionResult =
        if (outcome.usable) action() else ActionResult(ActionOutcome.Refused, "LOCKED_UNLOCK_FAILED:${outcome.name}")

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

    private fun channelRefused() = ActionResult(ActionOutcome.Refused, CHANNEL_UNAVAILABLE)

    private fun callRefused() = ActionResult(ActionOutcome.Refused, CALL_PERMISSION_MISSING)

    // ── edith-0.3 Phase 2.2 Task 6: shell/dial post-call observation ─────────────────────────────────────

    /**
     * Adds the shared execution facts + observation + at most one picture to one `shell`/`dial` result.
     *
     * Outcome, detail and `stateChanging` are never rewritten: a channel refusal stays `CHANNEL_UNAVAILABLE`, a shell
     * argument refusal keeps its bare code, and a non-zero exit stays `Failed`. A refused **argument** is the one case
     * that takes no picture (matching the `device`/`open` convention: there is no action to explain), while a real
     * attempt - success, execution failure, channel/call/lock refusal - gets one best-effort capture and the
     * `vision = ok` only when that capture really produced an image.
     *
     * `nonPersistent` is raised whenever an observation or picture exists, because both are perception content that may
     * live only in the current model request (C2).
     */
    private fun screenCall(tool: String, call: () -> ActionResult): ActionResult =
        enrichScreenResult(tool, call())

    /**
     * The enrichment half of [screenCall], split out so the JVM gate can feed a synthetic tool result.
     *
     * Today's `shell`/`dial` return no images of their own, so [ActionResult.images] is always empty in production. A
     * future tool version may attach one; the final `vision` fact is therefore derived from the **final** image list
     * (never from the capture's marker alone) and that list is coerced to at most one image, so a tool picture can never
     * be paired with `vision = disabled/unavailable`.
     */
    internal fun enrichScreenResult(tool: String, result: ActionResult): ActionResult {
        val observed = if (isArgumentRefusal(tool, result)) {
            ObservationAttempt(textObservation(), emptyList())
        } else {
            screenAttempt(attachPicture = result.images.isEmpty())
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
            execution = result.execution ?: executionFacts(tool, result),
            observation = observation,
            images = images,
            // Every `shell`/`dial` result now carries an observation, so it is perception content by definition (C2).
            nonPersistent = true,
        )
    }

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
     */
    private fun screenAttempt(attachPicture: Boolean): ObservationAttempt {
        val captured = if (attachPicture) {
            runCatching { capture() }.getOrElse { PerceptionScreenshotResult.Unavailable(SCREENSHOT_FAILED) }
        } else {
            PerceptionScreenshotResult.Disabled
        }
        val source = runCatching { observe() }.getOrNull()
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
     * The classifier reads only the tools' own stable codes, whose casing and shape are already part of their
     * contract: the shell rejects a malformed command (`INVALID_ARGUMENTS`) or stdin (`UNSUPPORTED`), the dial tool an
     * undialable number (`ARGUMENTS_INVALID`). Every other refusal is a real attempt whose screen the model should see.
     */
    private fun isArgumentRefusal(tool: String, result: ActionResult): Boolean {
        if (result.outcome != ActionOutcome.Refused) return false
        return when (tool) {
            ShellTool.NAME -> errorCodeOf(result).let { it == INVALID_ARGUMENTS || it == UNSUPPORTED }
            DialTool.NAME -> errorCodeOf(result) == ARGUMENTS_INVALID
            else -> false
        }
    }

    /**
     * The structured execution facts of one `shell`/`dial` result, derived from its own outcome and stable code.
     *
     * The shell is deliberately not given a verdict it cannot support: an exit code is **not** a target-state answer
     * (`PrivilegedShell` documents a false `OK` and a false `BLOCKED` measured on the K40), so a completed shell call is
     * `accepted = true` with `applied = null` whether it exited 0 or not - the command ran, whether it took effect is
     * unknown. The one exception is a truncated reply (`SHELL_REPLY_TOO_LARGE`): the command did run, only the answer
     * was cut. A refusal that never dispatched the action keeps `accepted/applied = false`, and an intent hand-off is
     * never verified as applied. Only a malformed argument is marked non-recoverable.
     */
    private fun executionFacts(tool: String, result: ActionResult): ToolExecution {
        val code = errorCodeOf(result)
        return when (result.outcome) {
            ActionOutcome.Done -> ToolExecution(
                outcome = ToolOutcome.Done,
                accepted = true,
                applied = null,
            )
            ActionOutcome.Failed -> ToolExecution(
                outcome = ToolOutcome.Failed,
                code = code,
                recoverable = true,
                accepted = true,
                // `dial`'s one failure means the intent could not be launched (nothing dispatched); a shell command's
                // non-zero exit does not prove its side effects did not happen, so that stays unknown.
                applied = if (tool == DialTool.NAME) false else null,
            )
            ActionOutcome.Refused -> {
                val executedAnyway = code == SHELL_REPLY_TOO_LARGE
                ToolExecution(
                    outcome = ToolOutcome.Refused,
                    code = code,
                    recoverable = code !in NON_RECOVERABLE_REFUSALS,
                    accepted = executedAnyway,
                    applied = if (executedAnyway) null else false,
                )
            }
        }
    }

    /**
     * The stable error code of a tool result, or `null`.
     *
     * `shell`/`dial` return two shapes: a bare code for a validation refusal (the pre-Task-6 shape is kept) and the
     * `toolEnvelope` JSON for everything that reached the channel, which carries `error.code` (or `null` for a
     * zero/non-zero exit that crossed the shell). Reading it here is what lets the structured facts stay stable without
     * touching either tool.
     */
    private fun errorCodeOf(result: ActionResult): String? {
        val detail = result.detail
        if (!detail.startsWith("{")) return detail.takeIf { it.isNotBlank() }
        return runCatching {
            val root = toolArgumentsJson.parseToJsonElement(detail) as? JsonObject ?: return@runCatching null
            val error = root["error"] as? JsonObject ?: return@runCatching null
            (error["code"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        }.getOrNull()
    }

    companion object {
        const val CHANNEL_UNAVAILABLE = "CHANNEL_UNAVAILABLE"
        const val CALL_PERMISSION_MISSING = "CALL_PERMISSION_MISSING"
        const val UNKNOWN_ACTION = "UNKNOWN_ACTION"

        /** An argument-shaped refusal; a malformed call never became an attempt and takes no picture. */
        private const val INVALID_ARGUMENTS = "INVALID_ARGUMENTS"
        private const val ARGUMENTS_INVALID = "ARGUMENTS_INVALID"
        private const val UNSUPPORTED = "UNSUPPORTED"

        /** The one shell refusal where the command is known to have run (only its reply was cut). */
        private const val SHELL_REPLY_TOO_LARGE = "SHELL_REPLY_TOO_LARGE"

        private val NON_RECOVERABLE_REFUSALS = setOf(INVALID_ARGUMENTS, ARGUMENTS_INVALID, UNSUPPORTED)

        private const val VISION_NO_SOURCE = "no_source"
        private const val VISION_NOT_ATTEMPTED = "not_attempted"
        private const val SCREENSHOT_FAILED = "SCREENSHOT_FAILED"
    }
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

/** One tool failure rendered into the model-facing JSON `error` object. */
internal data class ToolFailure(
    val code: String,
    val message: String,
    val recoverable: Boolean = true,
)

/**
 * The common envelope shape for the non-device tools (`open`/`dial`; `device` adds `verified`,
 * `images` and `timing` itself). Every field is always present so a result has one shape whether it
 * succeeded, was refused or failed.
 */
internal fun toolEnvelope(
    tool: String,
    ok: Boolean,
    channel: String,
    accepted: Boolean = ok,
    applied: Boolean? = null,
    data: JsonElement? = null,
    error: ToolFailure? = null,
    extra: JsonObjectBuilder.() -> Unit = {},
): String = buildJsonObject {
    put("tool", tool)
    put("action", tool)
    put("channel", channel)
    put("ok", ok)
    put("accepted", accepted)
    if (applied == null) put("applied", JsonNull) else put("applied", applied)
    put("data", data ?: JsonNull)
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
    extra()
}.toString()
