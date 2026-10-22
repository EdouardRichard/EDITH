package com.sheen.rokid.phone.plugin.smarthelper.turn

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * D49: one model request is capped by the profile's own `timeoutMillis`; the **turn's** budget is a different number
 * (it counts machine time and is shifted while a confirmation waits on the user). Pinned here because the two were the
 * same number before, and an accidental re-merge would put the human's decision window back inside the transport's
 * timeout.
 */
class SmartRequestTimeoutTest {

    @Test
    fun theProfileTimeoutBecomesThePerRequestCapInNanos() {
        assertEquals(30_000_000_000L, modelRequestTimeoutNanos(30_000L))
    }

    @Test
    fun anAbsentOrNonPositiveProfileTimeoutMeansNoSeparateCap() {
        assertEquals(0L, modelRequestTimeoutNanos(null))
        assertEquals(0L, modelRequestTimeoutNanos(-1L))
    }
}
