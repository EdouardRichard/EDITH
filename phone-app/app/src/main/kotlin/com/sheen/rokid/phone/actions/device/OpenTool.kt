package com.sheen.rokid.phone.actions.device

import android.provider.Settings
import com.sheen.rokid.phone.actions.intent.AppCatalog
import com.sheen.rokid.phone.actions.intent.AppLaunchResolver
import com.sheen.rokid.phone.actions.intent.LaunchResolution
import com.sheen.rokid.phone.actions.shizuku.PrivilegedShell
import com.sheen.rokid.phone.actions.shizuku.ShellResult
import com.sheen.rokid.plugin.ActionDescriptor
import com.sheen.rokid.plugin.ActionExecutor
import com.sheen.rokid.plugin.ActionOutcome
import com.sheen.rokid.plugin.ActionResult
import com.sheen.rokid.plugin.RiskLevel
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * R43/spec §4.11: `open` — a name, package, URL or settings page, launched through the shell.
 *
 * The result is always the structured envelope:
 *  - ambiguity is `Refused` with `error.code = APP_AMBIGUOUS` and
 *    `data.candidates = [{"label":...,"packageName":...}]` — never a `Done`, and never parsed out of
 *    a `CODE:payload` detail string (R20);
 *  - an unknown name is `APP_NOT_FOUND`;
 *  - a package launch resolves its launcher component first
 *    (`cmd package resolve-activity --brief -c android.intent.category.LAUNCHER <pkg>`), then
 *    `am start -W -n <component>`; no component falls back to the launcher intent;
 *  - `relaunch=false` with the target already in front does not start it again.
 *
 * A URL is restricted to `http(s)` and single-quote-escaped before it enters the command, so a URL
 * containing a quote or a shell metacharacter stays one literal argument. The settings-page table is
 * the same ten-page allowlist `IntentActionExecutor.open_settings` uses; it is duplicated here
 * (rather than launching through the intent channel) because R43 routes `open`'s launches through
 * the shell, and the brief explicitly permits an equivalent table.
 */
