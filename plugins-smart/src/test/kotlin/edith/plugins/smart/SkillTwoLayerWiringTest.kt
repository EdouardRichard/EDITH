package edith.plugins.smart

import edith.agent.ConversationStore
import edith.contracts.AcousticEvent
import edith.contracts.AcousticLabel
import edith.contracts.TranscriptEvent
import edith.plugin.Capability
import edith.plugin.ModelEnd
import edith.plugin.ModelHistoryItem
import edith.plugin.ModelStreamEvent
import edith.plugins.smart.context.ContextRoute
import edith.plugins.smart.skill.LoadSkillTool
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Phase 6 design D-1/D-2, wired into the plugin (Task 5).
 *
 * The library reaches the model in **two layers**: every request carries the catalog, and a body appears only after
 * the model pulls it with `load_skill`, for the rest of that turn, through the system slot's `SKILL` origin.
 *
 * The load-bearing pair is [the first request carries the catalog and not the body, the second request carries the
 * body]. Removing either half of that assertion would leave a test that goes green as soon as the body appears in the
 * request at all — which is exactly the D60 whole-library injection this phase removes — so the two halves are
 * deliberately in one test and both are asserted.
 *
 * These tests drive the **production shape**: a `ConversationStore` per route, so each request is a `RequestFrame` and
 * the system slot is the plugin's own. The legacy no-store path has no system slot and therefore no SKILL injection;
 * it is the pre-Phase-5 shape and is not the production wiring (`SmartWiring` passes stores).
 */
class SkillTwoLayerWiringTest {

    private fun rig(
        skills: SmartFakeSkills,
        stores: Map<ContextRoute, ConversationStore> = smartStores(),
    ): SmartTestRig = SmartTestRig(conversationStores = stores).also { rig ->
        rig.activate(overrides = mapOf(Capability.StorageAccess to skills))
    }

    /**
     * The model pulls [name] on its first request, then answers on the second — one tool round-trip inside one turn,
     * the shape `StoreSessionWiringTest` uses for `notify_user`.
     */
    private fun pullThenAnswer(rig: SmartTestRig, name: String, answer: String = "好。") {
        var calls = 0
        rig.model.onRequest = { request, listener ->
            calls += 1
            if (calls == 1) {
                listener(
                    smartToolCall(
                        "c1", LoadSkillTool.NAME, """{"name":"$name"}""",
                        requestId = request.requestId,
                    ),
                )
                listener(ModelStreamEvent.Terminal(request.requestId, ModelEnd.COMPLETED, null, null))
            } else {
                streamText(request, listener, answer)
            }
        }
    }

    /** The model notifies once, then answers: a tool round-trip that is **not** a pull, so it must not move drift. */
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

    // ------------------------------------------------------------ the two layers

    @Test
    fun `the first request carries the catalog and not the body, the second request carries the body`() {
        val marker = "正文标记-8821"
        val rig = rig(SmartFakeSkills(mapOf("/sdcard/EDITH/skills/a.md" to smartSkillDoc("reply-wechat-message", marker))))
        pullThenAnswer(rig, "reply-wechat-message")

        rig.wakeAndFinal("帮我回微信")

        assertEquals(2, rig.model.requests.size, "the load_skill round-trip must make a second model request")
        val first = rig.model.requests[0].instructions
        assertTrue(first.contains("## SKILL 目录"), "the first request must advertise the catalog: $first")
        assertTrue(
            first.contains("- reply-wechat-message: d-reply-wechat-message"),
            "the catalog carries name and description: $first",
        )
        assertFalse(
            marker in first,
            "the body must NOT reach the first request — the catalog is the whole point of the second layer: $first",
        )

        val second = rig.model.requests[1].instructions
        assertTrue(marker in second, "the pulled body must reach the next request of the same turn: $second")
        assertTrue(
            second.contains("## SKILL 正文: reply-wechat-message"),
            "the body travels in its own SKILL segment under a heading that names it: $second",
        )
    }

