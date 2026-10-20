package com.sheen.rokid.phone.actions.device

import android.provider.Settings
import com.sheen.rokid.phone.actions.intent.AppCatalog
import com.sheen.rokid.phone.actions.intent.AppLaunchResolver
import com.sheen.rokid.phone.actions.intent.LaunchResolution
import com.sheen.rokid.phone.actions.shizuku.PrivilegedShell
import com.sheen.rokid.phone.actions.shizuku.ShellResult
import com.sheen.rokid.phone.platform.P3Log
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
 *  - an unknown name is `APP_NOT_FOUND`; a package-shaped token counts as a package when the catalog knows it, and
 *    after name resolution fails it is still opened by package when the shell confirms it installed (R105: the
 *    catalog is a `PackageManager` view, so a system app can be launchable and absent from it). An uninstalled one is
 *    `APP_NOT_FOUND`;
 *  - `list=true` enumerates the **launchable** set from the shell's launcher activity query (R104: user and system
 *    apps), falling back to every package when that query is unusable - never to the old third-party-only filter;
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
        val target = shell ?: return channelUnavailable()
        // R104 (user report 2026-10-20): the wearer asked for the file manager / gallery / camera / settings / music,
        // and `pm list packages -3` hid every one of them (K40: 20 third-party packages vs 346 installed). The launcher
        // activity query is the **launchable** set - 31 components on the K40, system apps included. A failed, empty or
        // unparseable reply falls back to every package, never to the third-party filter that caused the defect.
        val launcherReply = runSafely(target, LAUNCHER_QUERY)
        val launcherPackages = launcherReply
            ?.takeIf { it.failure == null && it.exitCode == 0 }
            ?.let { parseLauncherPackages(it.stdout) }
            .orEmpty()
        val packages: List<String>
        val replyTruncated: Boolean
        val catalogVia: String
        if (launcherPackages.isNotEmpty()) {
            packages = launcherPackages
            replyTruncated = launcherReply?.truncated == true
            catalogVia = "launcher"
        } else {
            val all = runSafely(target, PACKAGE_LIST_COMMAND) ?: return channelUnavailable()
            if (all.failure != null || all.exitCode != 0) return channelUnavailable()
            packages = parsePackageLines(all.stdout)
            replyTruncated = all.truncated
            catalogVia = "packages"
        }
        val labels = catalog.visibleApps().associate { entry -> entry.packageName to entry.label }
        val rows = packages.map { packageName ->
            val label = labels[packageName]?.takeIf { it.isNotBlank() } ?: packageName
            AppRow(label = label, packageName = packageName)
        }
        val truncated = replyTruncated || rows.size > MAX_LIST_ROWS
        val visible = rows.take(MAX_LIST_ROWS)
        val hasPlayer = rows.any { row ->
            PLAYER_PACKAGE.containsMatchIn(row.packageName) || PLAYER_LABEL.containsMatchIn(row.label)
        }
        val hasSettings = rows.any { row ->
            row.packageName == SETTINGS_PACKAGE || row.label.contains("设置") ||
                row.label.contains("settings", ignoreCase = true)
        }
        P3Log.line(
            "P3_TOOLCATALOG via=$catalogVia count=${packages.size} hasPlayer=$hasPlayer " +
                "hasSettings=$hasSettings truncated=$truncated",
        )
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
            // P5/T4 frozen fields: a summary the controller can judge without reading the catalogue body.
            put("catalogVia", catalogVia)
            put("catalogCount", packages.size)
            put("hasPlayer", hasPlayer)
            put("hasSettings", hasSettings)
        }
        return done(data)
    }

    /**
     * R104: one package per launchable component line (`com.pkg/.Activity` or `com.pkg/com.pkg.Activity`), deduplicated
     * in the shell's order. Headers (`priority=0 preferredOrder=0`), blanks and anything else are not components.
     */
    private fun parseLauncherPackages(stdout: String): List<String> = stdout.lineSequence()
        .mapNotNull { line ->
            val trimmed = line.trim()
            val slash = trimmed.indexOf('/')
            if (slash <= 0 || slash == trimmed.lastIndex) return@mapNotNull null
            trimmed.substring(0, slash).takeIf { PACKAGE_NAME.matches(it) }
        }
        .distinct()
        .toList()

    /** R104 fallback: every installed package (`package:<name>` lines), deduplicated in the shell's order. */
    private fun parsePackageLines(stdout: String): List<String> = stdout.lineSequence()
        .map { it.trim() }
        .filter { it.startsWith(PACKAGE_PREFIX) }
        .map { it.removePrefix(PACKAGE_PREFIX).trim() }
        .filter { it.isNotEmpty() }
        .distinct()
        .toList()

    // ── app launch ───────────────────────────────────────────────────────────

    private fun launchApp(target: String, relaunch: Boolean): ActionResult {
        // A package-shaped token is a package when the catalog actually knows that package (R43 fix): "youtube" is a
        // label, so it must fall through to name resolution. R105: when the catalog misses an installed package (the
        // system apps `pm list packages -3` already hid are the motivating case), the shell's `pm list packages
        // <target>` is the installed check before refusing - the catalog is only one view of what is installed.
        val packageShaped = PACKAGE_NAME.matches(target)
        val known = if (packageShaped) {
            catalog.visibleApps().firstOrNull { it.packageName == target }
        } else {
            null
        }
        val resolved = if (known != null) {
            LaunchTarget(known.packageName, known.label)
        } else {
            when (val resolution = resolver.resolve(target)) {
                is LaunchResolution.Found -> LaunchTarget(
                    resolution.target.packageName,
                    resolution.target.label,
                )
                is LaunchResolution.Ambiguous -> return ambiguous(resolution.candidates)
                LaunchResolution.NotFound -> {
                    if (!packageShaped) return appNotFound()
                    when (shellInstalled(target)) {
                        true -> LaunchTarget(target, target)
                        null -> return channelUnavailable()
                        false -> return appNotFound()
                    }
                }
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
        stateChanging = true,
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

    /**
     * R105: whether the shell itself reports [packageName] installed, or `null` when the probe itself could not be read.
     *
     * `pm list packages <filter>` matches substrings, so the exact `package:<name>` line is the installed answer: the
     * catalog may still miss a system app that the shell can launch. A probe that could not cross the channel is not
     * "not installed" - it is an unusable channel, and the caller says so instead of claiming the app is absent.
     */
    private fun shellInstalled(packageName: String): Boolean? {
        val target = shell ?: return null
        val result = runSafely(target, "$PACKAGE_LIST_COMMAND $packageName") ?: return null
        if (result.failure != null || result.exitCode != 0) return null
        return result.stdout.lineSequence().any { it.trim() == "$PACKAGE_PREFIX$packageName" }
    }

    // R98: every `open` call is a write, whatever its outcome — launching an app, a page or a URL changes the
    // foreground state; the declaration describes the call class, not whether the launch got to run.
    private fun done(data: kotlinx.serialization.json.JsonObject): ActionResult = ActionResult(
        ActionOutcome.Done,
        toolEnvelope(tool = NAME, ok = true, channel = CHANNEL, accepted = true, data = data),
        stateChanging = true,
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
        stateChanging = true,
    )

    private fun appNotFound(): ActionResult = failure(
        code = APP_NOT_FOUND,
        message = "no installed app matches the target",
        recoverable = true,
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

        /** R104: the launchable set - one component per line, user and system apps alike. */
        private const val LAUNCHER_QUERY =
            "cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.LAUNCHER"

        /** R104 fallback: every installed package, never the third-party-only `-3` filter that hid system apps. */
        private const val PACKAGE_LIST_COMMAND = "pm list packages"

        private const val STATUS_OK = "Status: ok"
        private const val APP_AMBIGUOUS = "APP_AMBIGUOUS"
        private const val APP_NOT_FOUND = "APP_NOT_FOUND"
        private const val STATE_UNCHANGED = "STATE_UNCHANGED"
        private const val CHANNEL_UNAVAILABLE = "CHANNEL_UNAVAILABLE"
        private const val INVALID_URL = "INVALID_URL"
        private const val INVALID_ARGUMENTS = "INVALID_ARGUMENTS"
        private const val UNKNOWN_ACTION = "UNKNOWN_ACTION"

        private val PACKAGE_NAME = Regex("[A-Za-z0-9._]+")
        private val PLAYER_PACKAGE = Regex("(?i)player|music|video|media")
        private val PLAYER_LABEL = Regex("(?i)音乐|播放器|视频|music|player|video")
        private const val SETTINGS_PACKAGE = "com.android.settings"

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
            title = "打开应用、http(s) 页面或设置页",
            parametersJson = """{"type":"object","properties":{""" +
                """"target":{"type":"string","description":"应用名/包名、http(s) URL、或设置页别名（wifi/bluetooth/apps/display/sound/battery 等）。用户说类别词（如“文件”）时保持原词，不要自行扩成某个具体应用；歧义由工具返回候选。不支持 file://、content://、裸本地路径。"},""" +
                """"list":{"type":"boolean","description":"true 时列出可启动应用，不执行打开。"},""" +
                """"relaunch":{"type":"boolean"}},"additionalProperties":false}""",
            risk = RiskLevel.L1,
            requiresConfirmation = false,
            idempotent = true,
            reversible = false,
            scope = "device",
        )
    }
}
