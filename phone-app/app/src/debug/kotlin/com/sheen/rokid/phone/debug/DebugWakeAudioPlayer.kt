package com.sheen.rokid.phone.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import com.sheen.rokid.phone.platform.P3Log
import java.io.File
import kotlin.math.max

/**
 * Debug-only **acoustic** wake-word player (0.4 Task 9, `R-0.4-D5`/`D5b`).
 *
 * The detached wake route listens to the **phone** microphone, so its test driver must put the wake word into the air
 * between the phone's own speaker and its own microphone. The existing `DebugAudioInjector` deliberately does the
 * opposite - it offers PCM to the live CXR ingress, replacing the acoustic path - so it cannot answer "does the phone
 * microphone path wake?" at all (that is the question this task has to measure). Playing a file through a normal media
 * player from a host shell has no unattended path on this device (file URIs raise a picker), so the App gets one
 * debug-only speaker instrument instead.
 *
 * It plays a WAV to `STREAM_MUSIC` at the stream's maximum volume, through an [AudioTrack], and logs counts only:
 *
 * ```
 * adb shell am broadcast -a edith.DEBUG_WAKE_AUDIO -n com.sheen.rokid.phone/.debug.DebugWakeAudioPlayer
 * adb shell am broadcast -a edith.DEBUG_WAKE_AUDIO -n com.sheen.rokid.phone/.debug.DebugWakeAudioPlayer \
 *     --es path /data/data/com.sheen.rokid.phone/files/wake-zh.wav
 * ```
 *
 * The path must be readable by the **app** UID (`files/` written through `run-as`), not `/data/local/tmp`, which the
 * app cannot traverse. Default is `filesDir/wake-zh.wav`.
 *
 * The WAV geometry is parsed by [DebugWavPcm], the same conversion the injection instrument uses (PCM16 only, 16 kHz
 * mono output), so the file's declared rate/channels are handled rather than assumed; only counts are logged.
 *
 * **Why it exists only in the debug source set.** The receiver is exported so `am broadcast` from the host shell can
 * reach it. A shipped APK must never contain a component that plays arbitrary audio on the wearer's phone. It is
 * registered in `src/debug/AndroidManifest.xml` and cannot appear in a release build.
 *
 * It does **not** touch the session, the CXR transport or the audio ingress: the wake detector hears the speaker
 * exactly as it would hear a person in the room. That is the whole point, and it is why the evidence it produces is
 * labelled synthetic-acoustic rather than real-voice.
 */
class DebugWakeAudioPlayer : BroadcastReceiver() {

    /** Resolves, parses and plays the file. Never throws out of the receiver; every failure is a refused line. */
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_PLAY) return
        // The playback length is the file's length (potentially ~10 s): goAsync keeps the receiver process alive.
        val pending = goAsync()
        Thread({
            try {
                play(context.applicationContext, intent.getStringExtra(EXTRA_PATH))
            } catch (unexpected: Throwable) {
                refused("UNEXPECTED_ERROR")
            } finally {
                runCatching { pending.finish() }
            }
        }, THREAD_NAME).apply { isDaemon = true }.start()
    }

    private fun play(context: Context, requestedPath: String?) {
        val path = requestedPath?.takeIf { it.isNotBlank() }
            ?: File(context.filesDir, DEFAULT_FILE_NAME).absolutePath
        val file = File(path)
        if (!file.isFile) return refused("FILE_MISSING")

        val bytes = runCatching { file.readBytes() }.getOrNull() ?: return refused("READ_FAILED")
        val loaded = DebugWavPcm.load(bytes)
        if (loaded is DebugWavPcm.Loaded.Refused) return refused(loaded.code)
        loaded as DebugWavPcm.Loaded.Ready
        val totalBytes = loaded.chunks.sumOf { it.size }
        if (totalBytes <= 0) return refused("EMPTY")

        // Maximum media volume: the phone's speaker has to reach its own microphone, and the acceptance measures the
        // acoustic path at a defined, loudest setting. A failure to change it is not a playback failure (the track still
        // plays); the logged level makes the difference visible.
        val streamVolume = maximizeMediaVolume(context)
        P3Log.line(
            "P4_WAKE_AUDIO state=playing file=${file.name} bytes=$totalBytes chunks=${loaded.chunks.size} " +
                "sampleRateHz=${DebugWavPcm.TARGET_SAMPLE_RATE_HZ} streamVolume=$streamVolume",
        )

        val track = runCatching { buildTrack() }.getOrNull()
            ?: return refused("TRACK_INIT_FAILED")
        var written = 0
        try {
            runCatching { track.play() }.onFailure { return refused("PLAY_FAILED") }
            for (chunk in loaded.chunks) {
                val count = track.write(chunk, 0, chunk.size)
                if (count <= 0) return refused("WRITE_FAILED")
                written += count
            }
            // Do not call stop() while the last chunks are still in the track's buffer: on this API level stop() ends
            // playback immediately and the tail would never reach the speaker. Wait (bounded) for the playback head to
            // pass the written frames, then tear the track down.
            awaitDrained(track, written)
        } finally {
            runCatching { track.stop() }
            runCatching { track.release() }
        }
        P3Log.line("P4_WAKE_AUDIO state=done bytes=$written")
    }

    /**
     * Blocks until the playback head has consumed [writtenBytes] or a bounded wait elapses.
     *
     * A timeout is not a refusal: the audio was handed to the platform and the track is released normally; the track's
     * own buffer is allowed to drain. Only the line's absence of a second `done` would be a failure, and this method
     * cannot distinguish a slow drain from a dead mixer, so it does not pretend to. It is a pacing aid, not evidence.
     */
    private fun awaitDrained(track: AudioTrack, writtenBytes: Int) {
        val totalFrames = writtenBytes / BYTES_PER_FRAME
        val deadline = System.currentTimeMillis() + DRAIN_TIMEOUT_MILLIS
        while (System.currentTimeMillis() < deadline) {
            val position = runCatching { track.playbackHeadPosition }.getOrDefault(0)
            if (position >= totalFrames) return
            runCatching { Thread.sleep(DRAIN_POLL_MILLIS) }
        }
    }

    private fun buildTrack(): AudioTrack {
        val minBufferBytes = AudioTrack.getMinBufferSize(
            DebugWavPcm.TARGET_SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(DebugWavPcm.TARGET_SAMPLE_RATE_HZ)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(max(minBufferBytes, BUFFER_BYTES))
            .build()
    }

    /** Sets `STREAM_MUSIC` to its maximum and returns the level that is actually in force (-1 when unreadable). */
    private fun maximizeMediaVolume(context: Context): Int {
        val audio = context.getSystemService(AudioManager::class.java) ?: return -1
        return runCatching {
            val maximum = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, maximum, 0)
            audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        }.getOrDefault(-1)
    }

    private fun refused(code: String) {
        P3Log.line("P4_WAKE_AUDIO state=refused reason=$code bytes=0")
    }

    companion object {
        /** The explicit action; the only caller is an `adb shell am broadcast`. */
        const val ACTION_PLAY = "edith.DEBUG_WAKE_AUDIO"

        /** Optional app-readable WAV path; default `filesDir/wake-zh.wav`. */
        const val EXTRA_PATH = "path"

        private const val DEFAULT_FILE_NAME = "wake-zh.wav"
        private const val THREAD_NAME = "edith-debug-wake-audio"
        private const val BYTES_PER_FRAME = 2
        private const val BUFFER_BYTES = 640 * 8
        private const val DRAIN_POLL_MILLIS = 25L
        private const val DRAIN_TIMEOUT_MILLIS = 20_000L
    }
}
