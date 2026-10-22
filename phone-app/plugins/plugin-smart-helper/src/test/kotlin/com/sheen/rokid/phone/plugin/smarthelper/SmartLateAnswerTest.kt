package com.sheen.rokid.phone.plugin.smarthelper
import com.sheen.rokid.phone.plugin.smarthelper.turn.SmartTurn

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
 * Review I-1: a person who answers **late** still gets the result on the glasses.
 *
 * The turn's hint window is derived from the turn's own deadline (`SmartTurn.hintValidUntil` = deadline + grace), and
 * until D49 the whole turn ended as `DEADLINE` when the window ran out - so nobody ever saw this. D49 fixed the turn,
 * but left the hint window where it was: the loop shifted its *own* budget by the measured human wait, while the turn
 * still believed its deadline was the listening window it opened at the wake. The run then completed on the phone,
 * executed the approved action, assembled its sentence - and `emit` dropped it as expired, because
 * `minOf(now + lifetime, hintValidUntil)` was already in the past. The phone acted and the glasses stayed blank, which
 * is the exact scenario D47/D49 ship (a 120 s decision window against a ~30 s profile).
 *
 * The fix is additive in both directions: `AgentRun` reports the wall clock the run spent waiting for a person, and the
 * plugin shifts the turn's deadline by that amount before it submits the result - `SmartTurn.extendDeadline` moves
 * `hintValidUntil` with it, so a hint assembled after a late answer is still inside its own window.
 *
 * The question is answered from the test thread while the turn waits on a **real** worker ([OffThreadTasks]), because
 * that is the only way to advance the clock *during* the wait: the answer must be late, not merely "not immediate".
 */
class SmartLateAnswerTest {

    /** A question that is presented and then left open until the test answers it, exactly like §17.4's card. */
    private class OpenChannel : ConfirmationChannel {
        val questionUp = CountDownLatch(1)
        val presented = CopyOnWriteArrayList<Triple<String, String, Long>>()

        override fun present(requestId: String, summary: String, timeoutMillis: Long): CancellationHandle {
            presented += Triple(requestId, summary, timeoutMillis)
            questionUp.countDown()
            return object : CancellationHandle { override fun cancel() = Unit }
        }
    }

    /**
     * An L3 action (never exempt from asking) under an L4 ceiling: question one returns the tool call, question two
     * returns the model's sentence, which is what the glasses are supposed to show.
     */
    private fun rigAsking(channel: OpenChannel, tasks: OffThreadTasks): SmartTestRig {
        // The result is displayed on an already-visible panel: the official listening window starts at the wake, and
        // the 3 s PANEL_SHOWN wait (which a hidden panel without a shown event would trip at 3 s) is not armed.
        val rig = SmartTestRig(panelVisible = true)
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

    @Test fun `an answer that arrives long after the turn's own window still shows the result`() {
        val channel = OpenChannel()
        val tasks = OffThreadTasks()
        val rig = rigAsking(channel, tasks)

        // The run is carried by `OffThreadTasks`' worker (`launchTurn` hands it over); this caller only delivers the
        // two events, so it must not be the thread the question blocks.
        val caller = Thread({
            rig.plugin.handle(rig.wakeEvent())
            rig.plugin.handle(TranscriptEvent(rig.transcript("卸载探针 App")))
        }, "host-dispatcher-under-test")
        caller.isDaemon = true
        caller.start()

        // `waitStartedNanos` is read before the question is put, so a `questionUp` observation orders the clock advance
        // strictly *inside* the wait.
        assertTrue(channel.questionUp.await(5, TimeUnit.SECONDS), "the plugin must put the question to the host")
        assertTrue(
            100_000L > requireNotNull(rig.profile).timeoutMillis,
            "the advance must be past the turn's own window, which is the profile timeout",
        )
        rig.clock.advance(100_000L * 1_000_000L)

        rig.plugin.handle(ConfirmationResult(rig.envelope(), channel.presented.single().first, true))
        tasks.joinAll()
        caller.join(2_000)
        // The assembler merges for 150 ms before it emits, so the host's scheduler has to release that timer.
        rig.tick()

        assertEquals(listOf("uninstall" to "{}"), rig.actions.executed, "批准过的动作必须照常执行")
        assertEquals(
            listOf("处理完了。"),
            rig.hintTexts,
            "晚答（100 s，远超回合自己的窗口）之后结果提示必须仍然上屏，而不是被人时挤掉",
        )
    }

    @Test fun `an answer inside the turn's own window shows the result too`() {
        // The same path without the wait: the fix must not be what makes an ordinary answer work.
        val channel = OpenChannel()
        val tasks = OffThreadTasks()
        val rig = rigAsking(channel, tasks)

        val caller = Thread({
            rig.plugin.handle(rig.wakeEvent())
            rig.plugin.handle(TranscriptEvent(rig.transcript("卸载探针 App")))
        }, "host-dispatcher-under-test")
        caller.isDaemon = true
        caller.start()

        assertTrue(channel.questionUp.await(5, TimeUnit.SECONDS), "the plugin must put the question to the host")
        rig.clock.advance(1_000L * 1_000_000L)

        rig.plugin.handle(ConfirmationResult(rig.envelope(), channel.presented.single().first, true))
        tasks.joinAll()
        caller.join(2_000)
        rig.tick()

        assertEquals(listOf("处理完了。"), rig.hintTexts, "窗口内的正常答复本来就该上屏")
    }

    @Test fun `a failure after a late answer still shows the honest sentence`() {
        // Review (Important): the loop hands its measurement over through the returned `AgentRun`, so a transport
        // failure *after* a late answer lost it - the plugin built a fresh FAILED run carrying no wait, the hint window
        // stayed at the stale deadline, and even the fixed "没能给出结果" sentence was dropped: silent glasses, which is
        // the exact failure class I-1 exists to close. The measurement has to survive a run that ends by throwing.
        val channel = OpenChannel()
        val tasks = OffThreadTasks()
        val rig = rigAsking(channel, tasks)
        var request = 0
        rig.model.onRequest = { modelRequest, listener ->
            request += 1
            if (request == 1) {
                listener(ModelStreamEvent.ToolCallTerminal(modelRequest.requestId, "fc-1", "call-1", "uninstall", "{}"))
                listener(ModelStreamEvent.Terminal(modelRequest.requestId, ModelEnd.COMPLETED, null, null))
            } else {
                // What a transport failure does through `ModelClientGateway`: it escapes the loop and reaches the plugin.
                error("synthetic transport failure")
            }
        }

        val caller = Thread({
            rig.plugin.handle(rig.wakeEvent())
            rig.plugin.handle(TranscriptEvent(rig.transcript("卸载探针 App")))
        }, "host-dispatcher-under-test")
        caller.isDaemon = true
        caller.start()

        assertTrue(channel.questionUp.await(5, TimeUnit.SECONDS), "the plugin must put the question to the host")
        rig.clock.advance(100_000L * 1_000_000L)

        rig.plugin.handle(ConfirmationResult(rig.envelope(), channel.presented.single().first, true))
        tasks.joinAll()
        caller.join(2_000)
        rig.tick()

        assertEquals(listOf("uninstall" to "{}"), rig.actions.executed, "批准过的动作照常执行")
        assertEquals(
            listOf("智能助手这次没能给出结果。"),
            rig.hintTexts,
            "晚答之后 run 抛异常，也必须有那句诚实的失败提示，不得全黑",
        )
    }
}
