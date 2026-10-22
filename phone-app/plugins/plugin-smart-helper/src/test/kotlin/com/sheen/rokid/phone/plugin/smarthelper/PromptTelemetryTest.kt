package com.sheen.rokid.phone.plugin.smarthelper

import com.sheen.rokid.agent.MemoryRetrieval
import com.sheen.rokid.agent.Segment
import com.sheen.rokid.agent.SegmentOrigin
import com.sheen.rokid.contracts.AcousticEvent
import com.sheen.rokid.contracts.AcousticLabel
import com.sheen.rokid.contracts.TranscriptEvent
import com.sheen.rokid.phone.plugin.smarthelper.audit.P3Log
import com.sheen.rokid.phone.plugin.smarthelper.context.ContextManager
import com.sheen.rokid.phone.plugin.smarthelper.context.ContextRoute
import com.sheen.rokid.phone.plugin.smarthelper.prompt.PromptCatalog
import com.sheen.rokid.phone.plugin.smarthelper.prompt.PromptRoute
import com.sheen.rokid.phone.plugin.smarthelper.prompt.profileFixture
import com.sheen.rokid.phone.plugin.smarthelper.skill.LoadSkillTool
import com.sheen.rokid.plugin.Capability
import com.sheen.rokid.plugin.DeviceProfile
import com.sheen.rokid.plugin.InputPart
import com.sheen.rokid.plugin.ModelEnd
import com.sheen.rokid.plugin.ModelRequest
import com.sheen.rokid.plugin.ModelStreamEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * 0.3 Phase 2 Task 11 (spec §4.1/§4.2.1/§5.3; review fix R12): `P3_PROMPT` is a **per assembled request**
 * provenance line.
 *
 * It is how a device log can prove which prompt ran without ever printing it: the catalog version, the pinned plan's
 * ordered-section hash, the deterministic device-profile hash, the pinned SKILL catalog hash, the route, and the
 * system/tail sizes of the assembled request. It is emitted for both production frame paths — the wake route and,
 * since 0.3 Phase 3 Task 5, the proactive hint route — through `AgentRequest.onRequestBuilt`, called by `AgentLoop`
 * after its `RUNTIME_STATE`/`PERCEPTION` segments are folded in and before the gateway sends (a tool round is a second
 * assembled request). It is no longer emitted once per activation. A gateway overflow re-send reuses an
 * already-assembled request and reports only `P3_CACHE`; a request the gateway refuses before sending still has its
 * `P3_PROMPT`, because the assembly really happened.
 *
 * `dynamicChars` is the assembled frame's [com.sheen.rokid.agent.RequestLayout.dynamicTail] length **excluding** the
 * loop-owned `RUNTIME_STATE` segment: memory evidence, a pulled SKILL body, a retry instruction and the `PERCEPTION`
 * block all count. The line stays counts/hashes only, with no prompt, device or user text.
 */
class PromptTelemetryTest {

    private val profile = profileFixture(model = "M2012K11AC")

    /** The one memory line every request of the [tailsRig] assembly retrieves; its length is a known tail term. */
    private val memoryText = "memory: 用户喜欢乌龙茶"

    /** The production activation path: the device provider arrives through `services.deviceState()`. */
    private fun activateWithDeviceProfile(rig: SmartTestRig = SmartTestRig()): SmartTestRig {
        rig.activate(
            overrides = mapOf(
                Capability.Storage to SmartFakeStorage(),
                Capability.DeviceState to SmartFakeDeviceState(profile),
            ),
        )
        return rig
    }

