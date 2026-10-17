package com.sheen.rokid.phone.debug
import com.sheen.rokid.phone.platform.P3Log
import com.sheen.rokid.phone.bridge.RokidConnection
import com.sheen.rokid.phone.app.EdithApplication
import com.sheen.rokid.phone.app.HostCompositionRoot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.sheen.rokid.audio.PcmIngress
import com.sheen.rokid.audio.PcmOffer
import com.sheen.rokid.contracts.AudioFormat
import com.sheen.rokid.contracts.GapReason
import com.sheen.rokid.contracts.InputEpoch
import com.sheen.rokid.contracts.PcmEncoding
import com.sheen.rokid.contracts.VerifiedPcm
import com.sheen.rokid.transport.RokidSessionTransport
import java.io.File

/**
 * Debug-only PCM injection instrument (P3.1 device diagnosis).
 *
 * Three acoustic playbacks of the user's wake-word recording produced no `P3_WAKE` while the glasses' uplink stayed
 * alive (`P3_FRAME accepted=… overflow=0`), and that evidence alone cannot separate the two candidate causes: weak
 * acoustic coupling through the phone speaker, or poor keyword-spotting recall for `伊迪丝`. This receiver removes
 * the acoustic half from the question by offering a WAV file's PCM straight to the **live** [PcmIngress] - the exact
 * object the CXR transport feeds - so the keyword spotter, the speech pipeline and everything downstream of them runs
 * on known input. The glasses microphone → CXR hop is already independently proven by `P3_FRAME`, so a `P3_WAKE`
 * after an injection is positive proof of recall, and its absence is a measurement of the recording against the
 * detector **without** the microphone in the path.
 *
 * **The recording must be the only content.** The glasses' own real-time stream is offered into the *same* bounded
 * ingress at the poll cadence, so a paced injection would otherwise interleave with ambient audio one-for-one and the
 * detector would hear 20 ms islands of the recording. The burst therefore stops the transport's microphone stream
 * (`P3_INJECT state=ambient_stopped`), clears the ingress, injects one 20 ms chunk per 20 ms in 1.5 s windows with a
 * drain between them, and restores the stream (`state=ambient_restored`). A stream that cannot be proved stopped is
 * a distinct `AMBIENT_STOP_FAILED` refusal **instead of** an injection into the mix.
 *
 * Driven from a host shell:
 *
 * ```
 * adb shell am broadcast -a edith.DEBUG_INJECT -n com.sheen.rokid.phone/.DebugAudioInjector \
 *     --es path /data/local/tmp/edith-audio/wake-zh.wav
 * ```
 *
 * It exists **only in the debug source set** (see `src/debug/AndroidManifest.xml`) and is `exported` so `am
 * broadcast` can reach it. A release build contains no receiver, no exported component and no injection path.
 *
 * Privacy: only counters and the file's basename are logged. No PCM sample, and never the file's path or contents,
 * reaches logcat (P3.1's logging rules; `P3_INJECT` carries `chunks=<n>` and offer counters, nothing else).
 */
class DebugAudioInjector : BroadcastReceiver() {

