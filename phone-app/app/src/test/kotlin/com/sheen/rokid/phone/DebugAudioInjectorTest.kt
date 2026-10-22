package com.sheen.rokid.phone
import com.sheen.rokid.phone.debug.DebugBurstTarget
import com.sheen.rokid.phone.debug.DebugWavPcm
import com.sheen.rokid.phone.debug.DebugBurst

import com.sheen.rokid.audio.PcmOffer
import com.sheen.rokid.contracts.GapReason
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure half of the debug PCM injection instrument: RIFF/WAVE parsing, conversion to 16 kHz mono PCM16 and
 * 20 ms chunking.
 *
 * The receiver itself is deliberately thin (resolve the live ingress + epoch, read the file, offer one chunk every
 * 20 ms), so everything that can be decided without a device is decided here and pinned by these tests. The
 * instrument exists to separate "the glasses microphone never delivered the wake word acoustically" from "the
 * keyword spotter does not recall `伊迪丝`", so the conversion has to be honest about what it does to the samples:
 * channel averaging and linear-interpolation resampling, both asserted below rather than assumed.
 */
class DebugAudioInjectorTest {

    // ------------------------------------------------------------------ fixtures

    private fun le16(value: Int) = byteArrayOf((value and 0xFF).toByte(), ((value shr 8) and 0xFF).toByte())

    private fun le32(value: Int) = byteArrayOf(
        (value and 0xFF).toByte(), ((value shr 8) and 0xFF).toByte(),
        ((value shr 16) and 0xFF).toByte(), ((value shr 24) and 0xFF).toByte())

    private fun ascii(text: String) = text.toByteArray(Charsets.US_ASCII)

    private fun pcmBytes(samples: ShortArray): ByteArray {
        val out = ByteArray(samples.size * 2)
        samples.forEachIndexed { index, sample ->
            out[index * 2] = (sample.toInt() and 0xFF).toByte()
            out[index * 2 + 1] = ((sample.toInt() shr 8) and 0xFF).toByte()
        }
        return out
    }

    /** Reads one little-endian PCM16 sample out of a chunk, so the assertions are about audio, not about bytes. */
    private fun sampleAt(bytes: ByteArray, index: Int): Short =
        ((bytes[index * 2].toInt() and 0xFF) or (bytes[index * 2 + 1].toInt() shl 8)).toShort()

    /**
     * A RIFF/WAVE file with the given interleaved PCM16 [samples].
     *
     * The `fmt ` chunk is the same 16-byte PCM shape the project's own synthesized files carry (size 16), and
     * [withListChunk] inserts an unknown chunk between `fmt ` and `data` because chunk order is not fixed by the
     * format and the device files must not be mis-parsed when one appears.
     */
    private fun wav(sampleRateHz: Int = 16_000, channels: Int = 1, bitsPerSample: Int = 16,
        audioFormat: Int = 1, samples: ShortArray, withListChunk: Boolean = false,
        withDataChunk: Boolean = true): ByteArray {
        val fmt = ByteArrayOutputStream().apply {
            write(le16(audioFormat)); write(le16(channels)); write(le32(sampleRateHz))
            write(le32(sampleRateHz * channels * bitsPerSample / 8)); write(le16(channels * bitsPerSample / 8))
            write(le16(bitsPerSample))
        }.toByteArray()
        val body = ByteArrayOutputStream().apply {
            write(ascii("WAVE"))
            write(ascii("fmt ")); write(le32(fmt.size)); write(fmt)
            if (withListChunk) {
                val info = ascii("INFOISFT") + ByteArray(8)
                write(ascii("LIST")); write(le32(info.size)); write(info)
            }
            if (withDataChunk) {
                val data = pcmBytes(samples)
                write(ascii("data")); write(le32(data.size)); write(data)
            }
        }.toByteArray()
        return ByteArrayOutputStream().apply {
            write(ascii("RIFF")); write(le32(body.size)); write(body)
        }.toByteArray()
    }

    private fun ready(bytes: ByteArray): DebugWavPcm.Loaded.Ready {
        val loaded = DebugWavPcm.load(bytes)
        assertTrue("expected a loadable file, got $loaded", loaded is DebugWavPcm.Loaded.Ready)
        return loaded as DebugWavPcm.Loaded.Ready
    }

    private fun refusal(bytes: ByteArray): String {
        val loaded = DebugWavPcm.load(bytes)
        assertTrue("expected a refusal, got $loaded", loaded is DebugWavPcm.Loaded.Refused)
        return (loaded as DebugWavPcm.Loaded.Refused).code
    }

    // -------------------------------------------------------------------- tests

    @Test
    fun aSixteenKilohertzMonoFileBecomesWholeTwentyMillisecondChunks() {
        val samples = ShortArray(960) { it.toShort() }   // 60 ms = three whole 20 ms chunks

        val loaded = ready(wav(samples = samples))

        assertEquals(16_000, loaded.sampleRateHz)
        assertEquals(1, loaded.channels)
        assertEquals(3, loaded.chunks.size)
        loaded.chunks.forEach { assertEquals(DebugWavPcm.CHUNK_BYTES, it.size) }
        // 16 kHz in, 16 kHz out: the samples must survive untouched, chunk for chunk.
        assertEquals(0.toShort(), sampleAt(loaded.chunks[0], 0))
        assertEquals(320.toShort(), sampleAt(loaded.chunks[1], 0))
        assertEquals(640.toShort(), sampleAt(loaded.chunks[2], 0))
        assertEquals(959.toShort(), sampleAt(loaded.chunks[2], 319))
    }