    /**
     * The production store-backed shape: production always injects one `ConversationStore` per route.
     *
     * [wideWindow] keeps the gateway's trimmer out of a test that measures the dynamic tail: since Phase 4A Task 6 the
     * 24-tool system prompt no longer fits the default small fixture together with a retry segment, and a legitimate
     * trim would make the measured `dynamicChars` depend on the trimmer instead of on the loop's segment.
     */
    private fun wakeRig(
        deviceProfile: DeviceProfile? = profile,
        wideWindow: Boolean = false,
    ): SmartTestRig {
        val rig = SmartTestRig(conversationStores = smartStores())
        if (wideWindow) {
            rig.profile = smartProfile(
                usableWindowTokens = 32_000,
                inputBudgetTokens = 16_000,
                maxOutputTokens = 2_000,
                safetyTokens = 1_000,
            )
        }
        rig.activate(
            overrides = buildMap {
                put(Capability.Storage, SmartFakeStorage())
                if (deviceProfile != null) put(Capability.DeviceState, SmartFakeDeviceState(deviceProfile))
            },
        )
        return rig
    }

    /** One request that answers normally: the single assembled request the wake path reports. */
    private fun answerOnce(rig: SmartTestRig) {
        rig.model.onRequest = { request, listener -> streamText(request, listener, "好。") }
    }

    /** A tool round: the model notifies once, then answers — two assembled requests in one turn. */
    private fun notifyThenAnswer(rig: SmartTestRig, answer: String = "好。") {
        var calls = 0
        rig.model.onRequest = { request, listener ->
            calls += 1
            if (calls == 1) {
                listener(
                    smartToolCall(
                        "c1", "notify_user", """{"text":"稍等","channel":"screen"}""",
                        requestId = request.requestId,
                    ),
                )
                listener(ModelStreamEvent.Terminal(request.requestId, ModelEnd.COMPLETED, null, null))
            } else {
                streamText(request, listener, answer)
            }
        }
    }

    /** Runs [block] with the plugin's `P3` sink diverted; the previous sink is restored even when it throws. */
    private fun withLines(block: () -> Unit): List<String> {
        val lines = mutableListOf<String>()
        val previous = P3Log.sink
        P3Log.sink = { lines += it }
        return try {
            block()
            lines.toList()
        } finally {
            P3Log.sink = previous
        }
    }

    private fun promptLines(lines: List<String>): List<String> =
        lines.filter { it.startsWith("P3_PROMPT ") }

    private fun promptLine(lines: List<String>): String = promptLines(lines).single()

    private fun field(line: String, name: String): String =
        line.substringAfter("$name=").substringBefore(' ')

    private fun wakeLines(rig: SmartTestRig, utterance: String = "现在几点了"): List<String> {
        answerOnce(rig)
        return withLines { rig.wakeAndFinal(utterance) }
    }

    /** One fixed retrieval: every wake assembly gets this evidence, so the tail's memory term is a known length. */
    private class FixedMemory(private val parts: List<Segment>) : MemoryRetrieval {
        override fun retrieve(query: String, limit: Int): List<Segment> = parts
    }

    /**
     * A store-backed wake rig with all three plugin-owned tails available: memory (through the session assembly), a
     * SKILL library whose body the model can pull, and the host perception port. The store is what gives every request
     * an assembled frame.
     */
    private fun tailsRig(skillBody: String): SmartTestRig {
        val rig = SmartTestRig(
            contextForTurn = ContextManager(
                memory = FixedMemory(
                    listOf(Segment(SegmentOrigin.MEMORY_EVIDENCE, trusted = false, text = memoryText)),
                ),
                policy = smartContextPolicy(),
                promptPlan = smartPromptPlan("WAKE SYSTEM"),
            ),
            conversationStores = smartStores(),
        )
        // A wide window so the gateway's trimmer never drops the tail these tests measure: with the default small
        // fixture the production system prompt plus all three tails would exceed the hard limit, and a legitimate cut
        // (or `restoreCurrentUtterance`) would make the sizes depend on the trimmer instead of on this fix.
        rig.profile = smartProfile(
            usableWindowTokens = 32_000,
            inputBudgetTokens = 16_000,
            maxOutputTokens = 2_000,
            safetyTokens = 1_000,
        )
        rig.activate(
            overrides = mapOf(
                Capability.Storage to SmartFakeStorage(),
                Capability.DeviceState to SmartFakeDeviceState(profile),
                Capability.Perception to SmartFakePerception(),
                Capability.StorageAccess to SmartFakeSkills(
                    mapOf("/sdcard/EDITH/skills/a.md" to smartSkillDoc("alpha", skillBody)),
                ),
            ),
        )
        return rig
    }

