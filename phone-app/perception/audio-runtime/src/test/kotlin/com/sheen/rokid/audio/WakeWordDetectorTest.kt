package com.sheen.rokid.audio

import com.sheen.rokid.contracts.Generation
import com.sheen.rokid.contracts.InputEpoch
import com.sheen.rokid.contracts.SampleRange
import com.sheen.rokid.contracts.SessionId
import com.sheen.rokid.testsupport.FakeClock
import kotlin.test.*

class WakeWordDetectorTest {
    private val epoch = InputEpoch(SessionId("wake-session"), Generation(1), 1)
    private fun frame(index: Int, samples: FloatArray = FloatArray(320) { it.toFloat() / 320f }) = PcmFrame(
        epoch, samples, 16000, SampleRange("wake", index * 320L, (index + 1) * 320L),
        index * 20_000_000L, (index + 1) * 20_000_000L)

    private class Recorder {
        val emits = mutableListOf<Pair<String, Double>>()
        val words get() = emits.map { it.first }
        fun emit(word: String, confidence: Double) { emits += word to confidence }
    }

    // ---- Feeding and the sustained-silence release (D11) --------------------------------------------

    @Test fun `D11 the wake word is detected even though the vad only reports speech after it has passed`() {
        // The clip is 1 s of 20 ms frames. The word physically occupies the first 400 ms (frames 0-19) and the
        // scripted VAD is the device's measured one: its first `speech` frame lands 500 ms in (frame 25). If the
        // detector gates feeding on the VAD, frames 0-19 never reach the engine and the word is unreachable.
        val onset = (0 until 20).map { frame(it).range }.toSet()
        val engine = OnsetGatedWakeWordEngine("伊迪丝", onset)
        val vad = ScriptedVad().apply { decisionFor = { f -> VadDecision(f.range.first >= 8_000L, false) } }
        val recorder = Recorder()
        val detector = WakeWordDetector(vad, engine, recorder::emit, FakeClock())
        try {
            repeat(50) { detector.accept(frame(it)) }
            assertTrue(engine.accepted.contains(frame(0).range), "the onset frame must reach the engine")
            assertEquals(listOf("伊迪丝"), recorder.words)
        } finally { detector.close() }
    }

    @Test fun `engine receives every frame even while the vad reports silence and a match there is emitted`() {
        val vad = ScriptedVad().apply { decision = VadDecision(false, false) }
        val engine = RecordingWakeWordEngine().apply { words = listOf("edith") }
        val recorder = Recorder()
        val detector = WakeWordDetector(vad, engine, recorder::emit, FakeClock())
        try {
            detector.accept(frame(0))
            detector.accept(frame(1))
            detector.accept(frame(2))
            assertEquals(listOf(frame(0).range, frame(1).range, frame(2).range), engine.accepted)
            // The emission rides the engine's return value, not the VAD's decision: a wake word can fall inside
            // the leading frames the VAD still calls silence.
            assertEquals(listOf("edith"), recorder.words)
            assertEquals(listOf(1.0), recorder.emits.map { it.second })
            // Silence before any speech has no segment to close, so it must not release.
            assertEquals(0, engine.resets)
        } finally { detector.close() }
    }

    @Test fun `the stream survives a short silence and is released after the configured sustained silence`() {
        val vad = ScriptedVad()
        val engine = RecordingWakeWordEngine()
        val recorder = Recorder()
        val clock = FakeClock()
        val detector = WakeWordDetector(vad, engine, recorder::emit, clock, silenceReleaseNanos = 1_500_000_000L)
        try {
            detector.accept(frame(0))                        // speech: opens the silence window at t = 0
            vad.decision = VadDecision(false, false)
            clock.advance(500_000_000)
            detector.accept(frame(1))                        // 0.5 s of silence: survives
            clock.advance(999_999_999)
            detector.accept(frame(2))                        // 1.499999999 s: still inside the window
            assertEquals(0, engine.resets)
            assertEquals(3, engine.accepted.size)            // silence frames are fed, not dropped
            clock.advance(1)
            detector.accept(frame(3))                        // 1.5 s: release exactly once
            assertEquals(1, engine.resets)
            detector.accept(frame(4))                        // continued silence is not a second release
            assertEquals(1, engine.resets)
            assertEquals(5, engine.accepted.size)            // feeding continues across the release
        } finally { detector.close() }
    }

