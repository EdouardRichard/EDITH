package com.sheen.rokid.phone.actions.shizuku

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * P3.3 review, Minor 2, and the leftover-sweep review's Important 1 + 2: on a rooted device the `su` probe can block
 * forever (an `su` waiting for a permission prompt) and it is asked from the session page's thread, so the page would
 * hang - and once it is bounded, the panel must still say *which* way it failed.
 *
 * `probeStatus` is deliberately a plain function over [Process] rather than a lambda inside `onDevice()`: the timeout
 * policy and the reason it reports are then pinned here, on the JVM, with no device and no real `su`.
 */
class RootProbeTimeoutTest {

    /** A process the test drives by hand: [finishes] decides whether the bounded wait ever sees it exit. */
    private class StubProcess(private val finishes: Boolean, private val out: String = "uid=0\n") : Process() {
        var killedForcibly = false
        var unboundedWaitUsed = false
        var lastTimeoutMillis = -1L

        override fun getOutputStream() = ByteArrayOutputStream()
        override fun getInputStream() = ByteArrayInputStream(out.toByteArray())
        override fun getErrorStream() = ByteArrayInputStream(ByteArray(0))
        override fun waitFor(): Int { unboundedWaitUsed = true; return 0 }
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean {
            lastTimeoutMillis = unit.toMillis(timeout)
            return finishes
        }
        override fun exitValue(): Int = 0
        override fun destroy() = Unit
        override fun destroyForcibly(): Process { killedForcibly = true; return this }
    }

    private fun probe(process: Process): RootStatus =
        probeStatus(listOf("su", "-c", "id"), 2_000L) { process }

    @Test
    fun aProbeThatNeverAnswersIsReportedAsNoAnswerAndNeverAsRoot() {
        val process = StubProcess(finishes = false)

        val status = probe(process)

        assertEquals(RootStatus.NO_ANSWER, status)
        assertTrue("the stuck process must be killed, not leaked", process.killedForcibly)
        assertFalse("the probe must use the bounded wait, never the unbounded one", process.unboundedWaitUsed)
    }

    @Test
    fun aSilentSuIsNotReportedAsAMissingBinary() {
        // The distinction the panel's Root row now prints: these two must not collapse into one wording.
        val silent = probe(StubProcess(finishes = false))

        assertNotEquals("a su that did not answer is a different fact from a device with no su",
            RootStatus.ABSENT, silent)
        assertTrue("the timeout line must stay informational: " + RootProbe.noteFor(silent),
            "不可用" in RootProbe.noteFor(silent))
    }

    @Test
    fun aProbeThatAnswersStillReportsWhatItFound() {
        assertEquals(RootStatus.AVAILABLE, probe(StubProcess(finishes = true)))
        assertEquals(RootStatus.NOT_ROOT,
            probe(StubProcess(finishes = true, out = "uid=2000(shell) gid=2000(shell)\n")))
    }

    @Test
    fun anAnsweredProbeIsNotKilled() {
        val process = StubProcess(finishes = true)

        assertEquals(RootStatus.AVAILABLE, probe(process))

        assertFalse("a probe that answered must be left alone, not destroyed", process.killedForcibly)
    }

    @Test
    fun theCallersTimeoutIsTheOneTheProbeWaits() {
        val process = StubProcess(finishes = false)

        probeStatus(listOf("su", "-c", "id"), 1_234L) { process }

        assertEquals("the bound is the caller's, so it must be the value handed to the wait",
            1_234L, process.lastTimeoutMillis)
    }

    @Test
    fun aMissingBinaryIsAbsentRatherThanAnException() {
        val status = probeStatus(listOf("su", "-c", "id"), 2_000L) { throw IOException("su: not found") }

        assertEquals(RootStatus.ABSENT, status)
        assertEquals("Root：不可用（设备无 su）", RootProbe.noteFor(status))
    }
}
