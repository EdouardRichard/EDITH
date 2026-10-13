package edith.plugins.smart.context

import edith.plugin.Capability
import edith.plugin.EmbedResult
import edith.plugin.EmbeddingService
import edith.plugin.ModelHistoryItem
import edith.plugin.ModelInfo
import edith.plugins.smart.SmartFakeStorage
import edith.plugins.smart.SmartTestRig
import edith.plugins.smart.streamText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * edith-0.2 Phase B (B1/B6/B7): the session's own prompt assembly, read out of the request the model received.
 *
 * `ContextManager` was built, tested and **never called**: `runTurn` sent `SmartPrompts.instructions()`, passed no
 * history at all (review finding I1), and let the gateway retrieve memory on its own. So "two independent
 * conversations" and "the assistant remembers this session" were properties of a class nothing ran. These tests
 * activate the plugin the way the composition does and read `ModelRequest.history` / `.context` / `.instructions`.
 */
class SessionContextWiringTest {

    private class ScriptedEmbeddings : EmbeddingService {
        val embedded = mutableListOf<String>()

        override fun embed(text: String): EmbedResult {
            embedded += text
            return EmbedResult.Success(
                FloatArray(DIM).also { v ->
                    if (text.contains("绿色的菜") || text == "香菜") v[0] = 1f else v[1] = 1f
                },
            )
        }

        override val isAvailable = true
        override val modelInfo: ModelInfo? = null
        override val dimensions = DIM
    }

    private fun activatedRig(embeddings: EmbeddingService): SmartTestRig {
        val rig = SmartTestRig()
        rig.activate(
            overrides = mapOf(
                Capability.Storage to SmartFakeStorage(),
                Capability.Embedding to embeddings,
            ),
        )
        rig.model.onRequest = { request, listener -> streamText(request, listener, "好。") }
        return rig
    }

    @Test
    fun `the second turn of a session carries the first turn in its history`() {
        val rig = activatedRig(ScriptedEmbeddings())

        rig.wakeAndFinal("第一个问题", turnId = "asr-1")
        // The wake path has a 3 s cooldown, so a second wake inside it is skipped and never reaches the model.
        rig.tick(4_000)
        rig.wakeAndFinal("第二个问题", turnId = "asr-2")

        val history = rig.model.requests.last().history
            .filterIsInstance<ModelHistoryItem.Text>()
            .map { it.text }
        assertTrue(
            history.contains("第一个问题"),
            "the session transcript never reached the model — the prior turn is missing: $history",
        )
        assertTrue(
            history.contains("好。"),
            "the assistant's own reply is missing from the transcript: $history",
        )
        // `buildPrompt`'s history ends on this turn's user message and the loop appends the utterance itself, so the
        // assembly's last item must be dropped: a caller that forgot would ask the model the same thing twice.
        assertTrue(
            history.none { it == "第二个问题" },
            "this turn's utterance is in the history as well as in the input, so it was asked twice: $history",
        )
    }

    @Test
    fun `a re-activated session starts with no transcript`() {
        val rig = activatedRig(ScriptedEmbeddings())
        rig.wakeAndFinal("上一个会话的问题", turnId = "asr-1")
        rig.tick(4_000)

        // The host keeps one plugin instance and re-activates it for the next session; that boundary is what must
        // clear the conversation. (The rig's own event helpers stay bound to its ids, which is why this re-activates
        // with the same ones — activation is the boundary under test, not the id arithmetic.)
        rig.activate()
        rig.tick(4_000)
        rig.wakeAndFinal("新会话的问题", turnId = "asr-2")

        val history = rig.model.requests.last().history
            .filterIsInstance<ModelHistoryItem.Text>()
            .map { it.text }
        assertTrue(
            history.none { it == "上一个会话的问题" || it == "好。" },
            "the previous session's transcript leaked into the new one: $history",
        )
    }

    @Test
    fun `the turn's memory is embedded once, not once per retriever`() {
        // The trap this pins: the plugin's own assembly retrieves memory so the §4.3 budget can count it, and the
        // gateway is perfectly willing to retrieve as well. Both at once would embed the same query twice per turn,
        // which on the K40 is ~0.6 s of a ~600 ms-per-pass model spent twice on nothing.
        val embeddings = ScriptedEmbeddings()
        val rig = activatedRig(embeddings)
        rig.wakeAndFinal("记住我喜欢那个绿色的菜", turnId = "asr-1")
        rig.tick(4_000)

        rig.wakeAndFinal("香菜", turnId = "asr-2")

        val calls = embeddings.embedded.count { it == "香菜" }
        assertEquals(1, calls, "the query was embedded $calls times")
        assertTrue(
            rig.model.requests.last().context.isNotEmpty(),
            "the memory never reached the request: ${rig.model.requests.last().context}",
        )
    }

    private companion object {
        /** The index is 256-dim in production; a narrower vector is rejected by `VectorIndex`. */
        const val DIM = 256
    }
}
