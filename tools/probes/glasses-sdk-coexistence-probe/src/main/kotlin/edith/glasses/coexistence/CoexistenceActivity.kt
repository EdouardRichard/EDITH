package edith.glasses.coexistence

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.rokid.cxr.CXRServiceBridge
import com.rokid.cxr.Caps
import com.rokid.security.glass3.open.sdk.GlassSdk
import com.rokid.security.glass3.open.sdk.client.IServiceConnectionCallback
import com.rokid.security.glass3.sdk.base.data.offlineCmd.bean.VoiceAction
import com.rokid.security.glass3.sdk.base.data.offlineCmd.listener.IVoiceCallback
import com.rokid.security.system.server.IClientCallback
import kotlin.math.sqrt

/**
 * P3.0 T0 + T1: one Activity that drives **both** glasses-side SDK lines at once and reports whether each really works.
 *
 * Every symbol used here was read out of the resolved artifacts with `javap`, not from memory:
 *
 * - `GlassSdk.isReady()`, `bindSecurityService(Context, IServiceConnectionCallback)`,
 *   `registerClient(String, IClientCallback)`, `getGlassOfflineCmdService()`, `release()`.
 * - `IServiceConnectionCallback.onServiceConnected()/onServiceDisconnected()/onBindingDied()`.
 * - `IClientCallback extends IInterface` with a single `onReady()`.
 * - `IOfflineCmdService`: `add(VoiceAction)` returns **void**, while `setWords(String, List<VoiceAction>)` /
 *   `GlassSdk.setOfflineCmdWords(String, List<VoiceAction>)` return **boolean** and *replace* the whole set for a
 *   language. T1 therefore probes each word alone to learn whether the lexicon accepts it, then registers the accepted
 *   set in one call — calling the batch API per word would leave only the last one registered.
 * - `VoiceAction(String text, String pinyin, IVoiceCallback)`, `IVoiceCallback.onVoiceTriggered()`.
 * - The CXR-S bridge calls are the same ones `glasses-app`'s `RokidBridgeEndpoint` already runs on this hardware.
 *
 * The probe reports a *signal per question* rather than "it started": the risk it exists to settle is that two SDKs
 * initialise without error while one of them does nothing.
 */
class CoexistenceActivity : Activity() {

    private var bridge: CXRServiceBridge? = null

    @Volatile private var glassSdkReady = false
    @Volatile private var glassServiceReachable = false
    @Volatile private var cxrInbound = false
    @Volatile private var cxrOutboundAccepted = false

    @Volatile private var offlineTriggers = 0
    @Volatile private var captureBytes = 0L
    @Volatile private var capturePeakRms = 0
    @Volatile private var wordSummary = "not-run"
    @Volatile private var t1Started = false

    private val handler = Handler(Looper.getMainLooper())
    private val verdictTick = object : Runnable {
        override fun run() {
            logVerdict()
            handler.postDelayed(this, TICK_MILLIS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        initGlassSdk()
        initCxrBridge()
        handler.postDelayed(verdictTick, TICK_MILLIS)
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            logVerdict()
        } else {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), MIC_REQUEST)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
        Log.i(TAG, "T1_MIC_PERMISSION granted=$granted")
        if (granted) maybeStartT1()
    }

    override fun onDestroy() {
        handler.removeCallbacks(verdictTick)
        runCatching { GlassSdk.release() }
        super.onDestroy()
    }

    // ---------------------------------------------------------------- T0

    private fun initGlassSdk() {
        if (runCatching { GlassSdk.isReady() }.getOrDefault(false)) {
            onGlassBound()
            return
        }
        GlassSdk.bindSecurityService(applicationContext, object : IServiceConnectionCallback {
            override fun onServiceConnected() {
                Log.i(TAG, "GLASS_SERVICE_CONNECTED")
                runCatching {
                    GlassSdk.registerClient(CLIENT_ID, object : IClientCallback.Stub() {
                        override fun onReady() { Log.i(TAG, "GLASS_CLIENT_READY") }
                    })
                }.onFailure { Log.w(TAG, "registerClient failed", it) }
                onGlassBound()
            }

            override fun onServiceDisconnected() {
                glassSdkReady = false
                glassServiceReachable = false
                Log.w(TAG, "GLASS_SERVICE_DISCONNECTED")
                logVerdict()
            }

            override fun onBindingDied() {
                glassSdkReady = false
                glassServiceReachable = false
                Log.w(TAG, "GLASS_BINDING_DIED")
                logVerdict()
            }
        })
    }