    /**
     * The request-scoped tail the model actually received, decoded from the wire input: the gateway renders the
     * dynamic segments ahead of the user input, joined with a blank line. The trailing user input is known to the
     * test, so the decode is exact and no production constant is duplicated.
     */
    private fun dynamicPartsOf(request: ModelRequest, userInput: String): List<String> {
        val input = request.input.filterIsInstance<InputPart.Text>().joinToString("\n\n") { it.text }
        assertTrue(input.endsWith("\n\n$userInput"), "the dynamic tail must precede the user input: $input")
        return input.removeSuffix("\n\n$userInput").split("\n\n")
    }

    /** One rendered perception block's own character count, from its count-only `P3_PERCEPTION` line. */
    private fun perceptionChars(line: String): Int = field(line, "screenChars").toInt()

    @Test
    fun `activation no longer emits a prompt line`() {
        val lines = withLines { activateWithDeviceProfile() }

        assertTrue(
            promptLines(lines).isEmpty(),
            "P3_PROMPT is per assembled request now; activation must not claim a request that never happened: $lines",
        )
    }

    @Test
    fun `a wake request carries version, hashes, route and sizes but no prompt text`() {
        val rig = wakeRig()

        val lines = wakeLines(rig)

        val line = promptLine(lines)
        val plan = PromptCatalog.plan(PromptRoute.WAKE, profile)
        assertTrue(
            Regex(
                """^P3_PROMPT version=0\.4\.1 hash=[0-9a-f]{64} deviceHash=[0-9a-f]{64} """ +
                    """catalogHash=(?:[0-9a-f]{64}|none) route=WAKE staticChars=\d+ dynamicChars=\d+$""",
            ).matches(line),
            "the line must carry exactly the pinned plan's identity, route and sizes: $line",
        )
        assertEquals(plan.hash, field(line, "hash"), "the hash must fingerprint the rendered plan: $line")
        assertEquals(
            plan.systemText.length.toString(),
            field(line, "staticChars"),
            "staticChars must be the frame's own system length: $line",
        )
        // No memory, skill body, retry or perception in this request: the assembled dynamic tail is empty (the
        // loop-owned RUNTIME_STATE is excluded by contract, and this rig produces none anyway).
        assertEquals("0", field(line, "dynamicChars"), line)
        assertEquals("none", field(line, "catalogHash"), "no library was installed: $line")
        // No prompt prose, no device values, no user/model text: the whole line is ASCII counts and hex.
        assertTrue(line.all { it.code < 128 }, "P3_PROMPT must never carry prompt or device text: $line")
        assertFalse(line.contains("M2012K11AC"), "a device value leaked into the log: $line")
        assertFalse(line.contains("FixtureCorp"), "a device value leaked into the log: $line")
        assertFalse(line.contains("你是一名"), "prompt prose leaked into the log: $line")
        assertFalse(line.contains("【设备画像】"), "prompt prose leaked into the log: $line")
        assertFalse(line.contains("现在几点了"), "user text leaked into the log: $line")
    }

    @Test
    fun `a wake tool round emits one prompt line per assembled request`() {
        val rig = wakeRig()
        notifyThenAnswer(rig)

        val lines = withLines { rig.wakeAndFinal("现在几点了") }

        assertEquals(2, rig.model.requests.size, "the notify round-trip must make a second model request")
        val prompt = promptLines(lines)
        assertEquals(2, prompt.size, "one line per assembled request, not one per turn or activation: $lines")
        prompt.forEach { line ->
            assertTrue("route=WAKE" in line, line)
            assertTrue(Regex("""staticChars=\d+ dynamicChars=\d+$""").containsMatchIn(line), line)
        }
    }

