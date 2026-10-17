package com.sheen.rokid.phone.model
import com.sheen.rokid.phone.platform.P3Log
import com.sheen.rokid.phone.app.HostCompositionRoot

import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

/**
 * The one production line the model request itself was missing (2026-10-09).
 *
 * Before this, a successful request was silent: `HostCompositionRoot` built its `OkHttpClient` with no interceptor, so
 * the hop between `P3_TURN start` and the first `P3_TOOL` line could only be argued from its effects - a tool the
 * model had chosen, a turn duration, a blob that grew. A refused request *was* visible (it reaches the plugin as a
 * capability error), which is exactly the asymmetry that made the successful case unprovable.
 *
 * It reports one line per HTTP attempt, and nothing else: the method, the status (or the exception's own class) and the
 * duration. **No URL, no header, no request body, no response body, no credential** - the same rule [P3Log] states, and
 * the reason the durations come from an injected clock rather than a second reading of the system clock. A retried
 * request is two attempts and therefore two lines: the transport retry is real behaviour and is not flattened here.
 */
internal class ModelHttpLog(
    private val nowNanos: () -> Long,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val startedAt = nowNanos()
        val method = chain.request().method
        return try {
            val response = chain.proceed(chain.request())
            P3Log.line("P3_HTTP method=$method status=${response.code} ms=${elapsedMillis(startedAt)}")
            response
        } catch (failure: IOException) {
            // The failure still escapes: this only makes it visible. A transport error the model client retries or
            // reports must not be swallowed by its own observability.
            P3Log.line("P3_HTTP method=$method error=${failure.javaClass.simpleName} ms=${elapsedMillis(startedAt)}")
            throw failure
        }
    }

    private fun elapsedMillis(startedAt: Long): Long = (nowNanos() - startedAt) / 1_000_000
}
