package com.sheen.rokid.phone.plugin.smarthelper.memory

import com.sheen.rokid.plugin.StorageHandle
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Spec §5.2/§5.3/§5.6: entries carry a 256-dim embedding, the cap is 1000, and eviction is by use rather than by
 * recency alone.
 */
class MemoryEvictionTest {

    private val day = 24L * 60 * 60 * 1000
    private val now = 1_000L * day

    private fun entry(
        id: String,
        useCount: Int,
        lastUsedDaysAgo: Long,
        createdDaysAgo: Long = lastUsedDaysAgo,
        kind: MemoryKind = MemoryKind.FACT,
        source: MemorySource = MemorySource.AUTO,
    ) = MemoryEntry(
        id = id,
        kind = kind,
        text = "text of $id",
        createdAtMillis = now - createdDaysAgo * day,
        lastUsedAtMillis = now - lastUsedDaysAgo * day,
        useCount = useCount,
        source = source,
    )

    private class FakeStorage(
        val values: MutableMap<String, ByteArray> = mutableMapOf(),
    ) : StorageHandle {
        override fun read(key: String): ByteArray? = values[key]
        override fun write(key: String, value: ByteArray) {
            values[key] = value
        }
        override fun delete(key: String) {
            values.remove(key)
        }
        override fun clear() = values.clear()
    }

    // ---------------------------------------------------------------- eviction

    @Test
    fun `eviction drops the lowest use count first`() {
        val entries = listOf(
            entry("never-used", useCount = 0, lastUsedDaysAgo = 1),
            entry("used-once", useCount = 1, lastUsedDaysAgo = 1),
            entry("used-a-lot", useCount = 9, lastUsedDaysAgo = 1),
        )

        val (kept, dropped) = evictByUsage(entries, cap = 2, nowMillis = now)

        assertEquals(1, dropped)
        assertEquals(listOf("used-once", "used-a-lot"), kept.map { it.id })
    }

    @Test
    fun `a tie on use count drops the least recently used`() {
        val entries = listOf(
            entry("stale", useCount = 3, lastUsedDaysAgo = 30),
            entry("fresh", useCount = 3, lastUsedDaysAgo = 1),
            entry("kept", useCount = 4, lastUsedDaysAgo = 30),
        )

        val (kept, _) = evictByUsage(entries, cap = 2, nowMillis = now)

        assertEquals(listOf("fresh", "kept"), kept.map { it.id })
    }

    @Test
    fun `an entry younger than seven days is never dropped while an older one can be`() {
        // Spec §5.6 step 3: a just-learned fact must survive a store full of stale ones.
        val entries = listOf(
            entry("old-unused", useCount = 0, lastUsedDaysAgo = 40, createdDaysAgo = 40),
            entry("old-used", useCount = 1, lastUsedDaysAgo = 10, createdDaysAgo = 10),
            entry("just-learned", useCount = 0, lastUsedDaysAgo = 0, createdDaysAgo = 0),
        )

        val (kept, dropped) = evictByUsage(entries, cap = 2, nowMillis = now)

        assertEquals(1, dropped)
        assertTrue(kept.any { it.id == "just-learned" }, "the newest entry must survive: ${kept.map { it.id }}")
        assertTrue(kept.none { it.id == "old-unused" })
    }

    @Test
    fun `when every entry is inside the protection window the oldest is still dropped`() {
        // The protection is a preference, not an exemption: without this the store would grow past its cap forever.
        val entries = listOf(
            entry("a", useCount = 5, lastUsedDaysAgo = 1),
            entry("b", useCount = 0, lastUsedDaysAgo = 1),
            entry("c", useCount = 3, lastUsedDaysAgo = 1),
        )

        val (kept, dropped) = evictByUsage(entries, cap = 2, nowMillis = now)

        assertEquals(1, dropped)
        assertEquals(2, kept.size)
        assertTrue(kept.none { it.id == "b" }, "the least used still goes: ${kept.map { it.id }}")
    }

    @Test
    fun `nothing is dropped below the cap and the input order is preserved`() {
        val entries = listOf(
            entry("z", useCount = 0, lastUsedDaysAgo = 1),
            entry("a", useCount = 9, lastUsedDaysAgo = 1),
        )

        val (kept, dropped) = evictByUsage(entries, cap = 10, nowMillis = now)

        assertEquals(0, dropped)
        assertEquals(listOf("z", "a"), kept.map { it.id })
    }

    @Test
    fun `the protection window is seven days`() {
        assertEquals(7L * day, PROTECT_MILLIS)
    }

    // ---------------------------------------------------------------- the store

    @Test
    fun `the cap is a thousand`() {
        assertEquals(1000, MemoryLog.CAP)
    }

    @Test
    fun `an entry records its embedding but the document never carries one`() {
        // 1000 x 256 floats would make every write a multi-megabyte rewrite, and the vector is recomputable from the
        // text at any time. So the embedding lives in memory only - which is also why the document's shape and its
        // version number do not change, and why an older document keeps loading.
        val storage = FakeStorage()
        val log = MemoryLog(storage, now = { now }, log = {})
        log.record(MemoryKind.FACT, "我不吃香菜", MemorySource.EXPLICIT)

        val document = String(storage.values.getValue(MemoryLog.KEY), Charsets.UTF_8)
        assertTrue("embedding" !in document, "the document must not carry a vector: ${document.take(200)}")
        assertNull(log.entries().single().embedding)
    }

