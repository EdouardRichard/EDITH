package com.sheen.rokid.phone
import com.sheen.rokid.phone.platform.P3Log

/**
 * Test-local collector for the phone-side `P3_` lines.
 *
 * Kept out of `P3Log` itself (the brief's rule: test-only code belongs in test sources), so the production object
 * stays a single sink and a test can capture a line without touching the logcat stub. The previous sink is always
 * restored, including when the block throws, so one test cannot leak its collector into the next.
 */
internal fun <T> withP3Sink(block: (MutableList<String>) -> T): T {
    val lines = mutableListOf<String>()
    val previous = P3Log.sink
    P3Log.sink = { lines += it }
    return try {
        block(lines)
    } finally {
        P3Log.sink = previous
    }
}
