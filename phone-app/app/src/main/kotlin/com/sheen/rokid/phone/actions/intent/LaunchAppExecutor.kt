package com.sheen.rokid.phone.actions.intent

import com.sheen.rokid.phone.actions.ExecutionBackend
import com.sheen.rokid.plugin.ActionDescriptor
import com.sheen.rokid.plugin.ActionOutcome
import com.sheen.rokid.plugin.ActionResult
import com.sheen.rokid.plugin.RiskLevel

private const val LIST_APPS = "list_apps"
private const val UNKNOWN_ACTION = "UNKNOWN_ACTION"

/** Hard ceiling on list_apps output so a device with hundreds of packages does not flood the model's context. */
private const val MAX_LIST_ENTRIES = 100

/**
 * The local app-catalog tool (T2.3).
 *
 * `launch_app` used to live here; the 2026-10-22 K70 acceptance retired it (0 successful calls out of 10, all redundant
 * with the `open` umbrella) and this executor now advertises only `list_apps` (2026-10-22, owner-approved; 24 → 23).
 *
 * The list comes from the local `PackageManager` through [AppCatalog], so it **does not need Shizuku**: this is the
 * no-channel directory path the model can use when `open(list=true)` is unavailable. It is read-only, and the list is
 * truncated at [MAX_LIST_ENTRIES] with an explicit marker so the model knows it did not see everything.
 */
class LaunchAppExecutor(private val catalog: AppCatalog) : ExecutionBackend {

    override val id = "local_list"

    /** A local PackageManager read has no channel to wait for. */
    override fun available(): Boolean = true

    override fun descriptors(): List<ActionDescriptor> = listOf(
        ActionDescriptor(
            name = LIST_APPS,
            title = "列出已安装的应用",
            description = "列出可启动的已安装应用（每行“名称 (包名)”），无参数；只读、无副作用，不需要 Shizuku。结果超过 100 项会截断并标注 truncated: true，此时不要假设列表完整。需要一边列应用一边打开、或想复用通用打开流程时用 open 的 list=true；本工具不执行任何打开操作。",
            parametersJson = """{"type":"object","properties":{},"additionalProperties":false}""",
            risk = RiskLevel.L0,
            requiresConfirmation = false,
            idempotent = true,
            reversible = false,
            scope = "app",
            auditTargetArgument = null,
        ),
    )

    override fun execute(name: String, argsJson: String): ActionResult = when (name) {
        LIST_APPS -> listApps()
        else -> ActionResult(ActionOutcome.Refused, UNKNOWN_ACTION)
    }

    private fun listApps(): ActionResult {
        val launchable = catalog.visibleApps().filter { !it.launchIntentAction.isNullOrBlank() }
        if (launchable.isEmpty()) return ActionResult(ActionOutcome.Done, "")
        val truncated = launchable.size > MAX_LIST_ENTRIES
        val lines = launchable.take(MAX_LIST_ENTRIES).map { "${it.label} (${it.packageName})" }
        val detail = if (truncated) lines.joinToString("\n") + "\ntruncated: true" else lines.joinToString("\n")
        return ActionResult(ActionOutcome.Done, detail)
    }
}