    @Test fun `each utterance gets its own sustained silence release and speech reopens the window`() {
        val vad = ScriptedVad()
        val engine = RecordingWakeWordEngine()
        val recorder = Recorder()
        val clock = FakeClock()
        val detector = WakeWordDetector(vad, engine, recorder::emit, clock, silenceReleaseNanos = 1_000_000_000L)
        try {
            detector.accept(frame(0))                        // utterance 1
            vad.decision = VadDecision(false, false)
            clock.advance(1_000_000_000)
            detector.accept(frame(1))                        // release 1
            assertEquals(1, engine.resets)
            vad.decision = VadDecision(true, false)
            clock.advance(1_000_000_000)
            detector.accept(frame(2))                        // speech reopens the window
            assertEquals(1, engine.resets)
            vad.decision = VadDecision(false, false)
            clock.advance(999_999_999)
            detector.accept(frame(3))                        // inside the new window
            assertEquals(1, engine.resets)
            clock.advance(1)
            detector.accept(frame(4))                        // release 2
            assertEquals(2, engine.resets)
            assertEquals(5, engine.accepted.size)
            assertTrue(recorder.emits.isEmpty())
        } finally { detector.close() }
    }

    // ---- Cooldown -----------------------------------------------------------------------------------

    @Test fun `repeated word inside the cooldown emits once and again at the cooldown boundary`() {
        val vad = ScriptedVad()
        val engine = RecordingWakeWordEngine().apply { words = listOf("伊迪丝") }
        val clock = FakeClock()
        val recorder = Recorder()
        val detector = WakeWordDetector(vad, engine, recorder::emit, clock)
        try {
            detector.accept(frame(0))
            clock.advance(1_000_000_000)
            detector.accept(frame(1))
            clock.advance(999_999_999)
            detector.accept(frame(2))
            assertEquals(listOf("伊迪丝"), recorder.words)
            clock.advance(1)
            detector.accept(frame(3))
            assertEquals(listOf("伊迪丝", "伊迪丝"), recorder.words)
        } finally { detector.close() }
    }

    @Test fun `cooldown is per word so a different word is not blocked`() {
        val vad = ScriptedVad()
        val engine = RecordingWakeWordEngine().apply { words = listOf("edith") }
        val clock = FakeClock()
        val recorder = Recorder()
        val detector = WakeWordDetector(vad, engine, recorder::emit, clock)
        try {
            detector.accept(frame(0))
            engine.words = listOf("伊迪丝")
            detector.accept(frame(1))
            engine.words = listOf("edith")
            detector.accept(frame(2))
            assertEquals(listOf("edith", "伊迪丝"), recorder.words)
        } finally { detector.close() }
    }

    // ---- Self-audio suppression ---------------------------------------------------------------------

    @Test fun `suppression gate blocks forwarding and emission until it clears`() {
        val vad = ScriptedVad()
        val engine = RecordingWakeWordEngine().apply { words = listOf("edith") }
        val recorder = Recorder()
        var suppressed = true
        val detector = WakeWordDetector(vad, engine, recorder::emit, FakeClock(), suppressWhile = { suppressed })
        try {
            detector.accept(frame(0))
            assertTrue(engine.accepted.isEmpty())
            assertTrue(recorder.emits.isEmpty())
            suppressed = false
            detector.accept(frame(1))
            assertEquals(1, engine.accepted.size)
            assertEquals(listOf("edith"), recorder.words)
            suppressed = true
            detector.accept(frame(2))
            assertEquals(1, engine.accepted.size)
            assertEquals(listOf("edith"), recorder.words)
        } finally { detector.close() }
    }

    // ---- reset / close ------------------------------------------------------------------------------