    /**
     * Resolves, reads, converts and offers the file. Runs off the main thread: the main thread is the one Android
     * gives a broadcast receiver, and MIUI's cold start has already been measured blocking it for ~16 s. Nothing in
     * here may throw - a throw inside a receiver's worker would only become a mysterious process death, so every
     * refusal path is a `P3_INJECT state=refused reason=<code>` line instead.
     */
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_INJECT) return
        // `goAsync` because the worker thread outlives `onReceive` by however long the burst takes (a long file is
        // paced in real time, so it can take seconds).
        val pending = goAsync()
        Thread({
            try {
                inject(context.applicationContext, intent.getStringExtra(EXTRA_PATH))
            } catch (unexpected: Throwable) {
                // Last-resort net: `inject` answers its own refusals, so reaching here means something unforeseen
                // (a blocked thread, an unwritable state). Logged with a distinct code rather than thrown into the
                // receiver, and never with the payload.
                refused("UNEXPECTED_ERROR")
            } finally {
                runCatching { pending.finish() }
            }
        }, THREAD_NAME).apply { isDaemon = true }.start()
    }

    private fun inject(context: Context, path: String?) {
        val source = path?.takeIf { it.isNotBlank() } ?: return refused("MISSING_PATH")
        val file = File(source)
        if (!file.isFile) return refused("FILE_MISSING")

        val application = context as? EdithApplication ?: return refused("NO_APPLICATION")
        val composition = application.composition
        // The epoch the live ingress was opened with, not a fresh one: `PcmIngress.offer` refuses anything whose
        // epoch does not match the one it was opened with (`STALE_EPOCH`), and a new epoch would tag every frame
        // with an identity the host's delivery gate drops.
        val epoch = composition.currentAudioEpoch() ?: return refused("NO_EPOCH")
        val transport = composition.cxrInputPort() as? RokidSessionTransport
            ?: return refused("INGRESS_UNREACHABLE")
        val ingress = liveIngress(transport) ?: return refused("INGRESS_UNREACHABLE")

        val bytes = runCatching { file.readBytes() }.getOrNull() ?: return refused("READ_FAILED")
        val loaded = DebugWavPcm.load(bytes)
        if (loaded is DebugWavPcm.Loaded.Refused) return refused(loaded.code)
        loaded as DebugWavPcm.Loaded.Ready

        P3Log.line("P3_INJECT state=start file=${file.name} sampleRateHz=${loaded.sampleRateHz} " +
            "channels=${loaded.channels} chunks=${loaded.chunks.size} " +
            "windows=${DebugBurst.windowsFor(loaded.chunks.size)}")
        val outcome = DebugBurst.run(LiveBurstTarget(transport, ingress, epoch), loaded.chunks)
        outcome.refused?.let { return refused(it) }
        // The burst's own end line: the offer breakdown distinguishes "injected but not detected" from "not injected
        // at all", and `drained=false` says some of the file may not have been consumed before the stream came back.
        P3Log.line("P3_INJECT state=end chunks=${loaded.chunks.size} accepted=${outcome.accepted} " +
            "stale=${outcome.stale} unknown=${outcome.unknown} overflow=${outcome.overflow} " +
            "windows=${outcome.windows} drained=${outcome.drained}")
    }

    /**
     * The live connection's own [PcmIngress].
     *
     * There is no accessor for it: the composition hands the ingress to the transport (`RokidConnection.ingress` →
     * `RokidSessionTransport`), and only the transport and the audio chain ever hold it - the chain's field is
     * private and `HostCompositionRoot` exposes the transport's `InputPort`, not the ingress. This instrument is
     * restricted to the debug source set (the task constrains changes to this file, its test and the debug manifest),
     * so the transport is resolved through the public `cxrInputPort()` seam and its single `PcmIngress` field is
     * read reflectively. The search is **by type**, not by name, because the debug build runs R8.
     *
     * Returns null - never throws - when there is no CXR transport, so the caller reports `INGRESS_UNREACHABLE`.
     */
    private fun liveIngress(transport: RokidSessionTransport): PcmIngress? {
        val field = transport.javaClass.declaredFields.firstOrNull { it.type == PcmIngress::class.java }
            ?: return null
        return runCatching {
            field.isAccessible = true
            field.get(transport) as? PcmIngress
        }.getOrNull()
    }

    private fun refused(code: String) {
        P3Log.line("P3_INJECT state=refused reason=$code")
    }

    companion object {
        /** The explicit action, so the receiver is reachable without a matching implicit broadcast. */
        const val ACTION_INJECT = "edith.DEBUG_INJECT"

        /** The WAV file's absolute path on the device. */
        const val EXTRA_PATH = "path"

        private const val THREAD_NAME = "edith-debug-inject"
    }
}

/**
 * The live objects one burst drives, as a seam.
 *
 * The burst's rules are a **sequence of side effects** - stop the glasses' microphone stream, clear whatever an
 * earlier burst left queued, offer the recording one 20 ms chunk per 20 ms, drain between windows, restore the
 * stream - so they are decided here against this interface and pinned by JVM tests, while the Android half
 * ([LiveBurstTarget]) only maps each call onto the transport and the ingress that already exist.
 */
internal interface DebugBurstTarget {
    /** Stops the glasses' microphone stream. False when it cannot be proved stopped - the caller must then refuse. */
    fun stopAmbient(): Boolean

