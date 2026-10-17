package com.sheen.rokid.phone.actions.shizuku
import com.sheen.rokid.phone.R
import java.io.ByteArrayOutputStream
import java.io.InputStream
import kotlin.concurrent.thread

/**
 * Reads both of a child process's pipes to completion and returns `(stdout, stderr, truncated)`.
 *
 * **Why concurrently** (P3.3 review, Minor 3): each pipe holds only tens of KB. A child that writes more than that to
 * stderr blocks in `write(2)` until somebody reads it - and it does not exit, so its stdout never reaches EOF. Draining
 * stdout to completion first therefore waits on a process that is itself waiting for us, forever; the app side sees only
 * a binder timeout and reports a command that actually ran as failed. One thread per pipe is the fix, and stderr is the
 * one moved off the caller because stdout is usually the payload the caller is waiting for.
 *
 * **Why a cap, and why it does not stop the drain** (R-9 / D-6): without a bound the privileged process would hold an
 * unbounded `String`, then a second copy while `ShellReply.encode` builds its JSON, then a third on the app side. So
 * each stream accumulates at most [maxBytes] ([ShellReply.MAX_REPLY_BYTES] by default). Reaching the cap stops the
 * **accumulation only**: both pipes are still read to EOF, because the child is blocked in `write(2)` until somebody
 * reads it, and a drain that stopped early would reintroduce exactly the deadlock this function exists to remove - the
 * other stream would never reach EOF and the caller would wait forever.
 *
 * The cap is per stream, which keeps the third element meaningful: when one pipe overflows it is cut short and the
 * other stream is still returned whole (its diagnostics stay readable) rather than being sacrificed to a shared budget
 * in whatever order the two threads happened to run. The third element of the triple is `true` when **either** stream
 * was cut short.
 *
 * The returned triple keeps the two streams apart: `ShellReply` reports them separately, and the app side's judging
 * (`ShizukuTargetState`) reads stdout while stderr is what a human reads when an exit code lies.
 *
 * **Residual (P3.3 review, Minor 6).** This removes the *guaranteed* deadlock; it does not invent a timeout. A child that
 * goes quiet with both pipes open still parks the calling thread, because a blocking read on an arbitrary
 * `InputStream` is not interruptible - only the trailing `join` is. The bound is therefore the caller's
 * (`ShizukuUserServiceShell`'s binder call), and an interrupt that does arrive must surface rather than be swallowed
 * into a partial success; `ShellStreamsTest` pins exactly that.
 */
internal fun drainStreams(
    stdout: InputStream,
    stderr: InputStream,
    maxBytes: Int = ShellReply.MAX_REPLY_BYTES,
): Triple<String, String, Boolean> {
    var err = ""
    var errTruncated = false
    val stderrDrain = thread(isDaemon = true, name = "edith-shell-stderr") {
        val read = readReply(stderr, maxBytes)
        err = read.text
        errTruncated = read.truncated
    }
    val out = readReply(stdout, maxBytes)
    // `join` is also what publishes `err`/`errTruncated` to this thread; reading them before the join would be a data race.
    stderrDrain.join()
    return Triple(out.text, err, out.truncated || errTruncated)
}

/** One stream's accumulated text and whether it had to be cut short. */
private class DrainedReply(val text: String, val truncated: Boolean)

/**
 * Reads [stream] to EOF, accumulating at most [maxBytes] bytes.
 *
 * The clamp is `maxBytes - total`, never `0`, so a zero window never turns into a `read(buf, 0, 0)` loop; once the
 * budget is gone only the [DISCARD] read below runs, which drains the pipe without retaining it.
 */
private fun readReply(stream: InputStream, maxBytes: Int): DrainedReply {
    val out = ByteArrayOutputStream(minOf(maxBytes, 64 * 1024))
    val buffer = ByteArray(8 * 1024)
    var total = 0
    var truncated = false
    while (true) {
        val limit = maxBytes - total
        val read = if (limit > 0) stream.read(buffer, 0, minOf(buffer.size, limit)) else stream.read(DISCARD, 0, DISCARD.size)
        if (read < 0) break
        if (limit > 0) {
            out.write(buffer, 0, read)
            total += read
        } else {
            truncated = true
        }
    }
    return DrainedReply(out.toByteArray().toString(Charsets.UTF_8), truncated)
}

/** A sink used to finish draining a pipe whose budget is already spent (never retained). */
private val DISCARD = ByteArray(8 * 1024)
