package com.sheen.rokid.plugin

import com.sheen.rokid.contracts.ContractError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class AudioSegmentsContractTest {
    @Test fun `handle toString does not leak raw audio content or file paths`() {
        val handle = AudioSegmentHandle("ref-1", "turn-1", 500, 16000, "pcm16-16k-mono")
        val s = handle.toString()
        // Metadata like "bytes=16000" is fine; raw base64/audio content and filesystem paths are not.
        assertFalse(s.contains("/"), "toString must not contain file paths: $s")
        assertFalse(s.contains("RIFF"), "toString must not contain WAV header bytes: $s")
        assertEquals("ref-1", handle.ref)
    }

    @Test fun `noop source always returns null`() {
        // When no audio capability is wired, fetch must return null, not throw.
        val services = object : HostServices {
            override val tasks = object : TaskScope {
                override fun launch(block: () -> Unit) = object : CancellationHandle { override fun cancel() = Unit }
                override fun cancelAll() = Unit
            }
            override fun require(capability: Capability): Any =
                throw ContractError.CapabilityMissing("not granted: $capability")
        }
        assertNull(services.audioSegments().fetch("any-turn"))
    }
}
