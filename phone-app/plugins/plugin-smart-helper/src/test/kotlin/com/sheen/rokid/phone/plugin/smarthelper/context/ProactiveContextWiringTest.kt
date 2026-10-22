package com.sheen.rokid.phone.plugin.smarthelper.context

import com.sheen.rokid.phone.plugin.smarthelper.SmartFakeStorage
import com.sheen.rokid.phone.plugin.smarthelper.SmartTestRig
import com.sheen.rokid.phone.plugin.smarthelper.memory.MemoryKind
import com.sheen.rokid.phone.plugin.smarthelper.memory.MemoryLog
import com.sheen.rokid.phone.plugin.smarthelper.memory.MemorySource
import com.sheen.rokid.phone.plugin.smarthelper.prompt.PromptCatalog
import com.sheen.rokid.phone.plugin.smarthelper.prompt.PromptRoute
import com.sheen.rokid.phone.plugin.smarthelper.scene.ProactiveHintSpec
import com.sheen.rokid.phone.plugin.smarthelper.smartStores
import com.sheen.rokid.phone.plugin.smarthelper.smartToolCall
import com.sheen.rokid.phone.plugin.smarthelper.streamText

import com.sheen.rokid.agent.ConversationStore
import com.sheen.rokid.contracts.AcousticEvent
import com.sheen.rokid.contracts.AcousticLabel
import com.sheen.rokid.contracts.TranscriptEvent
import com.sheen.rokid.plugin.Capability
import com.sheen.rokid.plugin.EmbedResult
import com.sheen.rokid.plugin.EmbeddingService
import com.sheen.rokid.plugin.InputPart
import com.sheen.rokid.plugin.MessageRole
import com.sheen.rokid.plugin.ModelEnd
import com.sheen.rokid.plugin.ModelHistoryItem
import com.sheen.rokid.plugin.ModelInfo
import com.sheen.rokid.plugin.ModelRequest
import com.sheen.rokid.plugin.ModelStreamEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * edith-0.2 Phase B (B1/B3/B6): the **proactive** route consumes the session's own assembly.
 *
 * After the second assembly slice the wake route ran through `ContextManager.buildPrompt`, but the proactive route
 * still assembled its own prompt (hand-written `SmartPrompts` prose plus a single hand-built history item). The
 * consequence was not cosmetic: `ContextManager` constructed a PROACTIVE [ConversationContext] that **nothing ever
 * read**, so "two independent conversations" was only half true — the second route had no memory of its own and the
 * two routes could not be proven independent.
 *
 * 0.3 Task 12 adds the second half of the production shape: the route's system text is the **session-pinned**
 * `PromptCatalog.plan(PROACTIVE, profile)`, and its retrieved memory is request-scoped tail content
 * (`MEMORY_EVIDENCE` in the request input), never folded into `history[0]`.
 *
 * These tests activate the plugin the way the composition does and read `ModelRequest.instructions` / `.history` /
 * `.input`, i.e. what the model actually received.
 */
class ProactiveContextWiringTest {

    /** One 256-dim vector per text; every query matches, so retrieval is never the subject here. */
    private class ScriptedEmbeddings : EmbeddingService {
        override fun embed(text: String): EmbedResult = EmbedResult.Success(FloatArray(DIM) { 1f })
        override val isAvailable = true
        override val modelInfo: ModelInfo? = null
        override val dimensions = DIM
    }

    /** Records which route's store compression actually ran over (the store is the route's own instance). */
    private class CountingCompressor : ContextCompressor(historyBudgetTokens = 3, summaryMaxTokens = 64) {
        val storesCompressed = mutableListOf<ConversationStore>()

        override fun compressStore(
            store: ConversationStore,
            compressionOwed: Boolean,
        ): CompressionReport {
            storesCompressed += store
            return CompressionReport(truncated = 0, summaryStarted = false, summaryReason = null)
        }
    }

    private class Fixture {
        /** The memory the proactive turn will retrieve; written before activation, so it is in the session's store. */
        val storage = SmartFakeStorage()
        val stores = smartStores()
        val compressor = CountingCompressor()
        val rig = SmartTestRig(conversationStores = stores, compressor = compressor)

        init {
            MemoryLog(storage, log = {}).record(
                MemoryKind.PREFERENCE,
                "用户常问现在几点了",
                MemorySource.EXPLICIT,
            )
            rig.activate(
                overrides = mapOf(
                    Capability.Storage to storage,
                    Capability.Embedding to ScriptedEmbeddings(),
                ),
            )
        }

        fun arm() = rig.plugin.handle(AcousticEvent(rig.envelope(), AcousticLabel.Speech))

        /** A proactive request that answers with a `propose_hint` call. */
        fun propose(text: String) {
            rig.model.onRequest = { request, listener ->
                listener(smartToolCall("c1", "propose_hint", """{"text":"$text"}""", request.requestId))
                listener(ModelStreamEvent.Terminal(request.requestId, ModelEnd.COMPLETED, null, null))
            }
        }

        fun wakeTurn(text: String, turnId: String, reply: String) {
            rig.model.onRequest = { request, listener -> streamText(request, listener, reply) }
            rig.wakeAndFinal(text, turnId = turnId)
        }

        fun requestTexts(request: ModelRequest): List<String> =
            request.history.filterIsInstance<ModelHistoryItem.Text>().map { it.text }

        fun inputText(request: ModelRequest): String =
            request.input.filterIsInstance<InputPart.Text>().joinToString(" ") { it.text }

        /** The requests the proactive route issued: `propose_hint` is the route's one advertised tool. */
        fun proactiveRequests(): List<ModelRequest> =
            rig.model.requests.filter { request ->
                request.tools.any { it.name == ProactiveHintSpec.PROPOSE_HINT.name }
            }
    }

    @Test
    fun `the proactive turn records its own transcript and compresses it`() {
        val f = Fixture()
        f.propose("主动提示B")
        f.arm()

        f.rig.plugin.handle(TranscriptEvent(f.rig.transcript("现在几点了")))

        val proactive = f.stores.getValue(ContextRoute.PROACTIVE).history()
        assertTrue(
            proactive.any { it is ModelHistoryItem.Text && it.role == MessageRole.USER },
            "the proactive turn's user side was never recorded: $proactive",
        )
        assertTrue(
            proactive.any { it is ModelHistoryItem.Text && it.role == MessageRole.ASSISTANT && it.text == "主动提示B" },
            "the hint that was shown never reached the proactive transcript: $proactive",
        )
        assertTrue(
            f.compressor.storesCompressed.any { it === f.stores.getValue(ContextRoute.PROACTIVE) },
            "the proactive route's turn was never compressed",
        )
        assertFalse(
            f.compressor.storesCompressed.any { it === f.stores.getValue(ContextRoute.WAKE) },
            "the wake route was compressed by a proactive turn",
        )
    }

    private companion object {
        /** The index is 256-dim in production; a narrower vector is rejected by `VectorIndex`. */
        const val DIM = 256
    }
}
