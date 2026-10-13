package edith.plugins.smart

import edith.contracts.AcousticEvent
import edith.contracts.AcousticLabel
import edith.contracts.Generation
import edith.contracts.OutputStateEvent
import edith.contracts.OutputStatus
import edith.contracts.SessionId
import edith.contracts.TranscriptEvent
import edith.contracts.UserAction
import edith.plugin.Capability
import edith.plugin.ConfigSchema
import edith.plugin.DeactivationReason
import edith.plugin.InputPart
import edith.plugin.ModelEnd
import edith.plugin.ModelStreamEvent
import edith.plugin.OutputModality
import edith.plugin.PluginConfiguration
import edith.plugin.PluginLifecycle
import edith.plugin.RiskLevel
import kotlin.test.*

/**
 * The passive loop, end to end, with no Android and no network: a wake opens a turn, a final transcript runs one
 * single-use `AgentLoop`, and the terminal state becomes a hint on the host's `OutputSink`.
 */
class SmartAssistantPluginTest {

    @Test fun `the proactive configuration keys are validated and an unknown key is still refused`() {
        val plugin = SmartAssistantPlugin()
        fun configuration(extra: Map<String, String>) = PluginConfiguration(1,
            mapOf("profileId" to "p-1", "profileRevision" to "1") + extra)

        // The two P3.2 keys, at their bounds and with every level the enum names.
        plugin.validate(configuration(mapOf("proactiveCooldownMillis" to "5000", "proactiveSensitivity" to "LOW")))
        plugin.validate(configuration(mapOf("proactiveCooldownMillis" to "600000", "proactiveSensitivity" to "MEDIUM")))
        plugin.validate(configuration(mapOf("proactiveCooldownMillis" to "0", "proactiveSensitivity" to "HIGH")))
        // Absent is fine: the plugin's own defaults are the brief's.
        plugin.validate(configuration(emptyMap()))

        // Case is normalised rather than refused, exactly like `maxLevel` next to it; the store normalises too, so
        // this is defence in depth rather than a second, stricter spelling rule.
        plugin.validate(configuration(mapOf("proactiveSensitivity" to "low")))

        assertFailsWith<IllegalArgumentException> { plugin.validate(configuration(mapOf("proactiveSensitivity" to "LOUD"))) }
        assertFailsWith<IllegalArgumentException> { plugin.validate(configuration(mapOf("proactiveCooldownMillis" to "-1"))) }
        assertFailsWith<IllegalArgumentException> { plugin.validate(configuration(mapOf("proactiveCooldownMillis" to "abc"))) }
        assertFailsWith<IllegalArgumentException> { plugin.validate(configuration(mapOf("proactiveNonsense" to "1"))) }
    }

    @Test fun `descriptor is stable and lifecycle toggles active`() {
        val rig = SmartTestRig()
        val plugin: PluginLifecycle = rig.plugin
        assertEquals("smart-assistant", plugin.descriptor.pluginId)
        assertEquals("1.0.0", plugin.descriptor.version)
        assertEquals("Smart Assistant", plugin.descriptor.displayName)
        assertEquals(ConfigSchema(1), plugin.descriptor.configSchema)
        assertEquals(
            setOf(Capability.WakeWord, Capability.Asr, Capability.Model, Capability.Actions, Capability.TextOutput,
                Capability.Context, Capability.Storage, Capability.Confirmation, Capability.StorageAccess),
            plugin.descriptor.capabilities,
        )
        // P3.2 Task 8: `Context` is no longer absent. It is what the proactive path runs on - the host delivers
        // `AcousticEvent`/`ContextChanged` only to a plugin that declares it - and the composition now provides it.
        assertTrue(Capability.Context in plugin.descriptor.capabilities)
        assertFalse(rig.plugin.active)
        rig.plugin.deactivate(DeactivationReason.Stopped)
        assertFalse(rig.plugin.active)
    }

    @Test fun `activate declares the configured wake words and builds nothing else`() {
        val rig = SmartTestRig()
        rig.activate(wakeWords = "伊迪丝, edith ,,小助手")
        assertTrue(rig.plugin.active)
        rig.plugin.deactivate(DeactivationReason.Stopped)
        assertEquals(listOf(listOf("伊迪丝", "edith", "小助手")), rig.wake.declarations)
        assertTrue(rig.model.requests.isEmpty(), "activation itself must not acquire a model request")
        assertFalse(rig.plugin.active)
    }

    @Test fun `default wake words are declared when the configuration omits them`() {
        val rig = SmartTestRig()
        rig.activate()
        assertEquals(listOf(listOf("伊迪丝", "edith")), rig.wake.declarations)
    }

    @Test fun `a wake followed by a final transcript runs exactly one loop turn`() {
        val rig = SmartTestRig()
        rig.activate()
        rig.model.onRequest = { request, listener -> streamText(request, listener, "答案。") }
        rig.wakeAndFinal("记录一下")

        assertEquals(1, rig.model.requests.size)
        val request = rig.model.requests.single()
        assertEquals("asr-1", request.turnId)
        assertEquals("smart-profile", request.profileId)
        assertEquals(3, request.profileRevision)
        assertEquals(listOf(InputPart.Text("记录一下")), request.input)
        assertEquals(SmartPrompts.instructions(), request.instructions)
        assertEquals(OutputModality.TEXT, request.output)
    }

