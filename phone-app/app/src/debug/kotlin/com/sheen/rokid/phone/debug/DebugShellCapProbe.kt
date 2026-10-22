package com.sheen.rokid.phone.debug
import com.sheen.rokid.phone.R
import com.sheen.rokid.phone.platform.P3Log
import com.sheen.rokid.phone.actions.shizuku.SHELL_REPLY_TOO_LARGE
import com.sheen.rokid.phone.actions.shizuku.SHELL_REPLY_INVALID
import com.sheen.rokid.phone.actions.shizuku.SHELL_NOT_BOUND
import com.sheen.rokid.phone.actions.shizuku.ShizukuUserServiceShell
import com.sheen.rokid.phone.app.EdithApplication
import com.sheen.rokid.phone.app.HostCompositionRoot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.sheen.rokid.phone.actions.shizuku.SHELL_NOT_BOUND
import com.sheen.rokid.phone.actions.shizuku.SHELL_REPLY_INVALID
import com.sheen.rokid.phone.actions.shizuku.SHELL_REPLY_TOO_LARGE
import com.sheen.rokid.phone.actions.shizuku.ShellFailure
import com.sheen.rokid.phone.actions.shizuku.ShellResult

/**
 * R-9 device instrument: run **one** command through the production privileged shell and log a reply's *shape*.
 *
 * Why it exists: R-9's claim is about what happens when a reply **cannot cross the binder**, which is only observable
 * on a device. It was first built to look for the 8 MiB cap and a deadlock; the measurement showed neither - the
 * channel fails somewhere between 250 000 and 350 000 B, far below any cap - and that the failure was reported as
 * `SHELL_REPLY_INVALID`. The failure kind and the throwable's **class name** were added here to find out *which*
 * exception the binder actually raises, because the production code was catching `TransactionTooLargeException` and
 * evidently never saw it.
 *
 * It reaches the **production** `ShizukuUserServiceShell` through
 * `HostCompositionRoot.privilegedShellForProbe` (debug-only, see that field's KDoc) rather than constructing one: a
 * second instance would open a second Shizuku user-service binding and disturb the app's real channel.
 *
 * ```
 * adb shell am broadcast -a edith.DEBUG_SHELLCAP -n com.sheen.rokid.phone/.DebugShellCapProbe \
 *     --es label control-brightness --es command 'settings get system screen_brightness'
 * adb shell am broadcast -a edith.DEBUG_SHELLCAP -n com.sheen.rokid.phone/.DebugShellCapProbe \
 *     --es label bytes-400k --es command 'yes a | head -c 400000'
 * ```
 *
 * **What it logs, and what it must never log.** One line per phase: the channel's own readiness, then `start`, then
 * `end` carrying the fixed [EXTRA_LABEL] identity, the exit code, `stdout`/`stderr` **byte counts**, the `truncated`
 * flag, the channel's own failure code (`code=`), the production branch that produced it (`arm=`), the raising
 * exception's **class name** when the failure is a carrier one (`throwable=`), the branch of `run` that answered this
 * call (`answer=` - `direct`/`rebound`/`failed`/`rate_limited`/`not_usable`, R-9 follow-up item #2), whether a rebind
 * is what answered it (`relinked=`), and the elapsed milliseconds. The command itself and the reply's text are
 * **never** printed: a reply may carry logcat lines, i.e. anything at all, and
 * this is the same rule the other debug instruments follow. The label is sanitised to `[A-Za-z0-9_-]` and bounded, so
 * it cannot become a channel for text either, and the throwable field is a class name - never a message, which could
 * quote a command. `answer=` and `relinked=` are bounded enum-derived words and a boolean, so they cannot carry text.
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
        // `shell.run` promises not to throw, so this line's `arm` says which of its own branches answered: `none` for a
        // reply that crossed the channel, otherwise the failure kind the production code assigned. `code` stays the
        // greppable marker. The throwable is the **class name only** - the R-9 investigation needed it because the code
        // was named after an exception the device turned out not to throw, and a class name is bounded and carries no
        // payload (never a message, a command or a reply body).
        val (code, arm) = classify(reply)
        report(
            "P3_SHELLCAP end label=$label returned=true exit=${reply.exitCode} " +
                "stdoutBytes=${utf8Bytes(reply.stdout)} stderrBytes=${utf8Bytes(reply.stderr)} " +
                "truncated=${reply.truncated} code=$code arm=$arm " +
                "throwable=${if (reply.failure == ShellFailure.CARRIER) shell.lastCarrierThrowable ?: NONE else NONE} " +
                "answer=${shell.lastAnswerPath} relinked=${reply.relinked} elapsedMs=$elapsedMs",
        )
    }

    private fun report(line: String) = P3Log.line(line)

    /**
     * The channel's own failure code and which arm of `ShizukuUserServiceShell.run` produced it.
     *
     * `arm` is read off the production result's failure kind, not guessed from the strings; the code is derived from the
     * same kind with the marker as a fallback, so a result built without a kind still names itself. Device `stderr` is
     * never printed - only this code, which is a constant, not a payload.
     */
    private fun classify(reply: ShellResult): Pair<String, String> = when (reply.failure) {
        ShellFailure.CARRIER -> SHELL_REPLY_TOO_LARGE to "carrier"
        ShellFailure.PARSE -> SHELL_REPLY_INVALID to "parse"
        ShellFailure.NOT_BOUND -> SHELL_NOT_BOUND to "not_bound"
        null -> when (reply.stderr) {
            SHELL_REPLY_TOO_LARGE -> SHELL_REPLY_TOO_LARGE to "carrier"
            SHELL_REPLY_INVALID -> SHELL_REPLY_INVALID to "parse"
            SHELL_NOT_BOUND -> SHELL_NOT_BOUND to "not_bound"
            else -> NONE to NONE
        }
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
    }
}
