package edith.phone.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Phase C (spec §6.7): the phone's shell — which tabs exist, in what order, and what names the plugin in the top bar.
 *
 * Only the decisions are here; the Compose that draws them is the untestable half, and the acceptance for it is a
 * device walk-through. What can be pinned is the part a later edit could silently get wrong: the tab set and order the
 * spec fixes, and the rule that the title falls back to the plugin's id rather than to an empty string.
 */
class PhoneNavigationTest {

    @Test
    fun theFourTabsAreInTheOrderTheSpecFixes() {
        assertEquals(listOf("助手", "技能", "记忆", "设置"), PhoneTab.entries.map { it.label })
    }

    @Test
    fun everyTabIsReachableByItsOwnIndex() {
        PhoneTab.entries.forEachIndexed { index, tab -> assertEquals(tab, phoneTabFor(index)) }
    }

    @Test
    fun anUnknownIndexFallsBackToTheAssistantTabRatherThanToNothing() {
        // The body is rendered from this, and a null body would be a blank page with no explanation.
        assertEquals(PhoneTab.ASSISTANT, phoneTabFor(9))
        assertEquals(PhoneTab.ASSISTANT, phoneTabFor(-1))
    }

    @Test
    fun theTitleIsTheSelectedPluginsLabel() {
        assertEquals("智能助手", pluginTitle("smart-assistant", listOf("smart-assistant" to "智能助手")))
    }

    @Test
    fun anUnselectedPluginShowsItsIdRatherThanAnEmptyTitle() {
        // A title that renders as nothing looks like a broken page; the id at least says which plugin is active.
        assertEquals("p2-hint", pluginTitle("p2-hint", listOf("smart-assistant" to "智能助手")))
        assertEquals("", pluginTitle(null, listOf("smart-assistant" to "智能助手")))
    }

    @Test
    fun theSmartAssistantIsThePluginWhoseTabsTheseAre() {
        assertEquals("smart-assistant", PhoneTab.SMART_ASSISTANT_PLUGIN_ID)
    }
}
