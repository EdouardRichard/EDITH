package com.sheen.rokid.phone.plugin.smarthelper

import com.sheen.rokid.contracts.AssistantRoute
import com.sheen.rokid.contracts.ConfirmationResult
import com.sheen.rokid.contracts.TranscriptEvent
import com.sheen.rokid.contracts.UserAction
import com.sheen.rokid.plugin.CancellationHandle
import com.sheen.rokid.plugin.Capability
import com.sheen.rokid.plugin.ConfirmationChannel
import com.sheen.rokid.plugin.ModelEnd
import com.sheen.rokid.plugin.ModelStreamEvent
import com.sheen.rokid.plugin.RiskLevel
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * R128: STOP/teardown must withdraw a live confirmation question, not leave it pending.
 *
 * `ConfirmingActions` only asks the host; the pending slot lives in `SmartConfirmation`. Without cancelling it, a STOP
 * withdrew the turn but left the phone's question behind - and a later Allow settled the gate as approved, so the
 * command reached `host.execute` **after** the wearer had already stopped the assistant (the R52 bypass). These tests
 * drive the real plugin with a real `SmartConfirmation` and a channel that stays open, so the whole path - STOP,
 * teardown, host withdrawal, late Allow - is observed instead of a fake's bookkeeping.
 */
class StopConfirmationTest {

    /** Presents the question and leaves it open; [cancelled] proves the host's card was withdrawn. */
    private class OpenChannel : ConfirmationChannel {
        val questionUp = CountDownLatch(1)
        val presented = CopyOnWriteArrayList<String>()
        val cancelled = AtomicInteger()

        override fun present(requestId: String, summary: String, timeoutMillis: Long): CancellationHandle {
            presented += requestId
            questionUp.countDown()
            return object : CancellationHandle {
                override fun cancel() { cancelled.incrementAndGet() }
            }
        }
    }

    /**
     * An L3 action under an L4 ceiling: it must ask, so the question is real. The first request returns the tool
     * call; the second - which only runs if the tool result gets back to the model - streams an answer.
     */
    private fun rigAsking(channel: OpenChannel, tasks: OffThreadTasks): SmartTestRig {
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

    /** Delivers the wake and the utterance from a caller thread, then waits until the host has the question. */
    private fun startQuestion(rig: SmartTestRig, channel: OpenChannel): Thread {
        val caller = Thread({
            rig.plugin.handle(rig.wakeEvent())
            rig.plugin.handle(TranscriptEvent(rig.transcript("卸载探针 App")))
        }, "host-dispatcher-under-test")
        caller.isDaemon = true
        caller.start()
        assertTrue(channel.questionUp.await(5, TimeUnit.SECONDS), "the plugin must put the question to the host")
        return caller
    }

    @Test
    fun `stop withdraws a pending question and a later allow never executes`() {
        val channel = OpenChannel()
        val tasks = OffThreadTasks()
        val rig = rigAsking(channel, tasks)
        val caller = startQuestion(rig, channel)

        rig.plugin.handle(UserAction(rig.envelope(), UserAction.Action.Stop))

        assertEquals(
            0,
            rig.plugin.pendingConfirmationCountForTest(),
            "STOP must settle the live question instead of leaving it armed",
        )
        val requestId = channel.presented.single()
        // The phone tap the host had already queued (or a user tapping the stale card) arrives after STOP.
        rig.plugin.handle(ConfirmationResult(rig.envelope(), requestId, true))
        tasks.joinAll()
        caller.join(2_000)

        assertEquals(1, channel.cancelled.get(), "STOP must withdraw the host's question")
        assertTrue(rig.actions.executed.isEmpty(), "a late Allow after STOP must never reach the host executor")
        assertEquals(0, rig.plugin.pendingConfirmationCountForTest())
    }

    @Test
    fun `a panel close tears the turn down and withdraws its pending question`() {
        // The teardown path without stopAll: the panel went away while the phone still showed the question.
        val channel = OpenChannel()
        val tasks = OffThreadTasks()
        val rig = rigAsking(channel, tasks)
        val caller = startQuestion(rig, channel)

        rig.plugin.onPanelClosed(AssistantRoute.WAKE)

        assertEquals(
            0,
            rig.plugin.pendingConfirmationCountForTest(),
            "teardown must settle the question its turn asked",
        )
        rig.plugin.handle(ConfirmationResult(rig.envelope(), channel.presented.single(), true))
        tasks.joinAll()
        caller.join(2_000)

        assertEquals(1, channel.cancelled.get(), "teardown must withdraw the host's question")
        assertTrue(rig.actions.executed.isEmpty(), "an answer after teardown must not run the tool")
    }
}
