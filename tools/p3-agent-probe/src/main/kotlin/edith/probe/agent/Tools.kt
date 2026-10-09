package edith.probe.agent

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** One tool invocation and what the device did about it. */
data class ToolResult(val tool: String, val arguments: String, val ok: Boolean, val observation: String)

/** Runs adb and returns the exit code with the combined output. Never throws on a device-side failure. */
class Adb(private val adbPath: String, private val serial: String) {
    fun run(vararg args: String): Pair<Int, String> {
        val command = mutableListOf(adbPath, "-s", serial)
        command += args
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        val code = process.waitFor()
        return code to output.trim()
    }
}

/**
 * The tool set, grounded in what T3a actually measured as working rather than in what the platform is documented to
 * offer. Every tool here maps to a capability that returned OK on this hardware, and the one capability that could not
 * be trusted (`pm grant`, whose exit code is meaningless) is deliberately absent.
 *
 * `screen_text` and `find_and_tap` are what make this an agent rather than a macro: the model names a target in words,
 * and the tool resolves it against the live hierarchy. Blind coordinates would move the mapping decision into the
 * prompt, where a wrong guess is indistinguishable from a wrong tap.
 */
class DeviceTools(private val adb: Adb) {

    /**
     * The candidates from the most recent `find_elements`. `tap_element` taps one of these rather than searching again,
     * so the model's choice cannot be re-resolved to a different node between the two calls.
     */
    private var candidates: List<ElementRef> = emptyList()

    /** The visible text of the last dump, used by the loop's no-progress guard. */
    @Volatile
    var lastFingerprint: String = ""
        private set

    fun descriptors(): JsonArray = buildJsonArray {
        add(tool("screen_text", "Read the text currently visible on the phone screen. Returns one line per visible item.") {})
        add(
            tool(
                "find_elements",
                "List every on-screen element whose text or description contains the given string, numbered from 0. " +
                    "Call this before tapping, then choose the index you want with tap_element. Clickable elements are " +
                    "listed first.",
            ) {
                put("query", stringParam("Text to look for, e.g. WLAN or 搜索"))
            },
        )
        add(
            tool(
                "tap_element",
                "Tap one of the elements returned by the most recent find_elements, by its index. Fails if the screen " +
                    "changed since that listing.",
            ) {
                put("index", buildJsonObject { put("type", "integer"); put("description", "Index from find_elements") })
            },
        )
        add(
            tool("type_text", "Type ASCII text into the focused field. Non-ASCII input is rejected by the platform.", ) {
                put("text", stringParam("ASCII text to type"))
            },
        )
        add(
            tool("press_key", "Press a system key.", ) {
                put("key", enumParam("Key name", listOf("HOME", "BACK", "ENTER", "DEL")))
            },
        )
        add(
            tool("open_app", "Start an app by its component, e.g. com.android.settings/.Settings.") {
                put("component", stringParam("Android component name"))
            },
        )
        add(tool("wait", "Wait a moment for the screen to settle.") {
            put("seconds", buildJsonObject { put("type", "integer"); put("description", "Seconds to wait, 1-10") })
        })
        add(tool("report_done", "Report the final answer. Call this when the task is complete or cannot be completed.") {
            put("answer", stringParam("What you found, in the same language as the request"))
        })
    }

