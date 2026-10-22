package com.sheen.rokid.host

import com.sheen.rokid.contracts.*
import com.sheen.rokid.plugin.*
import kotlin.test.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class HostEventDispatcherTest {
    @Test fun `overflow closes admission without waiting for active storage operation`() {
        val rig = HostTestRig()
        val inRead = CountDownLatch(1)
        val releaseRead = CountDownLatch(1)
        val inHandle = CountDownLatch(1)
        val releaseHandle = CountDownLatch(1)
        val overflowDone = CountDownLatch(1)
        val storage = object : StorageHandle {
            override fun read(key: String): ByteArray? {
                inRead.countDown()
                check(releaseRead.await(5, TimeUnit.SECONDS))
                return null
            }
            override fun write(key: String, value: ByteArray) = Unit
            override fun delete(key: String) = Unit
            override fun clear() = Unit
        }
        val env = object : ScheduledHostEnvironment by rig.environment {
            override val availableCapabilities = setOf(Capability.Storage)
            override fun services(scope: ResourceScope, granted: Set<Capability>): HostServices =
                ScopedHostServices(scope, granted, mapOf(Capability.Storage to storage), com.sheen.rokid.testsupport.FakeTaskScope())
        }
        val host = HostRuntime(1, env)
        val plugin = object : PluginLifecycle {
            override val descriptor = PluginDescriptor("store", "1", 1, "store", listOf(Capability.Storage), ConfigSchema(1))
            lateinit var context: PluginContext
            override fun activate(context: PluginContext) { this.context = context }
            override fun handle(event: HostEvent) {
                if (event.envelope.eventId == "first") {
                    inHandle.countDown()
                    check(releaseHandle.await(5, TimeUnit.SECONDS))
                }
            }
            override fun deactivate(reason: DeactivationReason) = Unit
        }
        host.register(plugin); host.startSession(SessionId("s")); host.markSceneReady()
        assertEquals(ActivationResult.Activated, host.activate("store"))
        val generation = host.currentGeneration
        fun event(id: String) = UserAction(EventEnvelope(SessionId("s"), generation, id, 0, 1), UserAction.Action.Help)
        assertTrue(host.onEvent(event("first")))
        assertTrue(inHandle.await(5, TimeUnit.SECONDS))
        val reading = thread(isDaemon = true) { plugin.context.services.storage().read("key") }
        assertTrue(inRead.await(5, TimeUnit.SECONDS))
        val producer = thread(isDaemon = true) {
            repeat(32) { check(host.onEvent(event("queued-$it"))) }
            check(!host.onEvent(event("overflow")))
            overflowDone.countDown()
        }
        try {
            assertTrue(overflowDone.await(2, TimeUnit.SECONDS), "overflow waited on storage provider")
            assertFalse(host.onEvent(event("late")))
        } finally {
            releaseRead.countDown(); releaseHandle.countDown()
            reading.join(5000); producer.join(5000)
        }
        assertEquals(SessionState.Interrupted, host.currentState)
        assertFailsWith<ContractError.Cancelled> { plugin.context.services.storage().read("late") }
    }

    @Test fun `critical overflow closes host delivery before queued drain resumes`() {
        val rig = HostTestRig()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val plugin = object : PluginLifecycle {
            override val descriptor = PluginDescriptor("blocked", "1", 1, "blocked", listOf(Capability.Audio), ConfigSchema(1))
            lateinit var context: PluginContext
            val delivered = mutableListOf<String>()
            override fun activate(context: PluginContext) { this.context = context }
            override fun handle(event: HostEvent) {
                delivered += event.envelope.eventId
                if (event.envelope.eventId == "first") {
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                }
            }
            override fun deactivate(reason: DeactivationReason) = Unit
        }
        rig.host.register(plugin)
        assertEquals(ActivationResult.Activated, rig.host.activate("blocked"))
        val generation = rig.host.currentGeneration
        fun event(id: String) = UserAction(EventEnvelope(SessionId("s"), generation, id, 0, 1), UserAction.Action.Help)
        assertTrue(rig.host.onEvent(event("first")))
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        try {
            repeat(32) { assertTrue(rig.host.onEvent(event("queued-$it"))) }
            assertFalse(rig.host.onEvent(event("overflow")))
            assertFalse(rig.host.onEvent(event("late")))
            assertFailsWith<ContractError.Cancelled> { plugin.context.services.audio().listen {} }
        } finally { release.countDown() }
        assertEquals(SessionState.Interrupted, rig.host.currentState)
        assertEquals(listOf("first"), plugin.delivered)
    }

    @Test fun `critical overflow returns before slow provider cancellation`() {
        val rig = HostTestRig()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val cancelling = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        val blocked = CountDownLatch(1)
        val provider = object : AudioSubscription {
            override fun listen(listener: (AudioSegment) -> Unit) = object : CancellationHandle {
                override fun cancel() {
                    cancelling.countDown()
                    check(cancelled.await(5, TimeUnit.SECONDS))
                }
            }
        }
        val env = object : ScheduledHostEnvironment by rig.environment {
            override fun services(scope: ResourceScope, granted: Set<Capability>): HostServices =
                ScopedHostServices(scope, granted, mapOf(Capability.Audio to provider), com.sheen.rokid.testsupport.FakeTaskScope())
        }
        val host = HostRuntime(1, env)
        val plugin = object : PluginLifecycle {
            override val descriptor = PluginDescriptor("slow", "1", 1, "slow", listOf(Capability.Audio), ConfigSchema(1))
            lateinit var context: PluginContext
            override fun activate(context: PluginContext) { this.context = context; context.services.audio().listen {} }
            override fun handle(event: HostEvent) {
                if (event.envelope.eventId == "first") {
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                }
            }
            override fun deactivate(reason: DeactivationReason) = Unit
        }
        host.register(plugin); host.startSession(SessionId("s")); host.markSceneReady()
        assertEquals(ActivationResult.Activated, host.activate("slow"))
        val generation = host.currentGeneration
        fun event(id: String) = UserAction(EventEnvelope(SessionId("s"), generation, id, 0, 1), UserAction.Action.Help)
        assertTrue(host.onEvent(event("first")))
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        val producer = thread(isDaemon = true) {
            repeat(32) { check(host.onEvent(event("queued-$it"))) }
            check(!host.onEvent(event("overflow")))
            blocked.countDown()
        }
        try {
            assertTrue(blocked.await(2, TimeUnit.SECONDS), "overflow waited for provider cancellation")
            assertFalse(host.onEvent(event("late")))
            assertFailsWith<ContractError.Cancelled> { plugin.context.services.audio().listen {} }
        } finally {
            release.countDown()
            cancelled.countDown()
            producer.join(5000)
        }
        assertTrue(cancelling.await(5, TimeUnit.SECONDS))
        assertEquals(SessionState.Interrupted, host.currentState)
    }

    private fun transcript(revision: Long, final: Boolean = false, generation: Generation = Generation(0)) = TranscriptEvent(TranscriptRevision(
        EventEnvelope(SessionId("s"), generation, "e$revision", 0, 10), "t", revision, "test", final, 0, 10))

    @Test fun `full queue reports failure without replacing event`() {
        val queue = BoundedEventChannel<String>(1)
        assertTrue(queue.offer("final"))
        assertFalse(queue.offer("next-final"))
        assertEquals("final", queue.poll())
    }

    @Test fun `partial coalesces but final seals turn and duplicate is rejected`() {
        val delivered = mutableListOf<HostEvent>()
        val dispatcher = HostEventDispatcher(2, delivered::add) { fail("unexpected overflow") }
        assertTrue(dispatcher.offer(transcript(1)))
        assertTrue(dispatcher.offer(transcript(2)))
        assertTrue(dispatcher.offer(transcript(3, true)))
        assertFalse(dispatcher.offer(transcript(4)))
        assertFalse(dispatcher.offer(transcript(3, true)))
        dispatcher.drain()
        assertEquals(listOf(3L), delivered.filterIsInstance<TranscriptEvent>().map { it.revision.revision })
    }

    @Test fun `critical overflow produces observable fault and keeps previous event`() {
        val delivered = mutableListOf<HostEvent>()
        val faults = mutableListOf<HostEvent>()
        val dispatcher = HostEventDispatcher(1, delivered::add, faults::add)
        val first = transcript(1, true)
        val state = SessionStateEvent(EventEnvelope(SessionId("s"), Generation(0), "state", 0, 1), SessionState.Paused)
        assertTrue(dispatcher.offer(first))
        assertFalse(dispatcher.offer(state))
        dispatcher.drain()
        assertEquals(listOf<HostEvent>(first), delivered)
        assertEquals(listOf(GapReason.OVERFLOW), faults.filterIsInstance<DiscontinuityEvent>().map { it.reason })
    }

    @Test fun `host event delivery filters capabilities and old generation`() {
        val rig = HostTestRig()
        val text = ProbePlugin("text", listOf(Capability.TextOutput))
        rig.host.register(text); rig.host.activate("text")
        val current = transcript(1, generation = rig.host.currentGeneration)
        rig.host.onEvent(current)
        rig.host.onEvent(SpeakerEvent(SpeakerAssessment(current.envelope, SpeakerLabel.UNKNOWN, null, 0, 10)))
        rig.host.onEvent(UserAction(current.envelope.copy(eventId = "help"), UserAction.Action.Help))
        assertEquals(SessionState.Ready, rig.host.currentState)
        assertEquals(listOf(UserAction.Action.Help), text.events.filterIsInstance<UserAction>().map { it.action })
        assertTrue(text.events.none { it is TranscriptEvent || it is SpeakerEvent })
        rig.host.pause(); rig.host.resume()
        assertFalse(rig.host.onEvent(UserAction(current.envelope.copy(eventId = "old"), UserAction.Action.Help)))
        rig.host.currentState
        assertEquals(1, text.events.filterIsInstance<UserAction>().size)
    }

    @Test fun `concurrent producers never exceed queue capacity`() {
        val queue = BoundedEventChannel<Int>(4)
        val start = java.util.concurrent.CountDownLatch(1)
        val done = java.util.concurrent.CountDownLatch(4)
        val accepted = java.util.concurrent.atomic.AtomicInteger()
        repeat(4) { producer ->
            kotlin.concurrent.thread {
                check(start.await(5, java.util.concurrent.TimeUnit.SECONDS))
                repeat(100) { if (queue.offer(producer * 100 + it)) accepted.incrementAndGet() }
                done.countDown()
            }
        }
        start.countDown()
        assertTrue(done.await(5, java.util.concurrent.TimeUnit.SECONDS))
        assertEquals(4, accepted.get())
        assertEquals(4, queue.size())
        assertEquals(4, generateSequence { queue.poll() }.count())
    }
}
