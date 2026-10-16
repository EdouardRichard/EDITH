package edith.phone.actions

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
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
 * - a reply this build cannot parse ⇒ `SHELL_REPLY_INVALID` with exit `-1` and `failure = PARSE`, never an empty success;
 * - a call that did not come back ⇒ `SHELL_REPLY_TOO_LARGE` with exit `-1` and `failure = CARRIER` (R-9): a distinct
 *   code **and** a distinct kind, so "the channel could not carry it" and "this build could not parse it" are two facts
 *   rather than one, and neither is read as a success.
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

    /** See [lastCarrierThrowable]; a class name only, overwritten on the next carrier failure. */
    @Volatile
    private var lastCarrierThrowableValue: String? = null

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
        val service = remote ?: return ShellResult(command, -1, "", SHELL_NOT_BOUND, failure = ShellFailure.NOT_BOUND)
        // R-9: **any** throwable out of the binder call is a carrier failure, not a parse failure. Testing for
        // `TransactionTooLargeException` alone was the defect - the device raised something else, the catch-all below
        // swallowed it into `null`, and `decode(null)` reported a reply that could not cross the wire as garbage.
        val reply = try {
            service.run(command)
        } catch (thrown: Throwable) {
            lastCarrierThrowableValue = thrown.javaClass.name
            return ShellResult(command, -1, "", carrierCodeFor(thrown), failure = ShellFailure.CARRIER)
        }
        return ShellReply.decode(reply)?.copy(command = command)
            ?: ShellResult(command, -1, "", SHELL_REPLY_INVALID, failure = ShellFailure.PARSE)
    }

    /**
     * The class name of the last throwable the binder call raised, or `null` when it has not raised one.
     *
     * **Diagnostics only**, and a class name alone: it exists so a device probe can say *which* failure actually occurs,
     * because the code was once named after an exception (`TransactionTooLargeException`) that turned out not to be the
     * one the device throws. Never a message, never a reply body, never a command - the class name is bounded, carries
     * no payload, and is read by `DebugShellCapProbe` on the device.
     */
    val lastCarrierThrowable: String?
        get() = lastCarrierThrowableValue

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
     * Whether the probe that just ran produced a reply that **crossed the channel, was understood, and was not cut
     * short**.
     *
     * Starts `false`: before anything has been read, nothing may be claimed. Every probe goes through [read], so the
     * answer always describes the read the backend is about to judge.
     *
     * Both halves are R-9, and both are about not inventing an answer:
     * - **the channel reported a failure** - `SHELL_NOT_BOUND`, the carrier code, or `SHELL_REPLY_INVALID` - makes the
     *   read unreadable. `isAStateReply()` is deliberately the inverse of the channel's failure field rather than a test
     *   for two remembered codes, because the old two-code version let `SHELL_NOT_BOUND` through: [installed] then
     *   answered `false` on an empty `stdout` and the backend reported "the action did not take" for an action that was
     *   never attempted;
     * - **truncation** makes it unreadable too: [installed], [disabled] and [permissionGranted] are stdout **parsers**,
     *   and a clipped `stdout` is a prefix, not the whole answer - so the clipped tail can turn a present package into a
     *   `false` the backend then judges as "the action did not take". A carrier failure is already its own code, but the
     *   *decoded-and-truncated* case is a successful parse, and only this flag distinguishes it.
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
        shell.run(command).also { lastReplyReadable = it.isAStateReply() && !it.truncated }
}
