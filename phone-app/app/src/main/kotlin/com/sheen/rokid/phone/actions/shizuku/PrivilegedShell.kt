package com.sheen.rokid.phone.actions.shizuku
import com.sheen.rokid.phone.R
import com.sheen.rokid.phone.actions.intent.LaunchAppExecutor
import com.sheen.rokid.phone.actions.intent.SystemActionExecutor
import com.sheen.rokid.phone.storage.unavailable
import com.sheen.rokid.phone.debug.DebugShellCapProbe
/**
 * What went wrong when a reply could not be used (R-9).
 *
 * The distinction this exists for is **not** between exception classes but between *facts*: a reply the channel could
 * not carry is not a reply this build could not understand, and neither is a success. The device measurement
 * (`evidence/2026-10-16-phase-5-device-verification.md`) showed that the throwable actually raised for an oversized
 * reply is **not** `TransactionTooLargeException`, so a classification that tested for that one class produced the wrong
 * answer - and the field here is what a caller reads instead of comparing `stderr` against marker strings.
 */
enum class ShellFailure {
    /** The remote was never reachable: `SHELL_NOT_BOUND`. The channel died between `available()` and the call. */
    NOT_BOUND,

    /** The call did not come back: the binder refused or could not carry the reply. Never a statement about its syntax. */
    CARRIER,

    /** The reply arrived and this build could not parse it. The only case that means "garbage came back". */
    PARSE,
}

/**
 * One privileged command's outcome.
 *
 * [exitCode] is carried because it is what the OS said, and it is deliberately **not** the verdict: P0's shell matrix
 * measured both a false `OK` (`pm grant` returning 0 for a permission that was already granted) and a false `BLOCKED`
 * (`cmd statusbar` printing help and exiting 1 for a capability that works). Judgement belongs to [TargetState].
 */
data class ShellResult(
    val command: String,
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    /**
     * Whether this reply hit the single-reply cap and was cut short (R-9 / D-6).
     *
     * Appended last, so every existing positional construction keeps compiling. A truncated reply is still a reply the
     * caller understood - it is not a parse failure and not an empty success - but `stdout` is a prefix rather than the
     * whole answer, so a caller that judges a target by parsing it must be able to tell.
     */
    val truncated: Boolean = false,
    /**
     * Why this reply is not usable, or `null` when it crossed the channel and was understood (R-9).
     *
     * Non-null for every failure [PrivilegedShell] itself reports - [ShellFailure.NOT_BOUND], [ShellFailure.CARRIER],
     * [ShellFailure.PARSE] - so a caller can act on the kind without parsing [stderr]. A reply
     * that decoded normally leaves it `null` even when the command's own `stderr` is full of text: a child that writes
     * to its error stream did answer, and its text is the command's diagnostics, not a channel failure.
     */
    val failure: ShellFailure? = null,
    /**
     * Whether **a rebind answered this call**: the call first could not reach the remote, the channel asked Shizuku to
     * bind the same user service again, and the retry crossed (R-9 follow-up, item #2).
     *
     * A fact about the *path*, not about the reply's contents, so it stays separate from [failure] and [truncated]: a
     * `relinked` reply is an ordinary reply that happens to have been carried by a connection this code brought back
     * itself, and a rebind that did not restore the channel leaves this `false` so nothing downstream can read "it
     * recovered" out of an unchanged failure. Appended last, so every existing positional construction keeps compiling.
     */
    val relinked: Boolean = false,
)

/** The privileged command channel: Shizuku's shell UID (or whatever runs commands as that UID in a test). */
interface PrivilegedShell {
    fun run(command: String): ShellResult
}

/** Why the channel is (or is not) asking Shizuku to bind its user service again (R-9 follow-up, item #2). */
enum class RelinkDecision {
    /** The remote is there and this call did not just prove otherwise; nothing is rebound. */
    READY,

    /** The connection could not carry the call, Shizuku is alive and permission is granted: bind the same service again. */
    REBIND,

    /** The connection is down and Shizuku itself is unavailable or the grant is gone: there is nothing to bring back. */
    NOT_USABLE,

    /** A rebind was already tried inside the current window; wait rather than ask again. */
    RATE_LIMITED,
}

