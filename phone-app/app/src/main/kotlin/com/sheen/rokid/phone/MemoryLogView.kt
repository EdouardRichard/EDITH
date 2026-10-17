package com.sheen.rokid.phone

import com.sheen.rokid.plugin.StorageHandle
import com.sheen.rokid.phone.plugin.smarthelper.memory.MemoryEntry
import com.sheen.rokid.phone.plugin.smarthelper.memory.MemoryKind
import com.sheen.rokid.phone.plugin.smarthelper.memory.MemoryLog
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** One line of the memory page: what the user sees, and the id the delete verb needs. */
data class MemoryLine(val id: String, val text: String, val kind: String, val stamp: String)

/**
 * P3.4 §3.4: the session page's view of long-term memory - 可查看、可删除 (spec §6.5).
 *
 * A **reader plus two delete verbs**, not a second store: the document belongs to the plugin ([MemoryLog]) and this
 * class renders the fields that are in it and nothing else. A line therefore has no place to put an id, a use count or
 * a source, and a reader cannot be shown one.
 *
 * The key is the plugin's own constant ([MemoryLog.KEY]), never a second literal - a page reading a different key would
 * show "暂无记忆" while the store was full, which reads exactly like "nothing was ever remembered".
 *
 * The text shown here **is** the stored sentence, which is the whole point of the feature; it left the device only on a
 * request the session's upload switch had already allowed, and it never reaches a log.
 */
class MemoryLogView(private val storage: StorageHandle) {

    private val log = MemoryLog(storage = storage, log = {})

    /** The newest first, at most [limit] of them. A document this build cannot read renders as empty. */
    fun lines(limit: Int = DEFAULT_LIMIT): List<MemoryLine> =
        log.entries().asReversed().take(limit).map(::line)

    /** Exactly what the page shows for one entry. Kept separate so a test can pin the fields it may contain. */
    fun render(line: MemoryLine): String = "${line.stamp} | ${line.kind} | ${line.text}"

    /** The user's "forget this" - the entry goes out of the document, not merely off this page. */
    fun delete(id: String) = log.delete(id)

    /** The user's "forget everything" - the document itself goes. */
    fun clear() = log.clear()

    private fun line(entry: MemoryEntry) =
        MemoryLine(entry.id, entry.text, kindLabel(entry.kind), stamp(entry.createdAtMillis))

    private fun kindLabel(kind: MemoryKind): String = when (kind) {
        MemoryKind.FACT -> "事实"
        MemoryKind.PREFERENCE -> "偏好"
        MemoryKind.ROUTINE -> "常做任务"
    }

    private fun stamp(millis: Long): String =
        Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).format(STAMP)

    companion object {
        /** The plugin's own key. One literal, so the page and the store cannot drift apart. */
        const val KEY = MemoryLog.KEY

        /** How many lines the page shows unless it is asked for another number. */
        const val DEFAULT_LIMIT = 50

        private val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm")
    }
}
