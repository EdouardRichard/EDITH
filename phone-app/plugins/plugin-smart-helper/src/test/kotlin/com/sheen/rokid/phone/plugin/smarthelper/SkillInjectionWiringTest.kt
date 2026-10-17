package com.sheen.rokid.phone.plugin.smarthelper

import com.sheen.rokid.contracts.AcousticEvent
import com.sheen.rokid.contracts.AcousticLabel
import com.sheen.rokid.contracts.TranscriptEvent
import com.sheen.rokid.plugin.Capability
import com.sheen.rokid.plugin.ModelEnd
import com.sheen.rokid.plugin.ModelHistoryItem
import com.sheen.rokid.plugin.ModelStreamEvent
import com.sheen.rokid.phone.plugin.smarthelper.skill.LoadSkillTool
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The closure of the review's C3, first half, **rewritten for the two-layer injection** (Phase 6, Task 5).
 *
 * The old finding was that `SkillManager` had no production caller at all. Its test asserted the body arrived whole in
 * `instructions`; D73/D-1 replaces that form with a bounded catalog plus a pull. Every case below keeps its old
 * criterion and moves the assertion to the layer that now carries it (design §7's table):
 *
 * | old assertion | new assertion |
 * |---|---|
 * | the body is in `instructions` | the catalog is in `instructions`, and the body arrives after `load_skill` |
 * | the proactive route has the body | the proactive route has the **same catalog** (it has no pull tool by design) |
 * | no directory / broken file / refused grant ⇒ no `SKILL:` | those ⇒ no catalog segment, and the turn completes |
 * | a disabled SKILL is absent | a disabled SKILL is absent from the catalog **and** not loadable |
 */
class SkillInjectionWiringTest {

    /** A production-shaped rig: stores per route, so every request is a frame whose system slot the plugin builds. */
    private fun rig(skills: SmartFakeSkills? = null): SmartTestRig {
        val rig = SmartTestRig(conversationStores = smartStores())
        rig.activate(overrides = skills?.let { mapOf(Capability.StorageAccess to it) } ?: emptyMap())
        return rig
    }

    private fun answersNormally(rig: SmartTestRig) {
        rig.model.onRequest = { request, listener -> streamText(request, listener, "好。") }
    }

    private fun <T> withLines(block: (MutableList<String>) -> T): T {
        val lines = mutableListOf<String>()
        val previous = P3Log.sink
        P3Log.sink = { lines += it }
        return try {
            block(lines)
        } finally {
            P3Log.sink = previous
        }
    }

    private fun turnCompleted(lines: List<String>): Boolean =
        lines.any { it.startsWith("P3_TURN end ") && it.contains("outcome=COMPLETED") }

    @Test
    fun `an enabled skill still reaches the model, by pull instead of whole`() {
        // Replaces `an enabled skill is injected whole into the request instructions`, which asserted
        // `instructions.contains(marker)` on the first request. The intent is "the library's SKILL reaches the model",
        // not "it arrives early": the first request advertises it, the second carries the body after `load_skill`.
        val marker = "正文标记-8821"
        val rig = rig(SmartFakeSkills(mapOf("/sdcard/EDITH/skills/a.md" to smartSkillDoc("reply-wechat-message", marker))))
        var calls = 0
        rig.model.onRequest = { request, listener ->
            calls += 1
            if (calls == 1) {
                listener(
                    smartToolCall(
                        "c1", LoadSkillTool.NAME, """{"name":"reply-wechat-message"}""",
                        requestId = request.requestId,
                    ),
                )
                listener(ModelStreamEvent.Terminal(request.requestId, ModelEnd.COMPLETED, null, null))
            } else {
                streamText(request, listener, "好。")
            }
        }

        rig.wakeAndFinal("帮我回微信")

        assertEquals(2, rig.model.requests.size)
        val first = rig.model.requests[0].instructions
        assertTrue(first.contains("- reply-wechat-message: d-reply-wechat-message"), first)
        assertFalse(marker in first, "the catalog must not carry the body: $first")
        val second = rig.model.requests[1].instructions
        assertTrue(second.contains(marker), "the body must reach the model after the pull, not summarised: $second")
        assertTrue(second.contains("## SKILL 正文: reply-wechat-message"), second)
    }

    @Test
    fun `the proactive route is given the same skill catalog as the wake route`() {
        // Replaces `the proactive route is given the skills as well`, which asserted the **body** in the proactive
        // instructions. C3's intent — both routes share one library — is preserved as the shared **catalog**: the
        // proactive turn is one request whose only tool is `propose_hint`, so no body can be pulled on that route by
        // design (D-1/D-2). Asserting a body there would be asserting a shape the route deliberately does not have.
        val rig = rig(SmartFakeSkills(mapOf("/sdcard/EDITH/skills/a.md" to smartSkillDoc("brake-check", "正文标记-3312"))))
        answersNormally(rig)

        rig.plugin.handle(AcousticEvent(rig.envelope(), AcousticLabel.Speech))
        rig.plugin.handle(TranscriptEvent(rig.transcript("这个页面怎么一直转圈")))

        val proactive = rig.model.requests.single().instructions
        assertTrue(
            proactive.contains("- brake-check: d-brake-check"),
            "the proactive route must see the same library through its catalog: $proactive",
        )
        assertFalse("正文标记-3312" in proactive, "a proactive request has no pull, so it must carry no body: $proactive")
    }

    @Test
    fun `a host with no skill directory answers normally`() {
        // Replaces `assertTrue("SKILL:" !in instructions)`: spec §3.7's "no SKILLs installed is normal" now shows as an
        // absent catalog segment, and the turn still completes.
        val rig = rig(SmartFakeSkills())
        answersNormally(rig)

        val lines = withLines { captured ->
            rig.wakeAndFinal("你好")
            captured.toList()
        }

        assertEquals(1, rig.model.requests.size)
        assertFalse("## SKILL 目录" in rig.model.requests.single().instructions)
        assertTrue(turnCompleted(lines), "a host with no library must still answer: $lines")
    }

    @Test
    fun `a broken skill file does not break the turn`() {
        // Replaces `assertTrue("SKILL:" !in instructions)`: the broken file contributes no catalog entry and no
        // segment, and the turn still completes (spec §3.7 — a broken file is local to itself).
        val rig = rig(SmartFakeSkills(mapOf("/sdcard/EDITH/skills/a.md" to "no front matter at all")))
        answersNormally(rig)

        val lines = withLines { captured ->
            rig.wakeAndFinal("你好")
            captured.toList()
        }

        assertEquals(1, rig.model.requests.size)
        assertFalse("## SKILL 目录" in rig.model.requests.single().instructions)
        assertTrue(turnCompleted(lines), "a broken file must not break the turn: $lines")
    }

    @Test
    fun `a disabled skill is not injected`() {
        // Stronger than the old assertion (`"SKILL:" !in instructions`): the disabled entry is neither advertised nor
        // loadable, so the model cannot pull it either. Its body must not leak through the refusal.
        val rig = rig(SmartFakeSkills(mapOf(
            "/sdcard/EDITH/skills/a.md" to smartSkillDoc("off", "不该出现", enabled = false),
        )))
        var calls = 0
        rig.model.onRequest = { request, listener ->
            calls += 1
            if (calls == 1) {
                listener(smartToolCall("c1", LoadSkillTool.NAME, """{"name":"off"}""", requestId = request.requestId))
                listener(ModelStreamEvent.Terminal(request.requestId, ModelEnd.COMPLETED, null, null))
            } else {
                streamText(request, listener, "好。")
            }
        }

        rig.wakeAndFinal("你好")

        assertEquals(2, rig.model.requests.size)
        assertFalse(
            "## SKILL 目录" in rig.model.requests[0].instructions,
            "a disabled entry must not be advertised: ${rig.model.requests[0].instructions}",
        )
        assertTrue(
            rig.model.requests[1].history.any {
                it is ModelHistoryItem.ToolOutput && it.output.contains(LoadSkillTool.DISABLED)
            },
            "a disabled entry must be refused as disabled, not silently absent: ${rig.model.requests[1].history}",
        )
        assertFalse(
            rig.model.requests[1].instructions.contains("不该出现"),
            "a refused pull must not leak the disabled body into the system slot",
        )
    }

    @Test
    fun `a directory the host refuses leaves the system prompt alone`() {
        // Replaces the same intention as before: a refused StorageAccess grant degrades to "no SKILLs", never to a
        // failed turn. The assertion moves from the instructions string to the catalog segment.
        val rig = rig(skills = null)
        answersNormally(rig)

        val lines = withLines { captured ->
            rig.wakeAndFinal("你好")
            captured.toList()
        }

        assertEquals(1, rig.model.requests.size)
        assertFalse("## SKILL 目录" in rig.model.requests.single().instructions)
        assertTrue(turnCompleted(lines), "a refused grant must not fail the turn: $lines")
    }
}
