package com.sheen.rokid.phone.plugin.smarthelper.output

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * R48: the user-facing labels for the umbrella half of the tool face.
 *
 * Since edith-0.3 Phase 4A Task 4 the production face is 22 host descriptors (the umbrella four this map covers plus 18
 * atomic tools) and the plugin's `notify_user`/`load_skill`. The atomic 18 get their labels in Phase 4A Task 5; the
 * plugin's two answer/skill tools are not device actions and deliberately stay out of this table.
 */
class ToolLabelMapTest {

    /** The four umbrella tools `ToolCatalog` advertises, in descriptor order. */
    private val hostTools = listOf(
        "device" to "设备控制",
        "shell" to "终端命令",
        "open" to "打开应用/页面",
        "dial" to "拨打电话",
    )

    @Test
    fun everyHostToolIsRegisteredWithItsUserFacingLabel() {
        assertEquals(hostTools.size, ToolLabelMap.entries.size,
            "the map must cover exactly the four umbrella tools")
        hostTools.forEach { (action, label) ->
            assertTrue(ToolLabelMap.isRegistered(action), "$action must be registered")
            assertEquals(label, ToolLabelMap.labelFor(action), "$action label")
        }
    }

    @Test
    fun theAtomicToolsHaveNoLabelEntryYet() {
        // Phase 4A Task 4 wires the atomic 18 into the host face; Phase 4A Task 5 adds their labels. Until then the map
        // must not claim them early, and a tool row falls back to the raw model name.
        listOf(
            "read_screen", "tap", "long_press", "scroll", "input_text", "open_app", "open_url",
            "open_settings", "set_alarm", "compose_note", "launch_app", "list_apps", "system_action",
            "clear_data", "uninstall", "disable_app", "set_permission", "set_brightness",
        ).forEach { retired ->
            assertFalse(ToolLabelMap.isRegistered(retired), "$retired must not survive in the label map")
            assertNull(ToolLabelMap.lookup(retired))
        }
    }

    @Test
    fun thePluginsOwnToolsAreNotDeviceActionLabels() {
        listOf("notify_user", "load_skill").forEach { pluginTool ->
            assertFalse(ToolLabelMap.isRegistered(pluginTool), "$pluginTool is not a device action")
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
