package com.sheen.rokid.phone.plugin.smarthelper.context
import com.sheen.rokid.phone.plugin.smarthelper.SkillTwoLayerWiringTest

import com.sheen.rokid.agent.MemoryRetrieval
import com.sheen.rokid.agent.Segment
import com.sheen.rokid.agent.SegmentOrigin
import com.sheen.rokid.plugin.MessageRole
import com.sheen.rokid.plugin.ModelHistoryItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Spec §4.2/§4.3: the two routes are independent and the prompt is assembled from the budgeted pieces.
 *
 * Phase 6 (design D-1/D-2) **de-skilled this manager**: it no longer assembles SKILL text, because the system slot's
 * `SKILL` segments are the plugin's only SKILL path and the old whole-body section would double-inject. The two
 * assertions that lived here ("every enabled body is injected whole" and "skills are injected in name order") were the
 * D60 form this phase removes; their intent — a SKILL really reaches the model — is preserved by
 * `SkillTwoLayerWiringTest`'s pull tests, on the layer that now carries it.
 *
 * One deliberate deviation from spec §4.2's constructor: it takes no `SystemOneService`. Under D60 Laya does not
 * participate in compression, SKILL selection or memory writing (spec §4.7 says so in its first paragraph), and
 * injecting a dependency that does nothing would read as a participant.
 */
class ContextManagerTest {

    private class RecordingMemory(private val parts: List<Segment> = emptyList()) : MemoryRetrieval {
        val queries = mutableListOf<String>()
        val limits = mutableListOf<Int>()
        override fun retrieve(query: String, limit: Int): List<Segment> {
            queries += query
            limits += limit
            return parts
        }
    }

    @Test
    fun `the two routes count tokens independently`() {
        val manager = ContextManager()
        manager.context(ContextRoute.WAKE).appendUser("开灯")
        manager.context(ContextRoute.PROACTIVE).appendUser("该提醒他了")

        val wake = manager.tokenCount(ContextRoute.WAKE)
        val proactive = manager.tokenCount(ContextRoute.PROACTIVE)

        assertTrue(wake > 0)
        // "开灯" is 6 UTF-8 bytes -> 2 tokens; "该提醒他了" is 15 bytes -> 5. Neither route's count includes the other.
        assertEquals(2, wake)
        assertEquals(5, proactive)
    }

    @Test
    fun `appending to one route never changes the other's token count`() {
        val manager = ContextManager()
        manager.context(ContextRoute.WAKE).appendUser("一")
        val before = manager.tokenCount(ContextRoute.PROACTIVE)

        manager.context(ContextRoute.WAKE).appendUser("二")
        manager.appendAssistantMessage(ContextRoute.WAKE, "好")

        assertEquals(before, manager.tokenCount(ContextRoute.PROACTIVE))
    }

    @Test
    fun `buildPrompt puts the user input in the history's last position`() {
        val manager = ContextManager()
        manager.context(ContextRoute.WAKE).appendUser("先说一句")
        manager.context(ContextRoute.WAKE).appendAssistant("好")

        val prompt = manager.buildPrompt(ContextRoute.WAKE, "现在这句")

        val last = prompt.history.last()
        assertTrue(last is ModelHistoryItem.Text)
        assertEquals(MessageRole.USER, last.role)
        assertEquals("现在这句", last.text)
        assertEquals(3, prompt.history.size)
    }

    @Test
    fun `buildPrompt does not mutate the transcript it read`() {
        // The user input belongs to the request, not to the stored history; appending it would double it on the
        // next turn, because the caller also records the turn's messages afterwards.
        val manager = ContextManager()
        manager.context(ContextRoute.WAKE).appendUser("先说一句")

        manager.buildPrompt(ContextRoute.WAKE, "现在这句")

        assertEquals(1, manager.context(ContextRoute.WAKE).size())
    }

    @Test
    fun `the two routes get different base instructions`() {
        val manager = ContextManager()

        val wake = manager.buildPrompt(ContextRoute.WAKE, "x").instructions
        val proactive = manager.buildPrompt(ContextRoute.PROACTIVE, "x").instructions

        assertTrue(wake != proactive, "the proactive route asks the model for a different job")
    }

    @Test
    fun `memory is retrieved with the user input and lands in memorySegments`() {
        val part = Segment(SegmentOrigin.MEMORY_EVIDENCE, false, "用户在淘宝买过手机壳")
        val memory = RecordingMemory(listOf(part))
        val manager = ContextManager(memory = memory)

        val prompt = manager.buildPrompt(ContextRoute.WAKE, "上次买的什么壳")

        assertEquals(listOf("上次买的什么壳"), memory.queries)
        assertEquals(listOf(part), prompt.memorySegments)
    }

    @Test
    fun `a manager with no memory injects nothing and does not throw`() {
        val prompt = ContextManager().buildPrompt(ContextRoute.WAKE, "x")

        assertEquals(emptyList(), prompt.memorySegments)
    }

    @Test
    fun `appendToolResult lands on the route it names`() {
        val manager = ContextManager()
        manager.appendToolResult(ContextRoute.PROACTIVE, "call-1", "ok")

        val proactive = manager.context(ContextRoute.PROACTIVE).history().single()
        assertTrue(proactive is ModelHistoryItem.ToolOutput)
        assertEquals("call-1", proactive.callId)
        assertEquals(0, manager.context(ContextRoute.WAKE).size())
    }

    @Test
    fun `the routes are the two the enum declares and they are distinct`() {
        val manager = ContextManager()
        assertTrue(manager.context(ContextRoute.WAKE) !== manager.context(ContextRoute.PROACTIVE))
        assertEquals(ContextRoute.WAKE, manager.context(ContextRoute.WAKE).route)
    }

    @Test
    fun `estimated tokens cover the instructions the history and the context`() {
        val manager = ContextManager(memory = RecordingMemory(listOf(Segment(SegmentOrigin.MEMORY_EVIDENCE, false, "记忆"))))
        manager.context(ContextRoute.WAKE).appendUser("一")

        val prompt = manager.buildPrompt(ContextRoute.WAKE, "二")

        assertTrue(
            prompt.estimatedTokens >=
                estimateTokens(prompt.instructions) + estimateTokens("二") + estimateTokens("记忆"),
            "the estimate must cover every part of the request, was ${prompt.estimatedTokens}",
        )
    }
}
