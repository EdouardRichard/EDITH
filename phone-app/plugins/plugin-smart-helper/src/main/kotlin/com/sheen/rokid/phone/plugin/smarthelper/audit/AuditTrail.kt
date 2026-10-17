package com.sheen.rokid.phone.plugin.smarthelper.audit
import com.sheen.rokid.phone.plugin.smarthelper.SmartAssistantPlugin

import com.sheen.rokid.agent.AuditEntry
import com.sheen.rokid.agent.AuditSink
import com.sheen.rokid.agent.BoundedAuditLog
import com.sheen.rokid.agent.ConfirmationSource
import com.sheen.rokid.plugin.StorageHandle
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * The plugin's local audit trail (spec §17.5): P3.1's bounded in-memory [BoundedAuditLog] **plus** one bounded JSON
 * document in the plugin's own encrypted namespace.
 *
 * Two properties are the point:
 * - **nothing but the summary it was handed is written.** The document carries the eight structured fields and no
 *   arguments: the redaction rule lives in the loop (`ActionDescriptor.auditTargetArgument`), and this class never sees
 *   an argument to leak. A `dial` line therefore has an empty summary and no number anywhere in the file;
 * - **bounded on both sides.** The ring keeps [capacity] lines and the document is re-encoded from that same ring, so a
 *   long session cannot grow the file without limit and the file can never hold a line the ring dropped.
 *
 * The file is the durable record; the ring is the live view. A line replayed out of the file at [attach] is re-stamped
 * by the ring's clock - `AuditEntry.atNanos` is monotonic and per-process, so it is not a time that survives a restart.
 * The real time is the document's own `timestampMillis`, which is what the settings page shows. The plugin's namespace
 * is encrypted by the App's blob store, so this is the "local encrypted" half of §7.4 rather than a new store.
 *
 * Every storage failure is swallowed **and** logged without content: an audit trail that cannot be written must not take
 * the passive loop down with it, and must not claim it recorded anything.
 */