    @Test fun `completed answer becomes one hint carrying the turn identity`() {
        val rig = SmartTestRig()
        rig.activate()
        rig.model.onRequest = { request, listener -> streamText(request, listener, "答案。") }
        rig.wakeAndFinal("记录一下")

        assertEquals(listOf("答案。"), rig.hintTexts)
        val hint = rig.output.hints.single()
        assertEquals("asr-1", hint.sourceTurnId)
        assertEquals(rig.sessionId, hint.sessionId)
        assertEquals(rig.generation, hint.generation)
        assertEquals(0, hint.priority)
        assertNull(hint.optionalSpeech)
    }

    @Test fun `a completed answer is distinguished from an honest failure`() {
        val rig = SmartTestRig()
        rig.activate()
        rig.model.onRequest = { request, listener -> streamText(request, listener, "答案。") }
        rig.wakeAndFinal("问题")
        assertEquals(listOf("答案。"), rig.hintTexts)

        val failed = SmartTestRig()
        failed.activate()
        failed.model.onRequest = { request, listener ->
            listener(ModelStreamEvent.Terminal(request.requestId, ModelEnd.FAILED, null, "PROVIDER_DOWN"))
        }
        failed.wakeAndFinal("问题")
        assertEquals(listOf("智能助手这次没能给出结果。"), failed.hintTexts)
    }

    @Test fun `each non-completed outcome submits a distinct honest hint and never silence`() {
        val expected = mapOf(
            ModelEnd.FAILED to "智能助手这次没能给出结果。",
            ModelEnd.DEADLINE to "回答超时了，稍后再试。",
            ModelEnd.CANCELLED to "已按你的要求取消。",
        )
        for ((end, sentence) in expected) {
            val rig = SmartTestRig()
            rig.activate()
            rig.model.onRequest = { request, listener ->
                listener(ModelStreamEvent.Terminal(request.requestId, end, null, null))
            }
            rig.wakeAndFinal("问题")
            assertEquals(listOf(sentence), rig.hintTexts, "outcome $end")
        }
    }

    @Test fun `partial revisions never reach the model and only the final one runs`() {
        val rig = SmartTestRig()
        rig.activate()
        rig.model.onRequest = { request, listener -> streamText(request, listener, "答案。") }
        rig.plugin.handle(rig.wakeEvent())
        rig.plugin.handle(TranscriptEvent(rig.transcript("半句", revision = 1, final = false)))
        assertTrue(rig.model.requests.isEmpty(), "a partial revision must not run the loop")
        rig.plugin.handle(TranscriptEvent(rig.transcript("半句继续", revision = 2, final = false)))
        assertTrue(rig.model.requests.isEmpty())
        rig.plugin.handle(TranscriptEvent(rig.transcript("最终问题", revision = 3, final = true)))
        rig.tick()
        assertEquals(1, rig.model.requests.size)
        assertEquals(listOf(InputPart.Text("最终问题")), rig.model.requests.single().input)
    }

    @Test fun `transcripts for another session or generation are ignored`() {
        val rig = SmartTestRig()
        rig.activate()
        rig.model.onRequest = { request, listener -> streamText(request, listener, "答案。") }
        rig.plugin.handle(rig.wakeEvent())
        rig.plugin.handle(TranscriptEvent(rig.transcript("外会话", generation = edith.contracts.Generation(9))))
        rig.plugin.handle(
            TranscriptEvent(
                edith.contracts.TranscriptRevision(
                    edith.contracts.EventEnvelope(edith.contracts.SessionId("other"), rig.generation, "e", 0, 0),
                    "t-1", 1, "别的会话", true, 0, 0,
                ),
            ),
        )
        assertTrue(rig.model.requests.isEmpty())
        rig.plugin.handle(TranscriptEvent(rig.transcript("本会话")))
        rig.tick()
        assertEquals(1, rig.model.requests.size)
    }

    @Test fun `a final transcript without a wake opens nothing`() {
        val rig = SmartTestRig()
        rig.activate()
        rig.model.onRequest = { request, listener -> streamText(request, listener, "答案。") }
        rig.plugin.handle(TranscriptEvent(rig.transcript("没有唤醒")))
        rig.tick()
        assertTrue(rig.model.requests.isEmpty())
        assertTrue(rig.hintTexts.isEmpty())
    }

    @Test fun `a second wake inside the cooldown is refused by the policy and the first turn still answers`() {
        val rig = SmartTestRig()
        rig.activate()
        rig.model.onRequest = { request, listener -> streamText(request, listener, "答案。") }
        // Drive a **completed** turn first: nothing is awaiting a transcript afterwards, so the awaiting-transcript
        // guard cannot be what refuses the second wake — only `ScenePolicy` can.
        rig.wakeAndFinal("第一句", turnId = "asr-1")
        assertEquals(1, rig.model.requests.size)

        rig.plugin.handle(rig.wakeEvent())
        rig.plugin.handle(TranscriptEvent(rig.transcript("第二句", turnId = "asr-2")))
        rig.tick()
        assertEquals(1, rig.model.requests.size, "a wake inside the cooldown must not open a second turn")

        // The same event sequence, once the window has passed, does open a turn: the policy was the refuser above.
        rig.tick(millis = 4_000)
        rig.wakeAndFinal("第三句", turnId = "asr-3")
        assertEquals(listOf("asr-1", "asr-3"), rig.model.requests.map { it.turnId })
        assertEquals(listOf("答案。", "答案。"), rig.hintTexts)
    }

