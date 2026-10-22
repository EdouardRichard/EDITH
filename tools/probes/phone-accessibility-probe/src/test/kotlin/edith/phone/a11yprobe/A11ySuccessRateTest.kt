package edith.phone.a11yprobe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T3b's numbers, pinned before the probe runs.
 *
 * The two modes are counted **separately and never merged** into one success rate. A semantic node click survives a
 * layout shift, a re-render or a different screen density; a coordinate tap does not. A single blended figure would hide
 * exactly the comparison the P3 design needs in order to choose between them.
 *
 * The summary reports integer counts rather than rates: a rate computed in the probe would bake in a rounding decision
 * before anyone has seen the numbers, and `A11yRun.ok` is the only fact the device actually produced.
 */
class A11ySuccessRateTest {

    @Test
    fun summarizeSeparatesNodeAndCoordinateSuccess() {
        val runs = listOf(
            A11yRun(1, true, A11yMode.NODE_CLICK, null),
            A11yRun(2, false, A11yMode.NODE_CLICK, "node not found"),
            A11yRun(3, true, A11yMode.COORDINATE_TAP, null),
        )
        val summary = summarize(runs)
        assertEquals(1, summary.nodeSuccess)
        assertEquals(2, summary.nodeRuns)
        assertEquals(1, summary.coordSuccess)
        assertEquals(1, summary.coordRuns)
        assertEquals(1, summary.failures.getValue("node not found"))
    }

    @Test
    fun summarizeCountsEveryFailureReasonSeparately() {
        val runs = listOf(
            A11yRun(1, false, A11yMode.NODE_CLICK, "node not found"),
            A11yRun(2, false, A11yMode.NODE_CLICK, "node not found"),
            A11yRun(3, false, A11yMode.COORDINATE_TAP, "no bounds to tap"),
        )
        val summary = summarize(runs)
        assertEquals(2, summary.failures.getValue("node not found"))
        assertEquals(1, summary.failures.getValue("no bounds to tap"))
    }

    @Test
    fun summarizeTreatsAnEmptyRunAsZeroCountsRatherThanASuccess() {
        val summary = summarize(emptyList())
        assertEquals(0, summary.nodeSuccess)
        assertEquals(0, summary.nodeRuns)
        assertEquals(0, summary.coordSuccess)
        assertEquals(0, summary.coordRuns)
        assertEquals(0, summary.failures.size)
    }

    /**
     * The first real run reported 0/20 for both modes, and both zeros were the probe's fault:
     *
     *  - `performAction(ACTION_CLICK) returned false` (19/20): the node found was a non-clickable label, because the
     *    probe took the first *visible* match instead of preferring a clickable one.
     *  - `tap had no effect` (20/20): success was judged by "the target text is gone", but tapping into
     *    「网络和互联网」 opens a screen whose *title is* 「网络和互联网」 — navigation succeeded and was scored as
     *    failure.
     *
     * Effectiveness is therefore a change of screen, never the disappearance of a label.
     */
    @Test
    fun anActionCountsOnlyWhenTheScreenActuallyChanged() {
        assertTrue(isActionEffective(performed = true, before = "A|B", after = "C|D"))
        assertFalse(isActionEffective(performed = true, before = "A|B", after = "A|B"))
        assertFalse(isActionEffective(performed = false, before = "A|B", after = "C|D"))
    }

    @Test
    fun failureReasonsDistinguishRejectedFromIneffective() {
        assertEquals("performAction(ACTION_CLICK) returned false", failureFor(performed = false, before = "A", after = "A"))
        assertEquals("no screen change", failureFor(performed = true, before = "A", after = "A"))
        assertEquals(null, failureFor(performed = true, before = "A", after = "B"))
    }
}
