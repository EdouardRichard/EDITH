package com.sheen.rokid.phone.audio

import com.sheen.rokid.audio.PcmChunk
import com.sheen.rokid.audio.PcmIngress
import com.sheen.rokid.contracts.AudioFormat
import com.sheen.rokid.contracts.ContractError
import com.sheen.rokid.contracts.Generation
import com.sheen.rokid.contracts.InputEpoch
import com.sheen.rokid.contracts.PcmEncoding
import com.sheen.rokid.contracts.SessionId
import com.sheen.rokid.contracts.VerifiedPcm
import com.sheen.rokid.testsupport.FakeClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 0.4 Task 6 (`D199`/`D200`): the phone is the microphone, but the **frames** are the same.
 *
 * `PhoneMicCapture` is the one new producer on the wake route: an `AudioRecord`-shaped device read in 20 ms slices
 * and offered to the same [PcmIngress] the CXR uplink writes to. The rules these tests pin are the ones a device run
 * cannot prove by listening:
 *
 * - **the geometry is the chain's, or nothing.** 16 kHz / mono / 16-bit little-endian, 640 bytes per 20 ms chunk,
 *   because the same `PcmFramer`/`SpeechPipeline` consume both sources. A mismatch refuses with
 *   [PhoneMicCapture.MIC_FORMAT_UNSUPPORTED] instead of resampling - a "close enough" stream would produce confident
 *   nonsense rather than an error.
 * - **the chunk carries the session's epoch and one continuity**, so frames are tagged with the session that owns them
 *   and `PcmFramer` does not reset on every read.
 * - **a refused start is a stable code**, never a silent "started": an unavailable device ([PhoneMicCapture.MIC_UNAVAILABLE])
 *   and a recorder that cannot begin ([PhoneMicCapture.MIC_START_FAILED]) are different facts about the phone.
 * - **stop joins and is idempotent**, and nothing is offered after it returns - the thread is the only producer, so a
 *   late offer would be audio the session already released.
 *
 * The device is a seam (`PcmRecordDevice`) exactly so these rules can be driven on the JVM: no `AudioRecord`, no
 * permission, no phone.
 */
class PhoneMicCaptureTest {

    private val epoch = InputEpoch(SessionId("s-phone-mic"), Generation(1), 1)
    private val clock = FakeClock(0)
    private val format = PhoneMicFormat.VERIFIED

    /**
     * A blocking `PcmRecordDevice`: [read] waits up to 5 ms for the next fed chunk, so the capture thread behaves like
     * a real recorder waiting for samples rather than spinning.
     */
    private class FakeRecordDevice : PcmRecordDevice {
        private val chunks = LinkedBlockingQueue<ShortArray>()
        @Volatile private var stopped = false
        @Volatile private var started = false
        val reads = AtomicInteger()
        val starts = AtomicInteger()
        val stops = AtomicInteger()
        var startResult = true
        var startFailure: Throwable? = null

        override fun start(): Boolean {
            starts.incrementAndGet()
            startFailure?.let { throw it }
            started = startResult
            return startResult
        }

        override fun read(buffer: ShortArray): Int {
            reads.incrementAndGet()
            while (!stopped) {
                val chunk = chunks.poll(5, TimeUnit.MILLISECONDS) ?: continue
                val count = minOf(chunk.size, buffer.size)
                chunk.copyInto(buffer, 0, 0, count)
                return count
            }
            return 0
        }

        override fun stop() {
            stops.incrementAndGet()
            stopped = true
        }

        fun feed(samples: ShortArray) {
            chunks += samples
        }

        fun isStarted(): Boolean = started
    }

    private inner class Rig {
        val ingress = PcmIngress(FakeClock(0))
        val device = FakeRecordDevice()
        val capture: PhoneMicCapture

        init {
            ingress.open(epoch, format)
            capture = PhoneMicCapture(device, FakeClock(0), ingress, format, epoch)
        }

        fun start() = capture.start()

        fun stop() = capture.stop()

        /** 20 ms of 16 kHz mono PCM16 = 320 samples = 640 bytes. */
        fun chunk(value: Short = 0x1234): ShortArray = ShortArray(320) { value }

        fun awaitChunks(count: Int, timeoutMillis: Long = 2_000): List<PcmChunk> {
            val chunks = mutableListOf<PcmChunk>()
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
            while (chunks.size < count && System.nanoTime() < deadline) {
                val chunk = ingress.poll()
                if (chunk != null) chunks += chunk else Thread.sleep(2)
            }
            return chunks
        }
    }

    @Test
    fun thePhoneFormatIsTheChainsMonoPcm16Geometry() {
        assertEquals("the CXR framer's rate", 16_000, format.format.sampleRateHz)
        assertEquals("the CXR framer requires mono", 1, format.format.channels)
        assertEquals("the CXR framer requires 16-bit", 16, format.format.bitsPerSample)
        assertEquals("the CXR uplink is signed little-endian", PcmEncoding.SIGNED_LE, format.encoding)
        assertEquals("phone-mic-audiorecord-16k-mono-16le", format.evidenceId)
    }

