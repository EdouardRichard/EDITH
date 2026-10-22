package com.sheen.rokid.phone.actions.device

import com.sheen.rokid.device.ShellCommandClassifier
import com.sheen.rokid.device.ShellOutcome
import com.sheen.rokid.device.ShellPort
import com.sheen.rokid.phone.actions.shizuku.PrivilegedShell
import com.sheen.rokid.phone.actions.shizuku.ShellFailure
import com.sheen.rokid.phone.actions.shizuku.ShellResult
import com.sheen.rokid.plugin.ActionDescriptor
import com.sheen.rokid.plugin.ActionExecutor
import com.sheen.rokid.plugin.ActionOutcome
import com.sheen.rokid.plugin.ActionPrecheck
import com.sheen.rokid.plugin.ActionResult
import com.sheen.rokid.plugin.PrecheckDecision
import com.sheen.rokid.plugin.RiskLevel
import com.sheen.rokid.plugin.ToolExecution
import com.sheen.rokid.plugin.ToolOutcome
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * R45: the raw-command tool.
 *
 * The schema is `command` (required), `timeout_ms` (default 30 s), `max_bytes` (default 64 KiB) and
 * `stdin`. The tool owns the output budget: each stream is cut to at most `max_bytes` of **UTF-8
 * bytes** on a character boundary (a Chinese character is never split), and `truncated=true` says
 * so. `stdin` is unsupported in this version — a non-empty value is refused with `UNSUPPORTED`
 * rather than silently ignored.
 *
 * **The timeout is not faked.** [PrivilegedShell] has no timeout API, so `timeout_ms` is carried as
 * a semantic argument and the shell's own facts survive into the envelope (R65): a reply the carrier
 * cut short is `SHELL_REPLY_TOO_LARGE`, a carrier failure that truncated nothing is `SHELL_FAILED`,
 * an unparseable reply is `SHELL_FAILED`, and an unreachable channel is `SHELL_NOT_BOUND`; the tool
 * never invents `ACTION_TIMEOUT`. Command classification and confirmation are Task 11 and are
 * deliberately absent here.
 *
 * Outcome mapping: a completed zero-exit command is `Done`; a completed non-zero exit is `Failed`
 * (it ran and failed, with its exit code and output kept); a channel failure is `Refused` (the
 * command could not run at all). A throw from the shell **after** the command was handed over is
 * `Failed` in the legacy projection and `Uncertain` in [ToolExecution] (M1): the call may have run
 * and applied, which a pre-dispatch refusal would misstate.
 */
class ShellTool(private val shell: PrivilegedShell?) : ActionExecutor {

    override fun descriptors(): List<ActionDescriptor> = listOf(DESCRIPTOR)

    override fun cancel() = Unit

    /** The tool's own call shape (one argument object); [ActionExecutor.execute] adds the name. */
    fun execute(argsJson: String): ActionResult = execute(NAME, argsJson)

    /**
     * 0.25 Task 11 (R49/R50): classifies **this call** before anything reaches the shell.
     *
     * The classifier is a pure table in `device-control`; this method only extracts the `command` member and hands it
     * over. A missing/mistyped `command` is [PrecheckDecision.Reject] (there is no shell call to make), while an
     * unknown but well-formed command is `RequireConfirmation` - the classifier decides that, never this tool.
     */
    fun classify(argsJson: String): ActionPrecheck {
        val command = parseObject(argsJson)?.stringArg("command")
            ?: return ActionPrecheck(PrecheckDecision.Reject, reason = INVALID_ARGUMENTS)
        return ShellCommandClassifier.classify(command)
    }

    /** Only `shell` is command-classified; any other name is not this executor's call shape. */
    override fun precheck(name: String, argsJson: String): ActionPrecheck =
        if (name == NAME) classify(argsJson) else ActionPrecheck(PrecheckDecision.Allow)