    @Test fun `reset clears the cooldown and the engine state and close is idempotent`() {
        val vad = ScriptedVad()
        val engine = RecordingWakeWordEngine().apply { words = listOf("edith") }
        val recorder = Recorder()
        val detector = WakeWordDetector(vad, engine, recorder::emit, FakeClock())
        detector.accept(frame(0))
        assertEquals(listOf("edith"), recorder.words)
        detector.reset()
        assertEquals(1, engine.resets)
        detector.accept(frame(1))
        assertEquals(listOf("edith", "edith"), recorder.words)
        detector.close()
        detector.close()
        assertEquals(1, engine.closes)
        assertFailsWith<IllegalStateException> { detector.accept(frame(2)) }
    }

    // ---- defaultWakeNormalization -------------------------------------------------------------------

    @Test fun `default normalization folds full width case spacing and punctuation`() {
        assertEquals("edith", defaultWakeNormalization("edith"))
        assertEquals("edith", defaultWakeNormalization("Edith!"))
        assertEquals("edith", defaultWakeNormalization("ＥＤＩＴＨ"))
        assertEquals("edith", defaultWakeNormalization("  E d i t h ?  "))
        assertEquals("伊迪丝", defaultWakeNormalization("伊 迪 丝"))
        assertEquals("伊迪丝", defaultWakeNormalization("伊迪丝。"))
        assertEquals("", defaultWakeNormalization("   "))
        assertNotEquals(defaultWakeNormalization("edith"), defaultWakeNormalization("edison"))
    }

    // ---- wakeWordForTokenisation --------------------------------------------------------------------

    @Test fun `tokenisation form uppercases ascii so the uppercase only lexicon cannot skip the word`() {
        assertEquals("EDITH", wakeWordForTokenisation("edith"))
        assertEquals("EDITH", wakeWordForTokenisation("Edith"))
        assertEquals("EDITH", wakeWordForTokenisation("  edith "))
        assertEquals("伊迪丝", wakeWordForTokenisation("伊迪丝"))
        assertEquals("伊迪丝", wakeWordForTokenisation("  伊迪丝  "))
        assertEquals("", wakeWordForTokenisation("   "))
    }

    // ---- AsrTextWakeWordMatcher ---------------------------------------------------------------------

    @Test fun `asr text matcher returns configured words found in partial and final hypotheses`() {
        val asr = RecordingAsr()
        val matcher = AsrTextWakeWordMatcher(asr, listOf("伊迪丝", "edith"))
        asr.hypotheses = listOf(AsrHypothesis("你好，伊迪丝在吗", false))
        assertEquals(listOf("伊迪丝"), matcher.accept(frame(0)))
        assertEquals(listOf(SampleRange("wake", 0, 320)), asr.accepted)
        assertEquals(0, asr.finishes)
        asr.hypotheses = listOf(AsrHypothesis("EDITH, are you there?", true))
        assertEquals(listOf("edith"), matcher.accept(frame(1)))
        assertEquals(0, asr.finishes)
    }

    @Test fun `asr text matcher reports a word present in both partial and final hypothesis once`() {
        val asr = RecordingAsr()
        val matcher = AsrTextWakeWordMatcher(asr, listOf("伊迪丝"))
        asr.hypotheses = listOf(AsrHypothesis("伊迪丝", false), AsrHypothesis("伊迪丝 我在", true))
        assertEquals(listOf("伊迪丝"), matcher.accept(frame(0)))
    }

    @Test fun `asr text matcher returns nothing for blank or unmatched hypotheses`() {
        val asr = RecordingAsr()
        val matcher = AsrTextWakeWordMatcher(asr, listOf("伊迪丝", "edith"))
        asr.hypotheses = listOf(AsrHypothesis("", false))
        assertTrue(matcher.accept(frame(0)).isEmpty())
        asr.hypotheses = listOf(AsrHypothesis("   ", true))
        assertTrue(matcher.accept(frame(1)).isEmpty())
        asr.hypotheses = listOf(AsrHypothesis("edison was here", false))
        assertTrue(matcher.accept(frame(2)).isEmpty())
        assertEquals(3, asr.accepted.size)
        assertEquals(0, asr.finishes)
    }

    @Test fun `asr text matcher uses the injected normalizer instead of the default`() {
        val asr = RecordingAsr().apply { hypotheses = listOf(AsrHypothesis("edith", false)) }
        assertTrue(AsrTextWakeWordMatcher(asr, listOf("Edith"), normalize = { it }).accept(frame(0)).isEmpty())
        assertEquals(listOf("Edith"), AsrTextWakeWordMatcher(asr, listOf("Edith")).accept(frame(1)))
    }

