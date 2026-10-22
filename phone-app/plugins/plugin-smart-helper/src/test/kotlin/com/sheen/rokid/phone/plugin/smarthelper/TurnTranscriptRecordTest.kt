package com.sheen.rokid.phone.plugin.smarthelper

import com.sheen.rokid.agent.ConversationStore
import com.sheen.rokid.agent.SegmentOrigin
import com.sheen.rokid.contracts.EventEnvelope
import com.sheen.rokid.contracts.Generation
import com.sheen.rokid.contracts.SessionId
import com.sheen.rokid.model.ContextBudgeter
import com.sheen.rokid.phone.plugin.smarthelper.context.ContextRoute
import com.sheen.rokid.plugin.ActionOutcome
import com.sheen.rokid.plugin.ActionResult
import com.sheen.rokid.plugin.Capability
import com.sheen.rokid.plugin.ImageRef
import com.sheen.rokid.plugin.InputModality
import com.sheen.rokid.plugin.InputPart
import com.sheen.rokid.plugin.MessageRole
import com.sheen.rokid.plugin.ModelEnd
import com.sheen.rokid.plugin.ModelHistoryItem
import com.sheen.rokid.plugin.ModelProfile
import com.sheen.rokid.plugin.ModelRequest
import com.sheen.rokid.plugin.ModelStreamEvent
import com.sheen.rokid.plugin.OutputModality
import com.sheen.rokid.plugin.PerceptionElement
import com.sheen.rokid.plugin.SecretHandle
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * 0.25 Task 4: the production plugin records a whole tool round, and the frame renderer is the one channel that carries
 * a tool output's image references from the store to the model request.
 *
 * Before this task `recordTurnAndCompress` wrote only `appendUser` / `appendAssistant`, so every tool call, observation
 * and image the turn produced was lost across turns. The tests here assert the production wiring end to end: the store
 * receives the run's transcript (not a rebuilt two-text approximation), the next request carries the round, and an
 * image reference survives `ConversationStore` → `toSegment()` → `ModelClientGateway` unchanged.
 */
class TurnTranscriptRecordTest {

    private val reference = ImageRef(
        artifactId = "img-20261019-0001",
        mime = "image/jpeg",
        width = 768,
        height = 1664,
        sha256 = "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08",
    )

    private fun stores(): Map<ContextRoute, ConversationStore> =
        ContextRoute.entries.associateWith { ConversationStore() }

    private fun rig(stores: Map<ContextRoute, ConversationStore>): SmartTestRig =
        SmartTestRig(conversationStores = stores).also {
            it.activate(overrides = mapOf(Capability.Storage to SmartFakeStorage()))
        }

    /**
     * A wide-window profile for the **real** send-time budgeter: only the history's shape can reject this fixture, never
     * its size, so an over-budget profile cannot be mistaken for the defect.
     */
    private val wireProfile = ModelProfile(
        id = "wire-profile",
        revision = 1,
        name = "Wire shape profile",
        service = "fake",
        baseUrl = "https://invalid.test",
        protocolId = "DEEPSEEK_RESPONSES",
        model = "fake-model",
        secret = object : SecretHandle { override val id = "wire-secret" },
        usableWindowTokens = 100_000,
        inputBudgetTokens = 93_000,
        maxOutputTokens = 6_000,
        safetyTokens = 1_000,
        timeoutMillis = 30_000,
        inputs = setOf(InputModality.TEXT),
        outputs = setOf(OutputModality.TEXT),
        verifications = emptyList(),
    )

    /**
     * The next turn's request carrying [history], through the **real** send-time budgeter — the component whose
     * rejection poisons every later turn. A locally re-implemented shape rule could drift from the shipped one; this
     * cannot.
     */
    private fun assertSendableAsNextHistory(history: List<ModelHistoryItem>) {
        val request = ModelRequest(
            envelope = EventEnvelope(SessionId("wire-session"), Generation(1), "wire-request", 0, 0),
            requestId = "wire-next",
            turnId = "wire-next",
            profileId = wireProfile.id,
            profileRevision = wireProfile.revision,
            instructions = "instructions",
            input = listOf(InputPart.Text("下一句")),
            history = history,
            output = OutputModality.TEXT,
            deadlineNanos = 10_000,
        )

        val budgeted = ContextBudgeter().prepare(wireProfile, request)

        assertEquals(history, budgeted.request.history, "the whole stored transcript must fit this fixture")
    }

