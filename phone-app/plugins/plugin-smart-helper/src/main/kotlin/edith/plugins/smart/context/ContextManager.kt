package edith.plugins.smart.context

import com.sheen.rokid.agent.MemoryRetrieval
import com.sheen.rokid.agent.Segment
import com.sheen.rokid.plugin.MessageRole
import com.sheen.rokid.plugin.ModelHistoryItem
import edith.plugins.smart.SmartPrompts

/**
 * What [ContextManager.buildPrompt] hands the loop: the request's pieces, already assembled and measured.
 *
 * [history] ends on this turn's user message and is **not** written back — the caller records the turn's own
 * messages when the turn ends, so appending here would double them on the next one.
 */
data class PromptAssembly(
    val route: ContextRoute,
    val instructions: String,
    val history: List<ModelHistoryItem>,
    val memorySegments: List<Segment>,
    val estimatedTokens: Int,
    /** The history allowance this assembly was measured against (spec §4.3). */
    val historyBudgetTokens: Int,
    /** Whether the history has outgrown that allowance, which is the compression trigger (spec §4.4). */
    val overHistoryBudget: Boolean,
)

/**
 * The two conversation routes and the prompt each one builds (spec §4.2/§4.3).
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
    private val budget: TokenBudget = TokenBudget(),
    /**
     * The route's own system prompt. A seam rather than a constant because the two routes ask the model for
     * different jobs: the wake path answers a request, the proactive path decides whether to speak unprompted.
     */
    private val baseInstructions: (ContextRoute) -> String = { route ->
        when (route) {
            ContextRoute.WAKE -> SmartPrompts.instructions()
            ContextRoute.PROACTIVE -> SmartPrompts.proactiveInstructions()
        }
    },
) {

    private val contexts: Map<ContextRoute, ConversationContext> =
        ContextRoute.entries.associateWith { ConversationContext(it) }

    fun context(route: ContextRoute): ConversationContext = contexts.getValue(route)

    /**
     * Assembles one request: the route's base instructions, the transcript this turn continues, and the memory for
     * the utterance. Nothing here decides what SKILLs the model sees — that is the frame's `SKILL` segments.
     */
    fun buildPrompt(
        route: ContextRoute,
        userInput: String,
    ): PromptAssembly {
        val context = context(route)
        val instructions = baseInstructions(route)
        val history = context.history() + ModelHistoryItem.Text(MessageRole.USER, userInput)
        val memoryParts = runCatching { memory?.retrieve(userInput, MEMORY_LIMIT) }.getOrNull().orEmpty()

        val historyTokens = history.sumOf { estimateTokens(it.estimatedText()) }
        // Phase 6: this assembly carries no SKILL text, so the SKILL category is unused and its allowance flows to
        // history exactly as an unused category always has (spec §4.3). The catalog's own small cost rides the frame
        // and is not part of this estimate; it is bounded by the governor's cap, not by the token split.
        val historyBudget = historyBudgetWithLeftovers(
            categories = budget.categories(),
            skillsUsed = 0,
            memoryUsed = memoryParts.sumOf { estimateTokens(it.text) },
        )
        val estimated = estimateTokens(instructions) +
            historyTokens +
            memoryParts.sumOf { estimateTokens(it.text) }
        // `userInput` is already inside `history`; it is not counted twice.

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

    /** The allowance a compression measures a route's history against (spec §4.3/§4.4). */
    fun historyBudgetTokens(): Int = budget.historyBudgetTokens()

    private companion object {
        /** Spec §5.7: the top-5 related memories are injected. */
        const val MEMORY_LIMIT = 5
    }
}
