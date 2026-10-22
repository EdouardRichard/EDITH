package com.sheen.rokid.phone.platform

/**
 * The single sink for the phone-side `P3_` lines Task 15's acceptance reads out of logcat.
 *
 * One channel rather than a logger call at each site, because every line has to be capturable by a JVM test and the
 * fields have to be greppable on a device with one tag. The production sink writes to logcat under [TAG]; a test
 * swaps [sink] for a collector, so a line can be asserted on without a device or a stdout scrape.
 *
 * The lines this carries are observability only and are deliberately narrow: a counter, an epoch or a word and a
 * confidence - never a PCM sample, a transcript, a hint's text, screen content or a credential (see the P3.1 brief's
 * privacy rules). Nothing here formats a payload; each call site passes only the fields the brief names.
 */
internal object P3Log {
    /** The logcat tag every phone-side `P3_` line is written under (`EdithP3 <P3_FIELD ...>`). */
    const val TAG = "EdithP3"

    /**
     * Where one line goes.
     *
     * Production writes to logcat. The default body touches `android.util.Log` only when it actually runs, so a JVM
     * test that installs its own collector never needs an Android runtime. `@Volatile` because the audio loop's tick
     * and the transport's offer thread both reach it.
     */
    @Volatile
    var sink: (String) -> Unit = { line -> android.util.Log.i(TAG, line) }

    /** Writes one observability line, already restricted by the call site to the brief's fields. */
    fun line(message: String) {
        sink(message)
    }
}
