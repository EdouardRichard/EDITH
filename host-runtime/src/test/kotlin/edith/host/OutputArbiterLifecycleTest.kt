package edith.host

import edith.contracts.*
import edith.plugin.*
import edith.testsupport.*
import kotlin.test.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class OutputArbiterLifecycleTest {
    @Test fun `device callback returns while show is blocked`() {
        val inShow = CountDownLatch(1)
        val releaseShow = CountDownLatch(1)
        val callbackDone = CountDownLatch(1)
        lateinit var callback: (OutputStatus) -> Unit
        val slowDevice = object : HintDevice {
            override fun show(assistance: Assistance, revision: Long, listener: (OutputStatus) -> Unit): CancellationHandle {
                callback = listener
                inShow.countDown()
                check(releaseShow.await(5, TimeUnit.SECONDS))
                return FakeCancellation()
            }
            override fun clear(sessionId: SessionId, generation: Generation) = Unit
        }
        val subject = OutputArbiter(clock, scheduler, slowDevice, { identity }) { _, _ -> }
        val submitter = thread(isDaemon = true) { subject.submit(hint("blocked")) }
        assertTrue(inShow.await(5, TimeUnit.SECONDS))
        val caller = thread(isDaemon = true) { try { callback(OutputStatus.Displayed) } finally { callbackDone.countDown() } }
        try { assertTrue(callbackDone.await(2, TimeUnit.SECONDS), "device callback waited for output owner") }
        finally { releaseShow.countDown(); caller.join(5000); submitter.join(5000) }
        subject.clear(SessionId("s"), Generation(1))
    }

    private val clock = FakeClock()
    private val scheduler = ManualScheduler(clock)
    private val device = RecordingHintDevice()
    private var identity: Pair<SessionId, Generation>? = SessionId("s") to Generation(1)
    private val states = mutableListOf<Pair<String, OutputStatus>>()
    private val arbiter = OutputArbiter(clock, scheduler, device, { identity }) { hint, status -> states += hint.hintId to status }
    private fun hint(id: String, priority: Int = 1, expiry: Long = 100) =
        Assistance(id, SessionId("s"), Generation(1), "turn-$id", priority, "test", null, expiry)

    @Test fun `expiry clears without another submission and ignores late ACK`() {
        assertEquals(OutputStatus.Accepted, arbiter.submit(hint("h")))
        clock.advance(100); scheduler.runDue()
        assertEquals(listOf(SessionId("s") to Generation(1)), device.clears)
        assertEquals(1, device.handles.single().cancelCount)
        device.callbacks.single()(OutputStatus.Displayed)
        assertFalse(states.any { it.second == OutputStatus.Displayed })
        assertTrue("h" to OutputStatus.Expired in states)
    }

    @Test fun `higher priority preempts and queued expired hints never display`() {
        arbiter.submit(hint("high", 3, 100))
        assertEquals(OutputStatus.Queued, arbiter.submit(hint("low", 1, 50)))
        assertEquals(OutputStatus.Accepted, arbiter.submit(hint("urgent", 4, 200)))
        assertEquals(listOf("high", "urgent"), device.shown.map { it.first.hintId })
        clock.advance(200); scheduler.runDue()
        assertEquals(2, device.shown.size)
        assertTrue("low" to OutputStatus.Expired in states)
    }

    @Test fun `cancel turn clears current and starts live queued item`() {
        arbiter.submit(hint("first", 2)); arbiter.submit(hint("second", 1))
        arbiter.cancelTurn("turn-first")
        assertEquals(listOf("first", "second"), device.shown.map { it.first.hintId })
        assertEquals(1, device.clears.size)
    }

    @Test fun `queue is bounded rejects lowest and clear fences all callbacks`() {
        arbiter.submit(hint("active", 10))
        repeat(8) { assertEquals(OutputStatus.Queued, arbiter.submit(hint("q$it", 1))) }
        assertEquals(OutputStatus.Rejected, arbiter.submit(hint("overflow", 0)))
        arbiter.clear(SessionId("s"), Generation(1))
        device.callbacks.forEach { it(OutputStatus.Displayed) }
        clock.advance(100); scheduler.runDue()
        assertEquals(1, device.shown.size)
        assertFalse(states.any { it.second == OutputStatus.Displayed })
    }

    @Test fun `same hint uses increasing revision and ignores old ACK`() {
        arbiter.submit(hint("h")); arbiter.submit(hint("h").copy(text = "updated"))
        assertEquals(listOf(1L, 2L), device.shown.map { it.second })
        device.callbacks[0](OutputStatus.Displayed)
        arbiter.submit(hint("barrier", 0))
        assertFalse(states.any { it.second == OutputStatus.Displayed })
        device.callbacks[1](OutputStatus.Displayed)
        arbiter.submit(hint("barrier-2", 0))
        assertEquals(1, states.count { it.second == OutputStatus.Displayed })
    }

    @Test fun `stale identity long unicode and speech requests are rejected`() {
        assertEquals(OutputStatus.Rejected, arbiter.submit(hint("old").copy(generation = Generation(0))))
        assertEquals(OutputStatus.Rejected, arbiter.submit(hint("speech").copy(optionalSpeech = SpeechRequest())))
        assertEquals(OutputStatus.Rejected, arbiter.submit(hint("long").copy(text = "x".repeat(121))))
        assertEquals(OutputStatus.Accepted, arbiter.submit(hint("unicode").copy(text = "\uD83D\uDE00".repeat(120))))
    }
}
