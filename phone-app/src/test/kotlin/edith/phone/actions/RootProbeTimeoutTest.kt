package edith.phone.actions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/**
 * P3.3 review, Minor 2: on a rooted device the `su` probe can block forever (an `su` waiting for a permission prompt),
 * and `HostCompositionRoot.start` asks it on the session page's thread, so the page would hang.
 *
 * `probeWithTimeout` is deliberately a plain function over [Process] rather than a lambda inside `onDevice()`: the
 * timeout policy is then pinned here, on the JVM, with no device and no real `su`.
 */
class RootProbeTimeoutTest {

    /** A process the test drives by hand: [finishes] decides whether the bounded wait ever sees it exit. */
    private class StubProcess(private val finishes: Boolean, private val out: String = "uid=0\n") : Process() {
        var killedForcibly = false
        var unboundedWaitUsed = false

        override fun getOutputStream() = ByteArrayOutputStream()
        override fun getInputStream() = ByteArrayInputStream(out.toByteArray())
        override fun getErrorStream() = ByteArrayInputStream(ByteArray(0))
        override fun waitFor(): Int { unboundedWaitUsed = true; return 0 }
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = finishes
        override fun exitValue(): Int = 0
        override fun destroy() = Unit
        override fun destroyForcibly(): Process { killedForcibly = true; return this }
    }

    @Test
    fun aProbeThatNeverAnswersIsReportedAsNoRootInsteadOfBlockingTheSessionPage() {
        val process = StubProcess(finishes = false)

        val result = probeWithTimeout(listOf("su", "-c", "id"), 2_000L) { process }

        assertNull("an unanswered probe is not a root grant", result)
        assertTrue("the stuck process must be killed, not leaked", process.killedForcibly)
        assertFalse("the probe must use the bounded wait, never the unbounded one", process.unboundedWaitUsed)
    }

    @Test
    fun aProbeThatAnswersStillReportsItsExitCodeAndOutput() {
        val process = StubProcess(finishes = true)

        val result = probeWithTimeout(listOf("su", "-c", "id"), 2_000L) { process }

        assertEquals(0, result?.exitCode)
        assertTrue("the stdout check is what separates root from a su that answers as the shell identity",
            result!!.stdout.contains("uid=0"))
    }
}
