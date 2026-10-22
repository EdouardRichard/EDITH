package com.sheen.rokid.output

import com.sheen.rokid.contracts.Assistance
import com.sheen.rokid.contracts.SessionId
import com.sheen.rokid.contracts.Generation
import com.sheen.rokid.plugin.CancellationHandle
import com.sheen.rokid.plugin.SpeechPlayback
import com.sheen.rokid.plugin.SpeechPlayer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class SpeechPlayerTest {
    @Test fun `speech playback enum has started finished and failed values`() {
        val values = SpeechPlayback.values().map { it.name }.toSet()
        assertEquals(setOf("STARTED", "FINISHED", "FAILED"), values)
    }

    @Test fun `play returns a cancellable handle`() {
        var cancelled = false
        val player = object : SpeechPlayer {
            override fun play(assistance: Assistance, onState: (SpeechPlayback) -> Unit): CancellationHandle {
                return object : CancellationHandle {
                    override fun cancel() { cancelled = true }
                }
            }
        }
        val hint = Assistance("h", SessionId("s"), Generation(1), "t", 1, "text", null, 100)
        val handle = player.play(hint) { }
        assertNotNull(handle)
        handle.cancel()
        assertEquals(true, cancelled)
    }
}
