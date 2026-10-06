package edith.host

import edith.contracts.*
import edith.plugin.*
import kotlin.test.*

class HostEventDispatcherTest {
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
