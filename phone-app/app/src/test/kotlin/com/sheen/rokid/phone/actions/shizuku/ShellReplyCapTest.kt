package com.sheen.rokid.phone.actions.shizuku
import com.sheen.rokid.phone.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * R-9 / D-6: one privileged reply has an explicit cap, and "too large" is a distinguishable answer.
 *
 * Before this change `EdithShellService.run` read both pipes with `readText()` and no bound at all, and
 * `ShizukuUserServiceShell` collapsed every failure into `SHELL_REPLY_INVALID` - so a command that actually ran was
 * reported as a parse failure, and a reply that was merely large was indistinguishable from one that was malformed.
 * The privileged process would also accumulate an unbounded `String`, then a second copy for the JSON, then a third on
 * the app side.
 *
 * The drain-to-EOF claim is pinned by an **interleaved** stream pair rather than two whole `ByteArrayInputStream`s:
 * the second stream here withholds its bytes until the first has yielded a byte *past* the cap, so a drain that broke
 * out of the loop at the cap would starve it and hang the call, whereas the in-memory variant cannot see that at all
 * (each `ByteArrayInputStream` is read by its own thread under its own budget and reaches EOF regardless).
 */
class ShellReplyCapTest {

    @Test
    fun `a reply at exactly the cap is not truncated`() {
        // Review Focus 4: exactly at the cap is not over it; the boundary is exact.
        val out = "x".repeat(ShellReply.MAX_REPLY_BYTES)
        val (stdout, _, truncated) = drainStreams(out.byteInputStream(), "".byteInputStream())
        assertEquals(ShellReply.MAX_REPLY_BYTES, stdout.toByteArray().size)
        assertFalse(truncated)
    }

    @Test
    fun `a reply one byte over the cap is truncated and says so`() {
        val out = "x".repeat(ShellReply.MAX_REPLY_BYTES + 1)
        val (stdout, _, truncated) = drainStreams(out.byteInputStream(), "".byteInputStream())
        assertTrue(truncated)
        assertEquals(ShellReply.MAX_REPLY_BYTES, stdout.toByteArray().size)
    }

    @Test(timeout = 10_000)
    fun `both streams are drained concurrently even when the cap is reached early`() {
        // This covers a real deadlock: once the cap is reached, stopping the read leaves a child that filled the pipe
        // blocked in write(2), so its other stream never reaches EOF and the caller waits forever. Both streams must
        // still be read to EOF; only the accumulation stops.
        val big = "e".repeat(8 * 1024 * 1024)
        val (stdout, stderr, truncated) = drainStreams("x".repeat(9 * 1024 * 1024).byteInputStream(), big.byteInputStream())
        assertTrue(truncated)
        assertEquals(ShellReply.MAX_REPLY_BYTES, stdout.toByteArray().size)
        // stderr is a stream of its own: it is still read to EOF in full, not sacrificed because stdout hit the cap.
        assertEquals(big.length, stderr.length)
    }

    @Test
    fun `encode and decode round-trip the truncated flag`() {
        val decoded = ShellReply.decode(ShellReply.encode(0, "out", "err", truncated = true))!!
        assertTrue(decoded.truncated)
        assertFalse(ShellReply.decode(ShellReply.encode(0, "out", "err"))!!.truncated)
        // A missing `truncated` key (a reply from an older build) reads as false, not as a rejection.
        assertFalse(ShellReply.decode("""{"exit":0,"stdout":"a","stderr":""}""")!!.truncated)
    }

    @Test
    fun `an unreadable truncated value is read as false rather than rejecting the reply`() {
        // The flag is advisory and must never reject a reply whose payload we did understand - including when the value
        // is present but is not a primitive at all, which is the shape that used to throw out of `decode` instead.
        val decoded = ShellReply.decode("""{"exit":0,"stdout":"a","stderr":"","truncated":{}}""")
        assertEquals("a non-primitive truncated must not fail the payload", "a", decoded?.stdout)
        assertFalse("a non-primitive truncated reads as false", decoded?.truncated ?: true)
    }

    @Test
    fun `an untouched reply does not carry the truncated key`() {
        // Only write the key when it is true, so the wire form of the common case is unchanged.
        val encoded = ShellReply.encode(0, "out", "err")
        assertFalse(encoded.contains("truncated"))
    }

