package com.sheen.rokid.glasses.bridge
import com.sheen.rokid.glasses.display.HintPresentation

import android.util.Log
import com.rokid.cxr.CXRServiceBridge
import com.rokid.cxr.Caps

/**
 * The one CXR-S bridge endpoint on the glasses.
 *
 * Every symbol here was read out of the pinned `cxr-service-bridge` artifact with `javap`, not from memory:
 * `subscribe(String, MsgReplyCallback)`, `MsgReplyCallback.onReceive(String, Caps, byte[], Reply)`,
 * `Caps.size()`, `Caps.at(int): Caps.Value`, `Caps.Value.type()`, `Caps.Value.getString()`, `Caps.write(String)` and
 * `sendMessage(String, Caps): int`.
 *
 * Two deliberate choices:
 *
 * - **No `unsubscribe`.** That artifact exposes none, so the single subscription is created once and lives in
 *   application scope. Inventing a teardown call would be code no device has ever run; instead the Activity's listener
 *   is cleared on destroy, so the endpoint never keeps an Activity alive and no command reaches a dead screen.
 * - **Replies go out as a fresh `sendMessage`, not through the callback's `Reply` handle.** An acknowledgement is
 *   produced *after* the render commits, i.e. later than `onReceive` returns, and the SDK promises nothing about a
 *   `Reply` staying valid beyond that call. A reply-channel message is the same thing the phone side already reads.
 */
class RokidBridgeEndpoint private constructor() {
    @Volatile private var listener: ((String) -> Unit)? = null

    /**
     * Receives the CXR-S bridge's own `onDisconnected` (0.24 S5 Task 20).
     *
     * The status listener is owned by the one bridge instance above, so the panel service cannot observe the link
     * itself without a second `CXRServiceBridge`; this seam is how the service learns that the phone side of the
     * session is gone. Invoked on the CXR callback thread - the consumer must marshal to the main thread. Null while no
     * service is resident.
     */
    @Volatile private var disconnectionListener: (() -> Unit)? = null

    private val bridge = CXRServiceBridge().apply {
        setStatusListener(object : CXRServiceBridge.StatusListener {
            override fun onConnected(p0: String?, p1: String?, p2: Int) { Log.i(TAG, "CXR connected") }
            override fun onDisconnected() {
                Log.i(TAG, "CXR disconnected")
                disconnectionListener?.let { callback ->
                    runCatching { callback() }.onFailure { failure ->
                        Log.w(TAG, "disconnect listener failed", failure)
                    }
                }
            }
            override fun onConnecting(p0: String?, p1: String?, p2: Int) = Unit
            override fun onARTCStatus(p0: Float, p1: Boolean) = Unit
            override fun onRokidAccountChanged(p0: String?) = Unit
            override fun onAudioNoise(p0: Float) = Unit
        })
        val result = subscribe(CLIENT_KEY, object : CXRServiceBridge.MsgCallback {
            override fun onReceive(name: String?, args: Caps?, bytes: ByteArray?) {
                // Same shape as the official glasses sample: read the payload string out of the `Caps` it was handed.
                // The phone writes the reply channel key first and the payload second (its sample's layout), so the
                // payload is the **second** string - reading the first would hand the codec a channel key.
                val json = runCatching { payloadString(args) }.getOrNull() ?: return
                val decoded = com.sheen.rokid.output.HintWireCodec.decode(json)?.let { it::class.simpleName }
                Log.i(TAG, HintPresentation.inboundReport(json, decoded))
                listener?.invoke(json)
            }
        })
        Log.i(TAG, "subscribed to $CLIENT_KEY result=$result")
    }

    /** The current consumer; null means "no screen is listening" and inbound commands are dropped. */
    fun setListener(value: ((String) -> Unit)?) {
        listener = value
    }

    /**
     * Installs the consumer of the bridge's `onDisconnected`, or clears it (the service does the latter in
     * `onDestroy`). One consumer because this endpoint is a process singleton.
     */
    fun setDisconnectionListener(value: (() -> Unit)?) {
        disconnectionListener = value
    }

    /** Sends one payload to the phone on the reply channel, in the official sample's `Caps` layout. */
    fun send(json: String): Boolean {
        val result = runCatching { bridge.sendMessage(REPLY_KEY, replyCaps(json)) }
            .getOrElse { failure ->
                Log.w(TAG, "sendMessage($REPLY_KEY) failed", failure)
                return false
            }
        if (result != 0) Log.w(TAG, "sendMessage($REPLY_KEY) refused: $result")
        return result == 0
    }

    /**
     * The payload the phone expects.
     *
     * The official glasses sample replies with a label first and the payload second
     * (`Caps { write("message"); write(str) }`), which is the mirror of what its phone sample sends
     * (`Caps { write("rk_custom_key"); write(payload) }`). Both ends therefore read the **second** string.
     */
    private fun replyCaps(json: String): Caps = Caps().apply {
        write(PAYLOAD_LABEL)
        write(json)
    }

    /** The payload string of an inbound `Caps`: second string per the sample layout, or the only one present. */
    private fun payloadString(args: Caps?): String? {
        val caps = args ?: return null
        val strings = (0 until caps.size()).mapNotNull { index ->
            val value = caps.at(index)
            if (value.type() == Caps.Value.TYPE_STRING) value.string else null
        }
        return if (strings.size >= 2) strings[1] else strings.firstOrNull()
    }

    companion object {
        private const val TAG = "EdithGlassesBridge"

        /** Phone -> glasses subscribe channel; matches the phone's `sendCustomCmd` first argument. */
        const val CLIENT_KEY = "rk_custom_client"

        /** Glasses -> phone reply channel; matches the phone's inbound command routing. */
        const val REPLY_KEY = "rk_custom_key"

        /** The label the official sample writes before the payload. */
        const val PAYLOAD_LABEL = "message"

        /** One bridge per process: the SDK reference cannot be unsubscribed, so it must not be recreated per screen. */
        val instance: RokidBridgeEndpoint by lazy { RokidBridgeEndpoint() }
    }
}
