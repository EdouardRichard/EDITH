package com.sheen.rokid.agent

import com.sheen.rokid.plugin.MessageRole
import com.sheen.rokid.plugin.ModelHistoryItem
import com.sheen.rokid.plugin.ToolSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class PrefixHashTest {

    private fun hash(
        system: String = "system",
        tools: List<ToolSpec> = emptyList(),
        history: List<ModelHistoryItem> = emptyList(),
    ): String = PrefixHash.of(system, tools, history)

    private fun tool(
        name: String = "tool",
        description: String = "description",
        parametersJson: String = "{}",
    ) = ToolSpec(name, description, parametersJson)

    private fun user(text: String) = ModelHistoryItem.Text(MessageRole.USER, text)
    private fun assistant(text: String) = ModelHistoryItem.Text(MessageRole.ASSISTANT, text)

    @Test fun `the hash is stable for the same inputs`() {
        val first = hash(tools = listOf(tool("a")), history = listOf(user("u"), assistant("a")))
        val second = hash(tools = listOf(tool("a")), history = listOf(user("u"), assistant("a")))

        assertEquals(first, second)
    }

    @Test fun `the hash is lowercase hex of 64 chars`() {
        val digest = hash()

        assertEquals(64, digest.length)
        assertTrue(digest.all { it in "0123456789abcdef" }, "not lowercase hex: $digest")
    }

    @Test fun `the hash changes when the system text changes`() {
        assertNotEquals(hash(system = "one"), hash(system = "two"))
        assertNotEquals(hash(system = "a"), hash(system = "a "))
    }

    @Test fun `the hash changes when a tool definition changes`() {
        val base = tool("name", "description", """{"a":1}""")

        assertNotEquals(hash(tools = listOf(base)), hash(tools = listOf(base.copy(name = "name2"))))
        assertNotEquals(hash(tools = listOf(base)), hash(tools = listOf(base.copy(description = "other"))))
        assertNotEquals(
            hash(tools = listOf(base)),
            hash(tools = listOf(base.copy(parametersJson = """{"b":2}"""))),
        )
    }

    @Test fun `the hash changes when the tool order changes`() {
        assertNotEquals(
            hash(tools = listOf(tool("a"), tool("b"))),
            hash(tools = listOf(tool("b"), tool("a"))),
        )
    }

    @Test fun `the hash changes when the history changes`() {
        assertNotEquals(hash(), hash(history = listOf(user("u"))))
        assertNotEquals(hash(history = listOf(user("u"))), hash(history = listOf(assistant("u"))))
        assertNotEquals(hash(history = listOf(user("u"))), hash(history = listOf(user("v"))))
        assertNotEquals(
            hash(history = listOf(user("a"), assistant("b"))),
            hash(history = listOf(assistant("b"), user("a"))),
        )
    }

    @Test fun `tool call and output items are framed by type and their own fields`() {
        val call = ModelHistoryItem.ToolCall(callId = "call-1", name = "tool", argsJson = """{"x":1}""")
        val output = ModelHistoryItem.ToolOutput(callId = "call-1", output = """{"x":1}""")

        assertNotEquals(hash(history = listOf(call)), hash(history = listOf(output)))
        assertNotEquals(hash(history = listOf(call)), hash(history = listOf(call.copy(callId = "call-2"))))
        assertNotEquals(hash(history = listOf(call)), hash(history = listOf(call.copy(argsJson = "{}"))))
        assertNotEquals(hash(history = listOf(output)), hash(history = listOf(output.copy(callId = "call-2"))))
        assertNotEquals(hash(history = listOf(output)), hash(history = listOf(output.copy(output = "ok"))))
    }

    @Test fun `length framing keeps concatenated fields from colliding`() {
        val splitAfterFirstChar = listOf(ToolSpec("a", "bc", "{}"))
        val splitAfterSecondChar = listOf(ToolSpec("ab", "c", "{}"))

        assertNotEquals(hash(tools = splitAfterFirstChar), hash(tools = splitAfterSecondChar))
    }

    @Test fun `a different dynamic tail does not change the prefix hash because the caller excludes it`() {
        val systemSegments = listOf(Segment(SegmentOrigin.INSTRUCTIONS, trusted = true, text = "sys"))

        fun frame(perception: String, skillBody: String) = RequestFrame(
            systemSegments = systemSegments,
            conversationFlow = listOf(
                Segment(SegmentOrigin.PERCEPTION, trusted = false, text = perception),
                Segment(SegmentOrigin.SKILL_BODY, trusted = true, text = skillBody),
                Segment(SegmentOrigin.USER_INPUT, trusted = true, text = "now"),
            ),
            tools = emptyList(),
        )

        val first = frame("time=10:00", "body A")
        val second = frame("time=10:01", "body B")

        assertNotEquals(
            RequestLayout.dynamicTail(first.conversationFlow).map { it.text },
            RequestLayout.dynamicTail(second.conversationFlow).map { it.text },
        )
        assertEquals(
            RequestLayout.prefixHash(first.systemText, first.tools, emptyList()),
            RequestLayout.prefixHash(second.systemText, second.tools, emptyList()),
        )
    }
}
