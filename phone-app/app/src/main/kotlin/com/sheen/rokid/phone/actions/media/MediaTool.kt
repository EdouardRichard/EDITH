package com.sheen.rokid.phone.actions.media

import com.sheen.rokid.phone.actions.ExecutionBackend
import com.sheen.rokid.phone.actions.device.longArg
import com.sheen.rokid.phone.actions.device.parseObject
import com.sheen.rokid.phone.actions.device.stringArg
import com.sheen.rokid.phone.platform.P3Log
import com.sheen.rokid.plugin.ActionDescriptor
import com.sheen.rokid.plugin.ActionOutcome
import com.sheen.rokid.plugin.ActionResult
import com.sheen.rokid.plugin.RiskLevel
import com.sheen.rokid.plugin.ToolExecution
import com.sheen.rokid.plugin.ToolOutcome
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 0.5.4 Task 15 (spec §6.5): `media` - list the active media sessions and control exactly one of them.
 *
 * The split of responsibility is deliberate:
 * - this tool owns the model-facing state machine: argument validation, target selection, the **pre-send**
 *   `PlaybackState.actions` bit check, the stable failure codes and the content-free audit line. A missing bit is
 *   `ACTION_UNSUPPORTED` before the port is asked to send anything;
 * - the [MediaControlPort] owns Android: it sends one transport command, polls `PlaybackState` for evidence inside the
 *   spec's 1.5 s window and releases every controller. The tool never treats "the command was sent" as success -
 *   `Done` arrives only with the port's `before`/`after` pair.
 *
 * Target selection follows the spec exactly: a named `package` selects that session (even when several are active); a
 * unique session is used when no package is given; several sessions without a package are `AMBIGUOUS_SESSION` with the
 * candidate packages and are **never** silently resolved by list order; no session is `NO_SESSION`. Notification access
 * missing is its own `NO_NOTIFICATION_ACCESS`, never an empty list.
 *
 * Not implemented on purpose: search/`playFromSearch`, per-session volume, `AudioManager.dispatchMediaKeyEvent` as the
 * main channel, and any cross-turn session cache. The audit/log line carries only action, package, state/count and
 * result code - never a title, artist or album.
 */
class MediaTool(private val port: MediaControlPort) : ExecutionBackend {

    override val id = ID

    /** The notification listener can be off at call time; that is `NO_NOTIFICATION_ACCESS`, not an unavailable tool. */
    override fun available(): Boolean = true

    override fun descriptors(): List<ActionDescriptor> = listOf(DESCRIPTOR)

    /** The tool's own call shape (one argument object); the `ActionExecutor` contract adds the name. */
    fun execute(argsJson: String): ActionResult = execute(NAME, argsJson)

