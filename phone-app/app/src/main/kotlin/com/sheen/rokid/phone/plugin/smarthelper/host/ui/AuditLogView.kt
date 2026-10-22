package com.sheen.rokid.phone.plugin.smarthelper.host.ui
import kotlinx.serialization.json.JsonObject

import com.sheen.rokid.plugin.StorageHandle
import com.sheen.rokid.phone.plugin.smarthelper.audit.AuditTrail
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The settings page's view of the audit trail (spec §17.5's 可查可删).
 *
 * A **reader**, not a second store: the document belongs to the plugin ([AuditTrail]), and this class renders the fields
 * that are in it and nothing else. That is what makes "the audit cannot contain a transcript, a screen line or a phone
 * number" a property of the schema rather than of this rendering: there is no such field to show.
 *
 * The key is the plugin's own constant ([AuditTrail.KEY]), never a second literal - a page reading a different key would
 * show an empty trail and look like "nothing was ever recorded".
 */
class AuditLogView(private val storage: StorageHandle) {

    /** The newest first, at most [limit] of them. A document this build cannot read renders as empty. */
    fun lines(limit: Int = DEFAULT_LIMIT): List<String> = lines().asReversed().take(limit)

    /** The user's "delete the audit trail": removes the document, leaving every other key alone. */
    fun clear() {
        runCatching { storage.delete(KEY) }
    }

    private fun lines(): List<String> = decode().mapNotNull(::render)

    private fun decode(): List<JsonObject> {
        val bytes = runCatching { storage.read(KEY) }.getOrNull() ?: return emptyList()
        return runCatching {
            val root = Json.parseToJsonElement(String(bytes, Charsets.UTF_8)) as? JsonObject ?: return emptyList()
            if (root["version"]?.jsonPrimitive?.intOrNull != VERSION) return emptyList()
            val array = root["lines"]?.jsonArray ?: return emptyList()
            array.mapNotNull { it as? JsonObject }
        }.getOrElse { emptyList() }
    }

    /**
     * One page line: `时间 | 动作 | 风险 | 裁决 | 结果 | 确认来源 | 目标摘要`.
     *
     * A line without an action or an outcome is not rendered at all - a partially written record is not something to
     * guess at - and an absent field prints as `-` rather than disappearing, so a missing risk level is visible instead
     * of looking like a short line.
     */
    private fun render(line: JsonObject): String? {
        val action = line["action"]?.jsonPrimitive?.contentOrNull ?: return null
        val outcome = line["outcome"]?.jsonPrimitive?.contentOrNull ?: return null
        val fields = listOf(
            stamp(line["timestampMillis"]?.jsonPrimitive?.longOrNull),
            action,
            line["risk"]?.jsonPrimitive?.contentOrNull ?: "-",
            line["decision"]?.jsonPrimitive?.contentOrNull ?: "-",
            outcome,
            line["confirmationSource"]?.jsonPrimitive?.contentOrNull ?: "-",
        )
        val summary = line["summary"]?.jsonPrimitive?.contentOrNull.orEmpty()
        return (if (summary.isBlank()) fields else fields + summary).joinToString(SEPARATOR)
    }

    private fun stamp(millis: Long?): String =
        millis?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).format(STAMP) } ?: "-"

    companion object {
        /** The plugin's own key. One literal, pinned by a test so the two halves cannot drift apart silently. */
        const val KEY = AuditTrail.KEY

        /** How many lines the page shows unless it is asked for another number. */
        const val DEFAULT_LIMIT = 50

        private const val VERSION = 1
        private const val SEPARATOR = " | "
        private val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss")
    }
}
