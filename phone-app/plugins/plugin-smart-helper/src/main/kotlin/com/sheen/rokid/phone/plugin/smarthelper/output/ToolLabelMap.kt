package com.sheen.rokid.phone.plugin.smarthelper.output

/**
 * 0.24 S4 Task 19 (spec §5.6): the tool-row labels for the actions EDITH's `ExecutionRouter` actually exposes.
 *
 * The rule is "map only EDITH's own actions; an unknown name stays raw; never dress an unknown action in the official
 * `launcher_ai_tool_*` copy". The live host `ActionDescriptor.title` remains the first source (Task 17: it cannot drift
 * from the actions that are really advertised); this table is the explicit registration and the fallback for a host
 * that advertises an action without a title. The values intentionally equal the descriptors' user-facing titles:
 *
 * - `IntentActionExecutor`: open_app, open_url, open_settings, set_alarm, compose_note, dial
 * - `AccessibilityExecutionBackend`: read_screen, tap, long_press, scroll, input_text
 * - `LaunchAppExecutor`: launch_app, list_apps
 * - `SystemActionExecutor`: system_action
 * - `ShizukuExecutionBackend`: clear_data, uninstall, disable_app, set_permission, set_brightness
 *
 * A new action added to the router must get an entry here; [entries] is small and JVM-tested so the omission is visible
 * off-device rather than only as a raw `english_tool_name` on the wearer's panel.
 */
object ToolLabelMap {

    val entries: Map<String, String> = linkedMapOf(
        // L0/L1 intent actions.
        "open_app" to "打开 App",
        "open_url" to "打开网页",
        "open_settings" to "打开系统设置",
        "set_alarm" to "设置闹钟",
        "compose_note" to "记一条笔记",
        "dial" to "拨打电话",
        // Accessibility (semantic UI) actions.
        "read_screen" to "读取当前屏幕内容",
        "tap" to "点击指定索引的元素",
        "long_press" to "长按指定索引的元素",
        "scroll" to "滚动屏幕",
        "input_text" to "在指定索引的输入框输入文字",
        // Shizuku app launch/listing.
        "launch_app" to "打开指定应用",
        "list_apps" to "列出已安装的应用",
        // Shizuku system keys.
        "system_action" to "执行系统级按键动作",
        // Shizuku privileged app/permission/brightness actions.
        "clear_data" to "清除 App 数据",
        "uninstall" to "卸载 App",
        "disable_app" to "停用 App",
        "set_permission" to "变更 App 权限",
        "set_brightness" to "调整屏幕亮度",
    )

    fun isRegistered(action: String): Boolean = entries.containsKey(action)

    /** The registered label, or null when this is not an EDITH action (the caller then shows the raw name). */
    fun lookup(action: String): String? = entries[action]

    /** The label to paint: registered copy, else the raw action name, never a guessed official string. */
    fun labelFor(action: String): String = entries[action] ?: action
}
