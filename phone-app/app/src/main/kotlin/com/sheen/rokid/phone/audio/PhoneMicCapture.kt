package com.sheen.rokid.phone.audio

import com.sheen.rokid.audio.PcmIngress
import com.sheen.rokid.audio.PcmOffer
import com.sheen.rokid.contracts.ContractError
import com.sheen.rokid.contracts.InputEpoch
import com.sheen.rokid.contracts.VerifiedPcm
import com.sheen.rokid.plugin.MonotonicClock

/**
 * 0.4 Task 6 (`D199`/`D200`): reads the phone microphone in 20 ms slices and offers them to the session's ingress.
 *
 * This is the **producer** half of the phone audio source. It owns no framing, no VAD and no epoch bookkeeping: it
 * converts one `ShortArray` read into little-endian bytes and calls [PcmIngress.offer] with the epoch the session
 * opened. The existing `AudioIngressLoop` then polls exactly as it does for the CXR uplink, so KWS, the speech
 * pipeline and the wake word's 10 s/120 s windows are the same code (`D200`: only the source changes).
 *
 * Rules it keeps, and why each one is a test:
 *
 * - **The chain's geometry, or a refusal.** [start] validates [format] against [PhoneMicFormat.isChainGeometry] and
 *   throws [MIC_FORMAT_UNSUPPORTED] before touching the device. The same `PcmFramer`/`SpeechPipeline` consume both
 *   sources, so a phone stream of another geometry is not "slightly off" - it is a different signal.
 * - **A start failure is a stable code, never a silent start.** The device's false answer is [MIC_UNAVAILABLE]; its
 *   own `CapabilityMissing` code (`MIC_START_FAILED`, ...) is preserved; any other throw is [MIC_START_FAILED]. The
 *   code is also kept for [lastError] so the owner can surface it before/after the throw.
 * - **One chunk is 640 B / 20 ms and carries the epoch and continuity.** Both come from [PcmIngress]; the capture
 *   never stamps its own, so the frames the pipeline sees belong to the session that owns the ingress.
 * - **Stop joins the reader.** The thread is the only producer, so after [stop] returns there is no in-flight offer,
 *   and the device is released exactly once; a second [stop] is a no-op.
 *
 * @param clock monotonic source; the capture records when it actually started for diagnostics (never audio).
 * @param onOffer every offer outcome, so the owner can count overflow/unknown-format/stale exactly as the CXR
 *   forwarder does. Called on the capture thread.
 */
class PhoneMicCapture(
    private val device: PcmRecordDevice,
    private val clock: MonotonicClock,
    private val ingress: PcmIngress,
    private val format: VerifiedPcm,
    private val epoch: InputEpoch,
    private val onOffer: (PcmOffer) -> Unit = {},
) {

    private val lock = Any()

    /** True while the reader thread is allowed to offer; cleared before the device is released. */
    @Volatile private var capturing = false

    /** The reader thread, or null between [stop] and the next [start]. Guarded by [lock]. */
    private var worker: Thread? = null

    /** The first stable failure this capture reported, if any. Diagnostics only - never audio. */
    @Volatile private var captureError: String? = null

    /** The monotonic instant [start] succeeded, or null. Diagnostics only. */
    @Volatile private var startedAtNanos: Long? = null

    /**
     * Starts the reader for [epoch].
     *
     * Throws [ContractError.CapabilityMissing] with one of the stable codes and records it in [lastError]. Calling it
     * again while the previous reader runs is a no-op, exactly like the loop's own start.
     */
    @Synchronized fun start() {
        synchronized(lock) {
            if (worker != null || capturing) return
            if (!PhoneMicFormat.isChainGeometry(format)) throw fail(MIC_FORMAT_UNSUPPORTED)
            val began = try {
                device.start()
            } catch (missing: ContractError.CapabilityMissing) {
                throw fail(missing.message ?: MIC_UNAVAILABLE)
            } catch (_: Throwable) {
                throw fail(MIC_START_FAILED)
            }
            if (!began) throw fail(MIC_UNAVAILABLE)
            captureError = null
            startedAtNanos = clock.nowNanos()
            capturing = true
            worker = Thread({ captureLoop() }, CAPTURE_THREAD_NAME).apply {
                isDaemon = true
                start()
            }
        }
    }

    /**
     * Stops and joins the reader, then releases the device. Idempotent.
     *
     * The device is stopped **before** the join so a blocking `AudioRecord.read` wakes up; the reader itself checks
     * [capturing] after every read, so it cannot offer a chunk that belongs to a session that has already ended.
     * Serialized with [start] so a stop cannot race the device into existence.
     */
    @Synchronized fun stop() {
        val thread = synchronized(lock) {
            capturing = false
            worker.also { worker = null }
        }
        if (thread == null) return
        runCatching { device.stop() }
        thread.interrupt()
        try {
            thread.join(STOP_JOIN_MILLIS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    /** The stable failure code this capture reported, or null when it has not failed. */
    fun lastError(): String? = captureError

    /** The monotonic instant the current/last capture began, or null. Counts only; never audio. */
    fun startedAtNanos(): Long? = startedAtNanos

    /** The reader thread, or null. Exposed to the JVM gate and to a device diagnostic; no production policy reads it. */
    internal fun captureThread(): Thread? = synchronized(lock) { worker }

    private fun captureLoop() {
        val samples = ShortArray(PhoneMicFormat.CHUNK_SAMPLES)
        val bytes = ByteArray(PhoneMicFormat.CHUNK_BYTES)
        while (capturing) {
            val read = try {
                device.read(samples)
            } catch (_: Throwable) {
                captureError = MIC_UNAVAILABLE
                return
            }
            // Re-check after the blocking read: stop() may have run while the device was waking up.
            if (!capturing) return
            if (read < 0) {
                // A recorder error is a bounded failure, never a silent quiet stretch: the loop stops offering and the
                // code is visible to the owner (spec §4.5: 采集中断 must not 静默丢帧).
                captureError = MIC_UNAVAILABLE
                return
            }
            if (read == 0) continue
            val count = minOf(read, samples.size)
            for (index in 0 until count) {
                val sample = samples[index].toInt()
                bytes[index * 2] = (sample and 0xFF).toByte()
                bytes[index * 2 + 1] = ((sample shr 8) and 0xFF).toByte()
            }
            val offer = ingress.offer(epoch, bytes.copyOf(count * BYTES_PER_SAMPLE))
            runCatching { onOffer(offer) }
        }
    }

    /** Records [code] and returns the error to throw, so the throw site carries the same code as [lastError]. */
    private fun fail(code: String): ContractError.CapabilityMissing {
        captureError = code
        return ContractError.CapabilityMissing(code)
    }

    companion object {
        /** The phone microphone's declared geometry is not the chain's (16 kHz / mono / 16-bit LE). */
        const val MIC_FORMAT_UNSUPPORTED = "MIC_FORMAT_UNSUPPORTED"

        /** No capture device: `AudioRecord` could not be built/initialised, or the reader failed mid-session. */
        const val MIC_UNAVAILABLE = "MIC_UNAVAILABLE"

        /** The recorder was built but could not begin recording. */
        const val MIC_START_FAILED = "MIC_START_FAILED"

        private const val CAPTURE_THREAD_NAME = "edith-phone-mic"
        private const val BYTES_PER_SAMPLE = PhoneMicFormat.BYTES_PER_SAMPLE
        private const val STOP_JOIN_MILLIS = 2_000L
    }
}
