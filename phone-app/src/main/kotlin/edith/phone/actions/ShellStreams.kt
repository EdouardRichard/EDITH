package edith.phone.actions

import java.io.InputStream
import kotlin.concurrent.thread

/**
 * Reads both of a child process's pipes to completion and returns `(stdout, stderr)`.
 *
 * **Why concurrently** (P3.3 review, Minor 3): each pipe holds only tens of KB. A child that writes more than that to
 * stderr blocks in `write(2)` until somebody reads it - and it does not exit, so its stdout never reaches EOF. Draining
 * stdout to completion first therefore waits on a process that is itself waiting for us, forever; the app side sees only
 * a binder timeout and reports a command that actually ran as failed. One thread per pipe is the fix, and stderr is the
 * one moved off the caller because stdout is usually the payload the caller is waiting for.
 *
 * The returned pair keeps the two streams apart: `ShellReply` reports them separately, and the app side's judging
 * (`ShizukuTargetState`) reads stdout while stderr is what a human reads when an exit code lies.
 */
internal fun drainStreams(stdout: InputStream, stderr: InputStream): Pair<String, String> {
    var err = ""
    val stderrDrain = thread(isDaemon = true, name = "edith-shell-stderr") {
        err = stderr.bufferedReader().use { it.readText() }
    }
    val out = stdout.bufferedReader().use { it.readText() }
    // `join` is also what publishes `err` to this thread; reading it before the join would be a data race.
    stderrDrain.join()
    return out to err
}
