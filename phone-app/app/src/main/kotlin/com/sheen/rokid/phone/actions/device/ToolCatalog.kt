package com.sheen.rokid.phone.actions.device

import com.sheen.rokid.plugin.ActionDescriptor
import com.sheen.rokid.plugin.ActionExecutor
import com.sheen.rokid.plugin.ActionOutcome
import com.sheen.rokid.plugin.ActionPrecheck
import com.sheen.rokid.plugin.ActionResult
import com.sheen.rokid.plugin.PrecheckDecision
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
 * is refused instead of being delegated. The plugin adds `notify_user`/`load_skill` beside this
 * catalog, which is what makes the model-visible surface exactly six (R40).
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
) : ActionExecutor, AutoCloseable {

    override fun descriptors(): List<ActionDescriptor> = buildList {
        if (a11yAvailable()) add(device.descriptors().single())
        if (shellAvailable()) {
            // Task 12 (R60): the *plugin* wraps this executor in `ConfirmingActions`, which asks the wearer before a
            // risky command reaches the shell — inside the same `execute` call the loop time-bounds. A 30 s machine
            // cap would turn a slow (but legal, D47 120 s) human decision into `ACTION_TIMEOUT`, so the shell call
            // declares room for the full decision window plus the command itself. The turn deadline still bounds the
            // run: `min(descriptor.timeoutMillis, turnDeadline - now)` is what the loop enforces.
            add(shell.descriptors().single().copy(timeoutMillis = SHELL_CALL_TIMEOUT_MILLIS))
            add(open.descriptors().single())
        }
        if (callAvailable()) add(dial.descriptors().single())
    }

    override fun execute(name: String, argsJson: String): ActionResult = when (name) {
        DeviceTool.NAME ->
            if (a11yAvailable()) device.execute(name, argsJson) else channelRefused()
        ShellTool.NAME ->
            if (shellAvailable()) shell.execute(name, argsJson) else channelRefused()
        OpenTool.NAME ->
            if (shellAvailable()) open.execute(name, argsJson) else channelRefused()
        DialTool.NAME ->
            if (callAvailable()) dial.execute(name, argsJson) else callRefused()
        else -> ActionResult(ActionOutcome.Refused, UNKNOWN_ACTION)
    }

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
     * `null` while the accessibility channel is down: the device tool is not advertised then, so a state line about a
     * screen this run cannot even observe would be noise. Every other tool in this catalog keeps the interface default.
     */
    override fun runtimeState(): String? = if (a11yAvailable()) device.runtimeState() else null

    override fun close() {
        device.close()
    }

    private fun channelRefused() = ActionResult(ActionOutcome.Refused, CHANNEL_UNAVAILABLE)

    private fun callRefused() = ActionResult(ActionOutcome.Refused, CALL_PERMISSION_MISSING)

    companion object {
        const val CHANNEL_UNAVAILABLE = "CHANNEL_UNAVAILABLE"
        const val CALL_PERMISSION_MISSING = "CALL_PERMISSION_MISSING"
        const val UNKNOWN_ACTION = "UNKNOWN_ACTION"

        /**
         * Task 12 (R60): the confirmation window (D47: 120 s) plus the shell tool's own default command budget.
         *
         * This is not a second confirmation timeout — it is the machine-time cap the loop must not apply while the
         * wearer is still deciding. The run's turn deadline remains the outer bound.
         */
        const val SHELL_CALL_TIMEOUT_MILLIS = 120_000L + 30_000L
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