    @Test fun `a cooldown-refused wake is recorded once and opens no turn`() {
        val rig = SmartTestRig()
        rig.activate()
        rig.model.onRequest = { request, listener -> streamText(request, listener, "答案。") }
        rig.wakeAndFinal("第一句", turnId = "asr-1")

        val refused = rig.wakeEvent()
        rig.plugin.handle(refused)
        rig.plugin.handle(TranscriptEvent(rig.transcript("第二句", turnId = "asr-2")))
        rig.tick()

        assertEquals(1, rig.model.requests.size, "a refused wake opens no turn")
        val refusals = rig.auditEntries.filter { it.outcome == "CooldownRefused" }
        assertEquals(1, refusals.size, "the refusal must be recorded exactly once: ${rig.auditEntries}")
        val entry = refusals.single()
        assertEquals(refused.envelope.eventId, entry.turnId, "the wake's own eventId keys the line; no turn exists yet")
        assertEquals("wake", entry.action)
        assertEquals(refused.word, entry.summary)
    }

    @Test fun `a recorded audit line reaches the namespace the host granted`() {
        val storage = SmartFakeStorage()
        val rig = SmartTestRig()
        rig.activate(overrides = mapOf(Capability.Storage to storage))
        rig.model.onRequest = { request, listener -> streamText(request, listener, "答案。") }
        rig.wakeAndFinal("第一句", turnId = "asr-1")
        // A wake the cooldown refuses still records - it is the one line that exists with no turn to key it by, which
        // makes it the cheapest honest proof that the trail in use is the one the host handed over.
        rig.plugin.handle(rig.wakeEvent())
        rig.plugin.handle(TranscriptEvent(rig.transcript("第二句", turnId = "asr-2")))
        rig.tick()

        val raw = storage.read(AuditTrail.KEY)
        assertNotNull(raw, "the namespace the host granted must receive the audit document")
        val document = String(raw, Charsets.UTF_8)
        assertTrue("CooldownRefused" in document, "the refusal line must be in the persisted document")
        assertFalse("答案" in document, "the audit document must never carry an answer or a hint text")
    }

    @Test fun `without storage the trail still records and says nothing about a file`() {
        val rig = SmartTestRig()
        rig.activate()
        rig.model.onRequest = { request, listener -> streamText(request, listener, "答案。") }
        rig.wakeAndFinal("第一句", turnId = "asr-1")
        rig.plugin.handle(rig.wakeEvent())
        rig.plugin.handle(TranscriptEvent(rig.transcript("第二句", turnId = "asr-2")))
        rig.tick()

        assertEquals(1, rig.auditEntries.count { it.outcome == "CooldownRefused" })
    }

    @Test fun `a stale final transcript never survives deactivate and activate`() {
        val rig = SmartTestRig()
        rig.activate()
        rig.model.onRequest = { request, listener -> streamText(request, listener, "答案。") }
        rig.wakeAndFinal("上一会话的问题", turnId = "asr-1")
        assertEquals(1, rig.model.requests.size)

        // The host keeps one plugin instance and re-activates it for the next session/generation.
        val nextSession = SessionId("next-session")
        val nextGeneration = Generation(7)
        rig.plugin.deactivate(DeactivationReason.Switched)
        rig.activate(sessionId = nextSession, generation = nextGeneration)
        // Past the old cooldown, so the only thing that can keep `Help` from replaying the old utterance is the cleared
        // transcript — replaying it with the new session's envelope was the bug.
        rig.tick(millis = 4_000)
        rig.plugin.handle(
            UserAction(rig.envelope(generation = nextGeneration, sessionId = nextSession), UserAction.Action.Help),
        )
        rig.tick()
        assertEquals(1, rig.model.requests.size, "Help must not replay the previous session's utterance")
        assertEquals(1, rig.hintTexts.size, "the new session's Help submitted nothing")

        // A real final transcript in the new session makes Help work again.
        rig.plugin.handle(
            TranscriptEvent(
                rig.transcript("新会话的问题", turnId = "asr-9", sessionId = nextSession, generation = nextGeneration),
            ),
        )
        rig.plugin.handle(
            UserAction(rig.envelope(generation = nextGeneration, sessionId = nextSession), UserAction.Action.Help),
        )
        rig.tick()
        assertEquals(2, rig.model.requests.size)
        assertEquals(listOf(InputPart.Text("新会话的问题")), rig.model.requests.last().input)
        assertEquals("asr-9", rig.model.requests.last().turnId)
        assertEquals(listOf("答案。", "答案。"), rig.hintTexts)
    }

