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
 * The (current) clipboard text and its honest absences, over an injectable platform read.
 *
 * The class is the JVM-testable seam: production passes a lambda over [ClipboardManager], and a test drives the mapping
 * without Android. No permission is added and no read is attempted outside what the window state allows - a refused
 * read is `background_restricted`, not an empty clipboard.
 *
 * [read] catches the platform's refusal (`SecurityException`/`IllegalStateException`) and any unexpected failure, so a
 * dead binder or an OEM refusing the call degrades to an unavailability instead of crashing the perception read.
 */
internal class ClipboardSource(read: () -> CharSequence?) {

    // Stored under a distinct name: the class's own `read()` would otherwise shadow the lambda at every call site and
    // turn the function into an infinite recursion (found by the RED/GREEN compile).
    private val readClipboard = read

    fun read(): ClipboardRead {
        val value = try {
            readClipboard()
        } catch (_: SecurityException) {
            return ClipboardRead.Unavailable(REASON_BACKGROUND_RESTRICTED)
        } catch (_: IllegalStateException) {
            return ClipboardRead.Unavailable(REASON_BACKGROUND_RESTRICTED)
        } catch (_: Throwable) {
            return ClipboardRead.Unavailable(REASON_READ_FAILED)
        }
        if (value == null || value.isBlank()) return ClipboardRead.Unavailable(REASON_EMPTY)
        return ClipboardRead.Text(value.toString())
    }

    companion object {
        /** Background clipboard access is restricted by the platform; a read from there is expected to fail. */
        const val REASON_BACKGROUND_RESTRICTED = "background_restricted"

        /** There is no text on the clipboard (never used for a refused read). */
        const val REASON_EMPTY = "empty"

        /** The platform produced an error this source does not model; reported honestly instead of as "empty". */
        const val REASON_READ_FAILED = "read_failed"

        /**
         * The platform half: the current primary clip's first item text, read through `ClipboardManager` only.
         *
         * The manager and the clip are resolved on each read (the clipboard changes underneath the app), and the
         * read is left unchanged so the platform's own background restriction applies. Null means "no text on the
         * clipboard"; a refused read is thrown by the platform and mapped by [ClipboardSource.read].
         */
        fun android(context: Context): ClipboardSource {
            val application = context.applicationContext ?: context
            val read: () -> CharSequence? = {
                // A missing clipboard service is not "the clipboard is empty": it is a read that never happened, and
                // `read()` reports it as `read_failed` instead of fabricating an empty clip.
                val manager = application.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    ?: throw UnsupportedOperationException("clipboard service unavailable")
                val clip = manager.primaryClip
                if (clip == null || clip.itemCount == 0) null else clip.getItemAt(0).text
            }
            return ClipboardSource(read)
        }
    }
}
