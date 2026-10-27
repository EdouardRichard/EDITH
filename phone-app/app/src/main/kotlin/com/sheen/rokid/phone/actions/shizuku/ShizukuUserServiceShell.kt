package com.sheen.rokid.phone.actions.shizuku
import com.sheen.rokid.phone.R
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
 *
 * **The channel can also re-arm itself** (R-9 follow-up, item #2). A carrier failure kills the connection, and recovery
 * used to depend entirely on Shizuku's framework redelivering `onServiceConnected`: `bind()` ran exactly once at
 * composition time, and `onServiceDisconnected` did nothing but clear [remote]. The device does redeliver (measured:
 * the immediately next call succeeded on the same remote pid), but nothing here excluded the case where it does not -
 * so [run] now asks [RelinkGate] whether it may bind again, and does so at most once per window.
 */
class ShizukuUserServiceShell private constructor(
    componentName: ComponentName,
    versionCode: Int,
    debuggable: Boolean,
    /** P3.3: called once the shell process is connected, so the caller can report the channel as up without polling. */
    private val onReady: () -> Unit,
    /** R-9 follow-up, item #2: the bounded "may this call bind again" decision. See [RelinkGate]. */
    private val gate: RelinkGate,
    private val bindOverride: (() -> Boolean)?,
    private val shizukuUsableOverride: (() -> Boolean)?,
) : PrivilegedShell {

    /**
     * The production shape, and the only one this App builds: the component is named from the app's own package, so the
     * `Context` is read here rather than kept.
     */
    constructor(
        context: Context,
        versionCode: Int,
        debuggable: Boolean,
        onReady: () -> Unit = {},
        gate: RelinkGate = RelinkGate(),
        /**
         * **Test seam** (R-9 follow-up, item #2): replaces the recovery's bind request, and answers whether the
         * connection came back within it. `null` - production - binds through `Shizuku` and reports [remote].
         *
         * It exists because a JVM test cannot produce the two facts the recovery reads: whether Shizuku's bind request
         * happened, and whether it restored the remote. Everything else - [run], the carrier classification, the gate -
         * is the production code the test exercises.
         */
        bindForRecovery: (() -> Boolean)? = null,
        /** **Test seam**: replaces [binderAlive]; `null` performs the real Shizuku checks. */
        shizukuUsableOverride: (() -> Boolean)? = null,
    ) : this(
        componentName = ComponentName(context.packageName, EdithShellService::class.java.name),
        versionCode = versionCode,
        debuggable = debuggable,
        onReady = onReady,
        gate = gate,
        bindOverride = bindForRecovery,
        shizukuUsableOverride = shizukuUsableOverride,
    )

    private val args = Shizuku.UserServiceArgs(componentName)
        .daemon(false)
        .processNameSuffix("shell")
        .debuggable(debuggable)
        .version(versionCode)

    @Volatile
    private var remote: IRemoteShell? = null

    /**
     * Which branch of [run] answered the last call, as a value a test **and** a device probe can read: `direct` (the
     * remote was there), `rebound` (a retry crossed after a rebind), `failed` (the call could not reach the remote and
     * nothing brought it back), `rate_limited` (it could not be reached and a rebind had already been tried inside the
     * window).
     *
     * It is what `DebugShellCapProbe` logs as `answer=`, so a device run can tell "the recovery answered this" from
     * "this failed while a recovery was still pending" without printing anything from the command.
     */
    @Volatile
    var lastAnswerPath: String = "direct"
        private set

    /** How many binds this shell has asked for through the recovery path; a test reads it to prove the bound holds. */
    val bindCalls: Int get() = gate.attempts

    /**
     * **Test-only** stand-in for `onServiceConnected`, so a JVM test can say whether a remote is present.
     *
     * The production setter is the `ServiceConnection` below; this exists only because `IRemoteShell` is an AIDL stub
     * backed by a real binder, which a JVM test cannot mint.
     */
    internal var remoteForTest: IRemoteShell?
        get() = remote
        set(value) {
            remote = value
        }

    private val bindForRecovery: () -> Boolean = bindOverride ?: { bind(); remote != null }

    private val shizukuUsable: () -> Boolean = shizukuUsableOverride ?: { binderAlive() }

    /** See [lastCarrierThrowable]; a class name only, overwritten on the next carrier failure. */
    @Volatile
    private var lastCarrierThrowableValue: String? = null

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

    /**
     * Sends one command and decodes the reply, and lets the recovery path answer when the channel could not.
     *
     * It is also where the gate learns how the call went, which is what arms the self-rebind. That is deliberate: with
     * [RelinkGate.decide] as the only way to record an attempt, "bounded" is a property of the code rather than a rule
     * each caller has to remember.
     */
    override fun run(command: String): ShellResult {
        gate.decide(remoteReady = remote != null, shizukuUsable = shizukuUsable())
        val result = attempt(command)
        // A reply that crossed is not a reason to rebind, whatever it says about the target.
        if (result.failure != ShellFailure.CARRIER) return result
        // The connection failed to carry *this* call, so it is known dead here whatever `remote` still holds: a dead
        // binder does not clear the reference until the framework's `onServiceDisconnected` arrives. That third fact is
        // what the gate needs, because the first two would otherwise answer `READY` for a channel that just died.
        val recovery = gate.decide(
            remoteReady = remote != null,
            shizukuUsable = shizukuUsable(),
            connectionKnownDead = true,
        )
        return recoverFrom(command, result, decision = recovery)
    }

    /** One binder call, with R-9's classification: **any** throwable out of it is a carrier failure, never a parse one. */
    private fun attempt(command: String): ShellResult {
        // R-9: testing for `TransactionTooLargeException` alone was the defect - the device raised
        // `DeadObjectException`, the catch-all swallowed it into `null`, and `decode(null)` reported a reply that could
        // not cross the wire as garbage.
        val service = remote ?: return ShellResult(command, -1, "", SHELL_NOT_BOUND, failure = ShellFailure.NOT_BOUND)
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
     * The bounded self-recovery (R-9 follow-up, item #2): asks Shizuku to bind **the same** service again and retries
     * once, and only once, when the gate allowed it.
     *
     * A failed attempt keeps the original failure - the code and kind the first call really had - so a dead channel is
     * still reported as a dead channel; a successful one returns the retry's reply with [ShellResult.relinked] set, so
     * the caller can see that the recovery is what answered. Safe to call from the turn thread: the only work is the
     * bind request the composition already makes, and the gate is what keeps it from turning into a loop.
     */
    private fun recoverFrom(command: String, original: ShellResult, decision: RelinkDecision): ShellResult {
        if (decision != RelinkDecision.REBIND) {
            // Nothing was attempted, so there is nothing to retry: the original failure stands and the answer path
            // names the refusal (`NOT_USABLE` -> "not_usable", `RATE_LIMITED` -> "rate_limited").
            lastAnswerPath = decision.name.lowercase()
            return original
        }
        val rebound = bindForRecovery()
        if (!rebound) {
            lastAnswerPath = "failed"
            return original
        }
        val retry = attempt(command)
        lastAnswerPath = "rebound"
        return retry.copy(relinked = true)
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

    companion object {
        const val TAG = "ShizukuShell"

        /** `UserService` arrived in Shizuku v11 and settled in v12; older binders are not a channel we can use. */
        const val MIN_SHIZUKU_VERSION = 12

        /** Any value; Shizuku only echoes it so the caller can correlate the result. */
        const val PERMISSION_REQUEST_CODE = 4213

        /**
         * A shell with the binder seam faked, so the recovery path is exercisable without a device.
         *
         * **Not a production path**, and it cannot be used as one: the real constructor needs an Android `Context`, and
         * every argument here replaces one of the two facts a JVM test cannot produce - "Shizuku is alive and permitted"
         * and "the bind request happened and restored the connection". The shell still runs the production [run] /
         * [attempt] / [recoverFrom] logic, including [RelinkGate].
         *
         * It is `internal` rather than `private` so the test in `src/test` can reach it, and named `forTest` so no
         * reader mistakes it for a way to build a working shell.
         */
        internal fun forTest(
            relinkSucceeds: Boolean,
            gate: RelinkGate = RelinkGate(),
            binderAlive: () -> Boolean = { true },
        ): ShizukuUserServiceShell {
            var binds = 0
            // The shell is needed *inside* the bind override (a successful rebind installs the answering remote), so it
            // is held in a `lateinit var` the lambda closes over - the production writer of that field is
            // `ServiceConnection.onServiceConnected`.
            lateinit var shell: ShizukuUserServiceShell
            shell = ShizukuUserServiceShell(
                // The private constructor is ComponentName-based rather than Context-based on purpose: a JVM test has
                // no Android `Context`, and this configuration never binds through `Shizuku`.
                componentName = ComponentName("com.sheen.rokid.phone.test", "com.sheen.rokid.phone.actions.FakeShellService"),
                versionCode = 1,
                debuggable = true,
                onReady = {},
                gate = gate,
                bindOverride = {
                    binds++
                    // A successful rebind puts a working remote back. A failed one leaves the channel **disconnected**
                    // too, because that is what a failed bind means on the device - the framework's
                    // `onServiceDisconnected` clears the remote - and a fake that kept the dead binder around would let
                    // the "does not restore the channel" test agree with a channel that did come back.
                    if (relinkSucceeds) shell.remoteForTest = remoteThatAnswersTheRetry() else shell.remoteForTest = null
                    relinkSucceeds
                },
                shizukuUsableOverride = binderAlive,
            )
            // Start connected with a dead binder: the first call reaches the remote and the call itself is what dies, so
            // the recovery is entered by the failure the device measured (`DeadObjectException`).
            shell.remoteForTest = deadRemote()
            return shell
        }

        /**
         * The binder the first call dies on: it raises exactly what the K40 raised for an oversized reply, on **every**
         * call - a dead connection does not heal itself, and pretending otherwise would let a test pass for the wrong
         * reason.
         */
        private fun deadRemote(): IRemoteShell = object : IRemoteShell {
            override fun run(command: String): String =
                throw android.os.DeadObjectException("reply too large for the binder")

            override fun destroy() = Unit

            override fun asBinder(): IBinder =
                throw UnsupportedOperationException("test double: never asked for a binder")
        }

        /** What the retry finds after a successful rebind: a live remote that answers a real encoded reply. */
        private fun remoteThatAnswersTheRetry(): IRemoteShell = object : IRemoteShell {
            override fun run(command: String): String = ShellReply.encode(0, "package:edith.phone", "")

            override fun destroy() = Unit

            override fun asBinder(): IBinder =
                throw UnsupportedOperationException("test double: never asked for a binder")
        }
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
     *   read unreadable. The rule is the channel's own failure field rather than a test for two remembered codes,
     *   because the old two-code version let `SHELL_NOT_BOUND` through: [installed] then answered `false` on an empty
     *   `stdout` and the backend reported "the action did not take" for an action that was never attempted;
     * - **a non-zero exit** makes it unreadable as well (open item #4): `isReadableState()` keys on the failure kind and
     *   the exit code, and a state command that exited non-zero did not complete, so its `stdout` is not an answer;
     * - **truncation** makes it unreadable too: [installed], [disabled] and [permissionGranted] are stdout **parsers**,
     *   and a clipped `stdout` is a prefix, not the whole answer - so the clipped tail can turn a present package into a
     *   `false` the backend then judges as "the action did not take". A carrier failure is already its own code, but the
     *   *decoded-and-truncated* case is a successful parse, and only this flag distinguishes it.
     */
    @Volatile
    private var lastReplyReadable = false

    override fun readable(): Boolean = lastReplyReadable

    override fun installed(pkg: String): Boolean =
        read("pm list packages $pkg")?.stdout?.lineSequence()?.any { it.trim() == "package:$pkg" } ?: false

    override fun disabled(pkg: String): Boolean =
        read("pm list packages -d $pkg")?.stdout?.lineSequence()?.any { it.trim() == "package:$pkg" } ?: false

    override fun permissionGranted(pkg: String, permission: String): Boolean =
        read("dumpsys package $pkg")?.stdout?.contains("$permission: granted=true") ?: false

    override fun setting(name: String): String? =
        read("settings get system $name")?.stdout?.trim()?.takeIf { it.isNotEmpty() && it != "null" }

    /**
     * Runs one state command and reports whether its reply is a readable state answer.
     *
     * `null` when it is not: every parser above then answers "nothing" rather than judging the target from a reply that
     * did not complete, and [readable] says the same thing for the same read. Both branches going through one predicate
     * is the point - the failure the backend reports ("could not read") and the answer a parser gives cannot disagree.
     */
    private fun read(command: String): ShellResult? = shell.run(command).also {
        lastReplyReadable = it.isReadableState()
    }.takeIf { lastReplyReadable }
}