    @Test
    fun `the pulled body is gone from the next turn's first request`() {
        // Design D-2: the body lives for one turn. Accumulating it across turns would move the unbounded growth the
        // whole phase removes from the catalog into the conversation — so this half matters as much as the first.
        val marker = "正文标记-4417"
        val rig = rig(SmartFakeSkills(mapOf("/sdcard/EDITH/skills/a.md" to smartSkillDoc("alpha", marker))))
        var calls = 0
        rig.model.onRequest = { request, listener ->
            calls += 1
            if (calls == 1) {
                listener(smartToolCall("c1", LoadSkillTool.NAME, """{"name":"alpha"}""", requestId = request.requestId))
                listener(ModelStreamEvent.Terminal(request.requestId, ModelEnd.COMPLETED, null, null))
            } else {
                streamText(request, listener, "好。")
            }
        }

        rig.wakeAndFinal("第一回合")
        rig.tick(4_000) // beyond the wake cooldown, so the second turn is admitted
        rig.wakeAndFinal("第二回合")

        assertEquals(3, rig.model.requests.size, "turn 1 makes two requests, turn 2 makes one")
        assertTrue(marker in rig.model.requests[1].instructions, "turn 1's second request carries the pulled body")
        val secondTurnFirst = rig.model.requests[2].instructions
        assertTrue(
            secondTurnFirst.contains("- alpha: d-alpha"),
            "the next turn's first request must advertise the catalog again: $secondTurnFirst",
        )
        assertFalse(
            marker in secondTurnFirst,
            "the body is turn-scoped and must be gone from the next turn's first request: $secondTurnFirst",
        )
    }

    @Test
    fun `a turn advertises once even when it makes several requests`() {
        // Half one (catalog non-empty in both requests) is what catches a missing `beginTurn()`: with no observation the
        // governor has no entries, `catalog()` answers "", and the model silently sees no SKILL at all. Half two pins the
        // observation unit to the turn, not the request (design D-3).
        val rig = rig(SmartFakeSkills(mapOf("/sdcard/EDITH/skills/a.md" to smartSkillDoc("alpha", "正文"))))
        notifyThenAnswer(rig)

        rig.wakeAndFinal("两请求一回合")

        assertEquals(2, rig.model.requests.size)
        rig.model.requests.forEachIndexed { index, request ->
            assertTrue(
                request.instructions.contains("- alpha: d-alpha"),
                "request $index must advertise the catalog: ${request.instructions}",
            )
        }
        val governor = rig.plugin.skillGovernor()
        assertTrue(governor != null, "activation must build the session's library governor")
        assertEquals(
            1, governor.entry("alpha")?.observedTurns,
            "two requests are one turn; the second request must not add a second observation",
        )
    }

    @Test
    fun `a proactive turn followed by a wake turn records two observations`() {
        // Obligations 1 and 2: each route opens and closes its own turn. Without the proactive `endTurn()` the governor
        // stays open across the route switch and folds both turns into one observation — the exact silent arithmetic
        // error this test exists to catch.
        val rig = rig(SmartFakeSkills(mapOf("/sdcard/EDITH/skills/a.md" to smartSkillDoc("alpha", "正文"))))
        rig.model.onRequest = { request, listener ->
            if (request.tools.any { it.name == SmartPrompts.PROPOSE_HINT.name }) {
                listener(
                    smartToolCall(
                        "p1", SmartPrompts.PROPOSE_HINT.name, """{"text":"主动提示"}""",
                        requestId = request.requestId,
                    ),
                )
                listener(ModelStreamEvent.Terminal(request.requestId, ModelEnd.COMPLETED, null, null))
            } else {
                streamText(request, listener, "好。")
            }
        }

        // The proactive route: armed by a perception signal, admitted by a question-shaped trigger.
        rig.plugin.handle(AcousticEvent(rig.envelope(), AcousticLabel.Speech))
        rig.plugin.handle(TranscriptEvent(rig.transcript("帮我记一下这件事", turnId = "asr-p1")))
        rig.tick(4_000)
        // The wake route.
        rig.wakeAndFinal("开灯", turnId = "asr-2")

        assertEquals(2, rig.model.requests.size, "one proactive request, then one wake request")
        rig.model.requests.forEachIndexed { index, request ->
            assertTrue(
                request.instructions.contains("- alpha: d-alpha"),
                "both routes must advertise the shared catalog; request $index was: ${request.instructions}",
            )
        }
        assertEquals(
            2, rig.plugin.skillGovernor()?.entry("alpha")?.observedTurns,
            "a proactive turn and a wake turn are two separate observations",
        )
    }

    @Test
    fun `a SkillDirectory that throws leaves the turn running with no catalog segment`() {
        val rig = rig(SmartFakeSkills(listThrows = true))
        rig.model.onRequest = { request, listener -> streamText(request, listener, "好。") }

        val lines = withLines { captured ->
            rig.wakeAndFinal("你好")
            captured.toList()
        }

        assertEquals(1, rig.model.requests.size)
        val instructions = rig.model.requests.single().instructions
        assertFalse("## SKILL 目录" in instructions, "a broken library contributes no catalog segment: $instructions")
        assertFalse(lines.any { it.startsWith("P3_SKILL catalog ") }, "no catalog was built, so none may be reported")
        assertTrue(turnCompleted(lines), "a broken library must not fail the turn: $lines")
    }

