package com.sheen.rokid.phone.actions
import com.sheen.rokid.phone.storage.unavailable

import android.content.Intent
import android.provider.AlarmClock
import android.provider.Settings
import com.sheen.rokid.plugin.ActionDescriptor
import com.sheen.rokid.plugin.ActionExecutor
import com.sheen.rokid.plugin.ActionOutcome
import com.sheen.rokid.plugin.ActionResult
import com.sheen.rokid.plugin.RiskLevel
import com.sheen.rokid.plugin.StorageHandle
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import java.util.concurrent.atomic.AtomicLong

private const val OPEN_APP = "open_app"
private const val OPEN_URL = "open_url"
private const val OPEN_SETTINGS = "open_settings"
private const val SET_ALARM = "set_alarm"
private const val COMPOSE_NOTE = "compose_note"

/** P3.3 Task 9: the phase's one L2 action, placed through a standard intent but only with the user's own permission. */
private const val DIAL = "dial"

private const val ARGUMENTS_INVALID = "ARGUMENTS_INVALID"
private const val UNKNOWN_ACTION = "UNKNOWN_ACTION"
private const val APP_NOT_VISIBLE = "APP_NOT_VISIBLE"
private const val LAUNCH_REFUSED = "LAUNCH_REFUSED"
private const val INVALID_URL = "INVALID_URL"
private const val INVALID_SETTINGS_PAGE = "INVALID_SETTINGS_PAGE"
private const val INVALID_TIME = "INVALID_TIME"
private const val STORAGE_UNAVAILABLE = "STORAGE_UNAVAILABLE"
private const val INVALID_NOTE = "INVALID_NOTE"
private const val CALL_PERMISSION_MISSING = "CALL_PERMISSION_MISSING"

/**
 * A dialable number and nothing else. Same rule as the privileged channel's package name: the model's string is
 * refused unless it is exactly a number, so no part of it can become an intent payload we did not intend.
 */
private val DIALABLE_NUMBER = Regex("""\+?[0-9*#]{3,20}""")

private const val UNTITLED = "untitled"

/** The argument names, also the descriptor schema's keys. */
private const val NAME = "name"
private const val URL = "url"
private const val PAGE = "page"
private const val HOUR = "hour"
private const val MINUTE = "minute"
private const val LABEL = "label"
private const val TITLE = "title"
private const val BODY = "body"
private const val NUMBER = "number"

/**
 * Lenient on purpose, exactly like `agent-runtime`'s arguments pass: the model's tool call is the input, and kotlinx
 * only accepts some member forms (unquoted values) in lenient mode. Structural checks below are still strict - a
 * missing member, a wrong type or a non-object payload is refused, never coerced.
 */
private val argumentsJson = Json { isLenient = true }

/**
 * The allowlist of `open_settings` pages, mapped to the platform settings actions.
 *
 * Only these ten names are accepted; anything else is refused with `INVALID_SETTINGS_PAGE` rather than forwarded as a
 * guessed action string, so the model cannot hand us an arbitrary `Settings.ACTION_*` (several of which manage
 * security-relevant access, e.g. unknown sources or accessibility service grants).
 *
 * `notifications` uses `ACTION_ALL_APPS_NOTIFICATION_SETTINGS` (API 30+), the notifications page itself; the older
 * notification-listener pages grant notification access and are deliberately not offered. On a device without that
 * activity the launch is refused with `LAUNCH_REFUSED`, which is honest.
 */
private val SETTINGS_PAGES: Map<String, String> = mapOf(
    "wifi" to Settings.ACTION_WIFI_SETTINGS,
    "bluetooth" to Settings.ACTION_BLUETOOTH_SETTINGS,
    "apps" to Settings.ACTION_APPLICATION_SETTINGS,
    "display" to Settings.ACTION_DISPLAY_SETTINGS,
    "sound" to Settings.ACTION_SOUND_SETTINGS,
    "battery" to Settings.ACTION_BATTERY_SAVER_SETTINGS,
    "location" to Settings.ACTION_LOCATION_SOURCE_SETTINGS,
    "notifications" to Settings.ACTION_ALL_APPS_NOTIFICATION_SETTINGS,
    "accessibility" to Settings.ACTION_ACCESSIBILITY_SETTINGS,
    "data_usage" to Settings.ACTION_DATA_USAGE_SETTINGS,
)