    // ------------------------------------------------------------ the renderer's wire contract

    @Test
    fun `an image bearing tool output renders its image array`() {
        val item = ModelHistoryItem.ToolOutput("call-1", """{"ok":true}""", listOf(reference))

        val segment = item.toSegment()

        assertEquals(SegmentOrigin.TOOL_ROUND, segment.origin)
        assertTrue(segment.trusted)
        val entry = Json.parseToJsonElement(segment.text).jsonArray.single().jsonObject
        assertEquals("output", entry["type"]?.jsonPrimitive?.content)
        assertEquals("call-1", entry["callId"]?.jsonPrimitive?.content)
        assertEquals("""{"ok":true}""", entry["output"]?.jsonPrimitive?.content)
        val image = entry["images"]?.jsonArray?.single()?.jsonObject
        assertEquals(reference.artifactId, image?.get("artifactId")?.jsonPrimitive?.content)
        assertEquals(reference.mime, image?.get("mime")?.jsonPrimitive?.content)
        assertEquals(reference.width, image?.get("width")?.jsonPrimitive?.int)
        assertEquals(reference.height, image?.get("height")?.jsonPrimitive?.int)
        assertEquals(reference.sha256, image?.get("sha256")?.jsonPrimitive?.content)
    }

    @Test
    fun `a tool output without images omits the images member`() {
        // The pre-0.25 shape must stay byte-for-byte sendable: an empty `images` member is not the same contract as no
        // member, and the decoder's backward-compatibility promise is about a round that never had one.
        val segment = ModelHistoryItem.ToolOutput("call-1", "done").toSegment()

        val entry = Json.parseToJsonElement(segment.text).jsonArray.single().jsonObject
        assertFalse(entry.containsKey("images"), "a plain output must not invent an images member: ${segment.text}")
        assertEquals("call-1", entry["callId"]?.jsonPrimitive?.content)
        assertEquals("done", entry["output"]?.jsonPrimitive?.content)
    }

    // ------------------------------------------------------------ store → frame → gateway

    @Test
    fun `an image reference stored with a tool round reaches the next request`() {
        val stores = stores()
        val rig = rig(stores)
        // Task 13 (R68) made the trimmer charge ImageBudget.UNITS_PER_IMAGE for an image the adapter will put on the
        // wire; the rig's default 2 000-byte window cannot hold this round plus its image. This test is about the
        // store → segment → gateway channel, not about eviction, so give it the same wide window `wireProfile` uses and
        // let the shape, never the size, be what it proves.
        rig.profile = smartProfile().copy(
            usableWindowTokens = 100_000,
            inputBudgetTokens = 93_000,
            maxOutputTokens = 6_000,
            safetyTokens = 1_000,
        )
        // A completed earlier turn with a screenshot round. `appendTranscript` is the production record path, so the
        // seed is the same shape the plugin writes after a run.
        stores.getValue(ContextRoute.WAKE).appendTranscript(
            listOf(
                ModelHistoryItem.Text(MessageRole.USER, "看一下屏幕"),
                ModelHistoryItem.Text(MessageRole.ASSISTANT, "我来看看"),
                ModelHistoryItem.ToolCall("call-1", "device", """{"action":"observe"}""", "item-1"),
                ModelHistoryItem.ToolOutput("call-1", """{"ok":true}""", listOf(reference)),
                ModelHistoryItem.Text(MessageRole.ASSISTANT, "看到了"),
            ),
        )
        rig.model.onRequest = { request, listener -> streamText(request, listener, "现在九点。") }

        rig.wakeAndFinal("现在几点了")

        val sent = rig.model.requests.single()
        assertTrue(
            sent.history.any { it is ModelHistoryItem.ToolCall && it.callId == "call-1" },
            "the stored call must reach the request as a structured item: ${sent.history}",
        )
        val output = sent.history.filterIsInstance<ModelHistoryItem.ToolOutput>().single { it.callId == "call-1" }
        assertEquals(
            listOf(reference),
            output.images,
            "store → toSegment → gateway must not drop the image reference: ${sent.history}",
        )
    }