    @Test
    fun `a refused pull does not end the turn`() {
        // LoadSkillTool's refusal is a step the model can correct, never an exception and never a terminal state.
        val rig = rig(SmartFakeSkills(mapOf("/sdcard/EDITH/skills/a.md" to smartSkillDoc("alpha", "正文"))))
        pullThenAnswer(rig, "missing")

        val lines = withLines { captured ->
            rig.wakeAndFinal("帮我回微信")
            captured.toList()
        }

        assertEquals(2, rig.model.requests.size, "a refusal is fed back so the model gets another request")
        assertTrue(
            rig.model.requests[0].instructions.contains("- alpha: d-alpha"),
            "the catalog is what the model chose from, so it must carry the one real entry",
        )
        assertTrue(
            rig.model.requests[1].history.any {
                it is ModelHistoryItem.ToolOutput && it.output.contains(LoadSkillTool.NOT_FOUND)
            },
            "the refusal code must reach the model: ${rig.model.requests[1].history}",
        )
        assertTrue(turnCompleted(lines), "a refused pull must not end the turn: $lines")
    }

    @Test
    fun `three pulls in one turn keep distinct receipts and the turn completes`() {
        // Obligation 3: the tool instance — and therefore its receipt counter — must survive across the requests of one
        // run. A per-request instance restarts at `loaded:1`; three such receipts are three identical consecutive tool
        // outputs, `ProgressGuard` ends the run as `NO_PROGRESS`, and the phase's own tool kills the turn (design D-8).
        val names = listOf("alpha", "beta", "gamma")
        val rig = rig(
            SmartFakeSkills(names.associate { name -> "/sdcard/EDITH/skills/$name.md" to smartSkillDoc(name, "正文-$name") }),
        )
        var calls = 0
        rig.model.onRequest = { request, listener ->
            if (calls < names.size) {
                val name = names[calls]
                calls += 1
                listener(smartToolCall("c$calls", LoadSkillTool.NAME, """{"name":"$name"}""", requestId = request.requestId))
                listener(ModelStreamEvent.Terminal(request.requestId, ModelEnd.COMPLETED, null, null))
            } else {
                streamText(request, listener, "好。")
            }
        }

        val lines = withLines { captured ->
            rig.wakeAndFinal("三份都看看")
            captured.toList()
        }

        assertEquals(4, rig.model.requests.size, "three pulls plus the answer: the guard must not end the run")
        val receipts = rig.model.requests.last().history
            .filterIsInstance<ModelHistoryItem.ToolOutput>()
            .map { it.output }
        assertEquals(
            listOf("loaded:1", "loaded:2", "loaded:3"), receipts,
            "one instance across requests is what keeps the receipts distinct: $receipts",
        )
        assertTrue(turnCompleted(lines), "the turn must complete, not trip NO_PROGRESS: $lines")
    }

    // ------------------------------------------------------------ the B4 criterion, migrated

    @Test
    fun `migrated B4 criterion an enabled skill still reaches the model, by pull instead of whole`() {
        // Replaces `SkillInjectionWiringTest`'s `an enabled skill is injected whole into the request instructions`.
        // The old intent was "the library's SKILL reaches the model", not "it arrives early"; it now arrives after the
        // pull, and this test pins that arrival.
        val marker = "正文标记-5520"
        val rig = rig(SmartFakeSkills(mapOf("/sdcard/EDITH/skills/a.md" to smartSkillDoc("reply-wechat-message", marker))))
        pullThenAnswer(rig, "reply-wechat-message")

        rig.wakeAndFinal("帮我回微信")

        val second = rig.model.requests[1].instructions
        assertTrue(marker in second, "an enabled SKILL's body must still reach the model: $second")
        assertTrue(second.contains("## SKILL 正文: reply-wechat-message"), second)
    }

    // ------------------------------------------------------------ the turn-end count line (design D-10)

    @Test
    fun `the turn end reports the pull counts`() {
        // D-10 fixes the line's shape and requires it to be assertable verbatim. `neverPulled` is the size of
        // "appeared in the catalog this session" minus "was pulled this session": one of the two entries is pulled.
        val rig = rig(
            SmartFakeSkills(
                mapOf(
                    "/sdcard/EDITH/skills/a.md" to smartSkillDoc("alpha", "正文-a"),
                    "/sdcard/EDITH/skills/b.md" to smartSkillDoc("beta", "正文-b"),
                ),
            ),
        )
        pullThenAnswer(rig, "alpha")

        val lines = withLines { captured ->
            rig.wakeAndFinal("帮我回微信")
            captured.toList()
        }

        assertTrue(
            "P3_SKILL pulls turn=1 session=1 neverPulled=1" in lines,
            "the turn-end count line must be exactly the design's shape: $lines",
        )
    }
}
