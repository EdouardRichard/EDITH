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
    fun `the two exception classes in play are both carrier failures and never parse failures`() {
        // R-9 close-out review (Minor 3): the previous test here built a `ShellResult` with `failure = CARRIER` and
        // then asserted the four fields its own constructor had just set - it would have stayed green if
        // `carrierCodeFor` and `SHELL_REPLY_TOO_LARGE` were deleted from production. The mapping itself is what has to
        // be pinned, and this test drives it with **the classes that matter**:
        //
        // - `DeadObjectException` is the class the K40 actually raises (evidence §8.1): the remote logs the oversized
        //   transaction, the binder connection dies, and that is what the caller catches;
        // - `TransactionTooLargeException` is the class the old code tested for **by name** and the device *never*
        //   raised - not raising it is exactly why the named code never fired, and why a classification keyed on it
        //   answered "parse failure" for a reply that could not cross;
        // - an ordinary `RuntimeException` and an `Error` that is not an `Exception` at all: anything out of the binder
        //   call means the same thing, *this call did not deliver a reply*.
        //
        // Both are Android classes whose `Throwable`-taking constructors are reachable in a JVM unit test (checked
        // before this test was written), so the device's own classes are used rather than stand-ins.
        val witnesses = listOf<Throwable>(
            android.os.DeadObjectException("binder died"),
            android.os.TransactionTooLargeException("parcel too big"),
            RuntimeException("android.os.RemoteException"),
            StackOverflowError(),
        )
        for (witness in witnesses) {
            val name = witness.javaClass.name
            assertEquals("$name must be classified as a carrier failure", SHELL_REPLY_TOO_LARGE, carrierCodeFor(witness))
            assertNotEquals("$name must never be reported as a parse failure", SHELL_REPLY_INVALID, carrierCodeFor(witness))
        }
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

    @Test
    fun `a successful reply that wrote to stderr is refused as a state read`() {
        // R-9 close-out review: the whole point of the whitelist is the *dangerous* direction - a reply that arrived,
        // parsed, exited 0 and would therefore look like an answer, but whose `stderr` the command also wrote to.
        // `installed` would read the empty stdout as "the package is not there" and the backend would report "the
        // action did not take" for an action that may well have taken. The reply carries no `failure` kind (the bytes
        // crossed the channel and were understood), so only the empty-`stderr` half can refuse it, and the probe must
        // report the read as unreadable rather than answer.
        val noisy = ShellResult("pm list packages com.example", 0, "", "Warning: activity not started")
        assertNull("the reply crossed the channel and was understood, so it carries no failure kind", noisy.failure)
        assertFalse("a non-empty stderr is not a state reply, even on exit 0", noisy.isAStateReply())

        val state = ShizukuTargetState(
            object : PrivilegedShell {
                override fun run(command: String): ShellResult = noisy.copy(command = command)
            },
        )
        assertFalse("an unreadable reply must not be judged as 'the package is gone'", state.installed("com.example"))
        assertFalse("the read must be reported unreadable so the backend refuses instead of judging", state.readable())
    }
}
