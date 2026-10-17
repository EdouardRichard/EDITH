package com.sheen.rokid.phone.actions.intent
import kotlinx.serialization.json.JsonObject
import com.sheen.rokid.phone.R
import com.sheen.rokid.phone.actions.shizuku.isCallComplete
import com.sheen.rokid.phone.actions.shizuku.PrivilegedShell
import com.sheen.rokid.phone.actions.ExecutionBackend
import com.sheen.rokid.phone.actions.shizuku.ShizukuExecutionBackend
import com.sheen.rokid.plugin.ActionDescriptor
import com.sheen.rokid.plugin.ActionOutcome
import com.sheen.rokid.plugin.ActionResult
import com.sheen.rokid.plugin.RiskLevel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

private const val LAUNCH_APP = "launch_app"
private const val LIST_APPS = "list_apps"

private const val ARGUMENTS_INVALID = "ARGUMENTS_INVALID"
private const val UNKNOWN_ACTION = "UNKNOWN_ACTION"
private const val APP_NOT_FOUND = "APP_NOT_FOUND"
private const val STATE_UNCHANGED = "STATE_UNCHANGED"
private const val ACTION_CHANNEL_UNAVAILABLE = "ACTION_CHANNEL_UNAVAILABLE"

/** Hard ceiling on list_apps output so a device with hundreds of packages does not flood the model's context. */
private const val MAX_LIST_ENTRIES = 100

private val argumentsJson = Json { isLenient = true }

/**
 * The Shizuku-backed launch and list tools (T2.2 + T2.3).
 *
 * Two actions live here rather than in [ShizukuExecutionBackend] because they are L0/L1 and their channel probe is the
 * same binder, but their argument shapes and audit semantics are unrelated to the privileged four. Keeping them in one
 * backend would force the router to route `launch_app` through the same availability check that gates `uninstall`, and
 * a channel restart between the two would produce an audit line that claims the wrong action was refused. Separate
 * backends, same shell — each action group answers for itself.
 *
 * `launch_app` resolves a spoken name through [AppLaunchResolver] and, when exactly one candidate survives, asks the
 * shell to start it with `am start -W`. Ambiguity is returned as structured data rather than a guess: the same rule
 * the resolver enforces internally, now carried through to the action outcome so the model can ask the user to pick.
 *
 * `list_apps` formats the catalog into lines the model can read. Non-launchable entries are excluded (the resolver
 * already treats them as invisible) and the list is truncated at [MAX_LIST_ENTRIES] with an explicit marker so the
 * model knows it did not see everything.
 */
