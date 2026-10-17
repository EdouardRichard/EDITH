package com.sheen.rokid.phone.plugin.smarthelper.memory
import com.sheen.rokid.phone.plugin.smarthelper.audit.AuditTrail

import com.sheen.rokid.plugin.StorageHandle
import com.sheen.rokid.phone.plugin.smarthelper.audit.P3Log
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.util.UUID

/** The result of one read: what was there, and whether the namespace answered at all. */
data class MemorySnapshot(val entries: List<MemoryEntry>, val readOk: Boolean)

/**
 * P3.4 §3.1: the long-term memory store - one bounded JSON document in the plugin's own encrypted namespace, shaped
 * exactly like [`com.sheen.rokid.phone.plugin.smarthelper.audit.AuditTrail`]'s so the App can read it with the same kind of small view.
 *
 * Four rules decide whether this is honest, and each has a test:
 *
 *  - **A write failure is never a silent success.** [record] answers `null` and logs `write=n=0`; the turn it belongs
 *    to carries on, because a memory that cannot be saved must not take the assistant down with it.
 *  - **A corrupt or unreadable document reads as empty**, and the next write repairs it. A blob this build cannot parse
 *    is not a reason to throw inside a turn.
 *  - **The cap drops the least recently used**, and the eviction is logged as a count and a reason code - never by text.
 *  - **No log line carries a memory.** The document holds the user's sentences (that is the feature); the log holds
 *    counts, kinds, ids and reason codes. [`P3Log`] is the same channel the rest of the plugin uses.
 *
 * De-duplication uses [similarity] with the 0.85 threshold from spec §3.2: saying the same thing again refreshes the
 * entry instead of growing a second copy, while a negated twin (`我喜欢喝茶` / `我不喜欢喝茶`) stays two entries.
 */
