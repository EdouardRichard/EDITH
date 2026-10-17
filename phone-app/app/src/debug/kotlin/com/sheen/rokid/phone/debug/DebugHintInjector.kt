package com.sheen.rokid.phone.debug
import com.sheen.rokid.phone.app.EdithApplication

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Debug-only hint-length injection instrument (P3.2 Stage 0's capacity probe, T-P2.1).
 *
 * The glasses render a hint into a fixed region and a plain `TextView` with `maxLines` clips whatever does not fit -
 * **silently** until Task 1 added the `hint rendered lines=… clipped=…` line. Answering "how many characters does this
 * panel actually show, and does 120 overflow?" therefore needs hints of a **known** length pushed through the real
 * chain (phone arbiter → CXR → glasses view), not a mocked renderer.
 *
 * **Two payloads, two questions (P3.2-CLIP).** The capacity probe needs a known length, which is the synthetic `测`
 * filler. Judging the auto-scroll and legibility at wearing distance needs text a person can read: the owner's verdict
 * on the filler was "都是测测测，一样的字，辨识度不搞", so a caller-supplied text is accepted too. Both are bounded by
 * the wire's own limit.
 *
 * The filler is synthetic on purpose - a real transcript or note would put private content on the panel and into a
 * screenshot. A **supplied** text comes from the host shell that is already driving the device, and nothing but counts
 * is ever logged.
 *
 * Driven from a host shell (the phone serial must be given, or the glasses get the APK):
 *
 * ```
 * adb -s <phone> shell am broadcast -a edith.DEBUG_HINT -n com.sheen.rokid.phone/.DebugHintInjector --ei chars 120
 * adb -s <phone> shell am broadcast -a edith.DEBUG_HINT -n com.sheen.rokid.phone/.DebugHintInjector --es text '今晚八点前把周报发给张工'
 * ```
 *
 * It exists **only in the debug source set** (see `src/debug/AndroidManifest.xml`) and is `exported` so `am broadcast`
 * can reach it; a release build contains no receiver and no injection path.
 */
class DebugHintInjector : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_HINT) return
        val application = context.applicationContext as? EdithApplication
        val sender = application?.composition?.diagnosticHintTextSender
        val text = payload(intent.getStringExtra(EXTRA_TEXT), intent.getIntExtra(EXTRA_CHARS, 0))
        val accepted = when {
            sender == null -> false
            text.isEmpty() -> false
            else -> runCatching { sender.invoke(text) }.getOrDefault(false)
        }
        // Counts only: never the text, and never whether a layout later clipped it (the glasses line owns that).
        android.util.Log.i("EdithP3", "P3_HINT_INJECT chars=${text.length} accepted=$accepted")
    }

    companion object {
        const val ACTION_HINT = "edith.DEBUG_HINT"
        const val EXTRA_CHARS = "chars"
        const val EXTRA_TEXT = "text"

        /**
         * The wire refuses more than this anyway (`HintWireCodec.MAX_TEXT_CODEPOINTS`), so asking for more would only
         * measure a refusal. Kept as our own bound rather than reaching into `output-runtime` for a constant whose
         * meaning is the wire's, but **it must equal that bound** and `DebugHintInjectorTest` asserts it does.
         *
         * It was **120** until 2026-10-14, and that was the whole of the "探针测不到 120 以上" note in
         * `2026-10-09-p3-2-hint-capacity.md:36`: the probe refused the length itself, so 120 was never the chain's
         * limit and every value above it went unmeasured. The owner's I3 ruling (do not truncate; let the glasses
         * scroll) raised the product bound to 400, and this instrument has to follow it or the next measurement
         * repeats the same mistake.
         */
        const val MAX_CHARS = 400

        /**
         * What to inject: a supplied text when there is one, otherwise one long point of `chars` filler codepoints.
         *
         * Pure so the rule is testable on the JVM without a device or a `BroadcastReceiver`.
         */
        fun payload(text: String?, chars: Int): String {
            val supplied = text?.takeIf { it.isNotBlank() }
            if (supplied != null) return supplied.take(MAX_CHARS)
            if (chars <= 0) return ""
            return "测".repeat(chars.coerceAtMost(MAX_CHARS))
        }
    }
}
