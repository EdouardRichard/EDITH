package com.sheen.rokid.audio

import com.sheen.rokid.contracts.*
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import kotlin.test.*

/**
 * Task 12a (0.4 spec §5.3, D204): the `SpeechPipeline` seam a phone-mic dictation controller needs - a redirectable
 * event sink and a forced end-of-turn for the user's stop button, which is not a VAD endpoint.
 *
 * The constructor sink is the production wake route. Every test that installs no redirect also proves that route
 * keeps receiving the same events it did before the seam existed.
 */
class SpeechPipelineDictationTest {
    private val epoch = InputEpoch(SessionId("dictation-session"), Generation(1), 1)

    private fun frame(index: Int, continuity: String = "dictation") = PcmFrame(
        epoch, FloatArray(320), 16000, SampleRange(continuity, index * 320L, (index + 1) * 320L),
        index * 20_000_000L, (index + 1) * 20_000_000L)

    private fun envelope(frame: PcmFrame) = EventEnvelope(frame.epoch.sessionId, frame.epoch.generation,
        "dictation-gap", frame.startNanos, frame.endNanos)

    private class Fixture : AutoCloseable {
        val vad = ScriptedVad()
        val asr = RecordingAsr()
        val speaker = PendingSpeaker()
        val host = CopyOnWriteArrayList<HostEvent>()
        val redirected = CopyOnWriteArrayList<HostEvent>()
        val cancelled = CopyOnWriteArrayList<String>()
        val pipeline = SpeechPipeline(vad, asr, speaker, { host += it }, cancelled::add)
        val hostTranscripts get() = host.filterIsInstance<TranscriptEvent>().map { it.revision }
        override fun close() = pipeline.close()
    }

    @Test fun `a redirected final reaches the sink and not the constructor emit`() {
        Fixture().use { f ->
            f.pipeline.accept(frame(0))
            assertTrue(f.hostTranscripts.any { !it.final }, "the wake route gets partials when not redirected")

            f.pipeline.redirectEmit { f.redirected += it }
            f.vad.decision = VadDecision(false, true)
            f.pipeline.accept(frame(1))

            val final = f.redirected.filterIsInstance<TranscriptEvent>().single().revision
            assertTrue(final.final)
            assertTrue(f.hostTranscripts.none { it.final }, "the constructor emit must not receive the redirected final")
            assertEquals(1, f.asr.finishes)
        }
    }

    @Test fun `a forced dictation final follows the redirect`() {
        Fixture().use { f ->
            f.pipeline.redirectEmit { f.redirected += it }
            f.pipeline.accept(frame(0))

            assertTrue(f.pipeline.finishCurrentTurn())

            val final = f.redirected.filterIsInstance<TranscriptEvent>().single { it.revision.final }.revision
            assertTrue(final.final)
            assertTrue(f.hostTranscripts.none { it.final })
        }
    }

    @Test fun `restoring the constructor emit receives the next final`() {
        Fixture().use { f ->
            f.pipeline.redirectEmit { f.redirected += it }
            f.pipeline.accept(frame(0))
            assertTrue(f.host.isEmpty(), "redirected partial must not reach the constructor emit")
            assertTrue(f.redirected.filterIsInstance<TranscriptEvent>().isNotEmpty())

            f.pipeline.redirectEmit(null)
            f.vad.decision = VadDecision(false, true)
            f.pipeline.accept(frame(1))

            val final = f.hostTranscripts.single()
            assertTrue(final.final)
            assertTrue(f.redirected.filterIsInstance<TranscriptEvent>().none { it.revision.final })
        }
    }

    @Test fun `a redirect carries every emitted event kind and not only transcripts`() {
        Fixture().use { f ->
            f.pipeline.redirectEmit { f.redirected += it }
            f.pipeline.discontinuity(envelope(frame(0)), GapReason.OVERFLOW)

            assertTrue(f.redirected.filterIsInstance<DiscontinuityEvent>().isNotEmpty())
            assertTrue(f.host.isEmpty())
        }
    }