    @Test(timeout = 10_000)
    fun `reaching the cap does not stop the drain, so the other stream still reaches EOF`() {
        // The single fact the in-memory variant cannot see: `write(2)` coupling. stdout's one byte past the cap is what
        // releases stderr, so a drain that broke out of the loop at the cap never reads it, the stderr thread stays
        // parked on `await`, and the `join` never returns. `MAX_REPLY_BYTES` is a multiple of the 8 KiB read buffer, so
        // the read carrying that byte is exactly the first discard read.
        val overshootRead = CountDownLatch(1)
        val overshootReads = AtomicInteger(0)
        val stdout = capBoundStdout(overshootRead, overshootReads)
        val payload = STDERR_PAYLOAD
        val stderr = object : InputStream() {
            private var offset = 0
            private var gated = false
            override fun read(): Int = -1
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (!gated) {
                    // Gated exactly once: with the drain intact stdout has already been read past the cap and this
                    // returns immediately; with the drain cut short at the cap it waits - and the join hangs.
                    assertTrue(
                        "stderr must not be drained before stdout is read past the cap",
                        overshootRead.await(5, TimeUnit.SECONDS),
                    )
                    gated = true
                }
                if (offset >= payload.length) return -1
                val n = minOf(len, payload.length - offset)
                for (i in 0 until n) b[off + i] = payload[offset + i].code.toByte()
                offset += n
                return n
            }
        }

        val (out, err, truncated) = drainStreams(stdout, stderr)

