package com.sheen.rokid.phone.app
import com.sheen.rokid.contracts.*
import com.sheen.rokid.host.HostRuntime
import com.sheen.rokid.host.ResourceScope
import com.sheen.rokid.host.ScheduledHostEnvironment
import com.sheen.rokid.plugin.*
import com.sheen.rokid.testsupport.FakeClock
import com.sheen.rokid.testsupport.FakeTaskScope
import com.sheen.rokid.testsupport.ManualScheduler
import com.sheen.rokid.testsupport.RecordingHintDevice
import com.sheen.rokid.testsupport.RecordingInputPort
import org.junit.Assert.*
import org.junit.Test

/**
 * T19 fault-injection cases for the text loop (plan Step 1).
 *
 * Everything here runs against the real [HostRuntime] and its real output arbiter with **fake** transports, so
 * no audio, no network and no real service are involved. The fakes are what make the races reproducible: a
 * late result can be released at exactly the moment the test chooses, which is impossible against a real
 * service. These cases assert the fence rather than the happy path - work in flight when the user paused must
 * not become visible afterwards, and must be accounted as cancelled rather than silently dropped.
 *
 * They live in the JVM unit-test source set on purpose: nothing here needs a device, and as an instrumented test
 * they were only ever *written*, never run. A fence that has not been executed is not evidence.
 */
class P2TextLoopTest {
    @Test fun pauseFencesLateNetworkAndDisplayCallbacks() {
        val rig = AndroidTextLoopRig()
        try {
            rig.startAndManuallyRequest()
            val staleGeneration = rig.generation
            assertTrue("the rig must have a request in flight before the pause", rig.inFlightRequests > 0)

            rig.host.pause()

            // A result computed before the pause arrives after it. The pause bumped the generation, so the
            // host must refuse it without rendering anything.
            val late = rig.assistance(generation = staleGeneration, text = "stale answer")
            assertFalse("a result from the paused generation must be refused", rig.acceptAndReport(late))
            assertEquals("nothing may become visible after a pause", 0, rig.visibleHints)
            assertEquals("the refused request must be accounted as cancelled", 1, rig.cancelledRequests)

            // A display acknowledgement for that stale work must not resurrect it either.
            rig.releaseLateDisplayAck()
            assertEquals("a late display ack must not make stale work visible", 0, rig.visibleHints)
        } finally { rig.close() }
    }

    @Test fun resumeAllowsFreshWorkAndKeepsLateWorkRefused() {
        val rig = AndroidTextLoopRig()
        try {
            rig.startAndManuallyRequest()
            val staleGeneration = rig.generation
            rig.host.pause()
            rig.resume()

            val fresh = rig.assistance(generation = rig.generation, text = "fresh answer")
            assertTrue("fresh work in the current generation must be accepted", rig.acceptAndReport(fresh))
            assertEquals(1, rig.visibleHints)

            val late = rig.assistance(generation = staleGeneration, text = "stale answer")
            assertFalse("a stale generation must stay refused after resume", rig.acceptAndReport(late))
            assertEquals("refusing stale work must not disturb the visible hint", 1, rig.visibleHints)
        } finally { rig.close() }
    }

    @Test fun stopFencesEverythingAndClearsTheDisplay() {
        val rig = AndroidTextLoopRig()
        try {
            rig.startAndManuallyRequest()
            val staleGeneration = rig.generation
            assertTrue(rig.acceptAndReport(rig.assistance(generation = staleGeneration, text = "before stop")))
            assertEquals(1, rig.visibleHints)

            rig.host.stop()

            assertEquals("a stop must clear the glasses", 0, rig.visibleHints)
            assertFalse("work from before the stop must be refused",
                rig.acceptAndReport(rig.assistance(generation = staleGeneration, text = "after stop")))
            assertEquals(0, rig.visibleHints)
        } finally { rig.close() }
    }

