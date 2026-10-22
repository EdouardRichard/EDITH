package com.sheen.rokid.transport

import com.sheen.rokid.audio.AudioUplinkCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Telling apart the two things the shared reply channel carries.
 *
 * Minimal on purpose: the **wire shape is the official sample's**, not something this project defines, so there is
 * nothing here to derive. What is pinned is only what our own routing needs - our audio frames are magic-prefixed,
 * and a serialized `Caps`/raw JSON reply carries the payload JSON.
 *
 * `Caps.fromBytes` is a native call, so a JVM test exercises the raw-JSON branch; the `Caps` branch is the sample's own
 * form and is taken as given (decision D41: official-demo fidelity is the correctness criterion for these interactions).
 */
class CxrInboundPayloadTest {

    @Test
    fun ourOwnAudioFramesAreRecognisedByTheirMagic() {
        val frame = byteArrayOf(AudioUplinkCodec.MAGIC_0, AudioUplinkCodec.MAGIC_1, AudioUplinkCodec.MAGIC_2,
            AudioUplinkCodec.MAGIC_3, 0, 1, 2, 3)

        assertTrue(CxrInboundPayload.isAudioFrame(frame))
        assertNull("an audio frame carries no command", CxrInboundPayload.commandJsonOrNull(frame))
        assertFalse(CxrInboundPayload.isAudioFrame(frame.copyOf(3)))
        assertFalse(CxrInboundPayload.isAudioFrame(ByteArray(0)))
    }

    @Test
    fun aJsonPayloadIsAcceptedWhicheverShapeItArrivedIn() {
        assertEquals("{\"command\":\"PONG\"}", CxrInboundPayload.commandJsonOrNull("{\"command\":\"PONG\"}".toByteArray()))
        assertEquals("{\"command\":\"PONG\"}", CxrInboundPayload.commandJsonOrNull("  {\"command\":\"PONG\"}".toByteArray()))
    }

    @Test
    fun arbitraryBytesAreRejectedRatherThanDecodedIntoReplacementCharacters() {
        assertNull(CxrInboundPayload.commandJsonOrNull(byteArrayOf(1, 2, 3)))
        assertNull(CxrInboundPayload.commandJsonOrNull(ByteArray(0)))
    }
}