    @Test
    fun aStereoFortyFourPointOneKilohertzFileIsDownmixedThenResampledToSixteenKilohertz() {
        // 4410 frames = 100 ms at 44.1 kHz; left = 1000, right = 3000 -> a mono average of 2000.
        val frames = 4410
        val interleaved = ShortArray(frames * 2).also { for (frame in 0 until frames) {
            it[frame * 2] = 1000; it[frame * 2 + 1] = 3000 } }

        val loaded = ready(wav(sampleRateHz = 44_100, channels = 2, samples = interleaved))

        assertEquals(44_100, loaded.sampleRateHz)
        assertEquals(2, loaded.channels)
        // 4410 * 16000 / 44100 = 1600 samples = five whole 20 ms chunks.
        assertEquals(5, loaded.chunks.size)
        loaded.chunks.forEach { chunk ->
            assertEquals(DebugWavPcm.CHUNK_BYTES, chunk.size)
            for (index in 0 until chunk.size / 2) {
                assertEquals("chunk sample $index must carry the channel average",
                    2000.toShort(), sampleAt(chunk, index))
            }
        }
    }

    @Test
    fun resamplingInterpolatesBetweenInputFrames() {
        // 32 kHz -> 16 kHz is exactly 2:1, so output[i] must be input[2i] and the ramp makes any off-by-one visible.
        val loaded = ready(wav(sampleRateHz = 32_000, samples = ShortArray(640) { it.toShort() }))

        assertEquals(1, loaded.chunks.size)
        assertEquals(0.toShort(), sampleAt(loaded.chunks[0], 0))
        assertEquals(10.toShort(), sampleAt(loaded.chunks[0], 5))
        assertEquals(638.toShort(), sampleAt(loaded.chunks[0], 319))
    }

    @Test
    fun aFileWhoseChunkOrderIsNotTheMinimumIsStillParsed() {
        val samples = ShortArray(640) { (it * 3).toShort() }

        val loaded = ready(wav(samples = samples, withListChunk = true))

        assertEquals(2, loaded.chunks.size)
        assertEquals(0.toShort(), sampleAt(loaded.chunks[0], 0))
        assertEquals(960.toShort(), sampleAt(loaded.chunks[1], 0))
    }

    @Test
    fun aNonPcmFileIsRefused() {
        assertEquals("UNSUPPORTED_FORMAT",
            refusal(wav(audioFormat = 3, samples = ShortArray(320) { 1 })))
    }

    @Test
    fun aFileThatIsNotSixteenBitIsRefused() {
        assertEquals("UNSUPPORTED_FORMAT",
            refusal(wav(bitsPerSample = 8, samples = ShortArray(320) { 1 })))
    }

    @Test
    fun aFileThatIsNotRiffIsRefused() {
        assertEquals("NOT_RIFF", refusal(ByteArray(64)))
    }

    @Test
    fun aFileWithoutADataChunkIsRefused() {
        assertEquals("NO_DATA", refusal(wav(samples = ShortArray(320) { 1 }, withDataChunk = false)))
    }

    @Test
    fun anEmptyRecordingIsRefused() {
        assertEquals("EMPTY", refusal(wav(samples = ShortArray(0))))
    }

    @Test
    fun aRecordingShorterThanOneChunkIsRefused() {
        assertEquals("TOO_SHORT", refusal(wav(samples = ShortArray(100) { 1 })))
    }

    // -------------------------------------------------- burst orchestration (ambient off, paced, windowed)

    /**
     * Records what a burst asked the live objects to do, in order.
     *
     * The orchestration's job is a **sequence of side effects** - stop the ambient stream, reset/open the ingress,
     * offer one chunk per 20 ms, drain between windows, restore the stream - so the honest fake here is one that
     * remembers the order rather than one that only counts calls. [queued] answers the drain probe by offer count, so
     * a test can model "the loop is keeping up" (always 0) or "the loop never drains" (never 0).
     */
    private class RecordingTarget(
        private val stopSucceeds: Boolean = true,
        private val openSucceeds: Boolean = true,
        private val restoreSucceeds: Boolean = true,
        private val queued: (Int) -> Int = { 0 },
        private val outcomes: List<PcmOffer> = emptyList(),
    ) : DebugBurstTarget {
        val calls = mutableListOf<String>()
        val offered = mutableListOf<ByteArray>()

        override fun stopAmbient(): Boolean { calls += "stop"; return stopSucceeds }
        override fun resetIngress(reason: GapReason) { calls += "reset:$reason" }
        override fun openIngress(): Boolean { calls += "open"; return openSucceeds }
        override fun offer(chunk: ByteArray): PcmOffer {
            calls += "offer:${offered.size}"
            offered += chunk
            return outcomes.getOrElse(offered.size - 1) { PcmOffer.ACCEPTED }
        }
        override fun queuedBytes(): Int { calls += "drain"; return queued(offered.size) }
        override fun restoreAmbient(): Boolean { calls += "restore"; return restoreSucceeds }
    }