    /** Clears anything a previous burst left queued (`PcmIngress.reset`), so it cannot overflow into this one. */
    fun resetIngress(reason: GapReason)

    /**
     * (Re)opens the ingress for the burst's epoch.
     *
     * Needed because the existing stop path closes the ingress with the stream; false when it cannot be opened, which
     * is a refusal rather than an injection into an ingress nobody can accept into.
     */
    fun openIngress(): Boolean

    /** Offers one 20 ms chunk of the recording. */
    fun offer(chunk: ByteArray): PcmOffer

    /** Bytes still queued in the ingress, for the inter-window drain. */
    fun queuedBytes(): Int

    /** Restores the microphone stream. False when it could not be restored. */
    fun restoreAmbient(): Boolean
}

/** What one burst did, as counters only: no audio, no path, nothing a device log could leak. */
internal data class DebugBurstOutcome(
    val accepted: Int = 0, val stale: Int = 0, val unknown: Int = 0, val overflow: Int = 0,
    /** Windows the file was injected in; a file that fits the ingress bound is one window. */
    val windows: Int = 0,
    /** False when a drain hit its bound with bytes still queued - i.e. some of the file may not have been consumed. */
    val drained: Boolean = true,
    val ambientStopped: Boolean = false, val ambientRestored: Boolean = false,
    /** The first refusal, or null when the burst ran; see [DebugBurst] for the codes. */
    val refused: String? = null,
)

/**
 * The pure half of one injection burst: ambient off → reset → paced offers in bounded windows with a drain between
 * them → ambient back on.
 *
 * **Why the ambient stream has to go first.** The real-time glasses stream is offered into the *same* bounded
 * `PcmIngress` at the poll cadence, so injected chunks and ambient chunks alternate in one FIFO: the detector then
 * hears 20 ms islands of the recording separated by ambient audio, which is neither the recording nor a measurement
 * of it. Stopping the stream is the only way the recording is the sole content, and a stop that cannot be proved is
 * answered with `AMBIENT_STOP_FAILED` **instead of** injecting into the mix.
 *
 * **Why the offsets.** The ingress is bounded at 2 s (100 chunks at the verified geometry), so a window is 1.5 s
 * (75 chunks) - comfortably inside the bound - and each window is followed by a bounded drain of the ingress queue
 * before the next one starts. Within a window the pacing is one chunk per [PACE_MILLIS], which is the loop's own
 * cadence, so what the detector hears is real-time audio rather than a burst.
 *
 * **Why the refusal does not throw.** This runs on the broadcast receiver's worker: a throw would surface as a
 * process death rather than as evidence, so every refusal is a value.
 */
internal object DebugBurst {
    /** 1.5 s of 20 ms chunks: inside the ingress's 2 s bound with a margin. */
    const val WINDOW_CHUNKS = 75

    /** The ingress poll cadence and the frame length; the injection is paced at exactly this. */
    const val PACE_MILLIS = 20L

    /** How long one drain waits, in steps, before it gives up and reports [DebugBurstOutcome.drained] `false`. */
    const val DRAIN_STEP_MILLIS = 10L
    const val DRAIN_MAX_STEPS = 50

    /** The windows a file of [chunks] chunks is injected in; 0 for an empty file. */
    fun windowsFor(chunks: Int): Int =
        if (chunks <= 0) 0 else (chunks + WINDOW_CHUNKS - 1) / WINDOW_CHUNKS

    /**
     * Runs one burst. [sleep] is injected so the pacing is testable without sleeping 20 ms per chunk.
     *
     * The restore is in a `finally`: whichever way the burst ends, the microphone the burst stopped is put back. A
     * restore that fails is still reported (the outcome's `ambientRestored`), so the device log can never present a
     * stopped stream as a normal ending.
     */
    fun run(target: DebugBurstTarget, chunks: List<ByteArray>,
        sleep: (Long) -> Unit = { millis -> Thread.sleep(millis) }): DebugBurstOutcome {
        // Refuse rather than inject into the mix.
        if (!target.stopAmbient()) return DebugBurstOutcome(refused = "AMBIENT_STOP_FAILED")

        var outcome = DebugBurstOutcome(ambientStopped = true)
        var restored = false
        try {
            // A previous burst's backlog must not overflow into this one; the reason names what actually broke
            // continuity (the transport closed the ingress with the stream).
            target.resetIngress(GapReason.SESSION_INTERRUPTED)
            if (!target.openIngress()) {
                outcome = outcome.copy(refused = "INGRESS_REOPEN_FAILED")
            } else {
                var index = 0
                while (index < chunks.size) {
                    val windowEnd = minOf(index + WINDOW_CHUNKS, chunks.size)
                    while (index < windowEnd) {
                        outcome = outcome.count(target.offer(chunks[index]))
                        index++
                        sleep(PACE_MILLIS)
                    }
                    outcome = outcome.copy(windows = outcome.windows + 1)
                    outcome = drain(target, outcome, sleep)
                }
            }
        } finally {
            restored = target.restoreAmbient()
        }
        return outcome.copy(ambientRestored = restored)
    }