    @Test fun `help is admitted inside the wake cooldown`() {
        val rig = SmartTestRig()
        rig.activate()
        rig.model.onRequest = { request, listener -> streamText(request, listener, "答案。") }
        rig.wakeAndFinal("第一句", turnId = "asr-1")
        assertEquals(1, rig.model.requests.size)

        // Control: at this instant a wake is refused by the cooldown (no turn is awaiting a transcript).
        rig.plugin.handle(rig.wakeEvent())
        rig.plugin.handle(TranscriptEvent(rig.transcript("第二句", turnId = "asr-2")))
        rig.tick()
        assertEquals(1, rig.model.requests.size, "the wake really was refused by the cooldown")

        // Help is an explicit user action and must not wait for that window.
        rig.plugin.handle(UserAction(rig.envelope(), UserAction.Action.Help))
        assertEquals(2, rig.model.requests.size, "Help must bypass the wake cooldown")
        assertEquals(listOf(InputPart.Text("第二句")), rig.model.requests.last().input)
        assertEquals("asr-2", rig.model.requests.last().turnId)
    }

    @Test fun `help replaces a turn that is still waiting for its transcript`() {
        val rig = SmartTestRig()
        rig.activate()
        rig.model.onRequest = { request, listener -> streamText(request, listener, "答案。") }
        rig.wakeAndFinal("第一句", turnId = "asr-1")
        rig.tick(millis = 4_000)
        rig.plugin.handle(rig.wakeEvent())
        assertEquals(1, rig.model.requests.size, "the wake opened a turn that still awaits its transcript")

        rig.plugin.handle(UserAction(rig.envelope(), UserAction.Action.Help))
        assertEquals(2, rig.model.requests.size, "Help must not be refused because a turn awaits its transcript")
        assertEquals(listOf(InputPart.Text("第一句")), rig.model.requests.last().input)
        assertEquals("asr-1", rig.model.requests.last().turnId)
        // The waiting turn is *replaced*, so two loops never run side by side: the turn still carrying its minted
        // placeholder id was the one torn down.
        assertTrue(rig.cancels.last().third.startsWith("smart-"), "the waiting turn was torn down: ${rig.cancels}")
    }

    @Test fun `a stop from another session or generation cannot cancel this plugin's turn`() {
        val rig = SmartTestRig()
        rig.activate()
        rig.model.onRequest = { request, listener ->
            listener(ModelStreamEvent.TextDelta(request.requestId, "答案。"))
            // A `Stop` that belongs to somebody else must not reach this plugin's turn.
            rig.plugin.handle(UserAction(rig.envelope(sessionId = SessionId("other-session")), UserAction.Action.Stop))
            rig.plugin.handle(UserAction(rig.envelope(generation = Generation(9)), UserAction.Action.Stop))
            streamText(request, listener, "完整答案。")
        }
        rig.plugin.handle(rig.wakeEvent())
        rig.plugin.handle(TranscriptEvent(rig.transcript("问题")))
        rig.tick(millis = 1_000)

        assertTrue(rig.cancels.isEmpty(), "a foreign Stop must not tear this turn down: ${rig.cancels}")
        assertEquals(1, rig.hintTexts.size)
        assertTrue(rig.hintTexts.single().contains("完整答案。"), "the run must finish normally, was ${rig.hintTexts}")
    }

    @Test fun `a wake after the cooldown opens a second turn`() {
        val rig = SmartTestRig()
        rig.activate()
        rig.model.onRequest = { request, listener -> streamText(request, listener, "答案。") }
        rig.wakeAndFinal("第一句", turnId = "asr-1")
        rig.tick(millis = 4_000)
        rig.plugin.handle(rig.wakeEvent())
        rig.plugin.handle(TranscriptEvent(rig.transcript("第二句", turnId = "asr-2", revision = 1)))
        rig.tick()
        assertEquals(listOf("asr-1", "asr-2"), rig.model.requests.map { it.turnId })
        assertEquals(listOf("答案。", "答案。"), rig.hintTexts)
    }

    @Test fun `help runs the most recent final transcript without a wake`() {
        val rig = SmartTestRig()
        rig.activate()
        rig.model.onRequest = { request, listener -> streamText(request, listener, "答案。") }
        rig.plugin.handle(TranscriptEvent(rig.transcript("最后问题")))
        assertTrue(rig.model.requests.isEmpty(), "a transcript alone does not open a turn")

        rig.plugin.handle(UserAction(rig.envelope(), UserAction.Action.Help))
        assertEquals(1, rig.model.requests.size)
        assertEquals(listOf(InputPart.Text("最后问题")), rig.model.requests.single().input)
        assertEquals("asr-1", rig.model.requests.single().turnId)
        rig.tick()
        assertEquals(listOf("答案。"), rig.hintTexts)
    }

    @Test fun `help with nothing to reuse does nothing`() {
        val rig = SmartTestRig()
        rig.activate()
        rig.plugin.handle(UserAction(rig.envelope(), UserAction.Action.Help))
        rig.tick()
        assertTrue(rig.model.requests.isEmpty())
        assertTrue(rig.hintTexts.isEmpty())
    }

    @Test fun `pause and resume are inert but never crash`() {
        val rig = SmartTestRig()
        rig.activate()
        rig.model.onRequest = { request, listener -> streamText(request, listener, "答案。") }
        rig.plugin.handle(UserAction(rig.envelope(), UserAction.Action.Pause))
        rig.plugin.handle(UserAction(rig.envelope(), UserAction.Action.Resume))
        assertTrue(rig.plugin.active, "the host owns pause/resume; the plugin only stays safe")
        rig.wakeAndFinal("问题")
        assertEquals(listOf("答案。"), rig.hintTexts)
    }

