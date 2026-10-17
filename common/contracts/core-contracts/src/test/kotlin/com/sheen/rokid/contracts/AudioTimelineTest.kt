package com.sheen.rokid.contracts

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AudioTimelineTest {
    @Test fun `sample ranges reject invalid boundaries`() {
        assertFailsWith<IllegalArgumentException> { SampleRange("c", 100, 99) }
        assertFailsWith<IllegalArgumentException> { SampleRange("c", -1, 0) }
        assertFailsWith<IllegalArgumentException> { SampleRange(" ", 0, 1) }
        assertEquals(100L, SampleRange("c", 100, 100).endExclusive)
    }

    @Test fun `verified PCM requires evidence and supported representation`() {
        assertFailsWith<IllegalArgumentException> {
            VerifiedPcm(AudioFormat.Pcm(16000, 1, 16), PcmEncoding.SIGNED_LE, "")
        }
        listOf(AudioFormat.Pcm(16000, 1, 12), AudioFormat.Pcm(16000, 9, 16),
            AudioFormat.Pcm(1, 1, 16), AudioFormat.Pcm(384000, 1, 16)).forEach { format ->
            assertFailsWith<IllegalArgumentException> { VerifiedPcm(format, PcmEncoding.SIGNED_LE, "fixture") }
        }
        val verified = VerifiedPcm(AudioFormat.Pcm(16000, 1, 16), PcmEncoding.SIGNED_BE, "synthetic-fixture")
        assertEquals(PcmEncoding.SIGNED_BE, verified.encoding)
    }

    @Test fun `input epoch rejects negative serial`() {
        assertFailsWith<IllegalArgumentException> { InputEpoch(SessionId("s"), Generation(0), -1) }
    }
}