    @Test fun switchingPluginsFencesThePreviousGenerationsWork() {
        val rig = AndroidTextLoopRig()
        try {
            rig.startAndManuallyRequest()
            val staleGeneration = rig.generation
            assertTrue(rig.acceptAndReport(rig.assistance(generation = staleGeneration, text = "first plugin")))
            assertEquals(1, rig.visibleHints)

            rig.host.switchTo("second")
            rig.refreshGeneration()

            assertEquals("switching plugins must clear the previous plugin's hint", 0, rig.visibleHints)
            assertFalse("the previous generation's work must be refused after a switch",
                rig.acceptAndReport(rig.assistance(generation = staleGeneration, text = "late first plugin")))
            assertTrue("the newly selected plugin must still accept current work",
                rig.acceptAndReport(rig.assistance(generation = rig.generation, text = "second plugin")))
            assertEquals(1, rig.visibleHints)
        } finally { rig.close() }
    }

    @Test fun anExpiredAssistanceIsNeverRendered() {
        val rig = AndroidTextLoopRig()
        try {
            rig.startAndManuallyRequest()
            // `Assistance` requires a non-negative deadline, so an already-expired hint is built with a real deadline
            // and then overtaken by the clock - which is exactly what a slow network produces in the field.
            val expired = rig.assistance(generation = rig.generation, text = "already stale", expiresInNanos = 1_000_000)
            rig.advance(2_000_000)
            assertFalse("an assistance whose deadline passed must be refused", rig.acceptAndReport(expired))
            // The earlier request is legitimately still on screen; what must never appear is the expired one.
            assertFalse("the expired hint must never reach the glasses",
                rig.device.shown.any { it.first.text == "already stale" })
            assertEquals("and it must not disturb what is already visible", 1, rig.visibleHints)
        } finally { rig.close() }
    }

    @Test fun anInterruptClearsAndBumpsTheGeneration() {
        val rig = AndroidTextLoopRig()
        try {
            rig.startAndManuallyRequest()
            val staleGeneration = rig.generation
            assertTrue(rig.acceptAndReport(rig.assistance(generation = staleGeneration, text = "before interrupt")))
            assertEquals(1, rig.visibleHints)

            rig.host.interrupt()
            rig.refreshGeneration()

            assertEquals("an interrupt must clear the glasses", 0, rig.visibleHints)
            assertFalse("work from the interrupted generation must be refused",
                rig.acceptAndReport(rig.assistance(generation = staleGeneration, text = "after interrupt")))
        } finally { rig.close() }
    }
}

/**
 * Reuses the real [HostRuntime] and its real output arbiter with fake transports, so the text loop's fencing
 * and cancellation behaviour can be driven deterministically. Nothing here touches audio, network or a real
 * service: [RecordingHintDevice] stands in for the glasses and [ManualScheduler] for the real clock.
 *
 * **The submissions go through the sink the host hands the plugin**, not through a sink of the rig's own.
 * `HostRuntime.accept()` is only a predicate - it answers whether an assistance *would* be accepted and never
 * reaches the arbiter - so a rig that submitted its own fake sink would leave the arbiter and the device
 * untouched and every "nothing became visible" assertion below would pass for the wrong reason. This was a real
 * defect in the harness, found only when the cases were moved somewhere they actually run.
 */
class AndroidTextLoopRig {
    val clock = FakeClock()
    val scheduler = ManualScheduler(clock)
    val input = RecordingInputPort()
    val device = RecordingHintDevice()
    private val pending = mutableListOf<Assistance>()
    private var cancelled = 0
    private var turn = 0
    private val sessionId = SessionId("s-11111111-2222-3333-4444-555555555555")

    var generation: Generation = Generation(0)
        private set

    /** The arbiter-backed sink the host gave the most recently activated plugin; null before the first activation. */
    var activeSink: OutputSink? = null
        private set

    private val capabilities = setOf(Capability.Audio, Capability.TextOutput)
    private val environment = object : ScheduledHostEnvironment {
        override val clock = this@AndroidTextLoopRig.clock
        override val input = this@AndroidTextLoopRig.input
        override val output: HintDevice = this@AndroidTextLoopRig.device
        override val scheduler = this@AndroidTextLoopRig.scheduler
        override val availableCapabilities = capabilities
        override fun services(scope: ResourceScope, granted: Set<Capability>) = object : HostServices {
            // The host substitutes its own arbiter-backed sink for a granted TextOutput and never uses this one; it
            // exists only because the contract requires a provider for every granted capability.
            override fun require(capability: Capability): Any =
                if (capability == Capability.TextOutput) object : OutputSink {
                    override fun submit(assistance: Assistance): OutputSubmission = OutputSubmission.Rejected
                } else error("unsupported: $capability")
            override val tasks: TaskScope = FakeTaskScope()
        }
    }

