package edith.probe.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The agent's eyes and its aim, pinned before any model call.
 *
 * `find_and_tap` is what makes this an agent rather than a macro: the model names a *target* ("搜索", "Wi-Fi"), and the
 * tool resolves that name against the real UI hierarchy and computes where to tap. Blind coordinates would put the
 * mapping decision in the prompt, where it cannot be verified and where one wrong guess silently becomes a wrong result.
 *
 * The hierarchy from `uiautomator dump` marks the node it considers actionable with `clickable="true"`; a label nested
 * inside a clickable row is not itself clickable. Tapping the label's bounds usually still works, but choosing the
 * clickable ancestor is the difference between "usually" and "the node the platform says to hit".
 */
class UiDumpTest {

    private val dump = """
        <?xml version='1.0' encoding='UTF-8' standalone='yes' ?>
        <hierarchy rotation="0">
          <node index="0" text="" resource-id="" class="android.widget.FrameLayout" package="com.android.settings" content-desc="" clickable="false" enabled="true" bounds="[0,0][1080,2400]">
            <node index="1" text="搜索" resource-id="com.android.settings:id/search_bar" class="android.widget.TextView" package="com.android.settings" content-desc="" clickable="false" enabled="true" bounds="[24,120][1056,240]" />
            <node index="2" text="" resource-id="com.android.settings:id/search_action" class="android.widget.ImageView" package="com.android.settings" content-desc="搜索设置项" clickable="true" enabled="true" bounds="[900,120][1056,240]" />
            <node index="3" text="WLAN" resource-id="com.android.settings:id/title" class="android.widget.TextView" package="com.android.settings" content-desc="" clickable="true" enabled="true" bounds="[48,600][1032,760]" />
            <node index="4" text="蓝牙" resource-id="com.android.settings:id/title" class="android.widget.TextView" package="com.android.settings" content-desc="" clickable="true" enabled="true" bounds="[48,760][1032,920]" />
            <node index="5" text="已停用" resource-id="" class="android.widget.TextView" package="com.android.settings" content-desc="" clickable="false" enabled="false" bounds="[48,920][1032,1000]" />
          </node>
        </hierarchy>
    """.trimIndent()

    @Test
    fun parseExtractsTextBoundsAndClickability() {
        val nodes = parseUiDump(dump)
        assertEquals(6, nodes.size)
        val wlan = nodes.first { it.text == "WLAN" }
        assertEquals(Bounds(48, 600, 1032, 760), wlan.bounds)
        assertTrue(wlan.clickable)
    }

    @Test
    fun boundsCentreIsTheMidpoint() {
        assertEquals(540, Bounds(48, 600, 1032, 760).centerX)
        assertEquals(680, Bounds(48, 600, 1032, 760).centerY)
    }

    @Test
    fun findMatchesTextCaseInsensitively() {
        val nodes = parseUiDump(dump)
        assertEquals("WLAN", findTappable(nodes, "wlan")?.text)
    }

    @Test
    fun findMatchesContentDescriptionAsWellAsText() {
        val nodes = parseUiDump(dump)
        assertEquals("搜索设置项", findTappable(nodes, "搜索设置项")?.contentDesc)
    }

    @Test
    fun findPrefersAClickableNodeOverAMatchingLabel() {
        // "搜索" appears as a non-clickable TextView; the clickable sibling carries it as content-desc. The tool must
        // aim at the node the platform says is actionable.
        val nodes = parseUiDump(dump)
        val target = findTappable(nodes, "搜索")
        assertTrue(target != null, "a clickable match must be found for 搜索")
        assertTrue(target!!.clickable, "the chosen node must be clickable, not the nested label")
    }

    @Test
    fun findReturnsNullRatherThanGuessingWhenTheTargetIsAbsent() {
        val nodes = parseUiDump(dump)
        assertNull(findTappable(nodes, "不存在的条目"))
    }

    @Test
    fun findSkipsDisabledNodes() {
        val nodes = parseUiDump(dump)
        // "已停用" is present but disabled: tapping it cannot achieve anything, so reporting it as found would send the
        // agent into a loop of taps that never change the screen.
        assertNull(findTappable(nodes, "已停用"))
    }

    @Test
    fun parseDoesNotConfuseLongClickableWithClickable() {
        // Real dumps carry both attributes, and `long-clickable="true"` contains `clickable="true"` as a substring: a
        // naive regex reads the wrong one and the agent then aims at a node the platform does not consider tappable.
        val xml = """<hierarchy><node text="x" long-clickable="true" clickable="false" enabled="true" bounds="[0,0][10,10]" /></hierarchy>"""
        val node = parseUiDump(xml).single()
        assertFalse(node.clickable, "'long-clickable' must not be read as 'clickable'")
    }

    @Test
    fun findElementsListsEveryCandidateInsteadOfChoosingOne() {
        // The third real run failed exactly here: the agent asked for "搜索" and a single-match helper silently picked
        // "搜索记录" (the history header), so every step reported ok=true while the agent acted on the wrong element.
        // The tool's job is to put the choice in front of the model, not to guess.
        val nodes = parseUiDump(dump)
        val candidates = findElements(nodes, "搜索")
        assertTrue(candidates.size >= 2, "both the search header and the search field must be offered")
        assertEquals(listOf(0, 1), candidates.map { it.index })
    }

    @Test
    fun findElementsPutsClickableCandidatesFirst() {
        val nodes = parseUiDump(dump)
        val candidates = findElements(nodes, "搜索")
        assertTrue(candidates.first().clickable, "an actionable node must be offered before a plain label")
    }

    @Test
    fun findElementsSkipsDisabledNodesAndReportsCentres() {
        val nodes = parseUiDump(dump)
        assertTrue(findElements(nodes, "已停用").isEmpty())
        val wlan = findElements(nodes, "WLAN").single()
        assertEquals(540, wlan.centerX)
        assertEquals(680, wlan.centerY)
    }

    @Test
    fun progressGuardTripsOnlyAfterRepeatedlyIdenticalScreens() {
        // Without this the third run re-tapped identical screens until the step limit; the spec's no-progress guard is
        // load-bearing, so it is pinned rather than assumed.
        val guard = ProgressGuard(maxRepeats = 3)
        assertTrue(guard.observe("A"))
        assertTrue(guard.observe("A"))
        assertFalse(guard.observe("A"), "the third identical screen means no progress")
    }

    @Test
    fun progressGuardResetsWhenTheScreenChanges() {
        val guard = ProgressGuard(maxRepeats = 3)
        assertTrue(guard.observe("A"))
        assertTrue(guard.observe("A"))
        assertTrue(guard.observe("B"), "a changed screen is progress")
        assertTrue(guard.observe("B"))
        assertFalse(guard.observe("B"))
    }

    @Test
    fun parseToleratesAnEmptyOrMalformedDump() {
        assertEquals(0, parseUiDump("").size)
        assertEquals(0, parseUiDump("<hierarchy rotation=\"0\"></hierarchy>").size)
    }
}
