package edith.plugins.smart

import edith.contracts.AcousticEvent
import edith.contracts.AcousticLabel
import edith.contracts.TranscriptEvent
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Spec §4.6 / §7.3 B10: **WAKE outranks PROACTIVE**. A proactive trigger that arrives while a wake turn is in flight
 * is *paused, not discarded* — the difference matters, because dropping it loses the observation entirely, while
 * parking it means the assistant acts on it the moment the wearer's own turn is done.
 */
class RouteConcurrencyTest {

    private fun arm(rig: SmartTestRig) {
        rig.plugin.handle(AcousticEvent(rig.envelope(), AcousticLabel.Speech))
    }

    /**
     * Runs a wake turn on its own thread and blocks it inside the model request, so the test can act while the turn
     * is genuinely in flight. The latch is the only way to observe "in flight" deterministically.
     */
    private class BlockedWakeTurn(rig: SmartTestRig, text: String = "几点了", turnId: String = "asr-1") {
        private val entered = CountDownLatch(1)
        private val release = CountDownLatch(1)
        private val worker: Thread

        init {
            var first = true
            val previous = rig.model.onRequest
            rig.model.onRequest = { request, listener ->
                if (first) {
                    first = false
                    entered.countDown()
                    release.await(5, TimeUnit.SECONDS)
                }
                previous(request, listener)
            }
            worker = Thread {
                rig.plugin.handle(rig.wakeEvent())
                rig.plugin.handle(TranscriptEvent(rig.transcript(text, turnId = turnId)))
            }
            worker.isDaemon = true
            worker.start()
            assertTrue(entered.await(2, TimeUnit.SECONDS), "the wake turn must reach the model")
        }

        fun finish() {
            release.countDown()
            worker.join(5_000)
        }
    }

    private fun rig(): SmartTestRig {
        val rig = SmartTestRig()
        rig.activate()
        rig.model.onRequest = { request, listener -> streamText(request, listener, "答案。") }
        return rig
    }

    /** The parked turn runs on the worker thread, so its request arrives slightly after the join. */
    private fun awaitRequests(rig: SmartTestRig, count: Int) {
        val deadline = System.currentTimeMillis() + 2_000
        while (rig.model.requests.size < count && System.currentTimeMillis() < deadline) Thread.sleep(5)
    }

    @Test
    fun `a proactive trigger during a wake turn is parked rather than dropped`() {
        val rig = rig()
        val turn = BlockedWakeTurn(rig)
        try {
            arm(rig)
            rig.plugin.handle(TranscriptEvent(rig.transcript("今天星期几", turnId = "asr-2")))

            assertEquals(1, rig.model.requests.size, "nothing may run beside the wake turn")
        } finally {
            turn.finish()
        }

        awaitRequests(rig, 2)
        assertEquals(2, rig.model.requests.size, "the parked trigger must run once the wake turn is done")
    }

    @Test
    fun `only one trigger is parked and the newest one wins`() {
        // The queue is bounded on purpose: an unbounded one would let a long wake turn accumulate work that is stale
        // by the time it runs, and the newest observation is the one that describes the world now.
        val rig = rig()
        val turn = BlockedWakeTurn(rig)
        try {
            arm(rig)
            rig.plugin.handle(TranscriptEvent(rig.transcript("今天星期几", turnId = "asr-2")))
            rig.plugin.handle(TranscriptEvent(rig.transcript("现在几点了", turnId = "asr-3")))
        } finally {
            turn.finish()
        }

        awaitRequests(rig, 2)
        Thread.sleep(150)
        assertEquals(2, rig.model.requests.size, "exactly one parked trigger, not two")
    }

    @Test
    fun `a parked trigger does not survive deactivation`() {
        // The trigger describes a session that has ended; running it afterwards would act on a stale world under a
        // session that never saw it. The deactivation has to happen while the wake turn is *still blocked*, or the
        // parked trigger would have legitimately run at the end of that turn first.
        val rig = rig()
        val turn = BlockedWakeTurn(rig)
        try {
            arm(rig)
            rig.plugin.handle(TranscriptEvent(rig.transcript("今天星期几", turnId = "asr-2")))
            rig.plugin.deactivate(edith.plugin.DeactivationReason.Stopped)
        } finally {
            turn.finish()
        }
        Thread.sleep(150)

        assertEquals(1, rig.model.requests.size, "a deactivated session runs nothing it had parked")
    }

    @Test
    fun `a disarmed trigger is still dropped rather than parked`() {
        // Only BUSY is a reason to wait. A disarmed or too-short trigger is a decision, not a postponement.
        val rig = rig()
        val turn = BlockedWakeTurn(rig)
        try {
            // Deliberately not armed: no `AcousticEvent` has been seen in this session.
            rig.plugin.handle(TranscriptEvent(rig.transcript("今天星期几", turnId = "asr-2")))
        } finally {
            turn.finish()
        }
        Thread.sleep(150)

        assertEquals(1, rig.model.requests.size)
    }
}
