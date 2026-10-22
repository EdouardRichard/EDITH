package com.sheen.rokid.transport

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The CXR custom-command callback is shared by JSON commands and, for the T17 audio uplink, by raw PCM frames.
 *
 * The distinction matters because the existing handler decodes every payload as UTF-8 to run reply correlation.
 * Applied to audio that decode is lossy and destructive: arbitrary bytes are not valid UTF-8, so a PCM frame would
 * arrive at the other end as replacement characters. These tests pin the routing, and in particular that **no audio
 * byte is ever interpreted as text**.
 */
class BinaryChannelRoutingTest {
    @Test fun binaryPayloadOnTheRegisteredChannelArrivesByteForByte() {
        val rig = CxrBackendRig()
        rig.authorize()
        val received = mutableListOf<ByteArray>()
        rig.backend.setBinaryChannelListener("rk_custom_key") { received += it }

        // Bytes that are NOT valid UTF-8: 0xFF 0xFE would become replacement characters under a text decode.
        val audio = byteArrayOf(0x00, 0xFF.toByte(), 0xFE.toByte(), 0x80.toByte(), 0x7F, 0x00)
        rig.deliver("rk_custom_key", audio)

        assertEquals("a registered binary channel must deliver its payload", 1, received.size)
        assertArrayEquals("audio bytes must survive untouched", audio, received.single())
    }

    @Test fun aRegisteredBinaryChannelIsNotAlsoDecodedAsText() {
        val rig = CxrBackendRig()
        rig.authorize()
        var textDelivered = false
        val received = mutableListOf<ByteArray>()
        rig.backend.setBinaryChannelListener("rk_custom_key") { received += it }
        // A pending JSON command on the same channel must not be completed by an audio frame: its correlation would
        // match on the mojibake and resolve a command with audio.
        val correlated = mutableListOf<String?>()
        rig.backend.send(RokidCommandRoute.CustomClient, "{\"id\":\"x\"}",
            RokidReplyCorrelation { text -> textDelivered = true; true }) { correlated += it }

        rig.deliver("rk_custom_key", byteArrayOf(0x01, 0x02, 0x03))

        assertEquals("the frame still reaches the binary consumer", 1, received.size)
        assertTrue("an audio frame must not be offered to a text correlation", !textDelivered)
        assertTrue("and must not complete a pending command", correlated.isEmpty())
    }

    @Test fun unregisteredChannelsKeepTheirExistingTextBehaviour() {
        val rig = CxrBackendRig()
        rig.authorize()
        val binary = mutableListOf<ByteArray>()
        rig.backend.setBinaryChannelListener("rk_audio") { binary += it }
        val correlated = mutableListOf<String?>()
        rig.backend.send(RokidCommandRoute.CustomClient, "{\"id\":\"x\"}",
            RokidReplyCorrelation { text -> text == "{\"id\":\"x\"}" }) { correlated += it }

        rig.deliver("rk_custom_key", "{\"id\":\"x\"}".toByteArray())

        assertTrue("a different channel must not receive the payload", binary.isEmpty())
        assertEquals("text routing is unchanged", listOf<String?>("{\"id\":\"x\"}"), correlated)
    }

    @Test fun allRegisteredBinaryChannelsReceiveTheirOwnTraffic() {
        val rig = CxrBackendRig()
        rig.authorize()
        val audio = mutableListOf<ByteArray>()
        val video = mutableListOf<ByteArray>()
        rig.backend.setBinaryChannelListener("rk_audio") { audio += it }
        rig.backend.setBinaryChannelListener("rk_video") { video += it }

        rig.deliver("rk_video", byteArrayOf(9))
        rig.deliver("rk_audio", byteArrayOf(1, 2))

        assertEquals(1, video.size)
        assertEquals(1, audio.size)
        assertArrayEquals(byteArrayOf(1, 2), audio.single())
    }

    @Test fun aNullPayloadIsIgnoredRatherThanCrashingTheAudioPath() {
        val rig = CxrBackendRig()
        rig.authorize()
        val received = mutableListOf<ByteArray>()
        rig.backend.setBinaryChannelListener("rk_custom_key") { received += it }
        rig.deliver("rk_custom_key", null)
        assertTrue(received.isEmpty())
    }

    @Test fun aListenerThrowingDoesNotEscapeIntoTheVendorCallback() {
        val rig = CxrBackendRig()
        rig.authorize()
        rig.backend.setBinaryChannelListener("rk_custom_key") { error("synthetic audio consumer failure") }
        // The SDK calls this from its own thread; an exception escaping would take down the transport.
        val thrown = runCatching {
            rig.deliver("rk_custom_key", byteArrayOf(1, 2, 3))
        }.exceptionOrNull()
        assertNull("a consumer failure must not propagate into the vendor callback", thrown)
    }

    @Test fun removingTheListenerStopsDelivery() {
        val rig = CxrBackendRig()
        rig.authorize()
        val received = mutableListOf<ByteArray>()
        rig.backend.setBinaryChannelListener("rk_custom_key") { received += it }
        rig.deliver("rk_custom_key", byteArrayOf(1))
        rig.backend.removeBinaryChannelListener("rk_custom_key")
        rig.deliver("rk_custom_key", byteArrayOf(2))
        assertEquals("a removed listener must not be called again", 1, received.size)
    }

    @Test fun aBlankChannelKeyIsRefusedAtRegistration() {
        val rig = CxrBackendRig()
        val thrown = runCatching { rig.backend.setBinaryChannelListener("  ") { } }.exceptionOrNull()
        assertTrue("a blank channel key is a programming error", thrown is IllegalArgumentException)
    }
}
