package edith.phone

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
 * The text is **synthetic only** (`测` repeated `chars` times): this instrument exists to measure layout capacity, and a
 * real transcript or note would put private content on the panel and into a screenshot. Nothing but counts is logged.
 *
 * Driven from a host shell (the phone serial must be given, or the glasses get the APK):
 *
 * ```
 * adb -s <phone> shell am broadcast -a edith.DEBUG_HINT -n edith.phone/.DebugHintInjector --ei chars 120
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
        val chars = intent.getIntExtra(EXTRA_CHARS, 0)
        val text = synthetic(chars)
        val accepted = when {
            sender == null -> false
            text.isEmpty() -> false
            else -> runCatching { sender.invoke(text) }.getOrDefault(false)
        }
        // Counts only: never the text, and never whether a layout later clipped it (the glasses line owns that).
        android.util.Log.i("EdithP3", "P3_HINT_INJECT chars=${text.length} accepted=$accepted")
    }

    /** The measured payload: one long point of `chars` codepoints, so the count on the panel is the count requested. */
    private fun synthetic(chars: Int): String {
        if (chars <= 0) return ""
        val bounded = chars.coerceAtMost(MAX_CHARS)
        return "测".repeat(bounded)
    }

    private companion object {
        const val ACTION_HINT = "edith.DEBUG_HINT"
        const val EXTRA_CHARS = "chars"

        /**
         * The wire refuses more than this anyway (`HintWireCodec.MAX_TEXT_CODEPOINTS`), so asking for more would only
         * measure a refusal. Kept as our own bound rather than reaching into `output-runtime` for a constant whose
         * meaning is the wire's.
         */
        const val MAX_CHARS = 120
    }
}
