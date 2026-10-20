package com.sheen.rokid.phone.plugin.smarthelper

import com.sheen.rokid.contracts.AcousticEvent
import com.sheen.rokid.contracts.AcousticLabel
import com.sheen.rokid.contracts.TranscriptEvent
import com.sheen.rokid.phone.plugin.smarthelper.audit.P3Log
import com.sheen.rokid.phone.plugin.smarthelper.context.ContextRoute
import com.sheen.rokid.phone.plugin.smarthelper.prompt.PromptCatalog
import com.sheen.rokid.phone.plugin.smarthelper.prompt.profileFixture
import com.sheen.rokid.plugin.Capability
import com.sheen.rokid.plugin.DeviceProfile
import com.sheen.rokid.plugin.ModelEnd
import com.sheen.rokid.plugin.ModelStreamEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * 0.3 Phase 2 Task 11 (spec §4.1/§4.2.1/§5.3): `P3_PROMPT` is a **per request** provenance line.
 *
 * It is how a device log can prove which prompt ran without ever printing it: the catalog version, the pinned plan's
 * ordered-section hash, the deterministic device-profile hash, the pinned SKILL catalog hash, the route, and the
 * system/tail sizes of the frame that request carried. It is emitted for both production frame paths — the wake
 * builder (one line per model request, so a tool round is two lines) and the proactive request assembly — and no longer
 * once per activation: the plan is still pinned for the session, but the line describes each rendered request.
 *
 * `dynamicChars` is the frame's [com.sheen.rokid.agent.RequestLayout.dynamicTail] length at build time. The loop-owned
 * `RUNTIME_STATE` (and `PERCEPTION`) segments are folded in by `AgentLoop` **after** the wake builder returns, so they
 * are deliberately excluded; the line stays counts/hashes only, with no prompt, device or user text.
 */
class PromptTelemetryTest {

    private val profile = profileFixture(model = "M2012K11AC")

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

    /** The production store-backed shape: production always injects one `ConversationStore` per route. */
    private fun wakeRig(deviceProfile: DeviceProfile? = profile): SmartTestRig {
        val rig = SmartTestRig(conversationStores = smartStores())
        rig.activate(
            overrides = buildMap {
                put(Capability.Storage, SmartFakeStorage())
                if (deviceProfile != null) put(Capability.DeviceState, SmartFakeDeviceState(deviceProfile))
            },
        )
        return rig
    }

    /** One request that answers normally, i.e. the per-request line the wake path emits before the gateway call. */
    private fun answerOnce(rig: SmartTestRig) {
        rig.model.onRequest = { request, listener -> streamText(request, listener, "好。") }
    }

    /** A tool round: the model notifies once, then answers — two model requests in one turn. */
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

    @Test
    fun `activation no longer emits a prompt line`() {
        val lines = withLines { activateWithDeviceProfile() }

        assertTrue(
            promptLines(lines).isEmpty(),
            "P3_PROMPT is per request now; activation must not claim a request that never happened: $lines",
        )
    }

    @Test
    fun `a wake request carries version, hashes, route and sizes but no prompt text`() {
        val rig = wakeRig()

        val lines = wakeLines(rig)

        val line = promptLine(lines)
        val plan = PromptCatalog.plan(ContextRoute.WAKE, profile)
        assertTrue(
            Regex(
                """^P3_PROMPT version=0\.3\.0 hash=[0-9a-f]{64} deviceHash=[0-9a-f]{64} """ +
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
        // No memory, skill body or retry in this request: the dynamic tail is empty (loop-owned segments come later).
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
    fun `a wake tool round emits one prompt line per model request`() {
        val rig = wakeRig()
        notifyThenAnswer(rig)

        val lines = withLines { rig.wakeAndFinal("现在几点了") }

        assertEquals(2, rig.model.requests.size, "the notify round-trip must make a second model request")
        val prompt = promptLines(lines)
        assertEquals(2, prompt.size, "one line per model request, not one per turn or activation: $lines")
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
        val plan = PromptCatalog.plan(ContextRoute.WAKE, null)
        assertEquals(plan.hash, field(line, "hash"), "the plan hash must be the profile-less plan: $line")
        assertEquals(plan.systemText.length.toString(), field(line, "staticChars"), line)

        // And the system prompt really omitted the device section.
        assertEquals(plan.systemText, rig.model.requests.single().instructions, "no profile means the profile-less plan")
        assertFalse("【设备画像】" in rig.model.requests.single().instructions, "the device section must be omitted")
    }

    @Test
    fun `a proactive request logs route=PROACTIVE with the proactive plan`() {
        // The proactive route has no AgentLoop: it assembles its frame and calls the gateway directly, so its line is
        // emitted by that request assembly. The plan is the PROACTIVE one, never the wake plan.
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

        val line = promptLine(lines)
        val plan = PromptCatalog.plan(ContextRoute.PROACTIVE, profile)
        assertTrue("route=PROACTIVE" in line, "the proactive request must name its own route: $line")
        assertEquals(plan.hash, field(line, "hash"), "the proactive request must use the proactive plan: $line")
        assertEquals(64, field(line, "deviceHash").length, "a supplied profile hashes to a SHA-256 digest: $line")
        assertNotEquals("none", field(line, "deviceHash"), line)
        assertEquals(plan.systemText.length.toString(), field(line, "staticChars"), line)
        assertEquals("0", field(line, "dynamicChars"), "no memory/skill body was on this request: $line")
        assertNotEquals(
            PromptCatalog.plan(ContextRoute.WAKE, profile).hash,
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
}
