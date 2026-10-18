package com.sheen.rokid.glasses.display

import com.sheen.rokid.contracts.AssistantRoute
import com.sheen.rokid.contracts.ToolPhase
import com.sheen.rokid.output.ConversationRole

/**
 * 0.23 P3/P4 (spec §5.1): the official 「乐奇」 panel, as numbers and pure decisions.
 *
 * Every value here is either an official resource (B level: `dialog_page_ai.xml`, `recycler_item_page_ai.xml`,
 * `colors.xml`) or a device measurement (A level). `GlassesActivity` only draws them; anything that can be decided
 * without Android - which line is visible, which orb state, how tall a row may grow - lives here so a drift fails a
 * JVM test instead of needing someone to put the glasses on.
 *
 * The operating tips are **not** a panel constant: their copy and their pinned+answering condition belong to
 * `OfficialConversation` (0.24 Rule I2), so there is one source for both ends of the wire.
 *
 * **Units are px on purpose.** The official layout writes `android:textSize="16.0px"`, not `sp`: on a fixed 480x640 /
 * 240dpi panel the layout must not change with a font-scale setting. The Activity therefore passes these to
 * `setTextSize(TypedValue.COMPLEX_UNIT_PX, ...)`.
 */
object GlassesPanelSpec {

    // ---------------------------------------------------------------- canvas and panel

    /** The official panel is 480x640 at 240 dpi (1 dp = 1.5 px). */
    const val SCREEN_WIDTH_PX = 480
    const val SCREEN_HEIGHT_PX = 640
    const val SCREEN_DPI = 240

    /** `page_content`: bottom-anchored, 380 px tall, with 20 px of bottom padding inside it. */
    const val PANEL_HEIGHT_PX = 380
    const val PANEL_TOP_PX = SCREEN_HEIGHT_PX - PANEL_HEIGHT_PX
    const val PANEL_BOTTOM_PADDING_PX = 20

    /** `icon_ai_top_mask` / `icon_ai_bottom_mask`: 481x30 px black alpha fades. */
    const val MASK_HEIGHT_PX = 30

    /** `id_pv`: the PAG voice orb's box. */
    const val VOICE_ORB_WIDTH_PX = 55
    const val VOICE_ORB_HEIGHT_PX = 45

    /** `imv_loading` in the thinking row. */
    const val THINKING_ICON_PX = 22

    /** 0.24 S4 Task 17: the tool row's icon slot and copy, the official 22x22 / 16 px pair. */
    const val TOOL_ICON_PX = 22
    const val TOOL_TEXT_SIZE_PX = 16f

    // ---------------------------------------------------------------- official green scale

    /** `green_normal`: the assistant's answer, the listening line, the thinking line and the orb. */
    val GREEN_NORMAL: Int = 0xFF40FF5E.toInt()

    /** `green_normal_70`: the wearer's own line once the answer has arrived. */
    val GREEN_NORMAL_70: Int = 0xB340FF5E.toInt()

    /**
     * 0.24 S4 Task 17: the tool row's start/end copy.
     *
     * Official `agentToolStatus` paints START with full `green` and END with `green_50`; EDITH keeps its own
     * `green_normal` family (the panel already uses `green_normal` everywhere) and expresses "50%" as the same colour's
     * 50% alpha. Task 19 replaces the stand-in icon with the official `ai_agent_tool_*` GIFs; the copy/contrast rules
     * stay these.
     */
    val TOOL_TEXT_START: Int = GREEN_NORMAL
    val TOOL_TEXT_END: Int = 0x8040FF5E.toInt()

    /** `green_40`: the two operation tips. */
    val GREEN_40: Int = 0x6600FF00.toInt()

    /** The AI compliance label. */
    val ALERT_RED: Int = 0xFFCD0000.toInt()

    /** The panel and the masks' opaque end. */
    val BLACK: Int = 0xFF000000.toInt()

    // ---------------------------------------------------------------- type sizes (px, never sp)

    const val USER_TEXT_SIZE_PX = 14f
    const val ASSISTANT_TEXT_SIZE_PX = 16f
    const val LISTENING_TEXT_SIZE_PX = 14f
    const val THINKING_TEXT_SIZE_PX = 16f
    const val TIPS_TEXT_SIZE_PX = 14f
    const val COMPLIANCE_TEXT_SIZE_PX = 12f

    /** The wearer's line is capped at four lines and loses its **beginning** (`TruncateAt.START`). */
    const val USER_MAX_LINES = 4

