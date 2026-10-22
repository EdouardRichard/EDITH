package com.sheen.rokid.glasses.display

import com.sheen.rokid.contracts.AssistantRoute
import com.sheen.rokid.contracts.ToolPhase
import com.sheen.rokid.output.ConversationRole
import com.sheen.rokid.output.OfficialStatus

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

    // ---------------------------------------------------------------- official item spacing (Task 19)

    /**
     * `recycler_item_page_ai.xml`: the ASR item root is `marginTop=10px marginBottom=4px marginStart/End=10px`; the TTS
     * line is `marginTop=5px marginBottom=10px` with `paddingStart/End=10px`; the thinking/tool rows are exactly
     * `layout_height=22px` with a 10 px icon start margin and a 5 px copy start margin.
     *
     * The scroll view no longer re-adds a 10 px side padding: each row carries the official inset itself, so the two
     * sources cannot double to 20 px.
     */
    const val ASR_ROW_MARGIN_TOP_PX = 10
    const val ASR_ROW_MARGIN_BOTTOM_PX = 4
    const val ASR_ROW_MARGIN_SIDE_PX = 10
    const val TTS_ROW_MARGIN_TOP_PX = 5
    const val TTS_ROW_MARGIN_BOTTOM_PX = 10
    const val TTS_SIDE_PADDING_PX = 10
    const val STATE_ROW_HEIGHT_PX = 22
    const val STATE_ICON_MARGIN_START_PX = 10
    const val STATE_TEXT_MARGIN_START_PX = 5

    /** `imv_asr_loading`: the official 26x13 px ASR waiting GIF, with its own 10 px start/top margins. */
    const val ASR_GIF_WIDTH_PX = 26
    const val ASR_GIF_HEIGHT_PX = 13
    const val ASR_GIF_MARGIN_START_PX = 10
    const val ASR_GIF_MARGIN_TOP_PX = 10

    // ---------------------------------------------------------------- official green scale

    /** `green_normal`: the assistant's answer, the listening line, the thinking line and the orb. */
    val GREEN_NORMAL: Int = 0xFF40FF5E.toInt()

    /** `green_normal_70`: the wearer's own line once the answer has arrived. */
    val GREEN_NORMAL_70: Int = 0xB340FF5E.toInt()

    /**
     * 0.24 S4 Task 17: the tool row's start/end copy, exactly the official `AiAdapter.agentToolStatus` runtime values -
     * START binds `R.color.green` and END restyles the same label to `R.color.green_50` (`colors.xml`). The panel's
     * conversation greys/greens (`green_normal`/`green_normal_70`) are a different scale and are not substituted here;
     * spec §5.6's "绿阶保持不变" is about the existing conversation rows, not the new tool row. Task 19 replaces the
     * stand-in icon with the official `ai_agent_tool_*` GIFs; the copy colours stay these.
     */
    val TOOL_TEXT_START: Int = 0xFF00FF00.toInt()
    val TOOL_TEXT_END: Int = 0x8000FF00.toInt()

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

    // 0.24 S4 Task 17 (review M2): `textColor(role)` was removed. The rendered rule is not per role alone - the
    // wearer's line is full green while recognising and 70% once this turn's answer exists (`ConversationView.TurnRow`),
    // so a role-only colour table was a stale second authority a future caller could reintroduce the old rule from.

    /** The tool row's label colour: full green while running, 50% once the action returned. */
    fun toolTextColor(phase: ToolPhase): Int = when (phase) {
        ToolPhase.START -> TOOL_TEXT_START
        ToolPhase.END -> TOOL_TEXT_END
    }

    fun maxLines(role: ConversationRole): Int = when (role) {
        ConversationRole.USER -> USER_MAX_LINES
        ConversationRole.ASSISTANT -> ASSISTANT_MAX_LINES
    }

    /**
     * 0.24 S4 Task 18: the four-state probe shape for an official status.
     *
     * This is the status branch of the Task 16/17 `orbStateOf` decision, moved here so the PAG view's fallback and the
     * controller's probe cannot grow two different status tables: 0/1/2 are the listener chain, 4 is the thought, and
     * 3/5/7 are the answer/finish chain (spec §3.4). It is not a PAG segment decision - [OrbPagChain] owns that.
     */
    fun orbStateForStatus(status: OfficialStatus): OrbState = when (status) {
        OfficialStatus.ANSWERING, OfficialStatus.AUDIO_FINISHED, OfficialStatus.TOOL -> OrbState.ANSWERING
        OfficialStatus.ASR_END -> OrbState.THINKING
        OfficialStatus.EMPTY, OfficialStatus.LISTENING, OfficialStatus.ASR -> OrbState.LISTENING
    }
}

