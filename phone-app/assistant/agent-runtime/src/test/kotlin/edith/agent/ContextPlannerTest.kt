package edith.agent

import com.sheen.rokid.plugin.MessageRole
import com.sheen.rokid.plugin.ModelHistoryItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The spec's §6.1 degradation order, driven through a caller-supplied `cost` (the real estimator and window budget live
 * in `model-runtime` / `plugin-api` and are wired by Task 14, so nothing here counts tokens).
 */
class ContextPlannerTest {

    // ------------------------------------------------------------------ fixtures

    /** Caller-supplied cost: content characters per item, identical in kind to `ContextBudgeter.estimateText`. */
    private fun charCost(item: ModelHistoryItem): Int = when (item) {
        is ModelHistoryItem.Text -> item.text.length
        is ModelHistoryItem.ToolCall -> item.name.length + item.argsJson.length
        is ModelHistoryItem.ToolOutput -> item.output.length
    }

    private fun planner(budget: Int, summarizeAbove: Int = 400, keepLastToolRounds: Int = 2) =
        ContextPlanner(budget, ::charCost, summarizeAbove, keepLastToolRounds)

    private fun user(text: String) = ModelHistoryItem.Text(MessageRole.USER, text)

    private fun assistant(text: String) = ModelHistoryItem.Text(MessageRole.ASSISTANT, text)

    private fun toolCall(id: String) = ModelHistoryItem.ToolCall(id, "t$id", "{}", id)

    private fun toolOutput(id: String, output: String) = ModelHistoryItem.ToolOutput(id, output)

    /** One shipped-shape group `[USER, ASSISTANT, ToolCall, ToolOutput]`; char cost `2 + 2 + 4 + output.length`. */
    private fun toolGroup(id: String, output: String) =
        listOf(user("u$id"), assistant("a$id"), toolCall(id), toolOutput(id, output))

    private val fourRounds =
        toolGroup("1", "o1") + toolGroup("2", "o2") + toolGroup("3", "o3") + toolGroup("4", "o4")

    private fun total(history: List<ModelHistoryItem>) = history.sumOf(::charCost)

    /**
     * Local re-implementation of `model-runtime`'s `ContextBudgeter` history-shape rule. `agent-runtime` does not
     * depend on `model-runtime`, so the shipped validator cannot be imported here; this mirrors it: the transcript
     * opens with a `USER` text, text alternates `USER`/`ASSISTANT` except that an `ASSISTANT` text may directly follow
     * a tool item, a run of tool items must be opened by an `ASSISTANT` text, a group opens **only at a `Text(USER)`**
     * (so one group is one user turn), and the list never ends on a lone pending `USER` text. Validating the planner's
     * output against this is the point.
     */
    private fun shapeOk(history: List<ModelHistoryItem>): Boolean {
        var lastTextRole: MessageRole? = null
        var inToolRun = false
        var groupSize = 0
        var groupHasTools = false
        for (item in history) {
            when (item) {
                is ModelHistoryItem.Text -> {
                    when {
                        lastTextRole == null -> if (item.role != MessageRole.USER) return false
                        inToolRun -> Unit
                        lastTextRole == MessageRole.USER -> if (item.role != MessageRole.ASSISTANT) return false
                        else -> if (item.role != MessageRole.USER) return false
                    }
                    if (groupSize == 0 || item.role == MessageRole.USER) {
                        groupSize = 1
                        groupHasTools = false
                    } else {
                        groupSize++
                    }
                    lastTextRole = item.role
                    inToolRun = false
                }
                is ModelHistoryItem.ToolCall, is ModelHistoryItem.ToolOutput -> {
                    if (!inToolRun && lastTextRole != MessageRole.ASSISTANT) return false
                    inToolRun = true
                    groupSize++
                    groupHasTools = true
                }
            }
        }
        return !(groupSize == 1 && !groupHasTools)
    }

    // ------------------------------------------------------- 1. within budget

    @Test fun `a history within budget is returned untouched and never notes a drop`() {
        val history = fourRounds

        val planned = planner(budget = total(history)).plan(history)

        assertSame(history, planned.history)
        assertEquals(0, planned.droppedItems)
        assertNull(planned.noteToModel)
        assertTrue(shapeOk(planned.history))
    }

    // ------------------------------------------------- 2. summarize tool results

    @Test fun `an over budget history summarizes only the tool outputs longer than the threshold`() {
        val atThreshold = "y".repeat(20)
        val longOutput = "x".repeat(100)
        val history = toolGroup("1", atThreshold) + toolGroup("2", longOutput)
        val summarized = toolGroup("1", atThreshold) +
            toolGroup("2", "x".repeat(20) + "\u2026(summarized)")
        val budget = total(summarized)

        val planned = planner(budget, summarizeAbove = 20).plan(history)

        assertTrue(total(history) > budget, "the raw history must actually exceed the budget")
        assertEquals(summarized, planned.history)
        assertEquals(0, planned.droppedItems)
        assertNull(planned.noteToModel)
        assertTrue(shapeOk(planned.history))
    }

    // ------------------------------- 3. drop completed steps, keep conclusions

