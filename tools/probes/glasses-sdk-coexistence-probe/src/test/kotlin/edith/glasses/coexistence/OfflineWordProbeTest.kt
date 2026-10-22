package edith.glasses.coexistence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T1 asks two questions that a device run can easily answer wrongly, so both decisions are pinned here.
 *
 * 1. Which outcome each wake word landed in (`ACCEPTED` / `THREW` / `REJECTED_BY_RETURN`).
 * 2. Whether the offline lexicon and our own microphone capture can both carry **real** audio at once.
 *
 * The second one cannot be decided on byte counts alone. `AudioRecord` keeps returning the full byte rate when another
 * client of higher priority owns the microphone — it returns *zeros*. A criterion of "bytes arrived" would therefore
 * report success for a silently stolen microphone, which is precisely the failure this verdict exists to catch, so the
 * level of the captured audio is part of the verdict.
 */
class OfflineWordProbeTest {

    @Test
    fun contentionFailsWhenOnlyTheOfflinePathFired() {
        assertFalse(contention(3, 0L, 0).ok)
        assertEquals("capture", contention(3, 0L, 0).reason)
    }

    /** The trap: the recorder delivered its full byte rate, and every byte was silence. */
    @Test
    fun contentionFailsWhenCaptureArrivedButWasSilent() {
        assertFalse(contention(3, 384_000L, 0).ok)
        assertEquals("silent", contention(3, 384_000L, 0).reason)
        assertFalse(contention(3, 384_000L, MIN_SPEECH_RMS - 1).ok)
    }

    @Test
    fun contentionFailsWhenOnlyOurCaptureFired() {
        assertFalse(contention(0, 384_000L, 688).ok)
        assertEquals("offline", contention(0, 384_000L, 688).reason)
    }

    @Test
    fun contentionNamesBothWhenNeitherPathCarriesData() {
        assertFalse(contention(0, 0L, 0).ok)
        assertEquals("both", contention(0, 0L, 0).reason)
    }

    @Test
    fun contentionPassesOnlyWhenBothPathsCarryRealData() {
        assertTrue(contention(2, 384_000L, 688).ok)
        assertEquals("", contention(2, 384_000L, 688).reason)
        assertTrue(contention(2, 384_000L, MIN_SPEECH_RMS).ok)
    }

    @Test
    fun summarizeCountsEachOutcome() {
        val trial = WordTrial(
            listOf(
                WordAttempt("伊迪丝", "yi di si", WordOutcome.ACCEPTED),
                WordAttempt("open chat", "open chat", WordOutcome.THREW),
                WordAttempt("edith 你好", "edith ni hao", WordOutcome.REJECTED_BY_RETURN),
                WordAttempt("第2项", "di er xiang", WordOutcome.ACCEPTED),
            ),
        )
        val counts = summarize(trial)
        assertEquals(2, counts.getValue(WordOutcome.ACCEPTED))
        assertEquals(1, counts.getValue(WordOutcome.THREW))
        assertEquals(1, counts.getValue(WordOutcome.REJECTED_BY_RETURN))
    }
}