    /**
     * Waits - bounded - for the poll loop to consume the window just offered.
     *
     * The bound matters: the loop runs on the app's deadline thread and a stopped or wedged loop must not hang the
     * receiver's worker forever. A drain that hits the bound with bytes still queued is reported, not swallowed.
     */
    private fun drain(target: DebugBurstTarget, outcome: DebugBurstOutcome,
        sleep: (Long) -> Unit): DebugBurstOutcome {
        var steps = 0
        while (target.queuedBytes() > 0 && steps < DRAIN_MAX_STEPS) {
            sleep(DRAIN_STEP_MILLIS)
            steps++
        }
        return if (steps >= DRAIN_MAX_STEPS && target.queuedBytes() > 0) outcome.copy(drained = false) else outcome
    }

    private fun DebugBurstOutcome.count(offer: PcmOffer): DebugBurstOutcome = when (offer) {
        PcmOffer.ACCEPTED -> copy(accepted = accepted + 1)
        PcmOffer.STALE_EPOCH -> copy(stale = stale + 1)
        PcmOffer.UNKNOWN_FORMAT -> copy(unknown = unknown + 1)
        PcmOffer.OVERFLOW -> copy(overflow = overflow + 1)
    }
}

/**
 * The Android half of one burst: the live transport that owns the microphone stream and the ingress it feeds.
 *
 * Both directions use a path that already exists rather than a new API. Stopping is
 * `RokidSessionTransport.stop(epoch)` → `revoke()` → `CxrBackend.stop` → the CXR session's `stopAudioStream()`
 * plus the client audio installer's release; restoring is `RokidSessionTransport.start(epoch)`, which reopens the
 * ingress and asks the glasses for the stream again. The composition's own armed epoch is untouched, so the poll
 * loop that was already running keeps polling the same epoch across the burst.
 */
private class LiveBurstTarget(
    private val transport: RokidSessionTransport,
    private val ingress: PcmIngress,
    private val epoch: InputEpoch,
) : DebugBurstTarget {

    /**
     * The stop path closes the ingress as well as the stream, and `PcmIngress` uses only `format.format` (its
     * capacity and its `UNKNOWN_FORMAT` gate); the framer keeps the production `VerifiedPcm` it was built with, so
     * reopening with the transport's own resolved geometry is faithful. A transport that never resolved one keeps
     * refusing rather than injecting under a guessed format.
     */
    override fun openIngress(): Boolean {
        val format = transport.state.format as? AudioFormat.Pcm ?: return false
        return runCatching {
            ingress.open(epoch, VerifiedPcm(format, PcmEncoding.SIGNED_LE, INJECTION_EVIDENCE_ID))
            true
        }.getOrDefault(false)
    }

    override fun stopAmbient(): Boolean {
        transport.stop(epoch)
        // `revoke()` runs synchronously inside `stop`, so a null epoch is the proof that the live callback was
        // detached; the SDK's own `stopAudioStream()` follows on the transport worker.
        val stopped = transport.state.epoch == null
        if (stopped) P3Log.line("P3_INJECT state=ambient_stopped")
        return stopped
    }

    override fun resetIngress(reason: GapReason) {
        ingress.reset(reason)
    }

    override fun offer(chunk: ByteArray): PcmOffer = ingress.offer(epoch, chunk)

    override fun queuedBytes(): Int = ingress.queuedBytes

    override fun restoreAmbient(): Boolean {
        runCatching { transport.start(epoch) }
        val restored = transport.state.epoch == epoch
        P3Log.line(if (restored) "P3_INJECT state=ambient_restored"
            else "P3_INJECT state=ambient_restore_failed")
        return restored
    }

    private companion object {
        /**
         * Names the debug instrument, not a measurement: this `VerifiedPcm` only reaches the ingress's capacity
         * gate, and `PcmFormatEvidence`'s own entry stays the one the framer was built from.
         */
        const val INJECTION_EVIDENCE_ID = "debug-inject"
    }
}

