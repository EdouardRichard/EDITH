package com.sheen.rokid.phone.plugin.smarthelper.memory

import com.sheen.rokid.plugin.StorageHandle
import com.sheen.rokid.phone.plugin.smarthelper.SmartFakeStorage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * P3.4 §3.1: the memory store is one bounded JSON document in the plugin's own encrypted namespace, and it must behave
 * like the audit trail does under the same three pressures - a corrupt document, a storage that refuses to read, and a
 * cap - because those are exactly the cases where a store either takes the turn down with it or silently claims it
 * recorded something it did not.
 *
 * Content is the other half: the document holds the user's sentences (that is the feature), and **no log line may ever
 * carry one**. The last test pins that by capturing the log sink.
 */
class MemoryLogTest {

    private var clock = 1_000L
    private val logged = mutableListOf<String>()

    private fun store(
        storage: StorageHandle? = SmartFakeStorage(),
        cap: Int = MemoryLog.CAP,
    ) = MemoryLog(storage = storage, now = { clock }, cap = cap, log = { logged += it })

    /** A namespace that refuses every operation: the honest worst case a host can hand the plugin. */
    private class ExplodingStorage : StorageHandle {
        override fun read(key: String): ByteArray? = throw IllegalStateException("boom")
        override fun write(key: String, value: ByteArray): Unit = throw IllegalStateException("boom")
        override fun delete(key: String): Unit = throw IllegalStateException("boom")
        override fun clear(): Unit = throw IllegalStateException("boom")
    }

    @Test fun `a recorded entry survives a fresh reader`() {
        val storage = SmartFakeStorage()
        store(storage).record(MemoryKind.PREFERENCE, "我喜欢喝茶", MemorySource.EXPLICIT)

        val reloaded = store(storage).entries()

        assertEquals(1, reloaded.size)
        assertEquals("我喜欢喝茶", reloaded.single().text)
        assertEquals(MemoryKind.PREFERENCE, reloaded.single().kind)
        assertEquals(MemorySource.EXPLICIT, reloaded.single().source)
    }

    @Test fun `recording the same fact twice updates the existing entry instead of appending`() {
        val log = store()
        log.record(MemoryKind.PREFERENCE, "我喜欢喝茶", MemorySource.AUTO)
        clock = 2_000L
        log.record(MemoryKind.PREFERENCE, "我喜欢喝茶", MemorySource.AUTO)

        val entries = log.entries()

        assertEquals(1, entries.size, "同一条事实不得变成两条：${entries.map { it.text }}")
        assertEquals(2, entries.single().useCount)
        assertEquals(2_000L, entries.single().lastUsedAtMillis)
    }

    @Test fun `a negated preference is stored beside its positive twin`() {
        val log = store()
        log.record(MemoryKind.PREFERENCE, "我喜欢喝茶", MemorySource.AUTO)
        log.record(MemoryKind.PREFERENCE, "我不喜欢喝茶", MemorySource.AUTO)

        assertEquals(2, log.entries().size, "否定与肯定必须是两条：${log.entries().map { it.text }}")
    }

    @Test fun `a corrupt document reads as empty and the next write repairs it`() {
        val storage = SmartFakeStorage()
        storage.write(MemoryLog.KEY, "this is not json".toByteArray(Charsets.UTF_8))

        val log = store(storage)
        assertEquals(emptyList(), log.entries())

        log.record(MemoryKind.FACT, "我的爱好是喝茶", MemorySource.AUTO)

        assertEquals(1, store(storage).entries().size)
    }

    @Test fun `a storage read failure degrades to empty instead of throwing`() {
        val log = store(ExplodingStorage())

        assertEquals(emptyList(), log.entries())
    }

    @Test fun `a failed read is distinguishable from an empty document`() {
        // The difference matters downstream: "nothing stored yet" is normal, while "the namespace refused to be read"
        // is the case the phase's own observable has to name (`inject=0 reason=READ_FAILED`).
        val failed = store(ExplodingStorage()).snapshot()
        val empty = store(SmartFakeStorage()).snapshot()

        assertFalse(failed.readOk, "读失败必须如实报告")
        assertEquals(emptyList(), failed.entries)
        assertTrue(empty.readOk, "命名空间正常但没内容是另一次读成功")
    }

    @Test fun `a corrupt document is a successful read of nothing`() {
        val storage = SmartFakeStorage()
        storage.write(MemoryLog.KEY, "{ not json".toByteArray(Charsets.UTF_8))

        val snapshot = store(storage).snapshot()

        assertTrue(snapshot.readOk, "内容不可解析不等于命名空间读不了")
        assertEquals(emptyList(), snapshot.entries)
    }

    @Test fun `a document written with another version reads as empty`() {
        val storage = SmartFakeStorage()
        storage.write(MemoryLog.KEY, """{"version":99,"entries":[]}""".toByteArray(Charsets.UTF_8))

        assertEquals(emptyList(), store(storage).entries())
    }

    @Test fun `a second store over the same namespace does not lose what the first wrote`() {
        // The whole document is rewritten on every write, so the store must re-read it each time instead of caching a
        // copy: otherwise the second writer silently drops the first writer's entry.
        val storage = SmartFakeStorage()
        val one = store(storage)
        val two = store(storage)

        one.record(MemoryKind.FACT, "我的爱好是喝茶", MemorySource.AUTO)
        two.record(MemoryKind.FACT, "我的城市是杭州", MemorySource.AUTO)

        assertEquals(listOf("我的爱好是喝茶", "我的城市是杭州"), store(storage).entries().map { it.text })
    }

