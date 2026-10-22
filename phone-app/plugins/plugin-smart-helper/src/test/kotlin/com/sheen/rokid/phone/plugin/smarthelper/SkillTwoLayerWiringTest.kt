package com.sheen.rokid.phone.plugin.smarthelper
import com.sheen.rokid.phone.plugin.smarthelper.scene.ProactiveHintSpec
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
import com.sheen.rokid.plugin.InputPart
import com.sheen.rokid.plugin.ModelEnd
import com.sheen.rokid.plugin.ModelHistoryItem
import com.sheen.rokid.plugin.ModelStreamEvent
import com.sheen.rokid.phone.plugin.smarthelper.context.ContextRoute
import com.sheen.rokid.phone.plugin.smarthelper.skill.LoadSkillTool
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Phase 6 design D-1/D-2, wired into the plugin (Task 5).
 *
 * The library reaches the model in **two layers**: every request carries the catalog in its system slot, and a body
 * appears only after the model pulls it with `load_skill`, for the rest of that turn, as `SKILL_BODY` content in the
 * request tail (0.3 Task 12; before that the body rode the system slot, which rewrote the cacheable prefix).
 *
 * The load-bearing pair is [the first request carries the catalog and not the body, the second request carries the
 * body]. Removing either half of that assertion would leave a test that goes green as soon as the body appears in the
 * request at all — which is exactly the D60 whole-library injection this phase removes — so the two halves are
 * deliberately in one test and both are asserted.
 *
 * The two layers are also one **snapshot** (0.3 Task 12 review fix, Important 2): the catalog and the pulled bodies are
 * read from one `skillLock` critical section. Otherwise a concurrent `beginSkillTurn` (which clears the turn's bodies)
 * could pair a newer catalog with the previous turn's pulled set. The pairing tests read the production
 * `requestSkillSegmentsForTest` seam, which is the same call both frame builders make.
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

    /** The one request input as text: 0.3 Task 12 puts the pulled body in this request-scoped tail. */
    private fun inputText(request: com.sheen.rokid.plugin.ModelRequest): String =
        request.input.filterIsInstance<InputPart.Text>().joinToString("\n\n") { it.text }

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
    fun `a skill library mutation does not rewrite the session prompt and is reported once`() {
        // 0.3 review fix (spec §4.4/§5.1): the SKILL catalog is part of the cacheable system slot, so it is pinned at
        // activation. A directory edit mid-session must leave `instructions` byte-identical (the pinned catalog), and
        // the detectable change must be named for the trace: `P3_SKILL catalog_changed`, hashes only.
        val storage = MutableSmartFakeSkills(
            mapOf("/sdcard/EDITH/skills/a.md" to smartSkillDoc("alpha", "正文-a")),
        )
        val rig = SmartTestRig(conversationStores = smartStores())
        val lines = mutableListOf<String>()
        val previous = P3Log.sink
        P3Log.sink = { lines += it }
        try {
            rig.activate(overrides = mapOf(Capability.StorageAccess to storage))
            rig.model.onRequest = { request, listener -> streamText(request, listener, "好。") }

            rig.wakeAndFinal("第一问", turnId = "asr-1")
            val first = rig.model.requests.single().instructions
            val promptLine = lines.single { it.startsWith("P3_PROMPT ") }
            val pinnedHash = promptLine.substringAfter("catalogHash=").substringBefore(' ')

            // The user installs a second SKILL while the session is live.
            storage.files["/sdcard/EDITH/skills/b.md"] = smartSkillDoc("beta", "正文-b")
            rig.tick(4_000)
            rig.wakeAndFinal("第二问", turnId = "asr-2")

            val second = rig.model.requests.last().instructions
            assertEquals(first, second, "a live session's system slot must not be rewritten by a library edit")
            assertFalse("- beta: d-beta" in second, "the pinned catalog must not grow mid-session: $second")
            assertTrue(
                lines.any { it.startsWith("P3_SKILL catalog_changed ") },
                "a detectable catalog change must be named, not silent: $lines",
            )
            assertEquals(
                pinnedHash,
                promptLine.substringAfter("catalogHash=").substringBefore(' '),
                "the provenance line must keep the session's pinned catalog hash",
            )
            assertNotEquals("none", pinnedHash, "the session had a catalog installed at activation")
        } finally {
            P3Log.sink = previous
        }
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
    fun `one request's catalog and pulled bodies come from one snapshot and repeat byte-identically`() {
        // Design D-7 (the AC-3 range): with the same turn and the same loaded set, two builds of the request's SKILL
        // view are byte-identical. The catalog is recomputed per request (D-1), so this pins that recomputation against
        // a library that did not change and a pull set that did not change; a per-build counter, timestamp or unstable
        // sort order would show up as a difference here.
        //
        // 0.3 Task 12 moved the pulled body out of the system slot, which left `systemSlot` and `skillBodySegments`
        // taking separate `skillLock` snapshots. The review fix restores one snapshot: `requestSkillSegments` (the
        // production source of both halves) returns the pair, and this test reads that pair rather than capturing the
        // bodies first and the catalog later.
        val marker = "正文标记-9701"
        val rig = rig(SmartFakeSkills(mapOf("/sdcard/EDITH/skills/a.md" to smartSkillDoc("alpha", marker))))
        val generation = rig.plugin.beginSkillTurnForTest(owner = true)
        try {
            rig.plugin.loadSkillToolForTest().execute(LoadSkillTool.NAME, """{"name":"alpha"}""")
            val first = rig.plugin.requestSkillSegmentsForTest(ContextRoute.WAKE)
            val second = rig.plugin.requestSkillSegmentsForTest(ContextRoute.WAKE)

            val (firstSystem, firstBodies) = first
            val (secondSystem, secondBodies) = second
            assertTrue(
                firstSystem.any { it.origin == SegmentOrigin.SKILL && it.text.startsWith("## SKILL 目录") },
                "the system half must carry the catalog: $firstSystem",
            )
            assertFalse(
                firstSystem.any { marker in it.text },
                "the pulled body must not be in the system half: $firstSystem",
            )
            assertTrue(
                firstBodies.any { it.origin == SegmentOrigin.SKILL_BODY && marker in it.text },
                "the tail half must carry the body pulled this turn: $firstBodies",
            )
            assertEquals(
                firstSystem.map { it.text }, secondSystem.map { it.text },
                "design D-7: two builds in one turn with the same library must be byte-identical",
            )
            assertEquals(
                firstBodies.map { it.text }, secondBodies.map { it.text },
                "the pulled-body half must belong to the same snapshot as the catalog half",
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
            if (request.tools.any { it.name == ProactiveHintSpec.PROPOSE_HINT.name }) {
                listener(
                    smartToolCall(
                        "p1", ProactiveHintSpec.PROPOSE_HINT.name, """{"text":"主动提示"}""",
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
    fun `three pulls in one turn keep distinct receipts and the turn completes`() {
        // Obligation 3: the tool instance — and therefore its receipt counter — must survive across the requests of one
        // run. A per-request instance restarts at `loaded:1`; three such receipts are three identical consecutive tool
        // outputs, `ProgressGuard` ends the run as `NO_PROGRESS`, and the phase's own tool kills the turn (design D-8).
        val names = listOf("alpha", "beta", "gamma")
        val rig = rig(
            SmartFakeSkills(names.associate { name -> "/sdcard/EDITH/skills/$name.md" to smartSkillDoc(name, "正文-$name") }),
        )
        // This test is about the receipt counter surviving across requests, not about eviction. 0.25 Task 26 replaced
        // a short fixture question in the continuation input with the fixed cue, which is a few bytes longer; with the
        // default 2 000-byte window that was enough to make the trimmer evict the only turn of this deliberately large
        // three-round fixture. A wide window keeps the assertions on receipt identity and removes the unrelated trim.
        rig.profile = smartProfile().copy(
            usableWindowTokens = 100_000,
            inputBudgetTokens = 93_000,
            maxOutputTokens = 6_000,
            safetyTokens = 1_000,
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
            listOf("loaded:1", "loaded:2", "loaded:3").map(::pureToolOutput), receipts,
            "one instance across requests is what keeps the receipts distinct: $receipts",
        )
        assertTrue(turnCompleted(lines), "the turn must complete, not trip NO_PROGRESS: $lines")
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
                    "p1", ProactiveHintSpec.PROPOSE_HINT.name, """{"text":"主动提示"}""",
                    requestId = request.requestId,
                ),
            )
            listener(ModelStreamEvent.Terminal(request.requestId, ModelEnd.COMPLETED, null, null))
        }
        rig.plugin.handle(AcousticEvent(rig.envelope(), AcousticLabel.Speech))
        rig.plugin.handle(TranscriptEvent(rig.transcript("这个页面怎么一直转圈", turnId = "asr-p1")))

        val request = rig.model.requests.single()
        val sent = inputText(request)
        assertTrue(
            secondMarker in sent,
            "turn 1's late end must not clear the body turn 2 pulled: $sent",
        )
        assertFalse(firstMarker in sent, "turn 2 began with no pulled bodies: $sent")
        assertFalse(secondMarker in request.instructions, "the body still travels in the request tail, not system")
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

        // Both halves of the request's SKILL view come from one snapshot, so neither a joiner nor its end can split
        // the catalog from the body it belongs with.
        val (slot, bodies) = rig.plugin.requestSkillSegmentsForTest(ContextRoute.WAKE)
        assertTrue(
            bodies.any { it.origin == SegmentOrigin.SKILL_BODY && marker in it.text },
            "the wake turn's pulled body must survive the proactive turn's end: $bodies",
        )
        assertFalse(
            slot.any { marker in it.text },
            "a pulled body is request-tail content and must never enter the system slot: $slot",
        )
        rig.plugin.endSkillTurnForTest(wake)
    }

    /**
     * Review finding (Important), concurrency half: `LibraryGovernor` documents "not thread-safe" and the SKILL state
     * is one map and one turn flag read and written from the plugin's **two-thread** production pool. This drives two
     * turns through the real boundary and reads the request's SKILL view concurrently with a second owner begin.
     *
     * 0.3 final-fix wave: the catalog half is now **session-pinned** — a request no longer rebuilds it — so the two
     * halves cannot be split by a catalog build at all. What must still hold under the interleaving is that every
     * view pairs the same pinned catalog with a bodies list that is either turn A's pull or the next turn's empty set,
     * never a body inside the system slot, and that both threads finish without a torn structure.
     */
    @Test
    fun `a concurrent owner begin cannot split a request's catalog from its pulled bodies`() {
        val storage = SmartFakeSkills(
            mapOf(
                "/sdcard/EDITH/skills/a.md" to smartSkillDoc("alpha", "正文-a"),
                "/sdcard/EDITH/skills/b.md" to smartSkillDoc("beta", "正文-b"),
            ),
        )
        val rig = SmartTestRig(conversationStores = smartStores()).also {
            it.activate(overrides = mapOf(Capability.StorageAccess to storage))
        }
        val failures = ConcurrentLinkedQueue<Throwable>()
        val views = ConcurrentLinkedQueue<Pair<List<Segment>, List<Segment>>>()
        val start = CountDownLatch(1)
        val done = CountDownLatch(2)
        val secondGeneration = AtomicLong(0L)

        // Turn A owns the turn and has pulled alpha; its requests are the continuations that must carry the body.
        val first = rig.plugin.beginSkillTurnForTest(owner = true)
        rig.plugin.loadSkillToolForTest().execute(LoadSkillTool.NAME, """{"name":"alpha"}""")

        val request = Thread {
            start.await()
            try {
                repeat(64) { views += rig.plugin.requestSkillSegmentsForTest(ContextRoute.WAKE) }
            } catch (failure: Throwable) {
                failures += failure
            } finally {
                done.countDown()
            }
        }
        val second = Thread {
            start.await()
            try {
                secondGeneration.set(rig.plugin.beginSkillTurnForTest(owner = true))
                rig.plugin.endSkillTurnForTest(secondGeneration.get())
            } catch (failure: Throwable) {
                failures += failure
            } finally {
                done.countDown()
            }
        }
        request.start()
        second.start()
        start.countDown()
        assertTrue(done.await(5, TimeUnit.SECONDS), "both threads must finish; a deadlock is a failure")
        request.join(5_000)
        second.join(5_000)

        assertFalse(request.isAlive || second.isAlive, "both must finish; a torn structure or a deadlock is a failure")
        assertTrue(failures.isEmpty(), "concurrent SKILL turn work threw: ${failures.take(3)}")
        assertTrue(views.isNotEmpty(), "the request thread must have built at least one SKILL view")

        // Every view pairs the same pinned catalog with either turn A's pull or the next turn's empty set, and a body
        // never appears in the system slot. That is the concurrency invariant the pin preserves.
        views.forEach { (system, bodies) ->
            assertTrue(
                system.any { it.origin == SegmentOrigin.SKILL && "- alpha: d-alpha" in it.text },
                "every request's catalog half must stay the pinned catalog: $system",
            )
            assertFalse(system.any { "正文-a" in it.text }, "a body leaked into the system slot: $system")
            assertTrue(
                bodies.all { it.origin == SegmentOrigin.SKILL_BODY && "正文-a" in it.text },
                "a body half may only be turn A's pull: $bodies",
            )
        }

        // The second begin is the next turn's; a turn starts with no pulled bodies and the catalog did not move.
        val afterHandover = rig.plugin.requestSkillSegmentsForTest(ContextRoute.WAKE)
        assertTrue(
            afterHandover.second.isEmpty(),
            "the next turn must start with no pulled bodies: ${afterHandover.second}",
        )
        assertTrue(
            afterHandover.first.any { it.origin == SegmentOrigin.SKILL && "- alpha: d-alpha" in it.text },
            "the pinned catalog must survive the handover: ${afterHandover.first}",
        )
        // Turn A's end is stale now: the second owner already replaced it, so this must be a no-op.
        rig.plugin.endSkillTurnForTest(first)

        // The shared state must be intact: the entries are still governed (activation pinned them) and a fresh turn
        // still opens cleanly with the same catalog.
        val governor = assertNotNull(rig.plugin.skillGovernor(), "activation must build the session's library governor")
        assertTrue(
            governor.entry("alpha") != null && governor.entry("beta") != null,
            "both advertised entries must still be governed",
        )
        val fresh = rig.plugin.beginSkillTurnForTest(owner = true)
        val freshSlot = rig.plugin.requestSkillSegmentsForTest(ContextRoute.WAKE).first
        rig.plugin.endSkillTurnForTest(fresh)
        assertTrue(
            freshSlot.any { it.origin == SegmentOrigin.SKILL && "- alpha: d-alpha" in it.text },
            "the catalog must still be intact after the overlap: $freshSlot",
        )
    }
}

