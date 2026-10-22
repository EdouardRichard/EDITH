package com.sheen.rokid.transport

import android.util.Log
import com.rokid.cxr.link.callbacks.IAudioStreamCbk
import com.rokid.cxr.session.CxrSession
import com.rokid.sprite.aiapp.externalapp.example.ExternalAppClient

/**
 * Turns the glasses client's audio callbacks into host-facing events, and is the object actually installed on the
 * client.
 *
 * It exists because a device measurement showed that installing the callback on the session wrapper and installing it
 * on the client are **not** equivalent. On the same glasses, at the same instant, the vendor's own reference
 * implementation - which uses `ExternalAppClient.setCXRAudioCbk` - received 652,160 bytes of microphone audio, while
 * our transport, which used `CxrSession.addAudioCallback`, received none, even though the glasses was transmitting and
 * our session reported `startAudioStream: OK`.
 *
 * `onAudioReceived` deliberately extracts `[offset, offset + length)` rather than passing `data` on: measured on
 * device, the SDK hands over a **reused 192000-byte buffer** with only `length` bytes valid
 * (`data.size=192000 offset=0 length=640`). Forwarding the whole array would deliver roughly 300x the real audio,
 * almost all of it stale.
 *
 * The vendor's `ExternalAppClient` loads a native library in its static initialiser, so it cannot be constructed in a
 * JVM unit test; this class therefore takes the host events through [attach] and can be driven directly by tests,
 * leaving only the single `setCXRAudioCbk` call to device confirmation.
 */
class CxrAudioAdapter : IAudioStreamCbk {
    private var events: Events? = null
    private var released = false

    private var calls = 0L
    private var bytes = 0L
    private var duplicateChunks = 0L
    private var previousChunk: ByteArray? = null

    /** What the host wants to hear about. Same shape as the transport's audio sink. */
    interface Events {
        fun received(data: ByteArray)
        fun streaming(active: Boolean)
        fun failed()
    }

    /** Binds the host sink. Called once when an epoch starts. */
    fun attach(events: Events) {
        this.events = events
    }

    /**
     * Detaches idempotently. Release is reached from both `stop()` and teardown, and a second release would unregister
     * a callback that a later epoch may already have installed.
     */
    fun detach() {
        if (released) return
        released = true
        events = null
    }

    override fun onAudioReceived(data: ByteArray?, offset: Int, length: Int) {
        val sink = events ?: return
        val buffer = data ?: return
        if (length <= 0) return
        if (offset < 0 || offset >= buffer.size) return
        // Clamped rather than thrown: an out-of-range value here would escape into the vendor's callback thread and
        // take the session down. The SDK's own sample guards the same way because bad ranges have been observed.
        val end = minOf(offset + length, buffer.size)
        if (end <= offset) return
        val copy = buffer.copyOfRange(offset, end)
        count(copy)
        sink.received(copy)
    }

    /**
     * Diagnostic counters for the F-01 wake investigation: how many callbacks arrive, how many bytes they carry,
     * and whether consecutive callbacks repeat the same chunk. Counters only - never the audio, never a digest of
     * the content. The summary lands every 250 callbacks (about 5 s at 20 ms) so a device run can be read at a
     * glance: a 2x byte rate with a matching duplicate count means the same chunk is being delivered twice.
     */
    private fun count(chunk: ByteArray) {
        val summary = synchronized(this) {
            calls++
            bytes += chunk.size
            if (previousChunk?.contentEquals(chunk) == true) duplicateChunks++
            previousChunk = chunk
            if (calls % 250L != 0L) return
            "P3_CXR_AUDIO callbacks=$calls bytes=$bytes dupConsecutive=$duplicateChunks lastLength=${chunk.size}"
        }
        Log.i("EdithCxrAudio", summary)
    }

    override fun onAudioError(errorCode: Int, errorInfo: String?) {
        // Forwarded so the host can interrupt the epoch: a dead stream that still looks alive would hang the capture.
        events?.failed()
    }

    override fun onAudioStreamStateChanged(isStreaming: Boolean) {
        events?.streaming(isStreaming)
    }
}

/**
 * Installs the audio callback for one connection. Injected into the backend so the wiring is testable while the single
 * SDK call stays behind a thin implementation.
 */
interface AudioInstaller {
    /** Installs [events] as the audio sink. Returns false when installation was refused. */
    fun install(events: CxrAudioAdapter.Events): Boolean

    /** Stops the stream and silences this end. Safe to call more than once. */
    fun release()
}