    @Test
    fun `P3_PROMPT carries the pinned skill catalog hash and the frame's static size`() {
        // The SKILL catalog is part of the cacheable system slot, so its provenance rides the same line as the plan's.
        // Only the digest and the path-free counts may appear - never a SKILL name or body.
        val rig = SmartTestRig(conversationStores = smartStores())
        rig.activate(
            overrides = mapOf(
                Capability.Storage to SmartFakeStorage(),
                Capability.DeviceState to SmartFakeDeviceState(profile),
                Capability.StorageAccess to
                    SmartFakeSkills(mapOf("/sdcard/EDITH/skills/a.md" to smartSkillDoc("alpha", "正文标记"))),
            ),
        )

        val lines = wakeLines(rig)

        val line = promptLine(lines)
        val catalogHash = field(line, "catalogHash")
        assertEquals(64, catalogHash.length, "an installed catalog must be fingerprinted: $line")
        assertNotEquals("none", catalogHash, "a catalog was installed: $line")
        val (system, _) = rig.plugin.requestSkillSegmentsForTest(ContextRoute.WAKE)
        assertEquals(
            system.joinToString("\n") { it.text }.length.toString(),
            field(line, "staticChars"),
            "staticChars must include the catalog segment in the frame's system slot: $line",
        )
        assertFalse("alpha" in line, "a SKILL name leaked into the provenance line: $line")
        assertFalse(line.contains("正文标记"), "a SKILL body leaked into the provenance line: $line")
    }

    @Test
    fun `no skill library is announced as catalogHash none instead of inventing one`() {
        val rig = wakeRig()

        val lines = wakeLines(rig)

        assertTrue("catalogHash=none" in promptLine(lines), "no grant is a stable 'none': ${promptLine(lines)}")
    }

    @Test
    fun `no device profile is announced as deviceHash none instead of inventing one`() {
        val rig = wakeRig(deviceProfile = null)

        val lines = wakeLines(rig)

        val line = promptLine(lines)
        assertTrue("deviceHash=none" in line, "a missing provider is a stable 'none', not an invented profile: $line")
        val plan = PromptCatalog.plan(PromptRoute.WAKE, null)
        assertEquals(plan.hash, field(line, "hash"), "the plan hash must be the profile-less plan: $line")
        assertEquals(plan.systemText.length.toString(), field(line, "staticChars"), line)

        // And the system prompt really omitted the device section.
        assertEquals(plan.systemText, rig.model.requests.single().instructions, "no profile means the profile-less plan")
        assertFalse("【设备画像】" in rig.model.requests.single().instructions, "the device section must be omitted")
    }