    private fun chunks(count: Int) = List(count) { ByteArray(DebugWavPcm.CHUNK_BYTES) }

    @Test
    fun aBurstStopsTheAmbientStreamOffersTheFileThenRestoresTheStream() {
        val target = RecordingTarget()

        val outcome = DebugBurst.run(target, chunks(3), sleep = {})

        assertEquals(
            listOf("stop", "reset:SESSION_INTERRUPTED", "open", "offer:0", "offer:1", "offer:2", "drain", "restore"),
            target.calls)
        assertEquals(3, outcome.accepted)
        assertTrue(outcome.ambientStopped)
        assertTrue(outcome.ambientRestored)
        assertTrue(outcome.drained)
        assertNull(outcome.refused)
    }

    @Test
    fun aBurstRefusesWhenTheAmbientStreamCannotBeStopped() {
        val target = RecordingTarget(stopSucceeds = false)

        val outcome = DebugBurst.run(target, chunks(3), sleep = {})

        // The whole point of the instrument is not to inject into the mix: with the microphone still live the file is
        // not offered at all, and the refusal is distinct so the device log cannot be read as "injected but missed".
        assertEquals("AMBIENT_STOP_FAILED", outcome.refused)
        assertEquals(listOf("stop"), target.calls)
        assertTrue(target.offered.isEmpty())
        assertFalse(outcome.ambientStopped)
        assertFalse(outcome.ambientRestored)
    }

    @Test
    fun aBurstRefusesWhenTheIngressCannotBeReopenedAndStillRestoresTheAmbientStream() {
        val target = RecordingTarget(openSucceeds = false)

        val outcome = DebugBurst.run(target, chunks(3), sleep = {})

        assertEquals("INGRESS_REOPEN_FAILED", outcome.refused)
        assertTrue(target.offered.isEmpty())
        assertEquals("restore", target.calls.last())
        assertTrue(outcome.ambientRestored)
    }

    @Test
    fun aFileLongerThanTheIngressBoundIsInjectedInWindowsWithADrainBetweenThem() {
        // 160 chunks = 3.2 s, longer than the ingress's own 2 s bound: three 1.5 s windows at most.
        val total = DebugBurst.WINDOW_CHUNKS * 2 + 10
        val target = RecordingTarget()

        val outcome = DebugBurst.run(target, chunks(total), sleep = {})

        assertEquals(3, outcome.windows)
        assertEquals(total, target.offered.size)
        val drains = target.calls.withIndex().filter { it.value == "drain" }.map { it.index }
        assertEquals(3, drains.size)
        // The first drain sits exactly on the window boundary: after the 75th offer, before the 76th.
        assertEquals("offer:${DebugBurst.WINDOW_CHUNKS - 1}", target.calls[drains[0] - 1])
        assertEquals("offer:${DebugBurst.WINDOW_CHUNKS}", target.calls[drains[0] + 1])
    }

    @Test
    fun aWindowFitsInsideTheIngressTwoSecondBound() {
        assertTrue("a window must not reach the ingress bound it exists to stay under",
            DebugBurst.WINDOW_CHUNKS * DebugBurst.PACE_MILLIS < 2_000)
    }

    @Test
    fun theInjectionIsPacedOneChunkPerTwentyMilliseconds() {
        val sleeps = mutableListOf<Long>()

        DebugBurst.run(RecordingTarget(), chunks(5), sleep = { sleeps += it })

        assertEquals(List(5) { DebugBurst.PACE_MILLIS }, sleeps)
    }

    @Test
    fun everyOfferOutcomeIsCounted() {
        val target = RecordingTarget(outcomes = listOf(
            PcmOffer.ACCEPTED, PcmOffer.STALE_EPOCH, PcmOffer.UNKNOWN_FORMAT, PcmOffer.OVERFLOW))

        val outcome = DebugBurst.run(target, chunks(4), sleep = {})

        assertEquals(1, outcome.accepted)
        assertEquals(1, outcome.stale)
        assertEquals(1, outcome.unknown)
        assertEquals(1, outcome.overflow)
    }

    @Test
    fun aDrainThatNeverClearsIsBoundedAndReported() {
        val sleeps = mutableListOf<Long>()

        val outcome = DebugBurst.run(RecordingTarget(queued = { 1 }), chunks(2), sleep = { sleeps += it })

        // The receiver runs on a worker thread: a drain that waited for a stopped loop would hang it forever.
        assertFalse(outcome.drained)
        assertEquals(2 + DebugBurst.DRAIN_MAX_STEPS, sleeps.size)
    }

    @Test
    fun aRestoreThatFailsIsReportedRatherThanSwallowed() {
        val outcome = DebugBurst.run(RecordingTarget(restoreSucceeds = false), chunks(1), sleep = {})

        assertFalse(outcome.ambientRestored)
        assertNull(outcome.refused)
    }
}