    /**
     * `isReady()` alone proves only that a service is bound. The functional signal is that the offline wake-word
     * service this probe is really about is actually obtainable.
     */
    private fun onGlassBound() {
        glassSdkReady = runCatching { GlassSdk.isReady() }.getOrDefault(false)
        glassServiceReachable = runCatching { GlassSdk.getGlassOfflineCmdService() != null }.getOrDefault(false)
        Log.i(TAG, "GLASS_BOUND isReady=$glassSdkReady offlineCmdServiceReachable=$glassServiceReachable")
        logVerdict()
        maybeStartT1()
    }

    private fun initCxrBridge() {
        val created = runCatching {
            CXRServiceBridge().apply {
                setStatusListener(object : CXRServiceBridge.StatusListener {
                    override fun onConnected(p0: String?, p1: String?, p2: Int) { Log.i(TAG, "CXR_CONNECTED") }
                    override fun onDisconnected() { Log.i(TAG, "CXR_DISCONNECTED") }
                    override fun onConnecting(p0: String?, p1: String?, p2: Int) = Unit
                    override fun onARTCStatus(p0: Float, p1: Boolean) = Unit
                    override fun onRokidAccountChanged(p0: String?) = Unit
                    override fun onAudioNoise(p0: Float) = Unit
                })
                val result = subscribe(CLIENT_KEY, object : CXRServiceBridge.MsgCallback {
                    override fun onReceive(name: String?, args: Caps?, bytes: ByteArray?) {
                        cxrInbound = true
                        Log.i(TAG, "CXR_INBOUND name=$name")
                        // Reply on the phone's inbound channel. The 3-arg `MsgCallback` hands out no `Reply`, which is
                        // exactly why `glasses-app` acks with a fresh `sendMessage` too.
                        sendProbeReply()
                        logVerdict()
                    }
                })
                Log.i(TAG, "CXR_SUBSCRIBED to $CLIENT_KEY result=$result")
            }
        }
        bridge = created.getOrElse {
            Log.e(TAG, "CXR_BRIDGE_FAILED ${it.javaClass.simpleName}: ${it.message}")
            null
        }
    }

    private fun sendProbeReply() {
        val target = bridge ?: return
        val result = runCatching {
            target.sendMessage(REPLY_KEY, Caps().apply {
                write(PAYLOAD_LABEL)
                write(PROBE_PAYLOAD)
            })
        }.getOrElse {
            Log.w(TAG, "CXR_SEND_FAILED ${it.javaClass.simpleName}: ${it.message}")
            return
        }
        if (result == 0) cxrOutboundAccepted = true
        Log.i(TAG, "CXR_SEND result=$result")
    }

    /**
     * Three separate facts, then the verdict. A reader must be able to tell "the SDK bound but the service was null"
     * from "nothing bound at all", and neither may be reported as success.
     */
    private fun logVerdict() {
        val rxRoundTrip = cxrInbound && cxrOutboundAccepted
        val verdict = evaluate(glassSdkReady, glassServiceReachable, rxRoundTrip)
        Log.i(
            VERDICT_TAG,
            "P3_T0_VERDICT glassSdkReady=$glassSdkReady glassServiceReachable=$glassServiceReachable " +
                "cxrInbound=$cxrInbound cxrOutboundAccepted=$cxrOutboundAccepted " +
                "ok=${verdict.ok} missing=${verdict.missing}",
        )
    }

    // ---------------------------------------------------------------- T1

    private fun maybeStartT1() {
        if (t1Started || !glassSdkReady) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
        t1Started = true
        Thread { runT1() }.start()
    }