    @Test fun `the loop is built with the tightened whitelist and the app actions`() {
        val rig = SmartTestRig()
        rig.actions.descriptors += smartAction("compose_note", risk = RiskLevel.L1, scope = "note")
        rig.activate(maxLevel = "L1", whitelist = "compose_note|note")
        rig.model.onRequest = { request, listener -> streamText(request, listener, "答案。") }
        rig.wakeAndFinal("记一条笔记")

        val tools = rig.model.requests.single().tools
        assertEquals(1, tools.size)
        assertEquals("compose_note", tools.single().name)
        assertEquals("Action compose_note", tools.single().description)
    }

    @Test fun `a model-chosen L1 tool runs and feeds its result back into the same run`() {
        val rig = SmartTestRig()
        rig.actions.descriptors += smartAction("compose_note", risk = RiskLevel.L1, scope = "note")
        rig.actions.outcome = { _, _ -> edith.plugin.ActionResult(edith.plugin.ActionOutcome.Done, "已记下") }
        rig.activate(maxLevel = "L1", whitelist = "compose_note|note")
        var turn = 0
        rig.model.onRequest = { request, listener ->
            turn += 1
            if (turn == 1) {
                listener(ModelStreamEvent.ToolCallTerminal(request.requestId, "fc-1", "call-1", "compose_note", "{}"))
                listener(ModelStreamEvent.Terminal(request.requestId, ModelEnd.COMPLETED, null, null))
            } else {
                streamText(request, listener, "已经记好了。")
            }
        }
        rig.wakeAndFinal("帮我记一条笔记")
        rig.tick()

        assertEquals(listOf("compose_note" to "{}"), rig.actions.executed)
        assertEquals(2, rig.model.requests.size, "the run must continue after the tool step")
        assertEquals(listOf("已经记好了。"), rig.hintTexts)
        val second = rig.model.requests[1]
        assertTrue(
            second.history.any { it is edith.plugin.ModelHistoryItem.ToolOutput && it.output == "已记下" },
            "the tool output must be echoed back into the transcript, was ${second.history}",
        )
    }

    @Test fun `displayed ack is recorded only for the current hint before its expiry`() {
        val rig = SmartTestRig()
        rig.activate()
        rig.model.onRequest = { request, listener -> streamText(request, listener, "答案。") }
        rig.wakeAndFinal("问题")
        val hintId = rig.output.hints.single().hintId

        for (status in listOf(OutputStatus.Accepted, OutputStatus.Queued, OutputStatus.Expired, OutputStatus.Rejected)) {
            rig.plugin.handle(OutputStateEvent(rig.envelope(), hintId, status))
        }
        assertEquals(0, rig.displayedAcks)
        rig.plugin.handle(OutputStateEvent(rig.envelope(edith.contracts.Generation(9)), hintId, OutputStatus.Displayed))
        rig.plugin.handle(OutputStateEvent(rig.envelope(), "somebody-elses-hint", OutputStatus.Displayed))
        assertEquals(0, rig.displayedAcks, "only a Displayed for this hint in this generation counts")
        rig.plugin.handle(OutputStateEvent(rig.envelope(), hintId, OutputStatus.Displayed))
        assertEquals(1, rig.displayedAcks)
        rig.plugin.handle(OutputStateEvent(rig.envelope(), hintId, OutputStatus.Displayed))
        assertEquals(1, rig.displayedAcks, "a second ACK for the same hint is not a second display")

        // Past the hint's own expiry the ACK is late and must not be recorded.
        rig.clock.advance(6_000_000_000)
        val before = rig.displayedAcks
        rig.plugin.handle(OutputStateEvent(rig.envelope(), hintId, OutputStatus.Displayed))
        assertEquals(before, rig.displayedAcks)

        // And a new turn's ACK must never be attributed to the previous turn.
        rig.tick(millis = 4_000)
        rig.plugin.handle(rig.wakeEvent())
        rig.plugin.handle(TranscriptEvent(rig.transcript("第二个问题", turnId = "asr-2")))
        rig.tick()
        assertEquals(1, rig.displayedAcks)
        assertEquals(2, rig.output.hints.size)
    }

    @Test fun `a displayed ack delivered inside submit is retained only after the submission is accepted`() {
        val rig = SmartTestRig()
        rig.activate()
        rig.model.onRequest = { request, listener -> streamText(request, listener, "答案。") }
        rig.output.onSubmit = { hint ->
            rig.output.onSubmit = null
            rig.plugin.handle(OutputStateEvent(rig.envelope(), hint.hintId, OutputStatus.Displayed))
        }
        rig.wakeAndFinal("问题")
        assertEquals(1, rig.output.hints.size)
        assertEquals(1, rig.displayedAcks)

        val rejected = SmartTestRig()
        rejected.activate()
        rejected.model.onRequest = { request, listener -> streamText(request, listener, "答案。") }
        rejected.output.submission = edith.plugin.OutputSubmission.Rejected
        rejected.output.onSubmit = { hint ->
            rejected.output.onSubmit = null
            rejected.plugin.handle(OutputStateEvent(rejected.envelope(), hint.hintId, OutputStatus.Displayed))
        }
        rejected.wakeAndFinal("问题")
        assertEquals(1, rejected.output.hints.size)
        assertEquals(0, rejected.displayedAcks, "a rejected submission must not count as displayed")
    }

