package edith.plugins.smart.context

import edith.agent.MemoryRetrieval
import edith.plugin.ContextPart
import edith.plugin.MessageRole
import edith.plugin.ModelHistoryItem
import edith.plugins.smart.SmartPrompts
import edith.plugins.smart.skill.Skill

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
    val context: List<ContextPart>,
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
     * Assembles one request.
     *
     * The SKILL section is built by concatenating **every** supplied skill, in name order, with no scoring and no
     * cap (C9/D60). The caller has already filtered to `enabled` ones — `SkillManager.enabled()` — so nothing here
     * re-decides membership.
     */
    fun buildPrompt(
        route: ContextRoute,
        userInput: String,
        skills: List<Skill> = emptyList(),
    ): PromptAssembly {
        val context = context(route)
        val instructions = assembleInstructions(route, skills)
        val history = context.history() + ModelHistoryItem.Text(MessageRole.USER, userInput)
        val memoryParts = runCatching { memory?.retrieve(userInput, MEMORY_LIMIT) }.getOrNull().orEmpty()

        val historyTokens = history.sumOf { estimateTokens(it.estimatedText()) }
        val historyBudget = historyBudgetWithLeftovers(
            categories = budget.categories(),
            skillsUsed = estimateTokens(skillSection(skills)),
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
            context = memoryParts,
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

    private fun assembleInstructions(route: ContextRoute, skills: List<Skill>): String {
        val base = baseInstructions(route)
        val section = skillSection(skills)
        return if (section.isEmpty()) base else "$base\n\n$section"
    }

    private fun skillSection(skills: List<Skill>): String =
        skills.sortedBy { it.name }.joinToString("\n\n") { skill ->
            buildString {
                append("## SKILL: ").append(skill.name).append('\n')
                append(skill.description).append('\n')
                append(skill.body)
            }
        }

    /**
     * The text a history item contributes to the estimate.
     *
     * A tool call contributes its arguments and a tool output its payload; the identities are bookkeeping the model
     * does not read, so counting them would inflate the estimate without bounding anything real.
     */
    private fun ModelHistoryItem.estimatedText(): String = when (this) {
        is ModelHistoryItem.Text -> text
        is ModelHistoryItem.ToolCall -> argsJson
        is ModelHistoryItem.ToolOutput -> output
    }

    private companion object {
        /** Spec §5.7: the top-5 related memories are injected. */
        const val MEMORY_LIMIT = 5
    }
}