    @Test fun `asr text matcher delegates reset and close once`() {
        val asr = RecordingAsr()
        val matcher = AsrTextWakeWordMatcher(asr, listOf("edith"))
        matcher.reset()
        assertEquals(1, asr.resets)
        matcher.close()
        matcher.close()
        assertEquals(1, asr.closes)
    }

    // ---- KeywordSpotterWakeWordEngine ---------------------------------------------------------------

    private class KeywordFixture(ready: Boolean = true, hit: KeywordHit? = null) {
        val port = FakeKeywordSpotterPort().apply { this.ready = ready; this.hit = hit }
        val engine = KeywordSpotterWakeWordEngine(port, "伊迪丝 @伊迪丝")
    }

    @Test fun `keyword engine forwards the frame samples and rate and returns the decoded hit`() {
        val fixture = KeywordFixture(hit = KeywordHit("伊迪丝", listOf("y", "i"), floatArrayOf(0f, 0.1f)))
        val samples = FloatArray(320) { it.toFloat() / 320f }
        try {
            assertEquals(listOf("伊迪丝"), fixture.engine.accept(frame(0, samples)))
            assertEquals(1, fixture.port.creates)
            assertEquals("伊迪丝 @伊迪丝", fixture.port.streams.single().keywords)
            assertEquals(1, fixture.port.streams.single().accepts)
            assertEquals(16000, fixture.port.streams.single().sampleRateHz)
            assertContentEquals(samples, fixture.port.streams.single().lastSamples!!)
            assertEquals(1, fixture.port.decodes)
        } finally { fixture.engine.close() }
    }

    @Test fun `keyword engine skips decode while the stream is not ready`() {
        val fixture = KeywordFixture(ready = false, hit = KeywordHit("edith", emptyList(), floatArrayOf()))
        try {
            assertTrue(fixture.engine.accept(frame(0)).isEmpty())
            assertEquals(1, fixture.port.streams.single().accepts)
            assertEquals(0, fixture.port.decodes)
            assertEquals(0, fixture.port.resets)
        } finally { fixture.engine.close() }
    }

    @Test fun `keyword engine reports no hit when the port has no result`() {
        val fixture = KeywordFixture(hit = null)
        try {
            assertTrue(fixture.engine.accept(frame(0)).isEmpty())
            assertEquals(1, fixture.port.decodes)
        } finally { fixture.engine.close() }
    }

    @Test fun `keyword engine resets the stream in place after a hit and can report the next hit`() {
        val fixture = KeywordFixture(hit = KeywordHit("edith", emptyList(), floatArrayOf()))
        try {
            assertEquals(listOf("edith"), fixture.engine.accept(frame(0)))
            assertEquals(1, fixture.port.resets)
            // In place: the same stream keeps decoding, it is neither released nor recreated.
            assertEquals(0, fixture.port.streams.single().releases)
            assertEquals(1, fixture.port.creates)
            // The port's own reset clears its result; a ready frame without a hit must not reset again.
            fixture.port.hit = null
            assertTrue(fixture.engine.accept(frame(1)).isEmpty())
            assertEquals(1, fixture.port.resets)
            // After the reset the same keyword is reportable again instead of being swallowed by stale state.
            fixture.port.hit = KeywordHit("edith", emptyList(), floatArrayOf())
            assertEquals(listOf("edith"), fixture.engine.accept(frame(2)))
            assertEquals(2, fixture.port.resets)
            assertEquals(1, fixture.port.creates)
        } finally { fixture.engine.close() }
    }

