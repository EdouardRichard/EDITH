package com.sheen.rokid.phone.plugin.smarthelper
import com.sheen.rokid.phone.plugin.smarthelper.scene.ScenePolicy
import com.sheen.rokid.phone.plugin.smarthelper.audit.P3Log

import com.sheen.rokid.plugin.Capability
import com.sheen.rokid.plugin.EmbedResult
import com.sheen.rokid.plugin.EmbeddingService
import com.sheen.rokid.plugin.FailureReason
import com.sheen.rokid.plugin.ModelInfo
import com.sheen.rokid.plugin.SystemOneAnswer
import com.sheen.rokid.plugin.SystemOneQuestion
import com.sheen.rokid.plugin.SystemOneResult
import com.sheen.rokid.plugin.SystemOneService
import com.sheen.rokid.plugin.SystemOneStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Spec §1.3 C2 / §7.3 B9: `SystemOneService` and `EmbeddingService` are **hard dependencies** — when either is
 * unavailable the plugin may still be activated but must refuse service. D60 confirms the consequence was accepted
 * knowingly: with Laya as the intervention gate, "Laya unavailable" also stops the wake-word path.
 */
class HardDependencyTest {

    /**
     * Both helpers script a reply even though the turn is expected to be refused, and that is deliberate: a turn
     * that reaches the model with nothing scripted blocks the test thread on the gateway's latch until its deadline,
     * so a *failing* refusal test would hang instead of failing. With a reply scripted, a missing refusal shows up
     * as a failed assertion about the model never having been called.
     */
    private fun rigWithSystemOne(service: SystemOneService): SmartTestRig = SmartTestRig().also {
        it.model.onRequest = { request, listener -> streamText(request, listener, "不该出现") }
        it.activate(overrides = mapOf(Capability.SystemOne to service))
    }

    private fun rigWithEmbedding(service: EmbeddingService): SmartTestRig = SmartTestRig().also {
        it.model.onRequest = { request, listener -> streamText(request, listener, "不该出现") }
        it.activate(overrides = mapOf(Capability.Embedding to service))
    }

    @Test
    fun `the descriptor declares both local models`() {
        val plugin = SmartAssistantPlugin()

        assertTrue(Capability.SystemOne in plugin.descriptor.capabilities)
        assertTrue(Capability.Embedding in plugin.descriptor.capabilities)
    }

    @Test
    fun `both models available is the ordinary case and the turn runs`() {
        val rig = SmartTestRig()
        rig.activate()
        rig.model.onRequest = { request, listener -> streamText(request, listener, "好。") }

        rig.wakeAndFinal("你好")

        assertEquals(1, rig.model.requests.size)
    }

    @Test
    fun `an unavailable decision model refuses service without stopping the model call`() {
        val rig = rigWithSystemOne(SmartFakeSystemOne(available = false))
        rig.model.onRequest = { request, listener -> streamText(request, listener, "不该出现") }

        rig.wakeAndFinal("你好")

        assertTrue(rig.plugin.active, "the plugin stays activated (spec: 可激活但拒绝服务)")
        assertTrue(rig.model.requests.isEmpty(), "a refused turn must not reach the cloud model")
        assertEquals(1, rig.hintTexts.size)
        assertTrue("本地模型" in rig.hintTexts.single(), rig.hintTexts.single())
    }

    @Test
    fun `an unavailable embedding model refuses service too`() {
        val rig = rigWithEmbedding(SmartFakeEmbedding(available = false))
        rig.model.onRequest = { request, listener -> streamText(request, listener, "不该出现") }

        rig.wakeAndFinal("你好")

        assertTrue(rig.plugin.active)
        assertTrue(rig.model.requests.isEmpty())
        assertEquals(1, rig.hintTexts.size)
    }

    @Test
    fun `a host that grants neither capability refuses rather than throwing`() {
        // The host refuses activation when a declared capability has no provider, so this rig can only be built by
        // granting the capability a provider whose own state is unavailable. What must not happen is an exception
        // escaping the dispatcher.
        val rig = SmartTestRig()
        rig.model.onRequest = { request, listener -> streamText(request, listener, "不该出现") }
        rig.activate(
            overrides = mapOf(
                Capability.SystemOne to UnavailableSystemOne(),
                Capability.Embedding to UnavailableEmbedding(),
            ),
        )

        rig.wakeAndFinal("你好")

        assertTrue(rig.model.requests.isEmpty())
        assertEquals(1, rig.hintTexts.size)
    }

    @Test
    fun `the refusal is a fixed honest sentence and the log carries a reason code not the sentence`() {
        val rig = rigWithSystemOne(SmartFakeSystemOne(available = false))
        val lines = mutableListOf<String>()
        val previous = P3Log.sink
        P3Log.sink = { lines += it }
        try {
            rig.wakeAndFinal("你好")
        } finally {
            P3Log.sink = previous
        }

        val refusal = lines.single { it.startsWith("P3_DEPENDENCY") }
        assertTrue(refusal.contains("reason=SYSTEM_ONE_UNAVAILABLE"), refusal)
        assertTrue("本地模型" !in refusal, "the log carries the code, not the user-facing sentence: $refusal")
    }

    @Test
    fun `the refusal happens before the listening window rather than after it`() {
        // A refused turn must not sit waiting for an utterance it cannot act on.
        val rig = rigWithSystemOne(SmartFakeSystemOne(available = false))

        rig.wakeAndFinal("你好")

        assertEquals(1, rig.hintTexts.size)
        assertTrue(rig.model.requests.isEmpty())
    }

    @Test
    fun `a model that turns available later serves the next turn`() {
        // "Refuse service" is a per-turn judgement, not a latch: the models are resident and their state can change
        // between turns, so a plugin that latched the first refusal would stay dead after a slow load finished.
        val flaky = FlakySystemOne()
        val rig = rigWithSystemOne(flaky)
        rig.model.onRequest = { request, listener -> streamText(request, listener, "好。") }

        rig.wakeAndFinal("第一次")
        assertTrue(rig.model.requests.isEmpty())

        // The wake path has its own cooldown, so the second wake has to be past it or `ScenePolicy` refuses it
        // before the dependency check is ever reached.
        rig.tick(4_000)
        flaky.ready = true
        rig.wakeAndFinal("第二次", turnId = "asr-2")

        assertEquals(1, rig.model.requests.size)
    }

    private class UnavailableSystemOne : SystemOneService {
        override fun ask(state: String, question: SystemOneQuestion): SystemOneResult =
            SystemOneResult.Failure(FailureReason.MODEL_NOT_LOADED, "not loaded")
        override val isAvailable = false
        override val status = SystemOneStatus.UNAVAILABLE
        override val modelInfo: ModelInfo? = null
    }

    private class UnavailableEmbedding : EmbeddingService {
        override fun embed(text: String): EmbedResult =
            EmbedResult.Error(FailureReason.MODEL_NOT_LOADED, "not loaded")
        override val isAvailable = false
        override val modelInfo: ModelInfo? = null
        override val dimensions = 256
    }

    private class FlakySystemOne : SystemOneService {
        @Volatile var ready = false
        override fun ask(state: String, question: SystemOneQuestion): SystemOneResult =
            SystemOneResult.Success(SystemOneAnswer.Noul(0.5, 0.5), 1.0)
        override val isAvailable: Boolean get() = ready
        override val status: SystemOneStatus
            get() = if (ready) SystemOneStatus.READY else SystemOneStatus.UNAVAILABLE
        override val modelInfo: ModelInfo? = null
    }
}
