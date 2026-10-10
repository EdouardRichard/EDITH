package edith.phone.actions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * P3.3 review, Minor 3: `EdithShellService.run` drained stdout to completion before touching stderr, so a command whose
 * stderr fills the OS pipe (tens of KB) blocks **in the child** while the service waits for a stdout that never reaches
 * EOF - a deadlock the app side only sees as a binder timeout.
 *
 * The test builds exactly that shape: stderr yields its payload only to somebody who reads it, and stdout does not reach
 * EOF until stderr has been drained. A sequential drain therefore waits out the timeout; a concurrent one returns.
 */
class ShellStreamsTest {

    @Test(timeout = 10_000)
    fun aStderrStreamThatIsOnlyReadConcurrentlyCannotDeadlockTheStdoutDrain() {
        val stderrDrained = CountDownLatch(1)
        val payload = "e".repeat(200_000)
        val stderr = object : InputStream() {
            private var offset = 0
            override fun read(): Int = -1
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (offset >= payload.length) return -1
                val n = minOf(len, payload.length - offset)
                for (i in 0 until n) b[off + i] = payload[offset + i].code.toByte()
                offset += n
                if (offset >= payload.length) stderrDrained.countDown()
                return n
            }
        }
        // A child blocked on a full stderr pipe keeps stdout open: it is waiting for us while we wait for its stdout.
        val stdout = object : InputStream() {
            override fun read(): Int {
                assertTrue("stdout must not be drained to completion before stderr",
                    stderrDrained.await(30, TimeUnit.SECONDS))
                return -1
            }
        }

        val (out, err) = drainStreams(stdout, stderr)

        assertEquals("", out)
        assertEquals(payload.length, err.length)
    }
}
