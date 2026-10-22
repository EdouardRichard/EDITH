package edith.survey.shizukuprobe

import android.content.Context
import android.os.Process
import android.util.Log
import java.io.File

/**
 * TEMPORARY survey probe (2026-10-16). This class is instantiated by Shizuku **inside its own elevated process**, which
 * is the whole point of the measurement: `Process.myUid()`, `/proc/self/status` and `getenforce` read from here describe
 * the identity a command actually runs under, and that is the one thing this survey must establish by measurement
 * rather than by assumption.
 *
 * Commands run through `sh -c`, the same shape as the delivery app's `EdithShellService`, so the timings are comparable
 * to what the product channel pays.
 */
class ProbeShellService : IProbeShell.Stub {

    /** Shizuku v13 prefers a `(Context)` constructor; the vendor demo annotates both `@Keep`. */
    constructor()

    @Suppress("UNUSED_PARAMETER")
    constructor(context: Context)

    override fun destroy() {
        Log.i(TAG, "destroy")
        System.exit(0)
    }

    override fun run(command: String): String {
        val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
        val out = process.inputStream.bufferedReader().use { it.readText() }
        val err = process.errorStream.bufferedReader().use { it.readText() }
        val exit = process.waitFor()
        return "exit=$exit\n--- stdout ---\n$out\n--- stderr ---\n$err"
    }

    private companion object {
        const val TAG = "ShizukuSurvey"
    }
}

/** One `run()` over the binder, with the caller side's own timing. */
class ProbeShell(private val remote: IProbeShell) {
    fun run(command: String): String = runCatching { remote.run(command) }
        .getOrElse { "THREW ${it.javaClass.simpleName}: ${it.message}" }
}

/** The measurements themselves, kept in one object so the log is written in a handful of lines. */
object Probe {

    private const val TAG = "ShizukuSurvey"

    /** In the app's own external dir, so adb can pull it without root. */
    const val RESULT_DIR = "/sdcard/Android/data/edith.survey.shizukuprobe/files"

    fun log(line: String) = Log.i(TAG, line)

    fun writeResult(name: String, text: String) {
        runCatching {
            val dir = File(RESULT_DIR)
            if (!dir.exists()) dir.mkdirs()
            File(dir, name).writeText(text)
        }.onFailure { log("RESULT_WRITE_FAILED $name ${it.javaClass.simpleName}") }
    }

    /**
     * The identity question, asked from **inside** the UserService process.
     *
     * `id` answers uid/gid/groups, `/proc/self/status` answers the SELinux context and capability sets, `getenforce`
     * answers the enforcing mode. Together these are what "runs as shell" means concretely.
     */
    fun identityBlock(shell: ProbeShell): String = buildString {
        appendLine("== identity of the Shizuku UserService process ==")
        appendLine("Process.myUid()  = ${Process.myUid()}  (2000 = shell, 0 = root, 10xxx = app)")
        appendLine("Process.myPid()  = ${Process.myPid()}")
        appendLine("Process.myUserHandle() = ${Process.myUserHandle()}")
        appendLine("--- id ---")
        appendLine(shell.run("id"))
        appendLine("--- /proc/self/status identity fields ---")
        appendLine(shell.run("grep -E '^(Name|Uid|Gid|Groups|CapEff|CapPrm|CapBnd|Seccomp):' /proc/self/status"))
        appendLine("--- SELinux ---")
        appendLine(shell.run("getenforce"))
        appendLine(shell.run("cat /proc/self/attr/current"))
        appendLine("--- process tree ---")
        appendLine(shell.run("cat /proc/self/stat | cut -d' ' -f1-5"))
    }

    /** How long one `run()` round trip costs: the shell exec plus the binder leg the caller can see. */
    fun latencyBlock(shell: ProbeShell, items: Int): String = buildString {
        appendLine("== latency of one `sh -c` round trip through the UserService binder ==")
        val wallMs = LongArray(items)
        repeat(items) { i ->
            val before = System.nanoTime()
            shell.run("id > /dev/null 2>&1")
            wallMs[i] = (System.nanoTime() - before) / 1_000_000
        }
        appendLine("caller-observed round trip ms (n=$items): ${wallMs.toList()}")
        appendLine("min=${wallMs.min()} median=${wallMs.sorted()[items / 2]} max=${wallMs.max()}")
        appendLine("--- remote environment, for the record ---")
        appendLine(shell.run("uname -a; echo PATH=\$PATH; echo TMPDIR=\$TMPDIR"))
    }

