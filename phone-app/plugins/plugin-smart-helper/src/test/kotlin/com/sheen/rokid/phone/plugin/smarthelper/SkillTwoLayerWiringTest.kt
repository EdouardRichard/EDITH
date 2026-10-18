package com.sheen.rokid.phone.plugin.smarthelper
import com.sheen.rokid.phone.plugin.smarthelper.scene.SmartPrompts
import com.sheen.rokid.phone.plugin.smarthelper.library.LibraryGovernor
import com.sheen.rokid.phone.plugin.smarthelper.skill.SkillDirectory
import com.sheen.rokid.phone.plugin.smarthelper.audit.P3Log

import com.sheen.rokid.agent.ConversationStore
import com.sheen.rokid.agent.Segment
import com.sheen.rokid.agent.SegmentOrigin
import com.sheen.rokid.contracts.AcousticEvent
import com.sheen.rokid.contracts.AcousticLabel
import com.sheen.rokid.contracts.TranscriptEvent
import com.sheen.rokid.plugin.Capability
import com.sheen.rokid.plugin.ModelEnd
import com.sheen.rokid.plugin.ModelHistoryItem
import com.sheen.rokid.plugin.ModelStreamEvent
import com.sheen.rokid.plugin.StorageAccess
import com.sheen.rokid.phone.plugin.smarthelper.context.ContextRoute
import com.sheen.rokid.phone.plugin.smarthelper.skill.LoadSkillTool
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
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
    fun `a load_skill pull resets the answer stream but paints no device tool row`() {
        // Task 17 review M6(a): `load_skill` is a SKILL read, not an ExecutionRouter action, so it must never become a
        // tool row on the glasses (the filter, not just the `notify_user` filter, is what is being pinned). Its round
        // is still a real tool round for the answer-stream reset, so the pull must reach the model's second request.
        val display = SmartRecordingDisplay()
        val rig = SmartTestRig(panelVisible = true, conversationStores = smartStores()).also {
            it.activate(
                overrides = mapOf(
                    Capability.StorageAccess to
                        SmartFakeSkills(mapOf("/sdcard/EDITH/skills/a.md" to smartSkillDoc("alpha", "正文"))),
                    Capability.ConversationDisplay to display,
                ),
            )
        }
        pullThenAnswer(rig, "alpha")

        rig.wakeAndFinal("帮我看看")

        assertEquals(2, rig.model.requests.size, "the load_skill round-trip really happened")
        assertTrue(
            display.toolStatuses.isEmpty(),
            "load_skill must not paint a device tool row: ${display.toolStatuses}",
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
    fun `two system slot builds in one turn with the same loaded set are byte-identical`() {
        // Design D-7 (the AC-3 range): with the same turn and the same loaded set, two builds of the SKILL segment are
        // byte-identical. The catalog is recomputed per request (D-1), so this pins that recomputation against a library
        // that did not change and a pull set that did not change; a per-build counter, timestamp or unstable sort order
        // would show up as a difference here.
        val marker = "正文标记-9701"
        val rig = rig(SmartFakeSkills(mapOf("/sdcard/EDITH/skills/a.md" to smartSkillDoc("alpha", marker))))
        val generation = rig.plugin.beginSkillTurnForTest(owner = true)
        try {
            rig.plugin.loadSkillToolForTest().execute(LoadSkillTool.NAME, """{"name":"alpha"}""")
            val first = rig.plugin.systemSlotForTest(ContextRoute.WAKE).filter { it.origin == SegmentOrigin.SKILL }
            val second = rig.plugin.systemSlotForTest(ContextRoute.WAKE).filter { it.origin == SegmentOrigin.SKILL }

            assertTrue(first.isNotEmpty(), "the slot must carry the catalog and the pulled body: $first")
            assertTrue(
                first.any { it.text.startsWith("## SKILL 目录") },
                "the catalog is one of the two SKILL layers this test compares: $first",
            )
            assertTrue(first.any { marker in it.text }, "the loaded body is part of the compared segments: $first")
            assertEquals(
                first.map { it.text }, second.map { it.text },
                "design D-7: two builds in one turn with the same loaded set must be byte-identical",
            )
        } finally {
            rig.plugin.endSkillTurnForTest(generation)
        }
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

    // ------------------------------------------------------------ turn ownership under replacement (fix round)

    /**
     * Review finding (Important): the SKILL turn state is plugin-global, but turns are concurrent — a stopped/replaced
     * wake turn keeps unwinding and still reaches `endSkillTurn`. With one shared map and no owner there is nothing to
     * tell turn 1's late end from turn 2's own end, so it clears the live turn's pulled bodies (and closes its
     * observation). Ownership by generation is what makes the late end a no-op.
     *
     * The seam calls the production boundary directly because this interleaving cannot be produced through the event
     * flow deterministically: the replacement must begin *before* the stopped turn's end, which the host's own turn
     * replacement only races into. The "next request" is a real one: the proactive route builds its frame through the
     * same `systemSlot`, and a proactive turn joins the live turn instead of owning a new one.
     */
    @Test
    fun `a stale end from a stopped turn does not clear the next turn's pulled bodies`() {
        val firstMarker = "正文标记-8101"
        val secondMarker = "正文标记-8102"
        val rig = rig(
            SmartFakeSkills(
                mapOf(
                    "/sdcard/EDITH/skills/a.md" to smartSkillDoc("alpha", firstMarker),
                    "/sdcard/EDITH/skills/b.md" to smartSkillDoc("beta", secondMarker),
                ),
            ),
        )

        // Turn 1 owns the lifecycle and pulls alpha; turn 2 then takes ownership and pulls beta. Turn 1 is the one
        // that was stopped/replaced: its end must not carry turn 1's generation into turn 2's turn state.
        val first = rig.plugin.beginSkillTurnForTest(owner = true)
        rig.plugin.loadSkillToolForTest().execute(LoadSkillTool.NAME, """{"name":"alpha"}""")
        val second = rig.plugin.beginSkillTurnForTest(owner = true)
        rig.plugin.loadSkillToolForTest().execute(LoadSkillTool.NAME, """{"name":"beta"}""")
        rig.plugin.endSkillTurnForTest(first)

        // The next request goes through the real frame path; its slot must still carry turn 2's body.
        rig.model.onRequest = { request, listener ->
            listener(
                smartToolCall(
                    "p1", SmartPrompts.PROPOSE_HINT.name, """{"text":"主动提示"}""",
                    requestId = request.requestId,
                ),
            )
            listener(ModelStreamEvent.Terminal(request.requestId, ModelEnd.COMPLETED, null, null))
        }
        rig.plugin.handle(AcousticEvent(rig.envelope(), AcousticLabel.Speech))
        rig.plugin.handle(TranscriptEvent(rig.transcript("这个页面怎么一直转圈", turnId = "asr-p1")))

        val sent = rig.model.requests.single().instructions
        assertTrue(
            secondMarker in sent,
            "turn 1's late end must not clear the body turn 2 pulled: $sent",
        )
        assertFalse(firstMarker in sent, "turn 2 began with no pulled bodies: $sent")
        rig.plugin.endSkillTurnForTest(second)
    }

    /**
     * A proactive turn has no `load_skill` tool, so it never contributes a body and must never wipe the one a live
     * wake turn has pulled. When it overlaps a wake turn it therefore only joins the wake turn's observation; its own
     * end is a no-op. (When it is alone it owns its own turn — see `a proactive turn followed by a wake turn records
     * two observations` — because nothing else would ever close its observation.)
     */
    @Test
    fun `a proactive turn never clears a wake turn's pulled bodies`() {
        val marker = "正文标记-8103"
        val rig = rig(SmartFakeSkills(mapOf("/sdcard/EDITH/skills/a.md" to smartSkillDoc("alpha", marker))))

        val wake = rig.plugin.beginSkillTurnForTest(owner = true)
        rig.plugin.loadSkillToolForTest().execute(LoadSkillTool.NAME, """{"name":"alpha"}""")

        val proactive = rig.plugin.beginSkillTurnForTest(owner = false)
        assertEquals(0L, proactive, "a proactive turn beside a live wake turn must join it, not take ownership")
        rig.plugin.endSkillTurnForTest(proactive)

        val slot = rig.plugin.systemSlotForTest(ContextRoute.WAKE)
        assertTrue(
            slot.any { it.origin == SegmentOrigin.SKILL && marker in it.text },
            "the wake turn's pulled body must survive the proactive turn's end: $slot",
        )
        rig.plugin.endSkillTurnForTest(wake)
    }

    /**
     * Design D-1/D-2 fixes **where** the body travels: it belongs to the system slot's `SKILL` origin, never to
     * `INSTRUCTIONS`. The flattened `instructions` string cannot tell the two apart, so the old assertions would pass
     * even if the body were moved back into the instruction segment. This asserts on the segments themselves.
     */
    @Test
    fun `a pulled body travels in a SKILL segment and never in INSTRUCTIONS`() {
        val marker = "正文标记-8104"
        val rig = rig(SmartFakeSkills(mapOf("/sdcard/EDITH/skills/a.md" to smartSkillDoc("alpha", marker))))
        val slots = mutableListOf<List<Segment>>()
        var calls = 0
        rig.model.onRequest = { request, listener ->
            calls += 1
            if (calls == 1) {
                listener(smartToolCall("c1", LoadSkillTool.NAME, """{"name":"alpha"}""", requestId = request.requestId))
                listener(ModelStreamEvent.Terminal(request.requestId, ModelEnd.COMPLETED, null, null))
            } else {
                // The production producer for the slot this very request carries (the gateway flattens it into
                // `instructions`, so the segments are only observable through the plugin).
                slots += rig.plugin.systemSlotForTest(ContextRoute.WAKE)
                streamText(request, listener, "好。")
            }
        }

        rig.wakeAndFinal("帮我回微信")

        val slot = slots.single()
        assertTrue(
            slot.filter { it.origin == SegmentOrigin.SKILL }.any { marker in it.text },
            "the pulled body must travel in a SKILL segment: $slot",
        )
        assertFalse(
            slot.filter { it.origin == SegmentOrigin.INSTRUCTIONS }.any { marker in it.text },
            "the body must not be folded into INSTRUCTIONS; its origin is the whole point: $slot",
        )
    }

    /**
     * Review finding (Important), concurrency half: `LibraryGovernor` documents "not thread-safe" and the SKILL state
     * was one map and one turn flag read and written from the plugin's **two-thread** production pool. This drives two
     * turns through the real boundary and pins the interleaving with latches: a blocking directory holds turn A inside
     * its catalog build while turn B begins and ends.
     *
     * The latch handshake is deliberate — a bare race would only catch the corruption sometimes. Pre-fix, B's end is
     * free to run while A is mid-request, clearing the body A is about to read; the catalog build and the body snapshot
     * are also two separate lock acquisitions. Post-fix the one SKILL lock serialises B behind A, so A's request keeps
     * its body and no shared structure is mutated by two threads.
     */
    @Test
    fun `concurrent begin catalog and end do not throw or corrupt the turn state`() {
        val storage = BlockingSkillStorage(
            mapOf(
                "/sdcard/EDITH/skills/a.md" to smartSkillDoc("alpha", "正文-a"),
                "/sdcard/EDITH/skills/b.md" to smartSkillDoc("beta", "正文-b"),
            ),
        )
        val rig = SmartTestRig(conversationStores = smartStores()).also {
            it.activate(overrides = mapOf(Capability.StorageAccess to storage))
        }
        val failures = ConcurrentLinkedQueue<Throwable>()
        val firstSlot = AtomicReference<List<Segment>>(emptyList())
        val secondDone = CountDownLatch(1)

        val first = Thread {
            try {
                val generation = rig.plugin.beginSkillTurnForTest(owner = true)
                rig.plugin.loadSkillToolForTest().execute(LoadSkillTool.NAME, """{"name":"alpha"}""")
                // The next directory listing is the catalog build of this turn's request, and it blocks there.
                storage.blockNextList.set(true)
                firstSlot.set(rig.plugin.systemSlotForTest(ContextRoute.WAKE))
                rig.plugin.endSkillTurnForTest(generation)
            } catch (failure: Throwable) {
                failures += failure
            }
        }
        first.start()
        assertTrue(storage.entered.await(2, TimeUnit.SECONDS), "the first turn must reach its catalog build")

        val second = Thread {
            try {
                val generation = rig.plugin.beginSkillTurnForTest(owner = true)
                rig.plugin.endSkillTurnForTest(generation)
            } catch (failure: Throwable) {
                failures += failure
            }
            secondDone.countDown()
        }
        second.start()
        // Not an assertion on timing: pre-fix the second turn finishes here and clears the first turn's body; post-fix
        // it waits on the SKILL lock, and the wait only bounds the test.
        secondDone.await(500, TimeUnit.MILLISECONDS)
        storage.release()
        first.join(5_000)
        second.join(5_000)

        assertFalse(first.isAlive || second.isAlive, "both turns must finish; a torn structure or a deadlock is a failure")
        assertTrue(failures.isEmpty(), "concurrent SKILL turn work threw: ${failures.take(3)}")
        assertTrue(
            firstSlot.get().any { it.origin == SegmentOrigin.SKILL && "正文-a" in it.text },
            "the overlapping turn's end must not clear the body this request is still building: ${firstSlot.get()}",
        )

        // The shared state must be intact: both entries still governed, the catalog still non-empty, and a fresh turn
        // still opens and closes without wedging mid-turn.
        val governor = assertNotNull(rig.plugin.skillGovernor(), "activation must build the session's library governor")
        assertTrue(
            governor.entry("alpha") != null && governor.entry("beta") != null,
            "both advertised entries must still be governed",
        )
        val fresh = rig.plugin.beginSkillTurnForTest(owner = true)
        val freshSlot = rig.plugin.systemSlotForTest(ContextRoute.WAKE)
        rig.plugin.endSkillTurnForTest(fresh)
        assertTrue(
            freshSlot.any { it.origin == SegmentOrigin.SKILL && "- alpha: d-alpha" in it.text },
            "the catalog must still be intact after the overlap: $freshSlot",
        )
    }
}

/**
 * A `StorageAccess` that can hold one `listFiles` call on a latch, so a test can pin the exact interleaving of two
 * turns instead of racing them. The block is armed explicitly and fires once.
 */
private class BlockingSkillStorage(files: Map<String, String?>) : StorageAccess {
    private val delegate = SmartFakeSkills(files)
    val blockNextList = AtomicBoolean(false)
    val entered = CountDownLatch(1)
    private val release = CountDownLatch(1)

    fun release() = release.countDown()

    override fun listFiles(dirPath: String, suffix: String): List<String> {
        if (blockNextList.compareAndSet(true, false)) {
            entered.countDown()
            release.await(5, TimeUnit.SECONDS)
        }
        return delegate.listFiles(dirPath, suffix)
    }

    override fun readFile(path: String): String? = delegate.readFile(path)
    override fun deleteFile(path: String): Boolean = delegate.deleteFile(path)
}
