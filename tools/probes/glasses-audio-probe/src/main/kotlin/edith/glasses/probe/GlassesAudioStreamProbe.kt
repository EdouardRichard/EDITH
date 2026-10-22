package edith.glasses.probe

import com.rokid.cxr.CXRServiceBridge

/**
 * Drives the **glasses-side** audio-stream entry point and reports what the device says about it.
 *
 * This exists to answer exactly one question, and it is the only question it answers: **does calling
 * `CXRServiceBridge.startAudioStream(...)` from an application on the glasses make the device stream audio at
 * all?** Nothing here can prove audio *arrived* anywhere - only the phone can say that - so this probe deliberately
 * reports the device's own statements (the refusal code, the `onStartAudioRecord` confirmation, the streaming
 * state) and never converts any of them into a claim about delivery.
 *
 * Why this is a separate route from the two already refuted on this device:
 *
 * - The **supported session API** (`CxrSession.startAudioStream()` + `IAudioCallback`) was measured to deliver zero
 *   frames for three callers across six of six attempts, including Rokid's own built-in assistant.
 * - The **self-built uplink** (`AudioRecord` -> framed PCM -> `sendMessage`) was measured to deliver zero frames
 *   while the sender demonstrably succeeded (499 frames, `lastCode=0`), and survived five distinct fixes.
 * - `CXRServiceBridge.startAudioStream` is a **third, separate primitive**: it is the glasses-side sibling of the
 *   `openAudioRecord` call the device *itself* uses when it records (the device's own log shows `openAudioRecord2`
 *   and ~24 s of captured PCM on the assistant path), so it is the one audio entry point in the locked artifact
 *   that this project has never called.
 *
 * Deliberate constraints, carried from the earlier probes:
 *
 * - **RAM only.** No samples are read, held or written. The type this probe reports through cannot even hold audio,
 *   which is enforced by a test rather than by discipline.
 * - **Bounded.** Every attempt starts, is observed for a fixed window, and is stopped through [run].
 * - **One variable at a time.** The parameter meanings are undocumented, so the sweep varies `codecType` only and
 *   holds the stream type, the record parameters and the durations fixed. A positive must be attributable.
 */
class GlassesAudioStreamProbe(private val bridge: CXRServiceBridge = CXRServiceBridge()) {

    /** Per-attempt facts, all of them dictation from the device rather than interpretation. */
    @Volatile private var confirmations = 0
    @Volatile private var streaming = false
    @Volatile private var subscribed = -1
    @Volatile private var connected = false