    @Test fun `stop cancels the running loop the pending hint and submits nothing`() {
        val rig = SmartTestRig()
        rig.activate()
        // The fake client calls back into the plugin while the loop is in flight — this is the only way `Stop` can
        // reach a running turn, because `handle(TranscriptEvent)` is blocked inside the gateway until then.
        rig.model.onRequest = { request, listener ->
            listener(ModelStreamEvent.TextDelta(request.requestId, "迟到的答案。"))
            rig.plugin.handle(UserAction(rig.envelope(), UserAction.Action.Stop))
            streamText(request, listener, "更迟的答案。")
        }
        rig.plugin.handle(rig.wakeEvent())
        rig.plugin.handle(TranscriptEvent(rig.transcript("问题")))
        rig.tick(millis = 1_000)

        assertTrue(rig.hintTexts.isEmpty(), "Stop must not let a hint out")
        assertEquals(1, rig.cancels.size, "Stop must clear the pending output turn")
        val cancel = rig.cancels.single()
        assertEquals(rig.sessionId, cancel.first)
        assertEquals(rig.generation, cancel.second)
        assertEquals("asr-1", cancel.third)
    }

    @Test fun `a wake after stop starts a fresh loop`() {
        val rig = SmartTestRig()
        rig.activate()
        var calls = 0
        rig.model.onRequest = { request, listener ->
            calls += 1
            if (calls == 1) {
                listener(ModelStreamEvent.TextDelta(request.requestId, "旧答案。"))
                rig.plugin.handle(UserAction(rig.envelope(), UserAction.Action.Stop))
                listener(ModelStreamEvent.Terminal(request.requestId, ModelEnd.COMPLETED, null, null))
            } else {
                streamText(request, listener, "新答案。")
            }
        }
        rig.plugin.handle(rig.wakeEvent())
        rig.plugin.handle(TranscriptEvent(rig.transcript("问题")))
        assertTrue(rig.hintTexts.isEmpty())
        rig.tick(millis = 4_000)

        rig.wakeAndFinal("新问题", turnId = "asr-2")
        assertEquals(listOf("新答案。"), rig.hintTexts)
    }

    @Test fun `deactivate cancels the in-flight loop the pending output and a second deactivate is safe`() {
        val rig = SmartTestRig()
        rig.activate()
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        // The client blocks: the loop is genuinely in flight when `deactivate` runs on the test's own thread.
        rig.model.onRequest = { request, listener ->
            entered.countDown()
            release.await(2, java.util.concurrent.TimeUnit.SECONDS)
            streamText(request, listener, "迟到的答案。")
        }
        rig.plugin.handle(rig.wakeEvent())
        val worker = Thread { rig.plugin.handle(TranscriptEvent(rig.transcript("问题"))) }
        worker.isDaemon = true
        worker.start()
        assertTrue(entered.await(2, java.util.concurrent.TimeUnit.SECONDS), "the run must reach the model client")

        rig.plugin.deactivate(DeactivationReason.Stopped)
        rig.plugin.deactivate(DeactivationReason.Stopped)
        release.countDown()
        worker.join(5_000)

        assertTrue(rig.hintTexts.isEmpty())
        assertEquals(1, rig.cancels.size)
        assertEquals("asr-1", rig.cancels.single().third)
        assertTrue(rig.model.handles.any { it.cancelled }, "the transport must be cancelled")
        assertFalse(rig.plugin.active)
    }

    @Test fun `a configuration change cancels the running turn`() {
        val rig = SmartTestRig()
        rig.activate()
        rig.model.onRequest = { request, listener ->
            rig.plugin.configure(PluginConfiguration(1, mapOf("profileId" to "other", "profileRevision" to "1")))
            streamText(request, listener, "早已作废的答案。")
        }
        rig.plugin.handle(rig.wakeEvent())
        rig.plugin.handle(TranscriptEvent(rig.transcript("问题")))
        rig.tick(millis = 1_000)

        assertTrue(rig.cancels.isNotEmpty())
        assertTrue(rig.hintTexts.isEmpty(), "a reconfigured-away turn must not submit")
    }