class OpenTool(
    private val shell: PrivilegedShell?,
    catalog: AppCatalog,
    private val foreground: () -> String? = { null },
) : ActionExecutor {

    private val resolver = AppLaunchResolver(catalog)
    private val catalog = catalog

    override fun descriptors(): List<ActionDescriptor> = listOf(DESCRIPTOR)

    override fun cancel() = Unit

    /** The tool's own call shape (one argument object); [ActionExecutor.execute] adds the name. */
    fun execute(argsJson: String): ActionResult = execute(NAME, argsJson)

    override fun execute(name: String, argsJson: String): ActionResult {
        if (name != NAME) return ActionResult(ActionOutcome.Refused, UNKNOWN_ACTION)
        val args = parseObject(argsJson) ?: return invalidArguments()
        if (args.booleanArg("list") == true) return listApps()
        if (shell == null) return channelUnavailable()
        val target = args.stringArg("target")?.trim()?.takeIf { it.isNotEmpty() } ?: return invalidArguments()
        val relaunch = args.booleanArg("relaunch") ?: false
        val scheme = SCHEME.find(target)?.groupValues?.get(1)?.lowercase()
        if (scheme != null) {
            if (!isHttpUrl(target)) {
                return failure(
                    code = INVALID_URL,
                    message = "only http(s) URLs are accepted",
                    recoverable = false,
                )
            }
            return launch(command = "am start -W -a android.intent.action.VIEW -d '${singleQuote(target)}'")
        }
        SETTINGS_PAGES[target.lowercase()]?.let { action ->
            return launch(command = "am start -W -a $action")
        }
        return launchApp(target, relaunch)
    }

    // ── list ─────────────────────────────────────────────────────────────────

    private fun listApps(): ActionResult {
        val result = shell?.let { runSafely(it, "pm list packages -3") } ?: return channelUnavailable()
        if (result.failure != null || result.exitCode != 0) return channelUnavailable()
        val packages = result.stdout.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith(PACKAGE_PREFIX) }
            .map { it.removePrefix(PACKAGE_PREFIX).trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .toList()
        val labels = catalog.visibleApps().associate { entry -> entry.packageName to entry.label }
        val rows = packages.map { packageName ->
            val label = labels[packageName]?.takeIf { it.isNotBlank() } ?: packageName
            AppRow(label = label, packageName = packageName)
        }
        val truncated = result.truncated || rows.size > MAX_LIST_ROWS
        val visible = rows.take(MAX_LIST_ROWS)
        val data = buildJsonObject {
            put(
                "apps",
                buildJsonArray {
                    visible.forEach { row ->
                        add(
                            buildJsonObject {
                                put("label", row.label)
                                put("packageName", row.packageName)
                            },
                        )
                    }
                },
            )
            put("text", visible.joinToString("\n") { "${it.label} (${it.packageName})" })
            put("truncated", truncated)
        }
        return done(data)
    }

    // ── app launch ───────────────────────────────────────────────────────────

    private fun launchApp(target: String, relaunch: Boolean): ActionResult {
        val resolved = when {
            PACKAGE_NAME.matches(target) -> {
                val label = catalog.visibleApps().firstOrNull { it.packageName == target }?.label ?: target
                LaunchTarget(target, label)
            }
            else -> when (val resolution = resolver.resolve(target)) {
                is LaunchResolution.Found -> LaunchTarget(
                    resolution.target.packageName,
                    resolution.target.label,
                )
                is LaunchResolution.Ambiguous -> return ambiguous(resolution.candidates)
                LaunchResolution.NotFound -> return failure(
                    code = APP_NOT_FOUND,
                    message = "no installed app matches the target",
                    recoverable = true,
                )
            }
        }
        if (!relaunch && foreground() == resolved.packageName) {
            return done(
                buildJsonObject {
                    put("target", resolved.packageName)
                    put("alreadyForeground", true)
                    put("message", "already foreground")
                },
            )
        }
        val pkg = resolved.packageName
        val resolvedShell = shell ?: return channelUnavailable()
        val resolve = runSafely(
            resolvedShell,
            "cmd package resolve-activity --brief -c android.intent.category.LAUNCHER $pkg",
        ) ?: return channelUnavailable()
        if (resolve.failure != null || resolve.exitCode != 0) return channelUnavailable()
        val component = COMPONENT_LINE.findAll(resolve.stdout).lastOrNull()?.value?.trim()
        val launchCommand = if (component != null) {
            "am start -W -n $component"
        } else {
            "am start -W -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -p $pkg"
        }
        val started = launchEnvelope(
            resolvedShell,
            launchCommand,
            data = buildJsonObject {
                put("target", pkg)
                put("component", component?.let { kotlinx.serialization.json.JsonPrimitive(it) } ?: JsonNull)
            },
        )
        return started
    }

    private fun ambiguous(candidates: List<AppLaunchResolver.LaunchTarget>): ActionResult = ActionResult(
        ActionOutcome.Refused,
        toolEnvelope(
            tool = NAME,
            ok = false,
            channel = CHANNEL,
            accepted = false,
            data = buildJsonObject {
                put(
                    "candidates",
                    buildJsonArray {
                        candidates.forEach { candidate ->
                            add(
                                buildJsonObject {
                                    put("label", candidate.label)
                                    put("packageName", candidate.packageName)
                                },
                            )
                        }
                    },
                )
            },
            error = ToolFailure(
                code = APP_AMBIGUOUS,
                message = "multiple apps match the target; pick one of data.candidates",
                recoverable = true,
            ),
        ),
    )

    // ── shell helpers ────────────────────────────────────────────────────────

    /** One URL/settings launch, with the shell's own `Status: ok` as the success post-condition. */
    private fun launch(command: String): ActionResult {
        val target = shell ?: return channelUnavailable()
        return launchEnvelope(target, command)
    }

    private fun launchEnvelope(
        target: PrivilegedShell,
        command: String,
        data: kotlinx.serialization.json.JsonObject = buildJsonObject { },
    ): ActionResult {
        val result = runSafely(target, command) ?: return channelUnavailable()
        if (result.failure != null) return channelUnavailable()
        if (result.exitCode != 0) {
            return failure(STATE_UNCHANGED, "the launch command exited ${result.exitCode}", recoverable = true, outcome = ActionOutcome.Failed)
        }
        if (!result.stdout.contains(STATUS_OK)) {
            return failure(STATE_UNCHANGED, "the launch produced no success status", recoverable = true, outcome = ActionOutcome.Failed)
        }
        return done(
            buildJsonObject {
                data.forEach { (key, value) -> put(key, value) }
                put("launched", true)
            },
        )
    }

    private fun runSafely(target: PrivilegedShell, command: String): ShellResult? =
        runCatching { target.run(command) }.getOrNull()

    private fun done(data: kotlinx.serialization.json.JsonObject): ActionResult = ActionResult(
        ActionOutcome.Done,
        toolEnvelope(tool = NAME, ok = true, channel = CHANNEL, accepted = true, data = data),
    )

    private fun failure(
        code: String,
        message: String,
        recoverable: Boolean,
        outcome: ActionOutcome = ActionOutcome.Refused,
    ): ActionResult = ActionResult(
        outcome,
        toolEnvelope(
            tool = NAME,
            ok = false,
            channel = CHANNEL,
            accepted = false,
            error = ToolFailure(code, message, recoverable),
        ),
    )

    private fun channelUnavailable(): ActionResult = failure(
        code = CHANNEL_UNAVAILABLE,
        message = "the privileged shell is not usable",
        recoverable = true,
    )

    private fun invalidArguments(): ActionResult = failure(
        code = INVALID_ARGUMENTS,
        message = "open needs list=true or a target",
        recoverable = false,
    )

    private fun isHttpUrl(url: String): Boolean = try {
        val parsed = java.net.URI(url)
        (parsed.scheme?.lowercase() == "http" || parsed.scheme?.lowercase() == "https") &&
            !parsed.host.isNullOrBlank()
    } catch (_: java.net.URISyntaxException) {
        false
    }

    private fun singleQuote(value: String): String = value.replace("'", "'\\''")

    private data class LaunchTarget(val packageName: String, val label: String)

    private data class AppRow(val label: String, val packageName: String)

    companion object {
        const val NAME = "open"

        private const val CHANNEL = "shell"
        private const val MAX_LIST_ROWS = 100
        private const val PACKAGE_PREFIX = "package:"
        private const val STATUS_OK = "Status: ok"
        private const val APP_AMBIGUOUS = "APP_AMBIGUOUS"
        private const val APP_NOT_FOUND = "APP_NOT_FOUND"
        private const val STATE_UNCHANGED = "STATE_UNCHANGED"
        private const val CHANNEL_UNAVAILABLE = "CHANNEL_UNAVAILABLE"
        private const val INVALID_URL = "INVALID_URL"
        private const val INVALID_ARGUMENTS = "INVALID_ARGUMENTS"
        private const val UNKNOWN_ACTION = "UNKNOWN_ACTION"

        private val PACKAGE_NAME = Regex("[A-Za-z0-9._]+")

        private val SCHEME = Regex("^([A-Za-z][A-Za-z0-9+.-]*):")

        private val COMPONENT_LINE = Regex("""(?m)^\s*([A-Za-z0-9._]+)/([A-Za-z0-9._$]+)\s*$""")

        /** The same ten-page allowlist as `IntentActionExecutor.open_settings` (spec §4.11). */
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

        val DESCRIPTOR: ActionDescriptor = ActionDescriptor(
            name = NAME,
            title = "打开应用/页面",
            parametersJson = """{"type":"object","properties":{""" +
                """"target":{"type":"string"},"list":{"type":"boolean"},"relaunch":{"type":"boolean"}},""" +
                """"additionalProperties":false}""",
            risk = RiskLevel.L1,
            requiresConfirmation = false,
            idempotent = true,
            reversible = false,
            scope = "device",
        )
    }
}
