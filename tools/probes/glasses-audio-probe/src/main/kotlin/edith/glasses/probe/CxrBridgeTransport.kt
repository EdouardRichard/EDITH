package edith.glasses.probe

import com.rokid.cxr.CXRServiceBridge
import com.rokid.cxr.Caps

/**
 * The glasses-side transport for the audio uplink, over the CXR-S bridge.
 *
 * `CXRServiceBridge`'s only send primitive is the generic message call, so this adapter exists to answer one question
 * the documentation does not: **can that channel carry real-time audio?** It reports the refusal code from the bridge
 * rather than swallowing it, because "the transport said no" and "the transport was slow" need different responses.
 *
 * The bridge is native and its callbacks arrive on its own threads, so every entry point is defensive: a transport
 * failure must never take down the capture thread.
 */
class CxrBridgeTransport(
    private val channelKey: String = DEFAULT_CHANNEL_KEY,
    private val bridge: CXRServiceBridge = CXRServiceBridge(),
    private val sendVariant: CxrSendVariant = CxrSendVariant.DEFAULT,
) {
    /** Frames the bridge accepted, and the last refusal code it returned. */
    private var accepted = 0L
    private var lastCode = 0

    /** Per-instance connection state; the bridge's callbacks arrive on its own threads. */
    @Volatile private var connectedDevice = false
    @Volatile private var connectingDevice = false

    val acceptedFrames: Long get() = accepted
    val lastRefusalCode: Int get() = lastCode
    val isConnected: Boolean get() = connectedDevice
    val isConnecting: Boolean get() = connectingDevice

    /** True once the bridge reports a connected device, which is the precondition for any send to matter. */
    fun attach(): Boolean = try {
        bridge.setStatusListener(object : CXRServiceBridge.StatusListener {
            override fun onConnected(p0: String?, p1: String?, p2: Int) { connectedDevice = true; connectingDevice = false }
            override fun onDisconnected() { connectedDevice = false }
            override fun onConnecting(p0: String?, p1: String?, p2: Int) { connectingDevice = true }
            override fun onARTCStatus(p0: Float, p1: Boolean) = Unit
            override fun onRokidAccountChanged(p0: String?) = Unit
            override fun onAudioNoise(p0: Float) = Unit
        })
        // The channel is subscribed as well as sent on. Every measured run so far sent successfully (`lastCode=0`) with
        // zero frames arriving on the phone, and an unsubscribed channel is the leading explanation: the bridge may
        // only route traffic for a channel this end has registered. Subscribing is harmless if it is not required.
        subscribeResult = runCatching {
            bridge.subscribe(channelKey, object : CXRServiceBridge.MsgCallback {
                override fun onReceive(name: String?, args: Caps?, data: ByteArray?) = Unit
            })
        }.getOrDefault(-1)
        true
    } catch (error: Throwable) {
        false
    }

    /** The code `subscribe` returned, reported so the run shows whether the channel was accepted. */
    @Volatile var subscribeResult: Int = -1
        private set

    /**
     * Sends one framed payload. Returns false on any refusal, including an exception from the native layer, so the
     * caller's measurement counts it as a refusal instead of crashing the session.
     *
     * [variant] selects the overload. The two reach different native functions, and only [CxrSendVariant.CapsOnly] has
     * been measured (a clean negative: success reported on every frame, nothing delivered). The default is therefore
     * [CxrSendVariant.DEFAULT], which is the challenger.
     */
    fun send(frame: ByteArray, variant: CxrSendVariant = sendVariant): Boolean = try {
        val caps = Caps()
        val code = when (variant) {
            // The frame is serialised into the Caps as a value. Measured negative; kept for reproducing the baseline.
            CxrSendVariant.CapsOnly -> {
                caps.write(frame)
                bridge.sendMessage(channelKey, caps)
            }
            // The frame travels as its own buffer, through the overload the AAR provides for a payload. The Caps still
            // carries the command header, because a message with no header at all may not be routable.
            CxrSendVariant.CapsWithDataArray -> bridge.sendMessage(channelKey, caps, frame)
        }
        lastCode = code
        if (code == 0) { accepted++; true } else false
    } catch (error: Throwable) {
        lastCode = -1
        false
    }

    fun close() {
        runCatching { bridge.disconnectCXRDevice() }
    }

    companion object {
        /**
         * The reply channel the phone already listens on. Reusing it means the phone side needs no new subscription,
         * and the phone's binary routing keeps it out of the JSON reply correlation.
         */
        const val DEFAULT_CHANNEL_KEY = "rk_custom_key"
    }
}
