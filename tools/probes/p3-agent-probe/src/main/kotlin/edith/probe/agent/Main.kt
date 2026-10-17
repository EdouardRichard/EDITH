package edith.probe.agent

import com.sheen.rokid.model.DevelopmentProfileLoader
import com.sheen.rokid.model.ModelEndpoint
import com.sheen.rokid.storage.MemorySecretResolver
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.PrintStream
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * P3.0 agent probe: an LLM chooses tools, the tools act on a real phone, the results go back, and the loop continues
 * until the model reports it is done. This is the end-to-end check of the Agent harness — the thing the hand-run
 * capability matrix could not show, because there a person was the agent.
 *
 * Boundary, stated so the result is not over-read: the brain runs on the host JVM and the instruction layer is adb. This
 * verifies the harness pattern (tool choice, execution, observation, iteration, termination) against real hardware. It
 * does **not** verify the Android-side packaging — the App provider and the plugin — which is P3.1.
 */
private const val MAX_STEPS = 12

/** Bounded so an unresponsive model fails the run instead of looping forever on a real device. */
private const val MAX_EMPTY_TURNS = 3

/** Tools that can change the screen; only these feed the no-progress guard. */
private val ACTION_TOOLS = setOf("tap_element", "type_text", "press_key", "open_app")

/** Strict first, lenient second: real model output sometimes carries a raw newline inside a JSON string. */
private val LENIENT_JSON = Json { isLenient = true }

/** Compiled in, never passed on the command line: PowerShell 5.1 mangles a Chinese argument through CP936. */
private const val DEFAULT_TASK = "打开设置，点开搜索，输入 wifi，然后告诉我屏幕上出现了哪些和网络相关的条目。"
private const val INSTRUCTIONS =
    "You control a real Android phone through tools. Work one step at a time. Always read the screen with " +
        "screen_text before deciding what to tap, and use the exact wording you saw. Never guess coordinates. " +
        "When you have the answer, call report_done with a short summary."

