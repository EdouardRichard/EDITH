package com.sheen.rokid.agent

import com.sheen.rokid.plugin.ImageRef
import com.sheen.rokid.plugin.MessageRole
import com.sheen.rokid.plugin.ModelHistoryItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 0.25 Task 4: the store records a whole turn's transcript, not just the text halves.
 *
 * The production plugin hands the turn over as `AgentRun.transcript`: this turn's user text, the tool calls the model
 * made, their outputs (with any image references) and the closing assistant text. Before 0.25 only `appendUser` /
 * `appendAssistant` existed, so every tool round and observation was lost across turns. The tests here pin the new
 * append path's ordering, its backward compatibility with a text-only store, and the one invariant that makes a partial
 * turn safe to record: **a tool call whose output never arrived is not stored** (0.25 spec §5.1, R10).
 */
class ConversationStoreTranscriptTest {

    private val reference = ImageRef(
        artifactId = "img-20261019-0001",
        mime = "image/jpeg",
        width = 768,
        height = 1664,
        sha256 = "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08",
    )

    /** One complete multi-step turn, hand-written in the order the loop leaves it. */
    private fun multiStepTurn() = listOf(
        ModelHistoryItem.Text(MessageRole.USER, "看一下屏幕"),
        ModelHistoryItem.Text(MessageRole.ASSISTANT, "我来看看"),
        ModelHistoryItem.ToolCall("call-1", "device", """{"action":"observe"}""", "item-1"),
        ModelHistoryItem.ToolOutput("call-1", """{"ok":true}""", listOf(reference)),
        ModelHistoryItem.Text(MessageRole.ASSISTANT, "看到了"),
    )

    // ------------------------------------------------------------ round trip

    @Test
    fun `append transcript preserves order, text roles and image references`() {
        val store = ConversationStore()
        val turn = multiStepTurn()

        store.appendTranscript(turn)

        assertEquals(turn, store.history(), "the stored sequence must be the transcript, unchanged and in order")
        val output = store.history().filterIsInstance<ModelHistoryItem.ToolOutput>().single()
        assertEquals(listOf(reference), output.images, "the image reference is part of the stored tool output")
    }

    @Test
    fun `tool items are stored as tool round segments with no conversation role`() {
        val store = ConversationStore()
        store.appendTranscript(
            listOf(
                ModelHistoryItem.Text(MessageRole.USER, "看一下屏幕"),
                ModelHistoryItem.ToolCall("call-1", "device", """{"action":"observe"}"""),
                ModelHistoryItem.ToolOutput("call-1", """{"ok":true}"""),
                ModelHistoryItem.Text(MessageRole.ASSISTANT, "看到了"),
            ),
        )

        val snapshot = store.snapshot()
        assertEquals(listOf(SegmentOrigin.TRANSCRIPT, SegmentOrigin.TOOL_ROUND, SegmentOrigin.TOOL_ROUND, SegmentOrigin.TRANSCRIPT),
            snapshot.map { it.origin })
        assertEquals(listOf(SegmentRole.USER, null, null, SegmentRole.ASSISTANT), snapshot.map { it.role })
        assertTrue(snapshot.drop(1).take(2).all { it.trusted }, "tool rounds are transcript data, so they stay trusted")
    }

    // ------------------------------------------------------------ backward compatibility

    @Test
    fun `a text only store written before the transcript path still reads and accepts new rounds`() {
        val store = ConversationStore()
        store.appendUser("旧问题")
        store.appendAssistant("旧回答")

        assertEquals(
            listOf(
                ModelHistoryItem.Text(MessageRole.USER, "旧问题"),
                ModelHistoryItem.Text(MessageRole.ASSISTANT, "旧回答"),
            ),
            store.history(),
            "the old append-only text store must keep reading byte for byte",
        )

        store.appendTranscript(
            listOf(
                ModelHistoryItem.Text(MessageRole.USER, "新问题"),
                ModelHistoryItem.ToolCall("call-2", "device", """{"action":"observe"}"""),
                ModelHistoryItem.ToolOutput("call-2", """{"ok":true}"""),
                ModelHistoryItem.Text(MessageRole.ASSISTANT, "新回答"),
            ),
        )

        assertEquals(6, store.size(), "the new turn is appended after the old store, never replacing it")
        assertEquals("新回答", (store.history().last() as ModelHistoryItem.Text).text)
    }