    /**
     * T1's two questions, in the only order that can answer both: learn which words the lexicon accepts, register the
     * accepted set, then compete with it for the microphone.
     */
    private fun runT1() {
        val attempts = probeWordAcceptance()
        wordSummary = attempts.joinToString("|") { "${it.word}:${it.outcome}" }
        Log.i(TAG, "T1_WORDS $wordSummary")

        val accepted = attempts.filter { it.outcome == WordOutcome.ACCEPTED }
        if (accepted.isEmpty()) {
            Log.w(TAG, "T1_NO_WORDS_ACCEPTED")
            logT1Verdict()
            return
        }
        val registered = runCatching {
            GlassSdk.setOfflineCmdWords(
                LANGUAGE,
                accepted.map { VoiceAction(it.word, it.pinyin, triggerCallback(it.word)) },
            )
        }.getOrElse {
            Log.e(TAG, "T1_REGISTER_FAILED ${it.javaClass.simpleName}: ${it.message}")
            false
        }
        Log.i(TAG, "T1_REGISTERED count=${accepted.size} accepted=$registered")

        // The semantics of startRecord/stopRecord are unknown from the documentation — this is the probe measuring it,
        // so both outcomes of the call are logged rather than assumed.
        val startOutcome = runCatching { GlassSdk.getGlassOfflineCmdService()?.startRecord() }
            .fold({ "ok" }, { "${it.javaClass.simpleName}: ${it.message}" })
        Log.i(TAG, "T1_START_RECORD $startOutcome")

        val (bytes, peak) = captureMic(T1_SECONDS)
        captureBytes = bytes
        capturePeakRms = peak

        val stopOutcome = runCatching { GlassSdk.getGlassOfflineCmdService()?.stopRecord() }
            .fold({ "ok" }, { "${it.javaClass.simpleName}: ${it.message}" })
        Log.i(TAG, "T1_STOP_RECORD $stopOutcome")

        logT1Verdict()
    }

    /**
     * One word at a time, because the batch call **replaces** the language's set and returns a single boolean: probing
     * with it per word is the only way to learn which individual words the lexicon accepts.
     */
    private fun probeWordAcceptance(): List<WordAttempt> = T1_WORDS.map { (word, pinyin) ->
        val outcome = runCatching {
            if (GlassSdk.setOfflineCmdWords(LANGUAGE, listOf(VoiceAction(word, pinyin, noopCallback())))) {
                WordOutcome.ACCEPTED
            } else {
                WordOutcome.REJECTED_BY_RETURN
            }
        }.getOrElse {
            Log.w(TAG, "T1_WORD_THREW word=$word ${it.javaClass.simpleName}: ${it.message}")
            WordOutcome.THREW
        }
        Log.i(TAG, "T1_WORD word=$word pinyin=$pinyin outcome=$outcome")
        WordAttempt(word, pinyin, outcome)
    }

    private fun noopCallback(): IVoiceCallback = object : IVoiceCallback.Stub() {
        override fun onVoiceTriggered() = Unit
    }

    private fun triggerCallback(word: String): IVoiceCallback = object : IVoiceCallback.Stub() {
        override fun onVoiceTriggered() {
            offlineTriggers += 1
            Log.i(TAG, "T1_OFFLINE_TRIGGER word=$word count=$offlineTriggers")
            logT1Verdict()
        }
    }