    @Test fun `a storage write failure is reported as zero writes and never throws`() {
        val log = store(ExplodingStorage())

        val written = log.record(MemoryKind.FACT, "我的爱好是喝茶", MemorySource.AUTO)

        assertNull(written)
        assertTrue(logged.any { it.contains("write=n=0") && it.contains("STORAGE_FAILED") }, "必须如实报零写入：$logged")
    }

    @Test fun `the cap evicts the least recently used and logs only counts`() {
        val storage = SmartFakeStorage()
        val log = store(storage, cap = 2)
        log.record(MemoryKind.FACT, "我的爱好是喝茶", MemorySource.AUTO)
        clock = 2_000L
        log.record(MemoryKind.FACT, "我的工作是写代码", MemorySource.AUTO)
        clock = 3_000L
        log.record(MemoryKind.FACT, "我的城市是杭州", MemorySource.AUTO)

        val texts = log.entries().map { it.text }

        assertEquals(2, texts.size, "上限必须真的约束住：$texts")
        assertTrue("我的爱好是喝茶" !in texts, "最久没用过的那条应被淘汰：$texts")
        assertTrue(logged.any { it.contains("evict=n=1") && it.contains("reason=CAP") }, "淘汰必须留一行计数：$logged")
    }

    @Test fun `a log line never carries the memory text`() {
        val log = store()
        log.record(MemoryKind.PREFERENCE, "我喜欢喝茶", MemorySource.EXPLICIT)
        log.record(MemoryKind.PREFERENCE, "我不喜欢喝茶", MemorySource.AUTO)
        log.markUsed(log.entries().map { it.id })
        log.delete(log.entries().first().id)

        assertTrue(logged.isNotEmpty(), "这个测试要有意义，日志必须非空")
        assertTrue(
            logged.none { it.contains("喝茶") },
            "任何一行日志都不得含记忆正文：$logged",
        )
    }

    @Test fun `deleting one entry removes exactly that entry`() {
        val storage = SmartFakeStorage()
        val log = store(storage)
        log.record(MemoryKind.FACT, "我的爱好是喝茶", MemorySource.AUTO)
        log.record(MemoryKind.FACT, "我的城市是杭州", MemorySource.AUTO)
        val doomed = log.entries().first { it.text == "我的爱好是喝茶" }

        log.delete(doomed.id)

        assertEquals(listOf("我的城市是杭州"), store(storage).entries().map { it.text })
    }

    @Test fun `clearing removes the document`() {
        val storage = SmartFakeStorage()
        val log = store(storage)
        log.record(MemoryKind.FACT, "我的爱好是喝茶", MemorySource.AUTO)

        log.clear()

        assertNull(storage.read(MemoryLog.KEY), "清空后文档本身必须不在")
        assertEquals(emptyList(), log.entries())
    }

    @Test fun `marking an entry used moves it up for the next query`() {
        val log = store()
        log.record(MemoryKind.PREFERENCE, "我喜欢喝茶", MemorySource.AUTO)
        clock = 5_000L
        val id = log.entries().single().id

        log.markUsed(listOf(id))

        assertEquals(5_000L, log.entries().single().lastUsedAtMillis)
        assertEquals(2, log.entries().single().useCount)
    }

    // --- Phase 4 Task 2: dual storage ---

    @Test fun `record persists both original and normalized text`() {
        val storage = SmartFakeStorage()
        val log = MemoryLog(storage = storage, now = { clock }, cap = MemoryLog.CAP, log = { logged += it }, normalizer = ::normalize)
        val entry = log.record(MemoryKind.PREFERENCE, "我喜欢喝茶", MemorySource.AUTO)!!

        assertEquals("我喜欢喝茶", entry.text)
        assertEquals("用户曾表示喜欢喝茶", entry.normalizedText)

        // Round-trip through storage
        val restored = store(storage).entries().first { it.id == entry.id }
        assertEquals("我喜欢喝茶", restored.text)
        assertEquals("用户曾表示喜欢喝茶", restored.normalizedText)
    }

    @Test fun `record without normalizer leaves normalizedText empty`() {
        val log = store()
        val entry = log.record(MemoryKind.PREFERENCE, "我喜欢喝茶", MemorySource.AUTO)!!

        assertEquals("我喜欢喝茶", entry.text)
        assertEquals("", entry.normalizedText)
    }

    @Test fun `backward compat - old documents without normalizedText decode with empty string`() {
        val storage = SmartFakeStorage()
        // Write a v1 document manually without normalizedText field
        val oldJson = """{"version":1,"entries":[{"id":"old-1","kind":"PREFERENCE","text":"我喜欢喝茶","createdAtMillis":1000,"lastUsedAtMillis":1000,"useCount":1,"source":"AUTO"}]}"""
        storage.write(MemoryLog.KEY, oldJson.toByteArray(Charsets.UTF_8))

        val entries = store(storage).entries()

        assertEquals(1, entries.size)
        assertEquals("我喜欢喝茶", entries.single().text)
        assertEquals("", entries.single().normalizedText)
    }

    @Test fun `refreshed entry preserves normalizedText from original record`() {
        val storage = SmartFakeStorage()
        val log = MemoryLog(storage = storage, now = { clock }, cap = MemoryLog.CAP, log = { logged += it }, normalizer = ::normalize)
        log.record(MemoryKind.PREFERENCE, "我喜欢喝茶", MemorySource.AUTO)
        clock = 2_000L

        // Recording the same text again should refresh, keeping normalizedText
        val refreshed = log.record(MemoryKind.PREFERENCE, "我喜欢喝茶", MemorySource.AUTO)!!

        assertEquals("我喜欢喝茶", refreshed.text)
        assertEquals("用户曾表示喜欢喝茶", refreshed.normalizedText)
        assertEquals(2, refreshed.useCount)
    }
}
