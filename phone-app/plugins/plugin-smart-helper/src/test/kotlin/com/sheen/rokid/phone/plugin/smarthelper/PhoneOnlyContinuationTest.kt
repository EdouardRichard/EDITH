package com.sheen.rokid.phone.plugin.smarthelper

import com.sheen.rokid.contracts.AssistantRoute
import com.sheen.rokid.contracts.OutputStateEvent
import com.sheen.rokid.contracts.OutputStatus
import com.sheen.rokid.contracts.SpeechFinished
import com.sheen.rokid.contracts.SpeechResult
import com.sheen.rokid.contracts.TranscriptEvent
import com.sheen.rokid.contracts.UserAction
import com.sheen.rokid.output.OfficialConversation
import com.sheen.rokid.output.OfficialStatus
import com.sheen.rokid.plugin.Capability
import com.sheen.rokid.phone.plugin.smarthelper.audit.P3Log
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 0.5.1 Task 5 (`C2`, spec §3.2): phone-only mode disables **only** the post-answer automatic continuation.
 *
 * The owner's ruling is narrow on purpose:
 * - the wake's initial 10 s listening window stays: the first sentence is still spoken normally;
 * - the queued-utterance path (`continueDialogue` at the plugin's queued-utterance seam) stays: a sentence already
 *   said during the answer is still remembered, and glasses mode still consumes it;
 * - glasses mode is byte-identical: `phoneOnly=false` and the stored switch decide exactly as before;
 * - only the round opened **after a displayed answer** is gated, so in phone-only mode the answer settles on the
 *   official continue-off path (no status 5, close ~1 s later) and the next sentence is the user's long-press ASR.
 *
 * The bubble consequence (~4 s after the answer = 3 s TurnIdle dismissal + ~1 s close) is the host's existing
 * behaviour and is deliberately not re-tested here; this suite pins the conversation-cycle decision.
 */
class PhoneOnlyContinuationTest {

    /**
     * A panel-visible rig (phone-only's phone panel is always "visible" to the cycle) with the two seams split.
     *
     * [continueAfterAnswer] is `null` for the glasses baseline, which models every caller written before 0.5.1: the
     * plugin's constructor default then follows [continueDialogue].
     */
    private fun rig(
        continueDialogue: Boolean = true,
        continueAfterAnswer: Boolean? = false,
        phoneOnly: Boolean = true,
        speechAvailable: Boolean = false,
    ): Pair<SmartTestRig, SmartRecordingDisplay> {
        val display = SmartRecordingDisplay()
        val rig = SmartTestRig(
            panelVisible = true,
            continueDialogue = continueDialogue,
            continueAfterAnswer = continueAfterAnswer,
            phoneOnly = phoneOnly,
            speechAvailable = speechAvailable,
        )
        rig.activate(overrides = mapOf(Capability.ConversationDisplay to display))
        return rig to display
    }

    /** Wakes, answers one question and returns the displayed answer's hint id (not yet acknowledged). */
    private fun displayedAnswer(rig: SmartTestRig): String {
        rig.model.onRequest = { request, listener -> streamText(request, listener, "答案。") }
        rig.plugin.handle(rig.wakeEvent())
        rig.plugin.handle(TranscriptEvent(rig.transcript("问题")))
        rig.tick()
        return rig.output.hints.single().hintId
    }

    @Test
    fun `phone-only answer does not open the 10 s continuation window`() {
        // The stored switch stays on (glasses semantics), but phone-only mode passes the answer seam as off.
        val (rig, display) = rig(continueDialogue = true, continueAfterAnswer = false, phoneOnly = true)
        val hintId = displayedAnswer(rig)

        rig.plugin.handle(OutputStateEvent(rig.envelope(), hintId, OutputStatus.Displayed))

        assertTrue(
            OfficialStatus.AUDIO_FINISHED.wire !in display.statuses,
            "phone-only mode must not send status 5 for an automatic next round: ${display.statuses}",
        )
        assertEquals(
            1,
            display.statuses.count { it == OfficialStatus.LISTENING.wire },
            "the wake's own initial window is the only listening level: ${display.statuses}",
        )
        assertEquals(1, display.count("working:WAKE"), "no continuation round is opened")
        assertTrue(display.closes.isEmpty(), "the official continue-off exit is one second, not immediate")

        rig.tick(999)
        assertTrue(display.closes.isEmpty(), "999 ms is inside the official scheduleExit(1000)")
        rig.tick(1)
        assertEquals(
            listOf(AssistantRoute.WAKE),
            display.closes,
            "the panel closes ~1 s after the answer, so the answer bubble hides ~4 s after the answer",
        )
        assertEquals(
            1,
            rig.model.requests.size,
            "the next sentence is the user's long-press ball ASR, not a model turn opened by the answer",
        )
    }

    /**
     * 0.5.1 fix round (review Important): a final that arrives **after** the `Displayed` acknowledgement.
     *
     * The answer is already settled on the continue-off branch when that final arrives, so phone-only mode can never
     * open the continuation the sentence is queued for. The queue is therefore dead: it must not make the BALL/typed
     * admission busy (`canAcceptTypedMessage` is exactly the host's pre-check), and it must not cancel the 1 s
     * continue-off close - the close is what idles the answer bubble at ~4 s and releases the dead queue.
     */
    @Test
    fun `a final after Displayed is admitted and does not cancel the continue-off close`() {
        val (rig, display) = rig(continueDialogue = true, continueAfterAnswer = false, phoneOnly = true)
        val hintId = displayedAnswer(rig)

        rig.plugin.handle(OutputStateEvent(rig.envelope(), hintId, OutputStatus.Displayed))
        assertTrue(rig.plugin.canAcceptTypedMessage(), "the settled answer leaves the typed/BALL slot open")
        assertTrue(display.closes.isEmpty(), "the continue-off close is one second away")

        // Post-ACK late final: phone-only never opens the continuation this sentence is queued for. The stored
        // 「持续对话」 switch still queues it (that seam is unchanged); the queue is what the fix must neutralise.
        val lines = java.util.Collections.synchronizedList(mutableListOf<String>())
        val previous = P3Log.sink
        P3Log.sink = { lines += it }
        try {
            rig.plugin.handle(TranscriptEvent(rig.transcript("第二句", turnId = "asr-2", revision = 2)))
        } finally {
            P3Log.sink = previous
        }
        assertTrue(
            lines.any { it.startsWith("P3_CONV queue route=WAKE") },
            "the stored switch still queues a post-ACK final; the dead-queue fix must not change that seam: $lines",
        )

        assertTrue(
            rig.plugin.canAcceptTypedMessage(),
            "a queued utterance no continuation can consume must not make the BALL/typed path busy",
        )
        rig.tick(999)
        assertTrue(display.closes.isEmpty(), "999 ms is still inside the official scheduleExit(1000)")
        rig.tick(1)
        assertEquals(
            listOf(AssistantRoute.WAKE),
            display.closes,
            "the post-ACK final must not cancel the continue-off close",
        )
        assertTrue(
            OfficialStatus.AUDIO_FINISHED.wire !in display.statuses,
            "phone-only mode still sends no status 5: ${display.statuses}",
        )
        assertEquals(1, display.statuses.count { it == OfficialStatus.LISTENING.wire })
        assertEquals(1, rig.model.requests.size, "the dead queue must not run a hidden model turn")

        // The next sentence is the wearer's BALL/typed input, and it is admitted once the dead queue is released.
        rig.plugin.handle(UserAction(rig.envelope(), UserAction.Action.Typed, "下一句"))
        assertEquals(2, rig.model.requests.size, "the typed/BALL path is admitted after the dead queue is released")
    }

    /**
     * 0.5.1 fix round (review Minor 1): the `onSpeechFinished` half of the seam is behavior, not just a constructor
     * default.
     *
     * Every earlier rig ran with `speechAvailable = false`, so `ConversationCycle.onSpeechFinished` could have been
     * reverted to `continueDialogue()` and the rest of this file would still be green. This answer is actually spoken
     * (phone-only + a live player), so the `Displayed` acknowledgement must wait; only the matching `SpeechFinished`
     * may settle it, and it must settle on the continue-off close exactly 1 000 ms after the speech.
     */
    @Test
    fun `phone-only speech end closes on the continue-off path`() {
        val (rig, display) = rig(
            continueDialogue = true,
            continueAfterAnswer = false,
            phoneOnly = true,
            speechAvailable = true,
        )
        val hintId = displayedAnswer(rig)

        rig.plugin.handle(OutputStateEvent(rig.envelope(), hintId, OutputStatus.Displayed))
        // The answer is still being spoken, so the display ACK may not settle it on the continue-off branch.
        rig.tick(5_000)
        assertTrue(display.closes.isEmpty(), "a spoken answer must wait for SpeechFinished")
        assertTrue(
            OfficialStatus.AUDIO_FINISHED.wire !in display.statuses,
            "no status 5 may be sent while the speech wait is live: ${display.statuses}",
        )

        rig.plugin.handle(SpeechFinished(rig.envelope(), hintId, SpeechResult.FINISHED))

        assertTrue(display.closes.isEmpty(), "the continue-off close is armed one second after the speech end")
        rig.tick(999)
        assertTrue(display.closes.isEmpty(), "999 ms after the speech end is inside scheduleExit(1000)")
        rig.tick(1)
        assertEquals(
            listOf(AssistantRoute.WAKE),
            display.closes,
            "the speech-end seam selects the continue-off close, not a continuation",
        )
        assertTrue(
            OfficialStatus.AUDIO_FINISHED.wire !in display.statuses,
            "phone-only mode must send no status 5 after the speech ends: ${display.statuses}",
        )
        assertEquals(
            1,
            display.statuses.count { it == OfficialStatus.LISTENING.wire },
            "only the wake's initial window may be listening: ${display.statuses}",
        )
        assertEquals(1, rig.model.requests.size, "the answer opens no automatic next round")
    }

    @Test
    fun `phone-only wake still starts its initial 10 s listening window`() {
        val (rig, display) = rig(continueDialogue = true, continueAfterAnswer = false, phoneOnly = true)

        rig.plugin.handle(rig.wakeEvent())

        assertEquals(
            listOf(OfficialStatus.LISTENING.wire),
            display.statuses,
            "the initial wake window is not the post-answer continuation and must stay",
        )
        rig.tick(OfficialConversation.LISTEN_TIMEOUT_MILLIS - 1)
        assertTrue(display.closes.isEmpty(), "9_999 ms is inside the initial 10 s window")
        rig.tick(1)
        assertEquals(
            listOf(AssistantRoute.WAKE),
            display.closes,
            "the initial window still times out exactly as before",
        )
    }

    @Test
    fun `glasses mode with continueDialogue true still continues`() {
        // The pre-0.5.1 caller shape: `continueAfterAnswer` absent, so the plugin default follows `continueDialogue`.
        val (rig, display) = rig(continueDialogue = true, continueAfterAnswer = null, phoneOnly = false)
        val hintId = displayedAnswer(rig)

        rig.plugin.handle(OutputStateEvent(rig.envelope(), hintId, OutputStatus.Displayed))

        assertEquals(
            OfficialStatus.AUDIO_FINISHED.wire,
            display.statuses[display.statuses.size - 2],
            "glasses mode is unchanged: status 5 then the next listening level",
        )
        assertEquals(OfficialStatus.LISTENING.wire, display.statuses.last())
        assertEquals(2, display.count("working:WAKE"), "the glasses answer still opens exactly one continuation")
        assertTrue(display.closes.isEmpty(), "glasses mode does not take the continue-off close")
    }

    @Test
    fun `queued utterance path is not gated by continueAfterAnswer`() {
        val (rig, display) = rig(continueDialogue = true, continueAfterAnswer = false, phoneOnly = true)
        rig.model.onRequest = { request, listener -> streamText(request, listener, "第一轮回答。") }

        val lines = java.util.Collections.synchronizedList(mutableListOf<String>())
        val previous = P3Log.sink
        P3Log.sink = { lines += it }
        try {
            rig.plugin.handle(rig.wakeEvent())
            rig.plugin.handle(TranscriptEvent(rig.transcript("第一句", turnId = "asr-1")))
            rig.tick()
            assertEquals("第一轮回答。", rig.output.hints.single().text, "the first answer is on screen")

            // The run has ended and the answer is emitted, but nothing has acknowledged the display yet: this is the
            // idle gap in which the wearer's next sentence used to be lost. Queueing it is gated by the stored
            // 「持续对话」 switch (`continueDialogue`), never by the new post-answer seam.
            rig.plugin.handle(TranscriptEvent(rig.transcript("第二句", turnId = "asr-2", revision = 2)))

            assertTrue(
                lines.any { it.startsWith("P3_CONV queue route=WAKE") },
                "a sentence in the idle gap must still be queued while the stored switch is on: $lines",
            )
        } finally {
            P3Log.sink = previous
        }

        // Phone-only mode still does not consume the queue automatically: no continuation opens, the answer settles on
        // the continue-off path, and the queued sentence is released with the round rather than answered silently.
        val hintId = rig.output.hints.single().hintId
        rig.plugin.handle(OutputStateEvent(rig.envelope(), hintId, OutputStatus.Displayed))
        assertEquals(1, display.statuses.count { it == OfficialStatus.LISTENING.wire })
        rig.tick(1_000)
        assertEquals(listOf(AssistantRoute.WAKE), display.closes)
        assertTrue(
            rig.plugin.canAcceptTypedMessage(),
            "the close releases the queued sentence with the round, so the next BALL/typed sentence is admitted",
        )
        assertEquals(
            1,
            rig.model.requests.size,
            "phone-only mode never opens the automatic next round, queued utterance or not",
        )
    }
}
