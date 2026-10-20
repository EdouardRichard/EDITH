package com.sheen.rokid.phone.actions.device

import com.sheen.rokid.device.ActKind
import com.sheen.rokid.device.Bounds
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
        val revisionBefore = operations.revision
        val envelope = operations.observe(filter)
        return result(envelope, project(envelope, filter), fingerprintAfter(envelope, revisionBefore))
    }

    private fun act(args: JsonObject): ActionResult {
        val kindToken = args.stringArg("kind")?.trim()?.lowercase() ?: return refused(INVALID_ARGUMENTS)
        val kind = KIND_BY_TOKEN[kindToken] ?: return refused(INVALID_ARGUMENTS)
        val timeout = args.longArg("timeout_ms") ?: DEFAULT_TIMEOUT_MILLIS
        if (timeout <= 0L) return refused(INVALID_ARGUMENTS)
        if (kind == ActKind.SCROLL_TO_TEXT) return scrollToText(args, timeout)
        val ref = args.stringArg("ref")?.trim()?.takeIf { it.isNotEmpty() }?.let(::normalizedRef)
        val revisionBefore = operations.revision
        val envelope = operations.act(
            kind = kind,
            ref = ref,
            text = args.stringArg("text"),
            value = args.stringArg("value"),
            direction = args.stringArg("direction"),
            key = args.stringArg("key"),
            timeoutMillis = timeout,
        )
        return result(
            envelope,
            project(envelope, lastFilter),
            fingerprintAfter(envelope, revisionBefore),
            stateChanging = true,
        )
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
                return result(
                    scrolled.copy(action = ACTION_ACT),
                    project(scrolled, lastFilter),
                    fingerprintAfter(scrolled, revisionBefore),
                    stateChanging = true,
                )
            }
            val waited = operations.waitFor(text, null, false, remaining)
            last = waited
            if (waited.ok) {
                return result(
                    waited.copy(action = ACTION_ACT),
                    project(waited, lastFilter),
                    fingerprintAfter(waited, revisionBefore),
                    stateChanging = true,
                )
            }
            if (waited.error?.code != DeviceErrorCodes.VERIFY_TIMEOUT) {
                return result(
                    waited.copy(action = ACTION_ACT),
                    project(waited, lastFilter),
                    fingerprintAfter(waited, revisionBefore),
                    stateChanging = true,
                )
            }
        } while (clock() - started < timeoutMillis)
        val envelope = last ?: operations.waitFor(text, null, false, 1L)
        return result(
            envelope.copy(action = ACTION_ACT),
            project(envelope, lastFilter),
            fingerprintAfter(envelope, revisionBefore),
            stateChanging = true,
        )
    }

    private fun screenshot(): ActionResult {
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

    private fun status(): ActionResult {
        val envelope = operations.status()
        val data = buildJsonObject {
            // R41: keep the kernel's key=value diagnostic line verbatim, just inside a JSON object.
            put("status", envelope.data ?: envelope.error?.code ?: "")
        }
        return result(envelope, data)
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
     */
    private fun result(
        envelope: DeviceEnvelope,
        data: JsonObject,
        fingerprint: String? = null,
        stateChanging: Boolean = false,
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
        return ActionResult(outcome, renderEnvelope(envelope, data), fingerprint, stateChanging, images = envelope.images)
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