class MemoryLog(
    private val storage: StorageHandle?,
    private val now: () -> Long = System::currentTimeMillis,
    private val cap: Int = CAP,
    private val log: (String) -> Unit = { P3Log.line(it) },
    /**
     * Phase 4 §3.9 6d: optional normalizer that derives a third-person form from the original text at write time.
     * When non-null, [record] stores both the original and normalized text (dual storage).
     * When null, [MemoryEntry.normalizedText] stays empty — backward compatible with pre-Phase-4 behavior.
     */
    private val normalizer: ((String, MemoryKind) -> String)? = null,
) {

    init {
        require(cap >= 1) { "MemoryLog cap must be at least 1, was $cap" }
    }

    /** Everything the document holds, in stored order. Unreadable content answers empty rather than throwing. */
    fun entries(): List<MemoryEntry> = snapshot().entries

    /**
     * What a read found, **and whether the namespace answered at all**.
     *
     * The two empty cases are different facts: a store nobody has written to is normal, while a namespace that refused
     * to be read is the degradation spec §3.3 names out loud (`inject=0 reason=READ_FAILED`). Folding them together
     * would make a broken host look exactly like a user with no memories.
     */
    fun snapshot(): MemorySnapshot {
        // A missing namespace is not "an empty store": it is a store this process cannot read, which is exactly the
        // degradation the injection path reports as READ_FAILED. Folding it into "read succeeded, nothing there" would
        // make a broken host indistinguishable from a user with no memories.
        val target = storage ?: return MemorySnapshot(emptyList(), readOk = false)
        val read = runCatching { target.read(KEY) }
        if (read.isFailure) return MemorySnapshot(emptyList(), readOk = false)
        val bytes = read.getOrNull() ?: return MemorySnapshot(emptyList(), readOk = true)
        return MemorySnapshot(decode(bytes), readOk = true)
    }

    /**
     * Records one memory, or refreshes an existing one that says the same thing (same kind, [similarity] ≥ 0.85).
     *
     * Answers the entry that is now stored, or `null` when it could not be written - a rejected empty text, or a
     * storage that refused. A `null` is always paired with a log line saying so.
     */
    fun record(kind: MemoryKind, text: String, source: MemorySource): MemoryEntry? {
        val clean = text.trim()
        if (clean.isEmpty()) return null
        val current = entries().toMutableList()
        val stamp = now()
        val existing = current.indexOfFirst { it.kind == kind && similarity(it.text, clean) >= DEDUPE }
        val entry = if (existing >= 0) {
            // Refresh: keep the original normalizedText from the first record.
            val refreshed = current[existing].copy(lastUsedAtMillis = stamp, useCount = current[existing].useCount + 1)
            current[existing] = refreshed
            refreshed
        } else {
            val normalized = normalizer?.invoke(clean, kind) ?: ""
            MemoryEntry(UUID.randomUUID().toString(), kind, clean, stamp, stamp, 1, source, normalizedText = normalized).also { current += it }
        }
        val (kept, dropped) = evictByUsage(current, cap, stamp)
        // The eviction line goes out only after the document it describes was actually saved: an eviction announced
        // before a write that then fails would report something that never happened.
        if (!persist(kept)) return null
        if (dropped > 0) log("P3_MEMORY evict=n=$dropped reason=CAP")
        log("P3_MEMORY write=n=1 kind=${kind.name} source=${source.name}")
        return entry
    }

    /**
     * Marks the entries the last request actually used ([MemoryKind] aside, only the counters move).
     *
     * This is what makes "least recently used" mean something: a memory that keeps earning its place must not be the
     * one the cap throws away next.
     */
    fun markUsed(ids: List<String>) {
        if (ids.isEmpty()) return
        val wanted = ids.toSet()
        val updated = entries().map { if (it.id in wanted) it.copy(lastUsedAtMillis = now(), useCount = it.useCount + 1) else it }
        persist(updated)
    }

    /** The user's "forget this": one entry, gone from the document rather than hidden from a view. */
    fun delete(id: String) {
        persist(entries().filterNot { it.id == id })
    }

    /** The user's "forget everything": removes the document itself, like the audit page's clear does. */
    fun clear() {
        val target = storage ?: return
        runCatching { target.delete(KEY) }
            .onFailure { log("P3_MEMORY clear=n=0 reason=STORAGE_FAILED") }
    }

    /** Best effort, and never by content: a failure is logged by its reason code only. */
    private fun persist(all: List<MemoryEntry>): Boolean {
        val target = storage ?: return false
        return runCatching { target.write(KEY, encode(all)) }
            .onFailure { log("P3_MEMORY write=n=0 reason=STORAGE_FAILED") }
            .isSuccess
    }

    private fun encode(all: List<MemoryEntry>): ByteArray = buildJsonObject {
        put("version", VERSION)
        put(
            "entries",
            buildJsonArray {
                all.forEach { entry ->
                    add(
                        buildJsonObject {
                            put("id", entry.id)
                            put("kind", entry.kind.name)
                            put("text", entry.text)
                            put("createdAtMillis", entry.createdAtMillis)
                            put("lastUsedAtMillis", entry.lastUsedAtMillis)
                            put("useCount", entry.useCount)
                            put("source", entry.source.name)
                            // Phase 4 §3.9 6d: dual storage — persist normalized form alongside original.
                            // Always written (even when empty) so re-encoding a decoded document is stable.
                            put("normalizedText", entry.normalizedText)
                        },
                    )
                }
            },
        )
    }.toString().toByteArray(Charsets.UTF_8)

    private fun decode(bytes: ByteArray): List<MemoryEntry> {
        return runCatching {
            val root = Json.parseToJsonElement(String(bytes, Charsets.UTF_8)) as? JsonObject ?: return emptyList()
            if (root["version"]?.jsonPrimitive?.intOrNull != VERSION) return emptyList()
            val array = root["entries"]?.jsonArray ?: return emptyList()
            array.mapNotNull { element -> (element as? JsonObject)?.let(::entryFrom) }
        }.getOrElse { emptyList() }
    }

    private fun entryFrom(json: JsonObject): MemoryEntry? {
        val id = json["id"]?.jsonPrimitive?.contentOrNull ?: return null
        val text = json["text"]?.jsonPrimitive?.contentOrNull ?: return null
        val kind = json["kind"]?.jsonPrimitive?.contentOrNull?.let { name -> MemoryKind.entries.firstOrNull { it.name == name } }
            ?: return null
        val source = json["source"]?.jsonPrimitive?.contentOrNull
            ?.let { name -> MemorySource.entries.firstOrNull { it.name == name } }
            ?: MemorySource.AUTO
        return MemoryEntry(
            id = id,
            kind = kind,
            text = text,
            createdAtMillis = json["createdAtMillis"]?.jsonPrimitive?.longOrNull ?: 0L,
            lastUsedAtMillis = json["lastUsedAtMillis"]?.jsonPrimitive?.longOrNull ?: 0L,
            useCount = json["useCount"]?.jsonPrimitive?.intOrNull ?: 0,
            source = source,
            // Phase 4 §3.9 6d: old documents lack this field — default to empty string (backward compat).
            normalizedText = json["normalizedText"]?.jsonPrimitive?.contentOrNull ?: "",
        )
    }

    companion object {
        /**
         * The key the App's memory page reads and deletes. One literal, pinned by a test on the phone side (the same
         * arrangement `AuditTrail.KEY` uses), so a second literal in the App cannot quietly point at nothing.
         */
        const val KEY = "memory.log"

        /** Spec §5.3: at most this many entries; beyond it the least used go (§5.6). */
        const val CAP = 1000

        /**
         * Unchanged from the previous build, deliberately.
         *
         * The entry model gained an `embedding`, but the *document* did not: the vector is never persisted (see
         * [MemoryEntry.embedding]). Bumping the version would make [decode] reject every document the previous build
         * wrote — silently discarding every memory the user had — in exchange for nothing.
         */
        private const val VERSION = 1

        /** Spec §3.2's de-duplication threshold. */
        private const val DEDUPE = 0.85
    }
}