/** The exact five L0/L1 declarations; `agent-runtime` projects `title` to the model-visible description. */
private val DESCRIPTORS: List<ActionDescriptor> = listOf(
    ActionDescriptor(
        name = OPEN_APP,
        title = "打开 App",
        parametersJson = """{"type":"object","properties":{"name":{"type":"string","description":"App 名称"}},""" +
            """"required":["name"],"additionalProperties":false}""",
        risk = RiskLevel.L0,
        requiresConfirmation = false,
        idempotent = true,
        reversible = false,
        scope = "device",
    ),
    ActionDescriptor(
        name = OPEN_URL,
        title = "打开网页",
        parametersJson = """{"type":"object","properties":{"url":{"type":"string","description":"http 或 https 链接"}},""" +
            """"required":["url"],"additionalProperties":false}""",
        risk = RiskLevel.L0,
        requiresConfirmation = false,
        idempotent = true,
        reversible = false,
        scope = "device",
    ),
    ActionDescriptor(
        name = OPEN_SETTINGS,
        title = "打开系统设置",
        parametersJson = """{"type":"object","properties":{"page":{"type":"string","enum":[""" +
            SETTINGS_PAGES.keys.joinToString(",") { "\"$it\"" } +
            """]}},"required":["page"],"additionalProperties":false}""",
        risk = RiskLevel.L0,
        requiresConfirmation = false,
        idempotent = true,
        reversible = false,
        scope = "device",
    ),
    ActionDescriptor(
        name = SET_ALARM,
        title = "设置闹钟",
        parametersJson = """{"type":"object","properties":{"hour":{"type":"integer","minimum":0,"maximum":23},""" +
            """"minute":{"type":"integer","minimum":0,"maximum":59},"label":{"type":"string"}},""" +
            """"required":["hour","minute","label"],"additionalProperties":false}""",
        risk = RiskLevel.L1,
        requiresConfirmation = false,
        idempotent = false,
        reversible = false,
        scope = "local",
    ),
    ActionDescriptor(
        name = COMPOSE_NOTE,
        title = "记一条笔记",
        parametersJson = """{"type":"object","properties":{"title":{"type":"string"},"body":{"type":"string"}},""" +
            """"required":["title","body"],"additionalProperties":false}""",
        risk = RiskLevel.L1,
        requiresConfirmation = false,
        idempotent = false,
        reversible = false,
        scope = "local",
    ),
    ActionDescriptor(
        name = DIAL,
        title = "拨打电话",
        parametersJson = """{"type":"object","properties":{"number":{"type":"string","description":"电话号码"}},""" +
            """"required":["number"],"additionalProperties":false}""",
        risk = RiskLevel.L2,
        requiresConfirmation = false,
        idempotent = false,
        reversible = false,
        scope = "phone.number",
        // Deliberately null: a phone number is personal data, so no part of it ever reaches the audit trail
        // (spec §17.5). The line still records the action, its scope and its outcome.
        auditTargetArgument = null,
    ),
)

/**
 * The App-side L0/L1 action provider (P3.1 Task 12): the first real, bounded actions the Agent may take on the phone,
 * through standard Android intents plus the App's own encrypted storage.
 *
 * Scope is a product boundary, not a style choice. Only these five exist, all L0/L1, all `requiresConfirmation =
 * false`; nothing here sends messages, dials, pays, deletes, uninstalls or changes security settings - L2+ belongs to
 * P3.3 with Shizuku/accessibility/Root. `open_settings` is therefore an allowlist of benign pages, never a passthrough
 * of a model-chosen action string.
 *
 * Failure policy, which the registry depends on:
 * - a refusal is [ActionOutcome.Refused] with the code **exactly** as its detail, so the model sees
 *   `"Refused: <CODE>"` rather than a broken-tool failure;
 * - nothing throws: malformed arguments, an unknown tool name, a missing capability and a declining launcher all
 *   become refusals;
 * - a refusal performs no side effect.
 *
 * [noteStorage] is the `smart-assistant` namespace the composition hands in; `null` means storage is not wired, which
 * makes `compose_note` honestly refuse with `STORAGE_UNAVAILABLE`.
 */
