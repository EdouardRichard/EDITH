package com.sheen.rokid.phone.actions.shizuku
import kotlinx.serialization.json.JsonObject
import com.sheen.rokid.phone.actions.ExecutionBackend
import com.sheen.rokid.plugin.ActionDescriptor
import com.sheen.rokid.plugin.ActionOutcome
import com.sheen.rokid.plugin.ActionResult
import com.sheen.rokid.plugin.RiskLevel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

private const val CLEAR_DATA = "clear_data"
private const val UNINSTALL = "uninstall"
private const val DISABLE_APP = "disable_app"
private const val SET_PERMISSION = "set_permission"
private const val SET_BRIGHTNESS = "set_brightness"

private const val ARGUMENTS_INVALID = "ARGUMENTS_INVALID"
private const val UNKNOWN_ACTION = "UNKNOWN_ACTION"
private const val STATE_UNCHANGED = "STATE_UNCHANGED"
private const val ACTION_CHANNEL_UNAVAILABLE = "ACTION_CHANNEL_UNAVAILABLE"

private const val PACKAGE = "package"
private const val PERMISSION = "permission"
private const val GRANTED = "granted"
private const val VALUE = "value"

/** The system setting `set_brightness` writes and then reads back. */
private const val BRIGHTNESS_SETTING = "screen_brightness"

/** `settings put` wants 0-255; the schema says so too, and the executor re-checks rather than trusting the model. */
private val BRIGHTNESS_RANGE = 0..255

/**
 * A package name, and only a package name.
 *
 * Every one of these arguments comes from the model and is interpolated into a `pm` command line, so the check is a
 * whitelist rather than an escape: a value with a space, a `;`, a `$`, a quote or a leading dash is refused outright.
 * Escaping would leave the question "did I escape it right" in the path; refusing leaves nothing to get wrong.
 */
private val PACKAGE_NAME = Regex("""[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)+""")

/** A permission name: `android.permission.POST_NOTIFICATIONS`, `android.permission.CAMERA`, … */
private val PERMISSION_NAME = Regex("""[A-Za-z][A-Za-z0-9_.]*""")

private val argumentsJson = Json { isLenient = true }

/**
 * The descriptors this channel declares. Scope is `app` (or `app.permission`) and the audit target is the identifier the
 * action touched, never a payload: spec §17.5's redaction rule lives here, where the meaning of each argument is known.
 */
