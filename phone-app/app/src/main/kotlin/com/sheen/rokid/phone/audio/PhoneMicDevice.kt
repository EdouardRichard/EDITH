package com.sheen.rokid.phone.audio

import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.AudioFormat as PlatformAudioFormat
import com.sheen.rokid.contracts.AudioFormat
import com.sheen.rokid.contracts.ContractError
import com.sheen.rokid.contracts.PcmEncoding
import com.sheen.rokid.contracts.VerifiedPcm

/**
 * 0.4 Task 6 (`D199`/`D200`): the one phone-microphone geometry the wake route may consume.
 *
 * The phone microphone **replaces only the source** of PCM; the same `PcmFramer` → `AudioIngressLoop` →
 * KWS/`SpeechPipeline` chain consumes it. That is only true if the bytes have exactly the geometry the chain was
 * built for - 16 kHz, mono, signed 16-bit little-endian, which is also the CXR uplink's verified format. The
 * evidence id names the platform API rather than a CXR firmware pairing: this format is what `AudioRecord` is
 * asked for and verified against at start, not something measured off the glasses.
 */
object PhoneMicFormat {
    const val SAMPLE_RATE_HZ = 16_000
    const val CHANNELS = 1
    const val BITS_PER_SAMPLE = 16
    const val BYTES_PER_SAMPLE = BITS_PER_SAMPLE / 8

    /** One 20 ms read: what `PcmIngress`/`PcmFramer` already treat as one frame at this rate. */
    const val CHUNK_SAMPLES = SAMPLE_RATE_HZ / 50
    const val CHUNK_BYTES = CHUNK_SAMPLES * BYTES_PER_SAMPLE

    /** The format the phone source claims; a mismatch is refused, never resampled (spec §4.2). */
    val VERIFIED: VerifiedPcm = VerifiedPcm(
        format = AudioFormat.Pcm(SAMPLE_RATE_HZ, CHANNELS, BITS_PER_SAMPLE),
        encoding = PcmEncoding.SIGNED_LE,
        evidenceId = "phone-mic-audiorecord-16k-mono-16le",
    )

    /**
     * Whether [format] is the geometry the existing chain consumes.
     *
     * Deliberately an equality on every field the framer reads: rate, mono, 16-bit, little-endian. A stream that is
     * merely "close" (8 kHz, stereo, big-endian) would frame into plausible-looking garbage, so it is a refusal.
     */
    fun isChainGeometry(format: VerifiedPcm): Boolean =
        format.format.sampleRateHz == SAMPLE_RATE_HZ &&
            format.format.channels == CHANNELS &&
            format.format.bitsPerSample == BITS_PER_SAMPLE &&
            format.encoding == PcmEncoding.SIGNED_LE
}

/**
 * The microphone as `PhoneMicCapture` sees it, so the capture loop is testable without an `AudioRecord`.
 *
 * The methods are exactly the three things the capture does with the recorder - begin, read samples, release - and
 * their failure vocabulary is the stable one: [start] returns false for "this device cannot record", or throws a
 * [ContractError.CapabilityMissing] carrying `MIC_UNAVAILABLE`/`MIC_START_FAILED` when it can say more precisely why.
 * A JVM test substitutes this interface; production is [PhoneMicDevice].
 */
interface PcmRecordDevice {
    /** Begins recording. False means the device is unavailable; a thrown `CapabilityMissing` carries the stable code. */
    fun start(): Boolean

    /**
     * Reads mono 16-bit samples into [buffer].
     *
     * @return the number of samples read, 0 when none are ready yet, or a negative `AudioRecord` error code.
     */
    fun read(buffer: ShortArray): Int

    /**
     * Asks a blocked [read] to return **without releasing** the recorder.
     *
     * [PhoneMicCapture.stop] calls this before joining the reader: a release from another thread while a read is in
     * flight is the kind of use-after-free the capture must not risk. Releasing stays [stop]'s job, after the join.
     * Idempotent, and safe when nothing is recording.
     */
    fun wake()

    /** Stops recording and releases the platform recorder. Idempotent. */
    fun stop()
}