    @Test
    fun `a tool produced image reaches the next request with no hand built tool output`() {
        // 0.25 Task 24 (C1/R133/R135): the whole production chain, not a seeded transcript. A tool's ActionResult
        // carries the ImageRef → ToolRegistry → AgentLoop's ToolOutput → this plugin's toSegment() TOOL_ROUND JSON →
        // ModelClientGateway's decoder → the transport-facing ModelRequest. Before the result carrier existed the
        // image stopped at the executor and every stage after it had an empty list to carry.
        val stores = stores()
        val rig = rig(stores)
        rig.profile = smartProfile().copy(
            usableWindowTokens = 100_000,
            inputBudgetTokens = 93_000,
            maxOutputTokens = 6_000,
            safetyTokens = 1_000,
        )
        rig.actions.descriptors += smartAction("device")
        rig.actions.outcome = { _, _ ->
            ActionResult(ActionOutcome.Done, """{"ok":true}""", images = listOf(reference))
        }
        var request = 0
        rig.model.onRequest = { modelRequest, listener ->
            request++
            if (request == 1) {
                listener(smartToolCall("c1", "device", """{"action":"screenshot"}""", requestId = modelRequest.requestId))
                listener(ModelStreamEvent.Terminal(modelRequest.requestId, ModelEnd.COMPLETED, null, null))
            } else {
                streamText(modelRequest, listener, "看到了。")
            }
        }

        rig.wakeAndFinal("看一下屏幕")

        assertEquals(2, rig.model.requests.size, "the tool round must issue a continuation request")
        val continuation = rig.model.requests[1]
        val output = continuation.history.filterIsInstance<ModelHistoryItem.ToolOutput>()
            .single { it.callId == "c1" }
        assertEquals(
            listOf(reference),
            output.images,
            "a tool's image must reach the next request through the production chain: ${continuation.history}",
        )
        val carriedImages = continuation.history.flatMap { item ->
            if (item is ModelHistoryItem.ToolOutput) item.images else emptyList()
        }
        assertEquals(
            listOf(reference),
            carriedImages,
            "the frame path and the history path are one channel: the artifact must be sent once, not twice",
        )
    }

    // ------------------------------------------------------------ the production record path

    @Test
    fun `a multi step run records its tool round and the closing reply`() {
        val stores = stores()
        val rig = rig(stores)
        var request = 0
        rig.model.onRequest = { modelRequest, listener ->
            request++
            if (request == 1) {
                listener(
                    smartToolCall(
                        "c1", "notify_user", """{"text":"稍等","channel":"screen"}""",
                        requestId = modelRequest.requestId,
                    ),
                )
                listener(ModelStreamEvent.Terminal(modelRequest.requestId, ModelEnd.COMPLETED, null, null))
            } else {
                streamText(modelRequest, listener, "已经看过了。")
            }
        }

        rig.wakeAndFinal("现在几点了")

        val history = stores.getValue(ContextRoute.WAKE).history()
        assertEquals(
            1,
            history.count { it is ModelHistoryItem.Text && it.role == MessageRole.USER && it.text == "现在几点了" },
            "the turn's utterance must be recorded once, not once per recorded half: $history",
        )
        val call = history.filterIsInstance<ModelHistoryItem.ToolCall>().single()
        assertEquals("notify_user", call.name)
        assertEquals("c1", call.callId)
        val output = history.filterIsInstance<ModelHistoryItem.ToolOutput>().single()
        assertEquals("c1", output.callId)
        assertEquals(pureToolOutput("notified:1"), output.output)
        // The closing assistant text is what the wearer actually heard/saw: the notification bodies plus the model's
        // closing text, exactly the sentence `recordTurnAndCompress` has always recorded.
        val closing = history.last()
        assertTrue(closing is ModelHistoryItem.Text, "the turn must close on assistant text: $history")
        assertEquals(MessageRole.ASSISTANT, closing.role)
        assertEquals("稍等\n已经看过了。", closing.text)
    }

