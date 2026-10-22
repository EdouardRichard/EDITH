package com.sheen.rokid.host

import com.sheen.rokid.contracts.*
import com.sheen.rokid.plugin.*
import com.sheen.rokid.testsupport.*
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.*

/**
 * 0.24 S3 Task 14: the host's `SpeechFinished` event.
 *
 * Spec §5.5: the host emits `SpeechFinished(session, generation, hintId, result)` when the phone's playback of a
 * displayed answer ends, and the plugin decides whether the next listening round may start. The arbiter only reports
 * what really played ([OutputArbiterSpeechTest]); this class pins the event the plugin receives: the **live** identity,
 * the hint that was on screen, and nothing for a hint that never reached the display or whose session is gone.
 */
class HostRuntimeSpeechTest {

    private class ControlledSpeechPlayer : SpeechPlayer {
        val played = mutableListOf<String>()
        private val callbacks = mutableMapOf<String, (SpeechPlayback) -> Unit>()
        override fun play(assistance: Assistance, onState: (SpeechPlayback) -> Unit): CancellationHandle {
            played += assistance.hintId
            callbacks[assistance.hintId] = onState
            onState(SpeechPlayback.STARTED)
            return FakeCancellation()
        }
        fun terminal(hintId: String, state: SpeechPlayback) { callbacks.getValue(hintId)(state) }
    }

    private class SpeechProbe : PluginLifecycle {
        override val descriptor = PluginDescriptor(
            "speech-probe", "1", 1, "Speech probe", listOf(Capability.TextOutput), ConfigSchema(1),
        )
        val events = CopyOnWriteArrayList<HostEvent>()
        @Volatile var context: PluginContext? = null
        override fun activate(context: PluginContext) { this.context = context }
        override fun handle(event: HostEvent) { events += event }
        override fun deactivate(reason: DeactivationReason) = Unit
    }

    private class Rig {
        val clock = FakeClock()
        val scheduler = ManualScheduler(clock)
        val device = RecordingHintDevice()
        val speech = ControlledSpeechPlayer()
        val probe = SpeechProbe()
        val host: HostRuntime

        init {
            val environment = object : ScheduledHostEnvironment {
                override val clock get() = this@Rig.clock
                override val input = RecordingInputPort()
                override val output get() = this@Rig.device
                override val scheduler get() = this@Rig.scheduler
                override val speech get() = this@Rig.speech
                override val availableCapabilities = setOf(Capability.TextOutput)
                override fun services(scope: ResourceScope, granted: Set<Capability>): HostServices =
                    ScopedHostServices(
                        scope,
                        granted,
                        mapOf(Capability.TextOutput to FakeGlassesOutput()),
                        FakeTaskScope(),
                    )
            }
            host = HostRuntime(1, environment)
            host.register(probe)
            host.startSession(SessionId("s"))
            host.markSceneReady()
            assertEquals(ActivationResult.Activated, host.activate(probe.descriptor.pluginId))
        }

        fun submit(hintId: String, speech: Boolean = true, priority: Int = 0, emitId: String? = "emit-$hintId"): OutputSubmission {
            val now = clock.nowNanos()
            val assistance = Assistance(
                hintId = hintId,
                sessionId = SessionId("s"),
                generation = host.currentGeneration,
                sourceTurnId = "turn-1",
                priority = priority,
                text = "text-$hintId",
                optionalSpeech = if (speech) SpeechRequest() else null,
                expiresAtNanos = now + 5_000_000_000L,
                emitId = emitId,
            )
            return probe.context!!.services.textOutput().submit(assistance)
        }

        fun finish(hintId: String, state: SpeechPlayback = SpeechPlayback.FINISHED) = speech.terminal(hintId, state)