    // NOTE ON BOUNDING: this suite runs on kotlin.test / **JUnit 5**, so there is no in-annotation timeout available
    // (JUnit 5's `@Timeout` is not on the classpath and its config-file default is off). A test that blocks therefore
    // hangs the whole module's test task, which is what the first version of this one did.
    //
    // What it blocked on, from a thread dump: `deliverProactive -> ModelClientGateway.turn -> waitForStep`. The
    // proactive turn leaves the host dispatcher, and this rig's **default model never answers** - every other proactive
    // test sets `rig.model.onRequest` first. So the fix was to give the rig a model, not to bound the wait.
    @Test
    fun `an admitted trigger says which features let it through and never the text`() {
        // P3.5 §W3 / plan Task 11: on a device the only explainability for "why did this fire" has to be the log, and
        // the log may not carry the transcript. Feature **names** and a score are the explainable part.
        val rig = SmartTestRig()
        rig.activate()
        // Armed, or the trigger is refused before the admission rule runs (`DISARMED`) and there is no admit line to
        // assert on - which is what the first version of this test actually measured.
        rig.plugin.handle(AcousticEvent(rig.envelope(), AcousticLabel.Speech))
        // And a model, or the proactive turn parks in `ModelClientGateway.waitForStep` forever (the rig's default never
        // answers). This is the assertion's *setup*, not its subject.
        rig.model.onRequest = { request, listener ->
            listener(ModelStreamEvent.Terminal(request.requestId, ModelEnd.COMPLETED, null, null))
        }

        val lines = java.util.Collections.synchronizedList(mutableListOf<String>())
        val previous = P3Log.sink
        P3Log.sink = { lines += it }
        try {
            rig.plugin.handle(TranscriptEvent(rig.transcript("现在几点了", turnId = "asr-9", final = true)))
        } finally {
            P3Log.sink = previous
        }

        val admit = lines.firstOrNull { it.startsWith("P3_PROACTIVE admit") }
        assertTrue(admit != null, "an admitted trigger must report its features; logged: $lines")
        assertTrue(admit!!.contains("score="), "the score must be reported: $admit")
        assertTrue(admit.contains("features="), "and the features: $admit")
        assertTrue(admit.contains("CONTENT_NEED"), "a real question fires the content need: $admit")
        assertFalse(admit.contains("现在几点了"), "the transcript must never appear: $admit")
    }

    @Test fun `the transcript trace carries counts and never the text`() {
        // W4 (P3.5 §M). Without this line the "why did this row reach no decision" question is unanswerable: P3.4's
        // logs could not tell "the recogniser never produced a final" from "a final arrived and was dropped", which is
        // exactly what the offline diagnosis reported as UNKNOWN for all 18 rows it found.
        //
        // The line deliberately carries **counts and flags only** - never the transcript - so it can be logged and
        // quoted in evidence without touching the no-正文 boundary.
        val rig = SmartTestRig()
        rig.activate()

        val lines = java.util.Collections.synchronizedList(mutableListOf<String>())
        val previous = P3Log.sink
        P3Log.sink = { lines += it }
        try {
            rig.plugin.handle(TranscriptEvent(rig.transcript("现在几点了", turnId = "asr-9", final = true)))
        } finally {
            P3Log.sink = previous
        }

        val trace = lines.single { it.startsWith("P3_ASR") }
        assertTrue(trace.contains("final=true"), "the line must report finality: $trace")
        assertTrue(trace.contains("chars=5"), "and the length: $trace")
        assertTrue(trace.contains("usable=true"), "and whether the utterance was usable: $trace")
        assertFalse(trace.contains("现在几点了"), "the transcript itself must never appear: $trace")
    }

    @Test fun `requestConsent stays the host's gate, not a second one inside the plugin`() {
        // The brief is explicit: `requestConsent` is "true only when explicitly configured — the host's text-upload
        // consent remains the gate for anything leaving the device". `plugins-smart` therefore parses and keeps the
        // flag, but does not turn it into a second gate: the host (Task 14) is what lets or refuses an upload, and the
        // plugin must not silently refuse a turn the host already blessed.
        val rig = SmartTestRig()
        rig.activate()
        assertFalse(rig.consentFlag, "the flag defaults to false unless explicitly configured")
        rig.model.onRequest = { request, listener -> streamText(request, listener, "答案。") }
        rig.wakeAndFinal("问题")
        assertEquals(1, rig.model.requests.size)
        assertEquals(listOf("答案。"), rig.hintTexts)

        val consented = SmartTestRig()
        consented.activate(requestConsent = "true")
        assertTrue(consented.consentFlag)
    }

    // ------------------------------------------------- D12: the wake segment's own final is not the utterance

    @Test fun `D12 the wake segment's blank final is ignored and the request in a later segment runs the turn`() {
        val rig = SmartTestRig()
        rig.activate()
        rig.model.onRequest = { request, listener -> streamText(request, listener, "答案。") }

        rig.plugin.handle(rig.wakeEvent())
        // The measured device sequence: the ASR finalises the segment that carried the wake word **blank**, and the
        // request itself is transcribed in a later segment that arrives seconds afterwards.
        rig.plugin.handle(TranscriptEvent(rig.transcript("", turnId = "asr-wake", revision = 0)))
        rig.tick(millis = 5_000)
        assertTrue(rig.model.requests.isEmpty(), "the wake's own blank final must not run a turn")
        assertTrue(rig.hintTexts.isEmpty(), "and it must not submit the blank-utterance failure")

        rig.plugin.handle(
            TranscriptEvent(rig.transcript("明天上午十点给张老师回电话", turnId = "asr-request", revision = 11)),
        )
        rig.tick()

        val request = rig.model.requests.single()
        assertEquals("asr-request", request.turnId, "the run adopts the request's own ASR turn id")
        assertEquals(listOf(InputPart.Text("明天上午十点给张老师回电话")), request.input)
        assertEquals(listOf("答案。"), rig.hintTexts)
    }