/**
 * The bounded "bring the channel back" decision (R-9 follow-up, item #2).
 *
 * **Why this exists.** A reply too large for the Binder transaction buffer kills the connection
 * (`android.os.DeadObjectException`, measured on the K40). Recovery used to depend entirely on Shizuku's framework
 * redelivering `onServiceConnected`: `bind()` was called exactly once at composition time, and `onServiceDisconnected`
 * only cleared the remote. On the device the framework *did* redeliver (the immediately next call succeeded on the same
 * remote pid), but nothing in this code excluded the case where it does not - and in that case the channel would stay
 * dead for the rest of the process with no attempt to re-arm it. This class is that attempt, made deliberately small.
 *
 * **The refusals are the point, not the retry.** A rebind is attempted only when the call really could not reach the
 * remote, Shizuku is still alive, permission is still granted, and no attempt was made inside [windowMillis]. So it
 * cannot spin: a burst of commands inside one window costs exactly one bind, a dead Shizuku costs none, and a channel
 * that is already usable costs none. Callers report an attempt with [decide], which is also the only way to record one.
 *
 * **It never creates a second connection.** Shizuku allows one `UserService` binding per app, so the only recovery this
 * code has is asking Shizuku to bind *the same* args and *the same* `ServiceConnection` again - which is what
 * `ShizukuUserServiceShell.bind` does, and which it is already documented as safe to call repeatedly.
 *
 * **Thread safety.** The three executors and the state parsers all call into one shell and a turn thread can interleave
 * with the UI's probe, so the timestamp is an `AtomicLong` and [attempts] an `AtomicInteger`; every field read by a test
 * is `@Volatile`. There is no lock and no scheduling: two racing callers can each be answered `REBIND` and each bind,
 * which is a duplicate bind request - harmless and idempotent - never a second connection.
 *
 * **The clock is injected** so the window is testable on the JVM without sleeping. The default reads the monotonic
 * clock, and only differences are ever compared.
 */
class RelinkGate(
    /** How long the gate waits after a bind attempt before it is willing to ask again. */
    val windowMillis: Long = DEFAULT_WINDOW_MILLIS,
    private val elapsedMillis: () -> Long = { System.nanoTime() / 1_000_000L },
) {

    private val lastAttemptAt = java.util.concurrent.atomic.AtomicLong(Long.MIN_VALUE)
    private val attemptCount = java.util.concurrent.atomic.AtomicInteger(0)

    /** How many binds this gate has asked for; a test reads it to tell "bounded" from "on every call". */
    val attempts: Int get() = attemptCount.get()

    /** The last answer [decide] gave, or `null` before the first call. The "which path answered" a caller can read. */
    @Volatile
    var lastDecision: RelinkDecision? = null
        private set

    /** Whether this gate has ever asked for a bind: `true` after the first `REBIND`, never reset. */
    val remoteMissing: Boolean get() = attemptCount.get() > 0

    /**
     * Decides the path for one call, and **records an attempt when the answer is [RelinkDecision.REBIND]**.
     *
     * The recording lives here rather than in a separate `attempt()` on purpose: a caller that could record an attempt
     * without a matching `REBIND` could spend the window on a refusal, and a `RATE_LIMITED` caller that recorded anyway
     * would make "bounded" a comment instead of a property. With one entry point, boundedness is not something a caller
     * has to remember.
     *
     * `remoteReady` is the shell's own "a remote reference is present"; `shizukuUsable` its own "Shizuku is alive and
     * granted". `connectionKnownDead` is the third fact only the caller has: a carrier failure proves the connection did
     * not deliver **this** call, and a binder that dies does not necessarily clear the remote reference until the
     * framework's `onServiceDisconnected` arrives. Without it, a call that just died would be told `READY` and the
     * recovery would never run - the whole point of item #2.
     */
    fun decide(
        remoteReady: Boolean,
        shizukuUsable: Boolean,
        connectionKnownDead: Boolean = false,
    ): RelinkDecision {
        val decision = when {
            connectionKnownDead || !remoteReady -> when {
                !shizukuUsable -> RelinkDecision.NOT_USABLE
                !windowElapsed() -> RelinkDecision.RATE_LIMITED
                else -> RelinkDecision.REBIND
            }
            else -> RelinkDecision.READY
        }
        if (decision == RelinkDecision.REBIND) {
            attemptCount.incrementAndGet()
            lastAttemptAt.set(elapsedMillis())
        }
        lastDecision = decision
        return decision
    }

    private fun windowElapsed(): Boolean {
        val last = lastAttemptAt.get()
        return last == Long.MIN_VALUE || elapsedMillis() - last >= windowMillis
    }

    private companion object {
        /**
         * The K40's measured recovery latency is well under a second (the framework redelivered between two calls ~1 s
         * apart), but the window is deliberately far longer than that: its job is to bound a *failed* attempt, and a
         * recovery that the framework performs anyway must not turn every command in a turn into another bind request.
         */
        const val DEFAULT_WINDOW_MILLIS = 3_000L
    }
}

/** The marker the privileged shell uses when it cannot parse its own reply (see `ShellReply`). */
internal const val SHELL_REPLY_INVALID = "SHELL_REPLY_INVALID"