    override fun execute(name: String, argsJson: String): ActionResult {
        if (name != NAME) {
            return refusal(UNKNOWN_ACTION, "the media tool only answers media", recoverable = false)
        }
        val args = parseObject(argsJson) ?: return refusal(
            ARGUMENTS_INVALID,
            "media needs an action",
            recoverable = false,
        )
        val action = args.stringArg(ACTION)?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return refusal(
            ARGUMENTS_INVALID,
            "media needs a non-empty action string",
            recoverable = false,
        )
        if (action !in MediaAction.ALL) {
            return refusal(
                UNKNOWN_ACTION,
                "action must be one of ${MediaAction.ALL.sorted().joinToString("/")}",
                recoverable = false,
            )
        }
        // An optional member that is present but not a string/number is a malformed call, never a silently dropped
        // package or position.
        if (args.containsKey(PACKAGE) && args.stringArg(PACKAGE) == null) {
            return refusal(ARGUMENTS_INVALID, "package must be a string", recoverable = false)
        }
        if (args.containsKey(POSITION_MS) && args.longArg(POSITION_MS) == null) {
            return refusal(ARGUMENTS_INVALID, "position_ms must be an integer", recoverable = false)
        }
        val requestedPackage = args.stringArg(PACKAGE)?.trim()?.takeIf { it.isNotEmpty() }
        val requestedPosition = args.longArg(POSITION_MS)
        if (action == MediaAction.SEEK && (requestedPosition == null || requestedPosition < 0L)) {
            return refusal(ARGUMENTS_INVALID, "seek needs a non-negative position_ms in milliseconds", recoverable = false)
        }

        val sessions = try {
            port.list()
        } catch (_: MediaNotificationAccessException) {
            return audited(
                action = action,
                sessions = 0,
                packageName = requestedPackage,
                code = NO_NOTIFICATION_ACCESS,
                result = refusal(
                    NO_NOTIFICATION_ACCESS,
                    "notification access is not granted, so media sessions cannot be read",
                    recoverable = true,
                ),
            )
        }
        if (action == MediaAction.LIST) {
            return audited(action, sessions.size, null, "ok", listResult(sessions))
        }
        if (sessions.isEmpty()) {
            return audited(
                action = action,
                sessions = 0,
                packageName = requestedPackage,
                code = NO_SESSION,
                result = refusal(NO_SESSION, "no active media session", recoverable = true),
            )
        }
        // A named package is authoritative even when several sessions are active. Without a package a unique session is
        // the only unambiguous target; choosing one of several would be the tool inventing a target.
        val candidates = if (requestedPackage == null) {
            sessions
        } else {
            sessions.filter { it.packageName == requestedPackage }
        }
        if (candidates.isEmpty()) {
            return audited(
                action = action,
                sessions = sessions.size,
                packageName = requestedPackage,
                code = NO_SESSION,
                result = refusal(
                    NO_SESSION,
                    "no active session belongs to $requestedPackage",
                    recoverable = true,
                ),
            )
        }
        if (requestedPackage == null && candidates.size > 1) {
            val packages = candidates.map { it.packageName }
            return audited(
                action = action,
                sessions = sessions.size,
                packageName = null,
                code = AMBIGUOUS_SESSION,
                result = refusal(
                    AMBIGUOUS_SESSION,
                    "more than one media session is active; pass package to choose one",
                    recoverable = true,
                    data = buildJsonObject {
                        put("action", action)
                        put("candidates", stringArray(packages))
                    },
                ),
            )
        }
        // The media-key-event session is the phone's current one when the platform names it; otherwise list order is
        // the only available fact.
        val target = candidates.firstOrNull { it.current } ?: candidates.first()
        // The pre-send gate: a session that does not advertise the transport bit must not receive the command.
        val bit = requireNotNull(MediaAction.requiredBit(action))
        if ((target.actions and bit) == 0L) {
            return audited(
                action = action,
                sessions = sessions.size,
                packageName = target.packageName,
                code = ACTION_UNSUPPORTED,
                result = refusal(
                    ACTION_UNSUPPORTED,
                    "the session does not advertise the transport action for $action",
                    recoverable = true,
                    data = baseData(action, target.packageName),
                ),
            )
        }
        val position = if (action == MediaAction.SEEK) requestedPosition else null
        val controlled = port.control(action, target.packageName, position)
        return audited(
            action = action,
            sessions = sessions.size,
            packageName = target.packageName,
            code = codeFor(controlled),
            result = controlResult(action, target, position, controlled),
        )
    }

    /** `list` is a read: it reports every session and does not claim it changed anything. */
    private fun listResult(sessions: List<MediaSessionInfo>): ActionResult = ActionResult(
        ActionOutcome.Done,
        buildJsonObject {
            put("action", MediaAction.LIST)
            put("count", sessions.size)
            put("sessions", buildJsonArray { sessions.forEach { add(sessionJson(it)) } })
        }.toString(),
        stateChanging = false,
        // title/artist/album are user content: the model sees them in this request only, never in the stored transcript.
        nonPersistent = true,
        execution = ToolExecution(outcome = ToolOutcome.Done, accepted = true),
    )