    override fun execute(name: String, argsJson: String): ActionResult {
        if (name != NAME) {
            return ActionResult(
                ActionOutcome.Refused,
                // Task 8: the stable code is `execution.code`, not a copy of the whole result in `data`.
                "{}",
                // Fix review: an unknown action is not a `shell` call at all, so it cannot declare a state change.
                stateChanging = false,
                execution = refusedExecution(UNKNOWN_ACTION, recoverable = false),
            )
        }
        val args = parseObject(argsJson) ?: return refused(INVALID_ARGUMENTS)
        val command = args.stringArg("command")?.takeIf { it.isNotBlank() } ?: return refused(INVALID_ARGUMENTS)
        val timeout = args.longArg("timeout_ms") ?: DEFAULT_TIMEOUT_MILLIS
        if (timeout <= 0L) return refused(INVALID_ARGUMENTS)
        val maxBytes = args.longArg("max_bytes") ?: DEFAULT_MAX_BYTES
        if (maxBytes <= 0L || maxBytes > Int.MAX_VALUE) return refused(INVALID_ARGUMENTS)
        val stdin = args.stringArg("stdin")
        if (!stdin.isNullOrEmpty()) {
            return ActionResult(
                ActionOutcome.Refused,
                "{}",
                stateChanging = true,
                execution = refusedExecution(UNSUPPORTED, recoverable = false),
            )
        }
        val target = shell ?: return channelFailure(SHELL_NOT_BOUND)
        val result = try {
            target.run(command)
        } catch (t: Throwable) {
            // edith-0.3 Phase 3 M1: the command already crossed into the shell, so a throw is **not** a pre-dispatch
            // refusal. The legacy three-state projection cannot express uncertainty; the structured fact is
            // `Uncertain` with accepted=true and every tri-state field unknown - the command may have applied.
            return ActionResult(
                outcome = ActionOutcome.Failed,
                detail = "{}",
                stateChanging = true,
                execution = ToolExecution(
                    outcome = ToolOutcome.Uncertain,
                    code = SHELL_FAILED,
                    message = SHELL_DISPATCH_UNCERTAIN,
                    recoverable = true,
                    accepted = true,
                    applied = null,
                    verified = null,
                    changed = null,
                    uncertain = true,
                ),
            )
        }
        val (stdout, stdoutCut) = truncateUtf8(result.stdout, maxBytes.toInt())
        val (stderr, stderrCut) = truncateUtf8(result.stderr, maxBytes.toInt())
        val truncated = result.truncated || stdoutCut || stderrCut
        val failureCode = when {
            // R65: only a reply the carrier actually cut is the too-large answer. A carrier failure
            // that truncated nothing means the call did not cross, which is a plain shell failure.
            result.truncated -> SHELL_REPLY_TOO_LARGE
            result.failure == ShellFailure.NOT_BOUND -> SHELL_NOT_BOUND
            result.failure == ShellFailure.CARRIER -> SHELL_FAILED
            result.failure == ShellFailure.PARSE -> SHELL_FAILED
            else -> null
        }
        val ok = failureCode == null && result.exitCode == 0
        val outcome = when {
            ok -> ActionOutcome.Done
            failureCode != null -> ActionOutcome.Refused
            else -> ActionOutcome.Failed
        }
        // Task 8: the tool-specific data is the command's answer only. `ok`/`channel` are the envelope's execution
        // facts now, and `error` moved to `execution.code`/`message`.
        val detail = buildJsonObject {
            put("exitCode", result.exitCode)
            put("stdout", stdout)
            put("stderr", stderr)
            put("truncated", truncated)
        }.toString()
        return ActionResult(outcome, detail, stateChanging = true, execution = executionFacts(outcome, failureCode))
    }

    private fun channelFailure(code: String): ActionResult = ActionResult(
        ActionOutcome.Refused,
        "{}",
        stateChanging = true,
        execution = refusedExecution(code, recoverable = true),
    )

    // R98: every `shell` call is a write, whatever its outcome — the declaration describes the call class, not
    // whether the command got to run.
    private fun refused(code: String): ActionResult = ActionResult(
        ActionOutcome.Refused,
        "{}",
        stateChanging = true,
        execution = refusedExecution(code, recoverable = false),
    )

