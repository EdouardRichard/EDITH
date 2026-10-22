package com.sheen.rokid.phone.plugin.smarthelper.output

/**
 * edith-0.3 Phase 4A Task 5 (A5), updated 2026-10-22 (launch_app retired: 24 → 23): the tool-row labels for the
 * **23-tool default face**.
 *
 * The host's `ToolSurface` advertises 21 descriptors — the four umbrella tools `device`/`shell`/`open`/`dial` and the
 * 17 atomic tools — and the plugin adds its own `notify_user`/`load_skill`. This table names all 23, so a tool row can
 * always be painted with user copy even when the host descriptor arrives without a usable title.
 *
 * These values are **user-facing labels, never model-facing descriptions**: `ActionDescriptor.description` is the
 * instruction prose the model reads (and `ToolDescriptor.title` is its user-facing counterpart), so deriving a row label
 * from the description would put a paragraph on the glasses. The live host `ActionDescriptor.title` remains the first
 * source; this table is the explicit registration and the fallback for a host that advertises an action without a title.
 *
 * The plugin's two delivery tools carry labels for the face's completeness, but `publishToolStatus` filters them before
 * painting: `notify_user` is the answer channel and `load_skill` is a skill read, so neither is a device-action row.
 *
 * The rule stays "map only EDITH's own actions; an unknown name stays raw; never dress an unknown action in the
 * official `launcher_ai_tool_*` copy".
 */
object ToolLabelMap {

    val entries: Map<String, String> = linkedMapOf(
        // atomic a11y
        "read_screen" to "读取屏幕内容",
        "tap" to "点击界面元素",
        "long_press" to "长按界面元素",
        "scroll" to "滚动屏幕",
        "input_text" to "输入文字",
        // atomic intent
        "open_app" to "打开应用",
        "open_url" to "打开网页",
        "open_settings" to "打开设置",
        "set_alarm" to "设置闹钟",
        "compose_note" to "记笔记",
        // atomic Shizuku
        "clear_data" to "清除应用数据",
        "uninstall" to "卸载应用",
        "disable_app" to "停用应用",
        "set_permission" to "变更应用权限",
        "set_brightness" to "调整亮度",
        // atomic launch/system
        "list_apps" to "列出应用",
        "system_action" to "系统按键",
        // the umbrella catalog
        "device" to "设备控制",
        "shell" to "终端命令",
        "open" to "打开应用/页面",
        "dial" to "拨打电话",
        // the plugin's delivery tools
        "notify_user" to "通知用户",
        "load_skill" to "读取技能",
    )

    fun isRegistered(action: String): Boolean = entries.containsKey(action)

    /** The registered label, or null when this is not an EDITH action (the caller then shows the raw name). */
    fun lookup(action: String): String? = entries[action]

    /** The label to paint: registered copy, else the raw action name, never a guessed official string. */
    fun labelFor(action: String): String = entries[action] ?: action
}
