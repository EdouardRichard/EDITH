package com.sheen.rokid.phone.actions.shizuku
import com.sheen.rokid.phone.R
import android.content.Context
import android.util.Log
import androidx.annotation.Keep

/**
 * The class Shizuku instantiates **inside its own elevated process** (P3.3 Task 8).
 *
 * It is not a normal service: nothing binds it from our process through the package manager. Shizuku creates the
 * instance by name, so it must keep a public no-argument constructor and, from Shizuku v13, a `(Context)` one - the
 * vendor demo annotates that constructor `@Keep` because ProGuard cannot see the reflective call
 * (`D:\Project_new\Shizuku-API\demo\src\main\java\rikka\shizuku\demo\service\UserService.java`).
 *
 * Everything this class knows how to do is run one command and report the outcome; the interpretation of that outcome
 * happens on the app side, where it is unit-tested.
 */
@Keep
class EdithShellService : IRemoteShell.Stub {

    /** Required: Shizuku's fallback constructor. */
    @Keep
    constructor()

    /** Shizuku v13 prefers this one. `Context` is package-scoped, not a normal app context. */
    @Keep
    @Suppress("UNUSED_PARAMETER")
    constructor(context: Context)

    /** The reserved destroy transaction: the process must end, or a stale shell outlives the service that owns it. */
    override fun destroy() {
        Log.i(TAG, "destroy")
        System.exit(0)
    }

    override fun run(command: String): String {
        val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
        // Both streams are drained **concurrently** (see `drainStreams`): a child whose stderr fills the pipe blocks in
        // the child, so "stdout first, then stderr" waits on a process that is waiting for us (P3.3 review, Minor 3).
        // The drain also bounds what one reply may accumulate (R-9): past `ShellReply.MAX_REPLY_BYTES` it keeps reading
        // the pipes to EOF - stopping there would deadlock the child - but stops accumulating, and the reply says so.
        val (stdout, stderr, truncated) = drainStreams(process.inputStream, process.errorStream)
        val exit = process.waitFor()
        return ShellReply.encode(exit, stdout, stderr, truncated)
    }

    private companion object {
        const val TAG = "EdithShellService"
    }
}
