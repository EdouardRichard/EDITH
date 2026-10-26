package com.sheen.rokid.phone.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * 0.4 Task 8 (`D199`, spec §4.6): the CPU wake lock that keeps the detached session's KWS running while the phone's
 * screen is off.
 *
 * The platform is behind [WakeLockBridge] so these tests run on the JVM: they pin the ownership rules that a device
 * cannot cheaply prove - **one** platform acquire for one logical hold, an idempotent release, an acquisition that the
 * platform refused staying visibly *not held*, and `close` releasing. The only Android-touching half is
 * [PowerManagerWakeLockBridge], which is exercised on the phone (Task 9), not here.
 */
class WakeLockHoldTest {

    /** Records every platform call, and can be told to refuse the acquire the way a missing `WAKE_LOCK` would. */
    private class FakeWakeLockBridge : WakeLockBridge {
        val acquires = AtomicInteger()
        val releases = AtomicInteger()

        @Volatile private var held = false
        @Volatile var rejectAcquire = false
        @Volatile var failure: String? = null

        override fun acquire(): Boolean {
            acquires.incrementAndGet()
            if (rejectAcquire) {
                failure = "ACQUIRE_REFUSED"
                return false
            }
            held = true
            return true
        }

        override fun release() {
            releases.incrementAndGet()
            held = false
        }

        override fun isHeld(): Boolean = held

        override fun lastFailure(): String? = failure
    }

    /** A bridge whose `acquire` claims success while the platform reports no lock; the hold must not trust it. */
    private class LyingWakeLockBridge : WakeLockBridge {
        override fun acquire(): Boolean = true
        override fun release() = Unit
        override fun isHeld(): Boolean = false
    }

    @Test
    fun acquireIsIdempotentAndKeepsTheLockHeld() {
        val bridge = FakeWakeLockBridge()
        val hold = WakeLockHold(bridge)

        assertTrue("the first acquire must be reported as held", hold.acquire())
        assertTrue("a second acquire of a held lock is a no-op, not a second platform acquire", hold.acquire())

        assertEquals("only one platform acquire may happen for one logical hold", 1, bridge.acquires.get())
        assertTrue(hold.isHeld())
        assertNull("a successful hold carries no failure", hold.lastFailure())
    }

    @Test
    fun releaseWithoutAcquireIsSafe() {
        val bridge = FakeWakeLockBridge()
        val hold = WakeLockHold(bridge)

        hold.release()

        assertEquals("there is nothing to release before an acquire", 0, bridge.releases.get())
        assertFalse(hold.isHeld())
    }

    @Test
    fun releaseIsIdempotent() {
        val bridge = FakeWakeLockBridge()
        val hold = WakeLockHold(bridge)
        assertTrue(hold.acquire())

        hold.release()
        hold.release()

        assertEquals("a released hold must not release the platform lock twice", 1, bridge.releases.get())
        assertFalse(hold.isHeld())
    }

    @Test
    fun aRefusedAcquireReportsFalseAndIsNotHeld() {
        val bridge = FakeWakeLockBridge().apply { rejectAcquire = true }
        val hold = WakeLockHold(bridge)

        assertFalse("a platform refusal must not be reported as held", hold.acquire())

        assertFalse(hold.isHeld())
        assertEquals(1, bridge.acquires.get())
        assertEquals(0, bridge.releases.get())
        assertEquals("the bridge's reason must be kept for the evidence line",
            "ACQUIRE_REFUSED", hold.lastFailure())
    }

    @Test
    fun anAcquireThePlatformDidNotKeepIsNotHeld() {
        val hold = WakeLockHold(LyingWakeLockBridge())

        assertFalse("a bridge that says yes but holds nothing must not fake a hold", hold.acquire())

        assertFalse(hold.isHeld())
        assertEquals(WakeLockHold.ACQUIRE_FAILED, hold.lastFailure())
    }

    @Test
    fun aThrowingBridgeIsAFailureNotAHeldLock() {
        val hold = WakeLockHold(object : WakeLockBridge {
            override fun acquire(): Boolean = throw IllegalStateException("power manager died")
            override fun release() = Unit
            override fun isHeld(): Boolean = true
        })

        assertFalse(hold.acquire())
        assertFalse(hold.isHeld())
    }

    @Test
    fun aThrowingBridgeRecordsAGenericFailureReason() {
        val hold = WakeLockHold(object : WakeLockBridge {
            override fun acquire(): Boolean = throw IllegalStateException("power manager died")
            override fun release() = Unit
            override fun isHeld(): Boolean = true
        })

        assertFalse(hold.acquire())
        assertEquals("the hold's own stable reason is used when the bridge cannot give one",
            WakeLockHold.ACQUIRE_FAILED, hold.lastFailure())
    }

    @Test
    fun aFailedAcquireFollowedBySuccessClearsTheStaleReason() {
        val bridge = FakeWakeLockBridge().apply { rejectAcquire = true }
        val hold = WakeLockHold(bridge)
        assertFalse(hold.acquire())
        assertEquals("ACQUIRE_REFUSED", hold.lastFailure())

        bridge.rejectAcquire = false
        assertTrue("the retry must take the lock", hold.acquire())

        assertTrue(hold.isHeld())
        assertNull("a real hold must not keep the previous failure's reason", hold.lastFailure())
    }

    @Test
    fun aHoldWithoutAPlatformLockReleasesSafely() {
        // The same null-lock bridge shape as LOCK_UNAVAILABLE (newWakeLock threw) and POWER_MANAGER_MISSING; a
        // teardown that releases it must be a no-op, not a crash.
        val hold = WakeLockHold.fromPowerManager(null)
        assertFalse(hold.acquire())

        hold.release()
        hold.release()

        assertFalse(hold.isHeld())
        assertEquals(WakeLockHold.POWER_MANAGER_MISSING, hold.lastFailure())
    }

    @Test
    fun aThrowingPlatformReleaseDoesNotEscapeTheTeardown() {
        val hold = WakeLockHold(object : WakeLockBridge {
            private var held = false
            override fun acquire(): Boolean { held = true; return true }
            override fun release() { throw IllegalStateException("power manager died") }
            override fun isHeld(): Boolean = held
        })
        assertTrue(hold.acquire())

        hold.release()

        assertFalse("a throwing platform release must not crash the session teardown", hold.isHeld())
    }

    @Test
    fun closeReleasesAHeldLock() {
        val bridge = FakeWakeLockBridge()
        val hold = WakeLockHold(bridge)
        assertTrue(hold.acquire())

        hold.close()

        assertEquals(1, bridge.releases.get())
        assertFalse(hold.isHeld())
    }

    @Test
    fun aMissingPowerManagerIsNotAHeldLock() {
        val hold = WakeLockHold.fromPowerManager(null)

        assertFalse("no PowerManager service means no hold, never a pretended one", hold.acquire())
        assertFalse(hold.isHeld())
        assertEquals("POWER_MANAGER_MISSING", hold.lastFailure())
    }

    @Test
    fun theProductionTagNamesTheDetachedWakeLock() {
        assertEquals("edith:detached-wake", WakeLockHold.DEFAULT_TAG)
    }
}