/**
 * The four states the official PAG orb is observed in.
 *
 * Top-level so the Activity's `setState` and the JVM tests name the same type without a qualifier; the mapping from
 * the conversation is [GlassesPanelSpec.orbState].
 */
enum class OrbState { IDLE, LISTENING, THINKING, ANSWERING }

/**
 * 0.24 S4 Task 18 (spec §3.4): the official PAG orb's status→segment and animation-end chain, as pure decisions.
 *
 * The official `AiAssistantDialog` owns one `id_pv` PAGView and nine clips. Two rules decide what plays: the incoming
 * status asks for one segment, but the view only loads it when the official **tag** gate allows it; and every
 * `onAnimationEnd` looks at the tag the view currently carries to pick the next segment. Both are reproduced here so
 * the tag names, the guards and the loop cadence fail a JVM test instead of only being visible on a worn panel.
 *
 * Tag names are the official constants (`ANIMATION_LISTENER`, `ANIMATION_LISTENER_LAST`, ...). Note that
 * `listener_first` and `listener_middle` deliberately share the tag `listener`: official `cycleListeningWaiting()`
 * sets `ANIMATION_LISTENER` again after loading the middle, which is what makes the first → middle handover happen only
 * once. Do not "fix" that into two tags; it would reload the first clip on every duplicate status.
 */
object OrbPagChain {

    /** Official `ANIMATION_NONE`: the view before any clip. */
    const val TAG_NONE = "none"

    /** Official `ANIMATION_LISTENER`: both listener_first and listener_middle carry it. */
    const val TAG_LISTENER = "listener"
    const val TAG_LISTENER_LAST = "listener_last"
    const val TAG_THINK_FIRST = "think_first"
    const val TAG_THINK_MIDDLE = "think_middle"
    const val TAG_THINK_LAST = "think_last"
    const val TAG_FINISH_FIRST = "finish_first"
    const val TAG_FINISH_MIDDLE = "finish_middle"
    const val TAG_FINISH_LAST = "finish_last"

    /** Every official tag, in chain order; the JVM gate test iterates it to prove the guards cover the whole domain. */
    val ALL_TAGS: List<String> = listOf(
        TAG_NONE, TAG_LISTENER, TAG_LISTENER_LAST, TAG_THINK_FIRST, TAG_THINK_MIDDLE, TAG_THINK_LAST,
        TAG_FINISH_FIRST, TAG_FINISH_MIDDLE, TAG_FINISH_LAST,
    )

    /**
     * One official clip. [repeatCount] is libpag's own unit: 1 plays once, 0 loops forever (verified against the
     * pinned libpag 4.5.27 `PAGView.repeatCount()` contract), which is exactly how the official code drives first/last
     * versus middle.
     */
    enum class Segment(val asset: String, val tag: String, val repeatCount: Int) {
        LISTENER_FIRST("ai_listener_first.pag", TAG_LISTENER, 1),
        LISTENER_MIDDLE("ai_listener_middle.pag", TAG_LISTENER, 0),
        LISTENER_LAST("ai_listener_last.pag", TAG_LISTENER_LAST, 1),
        THINK_FIRST("ai_think_first.pag", TAG_THINK_FIRST, 1),
        THINK_MIDDLE("ai_think_middle.pag", TAG_THINK_MIDDLE, 0),
        THINK_LAST("ai_think_last.pag", TAG_THINK_LAST, 1),
        FINISH_FIRST("ai_finish_first.pag", TAG_FINISH_FIRST, 1),
        FINISH_MIDDLE("ai_finish_middle.pag", TAG_FINISH_MIDDLE, 0),
        FINISH_LAST("ai_finish_last.pag", TAG_FINISH_LAST, 1),
    }

