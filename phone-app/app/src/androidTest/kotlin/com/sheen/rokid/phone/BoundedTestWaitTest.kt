package com.sheen.rokid.phone

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask

class BoundedTestWaitTest {
    @Test fun stalledOperationFailsBeforeItEventuallyReturns() {
        val release = CountDownLatch(1)
        val task = FutureTask { release.await(); "late result" }
        val worker = Thread(task).apply { start() }
        val releaser = Thread { Thread.sleep(250); release.countDown() }.apply { start() }
        try {
            val failure = assertThrows(AssertionError::class.java) {
                awaitTestTask(task, "foreground launch", 20)
            }
            assertTrue(failure.message.orEmpty().contains("foreground launch"))
            assertTrue("timed-out work must be cancelled", task.isCancelled)
        } finally {
            release.countDown()
            worker.join(1_000)
            releaser.join(1_000)
            assertFalse(worker.isAlive)
            assertFalse(releaser.isAlive)
        }
    }

    @Test fun completedOperationReturnsItsValue() {
        val task = FutureTask { "shell output" }.apply { run() }
        assertEquals("shell output", awaitTestTask(task, "shell", 20))
    }

    @Test fun operationAssertionIsPropagatedUnchanged() {
        val expected = AssertionError("service assertion")
        val task = FutureTask<String> { throw expected }.apply { run() }
        assertSame(expected, assertThrows(AssertionError::class.java) {
            awaitTestTask(task, "Activity action", 20)
        })
    }

    @Test fun queuedTimedOutActionCannotRunLater() {
        var invoked = false
        val task = FutureTask { invoked = true }
        assertThrows(AssertionError::class.java) { awaitTestTask(task, "queued Activity action", 20) }
        task.run()
        assertFalse(invoked)
    }
}
