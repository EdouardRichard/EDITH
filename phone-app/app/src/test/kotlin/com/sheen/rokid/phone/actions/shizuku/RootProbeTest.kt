package com.sheen.rokid.phone.actions.shizuku
import com.sheen.rokid.phone.storage.unavailable

import com.sheen.rokid.plugin.ActionOutcome
import com.sheen.rokid.plugin.RiskLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Root is **probed, never promised** (spec §17.1#1, §7.3): the user's 2026-10-08 decision was to treat it as a case
 * that exists in the imagination rather than something to verify, so this channel declares **no actions at all**. What
 * it does is answer one question honestly, so the capability panel can say "root is not available on this device"
 * instead of silently implying the app could do L4 things it cannot.
 */
class RootProbeTest {

    @Test
    fun aMissingSuBinaryIsUnavailable() {
        assertFalse(RootProbe.fromRunner { null }.available())
        assertFalse(RootProbe.fromRunner { throw java.io.IOException("su: not found") }.available())
    }

    @Test
    fun suReportingUidZeroIsAvailable() {
        val probe = RootProbe.fromRunner { ShellResult("su -c id", 0, "uid=0(root) gid=0(root) groups=0(root)", "") }

        assertTrue(probe.available())
        assertEquals("ROOT_AVAILABLE", probe.reason())
    }

    @Test
    fun suThatExistsButIsNotRootIsUnavailable() {
        // A binary that runs but reports the shell identity is not root - the distinction the exit code cannot make.
        val probe = RootProbe.fromRunner { ShellResult("su -c id", 0, "uid=2000(shell) gid=2000(shell)", "") }

        assertFalse(probe.available())
        assertEquals("ROOT_UNAVAILABLE", probe.reason())
    }

    @Test
    fun theRootRowIsAnInformationalLineRatherThanAnError() {
        val description = RootProbe.fromRunner { null }.describe()

        assertTrue("the row must say it is unavailable: $description", "不可用" in description)
        assertFalse("an absent su is the device's nature, not a failure: $description", "错误" in description)
        assertFalse("异常" in description)
    }

    @Test
    fun theProbeDeclaresNoActions() {
        val probe = RootProbe.fromRunner { ShellResult("su -c id", 0, "uid=0(root)", "") }

        assertTrue("root declares nothing, so an L4 path can never be advertised from it", probe.actions().isEmpty())
    }

    @Test
    fun aRootActionWouldStillBeL4AndUnconfirmedByItselfIfOneEverExisted() {
        // Documents the boundary rather than a behaviour: the tier and the "the policy asks, not the executor" rule are
        // what any future root action must declare. There is no such action in P3.3.
        assertEquals(RiskLevel.L4, RootProbe.TIER)
        assertEquals(ActionOutcome.Refused, RootProbe.NOTHING_TO_RUN.outcome)
    }
}