    @Test
    fun `an empty transcript appends nothing`() {
        val store = ConversationStore()
        store.appendUser("已有问题")
        store.appendAssistant("已有回答")
        val before = store.history()

        store.appendTranscript(emptyList())

        assertEquals(before, store.history())
    }

    // ------------------------------------------------------------ incomplete rounds (R10)

    @Test
    fun `a trailing tool call with no output is dropped instead of stored dangling`() {
        val store = ConversationStore()
        store.appendTranscript(
            listOf(
                ModelHistoryItem.Text(MessageRole.USER, "看一下屏幕"),
                ModelHistoryItem.ToolCall("complete", "device", """{"action":"observe"}"""),
                ModelHistoryItem.ToolOutput("complete", """{"ok":true}"""),
                ModelHistoryItem.ToolCall("dangling", "device", """{"action":"observe"}"""),
            ),
        )

        val history = store.history()
        assertTrue(history.any { it is ModelHistoryItem.ToolCall && it.callId == "complete" })
        assertTrue(history.any { it is ModelHistoryItem.ToolOutput && it.callId == "complete" })
        assertFalse(
            history.any { it is ModelHistoryItem.ToolCall && it.callId == "dangling" },
            "a call whose result never arrived must not be persisted: $history",
        )
    }

    @Test
    fun `a tool output with no opening call is dropped as an incomplete round`() {
        val store = ConversationStore()
        store.appendTranscript(
            listOf(
                ModelHistoryItem.Text(MessageRole.USER, "看一下屏幕"),
                ModelHistoryItem.ToolOutput("orphan", """{"ok":true}"""),
            ),
        )

        assertEquals(
            listOf(ModelHistoryItem.Text(MessageRole.USER, "看一下屏幕")),
            store.history(),
            "an output without the call it answers cannot round-trip",
        )
    }

    @Test
    fun `text items around an incomplete round are kept`() {
        // Only the tool pair is incomplete; a turn that ends early still leaves the user and assistant text.
        val store = ConversationStore()
        store.appendTranscript(
            listOf(
                ModelHistoryItem.Text(MessageRole.USER, "看一下屏幕"),
                ModelHistoryItem.ToolCall("dangling", "device", """{"action":"observe"}"""),
                ModelHistoryItem.Text(MessageRole.ASSISTANT, "这次没能看到"),
            ),
        )

        assertEquals(
            listOf(
                ModelHistoryItem.Text(MessageRole.USER, "看一下屏幕"),
                ModelHistoryItem.Text(MessageRole.ASSISTANT, "这次没能看到"),
            ),
            store.history(),
        )
    }

    @Test
    fun `the envelope still drops whole turns, tool round included`() {
        // The transcript path reuses the store's existing whole-turn eviction: dropping the oldest turn must take its
        // tool round with it, never leave a tool item orphaned at the head.
        val store = ConversationStore(maxItems = 3)
        store.appendTranscript(
            listOf(
                ModelHistoryItem.Text(MessageRole.USER, "第一问"),
                ModelHistoryItem.ToolCall("call-1", "device", "{}"),
                ModelHistoryItem.ToolOutput("call-1", "ok"),
                ModelHistoryItem.Text(MessageRole.ASSISTANT, "第一答"),
            ),
        )
        store.appendTranscript(
            listOf(
                ModelHistoryItem.Text(MessageRole.USER, "第二问"),
                ModelHistoryItem.Text(MessageRole.ASSISTANT, "第二答"),
            ),
        )

        assertEquals(
            listOf(
                ModelHistoryItem.Text(MessageRole.USER, "第二问"),
                ModelHistoryItem.Text(MessageRole.ASSISTANT, "第二答"),
            ),
            store.history(),
            "the old whole turn and its tool round leave together",
        )
    }
}