    /**
     * Starts one attempt and returns what the device said. Never throws: a native failure is an outcome to report,
     * not something that may take down the probe thread - the same rule the uplink transport follows.
     */
    fun attempt(params: AudioStreamParams, observeMillis: Long): AudioStreamAttempt {
        confirmations = 0
        streaming = false
        var refusal = -1
        var error: String? = null
        val recordParam = CXRServiceBridge.AudioRecordParam(params.denoiseMode, params.rokidDtlnAEC, params.rokidBF)
        try {
            refusal = bridge.startAudioStream(params.codecType, params.secondary, params.streamType, recordParam)
        } catch (thrown: Throwable) {
            error = "${thrown.javaClass.simpleName}: ${thrown.message}"
        }
        if (error == null) {
            // Observed after the call rather than polled during it: the confirmation and the streaming flag arrive
            // on the bridge's own threads, and a full observation window is what separates "accepted" from
            // "actually started".
            val deadline = System.currentTimeMillis() + observeMillis
            while (System.currentTimeMillis() < deadline) {
                if (streaming) break
                try { Thread.sleep(POLL_MILLIS) } catch (interrupted: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
        }
        runCatching { bridge.stopAudioStream(params.streamType) }
        return AudioStreamAttempt(refusal, confirmations, streaming, error)
    }

    /**
     * Attaches the status listener and subscribes the command channel. Both are the preconditions the earlier
     * uplink work established: the bridge only reports a device once a listener is set, and `subscribe` is how this
     * end registers the channel it intends to use. Kept here so every attempt in a sweep runs under the same
     * preconditions.
     */
    fun attach(channelKey: String = DEFAULT_CHANNEL_KEY): Boolean = try {
        bridge.setStatusListener(object : CXRServiceBridge.StatusListener {
            override fun onConnected(p0: String?, p1: String?, p2: Int) { connected = true }
            override fun onDisconnected() { connected = false }
            override fun onConnecting(p0: String?, p1: String?, p2: Int) { connected = false }
            override fun onARTCStatus(p0: Float, p1: Boolean) = Unit
            override fun onRokidAccountChanged(p0: String?) = Unit
            override fun onAudioNoise(p0: Float) = Unit
        })
        subscribed = bridge.subscribe(channelKey, object : CXRServiceBridge.MsgCallback {
            override fun onReceive(name: String?, args: com.rokid.cxr.Caps?, data: ByteArray?) = Unit
        })
        true
    } catch (thrown: Throwable) {
        false
    }

    val subscriptionResult: Int get() = subscribed
    val deviceConnected: Boolean get() = connected

    fun close() { runCatching { bridge.disconnectCXRDevice() } }

    companion object {
        /** The same command channel the earlier uplink used, so this run changes one variable only. */
        const val DEFAULT_CHANNEL_KEY = "rk_custom_key"
        private const val POLL_MILLIS = 50L

        /**
         * The verdict for one attempt. Derived by rule, never by inspection, because the failure mode this guards
         * against is reading "the call returned 0" as success - which is exactly what the refuted `sendMessage`
         * route looked like (`lastCode=0` on 499 frames that never arrived).
         *
         * A non-zero refusal outranks everything: a confirmation arriving afterwards would describe a different
         * request, so it must not be able to turn a refusal into a start.
         */
        fun verdict(attempt: AudioStreamAttempt): AudioStreamVerdict = when {
            attempt.error != null -> AudioStreamVerdict.BRIDGE_THREW
            attempt.refusalCode != 0 -> AudioStreamVerdict.REFUSED_BY_BRIDGE
            attempt.streaming || attempt.confirmations > 0 -> AudioStreamVerdict.STREAM_STARTED
            else -> AudioStreamVerdict.ACCEPTED_BUT_NEVER_STREAMED
        }

        /**
         * The attempts to run. `codecType` is the only variable: its domain is undocumented, so the sweep walks the
         * small integers the device has been observed to use in its own logs while everything else stays fixed.
         *
         * Refuses an empty sweep (no evidence) and a blank stream type (an attempt that could not be stopped) rather
         * than producing a run whose result would be uninterpretable.
         */
        fun parameterSets(base: AudioStreamParams, codecTypes: List<Int>): List<AudioStreamParams> {
            require(codecTypes.isNotEmpty()) { "CODEC_TYPES_MUST_NOT_BE_EMPTY" }
            require(base.streamType.isNotBlank()) { "STREAM_TYPE_MUST_NOT_BE_BLANK" }
            return codecTypes.mapIndexed { index, codecType ->
                // The index, not the codec value, makes the name unique: a repeated codec in the list would
                // otherwise produce two attempts sharing one stream type, and the second could never be stopped.
                base.copy(codecType = codecType, streamType = "${base.streamType.trim()}-$index")
            }
        }

        /**
         * The single line a device run reports. Carries every input that decides the run, so two runs can be
         * compared; a line without the parameters would make a parameter sweep unreadable.
         */
        fun summary(
            attempt: AudioStreamAttempt,
            params: AudioStreamParams,
            attached: Boolean,
            connected: Boolean,
            subscribed: Int,
            seconds: Long,
            millis: Long,
        ): String = buildString {
            append("GLASSES_AUDIOSTREAM")
            append(" verdict=").append(verdict(attempt))
            append(" codecType=").append(params.codecType)
            append(" secondary=").append(params.secondary)
            append(" streamType=").append(params.streamType)
            append(" denoiseMode=").append(params.denoiseMode)
            append(" rokidDtlnAEC=").append(params.rokidDtlnAEC)
            append(" rokidBF=").append(params.rokidBF)
            append(" attached=").append(attached)
            append(" connected=").append(connected)
            append(" subscribed=").append(subscribed)
            append(" seconds=").append(seconds)
            append(" millis=").append(millis)
            append(" refusal=").append(attempt.refusalCode)
            append(" confirmations=").append(attempt.confirmations)
            append(" streaming=").append(attempt.streaming)
            append(" error=").append(attempt.error ?: "none")
        }
    }
}

/**
 * The outcome of one attempt, decided by rule so that "the call returned 0" can never be read as success - the
 * exact misreading that made the refuted `sendMessage` route look healthy (`lastCode=0` on 499 frames that never
 * arrived).
 *
 * Top-level rather than nested in the companion: Kotlin does not resolve a companion-nested type from outside the
 * class through the class name, and the tests and the reporter both need to name it.
 */
enum class AudioStreamVerdict {
    /** The bridge refused the request; the code is in `refusalCode`. */
    REFUSED_BY_BRIDGE,

    /** The call threw - a signature, link or threading problem rather than a policy refusal. */
    BRIDGE_THREW,

    /** The device reported streaming or confirmed the record start. Audio arrival is still unproven. */
    STREAM_STARTED,

    /** Accepted locally and nothing followed. The same shape as every previous negative on this device. */
    ACCEPTED_BUT_NEVER_STREAMED,
}

/**
 * The parameters for one attempt. `codecType` and `secondary` are `int` because the artifact takes `int`; their
 * meanings are undocumented, which is why the sweep varies only `codecType` and the report names each one.
 *
 * [streamType] must be unique per attempt, because `stopAudioStream` takes nothing else: two attempts sharing a
 * name would leave the first one running and make the second unstoppable. [GlassesAudioStreamProbe.parameterSets]
 * enforces that rather than relying on the caller.
 */
data class AudioStreamParams(
    val codecType: Int,
    val secondary: Int,
    val streamType: String,
    val denoiseMode: Int,
    val rokidDtlnAEC: Boolean,
    val rokidBF: Boolean,
)

/**
 * What the device said about one attempt. Carries counters and state only - no field can hold audio, so a run
 * cannot persist samples even by accident (D33).
 *
 * [confirmations] is the count of record-start confirmations the **public** surface reported. It is expected to
 * stay `0`: the AAR routes `onAudioRecordStart`/`onAudioRecordData` only into the callback registered through
 * `openAudioRecord(...)`, and `startAudioStream(...)` registers none, so this probe cannot observe them. The field
 * is kept because "zero confirmations" is itself part of the result and hiding it would overstate what was seen.
 */
data class AudioStreamAttempt(
    val refusalCode: Int,
    val confirmations: Int,
    val streaming: Boolean,
    val error: String?,
)
