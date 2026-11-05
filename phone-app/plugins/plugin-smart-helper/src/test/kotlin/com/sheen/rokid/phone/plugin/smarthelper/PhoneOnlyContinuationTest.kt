package com.sheen.rokid.phone.plugin.smarthelper

import com.sheen.rokid.contracts.AssistantRoute
import com.sheen.rokid.contracts.OutputStateEvent
import com.sheen.rokid.contracts.OutputStatus
import com.sheen.rokid.contracts.SpeechFinished
import com.sheen.rokid.contracts.SpeechResult
import com.sheen.rokid.contracts.TranscriptEvent
import com.sheen.rokid.output.OfficialConversation
import com.sheen.rokid.output.OfficialStatus
import com.sheen.rokid.plugin.Capability
import com.sheen.rokid.phone.plugin.smarthelper.audit.P3Log
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
 *   official continue-off path (no status 5) and the next sentence is the user's long-press ASR.
 *
 * 0.5.4 Task 5 revises the last point: when a sentence is queued for the route, the 1 s continue-off exit opens the
 * continuation that answers it instead of closing. With an empty queue the exit still closes ~1 s later.
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
     * 0.5.1 fix round (review Important), rewritten by 0.5.4 Task 5: a final that arrives **after** the `Displayed`
     * acknowledgement.
     *
     * 0.5.1 treated such a sentence as dead in phone-only mode, because no answer continuation could ever consume it,
     * and admitted the typed/BALL path through the live queue. Task 5 makes the continue-off exit a continuation seam:
     * the 1 s exit opens the continuation that answers the queued sentence, so the queue is live and the typed path
     * must not race it. The transcript still must not cancel that exit - the exit is what consumes it.
     */
    @Test
    fun `a final after Displayed is consumed by the continue-off continuation`() {
        val (rig, display) = rig(continueDialogue = true, continueAfterAnswer = false, phoneOnly = true)
        val hintId = displayedAnswer(rig)

        rig.plugin.handle(OutputStateEvent(rig.envelope(), hintId, OutputStatus.Displayed))
        assertTrue(rig.plugin.canAcceptTypedMessage(), "the settled answer leaves the typed/BALL slot open")
        assertTrue(display.closes.isEmpty(), "the continue-off exit is one second away")

        // Post-ACK late final: the stored 「持续对话」 switch still queues it (that seam is unchanged). Task 5 makes the
        // queued sentence the next turn, so a typed message must not race it.
        val lines = java.util.Collections.synchronizedList(mutableListOf<String>())
        val previous = P3Log.sink
        P3Log.sink = { lines += it }
        try {
            rig.plugin.handle(TranscriptEvent(rig.transcript("第二句", turnId = "asr-2", revision = 2)))
            assertTrue(
                lines.any {
                    it.startsWith("P3_CONV queue_kept route=WAKE reason=continuation_pending")
                },
                "the stored switch still queues a post-ACK final, and the line names why it was kept: $lines",
            )
            assertFalse(
                rig.plugin.canAcceptTypedMessage(),
                "the live queue is this round's next turn; the typed/BALL path must not race it",
            )

            rig.tick(999)
            assertTrue(display.closes.isEmpty(), "999 ms is still inside the official scheduleExit(1000)")
            assertEquals(1, rig.model.requests.size, "the queued sentence has not been answered yet")

            rig.tick(1)
        } finally {
            P3Log.sink = previous
        }

        assertEquals(
            emptyList<AssistantRoute>(),
            display.closes,
            "the queued sentence must be consumed, not dropped by a close: ${display.closes}",
        )
        assertEquals(2, rig.model.requests.size, "the exit's continuation answers the queued sentence")
        assertEquals("asr-2", rig.model.requests.last().turnId, "the queued sentence's own turn runs")
        assertTrue(
            display.userFinals.any { it.route == AssistantRoute.WAKE && it.text == "第二句" },
            "the queued sentence is this round's user row: ${display.userFinals}",
        )
        assertEquals(
            2,
            display.statuses.count { it == OfficialStatus.LISTENING.wire },
            "the continuation opens its own listening round: ${display.statuses}",
        )
        assertTrue(
            OfficialStatus.AUDIO_FINISHED.wire !in display.statuses,
            "phone-only mode still sends no status 5: ${display.statuses}",
        )
        assertTrue(
            lines.any { it.contains("P3_CONV queue_consumed route=WAKE reason=continue_off") },
            "the exit names the consumed queue: $lines",
        )
        assertTrue(
            lines.none { it.contains("第二句") },
            "the queue evidence carries counts only, never the wearer's words: $lines",
        )
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
                lines.any {
                    it.startsWith("P3_CONV queue_kept route=WAKE reason=continuation_pending")
                },
                "a sentence in the idle gap must still be queued while the stored switch is on: $lines",
            )

            // 0.5.4 Task 5: phone-only mode does not open the *answer* continuation, but the queued sentence is not
            // dead. The answer settles on the continue-off branch and its 1 s exit opens the continuation that answers
            // the queued sentence, so nothing the wearer said is dropped and no close is sent.
            val hintId = rig.output.hints.single().hintId
            rig.plugin.handle(OutputStateEvent(rig.envelope(), hintId, OutputStatus.Displayed))
            assertEquals(1, display.statuses.count { it == OfficialStatus.LISTENING.wire })
            assertFalse(
                rig.plugin.canAcceptTypedMessage(),
                "the live queue is this round's next turn; the typed/BALL path must not race it",
            )

            rig.tick(999)
            assertTrue(display.closes.isEmpty(), "999 ms is still inside the official scheduleExit(1000)")
            assertEquals(1, rig.model.requests.size, "the queued sentence waits for the exit")

            rig.tick(1)
        } finally {
            P3Log.sink = previous
        }

        assertEquals(
            emptyList<AssistantRoute>(),
            display.closes,
            "the queued sentence is continued, not released by a close: ${display.closes}",
        )
        assertEquals(2, rig.model.requests.size, "the exit's continuation answers the queued sentence")
        assertEquals("asr-2", rig.model.requests.last().turnId, "the queued sentence's own turn runs")
        assertTrue(
            display.userFinals.any { it.route == AssistantRoute.WAKE && it.text == "第二句" },
            "the queued sentence is this round's user row: ${display.userFinals}",
        )
        assertEquals(
            2,
            display.statuses.count { it == OfficialStatus.LISTENING.wire },
            "the continuation opens its own listening round: ${display.statuses}",
        )
        assertTrue(
            OfficialStatus.AUDIO_FINISHED.wire !in display.statuses,
            "the continue-off seam still sends no status 5: ${display.statuses}",
        )
        assertTrue(
            lines.any { it.contains("P3_CONV queue_consumed route=WAKE reason=continue_off") },
            "the exit names the consumed queue: $lines",
        )
        assertTrue(
            lines.none { it.contains("第二句") },
            "the queue evidence carries counts only, never the wearer's words: $lines",
        )
    }

    /**
     * 0.5.4 Task 5: the continue-off exit consumes the queued sentence instead of dropping it.
     *
     * The 0.5.1 fix treated a phone-only queue as dead because no answer continuation could ever open. The exit is a
     * continuation seam too: when the 1 s `scheduleExit` fires and a fresh sentence is queued for this route, the round
     * is continued with that sentence as its utterance, so nothing the wearer said is lost and no close is sent.
     */
    @Test
    fun `phone-only continue off consumes a queued utterance`() {
        val (rig, display) = rig(continueDialogue = true, continueAfterAnswer = false, phoneOnly = true)
        rig.model.onRequest = { request, listener -> streamText(request, listener, "第一轮回答。") }

        rig.plugin.handle(rig.wakeEvent())
        rig.plugin.handle(TranscriptEvent(rig.transcript("第一句", turnId = "asr-1")))
        rig.tick()
        assertEquals("第一轮回答。", rig.output.hints.single().text, "the first answer is on screen")

        // The wearer's next sentence lands in the idle gap before the display ACK: the stored switch queues it for the
        // continuation. The answer seam being off changes only which continuation opens, never whether it is consumed.
        rig.plugin.handle(TranscriptEvent(rig.transcript("第二句", turnId = "asr-2", revision = 2)))

        val hintId = rig.output.hints.single().hintId
        val lines = java.util.Collections.synchronizedList(mutableListOf<String>())
        val previous = P3Log.sink
        P3Log.sink = { lines += it }
        try {
            rig.plugin.handle(OutputStateEvent(rig.envelope(), hintId, OutputStatus.Displayed))
            assertTrue(display.closes.isEmpty(), "the continue-off exit is one second away")

            rig.tick(999)
            assertTrue(display.closes.isEmpty(), "999 ms is inside the official scheduleExit(1000)")
            assertEquals(1, rig.model.requests.size, "the queued sentence has not been answered yet")

            rig.tick(1)
        } finally {
            P3Log.sink = previous
        }

        assertEquals(
            emptyList<AssistantRoute>(),
            display.closes,
            "a consumable queue must be continued instead of closing: ${display.closes}",
        )
        assertEquals(2, rig.model.requests.size, "the queued sentence opens the next model turn")
        assertEquals("asr-2", rig.model.requests.last().turnId, "that turn is the queued sentence's own turn")
        assertTrue(
            display.userFinals.any { it.route == AssistantRoute.WAKE && it.text == "第二句" },
            "the queued sentence is published as this round's user row: ${display.userFinals}",
        )
        assertEquals(
            2,
            display.statuses.count { it == OfficialStatus.LISTENING.wire },
            "the continuation opens its own listening round: ${display.statuses}",
        )
        assertTrue(
            OfficialStatus.AUDIO_FINISHED.wire !in display.statuses,
            "the continue-off seam still sends no status 5: ${display.statuses}",
        )
        assertTrue(
            lines.any { it.contains("P3_CONV queue_consumed route=WAKE reason=continue_off") },
            "the consumed queue is named evidence: $lines",
        )
        assertTrue(
            lines.any { it.contains("P3_CONV queued_consumed route=WAKE") },
            "the plugin consumes exactly the route's queued sentence: $lines",
        )
        assertTrue(
            lines.none { it.contains("第二句") },
            "the queue evidence carries counts only, never the wearer's words: $lines",
        )
    }

    /**
     * 0.5.4 Task 5 fix round (review Important 1): the exit's queue predicate is freshness-aware.
     *
     * The take behind `openContinuation` drops a sentence older than `CONTINUATION_QUEUE_TTL_NANOS`, so a route-only
     * predicate would log `queue_consumed` and open an **empty** listening round while that same take silently dropped
     * the sentence - the close would no longer close. A stale slot must take the ordinary close branch instead: the
     * close clears it via `cancelTurn`, and no continuation (empty or otherwise) is opened.
     */
    @Test
    fun `continue off with a stale queued utterance closes instead of opening an empty continuation`() {
        val (rig, display) = rig(continueDialogue = true, continueAfterAnswer = false, phoneOnly = true)
        rig.model.onRequest = { request, listener -> streamText(request, listener, "第一轮回答。") }

        rig.plugin.handle(rig.wakeEvent())
        rig.plugin.handle(TranscriptEvent(rig.transcript("第一句", turnId = "asr-1")))
        rig.tick()
        assertEquals("第一轮回答。", rig.output.hints.single().text, "the first answer is on screen")

        rig.plugin.handle(TranscriptEvent(rig.transcript("第二句", turnId = "asr-2", revision = 2)))
        assertFalse(rig.plugin.canAcceptTypedMessage(), "a fresh queue owns the round while it is consumable")

        val hintId = rig.output.hints.single().hintId
        val lines = java.util.Collections.synchronizedList(mutableListOf<String>())
        val previous = P3Log.sink
        P3Log.sink = { lines += it }
        try {
            // Acknowledge while the hint is fresh: that is what arms the 1 s exit.
            rig.plugin.handle(OutputStateEvent(rig.envelope(), hintId, OutputStatus.Displayed))
            assertTrue(display.closes.isEmpty(), "the continue-off exit is one second away")
            assertEquals(1, rig.model.requests.size, "the stale sentence has not been answered")

            // Age the slot to 121 s old before the exit fires - a sentence captured during a >2 min tool round, with the
            // fake clock standing in for the wait. Moving the clock here is safe because the exit is the only live
            // deadline: the ack cancelled the wake's 10 s window and no speech wait is armed.
            rig.clock.advance(121_000_000_000L)

            rig.tick(1)
        } finally {
            P3Log.sink = previous
        }

        assertEquals(
            listOf(AssistantRoute.WAKE),
            display.closes,
            "a stale queue takes the normal close branch: ${display.closes}",
        )
        assertEquals(1, rig.model.requests.size, "the close opens no continuation, empty or otherwise")
        assertEquals(
            1,
            display.statuses.count { it == OfficialStatus.LISTENING.wire },
            "no second, empty listening round is opened for an unanswerable sentence: ${display.statuses}",
        )
        assertTrue(
            lines.any { it.contains("P3_CONV close route=WAKE reason=continue_off") },
            "the stale slot closes on the ordinary continue-off reason: $lines",
        )
        assertTrue(
            lines.any { it.contains("P3_CONV queue_drop route=WAKE reason=cycle_close") },
            "the close that clears an unconsumed sentence names the drop, so a lost utterance is attributable: $lines",
        )
        assertFalse(
            lines.any { it.contains("P3_CONV queue_consumed") },
            "a dropped sentence must never be reported as consumed: $lines",
        )
        assertTrue(
            rig.plugin.canAcceptTypedMessage(),
            "the close cleared the stale slot; a live one would still keep the typed/BALL path busy",
        )
    }

    /**
     * 0.5.4 Task 8 (§6.1.3): a queue slot is one sentence, latest wins.
     *
     * That replacement is existing behaviour, but it is a real loss of the earlier sentence. The count-only line must
     * name it (`queue_drop reason=superseded`), or a device log cannot tell "the answer never came" from "the wearer
     * said three things and two were replaced".
     */
    @Test
    fun `a newer queued sentence names the one it replaced`() {
        val (rig, _) = rig(continueDialogue = true, continueAfterAnswer = false, phoneOnly = true)
        rig.model.onRequest = { request, listener -> streamText(request, listener, "第一轮回答。") }

        rig.plugin.handle(rig.wakeEvent())
        rig.plugin.handle(TranscriptEvent(rig.transcript("第一句", turnId = "asr-1")))
        rig.tick()
        assertEquals("第一轮回答。", rig.output.hints.single().text, "the first answer is on screen")

        val lines = java.util.Collections.synchronizedList(mutableListOf<String>())
        val previous = P3Log.sink
        P3Log.sink = { lines += it }
        try {
            // Idle gap before the display ACK: each final is queued for the continuation that has not opened yet.
            rig.plugin.handle(TranscriptEvent(rig.transcript("第二句", turnId = "asr-2", revision = 2)))
            rig.plugin.handle(TranscriptEvent(rig.transcript("第三句", turnId = "asr-3", revision = 3)))
        } finally {
            P3Log.sink = previous
        }

        assertTrue(
            lines.count { it.startsWith("P3_CONV queue_kept route=WAKE reason=continuation_pending") } == 2,
            "both finals were kept while the continuation was pending: $lines",
        )
        assertTrue(
            lines.any { it == "P3_CONV queue_drop route=WAKE reason=superseded" },
            "replacing the queued sentence is a drop and must be named: $lines",
        )
        assertTrue(
            lines.none { it.contains("第二句") || it.contains("第三句") },
            "the queue evidence carries counts only, never the wearer's words: $lines",
        )
    }
}