    fun execute(name: String, args: JsonObject): ToolResult {
        val rendered = args.toString()
        return try {
            when (name) {
                "screen_text" -> {
                    val (code, text) = dumpScreen()
                    if (code != 0) return ToolResult(name, rendered, false, "uiautomator failed: $text")
                    val nodes = parseUiDump(text)
                    val lines = visibleText(nodes)
                    lastFingerprint = lines.joinToString("|")
                    ToolResult(name, rendered, true, if (lines.isEmpty()) "(screen shows no readable text)" else lines.joinToString("\n"))
                }
                "find_elements" -> {
                    val query = args["query"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    if (query.isBlank()) return ToolResult(name, rendered, false, "query is empty")
                    val (code, text) = dumpScreen()
                    if (code != 0) return ToolResult(name, rendered, false, "uiautomator failed: $text")
                    val nodes = parseUiDump(text)
                    lastFingerprint = visibleText(nodes).joinToString("|")
                    candidates = findElements(nodes, query)
                    if (candidates.isEmpty()) {
                        return ToolResult(name, rendered, false, "no enabled element containing '$query' is on screen")
                    }
                    val listing = candidates.joinToString("\n") { element ->
                        "[${element.index}] \"${element.label}\" clickable=${element.clickable} centre=(${element.centerX},${element.centerY})"
                    }
                    ToolResult(name, rendered, true, listing)
                }
                "tap_element" -> {
                    val index = args["index"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
                        ?: return ToolResult(name, rendered, false, "index must be an integer")
                    val chosen = candidates.getOrNull(index)
                        ?: return ToolResult(
                            name, rendered, false,
                            "index $index is not in the last find_elements result (${candidates.size} candidates); call find_elements first",
                        )
                    // The listing may be stale by the time the model answers. Re-read and refuse rather than tapping
                    // whatever now sits at those coordinates: a blind tap at a remembered point is how a stale index
                    // silently becomes a wrong action.
                    val (code, text) = dumpScreen()
                    if (code != 0) return ToolResult(name, rendered, false, "uiautomator failed: $text")
                    val stillThere = findElements(parseUiDump(text), chosen.label).any { it.clickable == chosen.clickable }
                    if (!stillThere) {
                        return ToolResult(name, rendered, false, "the screen changed; call find_elements again before tapping")
                    }
                    val (tapCode, tapOut) = adb.run("shell", "input", "tap", "${chosen.centerX}", "${chosen.centerY}")
                    refreshFingerprint()
                    ToolResult(
                        name, rendered, tapCode == 0,
                        "tapped [${chosen.index}] \"${chosen.label}\" at (${chosen.centerX},${chosen.centerY})" +
                            if (tapCode == 0) "" else " but adb said: $tapOut",
                    )
                }
                "type_text" -> {
                    val value = args["text"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    if (value.isBlank()) return ToolResult(name, rendered, false, "text is empty")
                    if (value.any { it.code > 127 }) {
                        return ToolResult(name, rendered, false, "non-ASCII text cannot be typed by 'input text'; use ASCII")
                    }
                    val (code, out) = adb.run("shell", "input", "text", value.replace(" ", "%s"))
                    refreshFingerprint()
                    ToolResult(name, rendered, code == 0, if (code == 0) "typed \"$value\"" else "adb said: $out")
                }
                "press_key" -> {
                    val key = args["key"]?.jsonPrimitive?.contentOrNull.orEmpty().uppercase()
                    if (key !in setOf("HOME", "BACK", "ENTER", "DEL")) return ToolResult(name, rendered, false, "unsupported key '$key'")
                    val (code, out) = adb.run("shell", "input", "keyevent", "KEYCODE_$key")
                    ToolResult(name, rendered, code == 0, if (code == 0) "pressed $key" else "adb said: $out")
                }
                "open_app" -> {
                    val component = args["component"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    if (component.isBlank()) return ToolResult(name, rendered, false, "component is empty")
                    val (code, out) = adb.run("shell", "am", "start", "-n", component)
                    // `am start` exits 0 even for a missing component in some builds, so the output is part of the check.
                    val failed = code != 0 || out.contains("Error", ignoreCase = true) || out.contains("does not exist")
                    Thread.sleep(1_200)
                    refreshFingerprint()
                    ToolResult(name, rendered, !failed, out.ifBlank { "started $component" })
                }
                "wait" -> {
                    val seconds = (args["seconds"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 2).coerceIn(1, 10)
                    Thread.sleep(seconds * 1_000L)
                    ToolResult(name, rendered, true, "waited ${seconds}s")
                }
                "report_done" -> {
                    val answer = args["answer"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    ToolResult(name, rendered, true, answer)
                }
                else -> ToolResult(name, rendered, false, "unknown tool '$name'")
            }
        } catch (error: Throwable) {
            ToolResult(name, rendered, false, "tool crashed: ${error.javaClass.simpleName}: ${error.message}")
        }
    }

    private fun dumpScreen(): Pair<Int, String> {
        val (dumpCode, dumpOut) = adb.run("shell", "uiautomator", "dump", REMOTE_DUMP)
        if (dumpCode != 0) return dumpCode to dumpOut
        return adb.run("shell", "cat", REMOTE_DUMP)
    }

    /**
     * Re-reads the screen strictly **after** an action, so the loop's no-progress guard compares post-action states.
     *
     * The first attempt updated the fingerprint on every dump, including the pre-tap lookups, so three pre-tap
     * observations of one unchanged screen tripped the guard before the tap had any chance to take effect — the guard
     * fired on the wrong evidence.
     */
    private fun refreshFingerprint() {
        Thread.sleep(SETTLE_MILLIS)
        val (code, text) = dumpScreen()
        if (code == 0) lastFingerprint = visibleText(parseUiDump(text)).joinToString("|")
    }

    private fun tool(name: String, description: String, params: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) =
        buildJsonObject {
            put("type", "function")
            put("name", name)
            put("description", description)
            put("parameters", buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject { params() })
                put("required", buildJsonArray {
                    // Every parameter this probe declares is required: the model otherwise invents partial calls and the
                    // failure looks like a tool defect rather than a missing argument.
                    when (name) {
                        "find_elements" -> add(JsonPrimitive("query"))
                        "tap_element" -> add(JsonPrimitive("index"))
                        "type_text" -> add(JsonPrimitive("text"))
                        "press_key" -> add(JsonPrimitive("key"))
                        "open_app" -> add(JsonPrimitive("component"))
                        "wait" -> add(JsonPrimitive("seconds"))
                        "report_done" -> add(JsonPrimitive("answer"))
                        else -> Unit
                    }
                })
            })
        }

    private fun stringParam(description: String) = buildJsonObject {
        put("type", "string"); put("description", description)
    }

    private fun enumParam(description: String, values: List<String>) = buildJsonObject {
        put("type", "string"); put("description", description)
        put("enum", buildJsonArray { values.forEach { add(JsonPrimitive(it)) } })
    }

    private companion object {
        const val REMOTE_DUMP = "/sdcard/edith_agent_dump.xml"

        /** Long enough for a window transition on a mid-range phone; the guard depends on this being a settled read. */
        const val SETTLE_MILLIS = 900L
    }
}