    @Test
    fun `a proactive hint request logs route=PROACTIVE_HINT with the hint plan`() {
        // Phase 3 Task 5: the hint turn is an AgentLoop, so its line comes from `AgentRequest.onRequestBuilt` like the
        // wake route's — one line for the one assembled request the terminal `propose_hint` ends. The plan is the
        // PROACTIVE_HINT one, never the wake plan.
        //
        // This rig grants no perception port, so the loop folds no PERCEPTION tail and `dynamicChars` stays 0: the
        // proactive message rides USER_INPUT (not counted), and memory/SKILL tails are absent here. With a perception
        // port granted the rendered block is counted as the tail; SmartProactiveTurnTest pins that separately.
        val rig = SmartTestRig(conversationStores = smartStores())
        rig.activate(
            overrides = mapOf(
                Capability.Storage to SmartFakeStorage(),
                Capability.DeviceState to SmartFakeDeviceState(profile),
            ),
        )
        rig.model.onRequest = { request, listener ->
            listener(
                smartToolCall(
                    "c1", "propose_hint", """{"text":"现在正适合提醒他一句"}""",
                    requestId = request.requestId,
                ),
            )
            listener(ModelStreamEvent.Terminal(request.requestId, ModelEnd.COMPLETED, null, null))
        }

        val lines = withLines {
            rig.plugin.handle(AcousticEvent(rig.envelope(), AcousticLabel.Speech))
            rig.plugin.handle(TranscriptEvent(rig.transcript("现在几点了")))
        }

        assertEquals(1, rig.model.requests.size, "the terminal proposal ends the hint run after one request")
        val line = promptLine(lines)
        val plan = PromptCatalog.plan(PromptRoute.PROACTIVE_HINT, profile)
        assertTrue(
            Regex(
                """^P3_PROMPT version=0\.4\.1 hash=[0-9a-f]{64} deviceHash=[0-9a-f]{64} """ +
                    """catalogHash=(?:[0-9a-f]{64}|none) route=PROACTIVE_HINT staticChars=\d+ dynamicChars=0$""",
            ).matches(line),
            "the proactive line must carry exactly the same pinned field list as the wake line: $line",
        )
        assertTrue("route=PROACTIVE_HINT" in line, "the proactive request must name its prompt route: $line")
        assertEquals(plan.hash, field(line, "hash"), "the proactive request must use the proactive plan: $line")
        assertEquals(64, field(line, "deviceHash").length, "a supplied profile hashes to a SHA-256 digest: $line")
        assertNotEquals("none", field(line, "deviceHash"), line)
        assertEquals(plan.systemText.length.toString(), field(line, "staticChars"), line)
        assertEquals("0", field(line, "dynamicChars"), "no memory/skill body was on this request: $line")
        assertNotEquals(
            PromptCatalog.plan(PromptRoute.WAKE, profile).hash,
            field(line, "hash"),
            "the two routes must not claim the same plan",
        )
    }

    @Test
    fun `the device hash follows the device profile and is deterministic`() {
        fun hashFor(model: String): String {
            val rig = wakeRig(profileFixture(model = model))
            return field(promptLine(wakeLines(rig)), "deviceHash")
        }

        val first = hashFor("M2012K11AC")
        val second = hashFor("M2012K11AC")
        val other = hashFor("M2012K11AG")

        assertEquals(first, second, "the same profile must fingerprint the same")
        assertNotEquals(first, other, "a different device profile must fingerprint differently")
        assertNotEquals("none", first, "a supplied profile must not read as absent")
    }