    /**
     * edith-0.3 Phase 2.2 Task 8: the structured facts of one shell call, now stated **by the tool** instead of parsed
     * back out of its `detail` by `ToolCatalog`.
     *
     * A completed shell call is `accepted=true` with `applied=null` whether it exited 0 or not: the command ran, but an
     * exit code is not a target-state answer (`PrivilegedShell` documents a false `OK` and a false `BLOCKED` measured on
     * the K40). A reply the carrier cut proves the command ran (only the answer was lost), so it stays accepted with
     * `applied=null`; every other refusal never dispatched the command and keeps `accepted=false`/`applied=false`.
     */
    private fun executionFacts(outcome: ActionOutcome, code: String?): ToolExecution = when (outcome) {
        ActionOutcome.Done -> ToolExecution(
            outcome = ToolOutcome.Done,
            accepted = true,
            applied = null,
        )
        ActionOutcome.Failed -> ToolExecution(
            outcome = ToolOutcome.Failed,
            code = code,
            message = messageFor(code),
            recoverable = true,
            accepted = true,
            applied = null,
        )
        ActionOutcome.Refused -> {
            val executedAnyway = code == SHELL_REPLY_TOO_LARGE
            ToolExecution(
                outcome = ToolOutcome.Refused,
                code = code,
                // Fix review: the channel reasons are useful to the model; the dropped plumbing message left only a code.
                message = messageFor(code),
                recoverable = true,
                accepted = executedAnyway,
                applied = if (executedAnyway) null else false,
            )
        }
    }

    private fun refusedExecution(code: String, recoverable: Boolean): ToolExecution = ToolExecution(
        outcome = ToolOutcome.Refused,
        code = code,
        message = messageFor(code),
        recoverable = recoverable,
        accepted = false,
        applied = false,
    )

    /** Stable, content-free reasons for the shell refusals (the command and its output never appear here). */
    private fun messageFor(code: String?): String? = when (code) {
        UNSUPPORTED -> "stdin is not supported"
        SHELL_NOT_BOUND -> "the privileged shell is not bound"
        SHELL_FAILED -> "the privileged shell call failed"
        INVALID_ARGUMENTS -> "the shell arguments are invalid"
        UNKNOWN_ACTION -> "shell is not this tool's action"
        else -> null
    }

    companion object {
        const val NAME = "shell"

        const val DEFAULT_TIMEOUT_MILLIS = 30_000L

        /** Spec §4.6: Shizuku replies are targeted at 64 KiB. */
        const val DEFAULT_MAX_BYTES = 65_536L

        private const val INVALID_ARGUMENTS = "INVALID_ARGUMENTS"
        private const val UNKNOWN_ACTION = "UNKNOWN_ACTION"
        private const val UNSUPPORTED = "UNSUPPORTED"
        private const val SHELL_NOT_BOUND = "SHELL_NOT_BOUND"
        private const val SHELL_REPLY_TOO_LARGE = "SHELL_REPLY_TOO_LARGE"
        private const val SHELL_FAILED = "SHELL_FAILED"

        /** M1: the stable, content-free reason for a throw that happened after the command was dispatched. */
        private const val SHELL_DISPATCH_UNCERTAIN = "the shell call may have been dispatched"

        val DESCRIPTOR: ActionDescriptor = ActionDescriptor(
            name = NAME,
            title = "终端命令",
            description = "在特权 shell（Shizuku）执行一条命令：command 必填；stdin 本版不支持（返回 UNSUPPORTED），每个输出流按 max_bytes 截断并标 truncated；timeout_ms 只是语义参数，本版不会中断已发出的命令。只在没有专用工具能完成时使用：点击/输入/滑动用 device 或对应的 atomic 工具，按键用 system_action，启动应用用 open，不要用 shell 模拟用户操作。可能改变系统状态的命令（pm clear/uninstall、settings put、rm、reboot、su 等）会先走命令级确认。退出码非 0 是 Failed 且 data 带 exitCode/stdout/stderr；回包被截断返回 SHELL_REPLY_TOO_LARGE，载体或解析失败返回 SHELL_FAILED，通道未就绪返回 SHELL_NOT_BOUND。不要用它处理用户隐私内容，也不要拼接未转义输入。",
            parametersJson = """{"type":"object","properties":{""" +
                """"command":{"type":"string"},"timeout_ms":{"type":"integer","minimum":1},""" +
                """"max_bytes":{"type":"integer","minimum":1},"stdin":{"type":"string"}},""" +
                """"required":["command"],"additionalProperties":false}""",
            risk = RiskLevel.L1,
            requiresConfirmation = false,
            idempotent = false,
            reversible = false,
            scope = "device.shell",
            // A raw command may contain user data, so no part of it is ever written to the audit trail.
            auditTargetArgument = null,
            // R99: the plugin's confirming wrapper asks the wearer inside `execute` (D47's 120 s window). The loop must
            // add its human allowance to the machine cap instead of clamping the whole call by the turn deadline.
            mayAskHuman = true,
            // edith-0.3 Phase 2.2 Task 6: a command can leave any screen behind, and `ToolCatalog` attaches the shared
            // post-call observation. This is the screen-observation declaration, not the fingerprint seam (`observation`
            // stays false), so `shell` does not start feeding the progress detectors.
            screenObservation = true,
        )
    }
}

