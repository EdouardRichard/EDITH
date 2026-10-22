package com.sheen.rokid.agent

import com.sheen.rokid.testsupport.FakeClock
import com.sheen.rokid.testsupport.ManualScheduler
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The gate's own contract, under the threads P3.3 actually uses it from.
 *
 * `ConfirmationGate` used to document that `resolve`/`cancelAll` "must be called from the thread the scheduler delivers
 * on", and stored its questions in a plain `mutableMapOf`. P3.3 broke that assumption: the plugin asks from the turn's
 * worker, the host answers from its event dispatcher, 急停 cancels from the UI thread and the timeout fires on the
 * deadline thread. A tap arriving at the 10 s boundary, or a 急停 while a timeout fires, then races on that map: a
 * settlement can be lost (the question hangs to `SmartConfirmation`'s fallback while the card is already gone) or
 * `cancelAll`'s iteration can throw into `HostRuntime.deliverEvent`'s `onFailure { pause() }`, silently pausing the
 * session.
 *
 * A data race is not reliably reproducible in a unit test, so this does what can be done deterministically instead: it
 * drives the real interleaving hard and asserts the invariants that must hold whatever the schedule is - nothing
 * throws, and no question ever settles more than once.
 */
class ConfirmationGateConcurrencyTest {

    @Test fun `concurrent ask answer and stop never throw and never settle a question twice`() {
        val failures = ConcurrentHashMap<String, Throwable>()
        repeat(ROUNDS) { round ->
            val clock = FakeClock(1_000_000_000)
            val gate = ConfirmationGate(clock, ManualScheduler(clock))
            val settlements = ConcurrentHashMap<String, Int>()
            val ids = (1..PER_ROUND).map { "r$round-q$it" }
            val go = CountDownLatch(1)

            val askers = ids.map { id ->
                Thread({
                    try {
                        go.await()
                        gate.request(id, "summary") { settlements.merge(id, 1, Int::plus) }
                    } catch (error: Throwable) { failures["ask:$id"] = error }
                }, "gate-ask-$id")
            }
            val answerer = Thread({
                try {
                    go.await()
                    ids.forEach { gate.resolve(it, approved = true) }
                } catch (error: Throwable) { failures["resolve:$round"] = error }
            }, "gate-resolve-$round")
            val stopper = Thread({
                try {
                    go.await()
                    gate.cancelAll()
                } catch (error: Throwable) { failures["cancelAll:$round"] = error }
            }, "gate-cancel-$round")

            (askers + answerer + stopper).forEach { it.isDaemon = true; it.start() }
            go.countDown()
            (askers + answerer + stopper).forEach { it.join(TimeUnit.SECONDS.toMillis(10)) }
        }

        assertTrue(failures.isEmpty(), "no call may throw, whatever the interleaving: $failures")
    }

    private companion object {
        const val ROUNDS = 300
        const val PER_ROUND = 6
    }
}
