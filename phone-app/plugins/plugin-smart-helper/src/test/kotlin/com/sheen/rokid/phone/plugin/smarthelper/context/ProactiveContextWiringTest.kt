package com.sheen.rokid.phone.plugin.smarthelper.context
import com.sheen.rokid.phone.plugin.smarthelper.memory.VectorIndex
import com.sheen.rokid.phone.plugin.smarthelper.scene.SmartPrompts

import com.sheen.rokid.contracts.AcousticEvent
import com.sheen.rokid.contracts.AcousticLabel
import com.sheen.rokid.contracts.TranscriptEvent
import com.sheen.rokid.plugin.Capability
import com.sheen.rokid.plugin.EmbedResult
import com.sheen.rokid.plugin.EmbeddingService
import com.sheen.rokid.plugin.MessageRole
import com.sheen.rokid.plugin.ModelHistoryItem
import com.sheen.rokid.plugin.ModelInfo
import com.sheen.rokid.phone.plugin.smarthelper.SmartFakeStorage
import com.sheen.rokid.phone.plugin.smarthelper.SmartTestRig
import com.sheen.rokid.phone.plugin.smarthelper.smartToolCall
import com.sheen.rokid.phone.plugin.smarthelper.streamText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * edith-0.2 Phase B (B1/B3/B6): the **proactive** route consumes the session's own assembly.
 *
 * After the second assembly slice the wake route ran through `ContextManager.buildPrompt`, but the proactive route
 * still assembled its own prompt (`SmartPrompts.proactiveInstructions()` plus a single hand-built history item). The
 * consequence was not cosmetic: `ContextManager` constructed a PROACTIVE [ConversationContext] that **nothing ever
 * read**, so "two independent conversations" was only half true — the second route had no memory of its own and the
 * two routes could not be proven independent.
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

    /** Counts what the session consulted, so "both routes compress" is observable without a real budget overflow. */
    private class CountingCompressor : ContextCompressor() {
        val calls = mutableListOf<Int>()
        override fun compressIfNeeded(ctx: ConversationContext, budgetTokens: Int): CompressionReport {
            calls += ctx.route.ordinal
            return CompressionReport(truncated = 0, summaryStarted = false, summaryReason = null)
        }
    }

    private class Fixture {
        val contexts = ContextManager(
            baseInstructions = { route ->
                if (route == ContextRoute.PROACTIVE) "注入:主动" else "注入:唤醒"
            },
        )
        val compressor = CountingCompressor()
        val rig = SmartTestRig(contextForTurn = contexts, compressor = compressor)

        init {
            rig.activate(
                overrides = mapOf(
                    Capability.Storage to SmartFakeStorage(),
                    Capability.Embedding to ScriptedEmbeddings(),
                ),
            )
        }

        fun arm() = rig.plugin.handle(AcousticEvent(rig.envelope(), AcousticLabel.Speech))

        /** A proactive request that answers with a `propose_hint` call. */
        fun propose(text: String) {
            rig.model.onRequest = { request, listener ->
                listener(smartToolCall("c1", "propose_hint", """{"text":"$text"}""", requestId = request.requestId))
                listener(com.sheen.rokid.plugin.ModelStreamEvent.Terminal(request.requestId, com.sheen.rokid.plugin.ModelEnd.COMPLETED, null, null))
            }
        }

        fun wakeTurn(text: String, turnId: String, reply: String) {
            rig.model.onRequest = { request, listener -> streamText(request, listener, reply) }
            rig.wakeAndFinal(text, turnId = turnId)
        }

        fun requestTexts(request: com.sheen.rokid.plugin.ModelRequest): List<String> =
            request.history.filterIsInstance<ModelHistoryItem.Text>().map { it.text }

        fun inputText(request: com.sheen.rokid.plugin.ModelRequest): String =
            request.input.filterIsInstance<com.sheen.rokid.plugin.InputPart.Text>().joinToString(" ") { it.text }
    }

    @Test
    fun `the proactive request's pieces come from the PROACTIVE context, not from a private assembly`() {
        val f = Fixture()
        f.propose("现在正适合提醒他一句")
        f.arm()

        f.rig.plugin.handle(TranscriptEvent(f.rig.transcript("现在几点了")))

        val request = f.rig.model.requests.single()
        assertEquals(
            "注入:主动",
            request.instructions,
            "the proactive route assembled its own prompt instead of asking the ContextManager",
        )
        // The route's own contract is untouched: one tool, and only that one.
        assertEquals(listOf("propose_hint"), request.tools.map { it.name })
        val history = f.requestTexts(request)
        assertFalse(
            history.any { it.contains("现在几点了") },
            "the trigger reached the history as well as the input, so it was put to the model twice: $history",
        )
        assertTrue(
            f.inputText(request).contains("现在几点了"),
            "the trigger never reached the request: ${f.inputText(request)}",
        )
        assertFalse(
            request.instructions.contains("感知上下文"),
            "the fallback assembler ran even though the session supplied a manager",
        )
    }

    @Test
    fun `the two routes keep independent transcripts`() {
        val f = Fixture()

        // A wake turn first: its words are the wake route's, and must never appear in the proactive route's history.
        f.wakeTurn("唤醒问题", "asr-w", "唤醒回答")
        f.rig.tick(4_000)

        // Then two proactive turns, the second of which must carry the first. Both triggers are worded so P3's own
        // admission rule admits them (a bare statement is refused as `VAGUE` before the model is ever reached).
        f.propose("主动提示A")
        f.arm()
        f.rig.plugin.handle(TranscriptEvent(f.rig.transcript("帮我记一下第一件事", turnId = "asr-p1")))
        // A hint starts the default 60 s cooldown, so the second trigger has to be past that window to be admitted.
        f.rig.clock.advance(61_000_000_000L)
        f.rig.plugin.handle(TranscriptEvent(f.rig.transcript("现在几点了", turnId = "asr-p2")))

        val proactiveTurns = f.rig.model.requests.filter { it.instructions == "注入:主动" }
        assertEquals(2, proactiveTurns.size, "both proactive triggers must reach the model")

        val second = f.requestTexts(proactiveTurns.last())
        assertTrue(
            second.any { it.contains("帮我记一下第一件事") },
            "the proactive route has no transcript of its own — its first turn is missing: $second",
        )
        assertTrue(
            second.none { it.contains("唤醒问题") || it == "唤醒回答" },
            "a wake turn leaked into the proactive transcript: $second",
        )

        // And the wake route must not have picked up the proactive turns either.
        val wakeTurns = f.rig.model.requests.filter { it.instructions == "注入:唤醒" }
        val wakeHistory = f.requestTexts(wakeTurns.single())
        assertTrue(
            wakeHistory.none { it.contains("帮我记一下第一件事") || it.contains("现在几点了") },
            "a proactive turn leaked into the wake transcript: $wakeHistory",
        )
    }

    @Test
    fun `the proactive turn records its own transcript and compresses it`() {
        val f = Fixture()
        f.propose("主动提示B")
        f.arm()

        f.rig.plugin.handle(TranscriptEvent(f.rig.transcript("现在几点了")))

        val proactive = f.contexts.context(ContextRoute.PROACTIVE).history()
        assertTrue(
            proactive.any { it is ModelHistoryItem.Text && it.role == MessageRole.USER },
            "the proactive turn's user side was never recorded: $proactive",
        )
        assertTrue(
            proactive.any { it is ModelHistoryItem.Text && it.role == MessageRole.ASSISTANT && it.text == "主动提示B" },
            "the hint that was shown never reached the proactive transcript: $proactive",
        )
        assertEquals(emptyList(), f.contexts.context(ContextRoute.WAKE).history(),
            "a proactive turn must not write into the wake transcript")
        assertTrue(
            f.compressor.calls.contains(ContextRoute.PROACTIVE.ordinal),
            "the proactive route's turn was never compressed: ${f.compressor.calls}",
        )
        assertFalse(
            f.compressor.calls.contains(ContextRoute.WAKE.ordinal),
            "the wake route was compressed by a proactive turn: ${f.compressor.calls}",
        )
    }

    private companion object {
        /** The index is 256-dim in production; a narrower vector is rejected by `VectorIndex`. */
        const val DIM = 256
    }
}
