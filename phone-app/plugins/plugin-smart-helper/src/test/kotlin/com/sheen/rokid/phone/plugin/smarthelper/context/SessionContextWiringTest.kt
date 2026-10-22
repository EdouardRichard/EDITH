package com.sheen.rokid.phone.plugin.smarthelper.context

import com.sheen.rokid.phone.plugin.smarthelper.CONTINUE_AFTER_TOOL
import com.sheen.rokid.phone.plugin.smarthelper.SmartFakeDeviceState
import com.sheen.rokid.phone.plugin.smarthelper.SmartFakeSkills
import com.sheen.rokid.phone.plugin.smarthelper.SmartFakeStorage
import com.sheen.rokid.phone.plugin.smarthelper.SmartTestRig
import com.sheen.rokid.phone.plugin.smarthelper.prompt.PromptCatalog
import com.sheen.rokid.phone.plugin.smarthelper.prompt.PromptKind
import com.sheen.rokid.phone.plugin.smarthelper.prompt.PromptPlan
import com.sheen.rokid.phone.plugin.smarthelper.prompt.PromptRoute
import com.sheen.rokid.phone.plugin.smarthelper.prompt.PromptSection
import com.sheen.rokid.phone.plugin.smarthelper.prompt.profileFixture
import com.sheen.rokid.phone.plugin.smarthelper.scene.ProactiveHintSpec
import com.sheen.rokid.phone.plugin.smarthelper.skill.LoadSkillTool
import com.sheen.rokid.phone.plugin.smarthelper.smartSkillDoc
import com.sheen.rokid.phone.plugin.smarthelper.smartStores
import com.sheen.rokid.phone.plugin.smarthelper.streamText

