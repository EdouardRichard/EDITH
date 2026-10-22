package com.sheen.rokid.phone.plugin.smarthelper

import com.sheen.rokid.contracts.AssistantRoute
import com.sheen.rokid.contracts.OutputStateEvent
import com.sheen.rokid.contracts.OutputStatus
import com.sheen.rokid.contracts.TranscriptEvent
import com.sheen.rokid.output.OfficialStatus
import com.sheen.rokid.plugin.Capability
import com.sheen.rokid.plugin.InputPart
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 0.3 ASR/continuous-dialogue repair: a usable final that arrives **while the wake turn is Running** is the wearer's
 * next sentence, not noise.
 *
 * The device run on 2026-10-22 showed the exact loss: `P3_ASR revision=2 final=true usable=true` arrived during the
 * wake turn's tool round and produced no `P3_TRANSCRIPT`, no turn and no proactive line - the final-review guard
 * (`wakeOwnsFinal`) dropped it because a Running WAKE turn must not be recorded as Help's next wake utterance or
 * parked as a proactive hint. That guard is still right about those two destinations; it left the wearer's next
 * sentence with no destination at all.
 *
 * The contract pinned here: while the wake turn runs, a usable final is queued **once** (latest wins), and the
 * continuation the displayed answer opens consumes it as its own utterance. The wearer therefore gets the next turn
 * without a second wake word, which is what "continuous dialogue" means to the person speaking.
 */
class RunningUtteranceQueueTest {

    private fun rig(
        display: SmartRecordingDisplay,
        tasks: OffThreadTasks,
    ): SmartTestRig {
        val rig = SmartTestRig(panelVisible = true, continueDialogue = true, speechAvailable = false)
        rig.activate(overrides = mapOf(Capability.ConversationDisplay to display), tasks = tasks)
        return rig
    }

    @Test
    fun `a usable final while the wake turn runs becomes the continuation utterance`() {
        val display = SmartRecordingDisplay()
        val tasks = OffThreadTasks()
        val rig = rig(display, tasks)
        val firstRequest = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val secondRequest = CountDownLatch(1)
        var requests = 0
        rig.model.onRequest = { request, listener ->
            requests += 1
            when (requests) {
                1 -> {
                    firstRequest.countDown()
                    // The tool round is where the wearer speaks in the device run; keep the turn Running until the
                    // second final has been delivered.
                    releaseFirst.await(5, TimeUnit.SECONDS)
                    streamText(request, listener, "第一轮回答。")
                }
                else -> {
                    secondRequest.countDown()
                    streamText(request, listener, "第二轮回答。")
                }
            }
        }

        rig.plugin.handle(rig.wakeEvent())
        rig.plugin.handle(TranscriptEvent(rig.transcript("第一句", turnId = "asr-1")))
        assertTrue(firstRequest.await(5, TimeUnit.SECONDS), "the wake turn must be Running before the next final arrives")

        // The wearer's next sentence, delivered while the wake turn is still Running.
        rig.plugin.handle(TranscriptEvent(rig.transcript("第二句", turnId = "asr-2", revision = 2)))
        assertTrue(rig.model.requests.size == 1, "a Running turn must not start a second turn synchronously")

        releaseFirst.countDown()
        tasks.joinAll()
        rig.tick(200)

        val answer = rig.output.hints.single { it.route == AssistantRoute.WAKE }
        rig.plugin.handle(OutputStateEvent(rig.envelope(), answer.hintId, OutputStatus.Displayed))

        assertTrue(secondRequest.await(5, TimeUnit.SECONDS), "the continuation must consume the queued utterance")
        tasks.joinAll()

        assertEquals(2, rig.model.requests.size, "the queued sentence is the continuation's own turn")
        val continuation = rig.model.requests[1]
        assertEquals(
            listOf("第二句"),
            continuation.input.filterIsInstance<InputPart.Text>().map { it.text },
            "the continuation request carries the utterance spoken during the first response",
        )
        val started = display.states.filter { it.status == OfficialStatus.ASR.wire }
        assertEquals(2, started.size, "both utterances publish their own ASR row")
        assertEquals("asr-2", started.last().turnId, "the queued sentence keeps its own ASR turn id")
    }

    @Test
    fun `a final in the idle gap before the continuation is queued too`() {
        val display = SmartRecordingDisplay()
        val tasks = OffThreadTasks()
        val rig = rig(display, tasks)
        var requests = 0
        rig.model.onRequest = { request, listener ->
            requests += 1
            streamText(request, listener, if (requests == 1) "第一轮回答。" else "第二轮回答。")
        }

        rig.plugin.handle(rig.wakeEvent())
        rig.plugin.handle(TranscriptEvent(rig.transcript("第一句", turnId = "asr-1")))
        tasks.joinAll()
        rig.tick(200)

        // The run has ended and the answer is emitted, but the display acknowledgement has not opened the continuation
        // yet. This is the device's ~3 s gap; the sentence said here belongs to the next round.
        rig.plugin.handle(TranscriptEvent(rig.transcript("第二句", turnId = "asr-2", revision = 2)))

        val answer = rig.output.hints.single { it.route == AssistantRoute.WAKE }
        rig.plugin.handle(OutputStateEvent(rig.envelope(), answer.hintId, OutputStatus.Displayed))
        tasks.joinAll()

        assertEquals(2, rig.model.requests.size, "the idle-gap sentence starts the continuation")
        assertEquals(
            listOf("第二句"),
            rig.model.requests[1].input.filterIsInstance<InputPart.Text>().map { it.text },
            "the continuation carries the sentence said before the display acknowledgement",
        )
    }

    @Test
    fun `the latest usable final during the run wins`() {
        val display = SmartRecordingDisplay()
        val tasks = OffThreadTasks()
        val rig = rig(display, tasks)
        val firstRequest = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        var requests = 0
        rig.model.onRequest = { request, listener ->
            requests += 1
            if (requests == 1) {
                firstRequest.countDown()
                releaseFirst.await(5, TimeUnit.SECONDS)
                streamText(request, listener, "第一轮回答。")
            } else {
                streamText(request, listener, "第二轮回答。")
            }
        }

        rig.plugin.handle(rig.wakeEvent())
        rig.plugin.handle(TranscriptEvent(rig.transcript("第一句", turnId = "asr-1")))
        assertTrue(firstRequest.await(5, TimeUnit.SECONDS))

        rig.plugin.handle(TranscriptEvent(rig.transcript("第二句", turnId = "asr-2", revision = 2)))
        rig.plugin.handle(TranscriptEvent(rig.transcript("第三句", turnId = "asr-3", revision = 3)))

        releaseFirst.countDown()
        tasks.joinAll()
        rig.tick(200)
        val answer = rig.output.hints.single { it.route == AssistantRoute.WAKE }
        rig.plugin.handle(OutputStateEvent(rig.envelope(), answer.hintId, OutputStatus.Displayed))
        tasks.joinAll()

        assertEquals(2, rig.model.requests.size, "only one queued sentence starts the next turn")
        assertEquals(
            listOf("第三句"),
            rig.model.requests[1].input.filterIsInstance<InputPart.Text>().map { it.text },
            "the newest utterance is the one the wearer meant to continue with",
        )
    }
}