/**
 * The kernel-side adapter from the app's [PrivilegedShell] to the pure-JVM `ShellPort`.
 *
 * The kernel's shell-key path and Task 11's shell classification use this adapter; it keeps the
 * R65 channel codes (`NOT_BOUND`→`SHELL_NOT_BOUND`, `CARRIER` without truncation→`SHELL_FAILED`,
 * `PARSE`→`SHELL_FAILED`, a truncated reply→`SHELL_REPLY_TOO_LARGE`) and cuts each stream to
 * [ShellPort.run]'s `maxBytes` with the same UTF-8-boundary rule the `shell` tool uses.
 */
class PrivilegedShellPort(private val shell: PrivilegedShell) : ShellPort {

    override fun run(command: String, timeoutMillis: Long, maxBytes: Int): ShellOutcome {
        val result = try {
            shell.run(command)
        } catch (t: Throwable) {
            return ShellOutcome(
                exitCode = -1,
                stdout = "",
                stderr = SHELL_FAILED,
                truncated = false,
                failure = SHELL_FAILED,
            )
        }
        val failure = when {
            result.truncated -> "SHELL_REPLY_TOO_LARGE"
            result.failure == ShellFailure.NOT_BOUND -> "SHELL_NOT_BOUND"
            result.failure == ShellFailure.CARRIER -> "SHELL_FAILED"
            result.failure == ShellFailure.PARSE -> "SHELL_FAILED"
            else -> null
        }
        val (stdout, stdoutCut) = truncateUtf8(result.stdout, maxBytes)
        val (stderr, stderrCut) = truncateUtf8(result.stderr, maxBytes)
        return ShellOutcome(
            exitCode = result.exitCode,
            stdout = stdout,
            stderr = stderr,
            truncated = result.truncated || stdoutCut || stderrCut,
            failure = failure,
        )
    }

    private companion object {
        const val SHELL_NOT_BOUND = "SHELL_NOT_BOUND"
        const val SHELL_REPLY_TOO_LARGE = "SHELL_REPLY_TOO_LARGE"
        const val SHELL_FAILED = "SHELL_FAILED"
    }
}

/**
 * Cuts [text] to at most [maxBytes] UTF-8 bytes without splitting a multibyte character.
 *
 * The returned string is a whole-character prefix; comparing the byte length is therefore safe.
 * `multibyte=false` means nothing was cut.
 */
internal fun truncateUtf8(text: String, maxBytes: Int): Pair<String, Boolean> {
    if (maxBytes < 0) return "" to text.isNotEmpty()
    val bytes = text.toByteArray(Charsets.UTF_8)
    if (bytes.size <= maxBytes) return text to false
    var end = maxBytes
    // A continuation byte (10xxxxxx) at the cut point means the prefix would end mid-character;
    // back off to the leading byte of that character, which becomes the first excluded byte.
    while (end > 0 && (bytes[end].toInt() and CONTINUATION_MASK) == CONTINUATION_BYTE) {
        end--
    }
    return String(bytes, 0, end, Charsets.UTF_8) to true
}

private const val CONTINUATION_MASK = 0xC0
private const val CONTINUATION_BYTE = 0x80
