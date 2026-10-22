package com.sheen.rokid.phone.bridge

import android.app.Activity
import android.content.Intent
import com.sheen.rokid.audio.PcmIngress
import com.sheen.rokid.host.HostRuntime
import com.sheen.rokid.plugin.MonotonicClock
import com.sheen.rokid.transport.CxrAuthorizationOutcome
import com.sheen.rokid.transport.CxrInboundPayload
import com.sheen.rokid.transport.RokidConnectionConfig
import com.sheen.rokid.transport.RokidSessionTransport

/** Explicit connection seam; engine/display capability selection remains with App assembly. */
class RokidConnection(activity: Activity, config: RokidConnectionConfig, host: HostRuntime,
    onSceneReady: () -> Unit, clock: MonotonicClock, onRawPcm: (ByteArray) -> Unit = {},
    /**
     * Reports what the connection's own [PcmIngress] decided about each offered datagram (Task 14a item 5).
     *
     * Additive and defaulted: every existing call site keeps compiling and behaving identically. Without it the
     * receiver's `ACCEPTED`/`OVERFLOW`/`UNKNOWN_FORMAT`/`STALE_EPOCH` result died inside `transport-rokid`, so
     * `INGRESS_OVERFLOW` and `UNKNOWN_FORMAT` could never be reported as faults.
     */
    onOffer: (com.sheen.rokid.audio.PcmOffer) -> Unit = {},
) : CxrConnection {
    val ingress = PcmIngress(clock)
    val input = RokidSessionTransport.create(activity.applicationContext, config, ingress,
        onSceneReady, host::onAudioReceived, host::interrupt, onRawPcm, onOffer)

    /**
     * The T17 audio uplink receiver, attached to the binary channel rather than the JSON reply path.
     *
     * It is owned here so it is created with the connection's own ingress and torn down with the connection: a
     * receiver outliving its session would keep accepting frames for an epoch that no longer exists.
     */
    val uplinkReceiver = com.sheen.rokid.audio.AudioUplinkReceiver(ingress, clock)

    private var inbound: ((String) -> Unit)? = null

    init {
        input.attachBinaryChannel(UPLINK_CHANNEL_KEY) { datagram ->
            // One channel carries two kinds of traffic, and they are told apart by **shape**, not by hope:
            // our audio frames are magic-prefixed, while a glasses reply is a serialized `Caps` whose first string is
            // the command JSON (the vendor's own phone reference parses exactly that way). A "does it start with {" test
            // would drop every real reply - which is what happened on device before `CxrInboundPayload` existed.
            if (CxrInboundPayload.isAudioFrame(datagram)) uplinkReceiver.feed(datagram)
            else inboundIfCommand(datagram)
        }
    }

    private fun inboundIfCommand(datagram: ByteArray) {
        val listener = inbound ?: return
        val json = CxrInboundPayload.commandJsonOrNull(datagram) ?: return
        runCatching { listener(json) }
    }

    fun connect(activity: Activity) = input.connect(activity)
    override fun connect(activity: Any) = connect(activity.asCxrActivity())
    override val ready: Boolean get() = input.state.ready
    override fun onAuthorizationResult(resultCode: Int, data: Intent?): CxrAuthorizationOutcome? =
        input.onAuthorizationResult(resultCode, data)
    override fun sendCommand(json: String): Boolean = input.sendCommand(json)
    override fun onInboundCommand(listener: (String) -> Unit) { inbound = listener }
    override fun close() {
        // Detach before closing so no frame can arrive after teardown, and stop the receiver so the ingress is
        // closed rather than left accepting for a dead epoch.
        inbound = null
        input.detachBinaryChannel(UPLINK_CHANNEL_KEY)
        runCatching { uplinkReceiver.stop() }
        input.close()
    }

    companion object {
        /** The reply channel the glasses uplink sends on; matches `CxrBridgeTransport.DEFAULT_CHANNEL_KEY`. */
        const val UPLINK_CHANNEL_KEY = "rk_custom_key"
    }
}
