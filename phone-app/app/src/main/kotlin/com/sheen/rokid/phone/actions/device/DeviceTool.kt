package com.sheen.rokid.phone.actions.device

import com.sheen.rokid.device.ActKind
import com.sheen.rokid.device.DeviceEnvelope
import com.sheen.rokid.device.DeviceErrorCodes
import com.sheen.rokid.device.DeviceKernel
import com.sheen.rokid.device.Observation
import com.sheen.rokid.device.Ref
import com.sheen.rokid.device.ScreenFilter
import com.sheen.rokid.plugin.ActionDescriptor
import com.sheen.rokid.plugin.ActionExecutor
import com.sheen.rokid.plugin.ActionOutcome
import com.sheen.rokid.plugin.ActionResult
import com.sheen.rokid.plugin.RiskLevel
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The kernel surface [DeviceTool] needs, as a seam.
 *
 * The production implementation is [DeviceKernelOperations]; tests script it directly, so the
 * tool's argument handling, projection and envelope mapping are exercised without the kernel's
 * worker thread and real-time settle.
 */
interface DeviceOperations {
    val latestObservation: Observation?

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

    /** The tool's own call shape (one argument object); [ActionExecutor.execute] adds the name. */
    fun execute(argsJson: String): ActionResult = execute(NAME, argsJson)

    override fun execute(name: String, argsJson: String): ActionResult {
        if (name != NAME) return refused(UNKNOWN_ACTION)
        val args = parseObject(argsJson) ?: return refused(INVALID_ARGUMENTS)
        val action = args.stringArg("action")?.trim()?.lowercase() ?: return refused(INVALID_ARGUMENTS)
        return when (action) {
            ACTION_OBSERVE -> observe(args)
            ACTION_ACT -> act(args)
            ACTION_SCREENSHOT -> screenshot()
            ACTION_WAIT -> wait(args)
            ACTION_STATUS -> status()
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
        val envelope = operations.observe(filter)
        return result(envelope, project(envelope, filter))
    }

    private fun act(args: JsonObject): ActionResult {
        val kindToken = args.stringArg("kind")?.trim()?.lowercase() ?: return refused(INVALID_ARGUMENTS)
        val kind = KIND_BY_TOKEN[kindToken] ?: return refused(INVALID_ARGUMENTS)
        val timeout = args.longArg("timeout_ms") ?: DEFAULT_TIMEOUT_MILLIS
        if (timeout <= 0L) return refused(INVALID_ARGUMENTS)
        if (kind == ActKind.SCROLL_TO_TEXT) return scrollToText(args, timeout)
        val ref = args.stringArg("ref")?.trim()?.takeIf { it.isNotEmpty() }?.let(::Ref)
        val envelope = operations.act(
            kind = kind,
            ref = ref,
            text = args.stringArg("text"),
            value = args.stringArg("value"),
            direction = args.stringArg("direction"),
            key = args.stringArg("key"),
            timeoutMillis = timeout,
        )
        return result(envelope, project(envelope, lastFilter))
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
    private fun scrollToText(args: JsonObject, timeoutMillis: Long): ActionResult {
        val text = args.stringArg("text")?.trim()?.takeIf { it.isNotEmpty() }
            ?: return refused(INVALID_ARGUMENTS)
        val direction = args.stringArg("direction")?.trim()?.takeIf { it.isNotEmpty() } ?: "forward"
        val started = clock()
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
                return result(scrolled.copy(action = ACTION_ACT), project(scrolled, lastFilter))
            }
            val waited = operations.waitFor(text, null, false, remaining)
            last = waited
            if (waited.ok) {
                return result(waited.copy(action = ACTION_ACT), project(waited, lastFilter))
            }
            if (waited.error?.code != DeviceErrorCodes.VERIFY_TIMEOUT) {
                return result(waited.copy(action = ACTION_ACT), project(waited, lastFilter))
            }
        } while (clock() - started < timeoutMillis)
        val envelope = last ?: operations.waitFor(text, null, false, 1L)
        return result(envelope.copy(action = ACTION_ACT), project(envelope, lastFilter))
    }

    private fun screenshot(): ActionResult {
        val envelope = operations.screenshot()
        val data = if (envelope.ok) {
            buildJsonObject {
                put("diagnostic", "screenshot is an artifact reference; bytes are not inlined")
            }
        } else {
            buildJsonObject {
                put("screen", JsonNull)
                put("diagnostic", envelope.error?.code ?: "the screenshot failed")
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

    private fun status(): ActionResult {
        val envelope = operations.status()
        val data = buildJsonObject {
            // R41: keep the kernel's key=value diagnostic line verbatim, just inside a JSON object.
            put("status", envelope.data ?: envelope.error?.code ?: "")
        }
        return result(envelope, data)
    }

    private fun project(envelope: DeviceEnvelope, filter: ScreenFilter): JsonObject {
        val observation = operations.latestObservation
        return if (observation != null) {
            projector.project(observation, filter)
        } else {
            buildJsonObject {
                put("screen", JsonNull)
                put("diagnostic", envelope.error?.code ?: "no observation")
            }
        }
    }

    private fun result(envelope: DeviceEnvelope, data: JsonObject): ActionResult {
        val outcome = when {
            envelope.ok -> ActionOutcome.Done
            envelope.error?.recoverable == true -> ActionOutcome.Refused
            else -> ActionOutcome.Failed
        }
        return ActionResult(outcome, renderEnvelope(envelope, data))
    }

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
         */
        val DESCRIPTOR: ActionDescriptor = ActionDescriptor(
            name = NAME,
            title = "设备控制",
            parametersJson = """{"type":"object","properties":{""" +
                """"action":{"type":"string","enum":["observe","act","screenshot","wait","status"]},""" +
                """"filter":{"type":"string","enum":["interactive","text","all"]},""" +
                """"kind":{"type":"string","enum":["tap","long_press","double_tap","swipe","scroll",""" +
                """"scroll_to_text","type","press_key","home","back","recents","notifications"]},""" +
                """"ref":{"type":"string"},"text":{"type":"string"},"value":{"type":"string"},""" +
                """"direction":{"type":"string"},"key":{"type":"string"},"foreground":{"type":"string"},""" +
                """"idle":{"type":"boolean"},"timeout_ms":{"type":"integer","minimum":1}},""" +
                """"required":["action"],"additionalProperties":false}""",
            risk = RiskLevel.L1,
            requiresConfirmation = false,
            idempotent = false,
            reversible = false,
            scope = "app.ui",
        )
    }
}
