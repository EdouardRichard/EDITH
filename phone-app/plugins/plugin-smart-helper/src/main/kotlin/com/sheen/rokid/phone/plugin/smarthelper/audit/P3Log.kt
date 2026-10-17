package com.sheen.rokid.phone.plugin.smarthelper.audit

/**
 * The single sink for the smart plugin's `P3_` lines Task 15's acceptance reads out of logcat.
 *
 * `plugins-smart` is a pure-JVM module with no Android dependency, so the production sink writes to `System.out`
 * (which Android also routes into logcat, under the platform's `System.out` tag) with the module tag [TAG] in front
 * of the line. A JVM test swaps [sink] for a collector, so a line is asserted on directly rather than by scraping
 * stdout.
 *
 * Every line is observability only and is deliberately narrow: a turn id, a revision number, a character count, an
 * outcome, a step count or a tool's safe code - never a transcript, a hint's text, screen/notification content or a
 * credential (see the P3.1 brief's privacy rules). No call site formats a text payload.
 */
internal object P3Log {
    /** The module tag that precedes every line in the production output (`EdithSmart <P3_FIELD ...>`). */
    const val TAG = "EdithSmart"

    /**
     * Where one line goes. Production prints `"$TAG $line"`; a JVM test installs its own collector. `@Volatile`
     * because a plugin turn may run on a host executor thread while a test reads the sink.
     */
    @Volatile
    var sink: (String) -> Unit = { line -> System.out.println("$TAG $line") }

    /** Writes one observability line, already restricted by the call site to the brief's fields. */
    fun line(message: String) {
        sink(message)
    }
}
