package edith.phone.actions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R-9's distinction at the boundary that actually broke on the device: **"the channel could not carry the reply"**
 * versus **"this build could not parse it"**.
 *
 * The K40 measurement (`evidence/2026-10-16-phase-5-device-verification.md`, §2) showed a reply of ~300-350 kB failing
 * with `SHELL_REPLY_INVALID`: `ShizukuUserServiceShell.run` caught `TransactionTooLargeException` separately and then
 * swallowed **every other** throwable into `null`, so `decode(null)` produced a parse failure for a reply that had
 * arrived but could not cross the binder. The exception actually thrown there was **not**
 * `TransactionTooLargeException`, which is exactly why the named code never fired: the classification cannot be a test
 * for one exception class, it has to be a statement about what *any* throwable out of the binder call means.
 *
 * These tests pin the classification and the "a failed read is not an answer" rule. `carrierCodeFor` is called
 * directly rather than faking a binder: `ShizukuUserServiceShell` itself needs an Android `Context` and a live Shizuku
 * connection, so the part that decides the code is pure and is exercised here, and the whole path is re-measured on the
 * device through `DebugShellCapProbe`.
 */
class ShellCarrierFailureTest {

    @Test
    fun `a binder failure that is not TransactionTooLarge is still classified as a carrier failure`() {
        // The device's real case. The concrete class is deliberately *not* `TransactionTooLargeException`: whatever the
        // binder throws, "the call did not come back" is a carrier fact, never a statement about the reply's syntax.
        assertEquals(SHELL_REPLY_TOO_LARGE, carrierCodeFor(RuntimeException("android.os.RemoteException")))
        assertEquals(SHELL_REPLY_TOO_LARGE, carrierCodeFor(IllegalStateException("parcel")))
        // Any throwable at all, including one that is not an exception subclass, is a call that did not come back.
        assertEquals(SHELL_REPLY_TOO_LARGE, carrierCodeFor(StackOverflowError()))
    }

    @Test
    fun `every carrier failure is distinguishable from a parse failure`() {
        assertNotEquals(SHELL_REPLY_INVALID, SHELL_REPLY_TOO_LARGE)
        assertNotEquals(SHELL_REPLY_INVALID, carrierCodeFor(RuntimeException()))
    }

    @Test
    fun `a carrier failure is reported as its own kind and never as a parse failure`() {
        // The old code produced `decode(null)` here: `SHELL_REPLY_INVALID`. Two different facts were one string, and a
        // command that had actually run was reported as garbage.
        val carrier = ShellResult("dumpsys power", -1, "", SHELL_REPLY_TOO_LARGE, failure = ShellFailure.CARRIER)
        assertEquals(ShellFailure.CARRIER, carrier.failure)
        assertNotEquals(SHELL_REPLY_INVALID, carrier.stderr)
        // Not a success in any reading: it carries no stdout and its exit code is the channel's own -1.
        assertEquals(0, carrier.stdout.length)
        assertEquals(-1, carrier.exitCode)
    }

    @Test
    fun `the failure kind is carried without parsing the marker string`() {
        // A caller should not have to compare `stderr` against constants to learn which case it has; the enum *is* the
        // answer, and `stderr` stays the human-readable marker for the logs.
        assertEquals(ShellFailure.NOT_BOUND, ShellResult("x", -1, "", "SHELL_NOT_BOUND", failure = ShellFailure.NOT_BOUND).failure)
        assertEquals(ShellFailure.PARSE, ShellResult("x", -1, "", SHELL_REPLY_INVALID, failure = ShellFailure.PARSE).failure)
        assertNull("a reply that crossed the channel carries no failure kind", ShellResult("x", 0, "ok", "").failure)
    }

    @Test
    fun `a successful reply is not a failure and is readable`() {
        val ok = ShellResult("pm list packages com.example", 0, "package:com.example\n", "")
        assertNull(ok.failure)
        assertTrue(ok.isAStateReply())
    }
}