    @Test
    fun `a document written by the previous build still loads`() {
        // Keeping VERSION at 1 is the decision under test: the shape did not change, so rejecting the previous
        // build's documents would silently discard every memory the user had.
        val storage = FakeStorage()
        storage.values[MemoryLog.KEY] = """
            {"version":1,"entries":[{"id":"a","kind":"FACT","text":"我住在三楼","createdAtMillis":1,
            "lastUsedAtMillis":1,"useCount":2,"source":"AUTO"}]}
        """.trimIndent().toByteArray(Charsets.UTF_8)

        val entries = MemoryLog(storage, now = { now }, log = {}).entries()

        assertEquals(1, entries.size)
        assertEquals("我住在三楼", entries.single().text)
        assertEquals(2, entries.single().useCount)
    }

    @Test
    fun `a document from a future build reads as empty rather than being guessed at`() {
        val storage = FakeStorage()
        storage.values[MemoryLog.KEY] =
            """{"version":99,"entries":[]}""".toByteArray(Charsets.UTF_8)

        assertEquals(emptyList(), MemoryLog(storage, now = { now }, log = {}).entries())
    }

    @Test
    fun `a corrupt document reads as empty and the next write repairs it`() {
        val storage = FakeStorage()
        storage.values[MemoryLog.KEY] = "{ not json".toByteArray(Charsets.UTF_8)
        val log = MemoryLog(storage, now = { now }, log = {})

        assertEquals(emptyList(), log.entries())

        log.record(MemoryKind.FACT, "新的记忆", MemorySource.EXPLICIT)

        val repaired = Json.parseToJsonElement(
            String(storage.values.getValue(MemoryLog.KEY), Charsets.UTF_8),
        ).jsonObject
        assertEquals(1, repaired.getValue("entries").jsonArray.size)
    }

    @Test
    fun `the store evicts by use when it overflows its cap`() {
        val storage = FakeStorage()
        val log = MemoryLog(storage, now = { now }, cap = 2, log = {})
        log.record(MemoryKind.FACT, "第一条", MemorySource.EXPLICIT)
        log.record(MemoryKind.FACT, "第二条", MemorySource.EXPLICIT)
        log.record(MemoryKind.FACT, "第三条", MemorySource.EXPLICIT)

        val kept = log.entries()
        assertEquals(2, kept.size)
        // Nothing has been marked used, so all three tie on useCount and the least recently used goes - which for
        // three writes in the same millisecond is the first one by input order.
        assertTrue(kept.none { it.text == "第一条" }, kept.map { it.text }.toString())
    }

    @Test
    fun `the eviction line carries counts and a reason code only`() {
        val lines = mutableListOf<String>()
        val log = MemoryLog(FakeStorage(), now = { now }, cap = 1, log = { lines += it })
        log.record(MemoryKind.FACT, "第一条", MemorySource.EXPLICIT)
        log.record(MemoryKind.FACT, "第二条", MemorySource.EXPLICIT)

        val eviction = lines.single { it.contains("evict") }
        assertEquals("P3_MEMORY evict=n=1 reason=CAP", eviction)
        assertTrue(lines.none { it.contains("第一条") || it.contains("第二条") })
    }

    @Test
    fun `an embedding is carried through a round trip in memory when it is supplied`() {
        // The vector is not persisted, so it must be attachable at retrieval time without disturbing the document.
        val storage = FakeStorage()
        val log = MemoryLog(storage, now = { now }, log = {})
        log.record(MemoryKind.FACT, "带向量的记忆", MemorySource.EXPLICIT)

        val vector = FloatArray(256) { it.toFloat() }
        val updated = log.entries().map { it.copy(embedding = vector) }

        assertEquals(256, updated.single().embedding?.size)
        // The stored document is untouched by an in-memory vector.
        assertTrue("embedding" !in String(storage.values.getValue(MemoryLog.KEY), Charsets.UTF_8))
    }

    @Test
    fun `the document still declares its schema version`() {
        val storage = FakeStorage()
        MemoryLog(storage, now = { now }, log = {}).record(MemoryKind.FACT, "x", MemorySource.EXPLICIT)

        val root = Json.parseToJsonElement(
            String(storage.values.getValue(MemoryLog.KEY), Charsets.UTF_8),
        ).jsonObject
        assertEquals(1, root.getValue("version").jsonPrimitive.content.toInt())
        assertEquals(setOf("version", "entries"), root.keys)
        val row = (root.getValue("entries") as kotlinx.serialization.json.JsonArray).single() as JsonObject
        assertEquals(
            // Phase 4 Task 2: normalizedText added for dual storage (§3.9 6d).
            setOf("id", "kind", "text", "createdAtMillis", "lastUsedAtMillis", "useCount", "source", "normalizedText"),
            row.keys,
        )
    }
}
