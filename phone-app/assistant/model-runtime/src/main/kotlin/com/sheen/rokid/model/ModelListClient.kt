package com.sheen.rokid.model

import com.sheen.rokid.plugin.SecretHandle
import com.sheen.rokid.storage.SecretResolver
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit

/** 0.52 design §5.4: the four user-visible failure families of a model-list fetch. */
enum class ModelListError { AUTH, UNSUPPORTED, TIMEOUT, NETWORK, INVALID_RESPONSE, EMPTY }

sealed interface ModelListResult {
    data class Success(val models: List<GatewayModelSnapshot>, val truncated: Boolean) : ModelListResult
    data class Failure(val error: ModelListError) : ModelListResult
}

/** The seam the catalog refresh (Task 5/13) consumes; production talks to the gateway, tests use a fake. */
fun interface ModelListSource {
    fun fetch(baseUrl: String, secret: SecretHandle): ModelListResult
}

/**
 * 0.52 design §5.4: a blocking `GET {base}/models` client.
 *
 * It never sends a request body, never follows a redirect and never retries; only the bearer key leaves the phone.
 * The URL/key/response body are never logged. A per-call [timeoutMillis] bounds the whole exchange, and the result
 * maps to [ModelListError] exactly as the design's table requires.
 */
class ModelListClient(
    http: OkHttpClient,
    private val resolver: SecretResolver,
    private val allowLocalHttp: Boolean = false,
    private val timeoutMillis: Long = 10_000,
) : ModelListSource {
    // A redirect could carry the bearer key to another origin; a retry would duplicate a refresh the caller
    // already budgets. Both are off here even if the injected client enables them.
    private val transport = http.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .build()

    override fun fetch(baseUrl: String, secret: SecretHandle): ModelListResult {
        val endpoint = try {
            ModelEndpoint.models(baseUrl, allowLocalHttp)
        } catch (_: Exception) {
            // The base URL is validated on save; if a stored row still cannot be resolved, the design's
            // "check Base URL and network" bucket is the truthful answer.
            return ModelListResult.Failure(ModelListError.NETWORK)
        }
        val request = try {
            authorize(endpoint, secret)
        } catch (_: Exception) {
            return ModelListResult.Failure(ModelListError.AUTH)
        }
        val call = transport.newCall(request)
        call.timeout().timeout(timeoutMillis, TimeUnit.MILLISECONDS)
        return try {
            call.execute().use { response ->
                when (response.code) {
                    401, 403 -> ModelListResult.Failure(ModelListError.AUTH)
                    404, 405 -> ModelListResult.Failure(ModelListError.UNSUPPORTED)
                    in 200..299 -> response.body?.string()
                        ?.let(::parseModelList)
                        ?: ModelListResult.Failure(ModelListError.INVALID_RESPONSE)
                    else -> ModelListResult.Failure(ModelListError.INVALID_RESPONSE)
                }
            }
        } catch (_: InterruptedIOException) {
            // OkHttp's call timeout and socket timeouts both surface as InterruptedIOException.
            ModelListResult.Failure(ModelListError.TIMEOUT)
        } catch (_: IOException) {
            ModelListResult.Failure(ModelListError.NETWORK)
        }
    }

    /** Builds the bearer request inside [SecretResolver.withSecret], applying ControlledModelClient's key discipline. */
    private fun authorize(endpoint: HttpUrl, secret: SecretHandle): Request {
        var request: Request? = null
        resolver.withSecret(secret) { chars ->
            if (chars.isEmpty() || chars.any { it.code !in PRINTABLE_ASCII }) {
                throw IllegalArgumentException("Unusable model list credential")
            }
            request = Request.Builder()
                .url(endpoint)
                .header("Authorization", "Bearer ${String(chars)}")
                .header("Accept", "application/json")
                .get()
                .build()
        }
        return request ?: throw IllegalStateException("Unusable model list credential")
    }

    private companion object {
        /** The same printable-ASCII check ControlledModelClient performs before writing its bearer header. */
        val PRINTABLE_ASCII = 33..126
    }
}
