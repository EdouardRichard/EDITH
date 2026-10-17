package edith.probe.toolcall

import com.sheen.rokid.model.DevelopmentProfileLoader
import com.sheen.rokid.model.ModelEndpoint
import com.sheen.rokid.storage.MemorySecretResolver
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * P3.0 T4: does a `tools` array round-trip through the Responses endpoint, and does the reply carry a `function_call`?
 * P3.1 T4b `--stream`: the same request with `stream: true`, parsed by the **real** delivery decoders.
 *
 * This goes to the wire itself rather than through `ControlledModelClient`, because `ModelRequest` has no place to put a
 * `tools` array — that absence is exactly what the probe is testing the need for. The request body follows the
 * **documented** DeepSeek Responses shape (`tools: [{type, name, description, parameters}]`, `tool_choice`), because
 * P2's most expensive lesson was that a self-invented payload produced a negative result about the vendor.
 *
 * Without `--stream` it tries `tool_choice=auto` then `tool_choice=required`: `auto` asks whether the model reaches for
 * the tool on its own; `required` isolates the protocol question. With `--stream` it sends **one** `auto` request and
 * reports it as a [StreamOutcome], because the delivery path is always streaming and the wire shape of a streamed tool
 * call is the assumption under test. `required` is not retried there: the endpoint answered HTTP 400 to it in T4, so
 * the streaming run must not spend its one request discovering that again.
 *
 * The key is read from the operator's local properties file, used once, never printed, and every line of output passes
 * through [scan] before it reaches the terminal.
 */
private const val FIXED_PROMPT = "请帮我记一条笔记：明天上午十点给张老师回电话。"
private const val INSTRUCTIONS = "You are a probe. When the user asks you to remember something, call the record_note tool."

/** The tag `ResponsesDecoder` puts on every event; it is the probe's own name, never wire content. */
private const val STREAM_REQUEST_ID = "p3-t4b-stream"

fun main(args: Array<String>) {
    var properties: Path? = null
    var stream = false
    try {
        var index = 0
        while (index < args.size) {
            when (args[index++]) {
                "--properties" -> {
                    require(properties == null && index < args.size)
                    properties = Path.of(args[index++])
                }
                "--stream" -> {
                    require(!stream)
                    stream = true
                }
                else -> throw IllegalArgumentException()
            }
        }
    } catch (_: Exception) {
        println("CLI_INVALID")
        return
    }
    val path = properties ?: run { println("CONFIG_MISSING"); return }

    MemorySecretResolver().use { resolver ->
        val loaded = DevelopmentProfileLoader(resolver).load(path)
        loaded.safeCode?.let { println(it); return }
        // The properties file's own gate: the same consent switch the p2 probe honours, so a production config cannot
        // be probed by accident.
        if (!loaded.allowSyntheticTextUpload) { println("PROBE_DISABLED"); return }
        val profile = loaded.profile ?: run { println("CONFIG_INVALID"); return }
        val endpoint = ModelEndpoint.responses(profile.baseUrl)
        val http = OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .callTimeout(profile.timeoutMillis, TimeUnit.MILLISECONDS)
            .build()
        try {
            resolver.withSecret(profile.secret) { chars ->
                val key = String(chars)
                val report = if (stream) {
                    streamFields(attemptStream(http, endpoint.toString(), key, profile.model, profile.maxOutputTokens))
                } else {
                    StringBuilder().apply {
                        for (choice in listOf("auto", "required")) {
                            val outcome = attempt(http, endpoint.toString(), key, profile.model, profile.maxOutputTokens, choice)
                            append(
                                "P3_T4_ATTEMPT tool_choice=$choice httpStatus=${outcome.httpStatus} " +
                                    "sawToolCall=${outcome.sawToolCall} toolName=${outcome.toolName} safeCode=${outcome.safeCode}\n",
                            )
                        }
                    }.toString()
                }
                val hits = scan(report, key.take(6))
                if (hits.isNotEmpty()) {
                    // Position only — the guard must not become the leak.
                    println("P3_T4_ABORT_SECRET_IN_OUTPUT hits=${hits.size} positions=${hits.joinToString(",")}")
                } else if (stream) {
                    // The scan ran over the fields above; this constant is the only thing appended.
                    println("$report redaction=CLEAN")
                } else {
                    print(report)
                }
            }
        } finally {
            http.dispatcher.executorService.shutdownNow()
            http.connectionPool.evictAll()
        }
    }
}