        assertTrue(
            "stdout was never read past the cap: overshootReads=${overshootReads.get()} latch=${overshootRead.count}",
            overshootReads.get() == 1,
        )
        assertTrue(truncated)
        assertEquals(ShellReply.MAX_REPLY_BYTES, out.toByteArray().size)
        assertEquals(payload.length, err.length)
    }

    @Test
    fun `only stderr over the cap is truncated too`() {
        // The flag is `either stream`, and only the stdout side used to be exercised - so `outTruncated` alone left
        // every test green. Here stdout is small and whole; stderr alone spends the budget.
        val (out, err, truncated) = drainStreams(
            "out".byteInputStream(),
            "e".repeat(ShellReply.MAX_REPLY_BYTES + 1).byteInputStream(),
        )
        assertTrue("a truncated stderr must set the flag on its own", truncated)
        assertEquals("out", out)
        assertEquals(ShellReply.MAX_REPLY_BYTES, err.toByteArray().size)
    }

    @Test
    fun `a tiny cap cuts both streams at the same per-stream budget and still drains each to EOF`() {
        // Every other test in this class (and `ShellStreamsTest`) leaves `maxBytes` at the 8 MiB default, so the
        // parameter was never exercised with any other value: a hard-coded cap inside `readReply` would have stayed
        // green. This is the seam made real - a cap small enough to reason about by hand.
        val cap = 100
        val stdout = ByteArray(250) { 'o'.code.toByte() }
        val stderr = ByteArray(400) { 'e'.code.toByte() }
        // One count per stream of reads requested **past** that stream's budget. Within the budget the caller clamps
        // the request to `maxBytes - total` (never more than the cap); past it the request is the whole 8 KiB discard
        // buffer, so a request above the cap is exactly the post-budget read. Atomic because the two streams drain on
        // two threads. A shared (rather than per-stream) budget would leave one of the two at zero.
        val pastBudget = arrayOf(AtomicInteger(0), AtomicInteger(0))

        val (out, err, truncated) = drainStreams(
            countingStream(stdout) { if (it > cap) pastBudget[0].incrementAndGet() },
            countingStream(stderr) { if (it > cap) pastBudget[1].incrementAndGet() },
            maxBytes = cap,
        )

        assertTrue("either stream over the cap must set the flag", truncated)
        assertEquals("stdout is cut to the cap, not to something else", cap, out.toByteArray().size)
        assertEquals("stderr spends its own budget, not what stdout left", cap, err.toByteArray().size)
        assertTrue("stdout must be read past its budget to reach EOF", pastBudget[0].get() > 0)
        assertTrue("stderr must be read past its budget to reach EOF", pastBudget[1].get() > 0)
        assertTrue(out.all { it == 'o' })
        assertTrue(err.all { it == 'e' })
    }

    /**
     * A stream that hands its payload over in the requested sizes and then reaches EOF. The callback sees each read
     * attempt's **requested** length after the first, which is what makes "this read is past the budget" observable.
     */
    private fun countingStream(payload: ByteArray, onRead: (Int) -> Unit): InputStream {
        var offset = 0
        return object : InputStream() {
            private var first = true
            override fun read(): Int = -1
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (first) {
                    first = false
                } else {
                    onRead(len)
                }
                if (offset >= payload.size) return -1
                val n = minOf(len, payload.size - offset)
                for (i in 0 until n) b[off + i] = payload[offset + i]
                offset += n
                return n
            }
        }
    }

    /**
     * Stands in for the interleaved child: [maxBytes] of stdout, then **one byte past the cap**, then EOF.
     *
     * The extra byte is what makes the post-cap read observable - the discard read has to carry real data to run at
     * all - and the countdown on it is what releases the peer stream.
     */
    private fun capBoundStdout(
        overshootRead: CountDownLatch,
        overshootReads: AtomicInteger,
        maxBytes: Int = ShellReply.MAX_REPLY_BYTES,
    ): InputStream = object : InputStream() {
        private var remaining = maxBytes
        private var pastCap = 1
        override fun read(): Int = -1
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (remaining > 0) {
                val n = minOf(len, remaining)
                java.util.Arrays.fill(b, off, off + n, 0)
                remaining -= n
                return n
            }
            if (pastCap > 0) {
                pastCap -= 1
                overshootReads.incrementAndGet()
                b[off] = 0
                overshootRead.countDown()
                return 1
            }
            return -1
        }
    }

    @Test
    fun `a reply too large is distinguishable from a reply that cannot be parsed`() {
        // The real defect: `runCatching{}.getOrNull()` used to collapse every throwable into SHELL_REPLY_INVALID.
        // `ShizukuUserServiceShell.run` reports a call that did not come back as SHELL_REPLY_TOO_LARGE + CARRIER
        // instead, so the two are different codes and different kinds... and neither is an empty success. (The device
        // then showed the thrown class is not `TransactionTooLargeException`, which is why the classification must not
        // test for that one class; `ShellCarrierFailureTest` pins that.)
        assertNotEquals(SHELL_REPLY_INVALID, SHELL_REPLY_TOO_LARGE)

        val tooLarge = ShellResult("dumpsys power", -1, "", SHELL_REPLY_TOO_LARGE, failure = ShellFailure.CARRIER)
        assertFalse("a too-large reply is a failed state read, not an answer", tooLarge.isReadableState())
        assertNotEquals("a too-large reply is not reported as a parse failure", SHELL_REPLY_INVALID, tooLarge.stderr)
        // The two carriers of the same fact agree: the code a human greps for, and the kind a caller reads. A result
        // marked CARRIER but still carrying SHELL_REPLY_INVALID would be the old collapse back in a new field.
        assertEquals(ShellFailure.CARRIER, tooLarge.failure)
        assertFalse(
            "a reply marked as a carrier failure is not a state reply even without the marker",
            ShellResult("dumpsys power", -1, "", "SHELL_SOMETHING_ELSE", failure = ShellFailure.CARRIER).isReadableState(),
        )

        // The `truncated` flag describes the payload, so a reply that was truncated still decodes and still says it was
        // truncated - that is what is asserted here. It is **not** a claim that a truncated **state read** may be
        // trusted, and `stderr` being non-empty is not a channel failure either: `decode` returns a reply with no
        // `failure` kind because the bytes *did* arrive and *were* understood. `ShizukuTargetState.read` joins the
        // facts as `isReadableState()`, which is `failure == null && exitCode == 0 && !truncated`, and the test below
        // pins the truncated half.
        val truncated = ShellReply.decode(ShellReply.encode(0, "out", "err", truncated = true))!!
        assertTrue("a decoded reply is a reply the build understood", truncated.truncated)
        assertNull("decoding a reply is not a channel failure", truncated.failure)
    }

    @Test
    fun `a state read whose stdout was truncated is not readable, so no probe answer is trusted`() {
        // R-9's flag is only honest if a *state-deciding* read consumes it. `installed`/`disabled`/`permissionGranted`
        // are stdout **parsers**: a `pm list packages` reply clipped at the cap still decodes and still carries
        // `truncated = true`, and `stdout` is then a prefix rather than the whole answer. The clipped tail is where the
        // package line lives, so the probe answers `false` for a package that **is** present - the exact
        // "a completed action that never happened" class `readable()` exists to block. The reply here is a *successful*
        // parse (no error marker on either stream): only the truncation flag makes it unreadable.
        val shell = object : PrivilegedShell {
            override fun run(command: String): ShellResult =
                ShellResult(command, 0, "package:com.example.other\npackage:com.examp", "", truncated = true)
        }
        val state = ShizukuTargetState(shell)

        assertFalse("the clipped stdout answers a negative for a package it never got to list", state.installed("com.example"))
        assertFalse("a truncated stdout is a prefix, so the state read cannot be read at all", state.readable())
    }

    @Test
    fun `an untruncated state read stays readable, so the flag alone decides`() {
        // The other half of the pair: the same shape with nothing clipped must still be readable, so the assertion above
        // is about `truncated` and not about stdout parsing in general.
        val shell = object : PrivilegedShell {
            override fun run(command: String): ShellResult =
                ShellResult(command, 0, "package:com.example.other\n", "")
        }
        val state = ShizukuTargetState(shell)

        assertFalse(state.installed("com.example"))
        assertTrue("a reply that was not clipped is still an answer about the target", state.readable())
    }

    private companion object {
        /** Non-empty, and small next to the 8 MiB cap, so a peer's payload is never mistaken for the clip. */
        val STDERR_PAYLOAD = "e".repeat(2 * 1024 * 1024)
    }
}