private val DESCRIPTORS: List<ActionDescriptor> = listOf(
    ActionDescriptor(
        name = CLEAR_DATA,
        title = "清除 App 数据",
        description = "清除指定 package 的全部应用数据（package 必须是完整包名，如 com.example.app，其它形式返回 ARGUMENTS_INVALID）。不可逆，会删除登录态、缓存与本地文件，只在用户明确要求清空某个应用数据时使用。L3 动作：按当前权限策略可能需要确认，也可能被拒绝。命令完成但数据未清空返回 STATE_UNCHANGED；通道掉线返回 ACTION_CHANNEL_UNAVAILABLE，等通道恢复后重试。",
        parametersJson = """{"type":"object","properties":{"package":{"type":"string"}},""" +
            """"required":["package"],"additionalProperties":false}""",
        risk = RiskLevel.L3,
        requiresConfirmation = false,
        idempotent = false,
        reversible = false,
        scope = "app",
        auditTargetArgument = PACKAGE,
    ),
    ActionDescriptor(
        name = UNINSTALL,
        title = "卸载 App",
        description = "卸载指定 package（完整包名，格式不合法返回 ARGUMENTS_INVALID）。不可逆，只在用户明确要求卸载应用时使用；只是停用用 disable_app，清数据用 clear_data。L3 动作：按当前权限策略可能需要确认，也可能被拒绝。卸载未真正生效返回 STATE_UNCHANGED；通道掉线返回 ACTION_CHANNEL_UNAVAILABLE，等通道恢复后重试。",
        parametersJson = """{"type":"object","properties":{"package":{"type":"string"}},""" +
            """"required":["package"],"additionalProperties":false}""",
        risk = RiskLevel.L3,
        requiresConfirmation = false,
        idempotent = false,
        reversible = false,
        scope = "app",
        auditTargetArgument = PACKAGE,
    ),
    ActionDescriptor(
        name = DISABLE_APP,
        title = "停用 App",
        description = "停用指定 package（完整包名，格式不合法返回 ARGUMENTS_INVALID）。可逆但影响大：停用后应用从启动器消失，只在用户明确要求停用时使用，恢复需要用户手动或经 shell 重新启用。L4 动作：按当前权限策略可能需要确认，也可能被拒绝。停用未生效返回 STATE_UNCHANGED；通道掉线返回 ACTION_CHANNEL_UNAVAILABLE，等通道恢复后重试。",
        parametersJson = """{"type":"object","properties":{"package":{"type":"string"}},""" +
            """"required":["package"],"additionalProperties":false}""",
        risk = RiskLevel.L4,
        requiresConfirmation = false,
        idempotent = true,
        reversible = true,
        scope = "app",
        auditTargetArgument = PACKAGE,
    ),
    ActionDescriptor(
        name = SET_PERMISSION,
        title = "变更 App 权限",
        description = "授予或撤销某个 App 的一项运行时权限：package（完整包名）、permission（如 android.permission.CAMERA）、granted（布尔值，true 授予、false 撤销）。只在用户明确要改一项具体权限时使用；引导用户去设置页改权限用 open_settings。L4 动作：按当前权限策略可能需要确认，也可能被拒绝。参数不合法返回 ARGUMENTS_INVALID，权限状态未变化返回 STATE_UNCHANGED，通道掉线返回 ACTION_CHANNEL_UNAVAILABLE，等通道恢复后重试。",
        parametersJson = """{"type":"object","properties":{"package":{"type":"string"},""" +
            """"permission":{"type":"string"},"granted":{"type":"boolean"}},""" +
            """"required":["package","permission","granted"],"additionalProperties":false}""",
        risk = RiskLevel.L4,
        requiresConfirmation = false,
        idempotent = true,
        reversible = true,
        scope = "app.permission",
        auditTargetArgument = PERMISSION,
    ),
    ActionDescriptor(
        name = SET_BRIGHTNESS,
        title = "调整屏幕亮度",
        description = "把系统屏幕亮度设为 value（0-255 的整数，越界或非整数返回 ARGUMENTS_INVALID），写入后回读确认。只在用户明确要求调整亮度时使用，读取当前亮度不要用它。L2 动作：按当前权限策略可能需要确认，也可能被拒绝。写入成功但回读不一致返回 STATE_UNCHANGED；通道掉线返回 ACTION_CHANNEL_UNAVAILABLE，等通道恢复后重试。",
        parametersJson = """{"type":"object","properties":{"value":{"type":"integer","minimum":0,"maximum":255}},""" +
            """"required":["value"],"additionalProperties":false}""",
        risk = RiskLevel.L2,
        requiresConfirmation = false,
        idempotent = true,
        reversible = true,
        scope = "device",
        // A brightness level is not personal data, but it is also not an audit target: the line's outcome already says
        // whether the write landed, so nothing extra is recorded.
        auditTargetArgument = null,
    ),
)

/**
 * The privileged channel: shell-UID commands through Shizuku's `UserService` (spec §17.2/§17.3).
 *
 * Three properties, each one paid for by a P0 measurement:
 * 1. **the state decides, not the exit code.** Every action re-reads [TargetState] afterwards, and a command that
 *    "succeeded" without changing what it claimed to change is the failure `STATE_UNCHANGED`. This is the honest
 *    answer to the `pm grant` false-positive in the T3a matrix;
 * 2. **arguments are refused, not escaped.** See [PACKAGE_NAME];
 * 3. **a channel that is down is refused twice**: [available] gates [execute], so a call that arrives after Shizuku
 *    stopped gets `ACTION_CHANNEL_UNAVAILABLE` instead of a command running as the wrong identity.
 *
 * `requiresConfirmation` is `false` on every descriptor on purpose: L3/L4 are never pre-approved (the plugin's
 * `WhitelistActionPolicy` forces a question for them regardless), and `true` would falsely claim the *executor* knows
 * best. The policy layer owns that decision, per spec §7.2.
 */