fun main(args: Array<String>) {
    // Chinese must survive the console on a CP936 host.
    System.setOut(PrintStream(FileOutputStream(FileDescriptor.out), true, "UTF-8"))

    var properties: Path? = null
    var task = DEFAULT_TASK
    var serial = "2696813b"
    var adbPath = "D:/Tools/Android/Sdk/platform-tools/adb.exe"
    try {
        var index = 0
        while (index < args.size) {
            when (args[index++]) {
                "--properties" -> { require(index < args.size); properties = Path.of(args[index++]) }
                "--serial" -> { require(index < args.size); serial = args[index++] }
                "--adb" -> { require(index < args.size); adbPath = args[index++] }
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
        if (!loaded.allowSyntheticTextUpload) { println("PROBE_DISABLED"); return }
        val profile = loaded.profile ?: run { println("CONFIG_INVALID"); return }
        val endpoint = ModelEndpoint.responses(profile.baseUrl).toString()
        val http = OkHttpClient.Builder()
            .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
            .callTimeout(profile.timeoutMillis, TimeUnit.MILLISECONDS)
            .build()
        val tools = DeviceTools(Adb(adbPath, serial))
        val toolsJson = tools.descriptors()
        try {
            resolver.withSecret(profile.secret) { chars ->
                val key = String(chars)
                val report = StringBuilder()
                runAgent(http, endpoint, key, profile.model, profile.maxOutputTokens, tools, toolsJson, report)
                val hits = scanFor(key.take(6), report.toString())
                if (hits.isNotEmpty()) {
                    println("P3_AGENT_ABORT_SECRET_IN_OUTPUT hits=${hits.size} positions=${hits.joinToString(",")}")
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

private fun runAgent(
    http: OkHttpClient,
    endpoint: String,
    key: String,
    model: String,
    maxOutputTokens: Int,
    tools: DeviceTools,
    toolsJson: JsonArray,
    report: StringBuilder,
) {
    var history = JsonArray(
        listOf(buildJsonObject { put("role", "user"); put("content", DEFAULT_TASK) }),
    )
    report.append("P3_AGENT_TASK $DEFAULT_TASK\n")

    var emptyTurns = 0
    val progress = ProgressGuard(maxRepeats = 3)
    for (step in 1..MAX_STEPS) {
        val body = buildJsonObject {
            put("model", model)
            put("instructions", INSTRUCTIONS)
            put("stream", false)
            put("max_output_tokens", minOf(maxOutputTokens, 8_192))
            // Thinking off. Measured, not guessed: with the default thinking behaviour the turn came back
            // `status=incomplete reason=max_output_tokens` twice in a row — the reasoning tokens were charged against
            // `max_output_tokens` and the tool call never arrived, so the agent stalled while looking "successful".
            // Choosing a tool from a tool list is not a reasoning-heavy task, and the documented `effort: "none"` turns
            // it off. A harness that drives real devices must not spend its output budget on deliberation.
            put("reasoning", buildJsonObject { put("effort", "none") })
            // `auto` only: the endpoint answered 400 to `tool_choice: "required"` in T4, so forcing is not available.
            put("tool_choice", "auto")
            put("tools", toolsJson)
            put("input", history)
        }.toString()

        val turn = runCatching {
            val request = Request.Builder().url(endpoint)
                .header("Authorization", "Bearer $key")
                .header("Accept", "application/json")
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()
            http.newCall(request).execute().use { response -> readTurn(response.code, response.body?.string().orEmpty()) }
        }.getOrElse { error ->
            report.append("P3_AGENT_TRANSPORT_ERROR ${error.javaClass.simpleName}: ${error.message}\n")
            return
        }

        if (turn.safeCode != null) {
            report.append("P3_AGENT_MODEL_ERROR step=$step safeCode=${turn.safeCode}\n")
            return
        }
        val call = turn.toolCall
        if (call == null) {
            if (turn.message != null) {
                report.append("P3_AGENT_FINAL step=$step no_tool_call=true answer=${turn.message}\n")
                return
            }
            // Neither a tool call nor any text. The second real run ended exactly here and the loop called it a final
            // (empty) answer, silently abandoning a task that was one step from done. A truncated turn is reported with
            // its API reason and retried with a nudge; only repeated emptiness fails the run.
            emptyTurns += 1
            val why = if (turn.truncated) "status=incomplete reason=${turn.incompleteReason ?: "unknown"}" else "status=${turn.status ?: "unknown"}"
            report.append("P3_AGENT_EMPTY_TURN step=$step emptyTurns=$emptyTurns $why\n")
            if (emptyTurns >= MAX_EMPTY_TURNS) {
                report.append("P3_AGENT_FAILED step=$step reason=model_returned_nothing_repeatedly\n")
                return
            }
            history = JsonArray(
                history + buildJsonObject {
                    put("role", "user")
                    put("content", "Your previous turn produced no tool call. Continue the task and call a tool.")
                },
            )
            continue
        }

        val arguments = runCatching { Json.parseToJsonElement(call.arguments) as? JsonObject }.getOrNull()
            // Running against a real model showed a second, non-negotiable case: `report_done` came back with a raw
            // newline inside a JSON string, which strict parsing rejects. The lenient pass handles that shape, and if it
            // also fails the error is fed back so the model retries — a malformed call is a step, not a verdict.
            ?: runCatching { LENIENT_JSON.parseToJsonElement(call.arguments) as? JsonObject }.getOrNull()
        val result = if (arguments == null) {
            ToolResult(call.name, call.arguments, false, "arguments were not a JSON object; send valid JSON")
        } else {
            tools.execute(call.name, arguments)
        }
        report.append(
            "P3_AGENT_STEP step=$step tool=${result.tool} ok=${result.ok} args=${result.arguments} " +
                "observation=${result.observation.replace("\n", " | ")}\n",
        )

        // Only a *successful* report_done ends the run. The first real run ended here with a JSON error as its "answer":
        // the model had the right answer but produced malformed arguments, and treating that as completion recorded a
        // failure as a pass. A failed call now goes back to the model like any other tool error.
        if (call.name == "report_done" && result.ok) {
            report.append("P3_AGENT_DONE step=$step answer=${result.observation}\n")
            return
        }

        // The spec's no-progress guard, and the third real run showed it is load-bearing: the agent re-tapped identical
        // screens until the step limit. Only *action* tools are observed, because only they can change the screen — the
        // first attempt fed every dump in, so three pre-tap lookups tripped the guard before the tap could take effect.
        if (result.tool in ACTION_TOOLS) {
            val fingerprint = tools.lastFingerprint
            if (fingerprint.isNotBlank() && !progress.observe(fingerprint)) {
                report.append("P3_AGENT_NO_PROGRESS step=$step repeats=3 screen_unchanged=true\n")
                return
            }
        }
        history = appendToolExchange(history, turn.rawOutput, call, result.observation)
    }
    report.append("P3_AGENT_STEP_LIMIT reached=$MAX_STEPS\n")
}

/** Local copy of the p3-toolcall-probe guard; hits are positions, never content. */
private fun scanFor(prefix: String, text: String): List<String> {
    if (prefix.length < 4) return emptyList()
    val hits = mutableListOf<String>()
    var index = text.indexOf(prefix, startIndex = 0, ignoreCase = true)
    while (index >= 0) {
        hits += "offset=$index"
        index = text.indexOf(prefix, startIndex = index + 1, ignoreCase = true)
    }
    return hits
}
