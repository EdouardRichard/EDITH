package edith.glasses.coexistence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The T0 verdict is the whole answer to "can the Glasses-side open SDK and the CXR-S bridge live in one app", so the
 * rule that decides it is pinned here rather than left inside the device harness. A run that crashes proves nothing:
 * `ok` requires a positive signal from *each* of the three questions, and `missing` names the ones that failed.
 */
class CoexistenceProbeTest {

    @Test
    fun evaluateFailsWhenAnySignalMissing() {
        assertFalse(evaluate(true, true, false).ok)
        assertEquals(listOf("cxrRoundTrip"), evaluate(true, true, false).missing)
        assertFalse(evaluate(true, false, true).ok)
        assertFalse(evaluate(false, true, true).ok)
    }

    @Test
    fun evaluatePassesOnlyWhenAllThreeHold() {
        assertTrue(evaluate(true, true, true).ok)
    }

    @Test
    fun evaluateNamesEveryMissingSignalInAFixedOrder() {
        val verdict = evaluate(false, false, false)
        assertEquals(listOf("glassSdkReady", "glassServiceReachable", "cxrRoundTrip"), verdict.missing)
    }
}