/**
 * The marker for a reply the binder could not carry (**R-9**), reported as its own code.
 *
 * Before this existed, `ShizukuUserServiceShell.run` wrapped the binder call in `runCatching{}.getOrNull()`, so
 * **every** throwable out of the call collapsed into [SHELL_REPLY_INVALID]: "the reply could not cross" and "the reply
 * could not be parsed" were the same string, and a command that actually ran was reported as garbage. They are two
 * different facts and both are *failures*: neither is an empty success.
 *
 * What the device actually does (K40, 2026-10-17) is worth stating, because it is not what the code used to assume. The
 * remote logs `Large reply transaction of 750080 bytes, interface descriptor com.sheen.rokid.phone.actions.IRemoteShell, code 2`
 * and the caller gets **`android.os.DeadObjectException`** - the binder connection itself dies mid-transaction, rather
 * than `TransactionTooLargeException` being raised politely. The service process is restarted by the framework, and
 * recovery was measured **on a later round, after that restart, on the same channel**: the byte sweep that contained
 * the failure (`bytes-350k` → `bytes-400k`/`bytes-900k`/`logcat-d` all `carrier`) did *not* recover entry by entry;
 * the `00:19` run on the new remote pid answered `exit=0` (`bytes-250k`/`bytes-300k`, and a `35 217 B` `logcat-d`).
 * That makes this a recoverable, per-call carrier failure - but only on a later call, not the immediately next one.
 * The classification therefore does not test for one exception class - see [carrierCodeFor].
 */
internal const val SHELL_REPLY_TOO_LARGE = "SHELL_REPLY_TOO_LARGE"

/** The marker for "the remote was never reachable", i.e. the binding died between `available()` and the call. */
internal const val SHELL_NOT_BOUND = "SHELL_NOT_BOUND"

/**
 * The code for a call that did not come back (R-9): the binder's own answer that the reply could not cross.
 *
 * **Why not test for `TransactionTooLargeException`**: the K40 measured ~300-350 kB replies failing without that class
 * ever being thrown - what it throws is `android.os.DeadObjectException`, and the remote logs the oversized transaction
 * (`Large reply transaction of 750080 bytes … code 2`). A test for `TransactionTooLargeException` therefore classified
 * the real failure as a parse failure, and `SHELL_REPLY_TOO_LARGE` - the code that was supposed to fire - never
 * appeared. Anything thrown out of the binder call means the same thing: *this call did not deliver a reply*, which is a
 * carrier fact and cannot be a statement about the reply's syntax.
 *
 * The parameter is kept, and is deliberately **not inspected** (R-9 close-out review, Minor 4): it is the witness that
 * this answer is type-agnostic, and it also keeps the mapping testable with the two classes that matter - the
 * `android.os.DeadObjectException` the device really raises and the `TransactionTooLargeException` it never raised.
 * Any branch on the class here would reintroduce the defect this function exists to remove; the class name is recorded
 * for diagnostics, separately, in `ShizukuUserServiceShell.lastCarrierThrowable`.
 */
@Suppress("UNUSED_PARAMETER")
internal fun carrierCodeFor(thrown: Throwable): String {
    // `thrown` is intentionally not read: the answer must not depend on the class, which is the whole point (the old
    // code's failure was exactly a by-name test for one class). Kept as the parameter the caller already has, and so
    // the mapping can be driven directly with the two classes that matter - `DeadObjectException` (what the device
    // raises) and `TransactionTooLargeException` (what it never raised).
    return SHELL_REPLY_TOO_LARGE
}

/**
 * Whether this call actually completed: the binder returned and the payload **and its marker** parsed.
 *
 * The one question every executor that reads a [ShellResult] has to ask before it judges an action, asked once so the
 * three executors on this channel cannot drift apart (R-9 follow-up, open item #3). `ShizukuExecutionBackend` consulted
 * [ShellResult.failure] and refused with `ACTION_CHANNEL_UNAVAILABLE`; [LaunchAppExecutor] and [SystemActionExecutor]
 * did not, so a call whose reply never crossed reported `STATE_UNCHANGED` / `COMMAND_FAILED` instead - **the wrong
 * invitation**: those tell the model the action failed (retry it, or pick another app), when the fact is that this route
 * could not carry an answer at all (take another route, or say the capability is unavailable). The dangerous direction
 * was never at risk, because every failure code reports `exitCode = -1` and empty `stdout`, so no success test can pass;
 * what was wrong was the reason, and the reason is what the model acts on.
 *
 * This is exactly [ShellResult.failure] `== null`, and deliberately **not** [isReadableState]: a command that answered
 * with a non-empty `stderr` *did* answer, and its diagnostics are the command's business rather than a channel failure.
 * A stdout parser needs more than "the call came back" - [isReadableState] adds "the command completed" and "the payload
 * was not clipped" - so folding the two together here would either open that hole or start calling a completed command a
 * dead channel.
 */
