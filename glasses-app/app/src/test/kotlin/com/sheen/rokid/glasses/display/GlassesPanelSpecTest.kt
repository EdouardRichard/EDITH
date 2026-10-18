package com.sheen.rokid.glasses.display

import com.sheen.rokid.contracts.AssistantRoute
import com.sheen.rokid.output.ConversationRole
import com.sheen.rokid.output.OfficialConversation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.23 P3 (spec §5.1): the official 「乐奇」 panel, pinned as numbers rather than pixels.
 *
 * Everything the panel draws is a constant the official resources state (B level) or the device screenshots show
 * (A level), so it belongs here instead of inside `GlassesActivity.buildLayout`: a value that drifts fails a JVM test
 * rather than needing someone to put the glasses on. The two state predicates are the whole of the "which line is on
 * screen" decision and are pure for the same reason.
 */
class GlassesPanelSpecTest {

    @Test
    fun thePanelMatchesTheOfficialGeometry() {
        assertEquals(480, GlassesPanelSpec.SCREEN_WIDTH_PX)
        assertEquals(640, GlassesPanelSpec.SCREEN_HEIGHT_PX)
        assertEquals(240, GlassesPanelSpec.SCREEN_DPI)
        assertEquals(380, GlassesPanelSpec.PANEL_HEIGHT_PX)
        assertEquals("a bottom-anchored 380px panel on a 640px screen starts here",
            GlassesPanelSpec.SCREEN_HEIGHT_PX - GlassesPanelSpec.PANEL_HEIGHT_PX, GlassesPanelSpec.PANEL_TOP_PX)
        assertEquals(20, GlassesPanelSpec.PANEL_BOTTOM_PADDING_PX)
        assertEquals(30, GlassesPanelSpec.MASK_HEIGHT_PX)
        assertEquals(55, GlassesPanelSpec.VOICE_ORB_WIDTH_PX)
        assertEquals(45, GlassesPanelSpec.VOICE_ORB_HEIGHT_PX)
        assertEquals(22, GlassesPanelSpec.THINKING_ICON_PX)
        assertEquals(4, GlassesPanelSpec.USER_MAX_LINES)
        assertEquals(Int.MAX_VALUE, GlassesPanelSpec.ASSISTANT_MAX_LINES)
    }

    @Test
    fun theOfficialGreensAndTypeSizesArePinned() {
        assertEquals(0xFF40FF5E.toInt(), GlassesPanelSpec.GREEN_NORMAL)
        assertEquals(0xB340FF5E.toInt(), GlassesPanelSpec.GREEN_NORMAL_70)
        assertEquals(0x6600FF00.toInt(), GlassesPanelSpec.GREEN_40)
        assertEquals("the tool row's running copy is the official `green`", 0xFF00FF00.toInt(),
            GlassesPanelSpec.TOOL_TEXT_START)
        assertEquals("the tool row's finished copy is the official `green_50`", 0x8000FF00.toInt(),
            GlassesPanelSpec.TOOL_TEXT_END)
        assertEquals(0xFFCD0000.toInt(), GlassesPanelSpec.ALERT_RED)
        assertEquals(0xFF000000.toInt(), GlassesPanelSpec.BLACK)
        assertEquals(14f, GlassesPanelSpec.USER_TEXT_SIZE_PX)
        assertEquals(16f, GlassesPanelSpec.ASSISTANT_TEXT_SIZE_PX)
        assertEquals(14f, GlassesPanelSpec.LISTENING_TEXT_SIZE_PX)
        assertEquals(16f, GlassesPanelSpec.THINKING_TEXT_SIZE_PX)
        assertEquals(22, GlassesPanelSpec.TOOL_ICON_PX)
        assertEquals(16f, GlassesPanelSpec.TOOL_TEXT_SIZE_PX)
        assertEquals(14f, GlassesPanelSpec.TIPS_TEXT_SIZE_PX)
        assertEquals(12f, GlassesPanelSpec.COMPLIANCE_TEXT_SIZE_PX)
    }