/** Used when a live session exposes no client to install on; refusing is correct, silently succeeding is not. */
internal object UnavailableAudioInstaller : AudioInstaller {
    override fun install(events: CxrAudioAdapter.Events) = false
    override fun release() = Unit
}

/**
 * Installs a [CxrAudioAdapter] on the glasses client itself - the path the vendor's own working implementation uses.
 *
 * Kept deliberately thin (one callback object, one SDK call) so the behaviour worth testing lives in
 * [CxrAudioAdapter] and only this single SDK call needs device confirmation.
 */
class ExternalAppClientAudioSubscription(private val client: ExternalAppClient) : AudioInstaller {
    private val adapter = CxrAudioAdapter()

    /** Installs the callback and binds the host sink. Returns false when installation was refused. */
    override fun install(events: CxrAudioAdapter.Events): Boolean = try {
        adapter.attach(events)
        client.setCXRAudioCbk(adapter)
        // Logged because on device this is the boundary between "we asked" and "the AI App answers": without it a
        // zero-byte result cannot be attributed to installation versus delivery.
        Log.i(TAG, "installed client audio callback: ${client.javaClass.simpleName}")
        true
    } catch (thrown: Throwable) {
        // A failure to install must be reported, never swallowed: continuing silently would leave an epoch waiting
        // for audio that can never arrive.
        Log.e(TAG, "setCXRAudioCbk failed: ${thrown.javaClass.simpleName}: ${thrown.message}")
        adapter.detach()
        false
    }

    /**
     * Stops the stream and silences this end.
     *
     * The callback is deliberately **not** cleared, following the vendor's own release path (its audio page calls
     * `stopAudioStream()` and leaves the callback registered; `setCXRAudioCbk` also does not accept null). Silencing
     * is done by detaching the adapter, which stops delivery to the host without touching the SDK's registration.
     */
    override fun release() {
        adapter.detach()
        runCatching { client.stopAudioStream() }
    }

    private companion object {
        const val TAG = "EdithCxrAudio"
    }
}

/**
 * Resolves where audio must be installed for a live session.
 *
 * `CxrSessionImpl` **holds** the `ExternalAppClient` whose `setCXRAudioCbk` registration the AI App actually serves -
 * it does not extend it, and the field is not visible to Kotlin (the AAR is obfuscated and exposes no getter, only
 * `public final ExternalAppClient b`). So the relation is taken reflectively, and any failure returns null.
 *
 * Returning null rather than throwing matters: callers turn it into a visible "audio unavailable", not a crash on a
 * device that cannot be retested cheaply.
 */
internal fun CxrSession.audioInstallerOrNull(): AudioInstaller? {
    val client = glassesClientOrNull() ?: return null
    return ExternalAppClientAudioSubscription(client)
}

/**
 * The `ExternalAppClient` a live session holds, or null when it cannot be reached.
 *
 * `CxrSessionImpl` **holds** the client rather than extending it, and the AAR is obfuscated with no getter (only
 * `public final ExternalAppClient b`). Every client-level callback we install - audio and custom commands - goes
 * through this one resolution, so the obfuscated field name is pinned in exactly one place and a future rename
 * degrades both to a visible "unavailable" instead of silently installing nothing.
 */
internal fun CxrSession.glassesClientOrNull(): ExternalAppClient? {
    val impl = this as? com.rokid.cxr.session.CxrSessionImpl ?: run {
        // Surfaced rather than swallowed: this is the difference between "the session is a different implementation"
        // and "the client could not be found".
        Log.w(TAG, "session is ${javaClass.simpleName}, not CxrSessionImpl; no client callback can be installed")
        return null
    }
    val client = runCatching { impl.javaClass.getField(CLIENT_FIELD).get(impl) as? ExternalAppClient }
        .onFailure { Log.w(TAG, "client field '$CLIENT_FIELD' unreadable: ${it.javaClass.simpleName}") }
        .getOrNull()
    if (client == null) {
        Log.w(TAG, "no glasses client reachable on this session")
        return null
    }
    // Logged because it proves which object callbacks are installed on - the exact confusion that produced zero bytes
    // when the session wrapper was used instead.
    Log.i(TAG, "resolved glasses client: ${client.javaClass.name}")
    return client
}

/**
 * The client holder field in `com.rokid.cxr.session.CxrSessionImpl`.
 *
 * Pinned deliberately, and paired with the failure path above: if a future SDK renames it, audio degrades to
 * "unavailable" (visible in the log) instead of silently installing nothing.
 */
private const val CLIENT_FIELD = "b"
private const val TAG = "EdithCxrAudio"