internal fun ShellResult.isCallComplete(): Boolean = failure == null

/**
 * The channel reported no failure: the bytes crossed and the payload parsed. **Not** yet a state read.
 *
 * This is [ShellResult.failure] `== null`, and nothing else. It used to also require an empty `stderr`, and that extra
 * half was open item #4: a successful command (`exit 0`) that wrote a warning to its error stream was judged unreadable,
 * so `installed`/`disabled`/`permissionGranted` answered `false` and the backend reported "the action did not take
 * effect" for an action whose state it had in fact just read - failing closed, but with the wrong reason.
 *
 * It is deliberately **not** the predicate a state parser may use either. What a stdout parser needs is
 * [isReadableState], which adds the two facts that make a reply an *answer about the target*: the command completed
 * (`exitCode == 0`) and the payload was not cut short. Keeping [isCallComplete] separate is what lets the executors
 * state "this call came back" without repeating the state-read rule; it must never be used to judge a target.
 *
 * A former `isAStateReply()` alias of this function was deleted (R-9 follow-up): its name promised a *state* answer
 * while it only reported completion, so it read as true for a reply that had exited non-zero or been cut short - the
 * two cases [isReadableState] exists to refuse. Nothing in production called it; its test call-sites now use
 * [isReadableState].
 */

/**
 * Whether a **state read** may be judged from this reply (R-9 follow-up, item #4).
 *
 * A reply is a readable state read when all three hold:
 * - **the channel reported no failure** ([isCallComplete]) - so `SHELL_NOT_BOUND`, the carrier code, a parse failure, and
 *   any code a newer shell invents are all refused. This is the half that a blacklist could not express, and it is kept
 *   exactly as it was;
 * - **the command completed**: `exitCode == 0`. The device's own completion signal, and the part that replaced the
 *   empty-`stderr` half. Measured (K40 `2696813b`, this build, `DebugShellCapProbe`): over 3 turns and five targets,
 *   **52 `exit 0` reads of the three state commands produced non-empty `stderr` 0 times** (`stdoutBytes` 30 … 328 983,
 *   `code=none`, `truncated=false` throughout), so on the one device this App targets the old whitelist was never
 *   *needed* - and every non-empty-`stderr` row observed (`exit -1`, `stderrBytes` 21, `code=SHELL_REPLY_TOO_LARGE`) was
 *   a carrier failure whose 21-byte `stderr` is the marker string itself, which the failure-kind half already refuses.
 *   A non-zero exit with an *empty* `stderr` was observed too (a hand-typed invalid argument to `dumpsys`), and the old
 *   predicate read it as a confident answer - the dangerous direction this half closes;
 * - **the payload was not cut short** (`!truncated`). A clipped `stdout` is a prefix, and the clipped tail is exactly
 *   where a `package:` line goes - so an unread prefix would answer `false` for a package that is present, and the
 *   backend would judge that as "the action did not take". A *decoded* reply that hit the cap is a successful parse, so
 *   only this flag distinguishes it from a whole one.
 *
 * **Every parser goes through this.** `installed`, `disabled`, `permissionGranted` and `setting` all read through
 * `ShizukuTargetState.read`, which answers nothing unless this is true, and [TargetState.readable] reports the same
 * answer for the same read - so the refusal the backend reports and the answer a parser gives cannot disagree.
 */
internal fun ShellResult.isReadableState(): Boolean =
    isCallComplete() && exitCode == 0 && !truncated

/**
 * What the device actually looks like after a command, which is the only thing that may be read as success.
 *
 * Every method must be answerable without the action under test being applied: they are called *after* the command to
 * decide whether it happened, and their answers are also what makes "refused means nothing happened" checkable.
 */
interface TargetState {
    fun installed(pkg: String): Boolean

    fun permissionGranted(pkg: String, permission: String): Boolean

    fun disabled(pkg: String): Boolean

    /** The current value of a system setting, or `null` when it has none. Read back to judge what was just written. */
    fun setting(name: String): String?

    /**
     * Whether the **last** read could be read at all.
     *
     * The other half of the honesty problem this interface exists for (P3.3 review, Important 4): `installed` answers
     * `false` both for "the package is gone" and for "the reply could not be parsed", and the second one judged by
     * `!installed` is a completed action that never happened. The channel probe catches the case where the channel died;
     * this catches the case where the channel is up and the reply is garbage.
     *
     * Defaults to `true` so a fake that answers meaningfully means what it says; the real
     * [com.sheen.rokid.phone.actions.shizuku.ShizukuTargetState] starts at `false` and reports each probe.
     */
    fun readable(): Boolean = true
}