    @Test
    fun listeningIsOnlyTheWakeRouteBeforeTheWearerSpeaks() {
        assertTrue(GlassesPanelSpec.listeningVisible(true, false, AssistantRoute.WAKE, null))
        assertTrue("a fresh wake after an answer is still the listening state",
            GlassesPanelSpec.listeningVisible(true, false, AssistantRoute.WAKE, ConversationRole.ASSISTANT))
        assertFalse("once the wearer's own line is in, the assistant is thinking",
            GlassesPanelSpec.listeningVisible(true, false, AssistantRoute.WAKE, ConversationRole.USER))
        assertFalse("the proactive route never shows the wake listening line",
            GlassesPanelSpec.listeningVisible(true, false, AssistantRoute.PROACTIVE, null))
        assertFalse("a live answer owns the bottom of the panel",
            GlassesPanelSpec.listeningVisible(true, true, AssistantRoute.WAKE, null))
        assertFalse(GlassesPanelSpec.listeningVisible(false, false, AssistantRoute.WAKE, null))
    }

    @Test
    fun thinkingIsProactiveOrWakeAfterTheUtterance() {
        assertTrue(GlassesPanelSpec.thinkingVisible(true, false, AssistantRoute.PROACTIVE, null))
        assertTrue(GlassesPanelSpec.thinkingVisible(true, false, AssistantRoute.WAKE, ConversationRole.USER))
        assertFalse("the wake route before the wearer speaks is listening, not thinking",
            GlassesPanelSpec.thinkingVisible(true, false, AssistantRoute.WAKE, ConversationRole.ASSISTANT))
        assertFalse(GlassesPanelSpec.thinkingVisible(true, true, AssistantRoute.PROACTIVE, null))
        assertFalse(GlassesPanelSpec.thinkingVisible(false, false, AssistantRoute.WAKE, ConversationRole.USER))
    }

    @Test
    fun theOrbFollowsTheAnswerThenTheListeningThenTheThinking() {
        assertEquals(OrbState.ANSWERING, GlassesPanelSpec.orbState(true, true, AssistantRoute.WAKE, null))
        assertEquals(OrbState.LISTENING, GlassesPanelSpec.orbState(true, false, AssistantRoute.WAKE, null))
        assertEquals(OrbState.THINKING,
            GlassesPanelSpec.orbState(true, false, AssistantRoute.WAKE, ConversationRole.USER))
        assertEquals(OrbState.THINKING, GlassesPanelSpec.orbState(true, false, AssistantRoute.PROACTIVE, null))
        assertEquals(OrbState.IDLE, GlassesPanelSpec.orbState(false, false, AssistantRoute.WAKE, null))
        assertEquals("a live answer outranks every level", OrbState.ANSWERING,
            GlassesPanelSpec.orbState(false, true, AssistantRoute.PROACTIVE, ConversationRole.USER))
    }

    @Test
    fun theTipsAndComplianceUseTheOfficialCopy() {
        // Rule I2 (Task 2 review): the tips copy has one source, `OfficialConversation`; this spec object must not
        // restate it. These two assertions pin the exact official strings at their one authority.
        assertEquals("滑动翻页 / 双击退出", OfficialConversation.TIPS_EXIT)
        assertEquals("滑动翻页 / 双击打断", OfficialConversation.TIPS_STOP)
        assertEquals("内容由 AI 生成", GlassesPanelSpec.complianceText())
        assertEquals("Hi, 我在听", GlassesPanelSpec.LISTENING_TEXT)
        assertEquals("正在思考", GlassesPanelSpec.THINKING_TEXT)
        assertEquals("单击钉住", GlassesPanelSpec.PIN_TEXT)
    }

    @Test
    fun theOldDuplicatedTipsLiteralsAreGoneFromTheSpec() {
        // Rule I2: a transitional duplicate is not allowed after Task 13. A later edit that re-adds either literal here
        // would reintroduce the second source of truth this task removes.
        val fields = GlassesPanelSpec::class.java.declaredFields.map { it.name }.toSet()
        assertFalse("TIPS_EXIT must live only in OfficialConversation", fields.contains("TIPS_EXIT"))
        assertFalse("TIPS_STOP must live only in OfficialConversation", fields.contains("TIPS_STOP"))
        assertTrue("the spec still owns the Hi text it renders", fields.contains("LISTENING_TEXT"))
    }