class AuditTrail(
    storage: StorageHandle?,
    private val memory: BoundedAuditLog,
    private val capacity: Int = PERSISTED_CAPACITY,
    private val wallClock: () -> Long = System::currentTimeMillis,
) : AuditSink {

    /** One line as the document stores it: the ring's fields, plus the wall clock a restart cannot reconstruct. */
    private data class Line(
        val timestampMillis: Long,
        val turnId: String,
        val action: String,
        val summary: String,
        val outcome: String,
        val scope: String?,
        val risk: String?,
        val decision: String?,
        val confirmationSource: ConfirmationSource?,
    )

    private val lines = ArrayDeque<Line>()
    private var storage: StorageHandle? = storage

    /**
     * How many lines the last **successful** write put in the document, or 0 when there is no document we wrote.
     *
     * This is what lets [record] tell "the file is still ours" from "the user deleted it": the settings page removes the
     * document directly (it is the same namespace, one process), so the ring has to notice and follow the file rather
     * than re-create what was deleted.
     */
    private var persistedLines = 0

    init {
        require(capacity >= 1) { "AuditTrail capacity must be at least 1, was $capacity" }
        seed()
    }

    /**
     * Points the trail at the namespace this session granted it and reads the existing document.
     *
     * Called from `activate`, and production hands a **fresh** scoped storage wrapper on every activation - so seeding
     * must be safe against that: it **replaces** the live ring with the document's contents instead of adding to it.
     * Keying on the storage instance was not enough (a new wrapper is a new instance over the same document), and the
     * append it guarded doubled the document on every session until the cap filled with copies of itself.
     */
    fun attach(storage: StorageHandle?) {
        this.storage = storage
        seed()
    }

    override fun record(
        turnId: String,
        action: String,
        summary: String,
        outcome: String,
        scope: String?,
        risk: String?,
        decision: String?,
        confirmationSource: ConfirmationSource?,
    ) {
        // The durable record is the file, so if the file we last wrote is gone the user cleared the trail and the ring
        // must go with it. Without this the next write re-encodes the ring and resurrects every deleted line - §17.5's
        // 可删 would be a lie in the same App run, over exactly the identifiers the user asked to forget.
        if (persistedLines > 0 && documentWasRemoved()) {
            lines.clear()
            memory.clear()
            persistedLines = 0
        }
        memory.record(turnId, action, summary, outcome, scope, risk, decision, confirmationSource)
        lines += Line(wallClock(), turnId, action, summary, outcome, scope, risk, decision, confirmationSource)
        while (lines.size > capacity) lines.removeFirst()
        persist()
    }

    /** The live ring, oldest first, as a defensive copy. */
    fun entries(): List<AuditEntry> = memory.entries()

    /** The user's "delete the audit trail": clears the ring, drops the document, and is honest when it cannot. */
    fun clear() {
        memory.clear()
        lines.clear()
        persistedLines = 0
        runCatching { storage?.delete(KEY) }
            .onFailure { P3Log.line("P3_AUDIT clear=failed reason=${it.javaClass.simpleName}") }
    }

    /** True when there is a namespace and it no longer holds the document. Never throws: unreadable counts as present. */
    private fun documentWasRemoved(): Boolean {
        val target = storage ?: return false
        return runCatching { target.read(KEY) }.getOrNull() == null
    }

    /** The document's contents **replace** the ring; the file is the durable record and the ring follows it. */
    private fun seed() {
        val target = storage ?: return
        val stored = decode(target).takeLast(capacity)
        lines.clear()
        memory.clear()
        stored.forEach { line ->
            memory.record(
                line.turnId, line.action, line.summary, line.outcome,
                line.scope, line.risk, line.decision, line.confirmationSource,
            )
            lines += line
        }
        persistedLines = stored.size
    }

    /** Best effort on purpose: a write failure is logged by its exception type, never by what it was writing. */
    private fun persist() {
        val target = storage ?: return
        runCatching { target.write(KEY, encode(lines.toList())) }
            .onSuccess { persistedLines = lines.size }
            .onFailure { P3Log.line("P3_AUDIT persistence=failed reason=${it.javaClass.simpleName}") }
    }

    private fun encode(all: List<Line>): ByteArray = buildJsonObject {
        put("version", VERSION)
        put("lines", buildJsonArray {
            all.forEach { line ->
                add(
                    buildJsonObject {
                        put("timestampMillis", line.timestampMillis)
                        put("turnId", line.turnId)
                        put("action", line.action)
                        put("summary", line.summary)
                        put("outcome", line.outcome)
                        line.scope?.let { put("scope", it) }
                        line.risk?.let { put("risk", it) }
                        line.decision?.let { put("decision", it) }
                        line.confirmationSource?.let { put("confirmationSource", it.name) }
                    },
                )
            }
        })
    }.toString().toByteArray(Charsets.UTF_8)

    /**
     * A document this build cannot read is treated as **empty**, never as a reason to throw: a corrupt blob must leave a
     * working, record-able trail rather than take the plugin's activation down (the same rule the settings store uses).
     */
    private fun decode(target: StorageHandle?): List<Line> {
        val bytes = runCatching { target?.read(KEY) }.getOrNull() ?: return emptyList()
        return runCatching {
            val root = Json.parseToJsonElement(String(bytes, Charsets.UTF_8)) as? JsonObject ?: return emptyList()
            if (root["version"]?.jsonPrimitive?.intOrNull != VERSION) return emptyList()
            val array = root["lines"]?.jsonArray ?: return emptyList()
            array.mapNotNull { element -> (element as? JsonObject)?.let(::lineFrom) }
        }.getOrElse { emptyList() }
    }

    private fun lineFrom(json: JsonObject): Line? {
        val turnId = json["turnId"]?.jsonPrimitive?.contentOrNull ?: return null
        val action = json["action"]?.jsonPrimitive?.contentOrNull ?: return null
        val outcome = json["outcome"]?.jsonPrimitive?.contentOrNull ?: return null
        return Line(
            timestampMillis = json["timestampMillis"]?.jsonPrimitive?.longOrNull ?: 0L,
            turnId = turnId,
            action = action,
            summary = json["summary"]?.jsonPrimitive?.contentOrNull ?: "",
            outcome = outcome,
            scope = json["scope"]?.jsonPrimitive?.contentOrNull,
            risk = json["risk"]?.jsonPrimitive?.contentOrNull,
            decision = json["decision"]?.jsonPrimitive?.contentOrNull,
            confirmationSource = json["confirmationSource"]?.jsonPrimitive?.contentOrNull
                ?.let { name -> ConfirmationSource.entries.firstOrNull { it.name == name } },
        )
    }

    companion object {
        /**
         * The key the App's settings page reads and deletes. One literal, pinned by a test on the phone side
         * (`AuditLogView.KEY === AuditTrail.KEY`), so the two halves cannot drift apart silently.
         */
        const val KEY = "audit.log"

        /** How many lines the document keeps; the ring in `SmartAssistantPlugin` keeps its own, larger, P3.1 capacity. */
        const val PERSISTED_CAPACITY = 200

        private const val VERSION = 1
    }
}
