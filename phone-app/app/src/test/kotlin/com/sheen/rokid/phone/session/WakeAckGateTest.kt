package com.sheen.rokid.phone.session

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeAckGateTest {

    @Test
    fun audibleAndItsTailAreSuppressedButNotForever() {
        val gate = WakeAckGate(tailNanos = 500_000_000L)
        gate.started()
        assertTrue("while audible", gate.shouldSuppress(1_000L))

        gate.finished(2_000_000_000L)
        assertTrue("inside the tail", gate.shouldSuppress(2_000_000_001L))
        assertTrue("last nanosecond of the tail", gate.shouldSuppress(2_499_999_999L))
        assertFalse("after the real end + tail", gate.shouldSuppress(2_500_000_000L))
    }

    @Test
    fun anAckThatNeverBecameAudibleArmsNothing() {
        val gate = WakeAckGate()
        gate.finished(5_000L)
        assertFalse("nothing played, so nothing can be its echo", gate.shouldSuppress(5_001L))
    }

    @Test
    fun aBackwardsClockFailsOpenInsteadOfMutingForever() {
        val gate = WakeAckGate()
        gate.started()
        gate.finished(2_000L)
        assertFalse("a non-monotonic sample must not extend the quiet",
            gate.shouldSuppress(1_000L))
    }

    @Test
    fun resetForgetsThePreviousAck() {
        val gate = WakeAckGate()
        gate.started()
        gate.finished(2_000L)
        assertTrue(gate.shouldSuppress(2_001L))
        gate.reset()
        assertTrue(!gate.shouldSuppress(2_002L))
    }
}