    @Test
    fun `a loop enriched failure observation never reaches the conversation store`() {
        // 0.3 Phase 2.2 Task 8: `AgentLoop.observedFailure` attaches an observation to a failure that came back without
        // one. The tool-returned result was persistent (nonPersistent=false); the loop's copy carries perception content,
        // so only the same-run request may see it.
        val sentinel = "SYNTHETIC-LOOP-OBSERVATION-6623"
        val stores = stores()
        val perception = SmartFakePerception(
            initial = smartPerceptionSnapshot(
                elements = listOf(
                    PerceptionElement(ref = "#n7", role = "text", text = sentinel, centerX = 1, centerY = 2),
                ),
            ),
        )
        val rig = SmartTestRig(conversationStores = stores).also {
            it.activate(
                overrides = mapOf(
                    Capability.Storage to SmartFakeStorage(),
                    Capability.Perception to perception,
                ),
            )
        }
        rig.actions.descriptors += smartAction("open", screenObservation = true)
        rig.actions.outcome = { _, _ ->
            ActionResult(ActionOutcome.Failed, "STATE_UNCHANGED", images = listOf(reference))
        }
        // A wide window so the failure round (envelope + observation + image reference) fits the fixture and the test is
        // about the store redaction, never about eviction.
        rig.profile = smartProfile().copy(
            usableWindowTokens = 100_000,
            inputBudgetTokens = 93_000,
            maxOutputTokens = 6_000,
            safetyTokens = 1_000,
        )
        var request = 0
        rig.model.onRequest = { modelRequest, listener ->
            request++
            if (request == 1) {
                listener(smartToolCall("c1", "open", """{"target":"com.example.music"}""", requestId = modelRequest.requestId))
                listener(ModelStreamEvent.Terminal(modelRequest.requestId, ModelEnd.COMPLETED, null, null))
            } else {
                streamText(modelRequest, listener, "看到了。")
            }
        }

        rig.wakeAndFinal("打开音乐")

        assertEquals(2, rig.model.requests.size, "the failure round must issue a continuation request")
        val continuationOutputs = rig.model.requests[1].history.filterIsInstance<ModelHistoryItem.ToolOutput>()
        assertTrue(
            continuationOutputs.any { it.callId == "c1" },
            "the continuation request must carry the failure output: ${rig.model.requests.map { it.history }}",
        )
        val sent = continuationOutputs.single { it.callId == "c1" }
        assertTrue(sent.output.contains(sentinel), "the model must see the loop observation: ${sent.output}")
        assertEquals(listOf(reference), sent.images, "the in-run request keeps the failure's picture reference")
        val stored = stores.getValue(ContextRoute.WAKE).history()
        assertTrue(
            stored.none { it.containsContent(sentinel) },
            "a loop-attached observation must not be persisted into ConversationStore: $stored",
        )
        val storedOutput = stored.filterIsInstance<ModelHistoryItem.ToolOutput>().single()
        assertEquals(
            emptyList(),
            storedOutput.images,
            "a failure image must never reach the stored transcript: ${storedOutput.output}",
        )
    }

