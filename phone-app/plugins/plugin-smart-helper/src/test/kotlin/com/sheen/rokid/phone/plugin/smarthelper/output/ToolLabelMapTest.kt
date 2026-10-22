package com.sheen.rokid.phone.plugin.smarthelper.output

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * R48 + edith-0.3 Phase 4A Task 5 (A5): the user-facing labels for the **24-tool default face**.
 *
 * Since Phase 4A Task 4 the production face is 22 host descriptors (18 atomic + the umbrella four) plus the plugin's
 * `notify_user`/`load_skill`. Task 5 labels all 24. The labels are the user's copy — never `ActionDescriptor.description`,
 * which is model-facing prose — and the plugin filters its own two tools before painting a row, so a label here only
 * means the name is registered, not that it always appears on the glasses.
 *
 * The rule stays "map only EDITH's own actions; an unknown name stays raw; never dress an unknown action in the
 * official `launcher_ai_tool_*` copy". The live host `ActionDescriptor.title` remains the first source; this table is
 * the explicit registration and the fallback for a host that advertises an action without a title.
 */
class ToolLabelMapTest {

    /** The 23 default tools, with the label the user sees for each. */
    private val labeled = listOf(
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
        // atomic local directory / system keys (`launch_app` retired 2026-10-22, 24 → 23)
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

    @Test
    fun allTwentyThreeToolsAreRegisteredWithTheirUserFacingLabel() {
        assertEquals(23, labeled.size, "the fixture is the default face")
        assertEquals(
            labeled.map { it.first }.toSet(),
            ToolLabelMap.entries.keys,
            "the map must cover exactly the 23 default tools",
        )
        labeled.forEach { (action, label) ->
            assertTrue(ToolLabelMap.isRegistered(action), "$action must be registered")
            assertEquals(label, ToolLabelMap.labelFor(action), "$action label")
        }
    }

    @Test
    fun aLabelIsUserCopyAndNeverTheRawModelName() {
        labeled.forEach { (action, label) ->
            assertTrue(label.isNotBlank(), "$action label must not be blank")
            assertNotEquals(action, label, "$action must not be displayed as its raw model name")
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
