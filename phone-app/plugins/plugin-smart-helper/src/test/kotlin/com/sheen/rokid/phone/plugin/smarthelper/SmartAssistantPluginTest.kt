package com.sheen.rokid.phone.plugin.smarthelper
import com.sheen.rokid.phone.plugin.smarthelper.audit.AuditTrail
import com.sheen.rokid.phone.plugin.smarthelper.scene.ScenePolicy
import com.sheen.rokid.phone.plugin.smarthelper.memory.MemoryExtraction
import com.sheen.rokid.phone.plugin.smarthelper.memory.MemoryLog
import com.sheen.rokid.phone.plugin.smarthelper.memory.score
import com.sheen.rokid.phone.plugin.smarthelper.prompt.PromptCatalog
import com.sheen.rokid.phone.plugin.smarthelper.prompt.PromptRoute
import com.sheen.rokid.phone.plugin.smarthelper.audit.P3Log

import com.sheen.rokid.contracts.AcousticEvent
import com.sheen.rokid.contracts.AcousticLabel
import com.sheen.rokid.contracts.AssistantRoute
import com.sheen.rokid.contracts.Generation
import com.sheen.rokid.contracts.OutputStateEvent
import com.sheen.rokid.contracts.OutputStatus
import com.sheen.rokid.contracts.PinChanged
import com.sheen.rokid.contracts.SessionId
import com.sheen.rokid.contracts.SpeechFinished
import com.sheen.rokid.contracts.SpeechResult
import com.sheen.rokid.contracts.ToolPhase
import com.sheen.rokid.contracts.TranscriptEvent
import com.sheen.rokid.contracts.UserAction
import com.sheen.rokid.output.OfficialConversation
import com.sheen.rokid.output.OfficialStatus
import com.sheen.rokid.plugin.ActionOutcome
import com.sheen.rokid.plugin.ActionResult
import com.sheen.rokid.plugin.Capability
import com.sheen.rokid.plugin.ConfigSchema
import com.sheen.rokid.plugin.DeactivationReason
import com.sheen.rokid.plugin.InputModality
import com.sheen.rokid.plugin.InputPart
import com.sheen.rokid.plugin.ModelEnd
import com.sheen.rokid.plugin.ModelStreamEvent
import com.sheen.rokid.plugin.CancellationHandle
import com.sheen.rokid.plugin.OutputModality
import com.sheen.rokid.plugin.OutputSubmission
import com.sheen.rokid.plugin.PerceptionFilter
import com.sheen.rokid.plugin.PerceptionProvider
import com.sheen.rokid.plugin.PerceptionScreenshotResult
import com.sheen.rokid.plugin.PerceptionSnapshot
import com.sheen.rokid.plugin.PerceptionSource
import com.sheen.rokid.plugin.TaskScope
import com.sheen.rokid.plugin.PluginConfiguration
import com.sheen.rokid.plugin.PluginLifecycle
import com.sheen.rokid.plugin.RiskLevel
import com.sheen.rokid.output.HintReadingBudget
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
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

    /**
     * 0.3 Phase 4B (`D189`, spec §5.3): the persisted mode is now part of the closed configuration shape.
     *
     * The key's value is accepted at its persisted spelling and defaults to **false** when absent; the live gate is the
     * constructor provider, not this turn snapshot (spec §5.1), so this test pins only the shape and the safe default.
     */
    @Test fun `the no-interception configuration key is accepted and defaults to false`() {
        val plugin = SmartAssistantPlugin()
        fun configuration(extra: Map<String, String>) = PluginConfiguration(1,
            mapOf("profileId" to "p-1", "profileRevision" to "1") + extra)

        plugin.validate(configuration(mapOf("noInterception" to "true")))
        plugin.validate(configuration(mapOf("noInterception" to "false")))
        plugin.validate(configuration(emptyMap()))

        assertFalse(plugin.noInterceptionConfigured, "an absent key takes the safe default (off)")
        plugin.configure(configuration(mapOf("noInterception" to "true")))
        assertTrue(plugin.noInterceptionConfigured)
        plugin.configure(configuration(mapOf("noInterception" to "false")))
        assertFalse(plugin.noInterceptionConfigured)

        assertFailsWith<IllegalArgumentException> {
            plugin.validate(configuration(mapOf("noInterception" to "yes")))
        }
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
                Capability.Context, Capability.Storage, Capability.Confirmation, Capability.StorageAccess,
                Capability.SystemOne, Capability.Embedding,
                // edith-0.2 Phase C: the conversation port. Added after a device report - the phone's 助手 page was
                // empty and no `SAID`/`BUSY` ever reached the glasses, because the plugin consumed the port without
                // declaring it and the optional access degraded to "no port" in silence. This list is the gate that
                // now makes that omission fail here instead of on the wearer's face.
                Capability.ConversationDisplay,
                // 0.3 Phase 2 Task 9: the host's per-turn perception port. The block is optional (a host without the
                // provider still activates), but declaring the capability is what makes `services.perception()`
                // reachable at all.
                Capability.Perception,
                // 0.3 Phase 1 Task 12: the static device profile that the session pins into its prompt plan. The host
                // only hands over a capability the descriptor declares, so declaring it is what makes the prompt's
                // device section reachable at all.
                Capability.DeviceState),
            plugin.descriptor.capabilities,
        )
        // P3.2 Task 8: `Context` is no longer absent. It is what the proactive path runs on - the host delivers
        // `AcousticEvent`/`ContextChanged` only to a plugin that declares it - and the composition now provides it.
        assertTrue(Capability.Context in plugin.descriptor.capabilities)
        assertFalse(rig.plugin.active)
        rig.plugin.deactivate(DeactivationReason.Stopped)
        assertFalse(rig.plugin.active)
    }

    @Test fun `a perception provider opens and closes the turn and its block rides before the utterance`() {
        val perception = SmartFakePerception()
        val rig = SmartTestRig(conversationStores = smartStores())
        val lines = mutableListOf<String>()
        val previous = P3Log.sink
        P3Log.sink = { lines += it }
        try {
            rig.activate(overrides = mapOf(Capability.Perception to perception))
            rig.model.onRequest = { request, listener -> streamText(request, listener, "好。") }

            rig.wakeAndFinal("现在几点了")

            assertEquals(1, perception.beginTurns, "the turn must open the provider exactly once")
            assertEquals(1, perception.endTurns, "the turn must close the provider on its success path")
            val input = rig.model.requests.single().input.filterIsInstance<InputPart.Text>().single().text
            assertTrue(input.startsWith("【本轮运行上下文】"), "the perception block is the request tail head: $input")
            assertTrue(input.contains("#n12 button \"搜索\""), input)
            assertTrue(input.endsWith("现在几点了"), "the wearer's utterance stays last: $input")
            val telemetry = lines.filter { it.startsWith("P3_PERCEPTION ") }
            assertEquals(1, telemetry.size, "one count-only line per rendered block: $lines")
            assertTrue("elements=1" in telemetry.single(), telemetry.single())
            assertTrue("images=0" in telemetry.single(), telemetry.single())
            assertFalse("搜索" in telemetry.single(), "the telemetry must not carry screen text: ${telemetry.single()}")
        } finally {
            P3Log.sink = previous
        }
    }

    @Test fun `a text-only profile says vision is unavailable instead of reporting the host gate as ok`() {
        // Phase 2 Task 10: the host's snapshot token says the capture path is on (`vision=ok`), but the selected
        // profile declares no IMAGE input. The rendered coverage must name that gap, and the telemetry must agree.
        val perception = SmartFakePerception()
        val rig = SmartTestRig(conversationStores = smartStores())
        val lines = mutableListOf<String>()
        val previous = P3Log.sink
        P3Log.sink = { lines += it }
        try {
            rig.activate(overrides = mapOf(Capability.Perception to perception))
            rig.model.onRequest = { request, listener -> streamText(request, listener, "好。") }

            rig.wakeAndFinal("现在几点了")

            val input = rig.model.requests.single().input.filterIsInstance<InputPart.Text>().single().text
            assertTrue("vision=unavailable(no_image_profile)" in input, input)
            val telemetry = lines.single { it.startsWith("P3_PERCEPTION ") }
            assertTrue("vision=unavailable(no_image_profile)" in telemetry, telemetry)
        } finally {
            P3Log.sink = previous
        }
    }

    @Test fun `an image-capable profile keeps vision ok`() {
        val perception = SmartFakePerception()
        val rig = SmartTestRig(conversationStores = smartStores())
        rig.profile = smartProfile().copy(inputs = setOf(InputModality.TEXT, InputModality.IMAGE))
        val lines = mutableListOf<String>()
        val previous = P3Log.sink
        P3Log.sink = { lines += it }
        try {
            rig.activate(overrides = mapOf(Capability.Perception to perception))
            rig.model.onRequest = { request, listener -> streamText(request, listener, "好。") }

            rig.wakeAndFinal("现在几点了")

            val input = rig.model.requests.single().input.filterIsInstance<InputPart.Text>().single().text
            assertTrue("vision=ok" in input, input)
            val telemetry = lines.single { it.startsWith("P3_PERCEPTION ") }
            assertTrue("vision=ok" in telemetry, telemetry)
        } finally {
            P3Log.sink = previous
        }
    }

    @Test fun `an early-refused turn still closes the perception turn`() {
        val perception = SmartFakePerception()
        val rig = SmartTestRig(conversationStores = smartStores())
        val lines = mutableListOf<String>()
        val previous = P3Log.sink
        P3Log.sink = { lines += it }
        try {
            rig.activate(
                overrides = mapOf(
                    Capability.Perception to perception,
                    // A hard dependency that is missing refuses the turn **after** the turn has opened, which is the
                    // early-return path the `finally` exists for.
                    Capability.SystemOne to SmartFakeSystemOne(available = false),
                ),
            )
            rig.model.onRequest = { request, listener -> streamText(request, listener, "好。") }

            rig.wakeAndFinal("现在几点了")

            assertTrue(lines.any { it.startsWith("P3_DEPENDENCY reason=") }, "the dependency refuses the turn: $lines")
            assertEquals(1, perception.beginTurns)
            assertEquals(1, perception.endTurns, "an early return must still close the perception turn")
            assertTrue(rig.model.requests.isEmpty(), "a refused turn never reaches the transport")
        } finally {
            P3Log.sink = previous
        }
    }

    @Test fun `a host without a perception provider still activates and runs a turn`() {
        val rig = SmartTestRig(conversationStores = smartStores())
        val lines = mutableListOf<String>()
        val previous = P3Log.sink
        P3Log.sink = { lines += it }
        try {
            // The descriptor declares Perception, but this host grants no provider: absence must be "no block".
            rig.activate()
            assertTrue(rig.plugin.active)
            rig.model.onRequest = { request, listener -> streamText(request, listener, "好。") }

            rig.wakeAndFinal("现在几点了")

            assertEquals(1, rig.model.requests.size)
            val input = rig.model.requests.single().input.filterIsInstance<InputPart.Text>().single().text
            assertFalse("【本轮运行上下文】" in input, "a missing provider injects nothing: $input")
            assertTrue(input.endsWith("现在几点了"), input)
            assertTrue(lines.none { it.startsWith("P3_PERCEPTION ") }, "no provider, no telemetry: $lines")
        } finally {
            P3Log.sink = previous
        }
    }

    @Test fun `a beginTurn that throws falls back to an explicit full snapshot`() {
        val perception = SmartFakePerception(initialFailure = IllegalStateException("synthetic-begin-failure"))
        val rig = SmartTestRig(conversationStores = smartStores())
        val lines = mutableListOf<String>()
        val previous = P3Log.sink
        P3Log.sink = { lines += it }
        try {
            rig.activate(overrides = mapOf(Capability.Perception to perception))
            rig.model.onRequest = { request, listener -> streamText(request, listener, "好。") }

            rig.wakeAndFinal("现在几点了")

            assertEquals(1, perception.beginTurns, "the failed opening is still one beginTurn attempt")
            assertEquals(
                listOf(PerceptionSource.entries.toSet()),
                perception.requestedSources,
                "a failed beginTurn must be recovered by one explicit full snapshot read",
            )
            val input = rig.model.requests.single().input.filterIsInstance<InputPart.Text>().single().text
            assertTrue(input.startsWith("【本轮运行上下文】"), "the fallback snapshot still renders the block: $input")
            assertTrue("#n12 button \"搜索\"" in input, input)
            assertEquals(1, lines.count { it.startsWith("P3_PERCEPTION ") }, "one line for the fallback block: $lines")
            assertEquals(1, perception.endTurns, "the failed opening is still closed by the finally")
        } finally {
            P3Log.sink = previous
        }
    }

    @Test fun `an ambient snapshot that throws injects no block and no telemetry`() {
        val perception = SmartFakePerception(ambientFailure = IllegalStateException("synthetic-ambient-failure"))
        val rig = SmartTestRig(conversationStores = smartStores())
        val lines = mutableListOf<String>()
        val previous = P3Log.sink
        P3Log.sink = { lines += it }
        try {
            rig.activate(overrides = mapOf(Capability.Perception to perception))
            var turn = 0
            rig.model.onRequest = { request, listener ->
                turn += 1
                if (turn == 1) {
                    listener(ModelStreamEvent.Terminal(request.requestId, ModelEnd.INCOMPLETE, null, null))
                } else {
                    streamText(request, listener, "好。")
                }
            }

            rig.wakeAndFinal("现在几点了")

            assertEquals(2, rig.model.requests.size, "the empty turn is retried")
            assertEquals(
                listOf(setOf(PerceptionSource.DEVICE, PerceptionSource.MEDIA)),
                perception.requestedSources,
                "only the retry asks the provider for the ambient snapshot",
            )
            val second = rig.model.requests[1].input.filterIsInstance<InputPart.Text>().single().text
            assertFalse("【本轮运行上下文】" in second, "a throwing ambient read contributes no block: $second")
            assertEquals(
                1,
                lines.count { it.startsWith("P3_PERCEPTION ") },
                "only the first request's block is rendered and logged: $lines",
            )
            assertEquals(1, perception.endTurns)
        } finally {
            P3Log.sink = previous
        }
    }

    @Test fun `a superseded turn cannot close the newer turn's perception boundary`() {
        val perception = TicketProbePerception()
        val tasks = JoinableTasks()
        val firstRequestStarted = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val secondRequestStarted = CountDownLatch(1)
        val releaseSecond = CountDownLatch(1)
        val rig = SmartTestRig(cooldownNanos = 0L)
        val previous = P3Log.sink
        P3Log.sink = { }
        try {
            rig.activate(overrides = mapOf(Capability.Perception to perception), tasks = tasks)
            var request = 0
            rig.model.onRequest = { modelRequest, listener ->
                when (++request) {
                    1 -> {
                        firstRequestStarted.countDown()
                        releaseFirst.await(5, TimeUnit.SECONDS)
                        streamText(modelRequest, listener, "第一个答案。")
                    }
                    2 -> {
                        secondRequestStarted.countDown()
                        releaseSecond.await(5, TimeUnit.SECONDS)
                        streamText(modelRequest, listener, "第二个答案。")
                    }
                    else -> streamText(modelRequest, listener, "好。")
                }
            }

            rig.plugin.handle(rig.wakeEvent())
            rig.plugin.handle(TranscriptEvent(rig.transcript("第一个问题", turnId = "asr-a")))
            assertTrue(perception.firstBegin.await(5, TimeUnit.SECONDS), "turn A opens its perception turn")
            assertTrue(firstRequestStarted.await(5, TimeUnit.SECONDS), "turn A is inside its model request")

            // A newer wake replaces A while A is still blocked, and opens its own perception boundary.
            rig.clock.advance(1_000_000L)
            rig.plugin.handle(rig.wakeEvent())
            rig.plugin.handle(TranscriptEvent(rig.transcript("第二个问题", turnId = "asr-b")))
            assertTrue(perception.secondBegin.await(5, TimeUnit.SECONDS), "turn B opens its perception turn")
            assertTrue(secondRequestStarted.await(5, TimeUnit.SECONDS), "turn B is inside its model request")

            // A now unwinds on its own worker. Its finally must not close B's still-open boundary.
            releaseFirst.countDown()
            tasks.join(0)
            assertEquals(
                0,
                perception.endTurns.get(),
                "a superseded turn must not call endTurn: it would clear the newer turn's snapshot/refs",
            )
            assertTrue(perception.liveSnapshot != null, "turn B's snapshot/refs survive turn A's finally")

            releaseSecond.countDown()
            tasks.join(1)
            assertEquals(1, perception.endTurns.get(), "only the live turn closes the provider")
        } finally {
            P3Log.sink = previous
        }
    }

    /**
     * The host port for the superseded-turn test: it models the one thing the real provider owns — a live snapshot/ref
     * set that `beginTurn` installs and `endTurn` clears — and lets the test see that set from outside.
     */
    private class TicketProbePerception : PerceptionProvider {
        val firstBegin = CountDownLatch(1)
        val secondBegin = CountDownLatch(1)
        val endTurns = AtomicInteger(0)
        private val begins = AtomicInteger(0)

        @Volatile
        var liveSnapshot: PerceptionSnapshot? = null
            private set

        override fun beginTurn(): PerceptionSnapshot {
            val number = begins.incrementAndGet()
            val snapshot = smartPerceptionSnapshot(snapshotId = "snap-$number")
            liveSnapshot = snapshot
            if (number == 1) firstBegin.countDown() else secondBegin.countDown()
            return snapshot
        }

        override fun snapshot(
            include: Set<PerceptionSource>,
            filter: PerceptionFilter,
            limit: Int?,
        ): PerceptionSnapshot =
            checkNotNull(liveSnapshot) { "snapshot requested after the perception turn closed" }

        override fun afterAction(): PerceptionSnapshot =
            snapshot(emptySet(), PerceptionFilter.ALL, null)

        override fun screenshot(): PerceptionScreenshotResult =
            PerceptionScreenshotResult.Unavailable("test")

        override fun endTurn() {
            endTurns.incrementAndGet()
            liveSnapshot = null
        }
    }

    /** A task scope whose workers the test can join one at a time, in launch order. */
    private class JoinableTasks : TaskScope {
        private val workers = mutableListOf<Thread>()

        override fun launch(block: () -> Unit): CancellationHandle {
            val worker = Thread(block, "perception-turn-worker-${workers.size + 1}")
            worker.isDaemon = true
            synchronized(workers) { workers += worker }
            worker.start()
            return object : CancellationHandle { override fun cancel() = Unit }
        }

        override fun cancelAll() = Unit

        fun join(index: Int, millis: Long = 5_000L) {
            synchronized(workers) { workers[index] }.join(millis)
        }
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
        assertEquals(
            PromptCatalog.plan(PromptRoute.WAKE, null).systemText,
            request.instructions,
            "the session's profile-less catalog plan is the system text (the rig grants no device provider)",
        )
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

    @Test fun `a long notification is submitted whole so the glasses can scroll it`() {
        // Owner ruling 2026-10-14: 超长通知不能截断，要推送眼镜端滚动显示. Three separate bounds used to shorten this, and
        // the quietest was `ReadableHintAssembler`'s three-sentence rule, which cut a long notification to its first
        // three sentences with nothing said about the rest. A hint the bound allows must reach the wearer whole.
        val rig = SmartTestRig()
        rig.activate()
        // Each sentence carries its ordinal, so "which sentences arrived" is readable straight off the hint. Six of
        // them also put the text past one six-line window, which is the case the ruling is about.
        val spoken = listOf(
            "第一句提醒你今天的安排。", "第二句也要把话说完。", "第三句同样重要不能省。",
            "第四句不能丢在路上。", "第五句是这条的结尾。", "第六句在这里收尾。",
        )
        val long = spoken.joinToString("")
        // The old rule cut at three sentences, so its output could never exceed these three; the whole text is what
        // the bound now allows through.
        assertTrue(long.length > spoken.take(3).sumOf { it.length }, "the fixture must exceed the old three-sentence cut")
        rig.model.onRequest = { request, listener -> streamText(request, listener, long) }
        rig.wakeAndFinal("记录一下")

        val shown = rig.hintTexts.single()
        spoken.forEach { sentence ->
            assertTrue(shown.contains(sentence.removeSuffix("。")), "the hint lost $sentence: $shown")
        }

        // And the panel must hold it for as long as the slide takes: a hint the reading budget sized for several
        // screens cannot expire on the turn's five-second grace, or the wearer loses the end of exactly the long
        // notifications this ruling is about.
        val hint = rig.output.hints.single()
        val needed = HintReadingBudget.readMillis(hint.text.length, baseMillis = 5_000L)
        val grantedMillis = (hint.expiresAtNanos - rig.clock.nowNanos()) / 1_000_000L
        assertTrue(
            grantedMillis >= needed - 1_000L,
            "the panel clears after ${grantedMillis}ms but the slide needs ${needed}ms",
        )
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

    @Test fun `S4 usable partial revisions are published before the final and the final names the asr entry id`() {
        val rig = SmartTestRig()
        val display = SmartRecordingDisplay()
        rig.activate(overrides = mapOf(Capability.ConversationDisplay to display))
        rig.model.onRequest = { request, listener -> streamText(request, listener, "答案。") }
        rig.plugin.handle(rig.wakeEvent())
        rig.plugin.handle(TranscriptEvent(rig.transcript(" 帮我看 ", turnId = "t-7", revision = 1, final = false)))
        rig.plugin.handle(TranscriptEvent(rig.transcript("帮我看下", turnId = "t-7", revision = 2, final = false)))
        rig.plugin.handle(TranscriptEvent(rig.transcript("帮我看下日程", turnId = "t-7", revision = 3, final = true)))
        rig.tick()

        assertEquals(
            listOf(
                SmartRecordingDisplay.UserPartialCall(AssistantRoute.WAKE, "t-7", 1, "帮我看"),
                SmartRecordingDisplay.UserPartialCall(AssistantRoute.WAKE, "t-7", 2, "帮我看下"),
            ),
            display.userPartials,
        )
        assertEquals(
            listOf(SmartRecordingDisplay.UserFinalCall(AssistantRoute.WAKE, "t-7", "asr-t-7", "帮我看下日程")),
            display.userFinals,
        )
        assertEquals(1, rig.model.requests.size)
    }

    @Test fun `S4 blank and wake-word-only partial revisions are not published`() {
        val rig = SmartTestRig()
        val display = SmartRecordingDisplay()
        rig.activate(overrides = mapOf(Capability.ConversationDisplay to display))
        rig.plugin.handle(rig.wakeEvent())

        rig.plugin.handle(TranscriptEvent(rig.transcript("   ", turnId = "asr-wake", revision = 0, final = false)))
        rig.plugin.handle(TranscriptEvent(rig.transcript("伊迪丝。", turnId = "asr-wake", revision = 1, final = false)))
        rig.plugin.handle(TranscriptEvent(rig.transcript("EDITH", turnId = "asr-wake", revision = 2, final = false)))

        assertTrue(display.userPartials.isEmpty(), "a blank/echo partial has no user row: ${display.userPartials}")
        assertTrue(display.userFinals.isEmpty())
        assertTrue(rig.model.requests.isEmpty(), "a partial must never start the model")
    }

    @Test fun `S4 a partial for a transcript with no current turn is not published`() {
        val rig = SmartTestRig()
        val display = SmartRecordingDisplay()
        rig.activate(overrides = mapOf(Capability.ConversationDisplay to display))

        rig.plugin.handle(TranscriptEvent(rig.transcript("没有唤醒的半句", turnId = "t-7", revision = 1, final = false)))

        assertTrue(display.userPartials.isEmpty(), "no conversation turn means no user row: ${display.userPartials}")
    }

    @Test fun `S4 a late partial after the final is not published`() {
        val rig = SmartTestRig()
        val display = SmartRecordingDisplay()
        rig.activate(overrides = mapOf(Capability.ConversationDisplay to display))
        rig.model.onRequest = { request, listener -> streamText(request, listener, "答案。") }
        rig.plugin.handle(rig.wakeEvent())
        rig.plugin.handle(TranscriptEvent(rig.transcript("帮我看下日程", turnId = "t-7", revision = 1, final = true)))
        rig.tick()
        val partialsBefore = display.userPartials.size

        rig.plugin.handle(TranscriptEvent(rig.transcript("迟到半句", turnId = "t-7", revision = 9, final = false)))

        assertEquals(partialsBefore, display.userPartials.size,
            "the final already replaced the line; a late revision must not reopen it")
        assertEquals(1, display.userFinals.size)
    }

    @Test fun `S4 a blank partial still cancels the official listening window`() {
        val rig = SmartTestRig(panelVisible = true)
        val display = SmartRecordingDisplay()
        rig.activate(overrides = mapOf(Capability.ConversationDisplay to display))
        rig.plugin.handle(rig.wakeEvent())
        assertTrue(display.states.isNotEmpty(), "the visible panel starts at a listening level")

        rig.plugin.handle(TranscriptEvent(rig.transcript("", turnId = "asr-echo", revision = 0, final = false)))
        rig.clock.advance(OfficialConversation.LISTEN_TIMEOUT_MILLIS * 1_000_000L)
        rig.scheduler.runDue()

        assertTrue(display.closes.isEmpty(),
            "a blank ASR is still an ASR message and cancels the 10 s window: ${display.calls}")
        assertTrue(rig.hintTexts.isEmpty())
    }

    // ------------------------------------------------- 0.24 S4 (Task 16): answer streaming

    @Test fun `S4 answer deltas revise one answer row and the final keeps the model's text`() {
        val (rig, display) = cycleRig(panelVisible = true)
        rig.model.onRequest = { request, listener ->
            listener(ModelStreamEvent.TextDelta(request.requestId, "今天"))
            listener(ModelStreamEvent.TextDelta(request.requestId, "晴。"))
            listener(ModelStreamEvent.Terminal(request.requestId, ModelEnd.COMPLETED, null, null))
        }
        rig.plugin.handle(rig.wakeEvent())
        rig.plugin.handle(TranscriptEvent(rig.transcript("今天天气", turnId = "t-7")))
        rig.tick()

        val hint = rig.output.hints.single()
        assertEquals("今天晴。", hint.text, "streaming must not change the final answer assembly")
        assertEquals(
            listOf(
                SmartRecordingDisplay.AssistantPartialCall(AssistantRoute.WAKE, "t-7", hint.hintId, 1, "今天"),
                SmartRecordingDisplay.AssistantPartialCall(AssistantRoute.WAKE, "t-7", hint.hintId, 2, "今天晴。"),
            ),
            display.assistantPartials,
            "every revision carries the final hint id and the accumulated text, with a monotonic revision",
        )
        assertEquals(
            listOf(SmartRecordingDisplay.AssistantFinalCall(AssistantRoute.WAKE, "t-7", hint.hintId, "今天晴。")),
            display.assistantFinals,
            "the final statement names the same entry identity the revisions used",
        )
        assertEquals(1, rig.output.hints.size, "streaming never submits a hint of its own")
    }

    @Test fun `S4 a blank delta publishes no row and the first real delta starts at revision one`() {
        val (rig, display) = cycleRig(panelVisible = true)
        rig.model.onRequest = { request, listener ->
            listener(ModelStreamEvent.TextDelta(request.requestId, ""))
            listener(ModelStreamEvent.TextDelta(request.requestId, "答案。"))
            listener(ModelStreamEvent.Terminal(request.requestId, ModelEnd.COMPLETED, null, null))
        }
        rig.plugin.handle(rig.wakeEvent())
        rig.plugin.handle(TranscriptEvent(rig.transcript("问", turnId = "t-4")))
        rig.tick()

        val hint = rig.output.hints.single()
        assertEquals(
            listOf(SmartRecordingDisplay.AssistantPartialCall(AssistantRoute.WAKE, "t-4", hint.hintId, 1, "答案。")),
            display.assistantPartials,
        )
    }

    @Test fun `S4 a delta after the final answer is dropped`() {
        val (rig, display) = cycleRig(panelVisible = true)
        rig.model.onRequest = { request, listener ->
            listener(ModelStreamEvent.TextDelta(request.requestId, "答案。"))
            listener(ModelStreamEvent.Terminal(request.requestId, ModelEnd.COMPLETED, null, null))
        }
        rig.plugin.handle(rig.wakeEvent())
        rig.plugin.handle(TranscriptEvent(rig.transcript("问", turnId = "t-5")))
        rig.tick()
        val partialsBefore = display.assistantPartials.size

        // A transport that delivers after its own terminal: the gateway drops it, so the display path is never reached.
        rig.model.callbacks.single()(ModelStreamEvent.TextDelta("t-5", "迟到的一句话"))

        assertEquals(partialsBefore, display.assistantPartials.size, "the final is authoritative: no late revision")
        assertEquals("答案。", rig.output.hints.single().text)
    }

    @Test fun `S4 a streamed answer whose final ACK races the run end settles once on the final emit`() {
        val (rig, display) = cycleRig(panelVisible = true, continueDialogue = true)
        rig.model.onRequest = { request, listener ->
            listener(ModelStreamEvent.TextDelta(request.requestId, "答案。"))
            listener(ModelStreamEvent.Terminal(request.requestId, ModelEnd.COMPLETED, null, null))
        }
        rig.output.onSubmit = { hint ->
            rig.output.onSubmit = null
            rig.plugin.handle(OutputStateEvent(rig.envelope(), hint.hintId, OutputStatus.Displayed, hint.emitId))
        }
        rig.plugin.handle(rig.wakeEvent())
        rig.plugin.handle(TranscriptEvent(rig.transcript("问题", turnId = "t-6")))
        rig.tick()

        assertEquals(1, rig.displayedAcks, "the in-submit ACK is admitted once the final emit is accepted")
        assertEquals(1, display.assistantPartials.size)
        assertEquals(1, display.assistantFinals.size)
        assertEquals(
            listOf(OfficialStatus.AUDIO_FINISHED.wire, OfficialStatus.LISTENING.wire),
            display.statuses.takeLast(2),
            "the streamed display did not settle the answer: the run end does, once, then the next round listens",
        )
        assertEquals(2, display.count("working:WAKE"), "exactly one continuation was opened")
        assertTrue(display.closes.isEmpty())
    }

    @Test fun `S4 streamed partials do not stand in for a final answer the display rejected`() {
        val (rig, display) = cycleRig(panelVisible = true, continueDialogue = true)
        rig.output.submission = OutputSubmission.Rejected
        rig.model.onRequest = { request, listener ->
            listener(ModelStreamEvent.TextDelta(request.requestId, "答案。"))
            listener(ModelStreamEvent.Terminal(request.requestId, ModelEnd.COMPLETED, null, null))
        }
        rig.plugin.handle(rig.wakeEvent())
        rig.plugin.handle(TranscriptEvent(rig.transcript("问题", turnId = "t-8")))
        rig.tick()

        assertTrue(display.assistantPartials.isNotEmpty(), "the deltas really reached the display port")
        assertEquals(listOf(AssistantRoute.WAKE), display.closes, "an answer nobody saw closes, it never continues")
        assertTrue(OfficialStatus.AUDIO_FINISHED.wire !in display.statuses)
        assertEquals(1, display.assistantFinals.size, "the phone's record still names the final entry")
    }

    // ------------------------------------------------- 0.24 S4 (Task 17): tool status rows

    /** A panel-visible rig whose host advertises one L1 action with a caller-chosen user-facing title. */
    private fun toolRig(
        title: String = "记笔记",
        description: String = title,
    ): Pair<SmartTestRig, SmartRecordingDisplay> {
        val display = SmartRecordingDisplay()
        val rig = SmartTestRig(panelVisible = true)
        rig.actions.descriptors += smartAction(
            "compose_note",
            risk = RiskLevel.L1,
            scope = "note",
            title = title,
            description = description,
        )
        rig.actions.outcome = { _, _ -> ActionResult(ActionOutcome.Done, "记好了") }
        rig.activate(overrides = mapOf(Capability.ConversationDisplay to display))
        return rig to display
    }

    /** First request: one text delta then a tool call; second request: the closing answer. */
    private fun SmartTestRig.composeNoteRun(preamble: String?) {
        var requests = 0
        model.onRequest = { request, listener ->
            if (requests++ == 0) {
                preamble?.let { listener(ModelStreamEvent.TextDelta(request.requestId, it)) }
                listener(ModelStreamEvent.ToolCallTerminal(request.requestId, "item-1", "call-1", "compose_note", "{}"))
                listener(ModelStreamEvent.Terminal(request.requestId, ModelEnd.COMPLETED, null, null))
            } else {
                streamText(request, listener, "已经记好了。")
            }
        }
    }

    @Test fun `S4 a tool call is published as START and END under the action's mapped label`() {
        val (rig, display) = toolRig(title = "记笔记")
        rig.composeNoteRun(preamble = null)
        rig.wakeAndFinal("帮我记一条笔记")
        rig.tick()

        assertEquals(
            listOf(
                SmartRecordingDisplay.ToolStatusCall(AssistantRoute.WAKE, "asr-1", ToolPhase.START, "记笔记"),
                SmartRecordingDisplay.ToolStatusCall(AssistantRoute.WAKE, "asr-1", ToolPhase.END, "记笔记"),
            ),
            display.toolStatuses,
            "the row must name the EDITH action with its host-declared label, not the raw name",
        )
        assertEquals(listOf("compose_note" to "{}"), rig.actions.executed)
        assertEquals(listOf("已经记好了。"), rig.hintTexts, "the tool call never becomes the answer")
    }

    @Test fun `S4 the tool row shows the user title and never the long model description`() {
        // edith-0.3 Phase 4A Task 5 (A5): description is the model's instruction prose. Painting it as the row label
        // would put a paragraph on the glasses; the row reads the host's short title.
        val longDescription = "Use compose_note when the wearer asks to keep a short local note. " +
            "Do not use it for messages or files. STORAGE_UNAVAILABLE means nothing was written. ".repeat(6)
        val (rig, display) = toolRig(title = "记笔记", description = longDescription)
        rig.composeNoteRun(preamble = null)
        rig.wakeAndFinal("帮我记一条笔记")
        rig.tick()

        assertEquals(
            listOf(
                SmartRecordingDisplay.ToolStatusCall(AssistantRoute.WAKE, "asr-1", ToolPhase.START, "记笔记"),
                SmartRecordingDisplay.ToolStatusCall(AssistantRoute.WAKE, "asr-1", ToolPhase.END, "记笔记"),
            ),
            display.toolStatuses,
            "the row must use the user title, never the model-facing description",
        )
    }

    @Test fun `S4 an unknown action name is published raw rather than guessed`() {
        val (rig, display) = cycleRig(panelVisible = true)
        var requests = 0
        rig.model.onRequest = { request, listener ->
            if (requests++ == 0) {
                listener(ModelStreamEvent.ToolCallTerminal(request.requestId, "item-1", "call-1", "mystery_tool", "{}"))
                listener(ModelStreamEvent.Terminal(request.requestId, ModelEnd.COMPLETED, null, null))
            } else {
                streamText(request, listener, "答案。")
            }
        }
        rig.wakeAndFinal("试一个未知动作")
        rig.tick()

        assertEquals(
            listOf(
                SmartRecordingDisplay.ToolStatusCall(AssistantRoute.WAKE, "asr-1", ToolPhase.START, "mystery_tool"),
                SmartRecordingDisplay.ToolStatusCall(AssistantRoute.WAKE, "asr-1", ToolPhase.END, "mystery_tool"),
            ),
            display.toolStatuses,
        )
    }

    @Test fun `S4 the plugin's own answer and skill tools never paint a tool row`() {
        val (rig, display) = cycleRig(panelVisible = true, continueDialogue = true)
        notifyAnswer(rig, channel = "screen")
        rig.plugin.handle(rig.wakeEvent())
        rig.plugin.handle(TranscriptEvent(rig.transcript("问题", turnId = "asr-1")))
        rig.tick()

        assertTrue(rig.output.hints.isNotEmpty(),
            "the run really called notify_user: ${rig.actions.executed}")
        assertTrue(display.toolStatuses.isEmpty(),
            "notify_user/load_skill are EDITH internals, not device actions: ${display.toolStatuses}")
    }

    @Test fun `S4 a tool round starts the official tool level before the row and the answer retires it`() {
        val (rig, display) = toolRig()
        rig.composeNoteRun(preamble = null)
        rig.wakeAndFinal("帮我记一条笔记")
        rig.tick()

        val statuses = display.statuses
        assertTrue(OfficialStatus.TOOL.wire in statuses,
            "the official tool level 7 must be displayed while the action runs: $statuses")
        assertTrue(statuses.indexOf(OfficialStatus.TOOL.wire) > statuses.indexOf(OfficialStatus.ASR_END.wire),
            "the tool level follows the thinking level")
        assertEquals(OfficialStatus.ANSWERING.wire, statuses.last(), "the closing answer is the last level")
        val toolIndex = display.calls.indexOfFirst { it.startsWith("tool:") }
        val answerIndex = display.calls.indexOfFirst { it.startsWith("answer:") }
        assertTrue(toolIndex in 0 until answerIndex,
            "the tool row is published before the streamed answer: ${display.calls}")
    }

    @Test fun `S4 a tool round's preamble does not accumulate into the final answer row`() {
        val (rig, display) = toolRig()
        rig.composeNoteRun(preamble = "先想一下。")
        rig.wakeAndFinal("帮我记一条笔记")
        rig.tick()

        assertEquals(
            listOf("先想一下。", "已经记好了。"),
            display.assistantPartials.map { it.text },
            "the second request's revision starts a fresh accumulation; the preamble is not re-sent with the answer",
        )
        assertEquals("已经记好了。", rig.output.hints.single().text,
            "the final answer assembly is unchanged")
    }

    // ------------------------------------------------- 0.24 S4 (Task 16 I1): the memory acknowledgement is not the answer

    /**
     * The real explicit-memory path, not a hand-emitted acknowledgement.
     *
     * The utterance is an explicit instruction, so `rememberFromTurn` writes the memory and emits
     * [MemoryExtraction.ACKNOWLEDGEMENT] with `recordAsAnswer = false` **after** the `notify_user` answer of the same
     * run: the production order in which the acknowledgement used to become the last write under `say-${hintId}`.
     */
    private fun explicitMemoryNotifyRig(): Triple<SmartTestRig, SmartRecordingDisplay, SmartFakeStorage> {
        val storage = SmartFakeStorage()
        val (rig, display) = cycleRig(
            panelVisible = true,
            continueDialogue = true,
            capabilities = mapOf(Capability.Storage to storage),
        )
        notifyAnswer(rig, channel = "screen")
        return Triple(rig, display, storage)
    }

    @Test fun `S4 an explicit memory acknowledgement never becomes the notify answer entry`() {
        val (rig, display, storage) = explicitMemoryNotifyRig()

        rig.plugin.handle(rig.wakeEvent())
        rig.plugin.handle(TranscriptEvent(rig.transcript("记住我不吃香菜")))
        rig.tick()

        assertEquals(2, rig.output.hints.size, "the answer and its acknowledgement are two hints")
        val answer = rig.output.hints[0]
        val acknowledgement = rig.output.hints[1]
        assertEquals("答案", answer.text)
        assertEquals(MemoryExtraction.ACKNOWLEDGEMENT, acknowledgement.text)
        assertEquals(answer.hintId, acknowledgement.hintId, "one turn keeps one conversation entry hint id")
        assertNotNull(answer.emitId)
        assertNotEquals(answer.emitId, acknowledgement.emitId, "each emit has its own identity")

        // The real extractor wrote the instruction; this is not a synthetic acknowledgement.
        assertEquals(
            listOf("我不吃香菜"),
            MemoryLog(storage = storage, now = { 1_000L }, log = { }).entries().map { it.text },
        )

        assertEquals(
            listOf(SmartRecordingDisplay.AssistantFinalCall(AssistantRoute.WAKE, "asr-1", answer.hintId, "答案")),
            display.assistantFinals,
            "exactly one answer final, with the model's text; the acknowledgement is not the answer's final",
        )
        assertEquals(
            listOf(SmartRecordingDisplay.AssistantNoticeCall(AssistantRoute.WAKE, MemoryExtraction.ACKNOWLEDGEMENT)),
            display.assistantNotices,
            "the acknowledgement is recorded under its own identity, never as the answer",
        )
        assertTrue(display.closes.isEmpty(), "the answer is still on screen; nothing settled no_display")
    }

    @Test fun `S4 the acknowledgement replacement rejection cannot settle the in flight answer`() {
        val (rig, display, _) = explicitMemoryNotifyRig()

        rig.plugin.handle(rig.wakeEvent())
        rig.plugin.handle(TranscriptEvent(rig.transcript("记住我不吃香菜")))
        rig.tick()
        val answer = rig.output.hints.first()
        assertEquals(2, rig.output.hints.size, "the acknowledgement was emitted after the answer")
        assertTrue(display.closes.isEmpty())

        // The real arbiter's replacement terminal for the answer, emitted when the acknowledgement replaces it by
        // hint id. The answer's own Displayed acknowledgement is still in flight at this point.
        rig.plugin.handle(OutputStateEvent(rig.envelope(), answer.hintId, OutputStatus.Rejected, answer.emitId))
        assertTrue(
            display.closes.isEmpty(),
            "a Rejected caused by a non-answer emit superseding the answer is not the answer's terminal",
        )
        assertTrue(OfficialStatus.AUDIO_FINISHED.wire !in display.statuses)

        // The in-flight acknowledgement lands: the answer was displayed after all and continues exactly once.
        rig.plugin.handle(OutputStateEvent(rig.envelope(), answer.hintId, OutputStatus.Displayed, answer.emitId))
        assertEquals(OfficialStatus.AUDIO_FINISHED.wire, display.statuses[display.statuses.size - 2])
        assertEquals(OfficialStatus.LISTENING.wire, display.statuses.last())
        assertTrue(display.closes.isEmpty(), "the displayed answer continues instead of closing")
    }

    @Test fun `transcripts for another session or generation are ignored`() {
        val rig = SmartTestRig()
        rig.activate()
        rig.model.onRequest = { request, listener -> streamText(request, listener, "答案。") }
        rig.plugin.handle(rig.wakeEvent())
        rig.plugin.handle(TranscriptEvent(rig.transcript("外会话", generation = com.sheen.rokid.contracts.Generation(9))))
        rig.plugin.handle(
            TranscriptEvent(
                com.sheen.rokid.contracts.TranscriptRevision(
                    com.sheen.rokid.contracts.EventEnvelope(com.sheen.rokid.contracts.SessionId("other"), rig.generation, "e", 0, 0),
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
        // B7 added `notify_user` to every turn's tool table (spec §6.1: it is the model's only channel to the
        // wearer), and Phase 6 added `load_skill` (D-1/D-2: the catalog's only way to a body), so the action set is
        // asserted by membership rather than by being the whole list. A count of 2 was true only before `load_skill`.
        assertEquals(
            setOf("compose_note", "notify_user", "load_skill"),
            tools.map { it.name }.toSet(),
        )
        assertEquals("Action compose_note", tools.single { it.name == "compose_note" }.description)
    }

    @Test fun `a model-chosen L1 tool runs and feeds its result back into the same run`() {
        val rig = SmartTestRig()
        rig.actions.descriptors += smartAction("compose_note", risk = RiskLevel.L1, scope = "note")
        rig.actions.outcome = { _, _ -> com.sheen.rokid.plugin.ActionResult(com.sheen.rokid.plugin.ActionOutcome.Done, "已记下") }
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
            second.history.any {
                it is com.sheen.rokid.plugin.ModelHistoryItem.ToolOutput && it.output == unmigratedToolOutput("已记下")
            },
            "the tool output must be echoed back into the transcript, was ${second.history}",
        )
    }

    @Test fun `displayed ack is recorded only for the current hint before its expiry`() {
        val rig = SmartTestRig()
        rig.activate()
        rig.model.onRequest = { request, listener -> streamText(request, listener, "答案。") }
        rig.wakeAndFinal("问题")
        val hintId = rig.output.hints.single().hintId

        for (status in listOf(OutputStatus.Accepted, OutputStatus.Queued)) {
            rig.plugin.handle(OutputStateEvent(rig.envelope(), hintId, status))
        }
        // `Expired`/`Rejected` are deliberately absent: since Task 14 they settle the answer as not displayed and
        // close the conversation (the I1 tests below cover them), so a later `Displayed` could not be admitted.
        assertEquals(0, rig.displayedAcks)
        rig.plugin.handle(OutputStateEvent(rig.envelope(com.sheen.rokid.contracts.Generation(9)), hintId, OutputStatus.Displayed))
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
        rejected.output.submission = com.sheen.rokid.plugin.OutputSubmission.Rejected
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
        // A visible panel: the wake segment's blank final is the expected first ASR message, and the bounded wait for a
        // panel that is already up is not armed (spec §5.3). A hidden panel would (correctly) close the round at 3 s.
        val rig = SmartTestRig(panelVisible = true)
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

    @Test fun `S3 blank finals cancel the official window without arming a failure answer`() {
        val display = SmartRecordingDisplay()
        val rig = SmartTestRig(panelVisible = true)
        rig.activate(overrides = mapOf(Capability.ConversationDisplay to display))

        rig.plugin.handle(rig.wakeEvent())
        rig.plugin.handle(TranscriptEvent(rig.transcript("", turnId = "asr-wake-1", revision = 0)))
        // A second blank segment is still an ASR message: the official handler removes the timeout before its blank
        // check, so it must stay cancelled rather than being re-armed.
        rig.tick(9_000)
        rig.plugin.handle(TranscriptEvent(rig.transcript("   ", turnId = "asr-wake-2", revision = 0)))
        rig.tick(60_000)

        assertTrue(rig.hintTexts.isEmpty(), "a blank final must never become the 'did not catch it' failure hint")
        assertTrue(rig.model.requests.isEmpty(), "nothing usable was said, so no model request may be made")
        assertTrue(display.closes.isEmpty(), "the ASR message cancelled the official window; no timer may close")
        assertTrue(display.statuses.none { it == OfficialStatus.ASR.wire },
            "a blank ASR must not publish an empty ASR row (spec §10 item 19)")
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

    @Test fun `S3 a stop during the official window cancels it without submitting`() {
        val display = SmartRecordingDisplay()
        val rig = SmartTestRig(panelVisible = true)
        rig.activate(overrides = mapOf(Capability.ConversationDisplay to display))

        rig.plugin.handle(rig.wakeEvent())
        assertEquals(1, rig.scheduler.pending(), "the visible-panel wake arms exactly one official window")
        rig.plugin.handle(UserAction(rig.envelope(), UserAction.Action.Stop))
        assertEquals(1, rig.cancels.size, "Stop must clear the pending output turn")
        assertEquals(0, rig.scheduler.pending(), "Stop must release the official window")

        // Far past the window: the cancelled deadline must not fire and nothing may be submitted.
        rig.tick(millis = 60_000)
        assertTrue(rig.model.requests.isEmpty(), "a stopped turn must never submit")
        assertTrue(rig.hintTexts.isEmpty(), "a stopped turn must never hint")
        assertEquals(1, display.idleCalls.size, "STOP shows the official idle and keeps the panel")
        assertTrue(display.closes.isEmpty())
    }

    @Test fun `S3 the official window does not borrow the model profile timeout`() {
        val display = SmartRecordingDisplay()
        val rig = SmartTestRig(panelVisible = true)
        rig.profile = null
        rig.activate(overrides = mapOf(Capability.ConversationDisplay to display))

        rig.plugin.handle(rig.wakeEvent())
        rig.tick(OfficialConversation.LISTEN_TIMEOUT_MILLIS - 1)
        assertTrue(display.closes.isEmpty(), "the official window is 10 s even when no profile can supply a timeout")
        rig.tick(1)
        assertEquals(listOf(AssistantRoute.WAKE), display.closes)
        assertTrue(rig.model.requests.isEmpty())
        assertTrue(rig.hintTexts.isEmpty())
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

    // ------------------------------------------------- 0.24 S3 (Task 12): official conversation cycle

    /** A rig with the official display port attached, so the cycle's levels and closes are observable. */
    private fun cycleRig(
        panelVisible: Boolean = false,
        continueDialogue: Boolean = false,
        speechAvailable: Boolean = true,
        /** Extra host capabilities, e.g. `Storage` for a test that drives the real memory extraction. */
        capabilities: Map<Capability, Any> = emptyMap(),
    ): Pair<SmartTestRig, SmartRecordingDisplay> {
        val display = SmartRecordingDisplay()
        val rig = SmartTestRig(
            panelVisible = panelVisible,
            continueDialogue = continueDialogue,
            speechAvailable = speechAvailable,
        )
        rig.activate(overrides = mapOf(Capability.ConversationDisplay to display) + capabilities)
        return rig to display
    }

    private fun SmartTestRig.displayedAnswer(): String {
        model.onRequest = { request, listener -> streamText(request, listener, "答案。") }
        plugin.handle(wakeEvent())
        plugin.handle(TranscriptEvent(transcript("问题")))
        tick()
        return output.hints.single().hintId
    }

    @Test fun `S3 a hidden panel starts the official window only at panel shown`() {
        val (rig, display) = cycleRig()

        rig.plugin.handle(rig.wakeEvent())
        // Inside the bounded wait (spec §5.3): the overlay's own start latency is not the wearer's silence.
        rig.tick(2_999)
        assertTrue(display.closes.isEmpty(), "the overlay's own start latency is not the wearer's silence")
        assertTrue(display.states.isEmpty(), "STATE 1 waits until there is a panel to show it on")
        assertTrue(rig.hintTexts.isEmpty(), "waiting for the panel must not answer anything")

        rig.plugin.onPanelShown(AssistantRoute.WAKE)
        assertEquals(listOf(OfficialStatus.LISTENING.wire), display.statuses)

        rig.tick(OfficialConversation.LISTEN_TIMEOUT_MILLIS - 1)
        assertTrue(display.closes.isEmpty(), "9_999 ms is inside the official window")
        rig.tick(1)
        assertEquals(listOf(AssistantRoute.WAKE), display.closes)
        assertTrue(rig.hintTexts.isEmpty(), "the official timeout closes the conversation; it does not submit a hint")
        assertTrue(rig.model.requests.isEmpty())
    }

    @Test fun `a panel shown while help runs is not closed as an orphan wake`() {
        val (rig, display) = cycleRig()
        rig.model.onRequest = { request, listener -> streamText(request, listener, "答案。") }
        rig.plugin.handle(TranscriptEvent(rig.transcript("问题")))
        rig.plugin.handle(rig.wakeEvent())
        // `Help` is the non-wake entry point: it replaces the waiting wake turn and resets the conversation cycle, so
        // the cycle has no window while a real turn runs. The panel requested by the wake can report its first layout
        // after that - it belongs to the Help turn and must not be closed by the turn-less defensive bound.
        rig.plugin.handle(UserAction(rig.envelope(), UserAction.Action.Help))
        assertEquals(1, rig.model.requests.size)

        rig.plugin.onPanelShown(AssistantRoute.WAKE)
        rig.tick(3_000)

        assertTrue(
            display.closes.isEmpty(),
            "a running turn owns the panel; the defensive close is for turn-less panels: ${display.closes}",
        )
    }

    @Test fun `S3 a panel that never comes up closes the listening round after three seconds`() {
        val (rig, display) = cycleRig()

        // Spec §5.3: the phone waits at most 3 s for the panel's first layout instead of waiting forever (the silent
        // wake that used to have no ending path at all). No STATE 1 is displayed - there is no panel to show it on.
        rig.plugin.handle(rig.wakeEvent())
        assertEquals(1, rig.scheduler.pending(), "the bounded PANEL_SHOWN wait is armed")

        rig.tick(2_999)
        assertTrue(display.closes.isEmpty())
        rig.tick(1)

        assertEquals(listOf(AssistantRoute.WAKE), display.closes)
        assertTrue(display.states.isEmpty(), "the panel never showed, so no listening level was ever displayed")
        assertEquals(0, rig.scheduler.pending())
        assertEquals(1, rig.cancels.size, "the turn behind the missing panel is cancelled")
    }

    @Test fun `S3 the bounded panel wait survives the wake segment's blank ASR`() {
        val (rig, display) = cycleRig()
        rig.plugin.handle(rig.wakeEvent())
        // The wake segment's own blank final is the expected first ASR message (D12). It cancels the 10 s window, but
        // the panel-acquisition cap is not a silence timer: a panel that never comes up must still end the round.
        rig.plugin.handle(TranscriptEvent(rig.transcript("", turnId = "asr-echo", revision = 0)))

        rig.tick(3_000)

        assertEquals(listOf(AssistantRoute.WAKE), display.closes)
        assertTrue(display.states.isEmpty(), "the panel never showed, so no listening level was ever displayed")
        assertEquals(0, rig.scheduler.pending())
    }

    @Test fun `S3 a usable final before panel shown does not let the three second cap close the turn`() {
        val (rig, display) = cycleRig(continueDialogue = true)
        rig.model.onRequest = { request, listener -> streamText(request, listener, "答案。") }

        val lines = java.util.Collections.synchronizedList(mutableListOf<String>())
        val previous = P3Log.sink
        P3Log.sink = { lines += it }
        try {
            rig.plugin.handle(rig.wakeEvent())
            // The ASR final is ready while the overlay is still starting up: the plugin publishes status 2 and starts
            // assembling the answer before the panel's first layout.
            rig.plugin.handle(TranscriptEvent(rig.transcript("问题")))
            rig.tick()
            assertEquals(OfficialStatus.ANSWERING.wire, display.statuses.last(), "the answer is being put on screen")

            // The panel arrives 2 s after the wake, inside the 3 s acquisition cap.
            rig.tick(1_800)
            rig.panelVisible = true
            rig.plugin.onPanelShown(AssistantRoute.WAKE)
            // 0.2 + 1.8 + 1.0 == 3.0 s: this is where the old order fired `panel_not_shown` and closed the live turn.
            rig.tick(1_000)
        } finally {
            P3Log.sink = previous
        }

        assertTrue(display.closes.isEmpty(), "the panel did show; the 3 s cap must not close the answering turn")
        assertTrue(
            lines.none { it.contains("P3_CONV panel_not_shown") },
            "the shown panel must not be recorded as missing: $lines",
        )
        assertTrue(rig.cancels.isEmpty(), "the live turn must not be torn down")
        assertEquals(
            OfficialStatus.ANSWERING.wire,
            display.statuses.last(),
            "a round already at status 2 must not be reset to a listening level by the late panel",
        )

        // Still live: the answer's acknowledgement settles and continues it like any other displayed answer.
        rig.plugin.handle(OutputStateEvent(rig.envelope(), rig.output.hints.single().hintId, OutputStatus.Displayed))
        assertEquals(OfficialStatus.AUDIO_FINISHED.wire, display.statuses[display.statuses.size - 2])
        assertEquals(OfficialStatus.LISTENING.wire, display.statuses.last(), "the answering turn survived to continue")
    }

    @Test fun `S3 a visible panel arms the window at wake and displays status one`() {
        val (rig, display) = cycleRig(panelVisible = true)

        rig.plugin.handle(rig.wakeEvent())

        assertEquals(listOf(OfficialStatus.LISTENING.wire), display.statuses)
        rig.tick(OfficialConversation.LISTEN_TIMEOUT_MILLIS)
        assertEquals(listOf(AssistantRoute.WAKE), display.closes)
        assertEquals(1, display.count("working:WAKE"))
        assertEquals(1, display.count("idle:WAKE"), "the closed turn's busy level must be cleared")
    }

    @Test fun `S3 a duplicate panel shown refreshes the window without a second state or close`() {
        val (rig, display) = cycleRig()
        rig.plugin.handle(rig.wakeEvent())
        rig.plugin.onPanelShown(AssistantRoute.WAKE)

        rig.tick(9_000)
        // Task 11 re-announces PANEL_SHOWN; a delayed original can also arrive. Idempotent by contract.
        rig.plugin.onPanelShown(AssistantRoute.WAKE)
        rig.tick(OfficialConversation.LISTEN_TIMEOUT_MILLIS - 1)
        assertTrue(display.closes.isEmpty(), "the duplicate must restart the 10 s window, not fire the first one")

        rig.tick(1)
        assertEquals(listOf(AssistantRoute.WAKE), display.closes)
        assertEquals(1, display.statuses.count { it == OfficialStatus.LISTENING.wire })
        assertEquals(0, rig.scheduler.pending())
    }

    @Test fun `S3 any ASR message cancels the window even when blank`() {
        val (rig, display) = cycleRig(panelVisible = true)
        rig.plugin.handle(rig.wakeEvent())
        rig.tick(9_000)

        rig.plugin.handle(TranscriptEvent(rig.transcript("", turnId = "asr-wake", revision = 0)))
        rig.tick(60_000)

        assertTrue(display.closes.isEmpty())
        assertTrue(display.statuses.none { it == OfficialStatus.ASR.wire },
            "a blank ASR must not publish an empty status-2 row (spec §10 item 19)")
        assertTrue(rig.hintTexts.isEmpty())
        assertTrue(rig.model.requests.isEmpty())
    }

    @Test fun `S3 a usable final displays the ASR statuses and cancels the timer`() {
        val (rig, display) = cycleRig(panelVisible = true)
        rig.model.onRequest = { request, listener -> streamText(request, listener, "答案。") }

        rig.plugin.handle(rig.wakeEvent())
        rig.plugin.handle(TranscriptEvent(rig.transcript("问题")))
        rig.tick()

        assertEquals(
            listOf(
                OfficialStatus.LISTENING.wire,
                OfficialStatus.ASR.wire,
                OfficialStatus.ASR_END.wire,
                OfficialStatus.ANSWERING.wire,
            ),
            display.statuses,
        )
        // The ASR final's own id replaces the wake placeholder before the official levels go out, so the glasses
        // group the user row and the answer under the same turn.
        assertEquals("asr-1", display.states.first { it.status == OfficialStatus.ASR.wire }.turnId)
        assertEquals("asr-1", display.states.first { it.status == OfficialStatus.ASR_END.wire }.turnId)
        assertTrue(display.closes.isEmpty())
    }

    @Test fun `S3 a displayed answer opens a continuation turn after status five`() {
        val (rig, display) = cycleRig(panelVisible = true, continueDialogue = true)
        val hintId = rig.displayedAnswer()

        rig.plugin.handle(OutputStateEvent(rig.envelope(), hintId, OutputStatus.Displayed))

        assertEquals(
            listOf(
                OfficialStatus.LISTENING.wire,
                OfficialStatus.ASR.wire,
                OfficialStatus.ASR_END.wire,
                OfficialStatus.ANSWERING.wire,
                OfficialStatus.AUDIO_FINISHED.wire,
                OfficialStatus.LISTENING.wire,
            ),
            display.statuses,
        )
        val firstTurn = display.states.first().turnId
        val nextTurn = display.states.last().turnId
        assertNotEquals(firstTurn, nextTurn, "the continuation is a new turn, not a replayed level")
        assertEquals(1, rig.model.requests.size, "the new round waits for the wearer; the model is not re-run")
        assertTrue(display.closes.isEmpty())
        assertEquals(2, display.count("working:WAKE"), "the continuation raises its own busy level")
        assertTrue(rig.cancels.isEmpty(), "the answer the next round continues must stay on the panel")
    }

    @Test fun `S3 continue off closes one second after the displayed answer with no status five or new turn`() {
        val (rig, display) = cycleRig(panelVisible = true, continueDialogue = false)
        val hintId = rig.displayedAnswer()

        rig.plugin.handle(OutputStateEvent(rig.envelope(), hintId, OutputStatus.Displayed))
        assertTrue(OfficialStatus.AUDIO_FINISHED.wire !in display.statuses)
        assertEquals(1, display.statuses.count { it == OfficialStatus.LISTENING.wire })

        rig.tick(999)
        assertTrue(display.closes.isEmpty(), "the official scheduleExit(1000) is not immediate")
        rig.tick(1)
        assertEquals(listOf(AssistantRoute.WAKE), display.closes)
        assertEquals(1, rig.model.requests.size, "continue off must not open a new turn")
        assertEquals(1, display.count("working:WAKE"))
        assertEquals(1, display.count("idle:WAKE"))
    }

    @Test fun `S3 an answer the display rejected closes without a continuation`() {
        val (rig, display) = cycleRig(panelVisible = true, continueDialogue = true)
        rig.output.submission = OutputSubmission.Rejected
        rig.model.onRequest = { request, listener -> streamText(request, listener, "答案。") }

        rig.plugin.handle(rig.wakeEvent())
        rig.plugin.handle(TranscriptEvent(rig.transcript("问题")))
        rig.tick()

        assertEquals(listOf(AssistantRoute.WAKE), display.closes)
        assertTrue(OfficialStatus.AUDIO_FINISHED.wire !in display.statuses)
        assertEquals(1, rig.model.requests.size, "a rejected answer has no next round")
        assertEquals(1, rig.output.hints.size)
    }

    @Test fun `S3 a displayed answer with speech waits for the matching SpeechFinished`() {
        val (rig, display) = cycleRig(panelVisible = true, continueDialogue = true)
        var requests = 0
        rig.model.onRequest = { request, listener ->
            if (requests++ == 0) {
                listener(smartToolCall("c1", "notify_user", """{"text":"答案","channel":"both"}"""))
            }
            listener(ModelStreamEvent.Terminal(request.requestId, ModelEnd.COMPLETED, null, null))
        }
        rig.plugin.handle(rig.wakeEvent())
        rig.plugin.handle(TranscriptEvent(rig.transcript("问题")))
        rig.tick()

        val hintId = rig.output.hints.single().hintId
        assertNotNull(rig.output.hints.single().optionalSpeech, "the voice channel asks the host to speak")
        rig.plugin.handle(OutputStateEvent(rig.envelope(), hintId, OutputStatus.Displayed))
        assertTrue(OfficialStatus.AUDIO_FINISHED.wire !in display.statuses,
            "status 5 waits for the speech, not for the display alone")

        rig.plugin.handle(SpeechFinished(rig.envelope(), "somebody-elses-hint", SpeechResult.FINISHED))
        assertTrue(OfficialStatus.AUDIO_FINISHED.wire !in display.statuses, "a stale hint id is ignored")

        rig.plugin.handle(SpeechFinished(rig.envelope(), hintId, SpeechResult.FINISHED))
        assertEquals(OfficialStatus.AUDIO_FINISHED.wire, display.statuses[display.statuses.size - 2])
        assertEquals(OfficialStatus.LISTENING.wire, display.statuses.last())
        assertNotEquals(
            display.states[display.states.size - 2].turnId,
            display.states.last().turnId,
            "the next turn really is new",
        )
    }

    @Test fun `S3 pin then a quiet ten seconds idles and the pinned close follows at 120 seconds`() {
        val (rig, display) = cycleRig(panelVisible = true)
        rig.plugin.handle(rig.wakeEvent())
        rig.plugin.handle(PinChanged(rig.envelope(), AssistantRoute.WAKE))

        rig.tick(OfficialConversation.LISTEN_TIMEOUT_MILLIS)
        assertEquals(1, display.idleCalls.size, "a pinned timeout shows idle instead of closing")
        assertTrue(display.closes.isEmpty())

        rig.tick(OfficialConversation.PINNED_CLOSE_MILLIS - 1)
        assertTrue(display.closes.isEmpty(), "119_999 ms after idle is inside the pinned window")
        rig.tick(1)
        assertEquals(listOf(AssistantRoute.WAKE), display.closes)
        assertTrue(rig.model.requests.isEmpty())
        assertTrue(rig.hintTexts.isEmpty())
    }

    @Test fun `S3 a pin set while answering survives the automatic continuation`() {
        val (rig, display) = cycleRig(panelVisible = true, continueDialogue = true)
        val hintId = rig.displayedAnswer()

        // The wearer pins while the answer is on screen; the answer then continues automatically. The official pin is
        // a dialog field, so the continuation (same dialog) must keep it.
        rig.plugin.handle(PinChanged(rig.envelope(), AssistantRoute.WAKE))
        rig.plugin.handle(OutputStateEvent(rig.envelope(), hintId, OutputStatus.Displayed))
        assertEquals(OfficialStatus.LISTENING.wire, display.statuses.last(), "the continuation opened a new window")

        rig.tick(OfficialConversation.LISTEN_TIMEOUT_MILLIS)
        assertEquals(1, display.idleCalls.size, "the pin survived the continuation, so its timeout idles")
        assertTrue(display.closes.isEmpty())

        rig.tick(OfficialConversation.PINNED_CLOSE_MILLIS)
        assertEquals(listOf(AssistantRoute.WAKE), display.closes, "the pinned 120 s close still ends it")
    }

    @Test fun `S3 an ASR message in the pinned idle window does not cancel the 120 s close`() {
        val (rig, display) = cycleRig(panelVisible = true)
        rig.plugin.handle(rig.wakeEvent())
        rig.plugin.handle(PinChanged(rig.envelope(), AssistantRoute.WAKE))
        rig.tick(OfficialConversation.LISTEN_TIMEOUT_MILLIS)
        assertEquals(1, display.idleCalls.size)
        assertEquals(1, rig.scheduler.pending(), "the 120 s pinned close is armed")

        // The CXR audio stream stays alive after the official-style exit (spec §10 item 20), so a blank wake echo can
        // arrive here. Official `removeExit()` removes only the 1 s continue-off exit, not `Ai_Delay_Quit`.
        rig.plugin.handle(TranscriptEvent(rig.transcript("", turnId = "asr-echo", revision = 0)))
        rig.tick(OfficialConversation.PINNED_CLOSE_MILLIS - 1)
        assertTrue(display.closes.isEmpty(), "the ASR message must not cancel the pinned close")
        rig.tick(1)
        assertEquals(listOf(AssistantRoute.WAKE), display.closes)
    }

    @Test fun `S3 stop shows idle and keeps the panel`() {
        val (rig, display) = cycleRig(panelVisible = true)
        rig.plugin.handle(rig.wakeEvent())

        rig.plugin.handle(UserAction(rig.envelope(), UserAction.Action.Stop))

        assertEquals(1, display.idleCalls.size)
        assertTrue(display.closes.isEmpty())
        assertEquals(0, rig.scheduler.pending())
    }

    @Test fun `S3 a panel closed by the glasses cancels the conversation and its window`() {
        val (rig, display) = cycleRig()
        rig.plugin.handle(rig.wakeEvent())
        rig.plugin.onPanelShown(AssistantRoute.WAKE)

        rig.plugin.onPanelClosed(AssistantRoute.WAKE)

        assertTrue(display.closes.isEmpty(), "the panel is already hidden; no CLOSE is sent")
        assertEquals(0, rig.scheduler.pending())
        assertEquals(1, rig.cancels.size, "the turn behind the hidden panel is cancelled")
    }

    @Test fun `S3 deactivate releases the pinned close timer`() {
        val (rig, display) = cycleRig(panelVisible = true)
        rig.plugin.handle(rig.wakeEvent())
        rig.plugin.handle(PinChanged(rig.envelope(), AssistantRoute.WAKE))
        rig.tick(OfficialConversation.LISTEN_TIMEOUT_MILLIS)
        assertEquals(1, display.idleCalls.size)
        assertEquals(1, rig.scheduler.pending(), "the 120 s pinned close is the one thing still armed")

        rig.plugin.deactivate(DeactivationReason.Stopped)

        assertEquals(0, rig.scheduler.pending(), "a session end must not leave a timer to close a future panel")
        rig.tick(OfficialConversation.PINNED_CLOSE_MILLIS + 1_000)
        assertTrue(display.closes.isEmpty())
    }

    // ------------------------------------------------- 0.24 S3 fix round: early speech and turn-scoped closes

    @Test fun `S3 a SpeechFinished that beats the display ACK is consumed when the ACK arrives`() {
        val (rig, display) = cycleRig(panelVisible = true, continueDialogue = true)
        var requests = 0
        rig.model.onRequest = { request, listener ->
            if (requests++ == 0) {
                listener(smartToolCall("c1", "notify_user", """{"text":"答案","channel":"both"}"""))
            }
            listener(ModelStreamEvent.Terminal(request.requestId, ModelEnd.COMPLETED, null, null))
        }
        rig.plugin.handle(rig.wakeEvent())
        rig.plugin.handle(TranscriptEvent(rig.transcript("问题")))
        rig.tick()
        val hintId = rig.output.hints.single().hintId

        // The local player can finish a short answer before the glasses' Displayed acknowledgement reaches the plugin.
        rig.plugin.handle(SpeechFinished(rig.envelope(), hintId, SpeechResult.FINISHED))
        assertTrue(
            OfficialStatus.AUDIO_FINISHED.wire !in display.statuses,
            "nothing may continue before the answer is known displayed",
        )

        rig.plugin.handle(OutputStateEvent(rig.envelope(), hintId, OutputStatus.Displayed))

        assertEquals(OfficialStatus.AUDIO_FINISHED.wire, display.statuses[display.statuses.size - 2],
            "the remembered speech end settles the answer at the acknowledgement")
        assertEquals(OfficialStatus.LISTENING.wire, display.statuses.last(), "and the next round opens")
        assertEquals(2, display.count("working:WAKE"), "the continuation opened its own round; the model is not re-run")
        assertEquals(1, rig.output.hints.size, "the next round waits for the wearer")
    }

    @Test fun `S3 a proactive hint's SpeechFinished cannot continue the wake conversation`() {
        val (rig, display) = cycleRig(panelVisible = true, continueDialogue = true)
        var wakeToolSent = false
        rig.model.onRequest = { request, listener ->
            when {
                request.turnId.startsWith("proactive-") ->
                    listener(smartToolCall("p1", "propose_hint", """{"text":"记得喝水"}"""))
                !wakeToolSent -> {
                    wakeToolSent = true
                    listener(smartToolCall("c1", "notify_user", """{"text":"答案","channel":"both"}"""))
                }
            }
            listener(ModelStreamEvent.Terminal(request.requestId, ModelEnd.COMPLETED, null, null))
        }
        rig.plugin.handle(rig.wakeEvent())
        rig.plugin.handle(TranscriptEvent(rig.transcript("问题")))
        rig.tick()
        val wakeHint = rig.output.hints.single().hintId
        rig.plugin.handle(OutputStateEvent(rig.envelope(), wakeHint, OutputStatus.Displayed))
        assertTrue(OfficialStatus.AUDIO_FINISHED.wire !in display.statuses, "the wake answer waits for its speech")

        // A proactive hint exists in the same session and spoke too. Its SpeechFinished names a different hint, so it
        // must not be taken as the wake answer's playback end (Task 5 review: route by hintId, not "current route").
        rig.plugin.handle(AcousticEvent(rig.envelope(), AcousticLabel.Speech))
        rig.plugin.handle(TranscriptEvent(rig.transcript("现在几点了", turnId = "asr-proactive")))
        rig.tick()
        val proactiveHint = rig.output.hints.firstOrNull { it.route == AssistantRoute.PROACTIVE }?.hintId
        assertNotNull(proactiveHint, "the proactive path submitted its own hint")
        assertNotEquals(wakeHint, proactiveHint)

        rig.plugin.handle(SpeechFinished(rig.envelope(), proactiveHint, SpeechResult.FINISHED))
        assertTrue(
            OfficialStatus.AUDIO_FINISHED.wire !in display.statuses,
            "a proactive hint's speech may not continue the WAKE conversation",
        )

        rig.plugin.handle(SpeechFinished(rig.envelope(), wakeHint, SpeechResult.FINISHED))
        assertEquals(OfficialStatus.AUDIO_FINISHED.wire, display.statuses[display.statuses.size - 2])
        assertEquals(OfficialStatus.LISTENING.wire, display.statuses.last())
    }

    @Test fun `S3 a close decided for the old turn does not tear down a concurrent wake`() {
        // The continue-off close runs 1 s after a displayed answer, and the old turn is already Idle, so a wake can
        // legitimately open the next turn. The cooldown is set to zero so that wake lands inside the close's own
        // after-lock callback; the production default would refuse it instead of testing the interleaving.
        val display = SmartRecordingDisplay()
        val rig = SmartTestRig(panelVisible = true, continueDialogue = false, cooldownNanos = 0L)
        rig.activate(overrides = mapOf(Capability.ConversationDisplay to display))

        rig.model.onRequest = { request, listener -> streamText(request, listener, "答案。") }
        rig.plugin.handle(rig.wakeEvent())
        rig.plugin.handle(TranscriptEvent(rig.transcript("问题")))
        rig.tick()
        val firstTurn = rig.output.hints.single().sourceTurnId
        rig.plugin.handle(OutputStateEvent(rig.envelope(), rig.output.hints.single().hintId, OutputStatus.Displayed))
        assertEquals(1, rig.scheduler.pending(), "the continue-off close is armed")

        display.onClosePanel = {
            display.onClosePanel = null
            // This is the concurrent wake: it runs inside the close decision, before the queued `cancelTurn`.
            rig.plugin.handle(rig.wakeEvent())
        }
        rig.tick(1_000)
        assertEquals(1, display.closes.size)
        val secondTurn = display.states.last().turnId
        assertNotEquals(firstTurn, secondTurn, "the concurrent wake opened a new turn")

        // The new turn must still own the machine: its transcript reaches the model under its own turn id.
        rig.model.onRequest = { request, listener -> streamText(request, listener, "答案。") }
        val requestsBefore = rig.model.requests.size
        rig.plugin.handle(TranscriptEvent(rig.transcript("再问一句", turnId = "asr-second")))
        rig.tick()
        assertEquals(requestsBefore + 1, rig.model.requests.size,
            "a close queued for the old turn must not detach the concurrent wake")
        assertEquals("asr-second", rig.model.requests.last().turnId)
        assertTrue(rig.cancels.none { it.third == secondTurn }, "the new turn's output was never cancelled")
    }

    // ------------------------------------------------- 0.24 S3 Task 14 (I1): notification answers and terminal output

    /**
     * A model answer that reaches the wearer through `notify_user` and therefore is emitted **while the run is still
     * `Running`** - the production answer shape (spec §6.1), and the one Task 12's `phase == Idle` check could not see.
     */
    private fun notifyAnswer(rig: SmartTestRig, channel: String = "screen") {
        var requests = 0
        rig.model.onRequest = { request, listener ->
            if (requests++ == 0) {
                listener(smartToolCall("c1", "notify_user", """{"text":"答案","channel":"$channel"}"""))
            } else {
                // The tool round: a normal closing answer. `submitOutcome` suppresses it because the notification
                // already reached the wearer, so it must not create a second hint.
                listener(ModelStreamEvent.TextDelta(request.requestId, "完成"))
            }
            listener(ModelStreamEvent.Terminal(request.requestId, ModelEnd.COMPLETED, null, null))
        }
    }

    /**
     * Two `notify_user` calls in **one** run (spec §6.3 supports a task-start and a task-done notice).
     *
     * Both are emitted under the turn's one hint id, so this is the production shape that used to make the second
     * emit's arbiter replacement look like a rejection of the answer itself (fix round C1).
     */
    private fun twoNotifyAnswers(rig: SmartTestRig) {
        var requests = 0
        rig.model.onRequest = { request, listener ->
            if (requests++ == 0) {
                listener(smartToolCall("c1", "notify_user", """{"text":"第一句","channel":"screen"}"""))
                listener(smartToolCall("c2", "notify_user", """{"text":"第二句","channel":"screen"}"""))
            } else {
                listener(ModelStreamEvent.TextDelta(request.requestId, "完成"))
            }
            listener(ModelStreamEvent.Terminal(request.requestId, ModelEnd.COMPLETED, null, null))
        }
    }

    @Test fun `S3 a notification answer emitted while running continues at its display ACK`() {
        val (rig, display) = cycleRig(panelVisible = true, continueDialogue = true)
        notifyAnswer(rig, channel = "screen")

        rig.plugin.handle(rig.wakeEvent())
        rig.plugin.handle(TranscriptEvent(rig.transcript("问题")))
        rig.tick()
        val hintId = rig.output.hints.single().hintId
        val requestsAfterRun = rig.model.requests.size
        assertTrue(OfficialStatus.AUDIO_FINISHED.wire !in display.statuses,
            "a notify_user answer must not continue before its display is acknowledged")

        rig.plugin.handle(OutputStateEvent(rig.envelope(), hintId, OutputStatus.Displayed))

        assertEquals(OfficialStatus.AUDIO_FINISHED.wire, display.statuses[display.statuses.size - 2])
        assertEquals(OfficialStatus.LISTENING.wire, display.statuses.last(), "the notification answer settles once")
        assertEquals(1, display.statuses.count { it == OfficialStatus.AUDIO_FINISHED.wire })
        assertEquals(2, display.count("working:WAKE"), "the continuation opened exactly one new round")
        assertEquals(requestsAfterRun, rig.model.requests.size, "the next round waits for the wearer")

        // A duplicate ACK cannot continue a second time.
        rig.plugin.handle(OutputStateEvent(rig.envelope(), hintId, OutputStatus.Displayed))
        assertEquals(1, display.statuses.count { it == OfficialStatus.AUDIO_FINISHED.wire })
    }

    @Test fun `S3 an expired notification answer closes without a continuation or status five`() {
        val (rig, display) = cycleRig(panelVisible = true, continueDialogue = true)
        notifyAnswer(rig, channel = "screen")

        rig.plugin.handle(rig.wakeEvent())
        rig.plugin.handle(TranscriptEvent(rig.transcript("问题")))
        rig.tick()
        val hintId = rig.output.hints.single().hintId
        val requestsAfterRun = rig.model.requests.size

        // The arbiter's deadline fires before the glasses ever acknowledge the display.
        rig.plugin.handle(OutputStateEvent(rig.envelope(), hintId, OutputStatus.Expired))

        assertEquals(listOf(AssistantRoute.WAKE), display.closes, "an answer nobody saw closes the panel")
        assertTrue(OfficialStatus.AUDIO_FINISHED.wire !in display.statuses)
        assertEquals(requestsAfterRun, rig.model.requests.size, "there is no next round to open")
        assertEquals(1, rig.cancels.size, "the turn behind the missing answer is cancelled")
        assertTrue(rig.hintTexts.isNotEmpty(), "the hint was submitted; it simply never reached the wearer")
    }

    @Test fun `S3 a late display ACK after the hint expired closes without a continuation`() {
        val (rig, display) = cycleRig(panelVisible = true, continueDialogue = true)
        notifyAnswer(rig, channel = "screen")

        rig.plugin.handle(rig.wakeEvent())
        rig.plugin.handle(TranscriptEvent(rig.transcript("问题")))
        rig.tick()
        val hintId = rig.output.hints.single().hintId
        val requestsAfterRun = rig.model.requests.size

        // The ACK arrives after the hint's own display window: it can no longer be honoured as "displayed".
        rig.clock.advance(6_000_000_000L)
        rig.plugin.handle(OutputStateEvent(rig.envelope(), hintId, OutputStatus.Displayed))

        assertEquals(listOf(AssistantRoute.WAKE), display.closes)
        assertTrue(OfficialStatus.AUDIO_FINISHED.wire !in display.statuses)
        assertEquals(requestsAfterRun, rig.model.requests.size)
        assertEquals(1, display.closes.size, "a repeated late ACK must not close twice")
    }

    @Test fun `S3 a rejected notification answer closes without a continuation`() {
        val (rig, display) = cycleRig(panelVisible = true, continueDialogue = true)
        rig.output.submission = OutputSubmission.Rejected
        notifyAnswer(rig, channel = "screen")

        rig.plugin.handle(rig.wakeEvent())
        rig.plugin.handle(TranscriptEvent(rig.transcript("问题")))
        rig.tick()
        val requestsAfterRun = rig.model.requests.size

        assertEquals(listOf(AssistantRoute.WAKE), display.closes)
        assertTrue(OfficialStatus.AUDIO_FINISHED.wire !in display.statuses)
        assertEquals(requestsAfterRun, rig.model.requests.size)
        assertEquals(1, rig.cancels.size)
    }

    @Test fun `S3 a silent answer closes without a continuation`() {
        val (rig, display) = cycleRig(panelVisible = true, continueDialogue = true)
        notifyAnswer(rig, channel = "silent")

        rig.plugin.handle(rig.wakeEvent())
        rig.plugin.handle(TranscriptEvent(rig.transcript("问题")))
        rig.tick()
        val requestsAfterRun = rig.model.requests.size

        assertTrue(rig.hintTexts.isEmpty(), "a silent answer is never displayed")
        assertEquals(listOf(AssistantRoute.WAKE), display.closes, "there is nothing to continue, so close")
        assertTrue(OfficialStatus.AUDIO_FINISHED.wire !in display.statuses)
        assertEquals(requestsAfterRun, rig.model.requests.size)
        assertEquals(1, rig.cancels.size)
    }

    @Test fun `S3 a spoken answer whose speech end never arrives closes at the bounded wait`() {
        val (rig, display) = cycleRig(panelVisible = true, continueDialogue = true)
        notifyAnswer(rig, channel = "both")

        rig.plugin.handle(rig.wakeEvent())
        rig.plugin.handle(TranscriptEvent(rig.transcript("问题")))
        rig.tick()
        val hintId = rig.output.hints.single().hintId
        val requestsAfterRun = rig.model.requests.size
        assertNotNull(rig.output.hints.single().optionalSpeech)

        rig.plugin.handle(OutputStateEvent(rig.envelope(), hintId, OutputStatus.Displayed))
        assertTrue(OfficialStatus.AUDIO_FINISHED.wire !in display.statuses, "the speech is still pending")

        // The answer's display window is 5 s from the emit, and the terminal bound is that window plus
        // SPEECH_LIVENESS_MILLIS (120 s, R106 - no longer the official 1 200 ms cue cadence): the emit happened 200 ms
        // before this point (the tool call runs inline), so 124_800 ms remain from the acknowledgement.
        rig.tick(124_799)
        assertTrue(display.closes.isEmpty(), "the answer's display window plus the liveness wait is still open: closes=${display.closes}")
        rig.tick(1)

        assertEquals(listOf(AssistantRoute.WAKE), display.closes, "a missing speech end closes, it never hangs")
        assertTrue(OfficialStatus.AUDIO_FINISHED.wire !in display.statuses)
        assertEquals(requestsAfterRun, rig.model.requests.size, "no next round without a confirmable speech end")
        assertEquals(1, rig.cancels.size)
    }

    @Test fun `S3 a duplicated SpeechFinished does not continue the conversation twice`() {
        val (rig, display) = cycleRig(panelVisible = true, continueDialogue = true)
        notifyAnswer(rig, channel = "both")

        rig.plugin.handle(rig.wakeEvent())
        rig.plugin.handle(TranscriptEvent(rig.transcript("问题")))
        rig.tick()
        val hintId = rig.output.hints.single().hintId
        rig.plugin.handle(OutputStateEvent(rig.envelope(), hintId, OutputStatus.Displayed))

        rig.plugin.handle(SpeechFinished(rig.envelope(), hintId, SpeechResult.FINISHED))
        rig.plugin.handle(SpeechFinished(rig.envelope(), hintId, SpeechResult.FINISHED))

        assertEquals(1, display.statuses.count { it == OfficialStatus.AUDIO_FINISHED.wire })
        assertEquals(2, display.count("working:WAKE"), "exactly one new round was opened")
    }

    @Test fun `S3 a one character final answer that assembles nothing closes at the fallback bound`() {
        val (rig, display) = cycleRig(panelVisible = true, continueDialogue = true)
        // `ReadableHintAssembler` intentionally drops a sentence whose content is shorter than two code points, so a
        // one-character terminal answer emits **no** hint at all. Without a liveness bound the cycle would sit at the
        // pre-answer status forever: run end set nothing and no timer was armed (review I2).
        rig.model.onRequest = { request, listener -> streamText(request, listener, "好。") }

        rig.plugin.handle(rig.wakeEvent())
        rig.plugin.handle(TranscriptEvent(rig.transcript("问题")))
        rig.tick() // the assembler's 150 ms merge runs and drops the sentence
        assertTrue(rig.hintTexts.isEmpty(), "the assembler really did emit nothing")

        rig.tick(799)
        assertTrue(display.closes.isEmpty(), "the fallback bound is one second, not immediate")
        rig.tick(1)

        assertEquals(listOf(AssistantRoute.WAKE), display.closes, "a fallback that emits nothing must not hang")
        assertTrue(OfficialStatus.AUDIO_FINISHED.wire !in display.statuses, "there is no displayed answer to continue")
        assertEquals(1, rig.cancels.size, "the turn behind the missing answer is cancelled")
    }

    // ------------------------------------------------- 0.24 S3 fix round C1: per-emit identity

    @Test fun `S3 a superseded notify emit's rejection does not settle the final answer`() {
        val (rig, display) = cycleRig(panelVisible = true, continueDialogue = true)
        twoNotifyAnswers(rig)

        rig.plugin.handle(rig.wakeEvent())
        rig.plugin.handle(TranscriptEvent(rig.transcript("帮我处理一下")))
        rig.tick()

        assertEquals(2, rig.output.hints.size)
        val first = rig.output.hints[0]
        val second = rig.output.hints[1]
        assertEquals(first.hintId, second.hintId, "both emits are one conversation entry")
        assertNotNull(first.emitId, "every emit carries its own identity")
        assertNotEquals(first.emitId, second.emitId)

        // The real arbiter's replacement terminal for the superseded emit, delivered before the final answer's ACK.
        rig.plugin.handle(OutputStateEvent(rig.envelope(), first.hintId, OutputStatus.Rejected, first.emitId))
        assertTrue(display.closes.isEmpty(), "a superseded emit's rejection is not the final answer's terminal")

        rig.plugin.handle(OutputStateEvent(rig.envelope(), second.hintId, OutputStatus.Displayed, second.emitId))
        assertEquals(OfficialStatus.AUDIO_FINISHED.wire, display.statuses[display.statuses.size - 2])
        assertEquals(OfficialStatus.LISTENING.wire, display.statuses.last(), "the final answer continues")
        assertTrue(display.closes.isEmpty())
    }

    @Test fun `S3 a SpeechFinished for a superseded emit cannot satisfy the current answer's speech wait`() {
        val (rig, display) = cycleRig(panelVisible = true, continueDialogue = true)
        notifyAnswer(rig, channel = "both")

        rig.plugin.handle(rig.wakeEvent())
        rig.plugin.handle(TranscriptEvent(rig.transcript("问题")))
        rig.tick()
        val answer = rig.output.hints.single()
        assertNotNull(answer.emitId)

        // A playback end for an earlier emit of the same turn entry (same hint id, different emit id). Before the
        // fix it matched by hint id and was stored as `earlySpeech`.
        rig.plugin.handle(SpeechFinished(rig.envelope(), answer.hintId, SpeechResult.FINISHED, "emit-superseded"))
        rig.plugin.handle(OutputStateEvent(rig.envelope(), answer.hintId, OutputStatus.Displayed, answer.emitId))

        assertTrue(
            OfficialStatus.AUDIO_FINISHED.wire !in display.statuses,
            "the current answer's speech is still pending; the stale result must not settle it",
        )

        rig.plugin.handle(SpeechFinished(rig.envelope(), answer.hintId, SpeechResult.FINISHED, answer.emitId))
        assertEquals(OfficialStatus.AUDIO_FINISHED.wire, display.statuses[display.statuses.size - 2])
        assertEquals(OfficialStatus.LISTENING.wire, display.statuses.last())
    }

    // ------------------------------------------------- 0.24 S3 fix round C2: speech off

    @Test fun `S3 speech off makes a both answer display only and continues on its display ACK`() {
        val (rig, display) = cycleRig(panelVisible = true, continueDialogue = true, speechAvailable = false)
        notifyAnswer(rig, channel = "both")

        rig.plugin.handle(rig.wakeEvent())
        rig.plugin.handle(TranscriptEvent(rig.transcript("问题")))
        rig.tick()
        val answer = rig.output.hints.single()
        assertNull(answer.optionalSpeech, "speech off: the answer must not ask the host to speak")

        rig.plugin.handle(OutputStateEvent(rig.envelope(), answer.hintId, OutputStatus.Displayed, answer.emitId))

        assertEquals(OfficialStatus.AUDIO_FINISHED.wire, display.statuses[display.statuses.size - 2])
        assertEquals(OfficialStatus.LISTENING.wire, display.statuses.last(), "the answer settles on Displayed")
        assertTrue(display.closes.isEmpty())
    }

    @Test fun `S3 speech off makes a voice answer display only and continues on its display ACK`() {
        val (rig, display) = cycleRig(panelVisible = true, continueDialogue = true, speechAvailable = false)
        notifyAnswer(rig, channel = "voice")

        rig.plugin.handle(rig.wakeEvent())
        rig.plugin.handle(TranscriptEvent(rig.transcript("问题")))
        rig.tick()
        val answer = rig.output.hints.single()
        assertNull(answer.optionalSpeech)

        rig.plugin.handle(OutputStateEvent(rig.envelope(), answer.hintId, OutputStatus.Displayed, answer.emitId))

        assertEquals(OfficialStatus.AUDIO_FINISHED.wire, display.statuses[display.statuses.size - 2])
        assertEquals(OfficialStatus.LISTENING.wire, display.statuses.last())
    }

    @Test fun `S3 speech off with continue off still closes one second after the display ACK`() {
        val (rig, display) = cycleRig(panelVisible = true, continueDialogue = false, speechAvailable = false)
        notifyAnswer(rig, channel = "both")

        rig.plugin.handle(rig.wakeEvent())
        rig.plugin.handle(TranscriptEvent(rig.transcript("问题")))
        rig.tick()
        val answer = rig.output.hints.single()
        assertNull(answer.optionalSpeech)
        val requestsAfterRun = rig.model.requests.size

        rig.plugin.handle(OutputStateEvent(rig.envelope(), answer.hintId, OutputStatus.Displayed, answer.emitId))
        assertTrue(OfficialStatus.AUDIO_FINISHED.wire !in display.statuses, "continue off never sends status 5")

        rig.tick(999)
        assertTrue(display.closes.isEmpty(), "the official scheduleExit(1000) is not immediate")
        rig.tick(1)
        assertEquals(listOf(AssistantRoute.WAKE), display.closes)
        assertEquals(requestsAfterRun, rig.model.requests.size, "continue off opens no next round")
    }
}
