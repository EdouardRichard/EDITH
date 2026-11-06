package com.sheen.rokid.phone.plugin.smarthelper.trace

import java.io.File

/**
 * The App-private append-only sink for [ModelTrace] lines: `<filesDir>/edith-model-trace.jsonl`, UTF-8.
 *
 * Deliberately tiny and synchronous: an agent turn is not a hot path, one run writes one line, and a debug trace has
 * no business owning a worker, a queue or a flush policy. The factory is public only so the App's wiring can inject it
 * behind `BuildConfig.DEBUG`; a release build passes the plugin's no-op default and never constructs this.
 *
 * A write failure is swallowed on purpose: tracing is an observation, and an observation must never fail the turn it
 * describes. A raw newline handed in by a caller is escaped to a literal `\n`, so one record always occupies exactly
 * one line even if a future caller forgets [ModelTrace]'s guarantee.
 */
object ModelTraceFile {

    /** The file `adb exec-out run-as <app> cat files/<name>` pulls. */
    const val FILE_NAME = "edith-model-trace.jsonl"

    /** One process-wide lock: turns may end on more than one thread, and appends must not interleave. */
    private val lock = Any()

    /** Returns the writer the plugin calls once per finished run; a null directory degrades to the no-op writer. */
    fun writer(filesDir: File?): (String) -> Unit {
        val dir = filesDir ?: return {}
        return { line ->
            runCatching {
                synchronized(lock) {
                    if (!dir.exists()) dir.mkdirs()
                    File(dir, FILE_NAME).appendText(singleLine(line) + "\n", Charsets.UTF_8)
                }
            }
        }
    }

    private fun singleLine(line: String): String =
        line.replace("\r\n", "\\n").replace("\r", "\\n").replace("\n", "\\n")
}
