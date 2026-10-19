package com.sheen.rokid.phone.actions.device

import com.sheen.rokid.device.ShellOutcome
import com.sheen.rokid.device.ShellPort
import com.sheen.rokid.phone.actions.shizuku.PrivilegedShell
import com.sheen.rokid.phone.actions.shizuku.ShellFailure
import com.sheen.rokid.phone.actions.shizuku.ShellResult
import com.sheen.rokid.plugin.ActionDescriptor
import com.sheen.rokid.plugin.ActionExecutor
import com.sheen.rokid.plugin.ActionOutcome
import com.sheen.rokid.plugin.ActionResult
import com.sheen.rokid.plugin.RiskLevel
import kotlinx.serialization.json.JsonNull
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
 * a semantic argument and the shell's own facts (`SHELL_NOT_BOUND`, `SHELL_REPLY_TOO_LARGE`,
 * `SHELL_FAILED`) survive into the envelope; the tool never invents `ACTION_TIMEOUT`. Command
 * classification and confirmation are Task 11 and are deliberately absent here.
 *
 * Outcome mapping: a completed zero-exit command is `Done`; a completed non-zero exit is `Failed`
 * (it ran and failed, with its exit code and output kept); a channel failure is `Refused` (the
 * command could not run at all).
 */
class ShellTool(private val shell: PrivilegedShell?) : ActionExecutor {

    override fun descriptors(): List<ActionDescriptor> = listOf(DESCRIPTOR)

    override fun cancel() = Unit

    /** The tool's own call shape (one argument object); [ActionExecutor.execute] adds the name. */
    fun execute(argsJson: String): ActionResult = execute(NAME, argsJson)

    override fun execute(name: String, argsJson: String): ActionResult {
        if (name != NAME) return ActionResult(ActionOutcome.Refused, UNKNOWN_ACTION)
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
                toolEnvelope(
                    tool = NAME,
                    ok = false,
                    channel = CHANNEL,
                    accepted = false,
                    error = ToolFailure(
                        UNSUPPORTED,
                        "stdin is not supported by the privileged shell in this version",
                        recoverable = false,
                    ),
                ),
            )
        }
        val target = shell ?: return channelFailure(SHELL_NOT_BOUND)
        val result = try {
            target.run(command)
        } catch (t: Throwable) {
            return channelFailure(SHELL_FAILED)
        }
        val (stdout, stdoutCut) = truncateUtf8(result.stdout, maxBytes.toInt())
        val (stderr, stderrCut) = truncateUtf8(result.stderr, maxBytes.toInt())
        val truncated = result.truncated || stdoutCut || stderrCut
        val failureCode = result.failure?.let { failure ->
            when (failure) {
                ShellFailure.NOT_BOUND -> SHELL_NOT_BOUND
                ShellFailure.CARRIER -> SHELL_REPLY_TOO_LARGE
                ShellFailure.PARSE -> SHELL_FAILED
            }
        }
        val ok = failureCode == null && result.exitCode == 0
        val outcome = when {
            ok -> ActionOutcome.Done
            failureCode != null -> ActionOutcome.Refused
            else -> ActionOutcome.Failed
        }
        val detail = buildJsonObject {
            put("ok", ok)
            put("exitCode", result.exitCode)
            put("stdout", stdout)
            put("stderr", stderr)
            put("truncated", truncated)
            put("channel", CHANNEL)
            if (failureCode == null) {
                put("error", JsonNull)
            } else {
                put(
                    "error",
                    buildJsonObject {
                        put("code", failureCode)
                        put("message", "the privileged shell channel reported $failureCode")
                        put("recoverable", true)
                    },
                )
            }
        }.toString()
        return ActionResult(outcome, detail)
    }

    private fun channelFailure(code: String): ActionResult = ActionResult(
        ActionOutcome.Refused,
        toolEnvelope(
            tool = NAME,
            ok = false,
            channel = CHANNEL,
            accepted = false,
            error = ToolFailure(code, "the privileged shell channel is not usable", recoverable = true),
        ),
    )

    private fun refused(code: String) = ActionResult(ActionOutcome.Refused, code)

    companion object {
        const val NAME = "shell"

        const val DEFAULT_TIMEOUT_MILLIS = 30_000L

        /** Spec §4.6: Shizuku replies are targeted at 64 KiB. */
        const val DEFAULT_MAX_BYTES = 65_536L

        private const val CHANNEL = "shell"
        private const val INVALID_ARGUMENTS = "INVALID_ARGUMENTS"
        private const val UNKNOWN_ACTION = "UNKNOWN_ACTION"
        private const val UNSUPPORTED = "UNSUPPORTED"
        private const val SHELL_NOT_BOUND = "SHELL_NOT_BOUND"
        private const val SHELL_REPLY_TOO_LARGE = "SHELL_REPLY_TOO_LARGE"
        private const val SHELL_FAILED = "SHELL_FAILED"

        val DESCRIPTOR: ActionDescriptor = ActionDescriptor(
            name = NAME,
            title = "终端命令",
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
        )
    }
}

/**
 * The kernel-side adapter from the app's [PrivilegedShell] to the pure-JVM `ShellPort`.
 *
 * The kernel's shell-key path and Task 11's shell classification use this adapter; it keeps the
 * adapter's channel codes (`NOT_BOUND`→`SHELL_NOT_BOUND`, `CARRIER`→`SHELL_REPLY_TOO_LARGE`,
 * `PARSE`→`SHELL_FAILED`) and cuts each stream to [ShellPort.run]'s `maxBytes` with the same
 * UTF-8-boundary rule the `shell` tool uses.
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
        val failure = when (result.failure) {
            ShellFailure.NOT_BOUND -> "SHELL_NOT_BOUND"
            ShellFailure.CARRIER -> "SHELL_REPLY_TOO_LARGE"
            ShellFailure.PARSE -> "SHELL_FAILED"
            null -> null
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
