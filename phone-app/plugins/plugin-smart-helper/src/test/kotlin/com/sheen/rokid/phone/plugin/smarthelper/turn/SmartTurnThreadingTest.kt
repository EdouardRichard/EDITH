package com.sheen.rokid.phone.plugin.smarthelper.turn
import com.sheen.rokid.phone.plugin.smarthelper.SmartTestRig
import com.sheen.rokid.phone.plugin.smarthelper.SmartAssistantPlugin
import com.sheen.rokid.phone.plugin.smarthelper.streamText
import com.sheen.rokid.phone.plugin.smarthelper.smartAction
import com.sheen.rokid.phone.plugin.smarthelper.OffThreadTasks

import com.sheen.rokid.contracts.ConfirmationResult
import com.sheen.rokid.contracts.TranscriptEvent
import com.sheen.rokid.plugin.CancellationHandle
import com.sheen.rokid.plugin.Capability
import com.sheen.rokid.plugin.ConfirmationChannel
import com.sheen.rokid.plugin.ModelEnd
import com.sheen.rokid.plugin.ModelStreamEvent
import com.sheen.rokid.plugin.RiskLevel
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.*

/**
 * Spec §17.4's threading rule as a test: the wait for an answer may block **only the turn's own thread**.
 *
 * The failure this pins is not theoretical. On the K40 the plugin ran the turn inline inside `handle`, and the host
 * calls `handle` on its **single dispatcher thread** — so for the whole confirmation window that dispatcher sat inside
 * `CountDownLatch.await`. Two things then followed, and both are fatal to the phone-tap criterion (§17.4 calls the
 * phone tap the *hard* acceptance criterion):
 *
 * 1. the answer travels back as a `ConfirmationResult` event, which the host delivers **through that same dispatcher**,
 *    so the answer could never be delivered while the question was open — the phone tap was structurally impossible;
 * 2. the App's own UI thread blocked in `HostRuntime.command` behind the occupied dispatcher, which the platform
 *    reported as an ANR: `Input dispatching timed out … Waited 5001ms for MotionEvent(action=UP)`.
 *
 * The other half of the phase — the proactive path — was already written this way (`SmartAssistantPlugin.runProactive`
 * hands its blocking work to `services.tasks` with exactly this reasoning in its comment). These tests cover the wake
 * turn, which was not.
 */
class SmartTurnThreadingTest {

    /**
     * Records the question and never answers it: what matters is what the caller does while it is open.
     *
     * [holdWhilePresenting] lets a test keep `present` **inside the host** on purpose. That is not artificial: the host
     * shows the question before it returns, and the question's gate has historically been armed *after* that call
     * returns — so an answer that arrives while the question is on screen but the gate is not yet armed used to be
     * swallowed (the question then timed out as a denial). Holding here turns that window into a deterministic test.
     */
    private class RecordingChannel(private val holdWhilePresenting: CountDownLatch? = null) : ConfirmationChannel {
        val questionUp = CountDownLatch(1)
        val presented = CopyOnWriteArrayList<Triple<String, String, Long>>()

        override fun present(requestId: String, summary: String, timeoutMillis: Long): CancellationHandle {
            presented += Triple(requestId, summary, timeoutMillis)
            questionUp.countDown()
            holdWhilePresenting?.await(5, TimeUnit.SECONDS)
            return object : CancellationHandle { override fun cancel() = Unit }
        }
    }

    /**
     * A rig whose confirmation is a real question: an L3 action (never exempt from asking) under an L4 ceiling, with a
     * host that supplies the confirmation channel, and blocking work scheduled on a **real** thread.
     */
    private fun rigAsking(channel: RecordingChannel, tasks: OffThreadTasks): SmartTestRig {
        val rig = SmartTestRig()
        rig.actions.descriptors += smartAction("uninstall", risk = RiskLevel.L3, scope = "app")
        rig.activate(
            maxLevel = "L4",
            overrides = mapOf(Capability.Confirmation to channel),
            tasks = tasks,
        )
        var request = 0
        rig.model.onRequest = { modelRequest, listener ->
            request += 1
            if (request == 1) {
                listener(ModelStreamEvent.ToolCallTerminal(modelRequest.requestId, "fc-1", "call-1", "uninstall", "{}"))
                listener(ModelStreamEvent.Terminal(modelRequest.requestId, ModelEnd.COMPLETED, null, null))
            } else {
                streamText(modelRequest, listener, "处理完了。")
            }
        }
        return rig
    }

    @Test fun `an answer that arrives while the question is being shown still settles it`() {
        val showing = CountDownLatch(1)
        val channel = RecordingChannel(holdWhilePresenting = showing)
        val tasks = OffThreadTasks()
        val rig = rigAsking(channel, tasks)

        // The caller stands in for the host's dispatcher: it must not be held by the question.
        val handleReturned = CountDownLatch(1)
        val caller = Thread({
            rig.plugin.handle(rig.wakeEvent())
            rig.plugin.handle(TranscriptEvent(rig.transcript("卸载探针 App")))
            handleReturned.countDown()
        }, "host-dispatcher-under-test")
        caller.isDaemon = true
        caller.start()

        assertTrue(channel.questionUp.await(5, TimeUnit.SECONDS), "the plugin must put the question to the host")
        assertTrue(
            handleReturned.await(1, TimeUnit.SECONDS),
            "handle() must return while the question is open: the wait belongs to the turn's thread, not the caller's",
        )

        // The user answers *while the question is still being presented*. Losing this would turn a "yes" into a
        // timeout-as-denial - the exact inversion of what the wearer said.
        rig.plugin.handle(ConfirmationResult(rig.envelope(), channel.presented.single().first, true))
        showing.countDown()
        tasks.joinAll()
        caller.join(2_000)

        assertEquals(listOf("uninstall" to "{}"), rig.actions.executed, "an approved action must still run")
    }

    @Test fun `a turn that is never answered still gives the caller's thread back`() {
        val channel = RecordingChannel()
        val tasks = OffThreadTasks()
        val rig = rigAsking(channel, tasks)

        val handleReturned = CountDownLatch(1)
        val caller = Thread({
            rig.plugin.handle(rig.wakeEvent())
            rig.plugin.handle(TranscriptEvent(rig.transcript("卸载探针 App")))
            handleReturned.countDown()
        }, "host-dispatcher-under-test")
        caller.isDaemon = true
        caller.start()

        assertTrue(channel.questionUp.await(5, TimeUnit.SECONDS), "the plugin must put the question to the host")
        assertTrue(
            handleReturned.await(1, TimeUnit.SECONDS),
            "silence must not hold the caller: the gate's own timeout is the authority, on the turn's thread",
        )
        assertTrue(rig.actions.executed.isEmpty(), "an unanswered question must not execute anything")
        caller.join(2_000)
    }
}