    @Test fun `finishCurrentTurn emits exactly one final for the active turn and clears it`() {
        Fixture().use { f ->
            f.pipeline.accept(frame(0))
            f.pipeline.accept(frame(1))
            val partial = f.hostTranscripts.last()
            assertFalse(partial.final)

            assertTrue(f.pipeline.finishCurrentTurn())

            val final = f.hostTranscripts.single { it.final }
            assertEquals(partial.turnId, final.turnId)
            assertEquals(partial.revision + 1, final.revision)
            assertEquals(0L, final.startNanos, "the revision keeps the turn's original start")
            assertEquals(40_000_000L, final.endNanos, "the revision ends at the last accepted frame")
            assertEquals(1, f.asr.finishes)
            assertEquals(1, f.vad.resets)
            assertEquals(1, f.asr.resets)

            // The turn is really cleared: a second stop is a no-op and new speech allocates a new turn.
            assertFalse(f.pipeline.finishCurrentTurn())
            assertEquals(1, f.hostTranscripts.count { it.final })
            f.pipeline.accept(frame(2))
            assertNotEquals(partial.turnId, f.hostTranscripts.last().turnId)
        }
    }

    @Test fun `finishCurrentTurn is false when no turn is active`() {
        Fixture().use { f ->
            assertFalse(f.pipeline.finishCurrentTurn())
            assertTrue(f.host.isEmpty())
            assertEquals(0, f.asr.finishes)

            f.vad.decision = VadDecision(false, false)
            repeat(3) { f.pipeline.accept(frame(it)) }
            assertFalse(f.pipeline.finishCurrentTurn(), "pre-roll silence is not an active turn")
            assertTrue(f.host.isEmpty())
            assertEquals(0, f.asr.finishes)
        }
    }

    @Test fun `finishCurrentTurn emits nothing after close`() {
        Fixture().use { f ->
            f.pipeline.accept(frame(0))
            val beforeClose = f.host.toList()
            f.pipeline.close()

            assertFalse(f.pipeline.finishCurrentTurn())
            assertEquals(beforeClose, f.host.toList())
            assertTrue(f.hostTranscripts.none { it.final })
        }
    }

    @Test fun `finishCurrentTurn does not duplicate a final already emitted by the VAD endpoint`() {
        Fixture().use { f ->
            f.pipeline.accept(frame(0))
            f.vad.decision = VadDecision(false, true)
            f.pipeline.accept(frame(1))
            assertEquals(1, f.hostTranscripts.count { it.final })

            assertFalse(f.pipeline.finishCurrentTurn(), "the endpoint already cleared the turn")

            assertEquals(1, f.hostTranscripts.count { it.final })
            assertEquals(1, f.asr.finishes)
        }
    }

    @Test fun `finishCurrentTurn does not duplicate a final the ASR already sealed`() {
        Fixture().use { f ->
            f.asr.hypotheses = listOf(AsrHypothesis("already final", true))
            f.pipeline.accept(frame(0))
            assertEquals(1, f.hostTranscripts.count { it.final })

            assertTrue(f.pipeline.finishCurrentTurn(), "a sealed turn was still active")
            assertEquals(1, f.hostTranscripts.count { it.final })
            assertEquals(1, f.asr.finishes)
            assertEquals(1, f.vad.resets)
        }
    }

    @Test fun `concurrent finishCurrentTurn calls produce exactly one final`() {
        Fixture().use { f ->
            f.pipeline.accept(frame(0))
            val start = CountDownLatch(1)
            val first = FutureTask { start.await(2, TimeUnit.SECONDS); f.pipeline.finishCurrentTurn() }
            val second = FutureTask { start.await(2, TimeUnit.SECONDS); f.pipeline.finishCurrentTurn() }
            Thread(first).start()
            Thread(second).start()
            start.countDown()

            val results = listOf(first.get(2, TimeUnit.SECONDS), second.get(2, TimeUnit.SECONDS))
            assertEquals(1, results.count { it })
            assertEquals(1, f.hostTranscripts.count { it.final })
            assertEquals(1, f.asr.finishes)
        }
    }
}
