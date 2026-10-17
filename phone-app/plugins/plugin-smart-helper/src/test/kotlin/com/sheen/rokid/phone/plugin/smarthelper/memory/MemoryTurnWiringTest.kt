package com.sheen.rokid.phone.plugin.smarthelper.memory

import com.sheen.rokid.plugin.Capability
import com.sheen.rokid.phone.plugin.smarthelper.SmartFakeStorage
import com.sheen.rokid.phone.plugin.smarthelper.SmartTestRig
import com.sheen.rokid.phone.plugin.smarthelper.streamText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * P3.4 §3.2, the wiring half: what a **finished turn** leaves in the namespace the host granted, and the one sentence
 * the user is owed when they asked for a memory.
 *
 * The store's own rules live in [MemoryExtractionTest]; this file is about the plugin actually calling them - a
 * correct extractor that nothing calls is a feature that does not exist, and an acknowledgement that is never sent is
 * the same silence as a dropped instruction.
 */
class MemoryTurnWiringTest {

    @Test fun `a finished turn writes what the user said and acknowledges an explicit instruction`() {
        val storage = SmartFakeStorage()
        val rig = SmartTestRig()
        rig.activate(overrides = mapOf(Capability.Storage to storage))
        rig.model.onRequest = { request, listener -> streamText(request, listener, "好。") }

        rig.wakeAndFinal("记住我不吃香菜", turnId = "asr-1")

        val entries = MemoryLog(storage = storage, now = { 1_000L }, log = { }).entries()
        assertEquals(listOf("我不吃香菜"), entries.map { it.text }, "回合结束后必须把显式内容写进命名空间")
        assertEquals(MemorySource.EXPLICIT, entries.single().source)
        assertTrue(
            rig.output.hints.any { it.text == MemoryExtraction.ACKNOWLEDGEMENT },
            "显式指令必须回一句确认，且这句不含内容：${rig.output.hints.map { it.text }}",
        )
    }

    @Test fun `an automatic preference is written without an acknowledgement`() {
        val storage = SmartFakeStorage()
        val rig = SmartTestRig()
        rig.activate(overrides = mapOf(Capability.Storage to storage))
        rig.model.onRequest = { request, listener -> streamText(request, listener, "好。") }

        rig.wakeAndFinal("我喜欢喝茶", turnId = "asr-1")

        val entries = MemoryLog(storage = storage, now = { 1_000L }, log = { }).entries()
        assertEquals(listOf("我喜欢喝茶"), entries.map { it.text })
        assertEquals(MemorySource.AUTO, entries.single().source)
        assertTrue(
            rig.output.hints.none { it.text == MemoryExtraction.ACKNOWLEDGEMENT },
            "自动抽取不得打断用户：${rig.output.hints.map { it.text }}",
        )
    }

    @Test fun `a turn with nothing stable in it writes nothing`() {
        val storage = SmartFakeStorage()
        val rig = SmartTestRig()
        rig.activate(overrides = mapOf(Capability.Storage to storage))
        rig.model.onRequest = { request, listener -> streamText(request, listener, "好。") }

        rig.wakeAndFinal("今天天气不错", turnId = "asr-1")

        assertTrue(MemoryLog(storage = storage, now = { 1_000L }, log = { }).entries().isEmpty())
    }
}
