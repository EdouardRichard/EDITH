package com.sheen.rokid.phone

import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

internal fun <T> awaitTestTask(task: FutureTask<T>, operation: String, timeoutMillis: Long = 10_000): T {
    try {
        return task.get(timeoutMillis, TimeUnit.MILLISECONDS)
    } catch (failure: TimeoutException) {
        task.cancel(false)
        throw AssertionError("$operation did not complete within $timeoutMillis ms", failure)
    } catch (failure: ExecutionException) {
        throw requireNotNull(failure.cause)
    } catch (failure: InterruptedException) {
        task.cancel(false)
        Thread.currentThread().interrupt()
        throw AssertionError("$operation was interrupted", failure)
    }
}
