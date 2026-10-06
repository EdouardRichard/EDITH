package edith.contracts

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ContractValidationTest {
    private val session = SessionId("s1")
    private val generation = Generation(2)
    private val envelope = EventEnvelope(session, generation, "e1", 0L, 1_000_000L)

    @Test fun `unknown audio format round trips`() {
        assertEquals(AudioFormat.Unknown, AudioFormat.Unknown)
        assertEquals(AudioFormat.Unknown, AudioSegment(envelope, AudioFormat.Unknown, ByteArray(0)).format)
    }

    @Test fun `transcript revisions are ordered within a turn`() {
        val first = TranscriptRevision(envelope, "turn", 1, "partial", false, 0L, 1L)
        val final = TranscriptRevision(envelope, "turn", 2, "final", true, 0L, 2L)
        assertTrue(final.isNewerThan(first))
        assertFalse(first.isNewerThan(final))
    }

    @Test fun `stale generation output is rejected`() {
        val output = Assistance("h1", session, Generation(1), null, 1, "hint", null, 2_000_000L)
        assertFalse(output.isCurrent(session, generation))
    }
}