    @Test
    fun offersTwentyMillisecondChunksWithTheEpochAndOneContinuity() {
        val rig = Rig()
        try {
            rig.start()
            rig.device.feed(rig.chunk(0x1234))
            rig.device.feed(rig.chunk(0x4321))

            val chunks = rig.awaitChunks(2)

            assertEquals("both reads must reach the ingress", 2, chunks.size)
            chunks.forEach { chunk ->
                assertEquals("a chunk belongs to the session's epoch", epoch, chunk.epoch)
                assertEquals("20 ms at 16 kHz mono PCM16 is 640 bytes", 640, chunk.bytes.size)
            }
            assertEquals("one capture is one continuity; a framer must not reset per read",
                chunks[0].continuityId, chunks[1].continuityId)
            assertEquals("the ingress orders the offered chunks", listOf(0L, 1L), chunks.map { it.sequence })
            assertEquals("little-endian: 0x1234 is 0x34 then 0x12",
                0x34.toByte(), chunks[0].bytes[0])
            assertEquals(0x12.toByte(), chunks[0].bytes[1])
        } finally {
            rig.stop()
        }
    }

    @Test
    fun rejectsAWrongGeometryWithoutStartingTheDevice() {
        val wrong = VerifiedPcm(AudioFormat.Pcm(8_000, 1, 16), PcmEncoding.SIGNED_LE, "wrong-geometry")
        val ingress = PcmIngress(clock)
        val device = FakeRecordDevice()
        val capture = PhoneMicCapture(device, clock, ingress, wrong, epoch)

        val failure = assertThrows(ContractError.CapabilityMissing::class.java) { capture.start() }

        assertEquals(PhoneMicCapture.MIC_FORMAT_UNSUPPORTED, failure.message)
        assertEquals("the refusal is visible to the caller after the throw",
            PhoneMicCapture.MIC_FORMAT_UNSUPPORTED, capture.lastError())
        assertEquals("a refused geometry must not open the microphone", 0, device.starts.get())
    }

    @Test
    fun aDeviceThatCannotStartReportsMicUnavailable() {
        val ingress = PcmIngress(clock)
        val device = FakeRecordDevice().also { it.startResult = false }
        val capture = PhoneMicCapture(device, clock, ingress, format, epoch)

        val failure = assertThrows(ContractError.CapabilityMissing::class.java) { capture.start() }

        assertEquals(PhoneMicCapture.MIC_UNAVAILABLE, failure.message)
        assertEquals(PhoneMicCapture.MIC_UNAVAILABLE, capture.lastError())
    }

    @Test
    fun aDeviceWhoseRecorderCannotBeginReportsStartFailed() {
        val ingress = PcmIngress(clock)
        val device = FakeRecordDevice().also {
            it.startFailure = ContractError.CapabilityMissing(PhoneMicCapture.MIC_START_FAILED)
        }
        val capture = PhoneMicCapture(device, clock, ingress, format, epoch)

        val failure = assertThrows(ContractError.CapabilityMissing::class.java) { capture.start() }

        assertEquals("the device's own stable code must survive the seam",
            PhoneMicCapture.MIC_START_FAILED, failure.message)
        assertEquals(PhoneMicCapture.MIC_START_FAILED, capture.lastError())
    }

    @Test
    fun stopJoinsTheCaptureThreadAndIsIdempotent() {
        val rig = Rig()
        try {
            rig.start()
            rig.device.feed(rig.chunk())
            assertEquals(1, rig.awaitChunks(1).size)
            assertNotNull("a started capture owns a thread", rig.capture.captureThread())
            val worker = requireNotNull(rig.capture.captureThread())

            rig.stop()

            assertFalse("stop must join the reader, not leave it behind", worker.isAlive)
            assertEquals("stop releases the recorder", 1, rig.device.stops.get())

            rig.stop()

            assertEquals("a second stop must not release the recorder twice", 1, rig.device.stops.get())
            assertNull("and there is no thread left to stop", rig.capture.captureThread())
        } finally {
            rig.stop()
        }
    }

    @Test
    fun nothingIsOfferedAfterStopReturns() {
        val rig = Rig()
        try {
            rig.start()
            rig.device.feed(rig.chunk())
            assertEquals(1, rig.awaitChunks(1).size)

            rig.stop()

            rig.device.feed(rig.chunk())
            val readsAtStop = rig.device.reads.get()
            val after = rig.awaitChunks(1, timeoutMillis = 100)
            assertTrue("a stopped capture must not offer audio the session already released", after.isEmpty())
            Thread.sleep(50)
            assertEquals("and it must not keep reading the device", readsAtStop, rig.device.reads.get())
        } finally {
            rig.stop()
        }
    }
}
