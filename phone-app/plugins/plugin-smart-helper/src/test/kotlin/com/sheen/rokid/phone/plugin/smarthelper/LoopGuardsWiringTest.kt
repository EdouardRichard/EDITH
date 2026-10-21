package com.sheen.rokid.phone.plugin.smarthelper

import com.sheen.rokid.contracts.AcousticEvent
import com.sheen.rokid.contracts.AcousticLabel
import com.sheen.rokid.contracts.TranscriptEvent
import com.sheen.rokid.plugin.InputPart
import com.sheen.rokid.plugin.ModelEnd
import com.sheen.rokid.plugin.ModelStreamEvent
import com.sheen.rokid.phone.plugin.smarthelper.context.ContextRoute
import com.sheen.rokid.phone.plugin.smarthelper.scene.ProactiveHintSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Task 12's production wiring (R58/R59): the plugin turns its scoped executor's runtime-state line into the loop's
 * non-persistent `RUNTIME_STATE` segment, allows a plan nudge only on the WAKE route, and never writes either into the
 * conversation store.
 *
 * Every assertion is on what the plugin did to the real collaborators — the model request it sent, the store it wrote,
 * the hint it submitted — never on a mock's bookkeeping.
 */
class LoopGuardsWiringTest {

    private val stateLine = "foreground=com.android.settings revision=4 generation=2"

    private fun readyRig(stores: Map<ContextRoute, com.sheen.rokid.agent.ConversationStore>? = null): SmartTestRig {
        val rig = SmartTestRig(conversationStores = stores)
        rig.activate(overrides = mapOf(com.sheen.rokid.plugin.Capability.Storage to SmartFakeStorage()))
        rig.actions.descriptors += smartAction("device")
        return rig
    }

    private fun inputText(request: com.sheen.rokid.plugin.ModelRequest): String =
        request.input.filterIsInstance<InputPart.Text>().joinToString("\n\n") { it.text }

    // ---------------------------------------------------------------- runtime state (R59)

    @Test
    fun `the wake route sends the runtime state line to the model and never stores it`() {
        val stores = smartStores()
        val rig = readyRig(stores)
        rig.actions.runtimeStateLine = stateLine
        rig.model.onRequest = { request, listener -> streamText(request, listener, "好。") }

        rig.wakeAndFinal("现在几点了")

        val sent = rig.model.requests.single()
        assertTrue(stateLine in inputText(sent), "the model must receive the state line: ${inputText(sent)}")
        // The state is request metadata: it never enters the stored conversation.
        val stored = stores.getValue(ContextRoute.WAKE).history()
            .filterIsInstance<com.sheen.rokid.plugin.ModelHistoryItem.Text>()
            .joinToString("\n") { it.text }
        assertFalse("foreground=" in stored, "runtime state must never be persisted: $stored")
        assertFalse("revision=4" in stored, "runtime state must never be persisted: $stored")
    }

    @Test
    fun `an unchanged runtime state is injected once across a retry`() {
        // The production wiring always injects the per-route stores, which is what gives every request a frame the
        // loop can fold the state into; the store-less path is the pre-Phase-5 test shape only. A retry executes no
        // tool, so every loop-owned field in the composed line is unchanged too: the second request must save it.
        val rig = readyRig(smartStores())
        rig.actions.runtimeStateLine = stateLine
        var turn = 0
        rig.model.onRequest = { request, listener ->
            when (turn++) {
                0 -> listener(ModelStreamEvent.Terminal(request.requestId, ModelEnd.COMPLETED, null, null))
                else -> streamText(request, listener, "好。")
            }
        }

        rig.wakeAndFinal("看一下屏幕")

        assertEquals(2, rig.model.requests.size, "the fixture must make a retry")
        assertEquals(1, rig.model.requests.count { stateLine in inputText(it) }, "an unchanged state is sent once")
    }

    @Test
    fun `a tool without runtime state contributes no segment`() {
        val rig = readyRig()
        rig.actions.runtimeStateLine = null
        rig.model.onRequest = { request, listener -> streamText(request, listener, "好。") }

        rig.wakeAndFinal("现在几点了")

        assertFalse("foreground=" in inputText(rig.model.requests.single()))
    }

    // ---------------------------------------------------------------- plan nudge (R58)

    @Test
    fun `the wake route nudges a plan only after the run has acted`() {
        val rig = readyRig()
        var turn = 0
        rig.model.onRequest = { request, listener ->
            when (turn++) {
                0 -> {
                    listener(smartToolCall("c1", "device", """{"action":"status"}""", requestId = request.requestId))
                    listener(ModelStreamEvent.Terminal(request.requestId, ModelEnd.COMPLETED, null, null))
                }
                1 -> streamText(request, listener, "接下来我会先观察当前屏幕，然后再点按确认。")
                else -> streamText(request, listener, "已经点好了。")
            }
        }

        rig.wakeAndFinal("点一下确认")

        assertEquals(3, rig.model.requests.size, "tool round + nudged plan + final answer")
        assertEquals("已经点好了。", rig.hintTexts.lastOrNull(), "the plan text is not the answer")
        // The nudge instruction is loop scaffolding; it must never be stored as the wearer's speech.
        assertFalse(rig.hintTexts.any { it.contains("接下来我会") })
    }

    @Test
    fun `the proactive route answers a plan-like text with silence and no nudge`() {
        val rig = SmartTestRig()
        rig.activate()
        rig.model.onRequest = { request, listener ->
            if (request.tools.any { it.name == ProactiveHintSpec.PROPOSE_HINT.name }) {
                streamText(request, listener, "接下来我会先观察屏幕，然后再提醒。")
            } else {
                streamText(request, listener, "好。")
            }
        }

        rig.plugin.handle(AcousticEvent(rig.envelope(), AcousticLabel.Speech))
        rig.plugin.handle(TranscriptEvent(rig.transcript("现在几点了")))
        awaitProactive(rig)

        val proactiveRequests = rig.model.requests.filter { request ->
            request.tools.any { it.name == ProactiveHintSpec.PROPOSE_HINT.name }
        }
        assertEquals(1, proactiveRequests.size, "PROACTIVE is one request; the hint loop may not nudge a plan")
        assertTrue(rig.output.hints.isEmpty(), "a plan without the propose_hint call is silence, not a hint")
    }

    private fun awaitProactive(rig: SmartTestRig, millis: Long = 2_000) {
        val deadline = System.nanoTime() + millis * 1_000_000
        while (rig.model.requests.none { request ->
                request.tools.any { it.name == ProactiveHintSpec.PROPOSE_HINT.name }
            } && System.nanoTime() < deadline
        ) {
            Thread.sleep(5)
        }
        // Give the asynchronous half a bounded chance to send a second request it is not supposed to send.
        Thread.sleep(100)
    }
}
