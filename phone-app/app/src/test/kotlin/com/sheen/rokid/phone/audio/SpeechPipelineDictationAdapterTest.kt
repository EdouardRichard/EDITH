package com.sheen.rokid.phone.audio

import com.sheen.rokid.audio.AsrHypothesis
import com.sheen.rokid.audio.PcmFrame
import com.sheen.rokid.audio.SpeakerEngine
import com.sheen.rokid.audio.SpeechPipeline
import com.sheen.rokid.audio.StreamingAsrEngine
import com.sheen.rokid.audio.VadDecision
import com.sheen.rokid.audio.VadEngine
import com.sheen.rokid.contracts.Generation
import com.sheen.rokid.contracts.HostEvent
import com.sheen.rokid.contracts.InputEpoch
import com.sheen.rokid.contracts.SampleRange
import com.sheen.rokid.contracts.SessionId
import com.sheen.rokid.contracts.SpeakerAssessment
import com.sheen.rokid.contracts.TranscriptEvent
import com.sheen.rokid.plugin.CancellationHandle
import com.sheen.rokid.plugin.SpeakerWindow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 0.4 Task 12b-ii (spec §5.3, D204): the production [DictationPipeline] adapter over the real [SpeechPipeline].
 *
 * Task 12a proved the pipeline seam itself. This file proves the adapter the host actually installs delegates
 * `redirect`/`finishCurrentTurn` to that seam without changing anything else: redirected finals stay off the
 * constructor sink, a forced finish produces exactly one final through the redirect, restoring the route hands the
 * next final back to the wake sink, and the active-turn query (the host's "do not steal a wake turn" refusal) tracks
 * the pipeline's own turn.
 */
class SpeechPipelineDictationAdapterTest {

    private val epoch = InputEpoch(SessionId("s-dictation-adapter"), Generation(1), 1)

    private fun frame(index: Int) = PcmFrame(
        epoch,
        FloatArray(320),
        16000,
        SampleRange("dictation-adapter", index * 320L, (index + 1) * 320L),
        index * 20_000_000L,
        (index + 1) * 20_000_000L,
    )

    private class ScriptedVad : VadEngine {
        var decision = VadDecision(true, false)
        override fun accept(frame: PcmFrame) = decision
        override fun reset() = Unit
        override fun close() = Unit
    }

    private class RecordingAsr : StreamingAsrEngine {
        var finalText = "手机听写的一句话"
        val frames = mutableListOf<PcmFrame>()
        override fun accept(frame: PcmFrame): List<AsrHypothesis> {
            frames += frame
            return listOf(AsrHypothesis("部分", false))
        }

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

    private class Fixture : AutoCloseable {
        val vad = ScriptedVad()
        val asr = RecordingAsr()
        val host = CopyOnWriteArrayList<HostEvent>()
        val redirected = CopyOnWriteArrayList<HostEvent>()
        val pipeline = SpeechPipeline(vad, asr, SilentSpeaker(), { host += it }, {})
        val adapter = SpeechPipelineDictation(pipeline)

        fun hostFinals() = host.filterIsInstance<TranscriptEvent>().map { it.revision }.filter { it.final }
        fun redirectedFinals() = redirected.filterIsInstance<TranscriptEvent>().map { it.revision }.filter { it.final }

        override fun close() = pipeline.close()
    }

    @Test
    fun `the adapter redirects the real pipeline and its forced finish emits through that redirect`() {
        Fixture().use { fixture ->
            fixture.pipeline.accept(frame(0))
            assertTrue("the wake sink gets the partial before any redirect", fixture.host.isNotEmpty())

            fixture.adapter.redirect { event -> fixture.redirected += event }
            assertTrue("the active speech frame is a turn", fixture.adapter.hasActiveTurn())
            assertTrue(fixture.adapter.finishCurrentTurn())

            val final = fixture.redirectedFinals().single()
            assertEquals("手机听写的一句话", final.text)
            assertTrue(final.final)
            assertTrue("the constructor emit must not see the redirected final", fixture.hostFinals().isEmpty())
            assertFalse("finishing clears the turn the host queries", fixture.adapter.hasActiveTurn())
        }
    }

    @Test
    fun `restoring the null redirect hands the next final back to the wake sink`() {
        Fixture().use { fixture ->
            fixture.adapter.redirect { event -> fixture.redirected += event }
            fixture.pipeline.accept(frame(0))
            assertTrue(fixture.adapter.finishCurrentTurn())
            assertEquals(1, fixture.redirectedFinals().size)

            fixture.adapter.redirect(null)
            // One frame both opens and ends a turn: the endpoint is what makes the pipeline finalise it on its own.
            fixture.vad.decision = VadDecision(true, true)
            fixture.pipeline.accept(frame(1))

            assertEquals("the restored route receives the next final", 1, fixture.hostFinals().size)
            assertEquals("the redirect saw only its own final", 1, fixture.redirectedFinals().size)
        }
    }

    @Test
    fun `finishCurrentTurn reports false through the adapter when no turn is active`() {
        Fixture().use { fixture ->
            fixture.adapter.redirect { event -> fixture.redirected += event }
            assertFalse(fixture.adapter.finishCurrentTurn())
            assertFalse(fixture.adapter.hasActiveTurn())
            assertTrue(fixture.redirected.isEmpty())
        }
    }
}
