package com.sheen.rokid.phone.plugin.smarthelper.output

/**
 * 0.25 Task 10 (R48): the tool-row labels for the six-tool surface EDITH actually exposes.
 *
 * Since this task the host's `ToolCatalog` advertises four tools — `device`, `shell`, `open`,
 * `dial` — and the plugin adds its own `notify_user`/`load_skill` beside it. Only the four host
 * tools are device actions and belong here; the plugin's two are its speech/skill channels and are
 * deliberately absent. The retired 21-tool router entries were removed with the surface.
 *
 * The rule stays "map only EDITH's own actions; an unknown name stays raw; never dress an unknown
 * action in the official `launcher_ai_tool_*` copy". The live host `ActionDescriptor.title` remains
 * the first source; this table is the explicit registration and the fallback for a host that
 * advertises an action without a title. The values intentionally equal the descriptors' titles:
 *
 * - `ToolCatalog`/`DeviceTool`: 设备控制
 * - `ToolCatalog`/`ShellTool`: 终端命令
 * - `ToolCatalog`/`OpenTool`: 打开应用/页面
 * - `ToolCatalog`/`DialTool`: 拨打电话
 */
object ToolLabelMap {

    val entries: Map<String, String> = linkedMapOf(
        "device" to "设备控制",
        "shell" to "终端命令",
        "open" to "打开应用/页面",
        "dial" to "拨打电话",
    )

    fun isRegistered(action: String): Boolean = entries.containsKey(action)

    /** The registered label, or null when this is not an EDITH action (the caller then shows the raw name). */
    fun lookup(action: String): String? = entries[action]

    /** The label to paint: registered copy, else the raw action name, never a guessed official string. */
    fun labelFor(action: String): String = entries[action] ?: action
}