        /** Waits for the event the host emits after the arbiter's speech callback (both run on their own threads). */
        fun awaitSpeech(timeoutMillis: Long = 2_000): SpeechFinished? {
            val deadline = System.nanoTime() + timeoutMillis * 1_000_000L
            while (System.nanoTime() < deadline) {
                probe.events.filterIsInstance<SpeechFinished>().firstOrNull()?.let { return it }
                Thread.sleep(5)
            }
            return null
        }

        /** Waits for any host event of [T] that satisfies [predicate]. */
        inline fun <reified T : HostEvent> awaitEvent(
            timeoutMillis: Long = 2_000,
            predicate: (T) -> Boolean,
        ): T? {
            val deadline = System.nanoTime() + timeoutMillis * 1_000_000L
            while (System.nanoTime() < deadline) {
                probe.events.filterIsInstance<T>().firstOrNull(predicate)?.let { return it }
                Thread.sleep(5)
            }
            return null
        }
    }

    @Test fun `a finished playback becomes a SpeechFinished for the live session and hint`() {
        val rig = Rig()
        assertEquals(OutputSubmission.Accepted, rig.submit("hint-1"))
        assertEquals(listOf("hint-1"), rig.speech.played)
        val generation = rig.host.currentGeneration

        rig.finish("hint-1", SpeechPlayback.FINISHED)
        val event = rig.awaitSpeech()

        assertNotNull(event, "the host must emit SpeechFinished when the played answer's speech ends")
        assertEquals("hint-1", event.hintId)
        assertEquals(SpeechResult.FINISHED, event.result)
        assertEquals(SessionId("s"), event.envelope.sessionId)
        assertEquals(generation, event.envelope.generation, "the event carries the session identity it was played under")
        assertEquals("emit-hint-1", event.emitId, "the event carries the per-emit identity (fix C1)")
    }

    @Test fun `a display event carries the emit identity of the hint it belongs to`() {
        val rig = Rig()
        rig.submit("hint-1", speech = false, emitId = "emit-hint-1")
        rig.device.callbacks.last()(OutputStatus.Displayed)

        val displayed = rig.awaitEvent<OutputStateEvent>(150) {
            it.hintId == "hint-1" && it.status == OutputStatus.Displayed
        }

        assertNotNull(displayed, "the host must forward the display acknowledgement")
        assertEquals("emit-hint-1", displayed.emitId, "a terminal/ACK must name the emit, not only the turn entry")
    }

    @Test fun `a failed playback becomes SpeechFinished with FAILED`() {
        val rig = Rig()
        rig.submit("hint-1")

        rig.finish("hint-1", SpeechPlayback.FAILED)
        val event = rig.awaitSpeech()

        assertNotNull(event)
        assertEquals(SpeechResult.FAILED, event.result)
        assertEquals("hint-1", event.hintId)
    }

    @Test fun `no event for a hint that carried no speech`() {
        val rig = Rig()
        rig.submit("plain", speech = false)

        assertNull(rig.awaitSpeech(150), "there was no playback, so there is nothing to finish")
    }

    @Test fun `no event for a hint that never reached the screen`() {
        val rig = Rig()
        // A higher-priority hint owns the screen; the second one is queued and never spoken.
        rig.submit("high", speech = true, priority = 2)
        assertEquals(OutputSubmission.Accepted, rig.submit("queued", speech = true, priority = 0))
        assertEquals(listOf("high"), rig.device.shown.map { it.first.hintId })
        assertFalse("queued" in rig.speech.played, "a queued hint is not played, so it has no playback to finish")

        rig.finish("high", SpeechPlayback.FINISHED)
        val event = rig.awaitSpeech()
        assertNotNull(event)
        assertEquals("high", event.hintId)
        assertTrue(rig.probe.events.filterIsInstance<SpeechFinished>().none { it.hintId == "queued" })
    }

    @Test fun `no event after the session ended`() {
        val rig = Rig()
        rig.submit("hint-1")
        rig.host.stop()

        rig.finish("hint-1", SpeechPlayback.FINISHED)

        assertNull(rig.awaitSpeech(150), "a playback that outlived its session must not drive a new conversation")
    }
}
