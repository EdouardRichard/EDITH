package edith.phone.actions

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.TransactionTooLargeException
import android.util.Log
import rikka.shizuku.Shizuku

/**
 * The privileged channel over Shizuku's `UserService` (P3.3 Task 8).
 *
 * Why a user service rather than `Shizuku.newProcess`: the API that used to expose a remote process is **private** in
 * 13.1.5 (verified with `javap` on the pinned artifact) and the vendor's README says since 13.1.1 that it is being
 * removed in favour of `UserService`. The binding shape here is the demo's
 * (`D:\Project_new\Shizuku-API\demo`: `Shizuku.bindUserService(args, connection)` with `UserServiceArgs(ComponentName)`,
 * `.daemon(false)`, `.processNameSuffix(...)`, `.debuggable(...)`, `.version(...)`).
 *
 * Three honest answers, never a guess:
 * - not bound yet ⇒ [ready] is false and the caller must not claim the channel works;
 * - the binder died ⇒ `onServiceDisconnected` clears the remote, so [ready] turns false again;
 * - a reply this build cannot parse ⇒ `SHELL_REPLY_INVALID` with exit `-1`, never an empty success;
 * - a reply the channel could not carry ⇒ `SHELL_REPLY_TOO_LARGE` with exit `-1` (R-9): a distinct code, so "too large"
 *   and "cannot be parsed" are two facts rather than one, and neither is read as a success.
 */
class ShizukuUserServiceShell(
    context: Context,
    versionCode: Int,
    debuggable: Boolean,
    /** P3.3: called once the shell process is connected, so the caller can report the channel as up without polling. */
    private val onReady: () -> Unit = {},
) : PrivilegedShell {

    @Volatile
    private var remote: IRemoteShell? = null

    private val args = Shizuku.UserServiceArgs(
        ComponentName(context.packageName, EdithShellService::class.java.name),
    )
        .daemon(false)
        .processNameSuffix("shell")
        .debuggable(debuggable)
        .version(versionCode)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            remote = binder?.takeIf { it.pingBinder() }?.let { IRemoteShell.Stub.asInterface(it) }
            if (remote != null) runCatching { onReady() }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            remote = null
        }
    }

    /** Asks Shizuku to start (or reuse) the user service. Safe to call more than once. */
    fun bind() {
        runCatching { Shizuku.bindUserService(args, connection) }
            .onFailure { Log.i(TAG, "bind failed: ${it.javaClass.simpleName}") }
    }

    /**
     * Asks the user for this app's Shizuku permission when it has not been granted yet.
     *
     * The request is Shizuku's own dialog, so it needs the App to be in the foreground; calling it from the session
     * start (which the user just tapped) is what makes that true. A denial changes nothing here - [ready] simply stays
     * false and the channel stays unadvertised.
     */
    fun requestPermissionIfNeeded() {
        runCatching {
            if (!binderAlive() && Shizuku.pingBinder()) {
                if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                    Shizuku.requestPermission(PERMISSION_REQUEST_CODE)
                }
            }
        }.onFailure { Log.i(TAG, "permission request failed: ${it.javaClass.simpleName}") }
    }

    fun unbind() {
        runCatching { Shizuku.unbindUserService(args, connection, true) }
        remote = null
    }

    /** The channel's own answer to "am I usable right now": binder alive, permission granted, service connected. */
    fun ready(): Boolean = binderAlive() && remote != null

    override fun run(command: String): ShellResult {
        val service = remote ?: return ShellResult(command, -1, "", "SHELL_NOT_BOUND")
        // `TransactionTooLargeException` is caught **first and separately** (R-9): it is a reply this build could not
        // carry, not one it could not parse, and reporting the two as the same string told a caller whose command had
        // actually run that the reply was garbage. Neither answer is an empty success.
        val reply = try {
            service.run(command)
        } catch (_: TransactionTooLargeException) {
            return ShellResult(command, -1, "", SHELL_REPLY_TOO_LARGE)
        } catch (_: Throwable) {
            null
        }
        return ShellReply.decode(reply)?.copy(command = command)
            ?: ShellResult(command, -1, "", SHELL_REPLY_INVALID)
    }

    private fun binderAlive(): Boolean = runCatching {
        Shizuku.pingBinder() && Shizuku.getVersion() >= MIN_SHIZUKU_VERSION &&
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    private companion object {
        const val TAG = "ShizukuShell"

        /** `UserService` arrived in Shizuku v11 and settled in v12; older binders are not a channel we can use. */
        const val MIN_SHIZUKU_VERSION = 12

        /** Any value; Shizuku only echoes it so the caller can correlate the result. */
        const val PERMISSION_REQUEST_CODE = 4213
    }
}

/**
 * The device's own answers about a target, read through the same privileged shell.
 *
 * Each probe reads a **state**, not an exit code: `pm list packages` for installed/disabled and `dumpsys package` for a
 * runtime permission. That is what makes "exit 0 but nothing changed" detectable, which is the whole point of the
 * T3a lesson.
 */
class ShizukuTargetState(private val shell: PrivilegedShell) : TargetState {

    /**
     * Whether the probe that just ran produced a reply this build could parse.
     *
     * Starts `false`: before anything has been read, nothing may be claimed. Every probe goes through [read], so the
     * answer always describes the read the backend is about to judge.
     */
    @Volatile
    private var lastReplyReadable = false

    override fun readable(): Boolean = lastReplyReadable

    override fun installed(pkg: String): Boolean =
        read("pm list packages $pkg").stdout.lineSequence().any { it.trim() == "package:$pkg" }

    override fun disabled(pkg: String): Boolean =
        read("pm list packages -d $pkg").stdout.lineSequence().any { it.trim() == "package:$pkg" }

    override fun permissionGranted(pkg: String, permission: String): Boolean =
        read("dumpsys package $pkg").stdout.contains("$permission: granted=true")

    override fun setting(name: String): String? =
        read("settings get system $name").stdout.trim().takeIf { it.isNotEmpty() && it != "null" }

    private fun read(command: String): ShellResult =
        shell.run(command).also { lastReplyReadable = it.isAStateReply() }
}
