package edith.probe.toolcall

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The guard that keeps the probe's own output from becoming the leak.
 *
 * The probe reads a real API key out of a local properties file in order to make one request. Everything it prints —
 * including any error body an endpoint echoes back — goes to a terminal and from there into an evidence file, so the
 * scan is a precondition of printing at all, not a nicety. Hits are reported by position, never by content, so the
 * guard itself cannot become the leak either.
 */
class RedactionGuardTest {

    @Test
    fun scanDetectsSecretPrefix() {
        assertEquals(1, scan("Authorization: Bearer sk-abc123", "sk-abc1").size)
    }

    @Test
    fun scanPassesCleanOutput() {
        assertTrue(scan("""{"status":"COMPLETED"}""", "sk-abc1").isEmpty())
    }

    @Test
    fun scanFindsEveryOccurrence() {
        assertEquals(2, scan("sk-abc1 then again sk-abc1", "sk-abc1").size)
    }

    @Test
    fun scanIsCaseInsensitive() {
        assertEquals(1, scan("SK-ABC1", "sk-abc1").size)
    }

    @Test
    fun scanHitsNeverCarryTheSecretContent() {
        val hits = scan("token=sk-abc123", "sk-abc1")
        assertEquals(1, hits.size)
        assertTrue(hits.none { it.contains("sk-abc1") }, "a hit must describe a position, not repeat the secret")
    }

    /** A prefix too short to be meaningful would match everywhere and pass everything: refuse it loudly. */
    @Test
    fun scanRefusesAPrefixTooShortToBeMeaningful() {
        assertFailsWith<IllegalArgumentException> { scan("anything", "sk") }
        assertFailsWith<IllegalArgumentException> { scan("anything", "") }
    }
}
