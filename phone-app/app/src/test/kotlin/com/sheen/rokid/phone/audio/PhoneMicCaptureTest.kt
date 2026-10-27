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
import java.util.concurrent.CopyOnWriteArrayList
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
 * - **a dead recorder is said out loud exactly once (spec §4.5).** A `read` error (`< 0`) or a throw is a bounded
 *   failure: the reader exits, the recorder is released, [PhoneMicCapture.lastError] names `MIC_UNAVAILABLE`, and the
 *   one-shot sink fires once **on the reader thread** so the owner can pause the session instead of leaving it
 *   claiming a normal microphone. Stopping is not a failure and must not fire it.
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
     * a real recorder waiting for samples rather than spinning. [failReadAt]/[throwAtRead] make an individual read
     * fail (1 = the very first read), which is how the mid-capture interruption rules are driven.
     */
    private class FakeRecordDevice : PcmRecordDevice {
        private val chunks = LinkedBlockingQueue<ShortArray>()
        @Volatile private var stopped = false
        @Volatile private var woken = false
        @Volatile private var started = false
        val reads = AtomicInteger()
        val starts = AtomicInteger()
        val stops = AtomicInteger()
        val wakes = AtomicInteger()
        var startResult = true
        var startFailure: Throwable? = null
        var failReadAt = -1
        var throwAtRead = -1

        override fun start(): Boolean {
            starts.incrementAndGet()
            startFailure?.let { throw it }
            stopped = false
            woken = false
            started = startResult
            return startResult
        }

        override fun read(buffer: ShortArray): Int {
            val read = reads.incrementAndGet()
            if (read == throwAtRead) throw IllegalStateException("recorder died")
            if (read == failReadAt) return -1
            while (!stopped && !woken) {
                val chunk = chunks.poll(5, TimeUnit.MILLISECONDS) ?: continue
                val count = minOf(chunk.size, buffer.size)
                chunk.copyInto(buffer, 0, 0, count)
                return count
            }
            return 0
        }

        override fun wake() {
            wakes.incrementAndGet()
            woken = true
        }

        override fun stop() {
            stops.incrementAndGet()
            stopped = true
            woken = true
        }

        fun feed(samples: ShortArray) {
            chunks += samples
        }

        fun isStarted(): Boolean = started
    }

    private inner class Rig {
        val ingress = PcmIngress(FakeClock(0))
        val device = FakeRecordDevice()
        val failures = CopyOnWriteArrayList<String>()
        val capture: PhoneMicCapture

        init {
            ingress.open(epoch, format)
            capture = PhoneMicCapture(device, FakeClock(0), ingress, format, epoch,
                onFailure = { code -> failures += code })
        }

        fun start() = capture.start()

        fun stop() = capture.stop()

        /** 20 ms of 16 kHz mono PCM16 = 320 samples = 640 bytes. */
        fun chunk(value: Short = 0x1234): ShortArray = ShortArray(320) { value }

        fun awaitFailures(count: Int, timeoutMillis: Long = 2_000): Boolean {
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
            while (failures.size < count && System.nanoTime() < deadline) Thread.sleep(2)
            return failures.size >= count
        }

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

    // ---- mid-capture interruption (spec §4.5) --------------------------------------------------------------------

    @Test
    fun aReadErrorReportsOneFailureReleasesTheDeviceAndStopsOffering() {
        val ingress = PcmIngress(clock)
        val device = FakeRecordDevice().also { it.failReadAt = 2 }
        val failures = CopyOnWriteArrayList<String>()
        ingress.open(epoch, format)
        val capture = PhoneMicCapture(device, clock, ingress, format, epoch, onFailure = { failures += it })
        try {
            capture.start()
            device.feed(ShortArray(320) { 0x1234 })

            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(2_000)
            while (failures.isEmpty() && System.nanoTime() < deadline) Thread.sleep(2)

            assertEquals("a read error must surface its one stable code exactly once",
                listOf(PhoneMicCapture.MIC_UNAVAILABLE), failures.toList())
            assertEquals("the code stays readable for the owner/UI",
                PhoneMicCapture.MIC_UNAVAILABLE, capture.lastError())
            assertNull("a dead capture must not keep pretending to own a reader", capture.captureThread())
            assertEquals("the recorder is released on the failure exit, not left recording", 1, device.stops.get())

            // The one chunk read before the failure was offered; nothing after it may be.
            assertNotNull("audio read before the failure still belongs to the epoch", ingress.poll())
            assertNull(ingress.poll())
            device.feed(ShortArray(320))
            Thread.sleep(100)
            assertNull("no further offers after the failure", ingress.poll())
            assertEquals("the sink is one-shot per capture", 1, failures.size)

            capture.stop()
            capture.stop()
            assertEquals("stop after a failure is idempotent and must not release twice", 1, device.stops.get())
        } finally {
            capture.stop()
        }
    }

    @Test
    fun aThrowingReadReportsTheSameBoundedFailureOnce() {
        val ingress = PcmIngress(clock)
        val device = FakeRecordDevice().also { it.throwAtRead = 1 }
        val failures = CopyOnWriteArrayList<String>()
        ingress.open(epoch, format)
        val capture = PhoneMicCapture(device, clock, ingress, format, epoch, onFailure = { failures += it })
        try {
            capture.start()

            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(2_000)
            while (failures.isEmpty() && System.nanoTime() < deadline) Thread.sleep(2)

            assertEquals("a recorder throw is the same bounded failure, not a silent death",
                listOf(PhoneMicCapture.MIC_UNAVAILABLE), failures.toList())
            assertEquals(PhoneMicCapture.MIC_UNAVAILABLE, capture.lastError())
            assertNull(capture.captureThread())
            assertEquals(1, device.stops.get())

            capture.stop()
            assertEquals(1, device.stops.get())
            assertEquals(1, failures.size)
        } finally {
            capture.stop()
        }
    }

    @Test
    fun aStopIsNotReportedAsACaptureFailure() {
        val rig = Rig()
        try {
            rig.start()
            rig.device.feed(rig.chunk())
            assertEquals(1, rig.awaitChunks(1).size)

            rig.stop()

            Thread.sleep(100)
            assertTrue("stopping on purpose is not a microphone failure", rig.failures.isEmpty())
            assertNull(rig.capture.lastError())
            assertEquals("stop wakes the blocked read before releasing the recorder", 1, rig.device.wakes.get())
            assertEquals(1, rig.device.stops.get())
        } finally {
            rig.stop()
        }
    }
}