/**
 * The pure half of the instrument: RIFF/WAVE → 16 kHz mono PCM16 → 20 ms chunks, with no Android type in sight.
 *
 * Kept separate from the receiver so the transformation is pinned by JVM tests and the untested part of the
 * instrument stays "resolve the live objects, read the file, offer the chunks".
 *
 * **Conversion rules, stated so the device evidence can be read correctly:**
 * - only 16-bit PCM (`audioFormat == 1`) is accepted; anything else is refused rather than guessed at;
 * - channels are downmixed by **averaging** every channel of a frame (so a stereo file whose channels differ keeps
 *   its common-mode content and loses the difference);
 * - the sample rate is converted with **linear interpolation** between the two neighbouring input frames, and the
 *   output length is `inputFrames * 16000 / inputRate` (truncated), so no sample beyond the input is invented;
 * - 16 kHz mono input is therefore copied through sample-for-sample.
 */
internal object DebugWavPcm {

    /** The rate the whole chain runs at (`AudioFormat.Pcm(16000, 1, 16)` is the verified uplink format). */
    const val TARGET_SAMPLE_RATE_HZ = 16_000

    /** One chunk is 20 ms, the frame length the manifest declares and the poll loop's cadence. */
    const val CHUNK_MILLIS = 20

    /** 20 ms of 16 kHz mono PCM16: 320 samples, 640 bytes - exactly one `PcmFramer` frame. */
    const val CHUNK_BYTES = TARGET_SAMPLE_RATE_HZ * CHUNK_MILLIS / 1000 * 2

    private const val CHUNK_SAMPLES = CHUNK_BYTES / 2

    /** A parsed file: the declared geometry and the interleaved PCM16 frames as they were stored. */
    data class Wav(val sampleRateHz: Int, val channels: Int, val samples: ShortArray)

    sealed interface Parsed {
        data class Ok(val wav: Wav) : Parsed
        data class Refused(val code: String) : Parsed
    }

    sealed interface Loaded {
        data class Ready(val sampleRateHz: Int, val channels: Int, val chunks: List<ByteArray>) : Loaded
        data class Refused(val code: String) : Loaded
    }

    /**
     * Parses the RIFF container and validates the PCM geometry; see the object's conversion rules.
     *
     * Chunks are walked rather than addressed at fixed offsets: `fmt ` is 16 bytes in the project's own recordings
     * but 18 in the synthesized ones, and the order between `fmt ` and `data` is not fixed by the format, so an
     * unknown chunk in between must be skipped by its declared length (plus its pad byte).
     */
    fun parse(bytes: ByteArray): Parsed {
        if (bytes.size < 12 || tag(bytes, 0) != "RIFF") return Parsed.Refused("NOT_RIFF")
        if (tag(bytes, 8) != "WAVE") return Parsed.Refused("NOT_WAVE")

        var position = 12
        var channels = 0
        var sampleRateHz = 0
        var sawFormat = false
        var dataOffset = -1
        var dataLength = 0L
        while (position + 8 <= bytes.size) {
            val id = tag(bytes, position)
            val declared = u32(bytes, position + 4)
            val start = position + 8
            if (start.toLong() + declared > bytes.size.toLong()) return Parsed.Refused("TRUNCATED")
            when (id) {
                "fmt " -> {
                    if (declared < 16) return Parsed.Refused("BAD_FMT")
                    val audioFormat = u16(bytes, start)
                    channels = u16(bytes, start + 2)
                    sampleRateHz = u32(bytes, start + 4).toInt()
                    val bitsPerSample = u16(bytes, start + 14)
                    // Only linear PCM16 is understood. Guessing at a float or 8-bit stream would inject noise the
                    // keyword spotter would then be judged on, so anything else is refused with a distinct code.
                    if (audioFormat != 1 || bitsPerSample != 16 || channels < 1 || sampleRateHz <= 0) {
                        return Parsed.Refused("UNSUPPORTED_FORMAT")
                    }
                    sawFormat = true
                }
                "data" -> {
                    dataOffset = start
                    dataLength = declared.coerceAtMost(bytes.size - start.toLong())
                }
            }
            position = start + declared.toInt() + (declared % 2).toInt()
        }
        if (!sawFormat) return Parsed.Refused("NO_FMT")
        if (dataOffset < 0) return Parsed.Refused("NO_DATA")

        val frames = (dataLength / (channels * 2L)).toInt()
        if (frames == 0) return Parsed.Refused("EMPTY")
        val samples = ShortArray(frames * channels)
        for (index in samples.indices) samples[index] = u16(bytes, dataOffset + index * 2).toShort()
        return Parsed.Ok(Wav(sampleRateHz, channels, samples))
    }