    @Test
    fun everyRowUsesTheOfficialSizesAndColors() {
        assertEquals(14f, GlassesPanelSpec.textSizePx(ConversationRole.USER))
        assertEquals(16f, GlassesPanelSpec.textSizePx(ConversationRole.ASSISTANT))
        assertEquals(4, GlassesPanelSpec.maxLines(ConversationRole.USER))
        assertEquals(Int.MAX_VALUE, GlassesPanelSpec.maxLines(ConversationRole.ASSISTANT))
        // Task 17 review M2: there is intentionally no role-only `textColor`; the wearer's colour depends on whether
        // this turn already has an answer (ConversationView.TurnRow), so the rendered rule is pinned there.
        assertFalse("a role-only colour would be a stale second authority",
            GlassesPanelSpec::class.java.declaredMethods.any { it.name == "textColor" })
    }

    // ---------------------------------------------------------------- Task 19 panel visuals

    @Test
    fun theOfficialRowSpacingAndGifSizesArePinned() {
        // `recycler_item_page_ai.xml`: the ASR item root is 10/4 (top/bottom) with 10 px side margins; the TTS line is
        // 5/10 with 10 px side padding; both state rows are exactly 22 px with a 10 px icon start margin and 5 px copy
        // start margin. These are the numbers the official layout states, not eyeballed screenshots.
        assertEquals(10, GlassesPanelSpec.ASR_ROW_MARGIN_TOP_PX)
        assertEquals(4, GlassesPanelSpec.ASR_ROW_MARGIN_BOTTOM_PX)
        assertEquals(10, GlassesPanelSpec.ASR_ROW_MARGIN_SIDE_PX)
        assertEquals(5, GlassesPanelSpec.TTS_ROW_MARGIN_TOP_PX)
        assertEquals(10, GlassesPanelSpec.TTS_ROW_MARGIN_BOTTOM_PX)
        assertEquals(10, GlassesPanelSpec.TTS_SIDE_PADDING_PX)
        assertEquals("the thinking/tool row height is the official 22 px", 22,
            GlassesPanelSpec.STATE_ROW_HEIGHT_PX)
        assertEquals(10, GlassesPanelSpec.STATE_ICON_MARGIN_START_PX)
        assertEquals(5, GlassesPanelSpec.STATE_TEXT_MARGIN_START_PX)

        // `imv_asr_loading` is 26x13 px; thinking (`imv_loading`) and the agent icon are 22x22 px.
        assertEquals(26, GlassesPanelSpec.ASR_GIF_WIDTH_PX)
        assertEquals(13, GlassesPanelSpec.ASR_GIF_HEIGHT_PX)
        assertEquals(10, GlassesPanelSpec.ASR_GIF_MARGIN_START_PX)
        assertEquals(10, GlassesPanelSpec.ASR_GIF_MARGIN_TOP_PX)
        assertEquals(22, GlassesPanelSpec.THINKING_ICON_PX)
        assertEquals(22, GlassesPanelSpec.TOOL_ICON_PX)
        assertEquals("the official pin is 20x20 at 40% alpha", 20, GlassesPanelSpec.PIN_ICON_PX)
        assertEquals(0.4f, GlassesPanelSpec.PIN_ICON_ALPHA, 0.0001f)
        assertEquals("the two edge fades are 30 px", 30, GlassesPanelSpec.MASK_HEIGHT_PX)
        assertEquals(12f, GlassesPanelSpec.COMPLIANCE_TEXT_SIZE_PX)
    }

    @Test
    fun onlyTheFinalAnswerIsRenderedAsMarkdown() {
        assertEquals("a streamed partial stays plain text", AnswerTextMode.PLAIN,
            MarkdownPolicy.modeFor(final = false))
        assertEquals("the final SHOW is Markwon-rendered", AnswerTextMode.MARKDOWN,
            MarkdownPolicy.modeFor(final = true))
        assertTrue("the partial path must stay within the task's <=100 ms bound",
            MarkdownPolicy.MAX_PARTIAL_THROTTLE_MILLIS <= 100L)
    }
}
