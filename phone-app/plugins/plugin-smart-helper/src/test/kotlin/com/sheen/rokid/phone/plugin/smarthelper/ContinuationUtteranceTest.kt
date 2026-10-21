package com.sheen.rokid.phone.plugin.smarthelper

import com.sheen.rokid.agent.TokenMeter
import com.sheen.rokid.model.ContextBudgeter
import com.sheen.rokid.model.DeepSeekResponsesAdapter
import com.sheen.rokid.plugin.Capability
import com.sheen.rokid.plugin.InputPart
import com.sheen.rokid.plugin.MessageRole
import com.sheen.rokid.plugin.ModelEnd
import com.sheen.rokid.plugin.ModelHistoryItem
import com.sheen.rokid.plugin.ModelProfile
import com.sheen.rokid.plugin.ModelRequest
import com.sheen.rokid.plugin.ModelStreamEvent
import com.sheen.rokid.phone.plugin.smarthelper.audit.P3Log
import com.sheen.rokid.phone.plugin.smarthelper.prompt.PromptCatalog
import com.sheen.rokid.phone.plugin.smarthelper.prompt.PromptRoute
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 0.25 Task 26 (R140/R141): a continuation request asks this turn's question **once**.
 *
 * `AgentLoop` keeps the current utterance in the transcript once a tool round or a retry has appended after it, and
 * `ModelClientGateway` maps every segment before the frame's one `USER_INPUT` into the request history. Before Task 26
 * the frame builder appended the utterance as that `USER_INPUT` unconditionally, so the gateway put the same question
 * in the history **and** in the input: the model was asked twice on every continuation. R140 fixes this at the one
 * renderer: when the transcript already carries the utterance, the frame's `USER_INPUT` is a fixed loop-internal cue;
 * on the opening turn (utterance still pending, hence absent from the transcript) it stays the wearer's own text.
 *
 * These are production-shaped end-to-end tests: the real plugin, the real `AgentLoop`, the real `ModelClientGateway`,
 * the real send-time `ContextBudgeter` and the production Responses encoder. Only the transport is a recording double,
 * and the wire body is the encoder's own JSON — so "the question appears once" is asserted on what a provider would
 * receive, not on an internal call count.
 *
 * Nothing here asserts that the user's text is absent from the conversation itself (the history legitimately carries
 * it): the assertions are about it being **repeated** in one request and about never reaching the diagnostic log.
 */
class ContinuationUtteranceTest {

    /** Distinct from every fixed prompt body, so a wire count is about this turn's question alone. */
    private val utterance = "把客厅的灯调暗一点"

    /**
     * The profile the production Responses encoder accepts, wide enough that eviction cannot be mistaken for the
     * defect (only the history's shape, never its size, is under test). Id/revision match the rig's configuration so
     * the gateway hands the encoder the exact profile it validates against.
     */
    private val wireProfile: ModelProfile = smartProfile().copy(
        id = "smart-profile",
        revision = 3,
        protocolId = "DEEPSEEK_RESPONSES",
        usableWindowTokens = 100_000,
        inputBudgetTokens = 93_000,
        maxOutputTokens = 6_000,
        safetyTokens = 1_000,
    )

    /** An activated production-shaped rig whose two route stores are the ones `SmartWiring` builds. */
    private fun rig(): SmartTestRig {
        val rig = SmartTestRig(conversationStores = smartStores())
        rig.activate(overrides = mapOf(Capability.Storage to SmartFakeStorage()))
        rig.profile = wireProfile
        return rig
    }

    /**
     * Runs [block] with the plugin's own `P3` sink diverted, so the run's complete diagnostic output can be inspected
     * for user content. The sink is restored even when the block throws.
     */
    private fun <T> withLines(block: () -> T): Pair<T, List<String>> {
        val lines = mutableListOf<String>()
        val previous = P3Log.sink
        P3Log.sink = { lines += it }
        return try {
            block() to lines.toList()
        } finally {
            P3Log.sink = previous
        }
    }

    private fun inputTexts(request: ModelRequest): List<String> =
        request.input.filterIsInstance<InputPart.Text>().map { it.text }

    /** How many user text items equal this turn's utterance: the transcript's own (legitimate) copy. */
    private fun utteranceHistoryCount(request: ModelRequest): Int =
        request.history.count {
            it is ModelHistoryItem.Text && it.role == MessageRole.USER && it.text == utterance
        }