    val host = HostRuntime(1, environment)

    init {
        host.register(FakeTextPlugin("first", capabilities) { activeSink = it })
        host.register(FakeTextPlugin("second", capabilities) { activeSink = it })
        host.startSession(sessionId)
        host.markSceneReady()
        host.activate("first")
        refreshGeneration()
    }

    /** Requests work the way the app does, leaving one request in flight for a later late result. */
    fun startAndManuallyRequest() {
        turn++
        submit(assistance(generation = generation, text = "in flight"))
    }

    fun resume() {
        host.resume()
        refreshGeneration()
    }

    fun refreshGeneration() {
        generation = host.currentGeneration
    }

    fun advance(nanos: Long) {
        clock.nowNanos += nanos
    }

    /**
     * Routes a candidate result through the **host's** sink, exactly as a late network reply would: a plugin
     * submits what it computed, and the arbiter fences it. A refusal - or a submission through a scope the host has
     * already revoked - is the answer to the race being tested, so it is reported rather than thrown, and counted as
     * cancelled work rather than silently disappearing.
     */
    fun acceptAndReport(assistance: Assistance): Boolean {
        val accepted = submit(assistance) == OutputSubmission.Accepted
        if (accepted) {
            // The arbiter is synchronous on its own executor, so the device state is settled for these assertions.
            scheduler.runDue()
        } else {
            cancelled++
        }
        return accepted
    }

    /** Submits through the sink the host gave the active plugin; null when nothing has been activated. */
    private fun submit(assistance: Assistance): OutputSubmission? {
        val sink = activeSink ?: return null
        val result = runCatching { sink.submit(assistance) }.getOrNull() ?: return null
        // One in-flight request per hint; a real retry replaces the previous attempt.
        pending.removeAll { it.hintId == assistance.hintId }
        if (result == OutputSubmission.Accepted) pending += assistance
        return result
    }

    /** A display acknowledgement arriving after the fence; the arbiter must already have dropped the item. */
    fun releaseLateDisplayAck() {
        device.callbacks.toList().forEach { runCatching { it(OutputStatus.Displayed) } }
    }

    fun assistance(generation: Generation, text: String, expiresInNanos: Long = 60_000_000_000L) =
        Assistance(
            hintId = "hint-${turn}-${text.hashCode()}",
            sessionId = sessionId,
            generation = generation,
            sourceTurnId = "turn-$turn",
            priority = 1,
            text = text,
            optionalSpeech = null,
            expiresAtNanos = clock.nowNanos() + expiresInNanos,
        )

    /** Hints the glasses is currently displaying: shown, and not cleared for that session/generation. */
    val visibleHints: Int
        get() {
            val cleared = device.clears.toSet()
            return device.shown.count { (assistance, _) ->
                (assistance.sessionId to assistance.generation) !in cleared
            }
        }

    val inFlightRequests: Int get() = pending.size

    /** Requests the fence refused: accounted, never rendered. */
    val cancelledRequests: Int get() = cancelled

    fun close() {
        runCatching { host.close() }
    }
}

/**
 * Minimal plugin that declares the capabilities the rig provides.
 *
 * It captures the sink the host hands it in `activate`, because that sink - not any sink the rig builds - is the
 * only path into the real arbiter.
 */
private class FakeTextPlugin(id: String, capabilities: Set<Capability>,
    private val onActivated: (OutputSink) -> Unit) : PluginLifecycle {
    override val descriptor = PluginDescriptor(id, "1", 1, id, capabilities.toList(), ConfigSchema(1))
    val deactivations = mutableListOf<DeactivationReason>()
    override fun activate(context: PluginContext) {
        onActivated(context.services.require(Capability.TextOutput) as OutputSink)
    }
    override fun handle(event: HostEvent) = Unit
    override fun deactivate(reason: DeactivationReason) { deactivations += reason }
}
