package edith.phone

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import edith.phone.actions.SHELL_REPLY_INVALID
import edith.phone.actions.SHELL_REPLY_TOO_LARGE

/**
 * R-9 device instrument: run **one** command through the production privileged shell and log a reply's *shape*.
 *
 * Why it exists: a JVM test proves the reply cap's arithmetic against a fake `IRemoteShell`, and nothing proved that a
 * real >8 MiB reply through the real Shizuku `UserService` binder engages the cap and **returns within a bounded time**
 * instead of deadlocking. `logcat -d` on this device is ~38 MB, which is the command that reaches the cap for real.
 *
 * It reaches the **production** `ShizukuUserServiceShell` through
 * `HostCompositionRoot.privilegedShellForProbe` (debug-only, see that field's KDoc) rather than constructing one: a
 * second instance would open a second Shizuku user-service binding and disturb the app's real channel.
 *
 * ```
 * adb shell am broadcast -a edith.DEBUG_SHELLCAP -n edith.phone/.DebugShellCapProbe \
 *     --es label control-brightness --es command 'settings get system screen_brightness'
 * adb shell am broadcast -a edith.DEBUG_SHELLCAP -n edith.phone/.DebugShellCapProbe \
 *     --es label logcat-d --es command 'logcat -d'
 * ```
 *
 * **What it logs, and what it must never log.** One line per phase: the channel's own readiness, then `start`, then
 * `end` carrying the fixed [EXTRA_LABEL] identity, the exit code, `stdout`/`stderr` **byte counts**, the `truncated`
 * flag, the channel's own failure code when `stderr` is one of its markers, and the elapsed milliseconds. The command
 * itself and the reply's text are **never** printed: a reply may carry logcat lines, i.e. anything at all, and this is
 * the same rule the other debug instruments follow. The label is sanitised to `[A-Za-z0-9_-]` and bounded, so it cannot
 * become a channel for text either.
 *
 * **"Did it return at all"** is read off the two lines: `start` is written *before* the binder call and `end` only after
 * it returned, so a `start` with no `end` is a call that never came back - the deadlock this instrument exists to look
 * for. The probe itself cannot log its own hang.
 *
 * It lives in the **debug source set only** (see `src/debug/AndroidManifest.xml`) and is `exported` because the only
 * caller is `am broadcast` as the shell UID; an unexported receiver cannot be reached that way. A release APK contains
 * no receiver.
 */
class DebugShellCapProbe : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        // `goAsync`, because the worker outlives `onReceive` by however long the command takes - and an unbounded call
        // time is exactly the case this probe measures. Nothing below may run on the broadcast's main thread.
        val pending = goAsync()
        val label = ident(intent.getStringExtra(EXTRA_LABEL))
        val command = intent.getStringExtra(EXTRA_COMMAND) ?: ""
        Thread({
            try {
                measure(context.applicationContext, label, command)
            } catch (unexpected: Throwable) {
                // A refusal is a result: it is logged as a code rather than thrown into the receiver, where it would
                // only become a process death.
                report("P3_SHELLCAP state=UNEXPECTED label=$label throw=${unexpected.javaClass.simpleName}")
            } finally {
                runCatching { pending.finish() }
            }
        }, THREAD_NAME).apply { isDaemon = true }.start()
    }

    private fun measure(app: Context, label: String, command: String) {
        val root = (app as? EdithApplication)?.composition
            ?: return report("P3_SHELLCAP state=NO_COMPOSITION label=$label")
        val shell = root.privilegedShellForProbe
            ?: return report("P3_SHELLCAP state=NO_SHELL label=$label")
        // Read the channel's own answer **before** using it: without the Shizuku grant the user service is not bound,
        // and a timing taken from an unbound channel would say nothing about the cap.
        val ready = shell.ready()
        report("P3_SHELLCAP state=CHANNEL label=$label ready=$ready")
        if (!ready) return
        if (command.isBlank()) return report("P3_SHELLCAP state=NO_COMMAND label=$label")

        report("P3_SHELLCAP start label=$label")
        val startedAt = System.nanoTime()
        val outcome = runCatching { shell.run(command) }
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L
        val reply = outcome.getOrNull()
        if (reply == null) {
            return report(
                "P3_SHELLCAP end label=$label returned=true threw=true " +
                    "throw=${outcome.exceptionOrNull()?.javaClass?.simpleName ?: "none"} elapsedMs=$elapsedMs",
            )
        }
        report(
            "P3_SHELLCAP end label=$label returned=true exit=${reply.exitCode} " +
                "stdoutBytes=${utf8Bytes(reply.stdout)} stderrBytes=${utf8Bytes(reply.stderr)} " +
                "truncated=${reply.truncated} code=${codeOf(reply.stderr)} elapsedMs=$elapsedMs",
        )
    }

    private fun report(line: String) = P3Log.line(line)

    /**
     * The channel's own failure code when `stderr` is one of its markers, and `none` otherwise.
     *
     * Without this the two distinct facts R-9 exists to keep apart (a reply too large to carry vs one this build could
     * not parse) would look identical from the outside: both are exit `-1` with an empty `stdout`. Device `stderr` is
     * never printed - only this code, which is a constant, not a payload.
     */
    private fun codeOf(stderr: String): String = when (stderr) {
        SHELL_REPLY_TOO_LARGE -> "REPLY_TOO_LARGE"
        SHELL_REPLY_INVALID -> "REPLY_INVALID"
        // The third marker `ShizukuUserServiceShell.run` returns, repeated here as the same literal so the probe can
        // name it; it is never reached through this probe's own `ready()` gate, so it can only be observed if the
        // binding died between that gate and the call.
        SHELL_NOT_BOUND -> "NOT_BOUND"
        else -> "none"
    }

    /** The reply's size in bytes, which is what the cap counts - never a character count, which UTF-8 changes. */
    private fun utf8Bytes(text: String): Int = text.toByteArray(Charsets.UTF_8).size

    /**
     * The label as a log-safe identity: `[A-Za-z0-9_-]`, bounded, `none` when absent.
     *
     * The label is the *identity* of a command the operator chose from a fixed runbook, and it is the only one of the
     * two extras that reaches logcat. Sanitising it keeps this instrument incapable of logging arbitrary text even if a
     * caller passes something else.
     */
    private fun ident(raw: String?): String = raw
        ?.filter { it.isLetterOrDigit() || it == '-' || it == '_' }
        ?.take(MAX_LABEL_CHARS)
        ?.ifEmpty { NONE }
        ?: NONE

    companion object {
        /** The explicit action, so the instrument is opt-in and greppable. */
        const val ACTION = "edith.DEBUG_SHELLCAP"

        /** The command to run. **Never logged** - only its [EXTRA_LABEL] identity is. */
        const val EXTRA_COMMAND = "command"

        /** The fixed identity of that command (`--es label logcat-d`); the only extra that reaches logcat. */
        const val EXTRA_LABEL = "label"

        private const val THREAD_NAME = "edith-shellcap-probe"
        private const val MAX_LABEL_CHARS = 32
        private const val NONE = "none"

        /** `ShizukuUserServiceShell.run`'s own marker for "the remote is not bound"; see [codeOf]. */
        private const val SHELL_NOT_BOUND = "SHELL_NOT_BOUND"
    }
}