    /** Averages every channel per frame and linearly resamples to 16 kHz; see the object's conversion rules. */
    fun toMono16k(wav: Wav): ShortArray {
        val frames = wav.samples.size / wav.channels
        if (frames == 0) return ShortArray(0)
        val mono = ShortArray(frames)
        for (frame in 0 until frames) {
            var sum = 0L
            for (channel in 0 until wav.channels) sum += wav.samples[frame * wav.channels + channel]
            mono[frame] = Math.round(sum.toDouble() / wav.channels).coerceIn(-32_768L, 32_767L).toShort()
        }
        if (wav.sampleRateHz == TARGET_SAMPLE_RATE_HZ) return mono

        // Truncated, so the output never extends past the input: the last input frame is never extrapolated.
        val outputLength = (frames.toLong() * TARGET_SAMPLE_RATE_HZ / wav.sampleRateHz).toInt()
        val step = wav.sampleRateHz.toDouble() / TARGET_SAMPLE_RATE_HZ
        val resampled = ShortArray(outputLength)
        for (index in 0 until outputLength) {
            val position = index * step
            val lower = position.toInt()
            val fraction = position - lower
            val upper = if (lower + 1 < frames) lower + 1 else lower
            val value = mono[lower] * (1 - fraction) + mono[upper] * fraction
            resampled[index] = Math.round(value).coerceIn(-32_768L, 32_767L).toShort()
        }
        return resampled
    }

    /** Cuts [samples] into 20 ms chunks; a trailing partial chunk is dropped, never padded with invented audio. */
    fun chunk(samples: ShortArray): List<ByteArray> {
        val chunks = ArrayList<ByteArray>(samples.size / CHUNK_SAMPLES)
        var offset = 0
        while (offset + CHUNK_SAMPLES <= samples.size) {
            val chunk = ByteArray(CHUNK_BYTES)
            for (index in 0 until CHUNK_SAMPLES) {
                val value = samples[offset + index].toInt()
                chunk[index * 2] = (value and 0xFF).toByte()
                chunk[index * 2 + 1] = ((value shr 8) and 0xFF).toByte()
            }
            chunks += chunk
            offset += CHUNK_SAMPLES
        }
        return chunks
    }

    /** The receiver's entry point: parse → convert → chunk, or the first refusal code. */
    fun load(bytes: ByteArray): Loaded {
        val parsed = parse(bytes)
        if (parsed is Parsed.Refused) return Loaded.Refused(parsed.code)
        val wav = (parsed as Parsed.Ok).wav
        val chunks = chunk(toMono16k(wav))
        if (chunks.isEmpty()) return Loaded.Refused("TOO_SHORT")
        return Loaded.Ready(wav.sampleRateHz, wav.channels, chunks)
    }

    /** The four ASCII bytes at [offset]; the caller has already bounded the container. */
    private fun tag(bytes: ByteArray, offset: Int) = String(bytes, offset, 4, Charsets.US_ASCII)

    private fun u16(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

    private fun u32(bytes: ByteArray, offset: Int): Long =
        (bytes[offset].toLong() and 0xFF) or ((bytes[offset + 1].toLong() and 0xFF) shl 8) or
            ((bytes[offset + 2].toLong() and 0xFF) shl 16) or ((bytes[offset + 3].toLong() and 0xFF) shl 24)
}
