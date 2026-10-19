package com.sheen.rokid.output

import com.sheen.rokid.contracts.SpeechResult
import com.sheen.rokid.contracts.ToolPhase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 0.24 spec §3.3 / §5.3: the official 「乐奇」 conversation, pinned status by status.
 *
 * These rows are the only place the Hi line, the ASR "…" row, the tips copy, BACK and the two official timeouts
 * are decided. Later tasks consume [OfficialConversation] on the phone, on the wire and on the glasses, so a drift
 * must fail a JVM test here instead of needing the panel on a face.
 */
class OfficialConversationTest {

    private val listening = listOf(OfficialStatus.EMPTY, OfficialStatus.LISTENING)

    private val notListening = listOf(
        OfficialStatus.ASR,
        OfficialStatus.ANSWERING,
        OfficialStatus.ASR_END,
        OfficialStatus.AUDIO_FINISHED,
        OfficialStatus.TOOL,
    )

    @Test
    fun `the listening indicator is visible only while waiting for speech`() {
        listening.forEach { status ->
            assertTrue(OfficialConversation.listeningVisible(status, idle = false, hiSeen = false), "$status")
        }
        notListening.forEach { status ->
            assertFalse(OfficialConversation.listeningVisible(status, idle = false, hiSeen = false), "$status")
        }
    }

    @Test
    fun `a continuing turn keeps the listening indicator after Hi was seen`() {
        // Official `showListenerUI` never restores `idTvSpeak`: the next turn listens with the "…" row + listener orb
        // only, and the listening indicator itself must stay on screen.
        assertTrue(OfficialConversation.listeningVisible(OfficialStatus.LISTENING, idle = false, hiSeen = true))
    }

    @Test
    fun `idle has no listening indicator`() {
        listening.forEach { status ->
            assertFalse(OfficialConversation.listeningVisible(status, idle = true, hiSeen = true), "$status")
        }
    }

    @Test
    fun `the ASR ellipsis is visible only while waiting for speech`() {
        listening.forEach { status ->
            assertTrue(OfficialConversation.asrLoadingVisible(status), "$status")
        }
        notListening.forEach { status ->
            assertFalse(OfficialConversation.asrLoadingVisible(status), "$status")
        }
    }

    @Test
    fun `Hi is visible only on the first open of a listening status`() {
        assertTrue(OfficialConversation.hiVisible(OfficialStatus.EMPTY, firstOpen = true))
        assertFalse(OfficialConversation.hiVisible(OfficialStatus.LISTENING, firstOpen = false))
        assertTrue(OfficialConversation.hiVisible(OfficialStatus.LISTENING, firstOpen = true))
        assertFalse(OfficialConversation.hiVisible(OfficialStatus.ASR, firstOpen = true))
        assertFalse(OfficialConversation.hiVisible(OfficialStatus.AUDIO_FINISHED, firstOpen = true))
        notListening.forEach { status ->
            assertFalse(OfficialConversation.hiVisible(status, firstOpen = true), "$status")
        }
    }

    @Test
    fun `tips ask to interrupt only when pinned and answering`() {
        assertEquals(
            OfficialConversation.TIPS_STOP,
            OfficialConversation.tipsText(OfficialStatus.ANSWERING, pinned = true),
        )
        assertEquals(
            OfficialConversation.TIPS_EXIT,
            OfficialConversation.tipsText(OfficialStatus.ANSWERING, pinned = false),
        )
        OfficialStatus.entries.forEach { status ->
            assertEquals(
                OfficialConversation.TIPS_EXIT,
                OfficialConversation.tipsText(status, pinned = false),
                "$status unpinned",
            )
        }
        OfficialStatus.entries.filter { it != OfficialStatus.ANSWERING }.forEach { status ->
            assertEquals(
                OfficialConversation.TIPS_EXIT,
                OfficialConversation.tipsText(status, pinned = true),
                "$status pinned",
            )
        }
    }

    @Test
    fun `BACK exits unless pinned while answering, where it stops`() {
        assertEquals(BackAction.EXIT, OfficialConversation.backAction(OfficialStatus.LISTENING, pinned = true))
        assertEquals(BackAction.STOP, OfficialConversation.backAction(OfficialStatus.ANSWERING, pinned = true))
        assertEquals(BackAction.EXIT, OfficialConversation.backAction(OfficialStatus.ANSWERING, pinned = false))
        assertEquals(BackAction.EXIT, OfficialConversation.backAction(OfficialStatus.ASR_END, pinned = true))
        OfficialStatus.entries.forEach { status ->
            assertEquals(
                BackAction.EXIT,
                OfficialConversation.backAction(status, pinned = false),
                "$status unpinned",
            )
        }
        OfficialStatus.entries.filter { it != OfficialStatus.ANSWERING }.forEach { status ->
            assertEquals(
                BackAction.EXIT,
                OfficialConversation.backAction(status, pinned = true),
                "$status pinned",
            )
        }
    }

    @Test
    fun `the official timeouts are exact`() {
        assertEquals(10_000L, OfficialConversation.LISTEN_TIMEOUT_MILLIS)
        assertEquals(120_000L, OfficialConversation.PINNED_CLOSE_MILLIS)
        // Official `RokidAIController.TIME_WAITE_TTS` is a 1 200 ms cue cadence; EDITH keeps the decompiled value but
        // never uses it as the end-of-turn bound (R106).
        assertEquals(1_200L, OfficialConversation.SPEECH_WAIT_MILLIS)
        // R106: the terminal liveness bound for a missing `SpeechFinished`. Long TTS may outlive the display window, so
        // the old display window + 1 200 ms bound cut the audio; this one is bounded but long enough to let it finish.
        assertEquals(120_000L, OfficialConversation.SPEECH_LIVENESS_MILLIS)
    }

    @Test
    fun `the official statuses carry their wire numbers`() {
        assertEquals(0, OfficialStatus.EMPTY.wire)
        assertEquals(1, OfficialStatus.LISTENING.wire)
        assertEquals(2, OfficialStatus.ASR.wire)
        assertEquals(3, OfficialStatus.ANSWERING.wire)
        assertEquals(4, OfficialStatus.ASR_END.wire)
        assertEquals(5, OfficialStatus.AUDIO_FINISHED.wire)
        assertEquals(7, OfficialStatus.TOOL.wire)
        assertEquals(7, OfficialStatus.entries.size, "status 6 is the official JSUI card and is out of EDITH's scope")
    }

    @Test
    fun `the tips copy is the official copy, not a paraphrase`() {
        assertEquals("滑动翻页 / 双击退出", OfficialConversation.TIPS_EXIT)
        assertEquals("滑动翻页 / 双击打断", OfficialConversation.TIPS_STOP)
    }

    @Test
    fun `pin never stops the ticker`() {
        // D133 被 0.24 取代: pin is one-way "do not auto-exit"; the ticker is only stopped by the wearer scrolling,
        // reaching the end, or the panel hiding (spec §3.3/§5.3).
        assertFalse(OfficialConversation.pinStopsTicker())
    }

    @Test
    fun `the shared tool-phase and speech-result contracts keep their names`() {
        assertEquals(listOf("START", "END"), ToolPhase.entries.map { it.name })
        assertEquals(listOf("FINISHED", "FAILED"), SpeechResult.entries.map { it.name })
    }
}
