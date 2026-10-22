package com.sheen.rokid.phone.debug

import android.app.Activity
import android.app.KeyguardManager
import android.os.Bundle
import android.util.Log
import com.sheen.rokid.phone.app.EdithApplication
import com.sheen.rokid.phone.app.HostCompositionRoot
import com.sheen.rokid.phone.actions.shizuku.ShizukuUserServiceShell
import java.util.concurrent.TimeUnit

/**
 * Debug-only 2026-10-20 probe: can the **production** Shizuku shell execute a command while the phone is locked?
 *
 * The debug broadcast instrument (`DebugShellCapProbe`) cannot answer this on the target MIUI build: an explicit
 * `am broadcast` is left pending in the system queue, so a missing line would be ambiguous. A shell-started Activity has
 * no broadcast dependency; it enters the App process, uses the same production `privilegedShellForProbe`, and logs only
 * the lock state, exit code, byte counts, truncation and the channel's answer path - never the command's stdout.
 *
 * ```
 * adb -s 2696813b shell am start -n com.sheen.rokid.phone/.debug.ShizukuLockProbeActivity
 * adb -s 2696813b logcat -d -v time | Select-String P3_LOCKPROBE
 * ```
 *
 * It lives in the debug source set and is exported only so the host shell can start it; a release APK contains neither
 * this class nor the manifest entry.
 */
class ShizukuLockProbeActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val locked = (getSystemService(KEYGUARD_SERVICE) as KeyguardManager).isKeyguardLocked
        Thread({
            try {
                val root = (application as EdithApplication).composition
                val shell = awaitShell(root)
                if (shell == null) {
                    report("state=NO_SHELL locked=$locked")
                    return@Thread
                }
                val result = shell.run("id")
                report(
                    "state=RESULT locked=$locked ready=${shell.ready()} exit=${result.exitCode} " +
                        "stdoutBytes=${result.stdout.toByteArray(Charsets.UTF_8).size} " +
                        "stderrBytes=${result.stderr.toByteArray(Charsets.UTF_8).size} " +
                        "truncated=${result.truncated} answer=${shell.lastAnswerPath}",
                )
            } catch (failure: Throwable) {
                report("state=THREW locked=$locked throw=${failure.javaClass.simpleName}")
            } finally {
                runOnUiThread { finish() }
            }
        }, "edith-shizuku-lock-probe").apply { isDaemon = true }.start()
    }

    private fun awaitShell(root: HostCompositionRoot): ShizukuUserServiceShell? {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120)
        while (System.nanoTime() < deadline) {
            val shell = root.privilegedShellForProbe
            if (shell != null && shell.ready()) return shell
            Thread.sleep(250)
        }
        return root.privilegedShellForProbe
    }

    private fun report(line: String) = Log.i(TAG, "P3_LOCKPROBE $line")

    private companion object {
        const val TAG = "EdithLockProbe"
    }
}
