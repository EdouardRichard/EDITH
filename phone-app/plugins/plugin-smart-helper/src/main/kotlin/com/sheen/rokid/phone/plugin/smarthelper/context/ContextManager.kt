package com.sheen.rokid.phone.plugin.smarthelper.context

import com.sheen.rokid.agent.ContextPolicy
import com.sheen.rokid.agent.MemoryRetrieval
import com.sheen.rokid.agent.Segment
import com.sheen.rokid.plugin.MessageRole
import com.sheen.rokid.plugin.ModelHistoryItem
import com.sheen.rokid.phone.plugin.smarthelper.prompt.PromptPlan

/**
 * What [ContextManager.buildPrompt] hands the loop: the request's pieces, already assembled and measured.
 *
 * [history] ends on this turn's user message and is **not** written back — the caller records the turn's own
 * messages when the turn ends, so appending here would double them on the next one.
 *
 * [historyBudgetTokens] is the **remaining** history allowance for this request (0.3 design §5.2):
 * `policy.hardInputLimit - measured(instructions) - measured(memory) - measured(input)`. It is request-scoped
 * because the system text, the retrieved memory and the input are measured facts, not fixed shares.
 */
data class PromptAssembly(
    val route: ContextRoute,
    val instructions: String,
    val history: List<ModelHistoryItem>,
    val memorySegments: List<Segment>,
    val estimatedTokens: Int,
    /** The history allowance this assembly was measured against (0.3 design §5.2). */
    val historyBudgetTokens: Int,
    /** Whether the history has outgrown that allowance, which is the compression trigger (0.3 design §6.3). */
    val overHistoryBudget: Boolean,
)

/**
 * The two conversation routes and the prompt each one builds (0.3 design §5.1/§5.2).
 *
 * Two [ConversationContext]s, never merged: C3 makes the wake path and the proactive path independent histories
 * that share only the SKILL library, the memory store and the local model services.
 *
 * **It no longer assembles SKILL text.** Phase 6 (design D-1/D-2) moved the SKILL layer to the request frame's
 * `SegmentOrigin.SKILL` segments — a bounded catalog plus the bodies pulled this turn — owned by the plugin's
 * `systemSlot`. Keeping the old whole-library section here as well would put every SKILL into the model twice
 * (`systemSlot` already takes `prompt.instructions`), which is why the `skills` parameter and this manager's
 * `assembleInstructions`/`skillSection` were deleted rather than left unused.
 *
 * **The system text is a [PromptPlan], and the budget is a [ContextPolicy].** Task 11 replaced the plugin's own
 * six-way token split with the policy derived from the session's `ModelProfile`: the plan supplies the instructions,
 * and the policy supplies the hard input limit every measurement is taken out of. A missing profile is not papered
 * over with a default here — the plugin fails closed and does not build a manager at all.
 *
 * **It holds no `SystemOneService`**, although spec §4.2's constructor sketch does. Under D60 Laya participates in
 * neither compression, SKILL selection nor memory writing — spec §4.7 says so in its first paragraph — so injecting
 * it would advertise a dependency that has no call site. The one place Laya is used is the proactive gate, which is
 * a different object.
 */
class ContextManager(
    /**
     * The long-term-memory port, or `null` for a manager with no memory. The production wiring passes the plugin's
     * retrieval; a `null` manager injects nothing rather than failing, so a host without memory still answers.
     */
    private val memory: MemoryRetrieval? = null,
    /** The session's limits, derived from the configured `ModelProfile` by `ContextPolicy.from` (0.3 design §5.2). */
    private val policy: ContextPolicy,
    /**
     * The route's own [PromptPlan]. A seam rather than a constant because the two routes ask the model for different
     * jobs: the wake path answers a request, the proactive path decides whether to speak unprompted. Production passes
     * the session's **pinned** plans (`PromptCatalog.plan(route, deviceProfile)` loaded once in `activate`), so the
     * system text cannot change under a live conversation.
     */
    private val promptPlan: (ContextRoute) -> PromptPlan,
) {

    private val contexts: Map<ContextRoute, ConversationContext> =
        ContextRoute.entries.associateWith { ConversationContext(it) }

    fun context(route: ContextRoute): ConversationContext = contexts.getValue(route)

    /**
     * Assembles one request: the route's planned instructions, the transcript this turn continues, and the memory for
     * the utterance. Nothing here decides what SKILLs the model sees — that is the frame's `SKILL` segments.
     *
     * The history allowance is what the policy's hard input limit leaves after the measured instructions, memory and
     * this turn's input (0.3 design §5.2). The clamp at zero is deliberate: a request whose fixed pieces alone exceed
     * the hard input has **no** history allowance, which marks the assembly over budget rather than inventing room.
     */
    fun buildPrompt(
        route: ContextRoute,
        userInput: String,
    ): PromptAssembly {
        val context = context(route)
        val instructions = promptPlan(route).systemText
        val history = context.history() + ModelHistoryItem.Text(MessageRole.USER, userInput)
        val memoryParts = runCatching { memory?.retrieve(userInput, MEMORY_LIMIT) }.getOrNull().orEmpty()

        val instructionTokens = estimateTokens(instructions)
        val historyTokens = history.sumOf { estimateTokens(it.estimatedText()) }
        val memoryTokens = memoryParts.sumOf { estimateTokens(it.text) }
        // `userInput` is already inside `history`; it is subtracted once here, never counted twice.
        val historyBudget = (policy.hardInputLimit - instructionTokens - memoryTokens - estimateTokens(userInput))
            .coerceAtLeast(0)
        val estimated = instructionTokens + historyTokens + memoryTokens

        return PromptAssembly(
            route = route,
            instructions = instructions,
            history = history,
            memorySegments = memoryParts,
            estimatedTokens = estimated,
            historyBudgetTokens = historyBudget,
            overHistoryBudget = historyTokens > historyBudget,
        )
    }

    fun appendAssistantMessage(route: ContextRoute, text: String) = context(route).appendAssistant(text)

    fun appendToolResult(route: ContextRoute, toolCallId: String, result: String) =
        context(route).appendToolOutput(toolCallId, result)

    /** The route's own transcript size in estimated tokens; the two routes never share a count. */
    fun tokenCount(route: ContextRoute): Int =
        context(route).history().sumOf { estimateTokens(it.estimatedText()) }

    /**
     * The allowance a compression measures a route's history against (0.3 design §6.3).
     *
     * It is the policy's `softLimit` — the end-of-turn threshold — rather than the request-scoped remaining budget
     * above: the compressor runs at the turn's tail, where there is no in-flight request whose fixed pieces could be
     * measured. `ContextCompressor` receives this value when the plugin builds it.
     */
    fun compressionBudgetTokens(): Int = policy.softLimit

    /** The policy's summary output allowance (0.3 design §6.4), handed to the compressor with [compressionBudgetTokens]. */
    fun summaryMaxTokens(): Int = policy.summaryMaxTokens

    private companion object {
        /** Spec §5.7: the top-5 related memories are injected. */
        const val MEMORY_LIMIT = 5
    }
}