    /**
     * The binder payload ceiling.
     *
     * Everything a command prints comes back inside ONE `String` across ONE binder transaction, so the question is not
     * "can shell print this" but "does the reply survive the binder". Each size is generated *inside* the UserService by
     * `head -c`, so the command string stays tiny and only the reply is large.
     */
    fun payloadBlock(shell: ProbeShell, sizesKb: List<Int>): String = buildString {
        appendLine("== binder reply ceiling: N KB generated remotely, whole reply returned as one String ==")
        for (kb in sizesKb) {
            val bytes = kb * 1024
            val result = shell.run("head -c $bytes /dev/zero | tr '\\0' 'A' | wc -c")
            val ok = result.contains("exit=0") && result.contains("$bytes")
            appendLine("requested ${bytes}B -> ${if (ok) "OK" else "FAILED"} | reply=${result.replace("\n", " ").take(200)}")
        }
        appendLine("--- the real large outputs this survey cares about ---")
        appendLine(shell.run("screencap -p /sdcard/tmp-probe-shot.png; ls -l /sdcard/tmp-probe-shot.png"))
        appendLine(shell.run("base64 /sdcard/tmp-probe-shot.png | wc -c"))
        appendLine(shell.run("uiautomator dump /sdcard/tmp-probe-ui.xml >/dev/null 2>&1; wc -c /sdcard/tmp-probe-ui.xml"))
    }

    /**
     * The same question, minus the one confound the block above cannot remove: `uiautomator dump` refuses to read the
     * window of the process that is driving it ("null root node"), so a failure there says nothing about *other* apps.
     * This variant puts a genuinely foreign window in front first, and reports the dump's cost as node count + XML
     * size. Only those two numbers leave the device — the XML itself is on-screen text and is never quoted.
     */
    fun uiReadForegroundBlock(shell: ProbeShell): String = buildString {
        appendLine("== cross-app UI read, with a foreign package in the foreground ==")
        appendLine(shell.run("am start -W -n com.android.settings/.Settings 2>&1 | tail -3"))
        appendLine("--- current focus ---")
        appendLine(shell.run("dumpsys window | grep mCurrentFocus"))
        appendLine("--- uiautomator dump of the foreign window ---")
        appendLine(shell.run(dumpStatsCommand("tmp-probe-fg.xml")))
    }

    /** Timed dump: node count + bytes, and never the XML content. */
    private fun dumpStatsCommand(name: String): String {
        val path = "/sdcard/Android/data/edith.survey.shizukuprobe/files/$name"
        // `grep -c` counts *lines* and this whole XML is one line, so the node count needs `grep -o` + `wc -l`
        // (run 2 reported `nodes=1` for a 12 590 B / 39-node tree because of exactly that).
        return "uiautomator dump $path >/dev/null 2>&1; " +
            "echo \"dump_nodes=\$(grep -o '<node ' $path 2>/dev/null | wc -l)\"; " +
            "echo \"dump_bytes=\$(wc -c < $path 2>/dev/null)\"; " +
            "rm -f $path"
    }

    /** Can the in-device shell UID read another app's screen, the way the PC's `adb shell` can? */
    fun uiReadBlock(shell: ProbeShell): String = buildString {
        appendLine("== cross-app UI read from the in-device shell UID ==")
        appendLine(shell.run("uiautomator dump /sdcard/tmp-probe-ui.xml 2>&1 | tail -2"))
        appendLine(shell.run("grep -c '<node ' /sdcard/tmp-probe-ui.xml"))
        // D53: the dump is on-screen text. Report its *size* only — never quote the XML itself (this line used to
        // `head -c 300` it, which would have written whatever app was in the foreground into this evidence file).
        appendLine(shell.run("wc -c < /sdcard/tmp-probe-ui.xml"))
    }
}