    /** The assistant's line is never truncated at the view layer; the list scrolls. */
    const val ASSISTANT_MAX_LINES = Int.MAX_VALUE

    // ---------------------------------------------------------------- official copy

    const val LISTENING_TEXT = "Hi, 我在听"
    const val THINKING_TEXT = "正在思考"
    const val COMPLIANCE_TEXT = "内容由 AI 生成"
    const val PIN_TEXT = "单击钉住"

    // ---------------------------------------------------------------- relative geometry (P3 layout)

    const val LIST_PADDING_SIDE_PX = 10
    const val LIST_PADDING_TOP_PX = 10
    const val LIST_PADDING_BOTTOM_PX = 25
    const val LISTENING_MARGIN_TOP_PX = 6
    const val STATE_MARGIN_BOTTOM_PX = 5
    const val TIPS_MARGIN_START_PX = 16
    const val TIPS_MARGIN_END_PX = 16
    const val TIPS_BOTTOM_MARGIN_PX = 70
    const val COMPLIANCE_MARGIN_END_PX = 17
    const val COMPLIANCE_MARGIN_BOTTOM_PX = 3
    const val PIN_ICON_PX = 20
    const val PIN_ICON_ALPHA = 0.4f

    /** The four states the official PAG orb is observed in. */
    // The enum is top-level (see the end of this file) so callers do not have to spell `GlassesPanelSpec.OrbState`.

    /**
     * Whether the official `Hi, 我在听` line is on screen.
     *
     * It belongs to the **wake** route and only before the wearer's own line has arrived: a proactive turn is
     * thinking rather than listening (the phone is not waiting for a wake utterance), and once the wearer has spoken
     * the turn has moved to thinking. `lastRole == null` is the fresh-wake case and counts as listening.
     */
    fun listeningVisible(
        busy: Boolean,
        live: Boolean,
        route: AssistantRoute,
        lastRole: ConversationRole?,
    ): Boolean = busy && !live && route == AssistantRoute.WAKE && lastRole != ConversationRole.USER

    /**
     * Whether the `正在思考` row is on screen.
     *
     * The proactive route thinks from the moment the phone reports the level; the wake route thinks once the wearer's
     * line is in (the assistant has not answered yet). A live answer owns the panel, so thinking is false then.
     */
    fun thinkingVisible(
        busy: Boolean,
        live: Boolean,
        route: AssistantRoute,
        lastRole: ConversationRole?,
    ): Boolean = busy && !live && (route == AssistantRoute.PROACTIVE || lastRole == ConversationRole.USER)

    /** The orb state, highest-priority first: a live answer, then listening, then thinking, then idle. */
    fun orbState(
        busy: Boolean,
        live: Boolean,
        route: AssistantRoute,
        lastRole: ConversationRole?,
    ): OrbState = when {
        live -> OrbState.ANSWERING
        listeningVisible(busy, live, route, lastRole) -> OrbState.LISTENING
        thinkingVisible(busy, live, route, lastRole) -> OrbState.THINKING
        else -> OrbState.IDLE
    }

    /** The bottom-right compliance label; official copy, never translated by a template. */
    fun complianceText(): String = COMPLIANCE_TEXT

    fun textSizePx(role: ConversationRole): Float = when (role) {
        ConversationRole.USER -> USER_TEXT_SIZE_PX
        ConversationRole.ASSISTANT -> ASSISTANT_TEXT_SIZE_PX
    }

    fun textColor(role: ConversationRole): Int = when (role) {
        ConversationRole.USER -> GREEN_NORMAL_70
        ConversationRole.ASSISTANT -> GREEN_NORMAL
    }

    /** The tool row's label colour: full green while running, 50% once the action returned. */
    fun toolTextColor(phase: ToolPhase): Int = when (phase) {
        ToolPhase.START -> TOOL_TEXT_START
        ToolPhase.END -> TOOL_TEXT_END
    }

    fun maxLines(role: ConversationRole): Int = when (role) {
        ConversationRole.USER -> USER_MAX_LINES
        ConversationRole.ASSISTANT -> ASSISTANT_MAX_LINES
    }
}

/**
 * The four states the official PAG orb is observed in.
 *
 * Top-level so the Activity's `setState` and the JVM tests name the same type without a qualifier; the mapping from
 * the conversation is [GlassesPanelSpec.orbState].
 */
enum class OrbState { IDLE, LISTENING, THINKING, ANSWERING }