class LaunchAppExecutor(
    private val resolver: AppLaunchResolver,
    private val catalog: AppCatalog,
    private val shell: PrivilegedShell,
    private val channelUp: () -> Boolean,
) : ExecutionBackend {

    override val id = "shizuku_launch"

    override fun available(): Boolean = runCatching { channelUp() }.getOrDefault(false)

    override fun descriptors(): List<ActionDescriptor> = listOf(
        ActionDescriptor(
            name = LAUNCH_APP,
            title = "打开指定应用",
            parametersJson = """{"type":"object","properties":{"name":{"type":"string"}},"required":["name"],"additionalProperties":false}""",
            risk = RiskLevel.L1,
            requiresConfirmation = false,
            idempotent = false,
            reversible = false,
            scope = "app",
            auditTargetArgument = "name",
        ),
        ActionDescriptor(
            name = LIST_APPS,
            title = "列出已安装的应用",
            parametersJson = """{"type":"object","properties":{},"additionalProperties":false}""",
            risk = RiskLevel.L0,
            requiresConfirmation = false,
            idempotent = true,
            reversible = false,
            scope = "app",
            auditTargetArgument = null,
        ),
    )

    override fun execute(name: String, argsJson: String): ActionResult {
        return when (name) {
            LAUNCH_APP -> {
                if (!available()) return refused(ACTION_CHANNEL_UNAVAILABLE)
                launchApp(argsJson)
            }
            LIST_APPS -> listApps()
            else -> refused(UNKNOWN_ACTION)
        }
    }

    private fun launchApp(argsJson: String): ActionResult {
        val args = objectArguments(argsJson) ?: return refused(ARGUMENTS_INVALID)
        val name = args["name"]?.stringOrNull() ?: return refused(ARGUMENTS_INVALID)
        return when (val resolution = resolver.resolve(name)) {
            is LaunchResolution.NotFound -> refused(APP_NOT_FOUND)
            is LaunchResolution.Ambiguous -> done(formatCandidates(resolution.candidates))
            is LaunchResolution.Found -> launchTarget(resolution.target)
        }
    }

    private fun launchTarget(target: AppLaunchResolver.LaunchTarget): ActionResult {
        // Resolve the exact launcher activity component first. On MIUI, `am start -p <pkg>` with
        // action+category can fail with "unable to resolve Intent" for some system apps (e.g.
        // com.miui.player), while `-n <component>` always works. The resolve step uses the same
        // PackageManager that built the catalog, so it cannot disagree about what is launchable.
        val resolveCmd = "cmd package resolve-activity --brief -c android.intent.category.LAUNCHER ${target.packageName}"
        val resolved = shell.run(resolveCmd)
        // R-9 follow-up #3, and the **short-circuit** is deliberate: a resolve call that did not come back is reported
        // as the channel failure it is, and the launch call is not attempted. Continuing would spend a second call on a
        // binder that just failed to carry a reply - the measured recovery is on a *later* call, not the next one
        // (evidence/2026-10-17-shizuku-reply-ceiling.md §1/§9) - and the fallback `am start -p` would only turn the
        // resolve failure into a second, equally undeliverable failure whose reason names the wrong command.
        if (!resolved.isCallComplete()) return refused(ACTION_CHANNEL_UNAVAILABLE)
        val component = resolved.stdout.lines().lastOrNull { it.contains("/") }?.trim()
        val launchCmd = if (!component.isNullOrBlank()) {
            "am start -W -n $component"
        } else {
            // Fallback: the original action+category+package form. This works for most apps and
            // is the right answer when resolve-activity returns nothing (the package has no
            // launcher activity matching the category, which the resolver already filtered out).
            "am start -W -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -p ${target.packageName}"
        }
        val result = shell.run(launchCmd)
        if (!result.isCallComplete()) return refused(ACTION_CHANNEL_UNAVAILABLE)
        val launched = result.exitCode == 0 && result.stdout.contains("Status: ok")
        return if (launched) done("launched ${target.label}") else failed(STATE_UNCHANGED)
    }

    private fun formatCandidates(candidates: List<AppLaunchResolver.LaunchTarget>): String {
        val items = candidates.joinToString(",") { c ->
            """{"label":"${escapeJson(c.label)}","packageName":"${escapeJson(c.packageName)}"}"""
        }
        return "[$items]"
    }

    private fun listApps(): ActionResult {
        val launchable = catalog.visibleApps().filter { !it.launchIntentAction.isNullOrBlank() }
        if (launchable.isEmpty()) return done("")
        val truncated = launchable.size > MAX_LIST_ENTRIES
        val entries = launchable.take(MAX_LIST_ENTRIES)
        val lines = entries.map { "${it.label} (${it.packageName})" }
        val detail = if (truncated) {
            lines.joinToString("\n") + "\ntruncated: true"
        } else {
            lines.joinToString("\n")
        }
        return done(detail)
    }

    private fun objectArguments(argsJson: String): JsonObject? =
        runCatching { argumentsJson.parseToJsonElement(argsJson) as? JsonObject }.getOrNull()

    private fun kotlinx.serialization.json.JsonElement.stringOrNull(): String? =
        (this as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    private fun escapeJson(value: String): String = value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t")

    private fun done(detail: String) = ActionResult(ActionOutcome.Done, detail)
    private fun refused(code: String) = ActionResult(ActionOutcome.Refused, code)
    private fun failed(code: String) = ActionResult(ActionOutcome.Failed, code)
}
