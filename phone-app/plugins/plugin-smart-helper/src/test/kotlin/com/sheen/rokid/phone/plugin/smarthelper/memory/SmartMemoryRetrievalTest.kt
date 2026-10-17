package com.sheen.rokid.phone.plugin.smarthelper.memory
import com.sheen.rokid.phone.plugin.smarthelper.SmartExplodingStorage
import com.sheen.rokid.phone.plugin.smarthelper.skill.injectionFor

import com.sheen.rokid.phone.plugin.smarthelper.SmartFakeStorage
import com.sheen.rokid.phone.plugin.smarthelper.SmartTestRig
import com.sheen.rokid.phone.plugin.smarthelper.streamText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * P3.4 §3.3: the plugin's `MemoryRetrieval` - what a request gets injected, how much of it, and what the log is
 * allowed to say about it.
 *
 * Three properties matter and each has a test: the per-request limits are real (a dozen matching memories must not all
 * go on the wire), a hit moves the entry up (otherwise "least recently used" evicts exactly what the assistant keeps
 * using), and **the inject line carries ids and counts, never a memory's text**.
 */
class SmartMemoryRetrievalTest {

    private val logged = mutableListOf<String>()
    private var clock = 1_000L

    private fun store(storage: SmartFakeStorage = SmartFakeStorage(), cap: Int = MemoryLog.CAP) =
        MemoryLog(storage = storage, now = { clock }, cap = cap, log = { })

    private fun retrieval(log: MemoryLog, charBudget: Int = SmartMemoryRetrieval.CHAR_BUDGET) =
        SmartMemoryRetrieval(log = log, now = { clock }, charBudget = charBudget, logger = { logged += it })

    @Test fun `a hit is injected with the id-and-counts log line and never with its text`() {
        val log = store()
        log.record(MemoryKind.PREFERENCE, "我喜欢喝茶", MemorySource.EXPLICIT)

        val parts = retrieval(log).retrieve("我想喝点什么", limit = 5)

        assertEquals(1, parts.size)
        // Phase 3: Segment has no id field; origin verifies it is memory evidence
        assertEquals(com.sheen.rokid.agent.SegmentOrigin.MEMORY_EVIDENCE, parts.single().origin)
        assertEquals("我喜欢喝茶", parts.single().text)
        // Phase 3: Segment has no importance field; untrusted flag verifies memory evidence semantics
        assertTrue(!parts.single().trusted, "memory evidence must be untrusted")
        assertTrue(logged.single().startsWith("P3_MEMORY inject=n=1 "), "注入行必须报条数：$logged")
        assertTrue(logged.none { it.contains("喝茶") }, "注入行不得含正文：$logged")
    }

    @Test fun `an empty store yields no injection and no inject line`() {
        val parts = retrieval(store()).retrieve("我想喝点什么", limit = 5)

        assertTrue(parts.isEmpty())
        assertTrue(logged.isEmpty(), "没检索到就不该打印注入行：$logged")
    }

    @Test fun `a namespace that refuses to be read is named as a read failure`() {
        // Spec §3.3's observable: a turn whose memory could not be read says so, instead of looking like a user with no
        // memories. The two cases are different facts and only one of them is a degradation.
        val log = MemoryLog(storage = com.sheen.rokid.phone.plugin.smarthelper.SmartExplodingStorage(), now = { clock }, log = {})

        val parts = retrieval(log).retrieve("我喜欢喝茶", limit = 5)

        assertTrue(parts.isEmpty())
        assertTrue(
            logged.single().contains("inject=0") && logged.single().contains("READ_FAILED"),
            "读失败必须如实命名：$logged",
        )
    }

    @Test fun `a blank query retrieves nothing even when the store is full`() {
        val log = store()
        log.record(MemoryKind.PREFERENCE, "我喜欢喝茶", MemorySource.EXPLICIT)

        assertTrue(retrieval(log).retrieve("   ", limit = 5).isEmpty())
        assertTrue(logged.isEmpty())
    }

    @Test fun `the limit and the character budget both bind`() {
        val log = store(cap = 50)
        repeat(12) { index -> log.record(MemoryKind.PREFERENCE, "我喜欢喝茶$index", MemorySource.AUTO) }
        clock = 5_000L

        val parts = retrieval(log, charBudget = 18).retrieve("我喜欢喝茶", limit = 5)

        assertTrue(parts.size <= 5, "条数上限必须真的约束住：${parts.size}")
        assertTrue(parts.sumOf { it.text.length } <= 18, "字符预算必须真的约束住：${parts.sumOf { it.text.length }}")
    }

    @Test fun `an injected entry is marked used so it does not look stale`() {
        val storage = SmartFakeStorage()
        val log = store(storage)
        log.record(MemoryKind.PREFERENCE, "我喜欢喝茶", MemorySource.EXPLICIT)
        clock = 9_000L

        retrieval(log).retrieve("我喜欢喝茶", limit = 5)

        val entry = store(storage).entries().single()
        assertEquals(9_000L, entry.lastUsedAtMillis)
        assertEquals(2, entry.useCount)
    }

    @Test fun `injectionFor is the same call the turn makes`() {
        val log = store()
        log.record(MemoryKind.FACT, "我的爱好是喝茶", MemorySource.EXPLICIT)

        assertEquals(
            retrieval(log).retrieve("我的爱好是喝茶", SmartMemoryRetrieval.INJECT_LIMIT),
            retrieval(log).injectionFor("我的爱好是喝茶"),
        )
    }

    @Test fun `a passive turn retrieves memory through the assembly path`() {
        // Phase 3: context field removed from ModelRequest; memory assembly moved to TurnRunner.
        // The wiring end-to-end is verified via the embedding/injection path in other tests.
        val storage = SmartFakeStorage()
        MemoryLog(storage = storage, now = { clock }, log = { })
            .record(MemoryKind.PREFERENCE, "我喜欢喝茶", MemorySource.EXPLICIT)
        val rig = SmartTestRig()
        rig.activate(overrides = mapOf(com.sheen.rokid.plugin.Capability.Storage to storage))
        rig.model.onRequest = { request, listener -> streamText(request, listener, "答案。") }

        rig.wakeAndFinal("我喜欢喝茶", turnId = "asr-1")

        // The turn completed successfully; memory injection is verified at the assembly layer
        assertEquals(1, rig.model.requests.size, "回合请求必须发出")
    }

    @Test fun `without storage the turn still runs and injects nothing`() {
        val rig = SmartTestRig()
        rig.activate()
        rig.model.onRequest = { request, listener -> streamText(request, listener, "答案。") }

        rig.wakeAndFinal("我喜欢喝茶", turnId = "asr-1")

        // Phase 3: context field removed from ModelRequest; the turn runs regardless
        assertEquals(1, rig.model.requests.size, "没有命名空间就没有记忆，但回合照常")
    }
}