    @Test
    fun `a reused call id keeps the store prefix and records this turn's tool round`() {
        // C2 re-review: redaction keyed on the current run's call ids alone rewrote an **earlier** turn's persistent
        // tool output when this run reused its id (ids only need to be non-blank). The stored transcript then stopped
        // being a prefix of the store, so `recordStoreTurn` fell back to two text halves and dropped this run's round.
        val stores = stores()
        val rig = rig(stores)
        stores.getValue(ContextRoute.WAKE).appendTranscript(
            listOf(
                ModelHistoryItem.Text(MessageRole.USER, "看一下屏幕"),
                ModelHistoryItem.Text(MessageRole.ASSISTANT, "稍等"),
                ModelHistoryItem.ToolCall("c1", "device", """{"action":"observe"}""", "item-1"),
                ModelHistoryItem.ToolOutput("c1", "EARLIER-LEGACY-OUTPUT-5521"),
                ModelHistoryItem.Text(MessageRole.ASSISTANT, "看到了"),
            ),
        )
        val sentinel = "SYNTHETIC-SCREEN-CONTENT-8842"
        rig.actions.descriptors += smartAction("device")
        rig.actions.outcome = { _, _ ->
            ActionResult(ActionOutcome.Done, sentinel, nonPersistent = true)
        }
        var request = 0
        rig.model.onRequest = { modelRequest, listener ->
            request++
            if (request == 1) {
                listener(smartToolCall("c1", "device", """{"action":"observe"}""", requestId = modelRequest.requestId))
                listener(ModelStreamEvent.Terminal(modelRequest.requestId, ModelEnd.COMPLETED, null, null))
            } else {
                streamText(modelRequest, listener, "看完了。")
            }
        }

        rig.wakeAndFinal("屏幕上有什么")

        val history = stores.getValue(ContextRoute.WAKE).history()
        val outputs = history.filterIsInstance<ModelHistoryItem.ToolOutput>()
        assertEquals(2, outputs.size, "the earlier round and this run's round must both be stored: $history")
        assertEquals("EARLIER-LEGACY-OUTPUT-5521", outputs[0].output, "an earlier turn must stay verbatim")
        assertTrue(outputs[1].output.isNotBlank(), "this run's output keeps a safe marker")
        assertTrue(
            history.none { it.containsContent(sentinel) },
            "this run's perception content must not be persisted: $history",
        )
        assertEquals(
            2,
            history.count { it is ModelHistoryItem.ToolCall },
            "the reused call id must not drop this run's call from the store: $history",
        )
    }

    // ------------------------------------------------------------ the fallback path

    @Test
    fun `a run that hands over no transcript falls back to the two text halves`() {
        // A transport failure makes `AgentLoop.run` throw, so the plugin's catch builds an `AgentRun` with no
        // transcript. The record path must keep its pre-0.25 shape for that turn, not append nothing.
        val stores = stores()
        val rig = rig(stores)
        rig.model.onRequest = { _, _ -> throw IllegalStateException("transport down") }

        rig.wakeAndFinal("帮我看看")

        val history = stores.getValue(ContextRoute.WAKE).history()
        assertEquals(
            listOf(
                ModelHistoryItem.Text(MessageRole.USER, "帮我看看"),
                ModelHistoryItem.Text(MessageRole.ASSISTANT, "智能助手这次没能给出结果。"),
            ),
            history,
            "an empty transcript must still leave an answerable text turn",
        )
    }

    @Test
    fun `a store changed during the run falls back to the two text halves`() {
        // The other reachable fallback: the asynchronous summary (ContextCompressor launches it on the host pool) lands
        // while the turn is still in flight, so by record time the store no longer prefixes the run's transcript.
        val stores = stores()
        val rig = rig(stores)
        val store = stores.getValue(ContextRoute.WAKE)
        store.appendUser("之前的问题")
        store.appendAssistant("之前的回答")
        rig.actions.descriptors += smartAction("finish_task", scope = "terminal")
        rig.actions.outcome = { _, _ ->
            store.appendSummary("较早的会话摘要")
            ActionResult(ActionOutcome.Done, "做完了")
        }
        rig.model.onRequest = { modelRequest, listener ->
            listener(smartToolCall("c1", "finish_task", requestId = modelRequest.requestId))
            listener(ModelStreamEvent.Terminal(modelRequest.requestId, ModelEnd.COMPLETED, null, null))
        }

        rig.wakeAndFinal("帮我处理")

        val history = store.history()
        val head = assertIs<ModelHistoryItem.Text>(history.firstOrNull(), "the summary must stay at the head: $history")
        assertTrue(head.text.contains("较早的会话摘要"), "the summary must survive the fallback: $history")
        assertTrue(
            history.none { it is ModelHistoryItem.ToolCall || it is ModelHistoryItem.ToolOutput },
            "the prefix-mismatch fallback cannot attribute the tool round, so it must not store half of it: $history",
        )
        assertEquals(
            listOf("之前的问题", "之前的回答", "帮我处理", "做完了"),
            history.drop(1).filterIsInstance<ModelHistoryItem.Text>().map { it.text },
            "the fallback must append the turn's two text halves after the changed store: $history",
        )
    }

    // ------------------------------------------------------------ runs that end on a non-conversation boundary

