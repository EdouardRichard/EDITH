package com.sheen.rokid.transport

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class CxrBackendRaceTest {
    @Test fun closeLinearizesBeforeAuthorizationDispatchWhenItWinsFence() {
        val fence = BackendLifecycleFence()
        fence.close()
        assertNull(fence.dispatch { "connect" })
    }
    @Test fun closeWaitsForAcceptedSessionCreateThenPreventsAnyLaterConnect() {
        val fence = BackendLifecycleFence()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val accepted = Thread {
            fence.dispatch {
                entered.countDown()
                release.await(2, TimeUnit.SECONDS)
                "created-and-connected"
            }
        }
        accepted.start()
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        val closedStarted = CountDownLatch(1)
        val closedReturned = CountDownLatch(1)
        val closed = Thread { closedStarted.countDown(); fence.close(); closedReturned.countDown() }
        closed.start()
        assertTrue(closedStarted.await(2, TimeUnit.SECONDS))
        assertFalse("close must wait for the accepted SDK operation", closedReturned.await(50, TimeUnit.MILLISECONDS))
        release.countDown()
        accepted.join(2000); closed.join(2000)
        assertFalse(closed.isAlive)
        assertNull(fence.dispatch { "late-connect" })
    }
}