    @Test fun `dropping intermediate tool rounds keeps the last rounds and counts every dropped item`() {
        val history = fourRounds

        val planned = planner(budget = 28).plan(history)

        assertEquals(
            toolGroup("1", "o1").take(2) + toolGroup("2", "o2").take(2) +
                toolGroup("3", "o3") + toolGroup("4", "o4"),
            planned.history,
        )
        // The last two rounds keep their call/output pair; the oldest two lose theirs.
        assertEquals(
            listOf("3", "4"),
            planned.history.filterIsInstance<ModelHistoryItem.ToolCall>().map { it.callId },
        )
        assertEquals(
            listOf("3", "4"),
            planned.history.filterIsInstance<ModelHistoryItem.ToolOutput>().map { it.callId },
        )
        assertEquals(4, planned.droppedItems)
        assertNotNull(planned.noteToModel)
        assertTrue(planned.noteToModel!!.isNotBlank())
        assertTrue(planned.noteToModel!!.contains("4"))
        assertTrue(shapeOk(planned.history))
    }

    // --------------------------------------- 4. truncate the oldest and say so

    @Test fun `a history that still does not fit truncates the oldest groups and marks how many items went`() {
        val history = fourRounds

        val planned = planner(budget = 20).plan(history)

        assertEquals(toolGroup("3", "o3") + toolGroup("4", "o4"), planned.history)
        assertEquals(8, planned.droppedItems)
        assertNotNull(planned.noteToModel)
        assertTrue(planned.noteToModel!!.isNotBlank())
        assertTrue(planned.noteToModel!!.contains("8"))
        assertTrue(shapeOk(planned.history))
    }

    @Test fun `a user turn that spans two tool rounds is one group and is truncated whole`() {
        // No `USER` text opens the second tool round: it continues the same assistant turn, so the group boundary
        // keeps it inside the user turn that produced it. Truncation can therefore only drop the whole seven-item
        // turn; cropping at the second assistant text would leave a leading assistant text the budgeter rejects.
        val history = listOf(
            user("u1"), assistant("a1"), toolCall("1"), toolOutput("1", "o1"),
            assistant("a1b"), toolCall("2"), toolOutput("2", "o2"),
            user("u2"), assistant("a2"),
        )

        val planned = planner(budget = 14).plan(history)

        assertEquals(listOf(user("u2"), assistant("a2")), planned.history)
        assertEquals(7, planned.droppedItems)
        assertNotNull(planned.noteToModel)
        assertTrue(planned.noteToModel!!.contains("7"))
        assertTrue(shapeOk(planned.history))
    }

    @Test fun `truncation is never silent so a drop always carries a note and a fit never does`() {
        val cases = listOf(
            "within budget" to total(fourRounds),
            "drop intermediate rounds" to 28,
            "truncate oldest groups" to 20,
        )
        for ((name, budget) in cases) {
            val planned = planner(budget).plan(fourRounds)
            assertEquals(
                planned.droppedItems > 0,
                planned.noteToModel != null,
                "$name violated the droppedItems/noteToModel biconditional",
            )
        }
    }

    // ------------------------------------------------------------ 5. shape safety

    @Test fun `every degradation path still satisfies the shipped history shape`() {
        val longOutput = "x".repeat(100)
        val fixtures = listOf(
            "within budget" to (fourRounds to total(fourRounds)),
            "summarize" to (toolGroup("1", "o1") + toolGroup("2", longOutput) to 60),
            "drop intermediate rounds" to (fourRounds to 28),
            "truncate oldest groups" to (fourRounds to 20),
        )

        for ((name, fixture) in fixtures) {
            val (history, budget) = fixture
            val planned = planner(budget, summarizeAbove = 20).plan(history)
            assertTrue(shapeOk(planned.history), "$name produced a history the budgeter would reject")
        }
    }

    // ----------------------------------------------------- 6. memory boundary

    @Test fun `MemoryRetrieval is one method that returns context parts`() {
        val declared = MemoryRetrieval::class.java.declaredMethods
            .filter { it.declaringClass == MemoryRetrieval::class.java }

        assertEquals(listOf("retrieve"), declared.map { it.name })
        val retrieve = declared.single()
        assertEquals(listOf(String::class.java, Int::class.javaPrimitiveType), retrieve.parameterTypes.toList())
        assertEquals(List::class.java, retrieve.returnType)
        assertTrue(
            retrieve.modifiers and java.lang.reflect.Modifier.ABSTRACT != 0,
            "retrieve must stay abstract: P3.1 ships the boundary, not an implementation",
        )
    }

    @Test fun `the retrieval boundary can be implemented and hands back segments`() {
        val parts = listOf(
            Segment(SegmentOrigin.MEMORY_EVIDENCE, false, "喜欢喝茶"),
            Segment(SegmentOrigin.MEMORY_EVIDENCE, false, "上午工作"),
        )
        val retrieval: MemoryRetrieval = FakeMemoryRetrieval(parts)

        assertEquals(parts, retrieval.retrieve("preferences", 5))
        assertEquals(parts.take(1), retrieval.retrieve("preferences", 1))
    }
}