private fun attempt(
    http: OkHttpClient,
    url: String,
    key: String,
    model: String,
    maxOutputTokens: Int,
    toolChoice: String,
): ToolCallOutcome {
    val request = Request.Builder()
        .url(url)
        .header("Authorization", "Bearer $key")
        .header("Accept", "application/json")
        .post(requestBody(model, maxOutputTokens, toolChoice, stream = false).toRequestBody("application/json".toMediaType()))
        .build()
    return runCatching {
        http.newCall(request).execute().use { response -> outcomeOf(response.code, response.body?.string().orEmpty()) }
    }.getOrElse { ToolCallOutcome(0, false, null, "TRANSPORT_${it.javaClass.simpleName.uppercase()}") }
}

/**
 * One streaming request, read and decoded the way the delivery path reads it: 8 KiB body reads into the real
 * [com.sheen.rokid.model.SseDecoder]/[com.sheen.rokid.model.ResponsesDecoder] pair (via [decodeStream]), the whole verdict reduced to a
 * [StreamOutcome]. Nothing from the wire but those fields ever leaves this function.
 */
private fun attemptStream(
    http: OkHttpClient,
    url: String,
    key: String,
    model: String,
    maxOutputTokens: Int,
): StreamOutcome {
    val request = Request.Builder()
        .url(url)
        .header("Authorization", "Bearer $key")
        .header("Accept", "text/event-stream")
        .post(requestBody(model, maxOutputTokens, "auto", stream = true).toRequestBody("application/json".toMediaType()))
        .build()
    return runCatching {
        http.newCall(request).execute().use { response ->
            val body = response.body
            // A non-200 answer is read as a status, never parsed: an error page is not a stream.
            if (response.code != 200 || body == null) return@use outcomeOf(response.code, emptyList())
            body.byteStream().use { input ->
                val buffer = ByteArray(8192)
                val chunks = generateSequence {
                    val count = input.read(buffer)
                    if (count < 0) null else buffer.copyOf(count)
                }
                outcomeOf(response.code, decodeStream(STREAM_REQUEST_ID, chunks))
            }
        }
    }.getOrElse { transportFailure(it) }
}

/**
 * The measured-good request body, shared by both modes so the streaming measurement differs from T4's by exactly
 * `stream`. The flattened `{type, name, description, parameters}` descriptor, `tool_choice=auto` and
 * `reasoning.effort=none` are measured, not chosen: with thinking on, the turn comes back
 * `incomplete/max_output_tokens` and the call never arrives, and the endpoint answered HTTP 400 to `required`.
 */
private fun requestBody(model: String, maxOutputTokens: Int, toolChoice: String, stream: Boolean): String =
    buildJsonObject {
        put("model", model)
        put("instructions", INSTRUCTIONS)
        put("stream", stream)
        put("max_output_tokens", minOf(maxOutputTokens, 256))
        put("tool_choice", toolChoice)
        put("reasoning", buildJsonObject { put("effort", "none") })
        put("tools", buildJsonArray {
            add(buildJsonObject {
                put("type", "function")
                put("name", "record_note")
                put("description", "Record a short note for the user.")
                put("parameters", buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        put("title", buildJsonObject {
                            put("type", "string"); put("description", "Short title")
                        })
                        put("body", buildJsonObject {
                            put("type", "string"); put("description", "Note body")
                        })
                    })
                    put("required", buildJsonArray { add(JsonPrimitive("title")); add(JsonPrimitive("body")) })
                })
            })
        })
        put("input", buildJsonArray {
            add(buildJsonObject { put("role", "user"); put("content", FIXED_PROMPT) })
        })
    }.toString()
