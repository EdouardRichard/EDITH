package com.sheen.rokid.phone.audio

import com.sheen.rokid.audio.AsrHypothesis
import com.sheen.rokid.audio.PcmFrame
import com.sheen.rokid.audio.SpeakerEngine
import com.sheen.rokid.audio.SpeechPipeline
import com.sheen.rokid.audio.StreamingAsrEngine
import com.sheen.rokid.audio.VadDecision
import com.sheen.rokid.audio.VadEngine
import com.sheen.rokid.contracts.EventEnvelope
import com.sheen.rokid.contracts.Generation
import com.sheen.rokid.contracts.InputEpoch
import com.sheen.rokid.contracts.SampleRange
import com.sheen.rokid.contracts.SessionId
import com.sheen.rokid.contracts.SessionState
import com.sheen.rokid.contracts.SessionStateEvent
import com.sheen.rokid.contracts.SpeakerAssessment
import com.sheen.rokid.contracts.TranscriptRevision
import com.sheen.rokid.plugin.CancellationHandle
import com.sheen.rokid.plugin.SpeakerWindow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The plugin-facing ASR provider: revisions reach every registered listener, in the order the pipeline emitted
 * them, and a cancelled listener stops receiving at once.
 *
 * The pipeline is real (its emit callback is routed into the session) so the tested link is the one production
 * uses: pipeline `TranscriptEvent` -> session listeners. A session that only forwarded calls made directly to
 * itself could pass without ever being connected to a pipeline.
 */
class SherpaAsrSessionTest {

    private class ScriptedVad : VadEngine {
        var speech = false
        var endpoint = false
        override fun accept(frame: PcmFrame) = VadDecision(speech, endpoint)
        override fun reset() = Unit
        override fun close() = Unit
    }

    private class ScriptedAsr(private val finalText: String) : StreamingAsrEngine {
        val hypotheses = ArrayDeque<AsrHypothesis>()
        override fun accept(frame: PcmFrame): List<AsrHypothesis> =
            if (hypotheses.isEmpty()) emptyList() else listOf(hypotheses.removeFirst())
        override fun finish() = AsrHypothesis(finalText, true)
        override fun reset() = Unit
        override fun close() = Unit
    }

    private class SilentSpeaker : SpeakerEngine {
        override fun assess(window: SpeakerWindow, listener: (SpeakerAssessment) -> Unit): CancellationHandle =
            object : CancellationHandle { override fun cancel() = Unit }
        override fun reset() = Unit
        override fun close() = Unit
    }

    private class Rig(finalText: String = "你好世界") {
        val epoch = InputEpoch(SessionId("s-1"), Generation(1), 0)
        val vad = ScriptedVad()
        val asr = ScriptedAsr(finalText)
        val session: SherpaAsrSession
        private val pipeline: SpeechPipeline
        private var offset = 0L

        init {
            // The session needs the pipeline and the pipeline's emit needs the session: production breaks the
            // cycle the same way, by routing the pipeline's emit into the already-registered provider slot.
            val holder = arrayOfNulls<SherpaAsrSession>(1)
            pipeline = SpeechPipeline(vad, asr, SilentSpeaker(),
                emit = { event -> holder[0]?.onEvent(event) }, cancelTurn = {})
            session = SherpaAsrSession(pipeline).also { holder[0] = it }
        }

        fun feed(partial: String? = null, endpoint: Boolean = false) {
            vad.speech = true
            vad.endpoint = endpoint
            if (partial != null) asr.hypotheses += AsrHypothesis(partial, false)
            val first = offset
            offset += 320
            pipeline.accept(PcmFrame(epoch, FloatArray(320) { 0.1f }, 16000,
                SampleRange("c-1", first, offset), first * 62_500L, offset * 62_500L))
        }
    }

    @Test
    fun listenersReceivePartialAndFinalRevisionsInOrder() {
        val rig = Rig()
        val received = mutableListOf<TranscriptRevision>()
        val alsoReceived = mutableListOf<String>()
        rig.session.listen { received += it }
        rig.session.listen { alsoReceived += it.text }

        rig.feed(partial = "你好")
        rig.feed(endpoint = true)

        assertEquals(listOf("你好", "你好世界"), received.map { it.text })
        assertEquals(listOf(false, true), received.map { it.final })
        assertEquals(listOf(0L, 1L), received.map { it.revision })
        assertEquals(listOf("你好", "你好世界"), alsoReceived)
    }

    @Test
    fun aCancelledListenerReceivesNothingFurtherAndCancellingTwiceIsSafe() {
        val rig = Rig()
        val first = mutableListOf<TranscriptRevision>()
        val second = mutableListOf<TranscriptRevision>()
        val handle = rig.session.listen { first += it }
        rig.session.listen { second += it }

        rig.feed(partial = "你好")
        assertEquals(1, first.size)
        assertEquals(1, second.size)

        handle.cancel()
        handle.cancel()

        rig.feed(partial = "你好世界")
        assertEquals("a cancelled listener must receive nothing further", 1, first.size)
        assertEquals(2, second.size)
    }

    @Test
    fun eventsThatAreNotTranscriptsAreIgnored() {
        val rig = Rig()
        val received = mutableListOf<TranscriptRevision>()
        rig.session.listen { received += it }

        rig.session.onEvent(SessionStateEvent(
            EventEnvelope(SessionId("s-1"), Generation(1), "e-1", 0, 0), SessionState.Ready))

        assertTrue("only transcripts are revisions", received.isEmpty())
    }

    /**
     * Task 11b (Minor 6): the cancellation contract is pinned rather than assumed.
     *
     * Listeners are invoked **outside** the session's lock, so a `cancel()` racing an in-flight dispatch can
     * leave at most the revision that dispatch had already selected: a cancelled listener receives nothing
     * *newer* than that, and because the cancelled listener is skipped the moment its own handle is cancelled,
     * even a listener cancelled by an earlier listener in the same dispatch receives nothing more. Delivery
     * under the lock was the alternative the brief offered; it would let a listener's callback deadlock the
     * pipeline thread that produces revisions, which is the worse failure for a live audio path.
     */
    @Test
    fun aListenerCancelledDuringADispatchReceivesNothingFromThatPointOn() {
        val rig = Rig()
        val first = mutableListOf<TranscriptRevision>()
        val second = mutableListOf<TranscriptRevision>()
        lateinit var secondHandle: CancellationHandle
        rig.session.listen {
            first += it
            // The first listener cancels the second while this very revision is being delivered.
            secondHandle.cancel()
        }
        secondHandle = rig.session.listen { second += it }

        rig.feed(partial = "你好")
        assertTrue("a listener cancelled inside a dispatch must not be invoked after the cancellation",
            second.isEmpty())

        rig.feed(partial = "你好世界")
        assertEquals("the survivor keeps receiving every later revision", 2, first.size)
        assertTrue("the cancelled listener never resumes", second.isEmpty())
    }

    @Test
    fun everyListenerRegisteredBeforeARevisionReceivesThatRevisionInRegistrationOrder() {
        val rig = Rig()
        val order = mutableListOf<String>()
        rig.session.listen { order += "a:${it.text}" }
        rig.session.listen { order += "b:${it.text}" }

        rig.feed(partial = "你好")

        assertEquals(listOf("a:你好", "b:你好"), order)
    }
}
