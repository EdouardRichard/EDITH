package edith.phone.actions

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
)

/** The privileged command channel: Shizuku's shell UID (or whatever runs commands as that UID in a test). */
interface PrivilegedShell {
    fun run(command: String): ShellResult
}

/** The marker the privileged shell uses when it cannot parse its own reply (see `ShellReply`). */
internal const val SHELL_REPLY_INVALID = "SHELL_REPLY_INVALID"

/** False when the shell could not parse its reply: a state read that **failed**, not an answer about the target. */
internal fun ShellResult.isAStateReply(): Boolean = stderr != SHELL_REPLY_INVALID

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