/**
 * The App-side action provider: the five L0/L1 tools P3.1 delivered, plus P3.3's one L2 action, through standard
 * Android intents and the App's own encrypted storage.
 *
 * Scope is a product boundary, not a style choice. L0/L1 are the levels the user has already accepted outright; `dial`
 * is the first action that reaches another person, so it is L2 and the policy layer asks before it runs. Nothing here
 * sends messages, deletes data, uninstalls, changes permissions or changes security settings - L3/L4 belong to the
 * privileged channel. `open_settings` is therefore an allowlist of benign pages, never a passthrough of a model-chosen
 * action string, and `dial` refuses without `CALL_PHONE` rather than silently opening the dialer instead.
 *
 * Failure policy, which the registry depends on:
 * - a refusal is [ActionOutcome.Refused] with the code **exactly** as its detail, so the model sees
 *   `"Refused: <CODE>"` rather than a broken-tool failure;
 * - nothing throws: malformed arguments, an unknown tool name, a missing capability and a declining launcher all
 *   become refusals;
 * - a refusal performs no side effect.
 *
 * [noteStorage] is the `smart-assistant` namespace the composition hands in; `null` means storage is not wired, which
 * makes `compose_note` honestly refuse with `STORAGE_UNAVAILABLE`.
 */
class IntentActionExecutor(
    private val resolver: AppLaunchResolver,
    private val launcher: IntentLauncher,
    private val noteStorage: StorageHandle?,
    /** P3.3 Task 9: denies by default, so an unwired app refuses to dial instead of pretending it may. */
    private val callPermission: CallPermission = CallPermission { false },
) : ActionExecutor {

    /** Makes note keys unique even when two notes share a title inside the same millisecond. */
    private val noteSequence = AtomicLong()

    override fun descriptors(): List<ActionDescriptor> = DESCRIPTORS

    override fun execute(name: String, argsJson: String): ActionResult = when (name) {
        OPEN_APP -> openApp(argsJson)
        OPEN_URL -> openUrl(argsJson)
        OPEN_SETTINGS -> openSettings(argsJson)
        SET_ALARM -> setAlarm(argsJson)
        COMPOSE_NOTE -> composeNote(argsJson)
        DIAL -> dial(argsJson)
        else -> refused(UNKNOWN_ACTION)
    }

    /**
     * A no-op, and documented as one: every action here is a synchronous intent hand-off or a synchronous storage
     * write, so by the time `execute` returns there is nothing left to cancel.
     */
    override fun cancel() = Unit

    private fun openApp(argsJson: String): ActionResult {
        val args = objectArguments(argsJson) ?: return refused(ARGUMENTS_INVALID)
        val name = args.string(NAME) ?: return refused(ARGUMENTS_INVALID)
        val resolution = resolver.resolve(name)
        val target = (resolution as? LaunchResolution.Found)?.target ?: return refused(APP_NOT_VISIBLE)
        val launched = launcher.launch(Intent.ACTION_MAIN, PACKAGE_URI_SCHEME + target.packageName, emptyMap())
        return if (launched) done("opened ${target.label}") else refused(LAUNCH_REFUSED)
    }

    private fun openUrl(argsJson: String): ActionResult {
        val args = objectArguments(argsJson) ?: return refused(ARGUMENTS_INVALID)
        val url = (args.string(URL) ?: return refused(ARGUMENTS_INVALID)).trim()
        if (!isHttpUrl(url)) return refused(INVALID_URL)
        val launched = launcher.launch(Intent.ACTION_VIEW, url, emptyMap())
        return if (launched) done("opened $url") else refused(LAUNCH_REFUSED)
    }

    private fun openSettings(argsJson: String): ActionResult {
        val args = objectArguments(argsJson) ?: return refused(ARGUMENTS_INVALID)
        val page = (args.string(PAGE) ?: return refused(ARGUMENTS_INVALID)).trim().lowercase()
        val action = SETTINGS_PAGES[page] ?: return refused(INVALID_SETTINGS_PAGE)
        val launched = launcher.launch(action, null, emptyMap())
        return if (launched) done("opened settings: $page") else refused(LAUNCH_REFUSED)
    }

    private fun setAlarm(argsJson: String): ActionResult {
        val args = objectArguments(argsJson) ?: return refused(ARGUMENTS_INVALID)
        val hour = args.integer(HOUR) ?: return refused(ARGUMENTS_INVALID)
        val minute = args.integer(MINUTE) ?: return refused(ARGUMENTS_INVALID)
        val label = args.string(LABEL) ?: return refused(ARGUMENTS_INVALID)
        if (hour !in 0..23 || minute !in 0..59 || label.isBlank()) return refused(INVALID_TIME)
        // EXTRA_SKIP_UI is deliberately not set: the clock app may still show its own pre-filled screen, and a
        // confirmation the system wants is not ours to suppress.
        val extras = mapOf(
            AlarmClock.EXTRA_HOUR to hour,
            AlarmClock.EXTRA_MINUTES to minute,
            AlarmClock.EXTRA_MESSAGE to label.trim(),
        )
        val launched = launcher.launch(AlarmClock.ACTION_SET_ALARM, null, extras)
        return if (launched) done("alarm set: %02d:%02d %s".format(hour, minute, label.trim()))
        else refused(LAUNCH_REFUSED)
    }

    /**
     * P3.3 Task 9: a direct call, and **only** a direct call.
     *
     * Without `CALL_PHONE` this refuses with `CALL_PERMISSION_MISSING`. It deliberately does not fall back to
     * `ACTION_DIAL`: a dialer screen is a different action with a different meaning ("prepare a call") and quietly
     * substituting it would make the model's step look done when nothing was placed. The number itself is validated
     * against [DIALABLE_NUMBER] before it becomes an intent payload.
     */
    private fun dial(argsJson: String): ActionResult {
        val args = objectArguments(argsJson) ?: return refused(ARGUMENTS_INVALID)
        val number = (args.string(NUMBER) ?: return refused(ARGUMENTS_INVALID)).trim()
        if (!DIALABLE_NUMBER.matches(number)) return refused(ARGUMENTS_INVALID)
        if (!callPermission.granted()) return refused(CALL_PERMISSION_MISSING)
        val launched = launcher.launch(Intent.ACTION_CALL, "tel:$number", emptyMap())
        return if (launched) done("dialing $number") else refused(LAUNCH_REFUSED)
    }

    private fun composeNote(argsJson: String): ActionResult {
        val args = objectArguments(argsJson) ?: return refused(ARGUMENTS_INVALID)
        val title = args.string(TITLE) ?: return refused(ARGUMENTS_INVALID)
        val body = args.string(BODY) ?: return refused(ARGUMENTS_INVALID)
        val storage = noteStorage ?: return refused(STORAGE_UNAVAILABLE)
        if (title.isBlank() && body.isBlank()) return refused(INVALID_NOTE)
        return try {
            storage.write(noteKey(title), (title + "\n" + body).toByteArray(Charsets.UTF_8))
            done("note written: ${title.ifBlank { UNTITLED }}")
        } catch (unavailable: Exception) {
            // Storage that was handed out but cannot be written is still a storage refusal; the listed code is used
            // rather than an unlisted one, and nothing is thrown at the registry.
            android.util.Log.i("EdithActions",
                "STORAGE_UNAVAILABLE: ${unavailable.javaClass.simpleName}: ${unavailable.message}")
            refused(STORAGE_UNAVAILABLE)
        }
    }

    /**
     * A storage key derived from the title, a timestamp and a sequence number.
     *
     * The `smart-assistant` namespace validates keys against `[A-Za-z0-9_.-]{1,64}`, so the title is reduced to that
     * alphabet (a fully non-ASCII title becomes `untitled`; the note's own text keeps the real title). The sequence
     * number is what keeps the declaration `idempotent = false` true: two notes with the same title must not
     * overwrite each other.
     */
    private fun noteKey(title: String): String {
        val slug = title.trim().lowercase()
            .map { if (it in 'a'..'z' || it in '0'..'9' || it == '_' || it == '.' || it == '-') it else '-' }
            .joinToString("")
            .trim('-')
            .replace(Regex("-{2,}"), "-")
            .take(20)
            .ifBlank { UNTITLED }
        return "note-$slug-${System.currentTimeMillis()}-${noteSequence.incrementAndGet()}"
    }

    private fun isHttpUrl(url: String): Boolean = try {
        val parsed = java.net.URI(url)
        (parsed.scheme?.lowercase() == "http" || parsed.scheme?.lowercase() == "https") && !parsed.host.isNullOrBlank()
    } catch (_: java.net.URISyntaxException) {
        false
    }

    private fun objectArguments(argsJson: String): JsonObject? =
        runCatching { argumentsJson.parseToJsonElement(argsJson) as? JsonObject }.getOrNull()

    /** A member that must be present **and** a JSON string; a number, a null or a missing member is not a name. */
    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    /** A member that must be present **and** a JSON number; a quoted `"8"` is a wrong type, not an 8. */
    private fun JsonObject.integer(key: String): Int? =
        (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull

    private fun done(detail: String) = ActionResult(ActionOutcome.Done, detail)
    private fun refused(code: String) = ActionResult(ActionOutcome.Refused, code)
}
