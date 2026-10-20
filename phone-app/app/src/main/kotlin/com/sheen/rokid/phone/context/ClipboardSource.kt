package com.sheen.rokid.phone.context

import android.content.ClipboardManager
import android.content.Context

/**
 * The clipboard fact (edith-0.3 Phase 2 Task 4; spec §7.2/§3.5, D179).
 *
 * A clipboard may only be read while the app is in the foreground / is the default IME; Android 10+ refuses the read
 * from the background. That refusal is reported as an [Unavailable] with its own reason, and the three outcomes stay
 * distinguishable: text, "there is no text" and "the platform refused this read".
 */
internal sealed interface ClipboardRead {
    data class Text(val value: String) : ClipboardRead

    data class Unavailable(val reason: String) : ClipboardRead
}

/**
 * The (current) clipboard text and its honest absences, over an injectable platform read (Task 4) and an injectable
 * foreground predicate (Task 6, R7).
 *
 * The class is the JVM-testable seam: production passes a lambda over [ClipboardManager] and a lambda over the host's
 * foreground fact, and a test drives both without Android. No permission is added and no read is attempted outside what
 * the window state allows.
 *
 * **R7 — background honesty.** [foreground] answers whether this host is currently in front. When it returns `false`
 * the platform read is **not attempted at all** and the answer is `background_restricted`: on Android 10+ a background
 * read can hand back `null`, and reporting that as [REASON_EMPTY] would silently claim "the clipboard is empty" when the
 * host never looked. `true` or `null` (the feature was not supplied, or the window state is unknown) keeps the old
 * "try the read" behavior. A throwing predicate is treated as `null`, so a broken window probe degrades to an attempt
 * instead of a crash. A refused read (`SecurityException`/`IllegalStateException`, or any unexpected platform failure)
 * is `background_restricted` - the platform refused this read - and only `null`/blank content is `empty`.
 */
internal class ClipboardSource(
    read: () -> CharSequence?,
    foreground: () -> Boolean? = { null },
) {

    // Stored under distinct names: the class's own `read()` would otherwise shadow the lambda at every call site and
    // turn the function into an infinite recursion (found by the RED/GREEN compile of Task 4).
    private val readClipboard = read
    private val foregroundState = foreground

    fun read(): ClipboardRead {
        // A known-background host never touches the platform clipboard (R7).
        if (runCatching { foregroundState() }.getOrNull() == false) {
            return ClipboardRead.Unavailable(REASON_BACKGROUND_RESTRICTED)
        }
        val value = try {
            readClipboard()
        } catch (_: Throwable) {
            // A refused or failed read is never "there is no text": the platform did not answer.
            return ClipboardRead.Unavailable(REASON_BACKGROUND_RESTRICTED)
        }
        if (value == null || value.isBlank()) return ClipboardRead.Unavailable(REASON_EMPTY)
        return ClipboardRead.Text(value.toString())
    }

    companion object {
        /** Background clipboard access is restricted by the platform; a read from there is expected to fail. */
        const val REASON_BACKGROUND_RESTRICTED = "background_restricted"

        /** There is no text on the clipboard (never used for a refused or skipped read). */
        const val REASON_EMPTY = "empty"

        /**
         * The platform half: the current primary clip's first item text, read through `ClipboardManager` only.
         *
         * The manager and the clip are resolved on each read (the clipboard changes underneath the app), and the
         * read is left unchanged so the platform's own background restriction applies. Null means "no text on the
         * clipboard"; a refused read is thrown by the platform and mapped by [ClipboardSource.read]. [foreground] is
         * optional and defaults to "unknown", keeping every existing construction source-compatible.
         */
        fun android(context: Context, foreground: () -> Boolean? = { null }): ClipboardSource {
            val application = context.applicationContext ?: context
            val read: () -> CharSequence? = {
                // A missing clipboard service is not "the clipboard is empty": it is a read that never happened, and
                // `read()` reports it as a refusal instead of fabricating an empty clip.
                val manager = application.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    ?: throw UnsupportedOperationException("clipboard service unavailable")
                val clip = manager.primaryClip
                if (clip == null || clip.itemCount == 0) null else clip.getItemAt(0).text
            }
            return ClipboardSource(read, foreground)
        }
    }
}