import com.sheen.rokid.contracts.AcousticEvent
import com.sheen.rokid.contracts.AcousticLabel
import com.sheen.rokid.contracts.TranscriptEvent
import com.sheen.rokid.plugin.Capability
import com.sheen.rokid.plugin.DeviceProfile
import com.sheen.rokid.plugin.EmbedResult
import com.sheen.rokid.plugin.EmbeddingService
import com.sheen.rokid.plugin.InputPart
import com.sheen.rokid.plugin.ModelEnd
import com.sheen.rokid.plugin.ModelHistoryItem
import com.sheen.rokid.plugin.ModelInfo
import com.sheen.rokid.plugin.ModelStreamEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * edith-0.2 Phase B (B1/B6/B7): the session's own prompt assembly, read out of the request the model received.
 *
 * `ContextManager` was built, tested and **never called**: `runTurn` sent the hand-written `SmartPrompts` prose,
 * passed no history at all (review finding I1), and let the gateway retrieve memory on its own. So "two independent
 * conversations" and "the assistant remembers this session" were properties of a class nothing ran. These tests
 * activate the plugin the way the composition does and read `ModelRequest.history` / `.context` / `.instructions`.
 *
 * 0.3 Task 12 moved the system text from that hand-written prose to `PromptCatalog.plan(route, deviceProfile)`. The
 * system prompt is therefore a **session-pinned** value that contains the live device profile, and a `load_skill` pull
 * is request-scoped tail content ([SegmentOrigin.SKILL_BODY]) instead of system text.
 *
 * Two request shapes live here. The transcript/memory tests use the legacy no-store shape (`SmartTestRig()`), where the
 * model reads `AgentRequest.instructions`/`history`; they are about the session transcript, not the system slot. The
 * plan-once/device-once/system-slot tests use the **store-backed production shape** (`conversationStores`, exactly as
 * `SmartWiring` builds them), where every request is a `RequestFrame` and `ModelRequest.instructions` is the plugin's
 * own `systemSlot` — the path production actually runs. 0.3 review fix (Important 1): the original version of those
 * tests ran on the legacy path, so they proved nothing about the slot production builds.
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

    /** The session's synthetic device profile; the model is the value the system prompt must carry. */
    private val deviceProfile = profileFixture(model = "M2012K11AC")

    private fun activatedRig(embeddings: EmbeddingService): SmartTestRig {
        val rig = SmartTestRig()
        rig.activate(
            overrides = mapOf(
                Capability.Storage to SmartFakeStorage(),
                Capability.Embedding to embeddings,
                Capability.DeviceState to SmartFakeDeviceState(deviceProfile),
            ),
        )
        rig.model.onRequest = { request, listener -> streamText(request, listener, "好。") }
        return rig
    }

    /**
     * The **production request shape** (0.3 review fix, Important 1): one `ConversationStore` per route exactly as
     * `SmartWiring` builds them, and the device profile resolved through `services.deviceState()` inside `activate`.
     *
     * On this path every request is a `RequestFrame`, so `ModelRequest.instructions` is the plugin's own
     * [com.sheen.rokid.phone.plugin.smarthelper.SmartAssistantPlugin] `systemSlot` — the slot production actually
     * sends — rather than the legacy no-store `AgentRequest.instructions` seam.
     */
    private fun storeBackedRig(
        device: SmartFakeDeviceState = SmartFakeDeviceState(deviceProfile),
        promptCatalog: ((PromptRoute, DeviceProfile?) -> PromptPlan)? = null,
    ): SmartTestRig {
        val rig = SmartTestRig(
            conversationStores = smartStores(),
            promptCatalog = promptCatalog,
        )
        rig.activate(
            overrides = mapOf(
                Capability.Storage to SmartFakeStorage(),
                Capability.Embedding to ScriptedEmbeddings(),
                Capability.DeviceState to device,
            ),
        )
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
        // Phase 3: context field removed from ModelRequest; memory assembly moved to TurnRunner.
        // Memory retrieval is verified via the embedding call count above.
    }

    @Test
    fun `a store-backed session reads all three plans and the device once and keeps the profile in its system slot`() {
        // 0.3 review fix (Important 1): this used `SmartTestRig()` without stores, so it asserted the legacy
        // `AgentRequest.instructions` path rather than `systemSlot`/`buildFrame`. Production `SmartWiring` always injects
        // the per-route stores, so the production shape is the one this test must run on.
        // 0.3 Phase 3: activation pins one plan per `PromptRoute`, including the not-yet-driven follow-up.
        val device = SmartFakeDeviceState(deviceProfile)
        val planned = mutableListOf<PromptRoute>()
        val rig = storeBackedRig(
            device = device,
            promptCatalog = { route, profile ->
                planned += route
                PromptCatalog.plan(route, profile)
            },
        )
        rig.model.onRequest = { request, listener ->
            if (request.tools.any { it.name == ProactiveHintSpec.PROPOSE_HINT.name }) {
                listener(
                    smartToolCall(
                        ProactiveHintSpec.PROPOSE_HINT.name,
                        """{"text":"主动提示"}""",
                        request.requestId,
                    ),
                )
                listener(ModelStreamEvent.Terminal(request.requestId, ModelEnd.COMPLETED, null, null))
            } else {
                streamText(request, listener, "好。")
            }
        }

        assertEquals(
            listOf(PromptRoute.WAKE, PromptRoute.PROACTIVE_HINT, PromptRoute.PROACTIVE_FOLLOWUP),
            planned,
            "activation must load exactly one plan per prompt route, all three at once",
        )
        assertEquals(1, device.profileReads, "the session must read the device profile once at activation")

        rig.wakeAndFinal("第一问", turnId = "asr-1")
        rig.tick(4_000)
        rig.wakeAndFinal("第二问", turnId = "asr-2")

        assertEquals(3, planned.size, "a turn must never re-load a prompt plan")
        assertEquals(1, device.profileReads, "a turn must never re-read the device profile")

        val systemSlots = rig.model.requests.map { it.instructions }
        assertEquals(2, systemSlots.size, "each wake turn must reach the model once")
        systemSlots.forEachIndexed { index, slot ->
            assertTrue(
                "M2012K11AC" in slot,
                "request $index's system slot must carry the session's device profile: $slot",
            )
        }
        assertEquals(systemSlots[0], systemSlots[1], "the system slot must be pinned for the whole session")
        assertEquals(
            PromptCatalog.plan(PromptRoute.WAKE, deviceProfile).systemText,
            systemSlots[0],
            "the store-backed system slot is the pinned catalog plan, not the legacy instructions string",
        )
    }

    @Test
    fun `a catalog change after activation does not rewrite a live session`() {
        // 0.3 review fix (Important 1): store-backed production shape again. Both routes are exercised so a per-turn
        // re-plan cannot hide behind the route that did not run.
        var revision = "first"
        val plans = mutableListOf<PromptPlan>()
        val device = SmartFakeDeviceState(deviceProfile)
        val rig = storeBackedRig(
            device = device,
            promptCatalog = { route, _ ->
                val text = "$revision:${route.name}"
                val sections = listOf(PromptSection("test-system", PromptKind.STATIC, text))
                PromptPlan(PromptCatalog.VERSION, sections, text, PromptPlan.hashOf(sections)).also { plans += it }
            },
        )
        rig.model.onRequest = { request, listener ->
            if (request.tools.any { it.name == ProactiveHintSpec.PROPOSE_HINT.name }) {
                listener(
                    smartToolCall(
                        ProactiveHintSpec.PROPOSE_HINT.name,
                        """{"text":"主动提示"}""",
                        request.requestId,
                    ),
                )
                listener(ModelStreamEvent.Terminal(request.requestId, ModelEnd.COMPLETED, null, null))
            } else {
                streamText(request, listener, "好。")
            }
        }

        assertEquals(
            listOf("first:WAKE", "first:PROACTIVE_HINT", "first:PROACTIVE_FOLLOWUP"),
            plans.map { it.systemText },
            "activation loads exactly one plan per prompt route, in route order",
        )

        rig.wakeAndFinal("第一问", turnId = "asr-1")
        assertEquals("first:WAKE", rig.model.requests.single().instructions)

        // The catalog is replaced after activation. A live session must keep the plan it pinned: re-planning now would
        // rewrite the system prompt (and invalidate the prefix cache) mid-session.
        revision = "second"
        rig.tick(4_000)
        rig.wakeAndFinal("第二问", turnId = "asr-2")
        assertEquals(2, rig.model.requests.size)
        assertEquals("first:WAKE", rig.model.requests.last().instructions)

        // The proactive route must keep its pinned plan too, not just the wake route.
        rig.plugin.handle(AcousticEvent(rig.envelope(), AcousticLabel.Speech))
        rig.plugin.handle(TranscriptEvent(rig.transcript("现在几点了", turnId = "asr-p1")))
        val proactive = rig.model.requests.single { request ->
            request.tools.any { it.name == ProactiveHintSpec.PROPOSE_HINT.name }
        }
        assertEquals("first:PROACTIVE_HINT", proactive.instructions)

        assertEquals(3, plans.size, "a live session must never re-load a prompt plan")
        assertEquals(1, device.profileReads, "a live session must never re-read the device profile")
    }

    @Test
    fun `pulled skill bodies are not in the system slot`() {
        val marker = "正文标记-1201"
        val rig = SmartTestRig(conversationStores = smartStores())
        // 0.3 Phase 4A Task 6: the 24-tool system prompt is larger than the six-tool one, so this content assertion
        // needs a window that fits both the prompt and the pulled body; trimming is not what this test measures.
        rig.profile = com.sheen.rokid.phone.plugin.smarthelper.smartProfile(
            usableWindowTokens = 32_000,
            inputBudgetTokens = 16_000,
            maxOutputTokens = 2_000,
            safetyTokens = 1_000,
        )
        rig.activate(
            overrides = mapOf(
                Capability.Storage to SmartFakeStorage(),
                Capability.Embedding to ScriptedEmbeddings(),
                Capability.DeviceState to SmartFakeDeviceState(deviceProfile),
                Capability.StorageAccess to SmartFakeSkills(
                    mapOf("/sdcard/EDITH/skills/a.md" to smartSkillDoc("alpha", marker)),
                ),
            ),
        )
        var calls = 0
        rig.model.onRequest = { request, listener ->
            calls += 1
            if (calls == 1) {
                listener(smartToolCall(LoadSkillTool.NAME, """{"name":"alpha"}""", request.requestId))
                listener(ModelStreamEvent.Terminal(request.requestId, ModelEnd.COMPLETED, null, null))
            } else {
                streamText(request, listener, "好。")
            }
        }

        rig.wakeAndFinal("帮我看看")

        assertEquals(2, rig.model.requests.size, "the load_skill round-trip must make a second request")
        val (first, second) = rig.model.requests
        val sessionPlan = PromptCatalog.plan(PromptRoute.WAKE, deviceProfile)
        assertTrue(first.instructions.startsWith(sessionPlan.systemText), "the catalog plan is the system text")
        assertTrue(first.instructions.contains("- alpha: d-alpha"), "the catalog must advertise the entry")
        assertFalse(marker in first.instructions, "the body must not be in the first request's system slot")
        assertFalse(marker in second.instructions, "a pulled body never becomes system text")
        assertFalse(
            "本轮技能正文" in second.instructions,
            "the SKILL body placeholder belongs to the dynamic tail, not the system slot",
        )

        val secondInput = second.input.filterIsInstance<InputPart.Text>().joinToString("\n\n") { it.text }
        assertTrue(marker in secondInput, "the pulled body must ride the request input: $secondInput")
        assertTrue("## SKILL 正文: alpha" in secondInput, "the body travels under its own heading: $secondInput")
        assertTrue(secondInput.endsWith(CONTINUE_AFTER_TOOL), "USER_INPUT stays last: $secondInput")
    }

    private fun smartToolCall(name: String, argsJson: String, requestId: String): ModelStreamEvent =
        ModelStreamEvent.ToolCallTerminal(
            requestId = requestId,
            itemId = "c1",
            callId = "c1",
            name = name,
            argsJson = argsJson,
        )

    private companion object {
        /** The index is 256-dim in production; a narrower vector is rejected by `VectorIndex`. */
        const val DIM = 256
    }
}
