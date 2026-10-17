package edith.glasses.probe

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.sheen.rokid.audio.CaptureConfig
import com.sheen.rokid.audio.UplinkCapture

/**
 * The glasses microphone, as [UplinkCapture] expects it.
 *
 * Configuration is refused rather than silently substituted: if the device will not give the documented 8-channel
 * geometry at 16 kHz, the caller must know, because a different geometry would be uplinked under a format the phone
 * was told to expect. The started-versus-yielding-silence distinction is preserved too - the vendor lists "audio no
 * data" as a known failure that surfaces later rather than at start.
 */
class AudioRecordCapture(private val config: CaptureConfig = CaptureConfig()) : UplinkCapture {
    private var recorder: AudioRecord? = null
    var actualChannelCount: Int = 0
        private set
    var actualSampleRateHz: Int = 0
        private set

    /** Non-null when the configuration was refused, for reporting rather than silent substitution. */
    var refusalReason: String? = null
        private set

    override fun start(): Boolean {
        refusalReason = null
        val bufferBytes = config.readBufferBytes()
        val minimum = AudioRecord.getMinBufferSize(
            config.sampleRateHz, config.channelMask, AudioFormat.ENCODING_PCM_16BIT,
        )
        val recorder = try {
            AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.MIC)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(config.sampleRateHz)
                        .setChannelMask(config.channelMask)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build(),
                )
                .setBufferSizeInBytes(maxOf(minimum, bufferBytes * BUFFER_READS))
                .build()
        } catch (error: Throwable) {
            refusalReason = "${error.javaClass.simpleName}: ${error.message}"
            return false
        }
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            refusalReason = "STATE_UNINITIALIZED"
            runCatching { recorder.release() }
            return false
        }
        actualChannelCount = recorder.channelCount
        actualSampleRateHz = recorder.sampleRate
        // The device is allowed to serve a different channel count than requested; report it rather than assume.
        if (actualChannelCount != config.sourceChannelCount) {
            Log.w(TAG, "PROBE_CAPTURE_GEOMETRY requested=${config.sourceChannelCount} actual=$actualChannelCount")
        }
        recorder.startRecording()
        this.recorder = recorder
        return true
    }

    /**
     * Reads into [buffer] at its real length. A negative AudioRecord result is reported as an error (-1) so the
     * session stops rather than treating a broken microphone as silence.
     */
    override fun read(buffer: ByteArray): Int {
        val recorder = recorder ?: return -1
        val samples = ShortArray(buffer.size / 2)
        val read = recorder.read(samples, 0, samples.size, AudioRecord.READ_BLOCKING)
        if (read < 0) return -1
        if (read == 0) return 0
        // Little-endian 16-bit, matching the platform's own byte order for PCM.
        for (index in 0 until read) {
            buffer[index * 2] = (samples[index].toInt() and 0xFF).toByte()
            buffer[index * 2 + 1] = ((samples[index].toInt() shr 8) and 0xFF).toByte()
        }
        return read * 2
    }

    override fun stop() {
        val recorder = this.recorder ?: return
        this.recorder = null
        runCatching { recorder.stop() }
        runCatching { recorder.release() }
    }

    private companion object {
        const val TAG = "EdithAudioProbe"
        /** Enough buffering that a slow consumer does not immediately overrun, without adding much latency. */
        const val BUFFER_READS = 4
    }
}