/**
 * The production recorder: `AudioRecord` at 16 kHz / mono / 16-bit LE from `MediaRecorder.AudioSource.MIC`.
 *
 * **The microphone is the one thing this task adds to the phone.** Everything downstream is the existing chain, so
 * this class is deliberately thin and fail-closed:
 *
 * - the requested geometry is checked against the **actual** recorder after construction; a platform that hands back
 *   something else is refused as [PhoneMicCapture.MIC_FORMAT_UNSUPPORTED] rather than passed on (no resampling);
 * - a recorder that cannot be built (occupied, permission revoked, no capture device) is
 *   [PhoneMicCapture.MIC_UNAVAILABLE]; one that builds but cannot begin recording is
 *   [PhoneMicCapture.MIC_START_FAILED]. Both are thrown to [PhoneMicCapture], which records them for the caller/UI;
 * - [stop] stops and releases exactly once, so the pause/stop/close paths can call it defensively.
 *
 * No `Context` is needed (the permission is checked by the platform at `AudioRecord` construction), which is also why
 * this path does not touch the `AssetManager` and the JVM tests can drive [PcmRecordDevice] instead.
 */
class PhoneMicDevice : PcmRecordDevice {

    private var record: AudioRecord? = null

    override fun start(): Boolean {
        if (record != null) return true
        val minBufferBytes = AudioRecord.getMinBufferSize(
            PhoneMicFormat.SAMPLE_RATE_HZ,
            PlatformAudioFormat.CHANNEL_IN_MONO,
            PlatformAudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBufferBytes <= 0) {
            throw ContractError.CapabilityMissing(PhoneMicCapture.MIC_UNAVAILABLE)
        }
        // Eight 20 ms chunks of headroom: enough that the reader thread is not the recorder's bottleneck, and the same
        // order of magnitude the CXR receiver buffers. The recorder's own minimum always wins when it is larger.
        val bufferBytes = maxOf(minBufferBytes, PhoneMicFormat.CHUNK_BYTES * 8)
        val built = try {
            AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.MIC)
                .setAudioFormat(
                    PlatformAudioFormat.Builder()
                        .setSampleRate(PhoneMicFormat.SAMPLE_RATE_HZ)
                        .setChannelMask(PlatformAudioFormat.CHANNEL_IN_MONO)
                        .setEncoding(PlatformAudioFormat.ENCODING_PCM_16BIT)
                        .build(),
                )
                .setBufferSizeInBytes(bufferBytes)
                .build()
        } catch (_: Exception) {
            throw ContractError.CapabilityMissing(PhoneMicCapture.MIC_UNAVAILABLE)
        }
        // Verify what actually exists, not what was requested (spec §4.2: "启动时校验实际配置，不一致报
        // MIC_FORMAT_UNSUPPORTED，不重采样、不猜").
        if (built.sampleRate != PhoneMicFormat.SAMPLE_RATE_HZ ||
            built.channelCount != PhoneMicFormat.CHANNELS ||
            built.audioFormat != PlatformAudioFormat.ENCODING_PCM_16BIT
        ) {
            runCatching { built.release() }
            throw ContractError.CapabilityMissing(PhoneMicCapture.MIC_FORMAT_UNSUPPORTED)
        }
        if (built.state != AudioRecord.STATE_INITIALIZED) {
            runCatching { built.release() }
            throw ContractError.CapabilityMissing(PhoneMicCapture.MIC_UNAVAILABLE)
        }
        try {
            built.startRecording()
        } catch (_: Exception) {
            runCatching { built.release() }
            throw ContractError.CapabilityMissing(PhoneMicCapture.MIC_START_FAILED)
        }
        if (built.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            runCatching { built.release() }
            throw ContractError.CapabilityMissing(PhoneMicCapture.MIC_START_FAILED)
        }
        record = built
        return true
    }

    override fun read(buffer: ShortArray): Int {
        val recorder = record ?: return -1
        return recorder.read(buffer, 0, buffer.size)
    }

    override fun wake() {
        val recorder = record ?: return
        // Stop the stream so a blocked `read` returns, but keep the object: `stop` releases it once the reader is gone.
        runCatching { if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) recorder.stop() }
    }

    override fun stop() {
        wake()
        val recorder = record ?: return
        record = null
        runCatching { recorder.release() }
    }
}
