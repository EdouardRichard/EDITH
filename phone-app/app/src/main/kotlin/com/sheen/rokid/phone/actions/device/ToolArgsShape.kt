package com.sheen.rokid.phone.actions.device

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * M1 (0.26): one privacy-safe shape line per tool call.
 *
 * The acceptance needs to tell "the model chose the wrong action/kind" from "the gate refused" and from "the tool ran and
 * did nothing". That needs the shape of the arguments, never their content: a `device` ref/text, a shell command, an
 * `open` URL/package and a dialled number are all user data and must not reach logcat. This object is the single
 * formatter so a test can assert both the useful fields and the absence of the raw values.
 */
internal object ToolArgsShape {

    fun describe(tool: String, argsJson: String): String {
        val known = when (tool) {
            "device", "shell", "open", "dial" -> tool
            else -> return "P3_TOOLARG tool=unknown"
        }
        val args = runCatching { Json.parseToJsonElement(argsJson).jsonObject }.getOrNull()
            ?: return "P3_TOOLARG tool=$known shape=invalid"
        return when (known) {
            "device" -> device(args)
            "shell" -> shell(args)
            "open" -> open(args)
            else -> dial(args)
        }
    }

    private fun device(args: JsonObject): String {
        val action = args.string("action")?.let { allowed(it, DEVICE_ACTIONS) } ?: "none"
        val kind = args.string("kind")?.let { allowed(it, DEVICE_KINDS) } ?: "none"
        val filter = args.string("filter")?.let { allowed(it, DEVICE_FILTERS) } ?: "none"
        val hasRef = args.string("ref")?.isNotBlank() == true
        val textChars = args.string("text")?.length ?: 0
        return "P3_TOOLARG tool=device action=$action kind=$kind filter=$filter " +
            "hasRef=$hasRef textChars=$textChars"
    }

    private fun shell(args: JsonObject): String {
        val command = args.string("command")?.takeIf { it.isNotBlank() }
            ?: return "P3_TOOLARG tool=shell shape=invalid"
        val timeout = args.long("timeout_ms") ?: DEFAULT_SHELL_TIMEOUT_MS
        return "P3_TOOLARG tool=shell commandClass=${commandClass(command)} chars=${command.length} " +
            "hasStdin=${!args.string("stdin").isNullOrEmpty()} timeoutMs=$timeout"
    }

    private fun open(args: JsonObject): String {
        if (args.boolean("list") == true) return "P3_TOOLARG tool=open shape=list"
        val target = args.string("target")?.takeIf { it.isNotBlank() }
            ?: return "P3_TOOLARG tool=open shape=invalid"
        val targetClass = when {
            target.startsWith("https://", ignoreCase = true) -> "https"
            target.startsWith("http://", ignoreCase = true) -> "http"
            PACKAGE_NAME.matches(target) -> "package"
            else -> "label"
        }
        return "P3_TOOLARG tool=open targetClass=$targetClass chars=${target.length} " +
            "relaunch=${args.boolean("relaunch") ?: false}"
    }

    private fun dial(args: JsonObject): String {
        val number = args.string("number")?.takeIf { it.isNotBlank() }
        return "P3_TOOLARG tool=dial hasNumber=${number != null} " +
            "digits=${number?.count { it in '0'..'9' } ?: 0}"
    }

    /** Only a fixed vocabulary may name the command's class; the raw first token is never echoed. */
    private fun commandClass(command: String): String = when (command.trim().substringBefore(' ').lowercase()) {
        "input" -> "input"
        "am" -> "activity"
        "pm" -> "package"
        "settings" -> "settings"
        "dumpsys" -> "diagnostics"
        "wm" -> "window"
        "id" -> "identity"
        else -> "other"
    }

    private fun allowed(value: String, allowed: Set<String>): String =
        value.lowercase().takeIf { it in allowed } ?: "other"

    private fun JsonObject.string(key: String): String? =
        get(key)?.jsonPrimitive?.contentOrNull

    private fun JsonObject.long(key: String): Long? =
        get(key)?.jsonPrimitive?.longOrNull

    private fun JsonObject.boolean(key: String): Boolean? =
        get(key)?.jsonPrimitive?.booleanOrNull

    private val PACKAGE_NAME = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$")
    private val DEVICE_ACTIONS = setOf("observe", "act", "screenshot", "wait", "status")
    private val DEVICE_KINDS = setOf(
        "tap", "long_press", "double_tap", "swipe", "scroll", "scroll_to_text", "type", "press_key",
        "home", "back", "recents", "notifications",
    )
    private val DEVICE_FILTERS = setOf("interactive", "text", "all")
    private const val DEFAULT_SHELL_TIMEOUT_MS = 30_000L
}