    @Test fun `D12 a wake followed only by blanks ends insufficient with no utterance after the window`() {
        val rig = SmartTestRig()
        rig.activate()

        rig.plugin.handle(rig.wakeEvent())
        rig.plugin.handle(TranscriptEvent(rig.transcript("", turnId = "asr-wake-1", revision = 0)))
        // A second blank segment extends the window instead of ending the turn...
        rig.tick(millis = 9_000)
        rig.plugin.handle(TranscriptEvent(rig.transcript("   ", turnId = "asr-wake-2", revision = 0)))
        assertTrue(rig.hintTexts.isEmpty(), "a blank final must not end the listening turn")

        // ...and the window really does expire, after the configured profile's own timeout.
        rig.tick(millis = 10_000)
        rig.tick()
        assertTrue(rig.model.requests.isEmpty(), "nothing usable was said, so no model request may be made")
        assertEquals(listOf("我没听清这句请求，再说一次试试。"), rig.hintTexts)
    }

    @Test fun `D12 a wake-word-only final is ignored and the request that follows still runs`() {
        val rig = SmartTestRig()
        rig.activate()
        rig.model.onRequest = { request, listener -> streamText(request, listener, "答案。") }

        rig.plugin.handle(rig.wakeEvent())
        rig.plugin.handle(TranscriptEvent(rig.transcript("伊迪丝。", turnId = "asr-wake-1", revision = 1)))
        rig.plugin.handle(TranscriptEvent(rig.transcript("EDITH", turnId = "asr-wake-2", revision = 2)))
        rig.tick(millis = 2_000)
        assertTrue(
            rig.model.requests.isEmpty(),
            "a final that is only the wake word (with or without punctuation) is the wake segment, not the request",
        )
        assertTrue(rig.hintTexts.isEmpty())

        rig.plugin.handle(TranscriptEvent(rig.transcript("帮我记一条笔记", turnId = "asr-request")))
        rig.tick()
        assertEquals(listOf(InputPart.Text("帮我记一条笔记")), rig.model.requests.single().input)
        assertEquals("asr-request", rig.model.requests.single().turnId)
    }

    @Test fun `D12 a stop during the listening window cancels it without submitting`() {
        val rig = SmartTestRig()
        rig.activate()

        rig.plugin.handle(rig.wakeEvent())
        assertEquals(1, rig.scheduler.pending(), "the wake must arm exactly one bounded listening window")
        rig.plugin.handle(TranscriptEvent(rig.transcript("", turnId = "asr-wake", revision = 0)))
        assertEquals(
            1,
            rig.scheduler.pending(),
            "extending the window replaces it rather than accumulating a second deadline",
        )
        rig.plugin.handle(UserAction(rig.envelope(), UserAction.Action.Stop))
        assertEquals(1, rig.cancels.size, "Stop must clear the pending output turn")
        assertEquals(0, rig.scheduler.pending(), "Stop must release the armed listening window")

        // Far past the window: the cancelled listening deadline must not fire and nothing may be submitted.
        rig.tick(millis = 60_000)
        assertTrue(rig.model.requests.isEmpty(), "a stopped turn must never submit")
        assertTrue(rig.hintTexts.isEmpty(), "a stopped turn must never hint")
    }

    @Test fun `D12 the listening window falls back to the documented default when no profile exists`() {
        val rig = SmartTestRig()
        rig.profile = null
        rig.activate()

        rig.plugin.handle(rig.wakeEvent())
        rig.plugin.handle(TranscriptEvent(rig.transcript("", turnId = "asr-wake", revision = 0)))

        // 14 s in, the turn is still listening: the default window is 15 s, not a zero-length one.
        rig.tick(millis = 14_000)
        assertTrue(rig.hintTexts.isEmpty(), "a missing profile must not collapse the listening window")
        // Just past it, the turn ends honestly rather than waiting forever with no utterance at all.
        rig.tick(millis = 1_100)
        rig.tick()
        assertEquals(listOf("我没听清这句请求，再说一次试试。"), rig.hintTexts)
        assertTrue(rig.model.requests.isEmpty())
    }

    @Test fun `strict configuration rejects bad levels revisions consent and unknown keys`() {
        val rig = SmartTestRig()
        val valid = PluginConfiguration(1, mapOf("profileId" to "smart-profile", "profileRevision" to "3"))
        rig.plugin.validate(valid)
        for (bad in listOf(
            valid.copy(schemaVersion = 2),
            valid.copy(values = valid.values - "profileId"),
            valid.copy(values = valid.values + ("profileRevision" to "-1")),
            valid.copy(values = valid.values + ("profileRevision" to "abc")),
            valid.copy(values = valid.values + ("maxLevel" to "L9")),
            valid.copy(values = valid.values + ("whitelist" to "no-scope")),
            valid.copy(values = valid.values + ("requestConsent" to "yes")),
            valid.copy(values = valid.values + ("secret" to "sentinel")),
            valid.copy(values = valid.values + ("wakeWords" to " , ")),
        )) {
            assertFailsWith<IllegalArgumentException>("expected rejection for $bad") { rig.plugin.validate(bad) }
        }
    }
}
