package com.sheen.rokid.transport

import com.rokid.cxr.Caps
import com.sheen.rokid.audio.AudioUplinkCodec

/**
 * What one inbound custom-command payload carries.
 *
 * The shape is the **official phone sample's**, not an invention: the vendor sends
 * `sendCustomCmd("rk_custom_client", Caps { write("rk_custom_key"); write(payload) })` - the reply channel key as the
 * first string and the payload as the second - and reads a reply with
 * `Caps.fromBytes(payload)`. Its glasses sample replies the same way, with a label first and the payload second.
 *
 * That is why a "does the payload start with `{`" test is wrong: the bytes on the wire are a **serialized `Caps`**,
 * and the JSON is the second string inside it.
 */
object CxrInboundPayload {

    /** True when the payload is one of our own audio-uplink frames. */
    fun isAudioFrame(datagram: ByteArray): Boolean = datagram.size >= 4 &&
        datagram[0] == AudioUplinkCodec.MAGIC_0 && datagram[1] == AudioUplinkCodec.MAGIC_1 &&
        datagram[2] == AudioUplinkCodec.MAGIC_2 && datagram[3] == AudioUplinkCodec.MAGIC_3

    /**
     * The JSON carried by a reply, or null when the payload carries none.
     *
     * Reads the **second** string of the serialized `Caps` (demo layout: label/key first, payload second). A
     * single-string payload is accepted from its only slot so a sender that omits the label still works, and a raw JSON
     * byte array is accepted as well - both are tolerated shapes of the same message, not alternative protocols.
     */
    fun commandJsonOrNull(datagram: ByteArray): String? {
        val caps = runCatching { Caps.fromBytes(datagram) }.getOrNull()
        if (caps != null && caps.size() > 0) {
            val strings = (0 until caps.size()).mapNotNull { index ->
                val value = caps.at(index)
                if (value.type() == Caps.Value.TYPE_STRING) value.string else null
            }
            val payload = if (strings.size >= 2) strings[1] else strings.firstOrNull()
            if (payload != null && looksLikeJson(payload)) return payload.trim()
        }
        val raw = runCatching { datagram.toString(Charsets.UTF_8) }.getOrNull() ?: return null
        return raw.takeIf { looksLikeJson(it) }?.trim()
    }

    private fun looksLikeJson(text: String): Boolean = text.trimStart().startsWith("{")
}