class ShizukuExecutionBackend(
    private val shell: PrivilegedShell,
    private val state: TargetState,
    /**
     * The channel probe. Named for the question it answers rather than `available`, so it cannot be confused with (or
     * shadowed by) the [available] override that owns the same word.
     */
    private val channelUp: () -> Boolean,
) : ExecutionBackend {

    override val id = "shizuku"

    override fun available(): Boolean = runCatching { channelUp() }.getOrDefault(false)

    override fun descriptors(): List<ActionDescriptor> = DESCRIPTORS

    override fun execute(name: String, argsJson: String): ActionResult {
        if (!available()) return refused(ACTION_CHANNEL_UNAVAILABLE)
        val args = objectArguments(argsJson) ?: return refused(ARGUMENTS_INVALID)
        return when (name) {
            CLEAR_DATA -> args.packageName()?.let(::clearData) ?: refused(ARGUMENTS_INVALID)
            UNINSTALL -> args.packageName()?.let(::uninstall) ?: refused(ARGUMENTS_INVALID)
            DISABLE_APP -> args.packageName()?.let(::disable) ?: refused(ARGUMENTS_INVALID)
            SET_PERMISSION -> setPermission(args)
            SET_BRIGHTNESS -> setBrightness(args)
            else -> refused(UNKNOWN_ACTION)
        }
    }

    /**
     * Whether a state answer may be trusted **after** the command has run.
     *
     * Two collapses have to be caught, and they are different failures:
     * - [TargetState] answers `false` for "the target is not installed" **and** for "I could not read the package list",
     *   so with the channel down a command that never ran becomes `Done`;
     * - the channel can also be **up** while the reply itself is unparseable (`SHELL_REPLY_INVALID`), which collapses
     *   the same way and which the channel probe cannot see.
     *
     * The second is why every action below reads the state *first* and asks [TargetState.readable] afterwards: the
     * answer describes the read that just happened, so the order is load-bearing, not stylistic.
     */
    private fun stateIsReadable(): Boolean = available() && state.readable()

    private fun clearData(pkg: String): ActionResult {
        val result = shell.run("pm clear $pkg")
        // The read happens **unconditionally**, exactly as in the four actions below: `readable()` answers about the read
        // that just happened, so a short-circuit here (the command failed, so `&&` stops) would leave it answering about
        // some earlier one - and the failure would be reported as `STATE_UNCHANGED` on the first call of a session and
        // as `ACTION_CHANNEL_UNAVAILABLE` on later ones, for no reason the device can see.
        val stillInstalled = state.installed(pkg)
        if (!stateIsReadable()) return refused(ACTION_CHANNEL_UNAVAILABLE)
        // The OS's own marker is the only evidence `pm clear` offers; the package surviving is the second half (a
        // `pm clear` that uninstalled its target would be a very different outcome).
        val cleared = result.exitCode == 0 && result.stdout.contains("Success") && stillInstalled
        return if (cleared) done("cleared $pkg") else failed(STATE_UNCHANGED)
    }

    private fun uninstall(pkg: String): ActionResult {
        shell.run("pm uninstall --user 0 $pkg")
        val installed = state.installed(pkg)
        if (!stateIsReadable()) return refused(ACTION_CHANNEL_UNAVAILABLE)
        return if (!installed) done("uninstalled $pkg") else failed(STATE_UNCHANGED)
    }

    private fun disable(pkg: String): ActionResult {
        shell.run("pm disable-user --user 0 $pkg")
        val disabled = state.disabled(pkg)
        if (!stateIsReadable()) return refused(ACTION_CHANNEL_UNAVAILABLE)
        return if (disabled) done("disabled $pkg") else failed(STATE_UNCHANGED)
    }

    private fun setPermission(args: JsonObject): ActionResult {
        val pkg = args.packageName() ?: return refused(ARGUMENTS_INVALID)
        val permission = args[PERMISSION]?.stringOrNull()?.takeIf { PERMISSION_NAME.matches(it) }
            ?: return refused(ARGUMENTS_INVALID)
        val granted = args[GRANTED]?.let { (it as? JsonPrimitive)?.takeIf { primitive -> !primitive.isString } }
            ?.booleanOrNull ?: return refused(ARGUMENTS_INVALID)
        val verb = if (granted) "grant" else "revoke"
        shell.run("pm $verb $pkg $permission")
        val nowGranted = state.permissionGranted(pkg, permission)
        if (!stateIsReadable()) return refused(ACTION_CHANNEL_UNAVAILABLE)
        return if (nowGranted == granted) done("permission $permission=$granted")
        else failed(STATE_UNCHANGED)
    }

    /** Spec §17.3's L2 privileged action: a reversible system write, judged by the value the device reports back. */
    private fun setBrightness(args: JsonObject): ActionResult {
        val value = (args[VALUE] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
            ?: return refused(ARGUMENTS_INVALID)
        if (value !in BRIGHTNESS_RANGE) return refused(ARGUMENTS_INVALID)
        shell.run("settings put system $BRIGHTNESS_SETTING $value")
        val readBack = state.setting(BRIGHTNESS_SETTING)
        if (!stateIsReadable()) return refused(ACTION_CHANNEL_UNAVAILABLE)
        return if (readBack?.trim() == value.toString()) done("brightness $value")
        else failed(STATE_UNCHANGED)
    }

    private fun JsonObject.packageName(): String? = this[PACKAGE]?.stringOrNull()?.takeIf { PACKAGE_NAME.matches(it) }

    /** A member that must be a JSON string; a number, a null or an object is a wrong type, not a coerced value. */
    private fun JsonElement.stringOrNull(): String? =
        (this as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    private fun objectArguments(argsJson: String): JsonObject? =
        runCatching { argumentsJson.parseToJsonElement(argsJson) as? JsonObject }.getOrNull()

    private fun done(detail: String) = ActionResult(ActionOutcome.Done, detail)
    private fun refused(code: String) = ActionResult(ActionOutcome.Refused, code)
    private fun failed(code: String) = ActionResult(ActionOutcome.Failed, code)
}