    /** The segment a status asks for (spec §3.4's table). Whether it may load is [canLoad]'s decision. */
    fun entryFor(status: OfficialStatus): Segment = when (status) {
        // status 0 (`showNoneView`), 1 (`showListenerUI`) and 2 (`showASRResultMessage`) all call
        // loadListeningWaitingGif(), which starts at listener_first unless the view is already in the listener tag.
        OfficialStatus.EMPTY, OfficialStatus.LISTENING, OfficialStatus.ASR -> Segment.LISTENER_FIRST
        // status 4 (`showTranslateStateUI`) calls loadingGif() from the listener tag.
        OfficialStatus.ASR_END -> Segment.LISTENER_LAST
        // status 3 (`showUpdateTTSUI`) and 7 (`agentToolStatus`) call loadSuccessGif().
        OfficialStatus.ANSWERING, OfficialStatus.TOOL -> Segment.THINK_LAST
        // status 5 (`showAudioFinishUI`) calls lastFinish() unconditionally.
        OfficialStatus.AUDIO_FINISHED -> Segment.FINISH_LAST
    }

    /** Official `showIdleStatus` -> `lastFinish()`: the finish tail, then hold the last frame. */
    fun idleEntry(): Segment = Segment.FINISH_LAST

    /** The official tag gate for loading [segment] while the view carries [currentTag]. */
    fun canLoad(segment: Segment, currentTag: String): Boolean = when (segment) {
        // loadListeningWaitingGif(): `!ANIMATION_LISTENER.equals(getTag())`.
        Segment.LISTENER_FIRST -> currentTag != TAG_LISTENER
        // cycleListeningWaiting(): `ANIMATION_LISTENER.equals(getTag())`.
        Segment.LISTENER_MIDDLE -> currentTag == TAG_LISTENER
        // loadingGif(): `ANIMATION_LISTENER.equals(getTag())`.
        Segment.LISTENER_LAST -> currentTag == TAG_LISTENER
        // loadThinkGif(): `ANIMATION_LISTENER_LAST.equals(getTag())`.
        Segment.THINK_FIRST -> currentTag == TAG_LISTENER_LAST
        // cycleThinking(): `ANIMATION_THINK_FIRST.equals(getTag())`.
        Segment.THINK_MIDDLE -> currentTag == TAG_THINK_FIRST
        // loadSuccessGif(): think_middle, think_first or listener_last. Status 3 cannot force a cold listener.
        Segment.THINK_LAST ->
            currentTag == TAG_THINK_MIDDLE || currentTag == TAG_THINK_FIRST || currentTag == TAG_LISTENER_LAST
        // loadFinish(): `ANIMATION_THINK_LAST.equals(getTag())`.
        Segment.FINISH_FIRST -> currentTag == TAG_THINK_LAST
        // cycleFinish(): `ANIMATION_FINISH_FIRST.equals(getTag())`.
        Segment.FINISH_MIDDLE -> currentTag == TAG_FINISH_FIRST
        // lastFinish() has no gate: status 5 and IDLE stop whatever clip is running.
        Segment.FINISH_LAST -> true
    }

    /** The segment the official `onAnimationEnd` loads for [currentTag], or null when the chain stops there. */
    fun nextOnEnd(currentTag: String): Segment? = when (currentTag) {
        TAG_LISTENER -> Segment.LISTENER_MIDDLE
        TAG_LISTENER_LAST -> Segment.THINK_FIRST
        TAG_THINK_FIRST -> Segment.THINK_MIDDLE
        TAG_THINK_LAST -> Segment.FINISH_FIRST
        TAG_FINISH_FIRST -> Segment.FINISH_MIDDLE
        // finish_last / finish_middle / think_middle / none have no official branch: they stop or loop in place.
        else -> null
    }
}
