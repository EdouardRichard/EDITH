package com.sheen.rokid.phone.plugin.smarthelper.output

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 0.24 S4 Task 19 (spec §5.6): every action EDITH's `ExecutionRouter` can expose has one registered tool-row label.
 *
 * The implementation plan registers the actions one by one; an unknown name is shown raw and is never translated into
 * an official `launcher_ai_tool_*` string (this project maps only its own actions). The live `ActionDescriptor.title`
 * still wins when the host supplies one (Task 17's single source); this table is the explicit registry/fallback that
 * makes "only EDITH's actions" checkable off-device.
 */
class ToolLabelMapTest {

    /** The 19 actions the ExecutionRouter's five backends declare today (intent 6, a11y 5, launch 2, system 1, shizuku 5). */
    private val edithActions = listOf(
        "open_app" to "打开 App",
        "open_url" to "打开网页",
        "open_settings" to "打开系统设置",
        "set_alarm" to "设置闹钟",
        "compose_note" to "记一条笔记",
        "dial" to "拨打电话",
        "read_screen" to "读取当前屏幕内容",
        "tap" to "点击指定索引的元素",
        "long_press" to "长按指定索引的元素",
        "scroll" to "滚动屏幕",
        "input_text" to "在指定索引的输入框输入文字",
        "launch_app" to "打开指定应用",
        "list_apps" to "列出已安装的应用",
        "system_action" to "执行系统级按键动作",
        "clear_data" to "清除 App 数据",
        "uninstall" to "卸载 App",
        "disable_app" to "停用 App",
        "set_permission" to "变更 App 权限",
        "set_brightness" to "调整屏幕亮度",
    )

    @Test
    fun everyEdithActionIsRegisteredWithItsUserFacingLabel() {
        assertEquals(edithActions.size, ToolLabelMap.entries.size,
            "the map must cover exactly the router's advertised actions")
        edithActions.forEach { (action, label) ->
            assertTrue(ToolLabelMap.isRegistered(action), "$action must be registered")
            assertEquals(label, ToolLabelMap.labelFor(action), "$action label")
        }
    }

    @Test
    fun anUnknownActionStaysRawAndIsNeverGivenOfficialCopy() {
        assertFalse(ToolLabelMap.isRegistered("mystery_tool"))
        assertNull(ToolLabelMap.lookup("mystery_tool"), "the map must not invent a label")
        assertEquals("mystery_tool", ToolLabelMap.labelFor("mystery_tool"),
            "spec §5.6: unknown names are displayed as-is")
    }
}
