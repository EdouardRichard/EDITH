package edith.output

import edith.contracts.Assistance
import edith.contracts.SessionId
import edith.contracts.Generation
import edith.plugin.CancellationHandle
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
