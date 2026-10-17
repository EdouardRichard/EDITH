package com.sheen.rokid.phone.model
import com.sheen.rokid.phone.platform.P3Log

import okhttp3.Call
import okhttp3.Connection
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

/**
 * The model-request hop had no production line at all: a successful request left nothing in logcat, so the one hop the
 * whole assistant depends on could only be argued from its effects (P3.1-NM, 2026-10-09). These tests pin the line that
 * closes that gap - and, just as importantly, that it carries nothing but the method, the status and the duration.
 *
 * The break they catch: an interceptor that reports nothing, one that cannot tell a failure from a success, one that
 * invents a duration, and one that leaks a response body into logcat.
 */
class ModelHttpLogTest {

    private val lines = mutableListOf<String>()
    private val clock = FakeNanos()

    @After
    fun restoreSink() {
        P3Log.sink = { line -> android.util.Log.i(P3Log.TAG, line) }
    }

    private fun interceptor(): ModelHttpLog {
        P3Log.sink = { line -> lines += line }
        return ModelHttpLog(clock::nowNanos)
    }

    @Test
    fun aSuccessfulAttemptIsReportedWithItsStatusAndDuration() {
        val target = interceptor()
        clock.nanos = 1_000_000_000
        val chain = FakeChain(request = post(), onProceed = {
            clock.nanos = 1_037_000_000
            response(200, body = "SECRET-BODY-MARKER")
        })

        target.intercept(chain).close()

        assertEquals(listOf("P3_HTTP method=POST status=200 ms=37"), lines)
        assertFalse("a response body must never reach the log: $lines",
            lines.any { it.contains("SECRET-BODY-MARKER") })
    }

    @Test
    fun aRefusedAttemptKeepsItsOwnStatusRatherThanReadingAsASuccess() {
        val target = interceptor()
        val chain = FakeChain(request = post(), onProceed = { response(429, body = "") })

        target.intercept(chain).close()

        assertEquals(listOf("P3_HTTP method=POST status=429 ms=0"), lines)
    }

    @Test
    fun aTransportFailureIsReportedAndStillEscapes() {
        val target = interceptor()
        clock.nanos = 0
        val chain = FakeChain(request = post(), onProceed = {
            clock.nanos = 5_250_000_000
            throw SocketTimeoutException("read timed out")
        })

        val thrown = assertThrows(SocketTimeoutException::class.java) { target.intercept(chain) }

        assertTrue("the failure must still reach the model client",
            thrown.message!!.contains("read timed out"))
        assertEquals(listOf("P3_HTTP method=POST error=SocketTimeoutException ms=5250"), lines)
    }

    private fun post(): Request =
        Request.Builder().url("https://example.invalid/v1/responses")
            .post("{}".toRequestBody("application/json".toMediaType())).build()

    private fun response(code: Int, body: String): Response =
        Response.Builder().request(post()).protocol(Protocol.HTTP_1_1)
            .code(code).message("test").body(body.toResponseBody("text/plain".toMediaType())).build()

    private class FakeNanos(var nanos: Long = 0L) {
        fun nowNanos(): Long = nanos
    }

    /**
     * Only [request] and [proceed] are part of what the interceptor may touch; everything else throws so an
     * interceptor that reaches for the call or the timeouts fails loudly instead of passing by accident.
     */
    private class FakeChain(
        private val request: Request,
        private val onProceed: () -> Response,
    ) : Interceptor.Chain {
        override fun request(): Request = request
        override fun proceed(request: Request): Response = onProceed()
        override fun connection(): Connection? = throw UnsupportedOperationException("not part of the contract")
        override fun call(): Call = throw UnsupportedOperationException("not part of the contract")
        override fun connectTimeoutMillis(): Int = throw UnsupportedOperationException("not part of the contract")
        override fun withConnectTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain =
            throw UnsupportedOperationException("not part of the contract")
        override fun readTimeoutMillis(): Int = throw UnsupportedOperationException("not part of the contract")
        override fun withReadTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain =
            throw UnsupportedOperationException("not part of the contract")
        override fun writeTimeoutMillis(): Int = throw UnsupportedOperationException("not part of the contract")
        override fun withWriteTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain =
            throw UnsupportedOperationException("not part of the contract")
    }
}