    @Test fun `keyword engine reset releases the stream and the next accept recreates it`() {
        val fixture = KeywordFixture(hit = KeywordHit("edith", emptyList(), floatArrayOf()))
        try {
            assertEquals(listOf("edith"), fixture.engine.accept(frame(0)))
            assertEquals(1, fixture.port.creates)
            fixture.engine.reset()
            assertEquals(1, fixture.port.streams.single().releases)
            fixture.port.hit = KeywordHit("伊迪丝", emptyList(), floatArrayOf())
            assertEquals(listOf("伊迪丝"), fixture.engine.accept(frame(1)))
            assertEquals(2, fixture.port.creates)
            assertEquals(1, fixture.port.streams.first().releases)
            assertEquals(1, fixture.port.streams.last().accepts)
        } finally { fixture.engine.close() }
    }

    @Test fun `keyword engine close releases the stream and the port exactly once`() {
        val fixture = KeywordFixture(hit = KeywordHit("edith", emptyList(), floatArrayOf()))
        fixture.engine.accept(frame(0))
        fixture.engine.close()
        fixture.engine.close()
        assertEquals(1, fixture.port.streams.single().releases)
        assertEquals(1, fixture.port.closes)
        assertFailsWith<IllegalStateException> { fixture.engine.accept(frame(1)) }
    }

    @Test fun `keyword engine rejects a frame whose rate differs from the configured rate`() {
        val fixture = KeywordFixture()
        try {
            assertFailsWith<IllegalArgumentException> { fixture.engine.accept(frame(0).copy(sampleRateHz = 8000)) }
            assertEquals(0, fixture.port.creates)
        } finally { fixture.engine.close() }
    }

    // ---- Phase diversity (F-01, 2026-10-20) ---------------------------------------------------------

    /**
     * The measured failure this pins: the native spotter's result depends on the **stream phase** - the same clip
     * that fires when the stream starts at its beginning does not fire when the stream has been running for a
     * different number of frames. A single stream therefore loses hits; staggered streams keep every phase.
     *
     * The fake answers only for the **second** stream, which a single-stream engine never creates.
     */
    @Test fun `keyword engine keeps a hit that only a later stream phase produces`() {
        val port = FakeKeywordSpotterPort().apply {
            hit = KeywordHit("edith", emptyList(), floatArrayOf())
            hittingCreations = setOf(1)
        }
        val engine = KeywordSpotterWakeWordEngine(port, "edith",
            streams = KeywordSpotterWakeWordEngine.PHASE_DIVERSITY_STREAMS,
            streamStrideFrames = KeywordSpotterWakeWordEngine.PHASE_DIVERSITY_STRIDE_FRAMES)
        try {
            val hits = (0 until 8).flatMap { engine.accept(frame(it)) }
            assertTrue("edith" in hits, "a later stream's hit must survive the stagger: $hits")
        } finally { engine.close() }
    }

    /**
     * The stagger is what gives the streams different phases: stream `j` is created `j * stride` frames after the
     * reset, so its first accepted frame (and therefore its chunk boundary) is shifted by that many frames. A reset
     * must re-stagger rather than create every stream on the same frame, or a later silence release would collapse
     * the diversity it exists for.
     */
    @Test fun `keyword engine staggers streams by the configured stride and re-staggers after reset`() {
        val port = FakeKeywordSpotterPort().apply { ready = true; hit = null }
        val engine = KeywordSpotterWakeWordEngine(port, "edith", streams = 4, streamStrideFrames = 2)
        try {
            repeat(8) { engine.accept(frame(it)) }
            assertEquals(4, port.streams.size)
            assertEquals(listOf(8, 6, 4, 2), port.streams.map { it.accepts })
            assertEquals(4, port.creates)

            engine.reset()
            assertEquals(listOf(1, 1, 1, 1), port.streams.map { it.releases })
            repeat(8) { engine.accept(frame(it)) }
            assertEquals(8, port.streams.size)
            assertEquals(listOf(8, 6, 4, 2), port.streams.drop(4).map { it.accepts })
        } finally { engine.close() }
    }

    @Test fun `keyword engine deduplicates the same word reported by several streams in one frame`() {
        val port = FakeKeywordSpotterPort().apply {
            hit = KeywordHit("edith", emptyList(), floatArrayOf())
            hittingCreations = null
        }
        val engine = KeywordSpotterWakeWordEngine(port, "edith", streams = 4, streamStrideFrames = 2)
        try {
            assertEquals(listOf("edith"), engine.accept(frame(6)))
        } finally { engine.close() }
    }
}
