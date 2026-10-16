package edith.phone.actions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R-9 / D-6: one privileged reply has an explicit cap, and "too large" is a distinguishable answer.
 *
 * Before this change `EdithShellService.run` read both pipes with `readText()` and no bound at all, and
 * `ShizukuUserServiceShell` collapsed every failure into `SHELL_REPLY_INVALID` - so a command that actually ran was
 * reported as a parse failure, and a reply that was merely large was indistinguishable from one that was malformed.
 * The privileged process would also accumulate an unbounded `String`, then a second copy for the JSON, then a third on
 * the app side.
 */
class ShellReplyCapTest {

    @Test
    fun `a reply at exactly the cap is not truncated`() {
        // Review Focus 4: one byte short of the cap is not truncated; the boundary is exact.
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
    fun `an untouched reply does not carry the truncated key`() {
        // Only write the key when it is true, so the wire form of the common case is unchanged.
        val encoded = ShellReply.encode(0, "out", "err")
        assertFalse(encoded.contains("truncated"))
    }

    @Test
    fun `a reply too large is distinguishable from a reply that cannot be parsed`() {
        // The real defect: `runCatching{}.getOrNull()` used to collapse every exception into SHELL_REPLY_INVALID.
        // `ShizukuUserServiceShell.run` returns SHELL_REPLY_TOO_LARGE for TransactionTooLargeException instead, so the
        // two are different codes... and neither is an empty success.
        assertNotEquals(SHELL_REPLY_INVALID, SHELL_REPLY_TOO_LARGE)

        val tooLarge = ShellResult("dumpsys power", -1, "", SHELL_REPLY_TOO_LARGE)
        assertFalse("a too-large reply is a failed state read, not an answer", tooLarge.isAStateReply())
        assertNotEquals("a too-large reply is not reported as a parse failure", SHELL_REPLY_INVALID, tooLarge.stderr)

        // A reply that decoded but was truncated is still readable: the flag describes the payload, not the channel.
        val truncated = ShellReply.decode(ShellReply.encode(0, "out", "err", truncated = true))!!
        assertTrue(truncated.isAStateReply())
    }

    @Test
    fun `a truncated state read is not answered as a readable state`() {
        val shell = object : PrivilegedShell {
            override fun run(command: String): ShellResult =
                ShellResult(command, -1, "", SHELL_REPLY_TOO_LARGE)
        }
        val state = ShizukuTargetState(shell)
        assertFalse(state.installed("com.example"))
        assertFalse("a too-large reply must never be read as a state", state.readable())
    }
}