    /**
     * The body the production Responses encoder puts on the wire for [request]: the real send-time budgeter plus the
     * real adapter. A request the budgeter refuses throws here rather than pretending to be sendable, so a shape
     * regression is a failure too. [profile] defaults to the wide [wireProfile]; the tight-window test passes the
     * profile it actually ran, so the restored shape is proven sendable at that window and not merely encodable.
     */
    private fun wireJson(request: ModelRequest, profile: ModelProfile = wireProfile): String {
        val budgeted = ContextBudgeter().prepare(profile, request)
        return DeepSeekResponsesAdapter().encode(profile, budgeted).toString(Charsets.UTF_8)
    }

    private fun occurrences(haystack: String, needle: String): Int = haystack.split(needle).size - 1

    @Test
    fun `a tool round continuation asks the current utterance once`() {
        val rig = rig()
        var requests = 0
        rig.model.onRequest = { request, listener ->
            requests++
            if (requests == 1) {
                // L0 and non-terminal: the loop must ask the model a second time in the same turn.
                listener(
                    smartToolCall(
                        "c1", "notify_user", """{"text":"稍等","channel":"screen"}""",
                        requestId = request.requestId,
                    ),
                )
                listener(ModelStreamEvent.Terminal(request.requestId, ModelEnd.COMPLETED, null, null))
            } else {
                streamText(request, listener, "已经看过了。")
            }
        }

        val (_, lines) = withLines { rig.wakeAndFinal(utterance) }

        assertEquals(2, rig.model.requests.size, "the tool round must issue a continuation request: $lines")
        val (opening, continuation) = rig.model.requests

        // The opening request: the utterance is the pending turn, so it is the input and not a history item.
        assertEquals(
            listOf(utterance),
            inputTexts(opening),
            "the opening request's input is the wearer's question exactly once",
        )
        assertEquals(0, utteranceHistoryCount(opening), "the pending question is the input, not a history item")
        assertEquals(1, occurrences(wireJson(opening), utterance), "the opening wire body carries the question once")

        // R140: the continuation carries the question in the history once, and the input is the loop-internal cue.
        assertEquals(
            listOf(CONTINUE_AFTER_TOOL),
            inputTexts(continuation),
            "the continuation input must be the loop-internal cue, not a second copy of the question",
        )
        assertEquals(
            1,
            utteranceHistoryCount(continuation),
            "the transcript must carry this turn's question exactly once: ${continuation.history}",
        )
        val continuationWire = wireJson(continuation)
        assertEquals(
            1,
            occurrences(continuationWire, utterance),
            "the continuation wire body must ask the question once, was ${occurrences(continuationWire, utterance)} " +
                "times: $continuationWire",
        )
        assertTrue(
            lines.none { it.contains(utterance) },
            "the user's text must never reach the diagnostic log: $lines",
        )
    }

    /**
     * A profile whose frame window fits exactly the system slot plus the widest of [inputs] and **no transcript
     * turn**. The plugin's frame builder still renders the fixed cue (the transcript carries the utterance), so cut ①
     * has to evict the whole current turn and leave the `USER_INPUT` alone — the window this test exists to create.
     */
    private fun windowSizedForInput(vararg inputs: String): ModelProfile {
        // The trimmer prices text in TokenMeter token units (`estimate + 32`), so the window is sized in those units:
        // a byte-sized window would leave a token-priced transcript several times more room than intended.
        fun measure(text: String) = TokenMeter.estimate(text) + 32
        // `buildFrame`'s system slot when the SKILL catalog is empty: 0.3 Task 12's session-pinned catalog plan.
        // This rig supplies no device provider, so the plan is the profile-less WAKE plan.
        val system = PromptCatalog.plan(PromptRoute.WAKE, null).systemText
        val window = 32 + measure(system) + inputs.maxOf(::measure) + 8
        val inputBudget = window + 32
        return wireProfile.copy(
            usableWindowTokens = inputBudget + 1_000 + 100,
            inputBudgetTokens = inputBudget,
            maxOutputTokens = 1_000,
            safetyTokens = 100,
        )
    }

