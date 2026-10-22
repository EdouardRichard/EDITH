package com.sheen.rokid.phone.debug
import com.sheen.rokid.phone.platform.P3Log
import com.sheen.rokid.phone.storage.unavailable
import com.sheen.rokid.phone.app.EdithApplication

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.sheen.rokid.contracts.AssistantRoute
import com.sheen.rokid.output.ConversationEntry
import com.sheen.rokid.output.ConversationRole
import java.io.File
import java.security.MessageDigest

/**
 * Debug-only F4 assistant-output capture (Phase 7 design §3.4).
 *
 * The F4 judgement reads the assistant's **final output** and, for §2.4's validity check, the ASR final transcript.
 * The phone already holds both on the WAKE route's [com.sheen.rokid.output.ConversationLog]; this receiver copies the last USER
 * and last ASSISTANT entries into two files:
 *
 * ```
 * adb -s <phone> shell am broadcast -a edith.DEBUG_F4_ANSWER -n com.sheen.rokid.phone/.DebugF4Answer
 * ```
 *
 * The files go to `context.getExternalFilesDir(null)` (`f4-user.txt`, `f4-assistant.txt`). If that directory is
 * unavailable, they go to `context.filesDir` instead and the log's `route=internal` tells the campaign to pull them
 * with `adb shell run-as edith.phone cat files/<name>`; `route=external` means a plain `adb pull` of the external
 * directory worked.
 *
 * The log line carries **only** counts and SHA-256. The text itself is the measurement subject and stays in the files,
 * which are pulled into the gitignored `.local/` tree (§1 R6).
 *
 * When no assistant entry exists yet, the assistant file is written empty and `assistantChars=0` is logged, so a turn
 * with no answer is visibly invalid (§2.4 item 6) instead of looking like a capture that never ran.
 *
 * It exists **only in the debug source set** (see `src/debug/AndroidManifest.xml`) and is `exported` so `am broadcast`
 * can reach it; a release build contains no receiver and no output-capture path.
 */
class DebugF4Answer : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_ANSWER) return
        val entries = runCatching {
            (context.applicationContext as? EdithApplication)?.composition?.conversationLog?.entries(AssistantRoute.WAKE)
        }.getOrNull().orEmpty()
        val userText = F4AnswerDump.lastUser(entries)?.text ?: ""
        val assistantText = F4AnswerDump.lastAssistant(entries)?.text ?: ""
        val route = writeBoth(context, userText, assistantText)
        // Counts and hashes only. `route` says which file system holds the text, not what it says.
        P3Log.line(
            "P3_F4ANSWER userChars=${userText.length} assistantChars=${assistantText.length} " +
                "userSha=${F4AnswerDump.sha256(userText)} assistantSha=${F4AnswerDump.sha256(assistantText)} " +
                "route=$route",
        )
    }

    /** Writes both files to the first directory that accepts them and answers the route name the log records. */
    private fun writeBoth(context: Context, userText: String, assistantText: String): String {
        val external = runCatching { context.getExternalFilesDir(null) }.getOrNull()
        if (external != null && write(external, userText, assistantText)) return ROUTE_EXTERNAL
        val internal = runCatching { context.filesDir }.getOrNull()
        if (internal != null && write(internal, userText, assistantText)) return ROUTE_INTERNAL
        return ROUTE_NONE
    }

    private fun write(dir: File, userText: String, assistantText: String): Boolean =
        runCatching {
            dir.mkdirs()
            File(dir, USER_FILE).writeText(userText, Charsets.UTF_8)
            File(dir, ASSISTANT_FILE).writeText(assistantText, Charsets.UTF_8)
        }.isSuccess

    companion object {
        /** The explicit action, so the receiver is reachable without a matching implicit broadcast. */
        const val ACTION_ANSWER = "edith.DEBUG_F4_ANSWER"

        const val USER_FILE = "f4-user.txt"
        const val ASSISTANT_FILE = "f4-assistant.txt"

        /** `route=external` means `getExternalFilesDir(null)` accepted the files. */
        const val ROUTE_EXTERNAL = "external"

        /** `route=internal` means `context.filesDir` did, and the campaign must pull through `run-as`. */
        const val ROUTE_INTERNAL = "internal"

        /** Neither directory could be written; the turn has no output file and is therefore invalid. */
        const val ROUTE_NONE = "NONE"
    }
}

/**
 * The pure selection and hashing rules the answer capture stands on.
 *
 * Split out so the JVM can pin them without a device or an Android `BroadcastReceiver`: "the last assistant entry"
 * and "the exact SHA-256 the log prints" are the two things a campaign script's validity check depends on.
 */
object F4AnswerDump {

    /** The newest ASSISTANT entry on the list, or `null` when the assistant has not answered yet. */
    fun lastAssistant(entries: List<ConversationEntry>): ConversationEntry? =
        entries.lastOrNull { it.role == ConversationRole.ASSISTANT }

    /** The newest USER entry on the list - the ASR final transcript the campaign reads from `f4-user.txt`. */
    fun lastUser(entries: List<ConversationEntry>): ConversationEntry? =
        entries.lastOrNull { it.role == ConversationRole.USER }

    /** Lower-case hex SHA-256 of the UTF-8 bytes of [text]; the only form in which it reaches the log. */
    fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
