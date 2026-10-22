package com.sheen.rokid.phone.plugin.smarthelper

import com.sheen.rokid.contracts.AcousticEvent
import com.sheen.rokid.contracts.AcousticLabel
import com.sheen.rokid.contracts.ContextChanged
import com.sheen.rokid.contracts.TranscriptEvent
import com.sheen.rokid.phone.plugin.smarthelper.proactive.GateForm
import com.sheen.rokid.phone.plugin.smarthelper.proactive.GateOutcome
import com.sheen.rokid.phone.plugin.smarthelper.proactive.ProactiveGate
import com.sheen.rokid.phone.plugin.smarthelper.proactive.ProactiveGateState
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
     * The `ContextChanged` entry point only evaluates an ambient trigger when a gate exists; this one always accepts,
     * so a trigger that passed admission really ran its HINT_TURN.
     */
    private class AlwaysInterveneGate : ProactiveGate {
        override val form = GateForm.NOUL
        override fun shouldIntervene(state: ProactiveGateState) = GateOutcome(true, 0.9, 1.0)
    }

    /**
     * A foreground change is the proactive trigger that remains legal while a wake turn is in flight: a final that
     * arrives in that state belongs to WAKE (P3-13), so it cannot stand in for a proactive utterance here.
     */
    private fun contextChange(rig: SmartTestRig, revision: Long, foreground: String) {
        rig.plugin.handle(ContextChanged(rig.envelope(), revision, mapOf("app.foreground" to foreground)))
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
        val rig = SmartTestRig(interventionGate = AlwaysInterveneGate())
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
            contextChange(rig, revision = 2, foreground = "设置")

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
            contextChange(rig, revision = 2, foreground = "设置")
            contextChange(rig, revision = 3, foreground = "音乐")
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
            contextChange(rig, revision = 2, foreground = "设置")
            rig.plugin.deactivate(com.sheen.rokid.plugin.DeactivationReason.Stopped)
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
            contextChange(rig, revision = 2, foreground = "设置")
        } finally {
            turn.finish()
        }
        Thread.sleep(150)

        assertEquals(1, rig.model.requests.size)
    }
}