    @Test
    fun `the line counts memory, skill body and perception but never the loop-owned runtime state`() {
        // Review Important (R12): the line is emitted after AgentLoop folds RUNTIME_STATE and PERCEPTION in, so the
        // memory evidence, the pulled SKILL body and the PERCEPTION block are counted, while the loop-owned state is
        // excluded.
        val skillMarker = "skill-body-marker-9042"
        val rig = tailsRig(skillMarker)
        rig.actions.runtimeStateLine = "foreground=com.android.settings"
        var skillBodyChars = 0
        var calls = 0
        rig.model.onRequest = { request, listener ->
            calls++
            if (calls == 1) {
                listener(
                    smartToolCall(
                        "c1", LoadSkillTool.NAME, """{"name":"alpha"}""",
                        requestId = request.requestId,
                    ),
                )
                listener(ModelStreamEvent.Terminal(request.requestId, ModelEnd.COMPLETED, null, null))
            } else {
                // The pulled body is live now; it is the exact SKILL_BODY tail the second request carries.
                skillBodyChars = rig.plugin.requestSkillSegmentsForTest(ContextRoute.WAKE).second
                    .sumOf { it.text.length }
                streamText(request, listener, "好。")
            }
        }

        val lines = withLines { rig.wakeAndFinal("现在几点了") }

        assertEquals(2, rig.model.requests.size, "the load_skill round-trip makes a second assembled request")
        val prompts = promptLines(lines)
        assertEquals(2, prompts.size, "exactly one P3_PROMPT per assembled request: $lines")
        val perceptions = lines.filter { it.startsWith("P3_PERCEPTION ") }
        assertEquals(2, perceptions.size, "each request rendered its own block: $lines")

        val first = dynamicPartsOf(rig.model.requests[0], "现在几点了")
        val second = dynamicPartsOf(rig.model.requests[1], CONTINUE_AFTER_TOOL)

        // Request 1: memory + the loop's runtime state + perception. The line must count memory and perception and
        // skip RUNTIME_STATE, even though the runtime state really is on the wire.
        assertTrue(first.any { it.startsWith("steps=") }, "the loop folded its state in: $first")
        assertTrue(memoryText in first, "the memory evidence rides the tail: $first")
        assertTrue(first.any { it.length == perceptionChars(perceptions[0]) }, "the perception block rides: $first")
        assertFalse(first.any { skillMarker in it }, "the body was never pulled before request 1: $first")
        assertEquals(
            first.filterNot { it.startsWith("steps=") }.sumOf { it.length },
            field(prompts[0], "dynamicChars").toInt(),
            "the line must sum memory + perception and exclude RUNTIME_STATE: $first",
        )

        // Request 2: the pulled SKILL body joins. No retry in this flow; an unchanged runtime state is deduplicated.
        assertTrue(memoryText in second, "memory is re-added to every request of the turn: $second")
        assertTrue(second.any { skillMarker in it }, "the pulled body rides the tail: $second")
        assertTrue(second.any { it.length == perceptionChars(perceptions[1]) }, "perception rides again: $second")
        assertTrue(second.none { it.startsWith("steps=") }, "an unchanged state is deduplicated: $second")
        assertEquals(
            second.sumOf { it.length },
            field(prompts[1], "dynamicChars").toInt(),
            "the line must sum memory + SKILL body + perception: $second",
        )
        assertEquals(
            memoryText.length + skillBodyChars + perceptionChars(perceptions[1]),
            field(prompts[1], "dynamicChars").toInt(),
            "the named tails add up to the reported count exactly",
        )
    }

    @Test
    fun `a retry instruction is counted and a request without perception still logs`() {
        // Review Important (R12): a retry re-assembles the frame with the loop's RETRY segment, so the line is
        // emitted again and counts it. This rig has no perception provider at all, and the request still logs.
        // The wide window is the same seam `tailsRig` uses: the 24-tool system prompt must not make the trimmer decide
        // what this count measures.
        val rig = wakeRig(wideWindow = true)
        rig.actions.runtimeStateLine = "foreground=com.android.settings"
        var calls = 0
        rig.model.onRequest = { request, listener ->
            calls++
            if (calls == 1) {
                listener(ModelStreamEvent.Terminal(request.requestId, ModelEnd.COMPLETED, null, null))
            } else {
                streamText(request, listener, "好。")
            }
        }

        val lines = withLines { rig.wakeAndFinal("现在几点了") }

        assertEquals(2, rig.model.requests.size, "the empty first reply must be retried")
        val prompts = promptLines(lines)
        assertEquals(2, prompts.size, "one line per assembled request: $lines")

        val first = dynamicPartsOf(rig.model.requests[0], "现在几点了")
        val second = dynamicPartsOf(rig.model.requests[1], CONTINUE_AFTER_TOOL)
        assertTrue(first.any { it.startsWith("steps=") }, "request 1 carried the loop state: $first")
        assertEquals(
            0,
            field(prompts[0], "dynamicChars").toInt(),
            "a request without perception still logs, and the loop-owned state is excluded: $first",
        )
        val retry = second.singleOrNull()
            ?: error("the retry instruction must be the second request's only dynamic tail: $second")
        assertTrue(retry.isNotBlank(), "the loop appended a retry instruction")
        assertEquals(
            PromptCatalog.loop("retry"),
            retry,
            "the production loop must send the catalog's authored retry instruction",
        )
        assertEquals(
            retry.length,
            field(prompts[1], "dynamicChars").toInt(),
            "the retry instruction is part of the assembled tail: $retry",
        )
    }
}