    /**
     * The review's Critical finding: `AgentLoop` appends its assistant opener and the model's calls, then may return
     * `STEP_LIMIT` at the **next** call before it runs, leaving a dangling `ToolCall` in `run.transcript`. The plugin
     * then appended the reply after the loop's opener, and `appendTranscript` dropped the call — leaving
     * `[Text(ASSISTANT, "(tool call)"), Text(ASSISTANT, reply)]`, the shape `ContextBudgeter` refuses. Every later
     * turn of that session then failed until the app restarted.
     */
    @Test
    fun `a step limit run stores complete rounds and a reply, never a dangling call`() {
        val stores = stores()
        val rig = rig(stores)
        rig.actions.descriptors += smartAction("lookup")
        rig.actions.outcome = { _, args -> ActionResult(ActionOutcome.Done, "done:$args") }
        var requests = 0
        rig.model.onRequest = { modelRequest, listener ->
            requests += 1
            listener(smartToolCall("c$requests", "lookup", """{"n":$requests}""", requestId = modelRequest.requestId))
            listener(ModelStreamEvent.Terminal(modelRequest.requestId, ModelEnd.COMPLETED, null, null))
        }

        rig.wakeAndFinal("一直查")

        // `AgentLoop`'s default maxSteps is 24: the 24th call runs and the 25th is refused before it executes.
        assertEquals(25, requests, "the fixture must actually reach STEP_LIMIT")
        val history = stores.getValue(ContextRoute.WAKE).history()

        val calls = history.filterIsInstance<ModelHistoryItem.ToolCall>().map { it.callId }
        val outputs = history.filterIsInstance<ModelHistoryItem.ToolOutput>().map { it.callId }
        assertEquals(24, calls.size, "every executed call must be stored: $history")
        assertEquals(calls.toSet(), outputs.toSet(), "a stored call must have its output and vice versa: $history")

        history.zipWithNext().forEach { (left, right) ->
            assertFalse(
                left is ModelHistoryItem.Text && left.role == MessageRole.ASSISTANT &&
                    right is ModelHistoryItem.Text && right.role == MessageRole.ASSISTANT,
                "two adjacent assistant texts are the shape the send-time budgeter rejects: $history",
            )
        }
        val closing = assertIs<ModelHistoryItem.Text>(history.last(), "the turn must close on assistant text: $history")
        assertEquals(MessageRole.ASSISTANT, closing.role)
        assertEquals(
            "智能助手这次没能给出结果。",
            closing.text,
            "the stored closing text must be the sentence the wearer got, not the loop's opener placeholder",
        )
        assertSendableAsNextHistory(history)
    }

    /**
     * The review's Important finding (R16): a retried empty turn left `Text(ASSISTANT, "(empty turn)")` and the
     * loop-internal retry instruction in the store as if the wearer had said it. Spec §5.1's stored conversation has
     * only the user's own turns, the assistant's real replies and tool rounds.
     */
    @Test
    fun `an empty turn's retry scaffolding is not stored as conversation`() {
        val stores = stores()
        val rig = rig(stores)
        var requests = 0
        rig.model.onRequest = { modelRequest, listener ->
            requests += 1
            if (requests <= 2) {
                listener(ModelStreamEvent.Terminal(modelRequest.requestId, ModelEnd.INCOMPLETE, null, null))
            } else {
                streamText(modelRequest, listener, "好。")
            }
        }

        rig.wakeAndFinal("现在几点了")

        assertEquals(3, requests, "two empty turns must be retried before the answer")
        assertEquals(
            listOf(
                ModelHistoryItem.Text(MessageRole.USER, "现在几点了"),
                ModelHistoryItem.Text(MessageRole.ASSISTANT, "好。"),
            ),
            stores.getValue(ContextRoute.WAKE).history(),
            "the retry instruction and its placeholder are loop-internal, not wearer/assistant speech",
        )
    }

    /** Whether a stored item carries [value]; the store path must keep perception content out of all three shapes. */
    private fun ModelHistoryItem.containsContent(value: String): Boolean = when (this) {
        is ModelHistoryItem.Text -> text.contains(value)
        is ModelHistoryItem.ToolCall -> argsJson.contains(value)
        is ModelHistoryItem.ToolOutput -> output.contains(value)
    }
}
