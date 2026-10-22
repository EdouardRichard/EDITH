package com.sheen.rokid.transport

import android.util.Log
import com.rokid.cxr.link.callbacks.ICustomCmdCbk
import com.rokid.cxr.session.CxrSession
import com.rokid.sprite.aiapp.externalapp.example.ExternalAppClient

/**
 * Turns the glasses client's **inbound** custom commands into transport callbacks, installed on the client itself.
 *
 * The same lesson as the audio callback, measured a second time on 2026-10-08. The vendor's own phone reference
 * registers with `CXRLink.setCXRCustomCmdCbk` (a `CXRLink`, which `javap` shows is an empty subclass of
 * `ExternalAppClient`), while we registered `CxrSession.addCustomCmdCallback`. On device the phone's `HELLO` and
 * `PING` reached the glasses and the glasses answered the `PING` (`sendMessage` returned the accepted code), yet
 * **nothing ever arrived back at the phone**: the session-level registration delivered nothing, exactly as the
 * session-level audio registration had delivered zero bytes before it.
 *
 * The payload the phone receives is a **serialized `Caps`** (`Caps.fromBytes(payload)`), which is why the routing
 * below inspects raw bytes and why the reply channel key is matched rather than assumed.
 */
class CxrCustomCmdAdapter : ICustomCmdCbk {
    private var sink: ((String?, ByteArray?) -> Unit)? = null
    private var released = false

    /** Binds the transport sink. */
    fun attach(sink: (String?, ByteArray?) -> Unit) {
        this.sink = sink
    }

    /** Detaches idempotently; release is reached from teardown as well as from a failed install. */
    fun detach() {
        if (released) return
        released = true
        sink = null
    }

    override fun onCustomCmdResult(key: String?, payload: ByteArray?) {
        val target = sink ?: return
        // This runs on the SDK's callback thread: a routing failure must never escape and take the session down.
        runCatching { target(key, payload) }
    }
}

/**
 * Installs the inbound custom-command callback for one connection. Injected into the backend so the wiring is
 * testable while the single SDK call stays behind a thin implementation.
 */
interface CustomCmdInstaller {
    /** Installs [sink] as the inbound custom-command consumer. Returns false when installation was refused. */
    fun install(sink: (String?, ByteArray?) -> Unit): Boolean

    /** Silences this end. Safe to call more than once. */
    fun release()
}

/** Used when a live session exposes no client to install on; refusing is correct, silently succeeding is not. */
internal object UnavailableCustomCmdInstaller : CustomCmdInstaller {
    override fun install(sink: (String?, ByteArray?) -> Unit) = false
    override fun release() = Unit
}

/**
 * Installs a [CxrCustomCmdAdapter] on the glasses client itself - the layer the vendor's own reference uses.
 *
 * Kept deliberately thin (one callback object, one SDK call) so the behaviour worth testing lives in the adapter and
 * only this single SDK call needs device confirmation.
 */
class ExternalAppClientCustomCmdSubscription(private val client: ExternalAppClient) : CustomCmdInstaller {
    private val adapter = CxrCustomCmdAdapter()

    override fun install(sink: (String?, ByteArray?) -> Unit): Boolean = try {
        adapter.attach(sink)
        client.setCXRCustomCmdCbk(adapter)
        Log.i(TAG, "installed client custom-command callback: ${client.javaClass.simpleName}")
        true
    } catch (thrown: Throwable) {
        // A failure to install must be reported, never swallowed: the phone would otherwise keep sending commands whose
        // answers can never arrive, which is indistinguishable from a broken glasses app.
        Log.e(TAG, "setCXRCustomCmdCbk failed: ${thrown.javaClass.simpleName}: ${thrown.message}")
        adapter.detach()
        false
    }

    /**
     * Silences this end without touching the SDK registration: `setCXRCustomCmdCbk` accepts no null, so the adapter is
     * detached instead - the same shape the audio subscription uses.
     */
    override fun release() {
        adapter.detach()
    }

    private companion object {
        const val TAG = "EdithCxrCommand"
    }
}

/** Resolves where the inbound custom-command callback must be installed for a live session. */
internal fun CxrSession.customCmdInstallerOrNull(): CustomCmdInstaller? {
    val client = glassesClientOrNull() ?: return null
    return ExternalAppClientCustomCmdSubscription(client)
}
