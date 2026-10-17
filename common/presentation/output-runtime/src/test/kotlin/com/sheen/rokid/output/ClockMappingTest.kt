package com.sheen.rokid.output

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * T18 clock mapping between the phone and glasses domains.
 *
 * The wire exchange is a four-timestamp handshake `(p0, g1, g2, p3)`: the phone sends `p0`, the glasses
 * stamp their receive `g1` and send `g2`, the phone stamps its receive `p3`. The phone/glasses offset `G-P`
 * is therefore bounded by `[g2 - p3, g1 - p0]`, and a hint's local expiry must use the pessimistic end of
 * that interval so a delayed SHOW can never extend a deadline the glasses already passed.
 *
 * Phone absolute nanoseconds must never be handed to the glasses as a clock: only a mapping with a `syncId`
 * crosses the boundary.
 */
class ClockMappingTest {
    @Test fun delayedShowCannotExtendDeadline() {
        val mapping = ClockMapping.fromExchange("x", 0, 10_000_000, 11_000_000, 2_000_000)
        val expiry = mapping.earliestGlassExpiry(0, 100)
        assertTrue(expiry <= 109_000_000, "expiry $expiry must not exceed the pessimistic bound")
    }

    @Test fun offsetIntervalComesFromTheFourTimestamps() {
        val mapping = ClockMapping.fromExchange("s", 0, 10_000_000, 11_000_000, 2_000_000)
        // offsetLower = g2 - p3 = 9_000_000, offsetUpper = g1 - p0 = 10_000_000
        assertEquals(9_000_000L, mapping.offsetLowerNanos)
        assertEquals(10_000_000L, mapping.offsetUpperNanos)
        assertEquals("s", mapping.syncId)
        assertTrue(mapping.offsetUpperNanos >= mapping.offsetLowerNanos)
    }

    @Test fun aMappingExpiresThirtySecondsAfterItsExchange() {
        // p3 = 1_000_000 means the exchange completed 1 ms into the phone's clock, so validity (30 s measured
        // from completion) closes at 1_000_000 + 30_000_000_000 = 30_999_000_000... precisely: established +
        // 30_000_000_000 = 31_000_000_000 is the first expired instant.
        val mapping = ClockMapping.fromExchange("s", 0, 10_000_000, 11_000_000, 1_000_000)
        val closesAt = mapping.establishedAtPhoneNanos + ClockMapping.VALIDITY_NANOS
        assertEquals(1_000_000L, mapping.establishedAtPhoneNanos)
        assertFalse(mapping.isExpired(1_000_000L), "usable immediately after the exchange")
        assertFalse(mapping.isExpired(closesAt - 1), "one nanosecond before the window closes")
        assertTrue(mapping.isExpired(closesAt), "a mapping older than 30 s is no longer usable")
    }

    @Test fun anExchangeThatCompletesAtZeroStillGetsTheFullWindow() {
        // Explicit frame check with p3 = 0: the window must close at exactly 30 s.
        val mapping = ClockMapping.fromExchange("s", 0, 10_000_000, 10_000_000, 0)
        assertEquals(0L, mapping.establishedAtPhoneNanos)
        assertFalse(mapping.isExpired(30_000_000_000L - 1))
        assertTrue(mapping.isExpired(30_000_000_000L))
    }

    @Test fun theRoundTripDurationDoesNotEatIntoTheWindow() {
        // A 5 s round trip (p0 = 0, p3 = 5 s) with a 5 ms offset interval: measuring validity from the
        // exchange's start instead of its completion would expire the mapping 5 s early.
        val mapping = ClockMapping.fromExchange("s", 0, 20_000_000, 5_015_000_000L, 5_000_000_000L)
        assertEquals(5_000_000_000L, mapping.establishedAtPhoneNanos)
        // offsetLower = g2 - p3 = 15_000_000, offsetUpper = g1 - p0 = 20_000_000.
        assertEquals(15_000_000L, mapping.offsetLowerNanos)
        assertEquals(20_000_000L, mapping.offsetUpperNanos)
        assertEquals(5_000_000L, mapping.offsetUpperNanos - mapping.offsetLowerNanos)
        val closesAt = mapping.establishedAtPhoneNanos + ClockMapping.VALIDITY_NANOS
        assertFalse(mapping.isExpired(closesAt - 1), "still usable just before 30 s after completion")
        assertTrue(mapping.isExpired(closesAt), "expired 30 s after completion")
    }

    @Test fun aMappingWiderThanTheInitialAllowanceIsRefused() {
        // 200 ms wide interval, over the 100 ms startup allowance.
        assertFailsWith<IllegalArgumentException> {
            ClockMapping.fromExchange("s", 0, 200_000_000, 1_000_000, 1_000_000)
        }
    }

    @Test fun anInconsistentExchangeIsRefusedInsteadOfProducingANegativeInterval() {
        // g2 - p3 must not exceed g1 - p0: that means the stamps cannot describe one round trip.
        assertFailsWith<IllegalArgumentException> {
            ClockMapping.fromExchange("s", 0, 1_000_000, 50_000_000, 2_000_000)
        }
    }

    @Test fun theDriftMarginIsCarriedIntoTheExpiry() {
        val mapping = ClockMapping.fromExchange("s", 0, 10_000_000, 11_000_000, 2_000_000)
        val withMargin = mapping.earliestGlassExpiry(0, 100)
        val wider = mapping.earliestGlassExpiry(0, 100, driftMarginNanos = 20_000_000)
        assertTrue(wider < withMargin, "a larger drift margin must expire earlier, not later")
    }

    @Test fun aNonPositiveTtlIsRejected() {
        val mapping = ClockMapping.fromExchange("s", 0, 10_000_000, 11_000_000, 2_000_000)
        assertFailsWith<IllegalArgumentException> { mapping.earliestGlassExpiry(0, 0) }
        assertFailsWith<IllegalArgumentException> { mapping.earliestGlassExpiry(-1, 100) }
    }

    @Test fun aBlankSyncIdIsRejected() {
        assertFailsWith<IllegalArgumentException> {
            ClockMapping.fromExchange(" ", 0, 10_000_000, 11_000_000, 2_000_000)
        }
    }
}
