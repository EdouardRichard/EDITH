package com.sheen.rokid.phone.plugin.smarthelper.turn

import com.sheen.rokid.contracts.ConfirmationResult
import com.sheen.rokid.contracts.TranscriptEvent
import com.sheen.rokid.plugin.CancellationHandle
import com.sheen.rokid.plugin.Capability
import com.sheen.rokid.plugin.ConfirmationChannel
import com.sheen.rokid.plugin.ModelEnd
import com.sheen.rokid.plugin.ModelStreamEvent
import com.sheen.rokid.plugin.RiskLevel
import com.sheen.rokid.phone.plugin.smarthelper.OffThreadTasks
import com.sheen.rokid.phone.plugin.smarthelper.SmartTestRig
import com.sheen.rokid.phone.plugin.smarthelper.audit.P3Log
import com.sheen.rokid.phone.plugin.smarthelper.smartAction
import com.sheen.rokid.phone.plugin.smarthelper.smartToolCall
import com.sheen.rokid.phone.plugin.smarthelper.streamText
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 0.5.4 Task 7: one agent turn may spend at most **five machine minutes**.
 *
 * The budget bounds **machine** time only: a confirmation (or a tool's own bounded `mayAskHuman` wait) spends the
 * person's time, so `AgentLoop` shifts its deadline by the measured wait and the plugin moves the turn's hint window
 * with it (`SmartTurn.extendDeadline`). Two people-adjacent consequences are pinned here beyond the number itself:
 *
 * - a six-minute confirmation — longer than the whole budget — still lets the turn finish, so the wait cannot be
 *   silently charged to the machine budget;
 * - a turn that spends more than five machine minutes ends with the distinct `TURN_BUDGET` safe code, so `P3_TURN`
 *   can tell the loop's own budget apart from the per-request `REQUEST_DEADLINE` / `FIRST_TOKEN_TIMEOUT` /
 *   `STREAM_IDLE_TIMEOUT` codes Task 6 introduced (the wearer-facing sentence stays the DEADLINE one).
 *
 * The test class lives in `...turn` because it drives the turn-budget seam, not the plugin's event surface; the
 * Gradle selector in the task brief names this package.
 */
class AgentTurnBudgetTest {

    /** A question the test leaves open until it answers, so the clock can advance *inside* the wait. */
    private class OpenChannel : ConfirmationChannel {
        val questionUp = CountDownLatch(1)
        val presented = CopyOnWriteArrayList<Triple<String, String, Long>>()

        override fun present(requestId: String, summary: String, timeoutMillis: Long): CancellationHandle {
            presented += Triple(requestId, summary, timeoutMillis)
            questionUp.countDown()
            return object : CancellationHandle { override fun cancel() = Unit }
        }
    }

    /** Captures the module's own observability sink and always restores it, so one test cannot leak into the next. */
    private fun <T> withLines(block: (MutableList<String>) -> T): T {
        val lines = mutableListOf<String>()
        val previous = P3Log.sink
        P3Log.sink = { lines += it }
        return try {
            block(lines)
        } finally {
            P3Log.sink = previous
        }
    }

    @Test
    fun `the turn budget is five minutes`() {
        val rig = SmartTestRig()
        rig.activate()

        assertEquals(
            5L * 60L * 1_000_000_000L,
            rig.plugin.agentTurnBudgetNanosForTest(),
            "one agent turn must be bounded to five machine minutes",
        )
    }

    @Test
    fun `a confirmation wait does not consume the turn budget`() {
        // Six minutes of waiting is longer than the whole five-minute budget: if the person's time were charged to
        // the turn, the run would end DEADLINE at the next loop check and the answer would never be assembled.
        val channel = OpenChannel()
        val tasks = OffThreadTasks()
        val rig = SmartTestRig(panelVisible = true)
        rig.actions.descriptors += smartAction("uninstall", risk = RiskLevel.L3, scope = "app")
        rig.activate(maxLevel = "L4", overrides = mapOf(Capability.Confirmation to channel), tasks = tasks)
        var request = 0
        rig.model.onRequest = { modelRequest, listener ->
            request += 1
            if (request == 1) {
                listener(smartToolCall("c1", "uninstall", "{}", requestId = modelRequest.requestId))
                listener(ModelStreamEvent.Terminal(modelRequest.requestId, ModelEnd.COMPLETED, null, null))
            } else {
                streamText(modelRequest, listener, "处理完了。")
            }
        }

        // The turn runs on a real worker, so the test thread can advance the clock while the question is open.
        val caller = Thread({
            rig.plugin.handle(rig.wakeEvent())
            rig.plugin.handle(TranscriptEvent(rig.transcript("卸载探针 App")))
        }, "turn-budget-wait-under-test")
        caller.isDaemon = true
        caller.start()

        assertTrue(channel.questionUp.await(5, TimeUnit.SECONDS), "the plugin must put the question to the host")
        rig.clock.advance(6L * 60L * 1_000_000_000L)
        rig.plugin.handle(ConfirmationResult(rig.envelope(), channel.presented.single().first, true))
        tasks.joinAll()
        caller.join(2_000)
        // The assembler merges for 150 ms before it emits, so the host's scheduler has to release that timer.
        rig.tick()

        assertEquals(
            listOf("处理完了。"),
            rig.hintTexts,
            "a six-minute confirmation must not spend the five-minute machine budget",
        )
    }

    @Test
    fun `a turn over five minutes of machine time ends with TURN_BUDGET`() {
        // The model request itself is machine time. Advancing the clock inside it puts the run one nanosecond past
        // the five-minute budget before the returned tool call can be executed, so the loop's own budget check - not
        // a provider timeout - is the thing that ends the run.
        val rig = SmartTestRig(panelVisible = true)
        rig.activate()
        rig.model.onRequest = { modelRequest, listener ->
            rig.clock.advance(5L * 60L * 1_000_000_000L + 1L)
            listener(smartToolCall("c1", "device.set_volume", requestId = modelRequest.requestId))
            listener(ModelStreamEvent.Terminal(modelRequest.requestId, ModelEnd.COMPLETED, null, null))
        }

        val lines = withLines { captured ->
            rig.wakeAndFinal("把音量调小")
            captured.toList()
        }

        assertTrue(
            lines.any { it.startsWith("P3_TURN end") && "outcome=DEADLINE" in it && "code=TURN_BUDGET" in it },
            "turn-budget expiry must be distinguishable from a request deadline: $lines",
        )
        assertEquals(
            listOf("回答超时了，稍后再试。"),
            rig.hintTexts,
            "an exhausted turn budget keeps the honest timeout sentence",
        )
    }
}