    /** Maps the port's answer to the model-facing code; only `Done` carries verified execution facts. */
    private fun controlResult(
        action: String,
        target: MediaSessionInfo,
        positionMs: Long?,
        result: MediaControlResult,
    ): ActionResult = when (result) {
        is MediaControlResult.Done -> ActionResult(
            ActionOutcome.Done,
            buildJsonObject {
                put("action", action)
                put("package", target.packageName)
                put("before", sessionJson(result.before))
                put("after", sessionJson(result.after))
            }.toString(),
            stateChanging = true,
            // before/after carry title/artist/album, so this content is request-scoped like `device.status`.
            nonPersistent = true,
            execution = ToolExecution(
                outcome = ToolOutcome.Done,
                accepted = true,
                applied = true,
                verified = true,
                changed = true,
            ),
        )
        MediaControlResult.NoPermission -> refusal(
            NO_NOTIFICATION_ACCESS,
            "notification access was lost before the command could be sent",
            recoverable = true,
        )
        MediaControlResult.NoSession -> refusal(NO_SESSION, "the media session is no longer active", recoverable = true)
        is MediaControlResult.Ambiguous -> refusal(
            AMBIGUOUS_SESSION,
            "more than one media session matches; pass package to choose one",
            recoverable = true,
            data = buildJsonObject {
                put("action", action)
                put("candidates", stringArray(result.candidates))
            },
        )
        MediaControlResult.Unsupported -> refusal(
            ACTION_UNSUPPORTED,
            "the session does not advertise the transport action for $action",
            recoverable = true,
            data = buildJsonObject {
                put("action", action)
                put("package", target.packageName)
            },
        )
        MediaControlResult.NoChange -> postSendFailure(
            NO_STATE_CHANGE,
            "the transport command was sent but the session state did not change in 1.5 s",
            action,
            target.packageName,
        )
        MediaControlResult.SessionGone -> postSendFailure(
            SESSION_GONE,
            "the media session disappeared before the command could be verified",
            action,
            target.packageName,
        )
    }

    private fun codeFor(result: MediaControlResult): String = when (result) {
        is MediaControlResult.Done -> "ok"
        MediaControlResult.NoPermission -> NO_NOTIFICATION_ACCESS
        MediaControlResult.NoSession -> NO_SESSION
        is MediaControlResult.Ambiguous -> AMBIGUOUS_SESSION
        MediaControlResult.Unsupported -> ACTION_UNSUPPORTED
        MediaControlResult.NoChange -> NO_STATE_CHANGE
        MediaControlResult.SessionGone -> SESSION_GONE
    }

    /** A refusal happened before anything was dispatched: `accepted/applied` are false, not unknown. */
    private fun refusal(
        code: String,
        message: String,
        recoverable: Boolean,
        data: JsonObject = JsonObject(emptyMap()),
    ): ActionResult = ActionResult(
        ActionOutcome.Refused,
        data.toString(),
        stateChanging = true,
        execution = ToolExecution(
            outcome = ToolOutcome.Refused,
            code = code,
            message = message,
            recoverable = recoverable,
            accepted = false,
            applied = false,
            verified = null,
            changed = null,
        ),
    )

    /**
     * The command was dispatched but its effect was not proven. `accepted=true` is the one fact we have; `applied`
     * stays null because "the state did not change" is not "the command did nothing".
     */
    private fun postSendFailure(
        code: String,
        message: String,
        action: String,
        packageName: String,
    ): ActionResult = ActionResult(
        ActionOutcome.Failed,
        baseData(action, packageName).toString(),
        stateChanging = true,
        execution = ToolExecution(
            outcome = ToolOutcome.Failed,
            code = code,
            message = message,
            recoverable = true,
            accepted = true,
            applied = null,
            verified = false,
            changed = null,
        ),
    )

    private fun baseData(action: String, packageName: String?): JsonObject = buildJsonObject {
        put("action", action)
        put("package", if (packageName != null) JsonPrimitive(packageName) else JsonNull)
    }

