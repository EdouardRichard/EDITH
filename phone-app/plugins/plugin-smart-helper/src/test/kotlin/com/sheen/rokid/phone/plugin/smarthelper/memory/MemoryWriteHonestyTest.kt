package com.sheen.rokid.phone.plugin.smarthelper.memory

import com.sheen.rokid.plugin.StorageHandle
import com.sheen.rokid.phone.plugin.smarthelper.SmartExplodingStorage
import com.sheen.rokid.phone.plugin.smarthelper.SmartFakeStorage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The P3.4 write path's **honesty rules**, pinned after a fresh read-only review found the code contradicting its own
 * KDoc. Four separate claims are at stake, and each one is a way a memory feature quietly lies to its user:
 *
 *  1. a write that failed must not be acknowledged as remembered;
 *  2. an eviction must not be announced before the document it belongs to was actually saved;
 *  3. "no namespace at all" must not be reported as a successful read of nothing;
 *  4. a habit the per-turn cap pushed out must stay pending instead of being consumed and lost forever.
 *
 * Assertion messages come last (`kotlin.test`).
 */
class MemoryWriteHonestyTest {

    private var clock = 1_000L

    /** Reads what a previous successful write left behind, and refuses writes on demand - the shape that separates "computed" from "stored". */
    private class ToggleStorage : StorageHandle {
        val values = mutableMapOf<String, ByteArray>()
        var refuseWrites = false
        override fun read(key: String): ByteArray? = values[key]
        override fun write(key: String, value: ByteArray) {
            if (refuseWrites) throw IllegalStateException("write refused")
            values[key] = value
        }
        override fun delete(key: String) { values.remove(key) }
        override fun clear() { values.clear() }
    }

    @Test fun `a write that failed is never acknowledged as remembered`() {
        val extraction = MemoryExtraction(log = MemoryLog(SmartExplodingStorage(), now = { clock }, log = { }))

        val report = extraction.afterTurn("记住我不吃香菜", emptyList())

        assertEquals(0, report.written, "写失败就是没写进去")
        assertFalse(report.explicit, "写失败时不得回「好，我记下了。」——那是在骗用户")
    }

    @Test fun `a successful explicit write still asks for the acknowledgement`() {
        val extraction = MemoryExtraction(log = MemoryLog(SmartFakeStorage(), now = { clock }, log = { }))

        val report = extraction.afterTurn("记住我不吃香菜", emptyList())

        assertEquals(1, report.written)
        assertTrue(report.explicit, "成功写入时才该回确认句")
    }

    @Test fun `an eviction is not announced before the write it belongs to succeeded`() {
        val storage = ToggleStorage()
        val lines = mutableListOf<String>()
        val log = MemoryLog(storage = storage, now = { clock }, cap = 1, log = { lines += it })

        log.record(MemoryKind.FACT, "第一条", MemorySource.AUTO)
        storage.refuseWrites = true
        val second = log.record(MemoryKind.FACT, "第二条", MemorySource.AUTO)

        assertEquals(null, second, "写入被拒时必须报 null")
        assertTrue(lines.none { it.contains("evict=") }, "写都没成功，不得报告发生过淘汰：$lines")
    }

    @Test fun `a store with no namespace cannot claim it read successfully`() {
        val snapshot = MemoryLog(storage = null, now = { clock }, log = { }).snapshot()

        assertTrue(snapshot.entries.isEmpty())
        assertFalse(snapshot.readOk, "没有存储句柄＝读不了，不能算「读成功但为空」")
    }

    @Test fun `a habit the per-turn cap pushed out stays pending`() {
        val storage = SmartFakeStorage()
        val extraction = MemoryExtraction(
            log = MemoryLog(storage = storage, now = { clock }, log = { }),
            maxPerTurn = 1,
        )
        // Three turns, each with one automatic candidate that fills the single slot, plus one successful dial: the third
        // dial reaches the threshold exactly when the cap leaves no room for the routine.
        repeat(3) { extraction.afterTurn("我喜欢喝茶", listOf("dial")) }

        val report = extraction.afterTurn("今天天气不错", listOf("dial"))

        assertEquals(1, report.written, "第 4 次成功动作时名额已空出，习惯必须补上")
        assertTrue(
            MemoryLog(storage = storage, now = { clock }, log = { }).entries().any { it.kind == MemoryKind.ROUTINE },
            "被名额挤掉的习惯不得被永久吞掉",
        )
    }
}
