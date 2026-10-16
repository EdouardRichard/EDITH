package edith.phone.actions

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
     * [ShellFailure.PARSE] - so a caller (and [isAStateReply]) can act on the kind without parsing [stderr]. A reply
     * that decoded normally leaves it `null` even when the command's own `stderr` is full of text: a child that writes
     * to its error stream did answer, and its text is the command's diagnostics, not a channel failure.
     */
    val failure: ShellFailure? = null,
)

/** The privileged command channel: Shizuku's shell UID (or whatever runs commands as that UID in a test). */
interface PrivilegedShell {
    fun run(command: String): ShellResult
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
 * remote logs `Large reply transaction of 750080 bytes, interface descriptor edith.phone.actions.IRemoteShell, code 2`
 * and the caller gets **`android.os.DeadObjectException`** - the binder connection itself dies mid-transaction, rather
 * than `TransactionTooLargeException` being raised politely. The service process is restarted by the framework and the
 * next call on the same channel succeeds (measured: the very next sweep entry returned `exit=0`), so this is a
 * recoverable, per-call carrier failure. The classification therefore does not test for one exception class - see
 * [carrierCodeFor].
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
 */
internal fun carrierCodeFor(thrown: Throwable): String = SHELL_REPLY_TOO_LARGE

/**
 * False when the shell itself reported a failure: a state read that **failed**, not an answer about the target.
 *
 * A reply is a state reply only when the channel reported **no** failure, i.e. [ShellResult.failure] is `null` **and**
 * `stderr` is empty.
 *
 * Why the empty-`stderr` half is a whitelist rather than a list of known-bad codes: `isAStateReply()` used to be
 * `stderr != SHELL_REPLY_INVALID && stderr != SHELL_REPLY_TOO_LARGE`, which made `SHELL_NOT_BOUND` - and any code added
 * later, or written by a newer shell - read as a successful state reply with an empty `stdout`. `installed()` then
 * answered `false` for a target nobody ever asked about, and the backend reported "the action did not take" for an
 * action that was never attempted. A blacklist cannot exclude a code it has never seen, so the rule is stated the other
 * way round.
 *
 * The strict half costs nothing measured: every real state command this app issues (`pm list packages`,
 * `dumpsys package <pkg>`, `settings get`) answers with an empty `stderr`, as the K40 probe's `stderrBytes=0` records.
 * A read whose `stderr` is non-empty therefore describes a read the channel or the command disturbed, and treating it as
 * unreadable means the backend refuses instead of judging the action from a reply it cannot fully account for.
 */
internal fun ShellResult.isAStateReply(): Boolean = failure == null && stderr.isEmpty()

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
     * [edith.phone.actions.ShizukuTargetState] starts at `false` and reports each probe.
     */
    fun readable(): Boolean = true
}
