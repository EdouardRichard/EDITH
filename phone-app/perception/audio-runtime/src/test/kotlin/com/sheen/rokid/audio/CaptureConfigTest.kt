package com.sheen.rokid.audio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The capture geometry is stated once here and asserted, because an error in it would present as "no audio" or
 * "wrong-sounding audio" on hardware rather than as an exception - the two most expensive outcomes to diagnose.
 */
class CaptureConfigTest {
    @Test fun theDeviceMaskIsTheDocumentedEightChannelValue() {
        // 0x6000FC is the vendor-documented mask for this microphone array; a wrong value is not rejected, it simply
        // yields a different channel layout.
        assertEquals(0x6000FC, CaptureConfig.DEVICE_EIGHT_CHANNEL_MASK)
        assertEquals(0x6000FC, CaptureConfig().channelMask)
    }

    @Test fun oneReadCoversTheDocumentedGeometry() {
        // 40 ms at 16 kHz is 640 samples per channel, x8 channels x2 bytes = 10240 bytes.
        assertEquals(10_240, CaptureConfig().readBufferBytes())
    }

    @Test fun aShorterReadWindowScalesWithTheGeometry() {
        assertEquals(5_120, CaptureConfig(readMillis = 20).readBufferBytes())
        assertEquals(2_560, CaptureConfig(sourceChannelCount = 2).readBufferBytes())
    }

    @Test fun impossibleGeometryIsRefusedAtConstruction() {
        assertTrue(runCatching { CaptureConfig(sampleRateHz = 0) }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { CaptureConfig(bitsPerSample = 12) }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { CaptureConfig(sourceChannelCount = 0) }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { CaptureConfig(readMillis = 0) }.exceptionOrNull() is IllegalArgumentException)
    }

    @Test fun theStartOutcomesDistinguishARefusedConfigFromSilentAudio() {
        // These are separate outcomes because they need different responses: the first is a capability or coding
        // problem, the second is the vendor's documented "audio no data" case.
        assertEquals(2, CaptureStart.entries.size)
        assertTrue(CaptureStart.entries.contains(CaptureStart.ConfigRefused))
        assertTrue(CaptureStart.entries.contains(CaptureStart.Started))
    }
}