    /** The model-facing session shape: platform facts only, no credentials and no controller handle. */
    private fun sessionJson(session: MediaSessionInfo): JsonObject = buildJsonObject {
        put("package", session.packageName)
        put("state", session.state)
        put("title", if (session.title != null) JsonPrimitive(session.title) else JsonNull)
        put("artist", if (session.artist != null) JsonPrimitive(session.artist) else JsonNull)
        put("album", if (session.album != null) JsonPrimitive(session.album) else JsonNull)
        put("positionMs", if (session.positionMs != null) JsonPrimitive(session.positionMs) else JsonNull)
        put("durationMs", if (session.durationMs != null) JsonPrimitive(session.durationMs) else JsonNull)
        put("actions", session.actions)
        put("current", session.current)
        put("controllable", session.controllable)
    }

    private fun stringArray(values: List<String>): JsonArray = buildJsonArray {
        values.forEach { add(JsonPrimitive(it)) }
    }

    /**
     * The one count-only audit line per parsed call: action, target package, session count and the result code.
     * Metadata (title/artist/album) is user content and never appears here.
     */
    private fun audited(
        action: String,
        sessions: Int,
        packageName: String?,
        code: String,
        result: ActionResult,
    ): ActionResult {
        P3Log.line(
            "P3_MEDIA action=$action sessions=$sessions result=$code package=${packageName ?: "-"}",
        )
        return result
    }

    companion object {
        const val NAME = "media"

        /** The stable execution-channel id; the notification listener has no unavailable advertisement state. */
        const val ID = "media_session"

        private const val ACTION = "action"
        private const val PACKAGE = "package"
        private const val POSITION_MS = "position_ms"

        private const val ARGUMENTS_INVALID = "ARGUMENTS_INVALID"
        private const val UNKNOWN_ACTION = "UNKNOWN_ACTION"
        private const val NO_NOTIFICATION_ACCESS = "NO_NOTIFICATION_ACCESS"
        private const val NO_SESSION = "NO_SESSION"
        private const val AMBIGUOUS_SESSION = "AMBIGUOUS_SESSION"
        private const val ACTION_UNSUPPORTED = "ACTION_UNSUPPORTED"
        private const val NO_STATE_CHANGE = "NO_STATE_CHANGE"
        private const val SESSION_GONE = "SESSION_GONE"

        val DESCRIPTOR: ActionDescriptor = ActionDescriptor(
            name = NAME,
            title = "控制媒体播放",
            description = "列出并控制手机上已有的媒体会话，不搜索、不调音量。action 只能是 " +
                "list、play、pause、toggle、next、previous、stop、seek；list 返回每个会话的 package、state、" +
                "title、artist、album、positionMs、durationMs、actions、current、controllable，其中 current 是系统的" +
                "媒体键会话（API 33+）。有 package 时只控制该包名的会话；无 package 且只有一个会话时控制它；" +
                "多个会话又没给 package 返回 AMBIGUOUS_SESSION 并列出候选包名，绝不替你选；没有会话返回 NO_SESSION；" +
                "通知监听未开启返回 NO_NOTIFICATION_ACCESS（与 NO_SESSION 不同）。发送前检查会话的 actions 位，" +
                "缺对应位返回 ACTION_UNSUPPORTED，不会把命令发出去。发送后最多等 1.5 秒看 PlaybackState：" +
                "状态没变返回 NO_STATE_CHANGE，会话消失返回 SESSION_GONE；只有拿到证据才算成功。" +
                "seek 必须给非负整数 position_ms（毫秒）。不支持搜索/playFromSearch、不支持 per-session 音量、" +
                "不跨回合缓存会话。需要控制已有媒体播放时用它；要打开 App 或页面用 open。",
            parametersJson = """{"type":"object","properties":{""" +
                """"action":{"type":"string","enum":["list","play","pause","toggle","next","previous","stop","seek"],""" +
                """"description":"list 只读列出；其余为播放控制"},""" +
                """"package":{"type":"string","description":"目标媒体 App 的包名；多个会话时必须指定"},""" +
                """"position_ms":{"type":"integer","description":"seek 的目标位置，毫秒，非负；其它 action 忽略"}},""" +
                """"required":["action"],"additionalProperties":false}""",
            risk = RiskLevel.L1,
            requiresConfirmation = false,
            idempotent = false,
            reversible = false,
            scope = "device",
            auditTargetArgument = "action",
            screenObservation = false,
        )
    }
}
