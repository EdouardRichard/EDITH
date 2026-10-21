package com.sheen.rokid.phone.actions.intent
import kotlinx.serialization.json.JsonObject
import com.sheen.rokid.phone.R
import com.sheen.rokid.phone.actions.shizuku.isCallComplete
import com.sheen.rokid.phone.actions.shizuku.PrivilegedShell
import com.sheen.rokid.phone.actions.ExecutionBackend
import com.sheen.rokid.plugin.ActionDescriptor
import com.sheen.rokid.plugin.ActionOutcome
import com.sheen.rokid.plugin.ActionResult
import com.sheen.rokid.plugin.RiskLevel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

private const val SYSTEM_ACTION = "system_action"

private const val ARGUMENTS_INVALID = "ARGUMENTS_INVALID"
private const val UNKNOWN_ACTION = "UNKNOWN_ACTION"
private const val COMMAND_FAILED = "COMMAND_FAILED"
private const val ACTION_CHANNEL_UNAVAILABLE = "ACTION_CHANNEL_UNAVAILABLE"

private val argumentsJson = Json { isLenient = true }

/**
 * Maps each supported system action name to the shell command that produces it.
 *
 * The map is the single source of truth for both dispatch and validation: an action not in this map is refused as
 * [UNKNOWN_ACTION] without ever reaching the shell, so a typo in the model's output cannot accidentally run a
 * different command.
 */
private val SYSTEM_COMMANDS: Map<String, String> = mapOf(
    "back" to "input keyevent KEYCODE_BACK",
    "home" to "input keyevent KEYCODE_HOME",
    "recents" to "input keyevent KEYCODE_APP_SWITCH",
    "notifications" to "cmd statusbar expand-notifications",
)

/**
 * The Shizuku-backed system key action tool (T2.4).
 *
 * A thin dispatcher: each supported action maps to exactly one shell command, and the executor adds nothing beyond
 * argument validation, channel gating, and outcome translation. The four actions are L1 because they affect device
 * state (the current foreground activity, the notification shade) but are reversible by the user at any time and
 * carry no personal data — `auditTargetArgument` records which key was pressed, never any content.
 *
 * Exit code 0 is the success criterion here rather than a state re-read: there is no meaningful state to verify after
 * pressing Back or Home (the foreground activity changes, but reading it back would race with the animation and add a
 * dependency on `dumpsys` parsing that buys no safety). The P0 matrix's false-positive concern applies to writes that
 * claim success without changing anything; a key event either fires or does not, and the OS reports that directly.
 */
class SystemActionExecutor(
    private val shell: PrivilegedShell,
    private val channelUp: () -> Boolean,
) : ExecutionBackend {

    override val id = "shizuku_system"

    override fun available(): Boolean = runCatching { channelUp() }.getOrDefault(false)

    override fun descriptors(): List<ActionDescriptor> = listOf(
        ActionDescriptor(
            name = SYSTEM_ACTION,
            title = "执行系统级按键动作",
            description = "执行一个系统级按键动作：action 只能是 back、home、recents、notifications 之一（其它值返回 UNKNOWN_ACTION）。用于在任意界面按键、且不需要界面元素引用时；需要按 ref 点击/输入/滑动或验证界面时用 device 的 act，具体界面操作优先用对应专用工具。不要用 shell 的 input keyevent 代替这四个预置动作；只有需要这四个动作之外的按键、或要把按键与其他特权命令组合时才用 shell。命令以非 0 退出码结束返回 COMMAND_FAILED；通道掉线返回 ACTION_CHANNEL_UNAVAILABLE，等通道恢复后重试。",
            parametersJson = """{"type":"object","properties":{"action":{"type":"string","enum":["back","home","recents","notifications"]}},"required":["action"],"additionalProperties":false}""",
            risk = RiskLevel.L1,
            requiresConfirmation = false,
            idempotent = true,
            reversible = false,
            scope = "device",
            auditTargetArgument = "action",
        ),
    )

    override fun execute(name: String, argsJson: String): ActionResult {
        if (name != SYSTEM_ACTION) return refused(UNKNOWN_ACTION)
        if (!available()) return refused(ACTION_CHANNEL_UNAVAILABLE)
        val args = objectArguments(argsJson) ?: return refused(ARGUMENTS_INVALID)
        val action = args["action"]?.stringOrNull()?.takeIf { it.isNotBlank() }
            ?: return refused(ARGUMENTS_INVALID)
        val command = SYSTEM_COMMANDS[action] ?: return refused(UNKNOWN_ACTION)
        val result = shell.run(command)
        // R-9 follow-up #3: a call that never came back is a **channel** failure, not a failed command. Without this the
        // carrier/not-bound/parse replies (`exit -1`, empty stdout) read as `COMMAND_FAILED`, which tells the model the
        // key event itself failed and invites a retry on a route that just proved it cannot carry an answer.
        if (!result.isCallComplete()) return refused(ACTION_CHANNEL_UNAVAILABLE)
        return if (result.exitCode == 0) done("executed $action") else failed(COMMAND_FAILED)
    }

    private fun objectArguments(argsJson: String): JsonObject? =
        runCatching { argumentsJson.parseToJsonElement(argsJson) as? JsonObject }.getOrNull()

    private fun kotlinx.serialization.json.JsonElement.stringOrNull(): String? =
        (this as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    private fun done(detail: String) = ActionResult(ActionOutcome.Done, detail)
    private fun refused(code: String) = ActionResult(ActionOutcome.Refused, code)
    private fun failed(code: String) = ActionResult(ActionOutcome.Failed, code)
}
