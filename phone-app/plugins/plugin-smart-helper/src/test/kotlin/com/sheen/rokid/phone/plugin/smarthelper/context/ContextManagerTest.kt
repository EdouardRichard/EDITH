package com.sheen.rokid.phone.plugin.smarthelper.context

import com.sheen.rokid.agent.ContextPolicy
import com.sheen.rokid.agent.MemoryRetrieval
import com.sheen.rokid.agent.Segment
import com.sheen.rokid.agent.SegmentOrigin
import com.sheen.rokid.agent.TokenMeter
import com.sheen.rokid.plugin.MessageRole
import com.sheen.rokid.plugin.ModelHistoryItem
import com.sheen.rokid.phone.plugin.smarthelper.prompt.PromptKind
import com.sheen.rokid.phone.plugin.smarthelper.prompt.PromptPlan
import com.sheen.rokid.phone.plugin.smarthelper.prompt.PromptSection
import com.sheen.rokid.phone.plugin.smarthelper.smartProfile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Spec §4.2/§5.2: the two routes are independent, and the prompt is assembled from the session's measured budget.
 *
 * Phase 6 (design D-1/D-2) **de-skilled this manager**: it no longer assembles SKILL text, because the system slot's
 * `SKILL` segments are the plugin's only SKILL path and the old whole-body section would double-inject. The two
 * assertions that lived here ("every enabled body is injected whole" and "skills are injected in name order") were the
 * D60 form this phase removes; their intent — a SKILL really reaches the model — is preserved by
 * `SkillTwoLayerWiringTest`'s pull tests, on the layer that now carries it.
 *
 * 0.3 Task 11 moved the budget from the fixed six-way `TokenBudget` split to [ContextPolicy]: the instructions are the
 * prompt plan's `systemText`, and the history allowance is whatever `hardInputLimit` leaves after the measured system
 * text, memory and this turn's input. The load-bearing property is that the profile — not a constant — decides the
 * size, so an 8K local model and a large-window model cannot get the same prompt budget.
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

    /** A plan whose single section is [text]; the hash is real so it is not a made-up value. */
    private fun plan(text: String): PromptPlan {
        val sections = listOf(PromptSection("test-system", PromptKind.STATIC, text))
        return PromptPlan("0.3.0", sections, text, PromptPlan.hashOf(sections))
    }

    /**
     * The fixture profile's limits. The defaults match `smartProfile`: a 2 000-token hard input out of a 4 000-token
     * usable window after the output and safety reservations.
     */
    private fun policy(
        inputBudgetTokens: Int = 2_000,
        usableWindowTokens: Int = 4_000,
        maxOutputTokens: Int = 1_000,
        safetyTokens: Int = 100,
    ): ContextPolicy = ContextPolicy.from(
        smartProfile(
            usableWindowTokens = usableWindowTokens,
            inputBudgetTokens = inputBudgetTokens,
            maxOutputTokens = maxOutputTokens,
            safetyTokens = safetyTokens,
        ),
    )

    private fun manager(
        memory: MemoryRetrieval? = null,
        policy: ContextPolicy = policy(),
        instructions: (ContextRoute) -> String = { route ->
            if (route == ContextRoute.WAKE) "WAKE SYSTEM" else "PROACTIVE SYSTEM"
        },
    ): ContextManager = ContextManager(
        memory = memory,
        policy = policy,
        promptPlan = { route -> plan(instructions(route)) },
    )

    @Test
    fun `buildPrompt uses the prompt plan instructions and the profile policy`() {
        val planned = plan("SYSTEM")
        val sessionPolicy = policy(
            inputBudgetTokens = 6_912,
            usableWindowTokens = 8_192,
            maxOutputTokens = 1_024,
            safetyTokens = 256,
        )
        val manager = ContextManager(policy = sessionPolicy, promptPlan = { planned })

        val prompt = manager.buildPrompt(ContextRoute.WAKE, "你好")

        assertEquals("SYSTEM", prompt.instructions)
        assertTrue(prompt.historyBudgetTokens in 1..sessionPolicy.hardInputLimit)
        assertEquals(
            sessionPolicy.hardInputLimit - TokenMeter.estimate("SYSTEM") - TokenMeter.estimate("你好"),
            prompt.historyBudgetTokens,
            "the history allowance is the hard input limit minus the measured system text and input",
        )
    }

    @Test
    fun `a token budget changes when the profile changes, not because of a fixed 4096`() {
        val small = manager(policy = policy(inputBudgetTokens = 4, maxOutputTokens = 1, safetyTokens = 1))
        val large = manager(policy = policy(inputBudgetTokens = 200_000, usableWindowTokens = 1_000_000))

        // §6.3: the compression allowance is softLimit = floor(hardInputLimit * 0.75).
        assertEquals(3, small.compressionBudgetTokens(), "a 4-token hard input leaves a 3-token compression allowance")
        assertEquals(150_000, large.compressionBudgetTokens())
        assertTrue(
            large.compressionBudgetTokens() > small.compressionBudgetTokens(),
            "the profile, not a constant, must decide the budget",
        )
        assertTrue(small.compressionBudgetTokens() != 4096 && large.compressionBudgetTokens() != 4096)
    }

    @Test
    fun `the dynamic history budget subtracts the measured memory as well`() {
        val memory = RecordingMemory(listOf(Segment(SegmentOrigin.MEMORY_EVIDENCE, false, "记忆")))
        val sessionPolicy = policy()
        val manager = manager(memory = memory, policy = sessionPolicy, instructions = { "SYSTEM" })

        val prompt = manager.buildPrompt(ContextRoute.WAKE, "你好")

        assertEquals(
            sessionPolicy.hardInputLimit - TokenMeter.estimate("SYSTEM") - TokenMeter.estimate("记忆") -
                TokenMeter.estimate("你好"),
            prompt.historyBudgetTokens,
        )
    }

    @Test
    fun `a history larger than the remaining allowance is flagged over budget`() {
        // The policy's hard input is 8 tokens; the instruction and the short input are measured out of it, and the
        // retained history is far beyond what is left. That must be visible, because it is the compression trigger.
        val manager = manager(policy = policy(inputBudgetTokens = 8, maxOutputTokens = 1, safetyTokens = 1))
        manager.context(ContextRoute.WAKE).appendUser("这是一段很长很长的历史记录")

        val prompt = manager.buildPrompt(ContextRoute.WAKE, "好")

        assertTrue(prompt.historyBudgetTokens in 0..7, "a remaining allowance must never be negative")
        assertTrue(prompt.overHistoryBudget, "the retained history outgrew the measured allowance")
    }

    @Test
    fun `a plan that alone overruns the hard input leaves no history allowance`() {
        // Fail closed on the arithmetic rather than inventing room: the remaining allowance is 0, so every history is
        // over budget. Whether the turn is then refused is the send-time budgeter's decision (§5.2 PROMPT_EXCEEDS_BUDGET).
        val manager = manager(
            policy = policy(inputBudgetTokens = 4, maxOutputTokens = 1, safetyTokens = 1),
            instructions = { "SYSTEM" },
        )

        val prompt = manager.buildPrompt(ContextRoute.WAKE, "你好")

        assertEquals(0, prompt.historyBudgetTokens)
        assertTrue(prompt.overHistoryBudget)
    }

    @Test
    fun `the two routes count tokens independently`() {
        val manager = manager()
        manager.context(ContextRoute.WAKE).appendUser("开灯")
        manager.context(ContextRoute.PROACTIVE).appendUser("该提醒他了")

        val wake = manager.tokenCount(ContextRoute.WAKE)
        val proactive = manager.tokenCount(ContextRoute.PROACTIVE)

        assertTrue(wake > 0)
        // `TokenMeter` counts every non-ASCII code point as one token: 2 and 5. Neither count includes the other.
        assertEquals(2, wake)
        assertEquals(5, proactive)
    }

    @Test
    fun `appending to one route never changes the other's token count`() {
        val manager = manager()
        manager.context(ContextRoute.WAKE).appendUser("一")
        val before = manager.tokenCount(ContextRoute.PROACTIVE)

        manager.context(ContextRoute.WAKE).appendUser("二")
        manager.appendAssistantMessage(ContextRoute.WAKE, "好")

        assertEquals(before, manager.tokenCount(ContextRoute.PROACTIVE))
    }

    @Test
    fun `buildPrompt puts the user input in the history's last position`() {
        val manager = manager()
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
        val manager = manager()
        manager.context(ContextRoute.WAKE).appendUser("先说一句")

        manager.buildPrompt(ContextRoute.WAKE, "现在这句")

        assertEquals(1, manager.context(ContextRoute.WAKE).size())
    }

    @Test
    fun `the two routes get the plan's instructions for their own route`() {
        val manager = manager()

        val wake = manager.buildPrompt(ContextRoute.WAKE, "x").instructions
        val proactive = manager.buildPrompt(ContextRoute.PROACTIVE, "x").instructions

        assertEquals("WAKE SYSTEM", wake)
        assertEquals("PROACTIVE SYSTEM", proactive)
        assertTrue(wake != proactive, "the proactive route asks the model for a different job")
    }

    @Test
    fun `memory is retrieved with the user input and lands in memorySegments`() {
        val part = Segment(SegmentOrigin.MEMORY_EVIDENCE, false, "用户在淘宝买过手机壳")
        val memory = RecordingMemory(listOf(part))
        val manager = manager(memory = memory)

        val prompt = manager.buildPrompt(ContextRoute.WAKE, "上次买的什么壳")

        assertEquals(listOf("上次买的什么壳"), memory.queries)
        assertEquals(listOf(part), prompt.memorySegments)
    }

    @Test
    fun `a manager with no memory injects nothing and does not throw`() {
        val prompt = manager().buildPrompt(ContextRoute.WAKE, "x")

        assertEquals(emptyList(), prompt.memorySegments)
    }

    @Test
    fun `appendToolResult lands on the route it names`() {
        val manager = manager()
        manager.appendToolResult(ContextRoute.PROACTIVE, "call-1", "ok")

        val proactive = manager.context(ContextRoute.PROACTIVE).history().single()
        assertTrue(proactive is ModelHistoryItem.ToolOutput)
        assertEquals("call-1", proactive.callId)
        assertEquals(0, manager.context(ContextRoute.WAKE).size())
    }

    @Test
    fun `the routes are the two the enum declares and they are distinct`() {
        val manager = manager()
        assertTrue(manager.context(ContextRoute.WAKE) !== manager.context(ContextRoute.PROACTIVE))
        assertEquals(ContextRoute.WAKE, manager.context(ContextRoute.WAKE).route)
    }

    @Test
    fun `estimated tokens cover the instructions the history and the context`() {
        val manager = manager(memory = RecordingMemory(listOf(Segment(SegmentOrigin.MEMORY_EVIDENCE, false, "记忆"))))
        manager.context(ContextRoute.WAKE).appendUser("一")

        val prompt = manager.buildPrompt(ContextRoute.WAKE, "二")

        assertTrue(
            prompt.estimatedTokens >=
                estimateTokens(prompt.instructions) + estimateTokens("二") + estimateTokens("记忆"),
            "the estimate must cover every part of the request, was ${prompt.estimatedTokens}",
        )
    }
}