    @Test
    fun `a continuation whose transcript the trim drops restores the utterance`() {
        // 0.25 Task 27 (R143): the cue is right while the transcript carries the question — but TurnTrimmer cut ① may
        // evict that whole turn under a tight window, leaving the cue as the only input. The gateway must restore the
        // wearer's utterance, and it must do so once (nothing is duplicated: the history is empty).
        val rig = rig()
        val tight = windowSizedForInput(utterance, CONTINUE_AFTER_TOOL)
        rig.profile = tight
        var requests = 0
        rig.model.onRequest = { request, listener ->
            requests++
            if (requests == 1) {
                listener(
                    smartToolCall(
                        "c1", "notify_user", """{"text":"稍等","channel":"screen"}""",
                        requestId = request.requestId,
                    ),
                )
                listener(ModelStreamEvent.Terminal(request.requestId, ModelEnd.COMPLETED, null, null))
            } else {
                streamText(request, listener, "已经看过了。")
            }
        }

        val (_, lines) = withLines { rig.wakeAndFinal(utterance) }

        assertEquals(2, rig.model.requests.size, "the tool round must issue a continuation request: $lines")
        val (opening, continuation) = rig.model.requests

        // The opening request is unchanged by the safety net: the utterance is its input, exactly once on the wire.
        assertEquals(listOf(utterance), inputTexts(opening), "the opening input is still the wearer's question")
        assertEquals(
            1,
            occurrences(wireJson(opening, tight), utterance),
            "the opening wire body carries the question once",
        )

        // The window really did evict the current turn's transcript: no USER item survives to carry the question.
        assertEquals(
            emptyList(),
            continuation.history,
            "cut ① must have evicted the whole current turn under this window: ${continuation.history}",
        )
        assertEquals(
            listOf(utterance),
            inputTexts(continuation),
            "the gateway must restore the question when trim drops it, not send the cue alone",
        )
        val continuationWire = wireJson(continuation, tight)
        assertEquals(
            1,
            occurrences(continuationWire, utterance),
            "the continuation wire body must carry the question once, was " +
                "${occurrences(continuationWire, utterance)} times: $continuationWire",
        )
        assertFalse(
            continuationWire.contains(CONTINUE_AFTER_TOOL),
            "the fixed cue must not be the only thing the model reads: $continuationWire",
        )
        assertTrue(
            lines.none { it.contains(utterance) },
            "the user's text must never reach the diagnostic log: $lines",
        )
    }

    @Test
    fun `a retry continuation asks the current utterance once`() {
        val rig = rig()
        var requests = 0
        rig.model.onRequest = { request, listener ->
            requests++
            if (requests <= 2) {
                // An INCOMPLETE turn with no call is the unproductive turn the loop retries (maxEmptyTurns is 3).
                listener(ModelStreamEvent.Terminal(request.requestId, ModelEnd.INCOMPLETE, null, null))
            } else {
                streamText(request, listener, "好。")
            }
        }

        val (_, lines) = withLines { rig.wakeAndFinal(utterance) }

        assertEquals(3, rig.model.requests.size, "two empty turns must be retried before the answer: $lines")
        val opening = rig.model.requests.first()
        assertEquals(listOf(utterance), inputTexts(opening), "the opening request's input is the wearer's question")
        assertEquals(1, occurrences(wireJson(opening), utterance), "the opening wire body carries the question once")

        for (retry in rig.model.requests.drop(1)) {
            val input = inputTexts(retry).joinToString("\n\n")
            assertTrue(
                input.contains("no tool call and no text"),
                "the loop's retry instruction must still be sent: $input",
            )
            assertTrue(
                input.endsWith(CONTINUE_AFTER_TOOL),
                "the cue must be the input's last line, so retry semantics are unchanged: $input",
            )
            assertFalse(input.contains(utterance), "the retry input must not repeat the question: $input")
            assertEquals(
                1,
                utteranceHistoryCount(retry),
                "the transcript must carry this turn's question exactly once: ${retry.history}",
            )
            val retryWire = wireJson(retry)
            assertEquals(
                1,
                occurrences(retryWire, utterance),
                "the retry wire body must ask the question once, was ${occurrences(retryWire, utterance)} " +
                    "times: $retryWire",
            )
        }
        assertTrue(
            lines.none { it.contains(utterance) },
            "the user's text must never reach the diagnostic log: $lines",
        )
    }
}