    /**
     * Reads the microphone on the same path and configuration P2 measured (`MediaRecorder.AudioSource.MIC`, 16 kHz
     * mono PCM16), so "the lexicon took the microphone" is measured against the production geometry.
     *
     * Returns total bytes and the **peak 20 ms-frame RMS**. Peak rather than average on purpose: a person speaking into
     * a contended microphone produces intermittent speech, and an average over a mostly silent minute would call a
     * working microphone dead. Frame RMS rather than raw peak sample, because a single click is not speech.
     */
    private fun captureMic(seconds: Int): Pair<Long, Int> {
        val minimum = AudioRecord.getMinBufferSize(
            SAMPLE_RATE_HZ, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
        )
        val recorder = runCatching {
            AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.MIC)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(SAMPLE_RATE_HZ)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build(),
                )
                .setBufferSizeInBytes(maxOf(minimum, FRAME_BYTES * BUFFER_FRAMES))
                .build()
        }.getOrElse {
            Log.e(TAG, "T1_CAPTURE_BUILD_FAILED ${it.javaClass.simpleName}: ${it.message}")
            return 0L to 0
        }
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "T1_CAPTURE_UNINITIALIZED")
            runCatching { recorder.release() }
            return 0L to 0
        }

        val samples = ShortArray(FRAME_BYTES / 2)
        var bytes = 0L
        var peakRms = 0
        var lastLogMillis = 0L
        val deadline = System.currentTimeMillis() + seconds * 1_000L
        runCatching { recorder.startRecording() }.onFailure {
            Log.e(TAG, "T1_CAPTURE_START_FAILED ${it.javaClass.simpleName}: ${it.message}")
            runCatching { recorder.release() }
            return 0L to 0
        }
        Log.i(TAG, "T1_CAPTURE_START seconds=$seconds")
        while (System.currentTimeMillis() < deadline) {
            val read = runCatching { recorder.read(samples, 0, samples.size, AudioRecord.READ_BLOCKING) }
                .getOrDefault(-1)
            if (read < 0) {
                Log.e(TAG, "T1_CAPTURE_READ_ERROR read=$read")
                break
            }
            if (read == 0) continue
            bytes += read * 2L
            var sumSquares = 0.0
            for (index in 0 until read) {
                val value = samples[index].toDouble()
                sumSquares += value * value
            }
            val frameRms = sqrt(sumSquares / read).toInt()
            if (frameRms > peakRms) peakRms = frameRms

            val now = System.currentTimeMillis()
            if (now - lastLogMillis >= T1_PROGRESS_MILLIS) {
                lastLogMillis = now
                Log.i(TAG, "T1_CAPTURE_PROGRESS bytes=$bytes peakRms=$peakRms triggers=$offlineTriggers")
            }
        }
        runCatching { recorder.stop() }
        runCatching { recorder.release() }
        return bytes to peakRms
    }

    private fun logT1Verdict() {
        val verdict = contention(offlineTriggers, captureBytes, capturePeakRms)
        Log.i(
            T1_VERDICT_TAG,
            "P3_T1_VERDICT offlineTriggers=$offlineTriggers captureBytes=$captureBytes " +
                "capturePeakRms=$capturePeakRms minSpeechRms=$MIN_SPEECH_RMS ok=${verdict.ok} " +
                "reason=${verdict.reason.ifEmpty { "none" }} words=$wordSummary",
        )
    }

    private companion object {
        const val TAG = "EdithP3T0"

        /** Separate tags so the verdict lines can be extracted with `logcat -s` without the noise. */
        const val VERDICT_TAG = "P3_T0_VERDICT"
        const val T1_VERDICT_TAG = "P3_T1_VERDICT"

        /** Phone -> glasses subscribe channel; matches the phone's `sendCustomCmd` first argument. */
        const val CLIENT_KEY = "rk_custom_client"

        /** Glasses -> phone reply channel; matches the phone's inbound command routing. */
        const val REPLY_KEY = "rk_custom_key"

        /** The label the official sample writes before the payload. */
        const val PAYLOAD_LABEL = "message"

        const val PROBE_PAYLOAD = """{"probe":"t0"}"""

        /** `registerClient`'s id must match the phone side's client id. */
        const val CLIENT_ID = "GlassSample"

        const val TICK_MILLIS = 2_000L
        const val T1_PROGRESS_MILLIS = 2_000L
        const val MIC_REQUEST = 4_201
        const val LANGUAGE = "ZH_CN"

        /** Long enough for several readings per word and for the microphone to be contended for a while. */
        const val T1_SECONDS = 60

        const val SAMPLE_RATE_HZ = 16_000

        /** 20 ms of 16 kHz mono PCM16 — the block size the device actually delivered in P2 (640 bytes). */
        const val FRAME_BYTES = 640
        const val BUFFER_FRAMES = 8

        /**
         * The four categories T1 must cover: plain Chinese, plain English, mixed, and one carrying a digit. The last
         * one is where a pinyin-backed lexicon is most likely to refuse.
         */
        val T1_WORDS = listOf(
            "伊迪丝" to "yi di si",
            "open chat" to "open chat",
            "edith 你好" to "edith ni hao",
            "第2项" to "di er xiang",
        )
    }
}
