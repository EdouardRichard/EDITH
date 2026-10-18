package com.sheen.rokid.glasses

import android.graphics.drawable.AnimatedImageDrawable
import android.text.Spanned
import android.view.KeyEvent
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sheen.rokid.contracts.AssistantRoute
import com.sheen.rokid.contracts.ToolPhase
import com.sheen.rokid.glasses.display.GlassesPanelSpec
import com.sheen.rokid.glasses.display.GlassesPanelView
import com.sheen.rokid.glasses.display.OrbState
import com.sheen.rokid.glasses.display.TailTextView
import com.sheen.rokid.output.HintCommand
import com.sheen.rokid.output.HintWireCodec
import com.sheen.rokid.output.OfficialConversation
import com.sheen.rokid.output.OfficialStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 0.23 P3 device-side view check: the official panel's state wiring, asserted on the committed view rather than a log.
 *
 * This does not claim the wearer can read it (that is P6's subjective item); it claims that the state the phone
 * reported maps to the line/orb/tips the panel actually shows. The inbound path is the production one except the
 * bridge: the same `acceptCommand` the CXR callback drives is fed the same bytes the phone encodes.
 */
@RunWith(AndroidJUnit4::class)
class GlassesPanelStateTest {

    /**
     * Lets the UI thread run the posted commands and one layout pass.
     *
     * Deliberately **not** `waitForIdleSync`: the orb's breathing animator runs while listening/answering, so "idle"
     * is not a state this screen reaches, and the instrumentation wait would block until the busy level expired.
     */
    private fun waitForLayout() {
        Thread.sleep(700)
    }

    private fun syncJson() = HintWireCodec.encode(
        HintCommand.Sync("sync-p3", offsetLowerNanos = 0, offsetUpperNanos = 1_000_000,
            validUntilGlassNanos = System.nanoTime() + 30_000_000_000L),
    )

    /**
     * 0.24 S2 Task 7: the panel tree lives in [GlassesPanelView]; this pins that the Activity shows that view.
     *
     * The assertion is deliberately structural. The extraction's contract is that the *one* panel the wearer sees is
     * the extracted class - an indirect probe (a text or an orb state) would still pass if the Activity built a second,
     * stale tree, which is exactly the regression this refactor must not introduce.
     */
    @Test
    fun theActivityShowsTheExtractedPanelView() {
        launchHostPanel().use { scenario ->
            scenario.onActivity { activity ->
                val content = requireNotNull(activity.findViewById<ViewGroup>(android.R.id.content)) {
                    "the Activity must have a content view"
                }
                assertTrue("the Activity's content must be the extracted GlassesPanelView, was ${content.getChildAt(0)}",
                    content.getChildAt(0) is GlassesPanelView)
            }
        }
    }

    @Test
    fun theOfficialListeningStatesShowHiThenThinkingThenTheAnswer() {
        launchHostPanel().use { scenario ->
            scenario.onActivity { activity ->
                assertEquals("nothing is happening yet", OrbState.IDLE, activity.orbStateForProbe())
                assertFalse(activity.listeningVisibleForProbe())
                assertFalse(activity.hiVisibleForProbe())
                assertFalse(activity.thinkingVisibleForProbe())
            }

            scenario.onActivity { activity ->
                activity.acceptCommand(HintWireCodec.encode(HintCommand.Hello("s-p3", 1)))
                activity.acceptCommand(HintWireCodec.encode(
                    HintCommand.State("s-p3", 1, AssistantRoute.WAKE, turnId = "turn-p3",
                        status = OfficialStatus.LISTENING.wire, sequence = 1)))
            }
            waitForLayout()
            scenario.onActivity { activity ->
                assertTrue("status 1 owns the \"…\"/listener row", activity.listeningVisibleForProbe())
                assertTrue("the first listening turn shows Hi", activity.hiVisibleForProbe())
                assertFalse(activity.thinkingVisibleForProbe())
                assertEquals(OrbState.LISTENING, activity.orbStateForProbe())
                assertEquals(GlassesPanelSpec.LISTENING_TEXT, activity.listeningTextForProbe())
            }

            scenario.onActivity { activity ->
                activity.acceptCommand(HintWireCodec.encode(
                    HintCommand.Said("s-p3", 1, sequence = 1, route = AssistantRoute.WAKE,
                        entryId = "said-p3", text = "现在几点了")))
                activity.acceptCommand(HintWireCodec.encode(
                    HintCommand.State("s-p3", 1, AssistantRoute.WAKE, turnId = "turn-p3",
                        status = OfficialStatus.ASR.wire, sequence = 2)))
            }
            waitForLayout()
            scenario.onActivity { activity ->
                assertFalse("status 2 retires the listening row", activity.listeningVisibleForProbe())
                assertFalse("and Hi", activity.hiVisibleForProbe())
                assertFalse("status 2 is ASR in progress, not the thinking row", activity.thinkingVisibleForProbe())
                assertEquals("the listener orb still belongs to ASR", OrbState.LISTENING,
                    activity.orbStateForProbe())
            }

            scenario.onActivity { activity ->
                activity.acceptCommand(HintWireCodec.encode(
                    HintCommand.State("s-p3", 1, AssistantRoute.WAKE, turnId = "turn-p3",
                        status = OfficialStatus.ASR_END.wire, sequence = 3)))
            }
            waitForLayout()
            scenario.onActivity { activity ->
                assertFalse(activity.listeningVisibleForProbe())
                assertFalse(activity.hiVisibleForProbe())
                assertTrue("status 4 is the official thinking row", activity.thinkingVisibleForProbe())
                assertEquals(OrbState.THINKING, activity.orbStateForProbe())
                assertEquals(GlassesPanelSpec.THINKING_TEXT, activity.thinkingTextForProbe())
            }

            scenario.onActivity { activity ->
                activity.acceptCommand(syncJson())
                activity.acceptCommand(HintWireCodec.encode(
                    HintCommand.State("s-p3", 1, AssistantRoute.WAKE, turnId = "turn-p3",
                        status = OfficialStatus.ANSWERING.wire, sequence = 4)))
                activity.acceptCommand(HintWireCodec.encode(
                    HintCommand.Show("s-p3", 1, sequence = 3, hintId = "h-p3", revision = 1,
                        syncId = "sync-p3", phoneSentNanos = System.nanoTime(), remainingTtlMillis = 5_000,
                        text = "现在是凌晨十二点")))
            }
            waitForLayout()
            scenario.onActivity { activity ->
                assertEquals(OrbState.ANSWERING, activity.orbStateForProbe())
                assertFalse(activity.listeningVisibleForProbe())
                assertFalse(activity.hiVisibleForProbe())
                assertFalse(activity.thinkingVisibleForProbe())
                assertEquals("现在是凌晨十二点", activity.hintText())
            }
        }
    }

    @Test
    fun theAutomaticNextTurnListensWithoutSayingHiAgain() {
        launchHostPanel().use { scenario ->
            scenario.onActivity { activity ->
                activity.acceptCommand(HintWireCodec.encode(HintCommand.Hello("s-hi", 1)))
                activity.acceptCommand(HintWireCodec.encode(
                    HintCommand.State("s-hi", 1, AssistantRoute.WAKE, turnId = "turn-hi-1",
                        status = OfficialStatus.LISTENING.wire, sequence = 1)))
            }
            waitForLayout()
            scenario.onActivity { activity ->
                assertTrue("first listening turn: Hi is visible", activity.hiVisibleForProbe())
            }

            scenario.onActivity { activity ->
                activity.acceptCommand(HintWireCodec.encode(
                    HintCommand.State("s-hi", 1, AssistantRoute.WAKE, turnId = "turn-hi-1",
                        status = OfficialStatus.AUDIO_FINISHED.wire, sequence = 2)))
            }
            waitForLayout()
            scenario.onActivity { activity ->
                assertFalse("status 5 has finished speaking", activity.hiVisibleForProbe())
            }

            scenario.onActivity { activity ->
                activity.acceptCommand(HintWireCodec.encode(
                    HintCommand.State("s-hi", 1, AssistantRoute.WAKE, turnId = "turn-hi-2",
                        status = OfficialStatus.LISTENING.wire, sequence = 3)))
            }
            waitForLayout()
            scenario.onActivity { activity ->
                assertTrue("the continuation owns the \"…\" row", activity.listeningVisibleForProbe())
                assertFalse("but showListenerUI never restores Hi", activity.hiVisibleForProbe())
                assertTrue("unpinned: the 单击钉住 hint is shown", activity.pinTextVisibleForProbe())
                assertFalse("and the pin icon is not", activity.pinIconVisibleForProbe())
            }
        }
    }

    @Test
    fun aProactiveTurnThinksOnlyOnItsOwnRoute() {
        launchHostPanel().use { scenario ->
            scenario.onActivity { activity ->
                activity.acceptCommand(HintWireCodec.encode(HintCommand.Hello("s-p3", 1)))
                activity.acceptCommand(HintWireCodec.encode(
                    HintCommand.State("s-p3", 1, AssistantRoute.PROACTIVE, turnId = "turn-pro",
                        status = OfficialStatus.ASR_END.wire, sequence = 1)))
            }
            waitForLayout()
            scenario.onActivity { activity ->
                // U8 route isolation: a proactive level must not paint the wake conversation with proactive state.
                assertFalse("the wake view must not borrow the proactive turn's thinking row",
                    activity.thinkingVisibleForProbe())
                assertFalse(activity.listeningVisibleForProbe())
                assertEquals(OrbState.IDLE, activity.orbStateForProbe())
            }

            scenario.onActivity { activity ->
                activity.acceptCommand(syncJson())
                activity.acceptCommand(HintWireCodec.encode(
                    HintCommand.State("s-p3", 1, AssistantRoute.PROACTIVE, turnId = "turn-pro",
                        status = OfficialStatus.ANSWERING.wire, sequence = 2)))
                activity.acceptCommand(HintWireCodec.encode(
                    HintCommand.Show("s-p3", 1, sequence = 1, hintId = "h-proactive", revision = 1,
                        syncId = "sync-p3", phoneSentNanos = System.nanoTime(), remainingTtlMillis = 30_000,
                        text = "主动提醒一句", route = AssistantRoute.PROACTIVE)))
            }
            waitForLayout()
            scenario.onActivity { activity ->
                assertEquals(AssistantRoute.PROACTIVE, activity.selectedRoute())
                assertEquals("the live proactive answer owns the orb", OrbState.ANSWERING, activity.orbStateForProbe())
                assertFalse("a live answer is not a thinking row", activity.thinkingVisibleForProbe())
            }

            scenario.onActivity { activity ->
                activity.acceptCommand(HintWireCodec.encode(
                    HintCommand.Clear("s-p3", 1, sequence = 2)))
                activity.acceptCommand(HintWireCodec.encode(
                    HintCommand.State("s-p3", 1, AssistantRoute.PROACTIVE, turnId = "turn-pro",
                        status = OfficialStatus.ASR_END.wire, sequence = 3)))
            }
            waitForLayout()
            scenario.onActivity { activity ->
                assertEquals(AssistantRoute.PROACTIVE, activity.selectedRoute())
                assertTrue("with the answer retired and status 4, the proactive route thinks",
                    activity.thinkingVisibleForProbe())
                assertEquals(OrbState.THINKING, activity.orbStateForProbe())
            }
        }
    }

    @Test
    fun thePanelCarriesTheOfficialFixedCopy() {
        launchHostPanel().use { scenario ->
            scenario.onActivity { activity ->
                assertEquals(GlassesPanelSpec.COMPLIANCE_TEXT, activity.complianceTextForProbe())
                assertEquals(OfficialConversation.TIPS_EXIT, activity.tipsTextForProbe())
            }
        }
    }

    @Test
    fun theWearersLineShowsTheNewestFourLinesAndAlignsLeft() {
        launchHostPanel().use { scenario ->
            scenario.onActivity { activity ->
                activity.acceptCommand(HintWireCodec.encode(HintCommand.Hello("s-p3", 1)))
                activity.acceptCommand(HintWireCodec.encode(
                    HintCommand.Said("s-p3", 1, sequence = 1, route = AssistantRoute.WAKE,
                        entryId = "said-long", text = "第一行\n第二行\n第三行\n第四行\n第五行\n第六行")))
            }
            waitForLayout()
            scenario.onActivity { activity ->
                val userRow = requireNotNull(activity.lastMessageRowForProbe()) {
                    "the wearer's line must have been rendered"
                } as TailTextView
                assertEquals("the wearer's line is capped at four lines", 4,
                    userRow.maxVisibleLinesForProbe())
                assertTrue("six lines must be tail-clipped", userRow.isTailClippedForProbe())
                assertEquals("the newest line must survive the cap", "第六行",
                    userRow.visibleLineTextForProbe().last())
                val absolute = android.view.Gravity.getAbsoluteGravity(
                    userRow.gravity, userRow.layoutDirection)
                assertEquals("and aligns left like every other line",
                    android.view.Gravity.LEFT,
                    absolute and android.view.Gravity.HORIZONTAL_GRAVITY_MASK)
                assertEquals("0.24 S4: while the turn is still recognising, the wearer's line is full green",
                    GlassesPanelSpec.GREEN_NORMAL, userRow.currentTextColor)
            }
        }
    }

    // ---------------------------------------------------------------- per-turn items (0.24 S4 Task 17)

    private fun syncFor(activity: com.sheen.rokid.glasses.app.GlassesActivity, syncId: String = "sync-s4") {
        activity.acceptCommand(HintWireCodec.encode(
            HintCommand.Sync(syncId, offsetLowerNanos = 0, offsetUpperNanos = 1_000_000,
                validUntilGlassNanos = System.nanoTime() + 30_000_000_000L)))
    }

    @Test
    fun aToolRunShowsThe22PxToolRowAndTheEndRestylesTheSameRow() {
        launchHostPanel().use { scenario ->
            scenario.onActivity { activity ->
                activity.acceptCommand(HintWireCodec.encode(HintCommand.Hello("s-tool", 1)))
                activity.acceptCommand(HintWireCodec.encode(
                    HintCommand.State("s-tool", 1, AssistantRoute.WAKE, turnId = "turn-tool",
                        status = OfficialStatus.TOOL.wire, sequence = 1)))
                activity.acceptCommand(HintWireCodec.encode(
                    HintCommand.Tool("s-tool", 1, AssistantRoute.WAKE, turnId = "turn-tool",
                        phase = ToolPhase.START, label = "记笔记")))
            }
            waitForLayout()
            var startedRow: android.view.View? = null
            scenario.onActivity { activity ->
                assertTrue("status 7 must paint the tool row", activity.toolVisibleForProbe())
                assertEquals("记笔记", activity.toolTextForProbe())
                assertEquals("the tool icon slot is the official 22 px",
                    GlassesPanelSpec.TOOL_ICON_PX, activity.toolIconWidthForProbe())
                assertEquals("the label is 16 px", GlassesPanelSpec.TOOL_TEXT_SIZE_PX,
                    activity.toolLabelTextSizeForProbe(), 0.1f)
                assertEquals("START is the official full green #FF00FF00", 0xFF00FF00.toInt(),
                    activity.toolTextColorForProbe())
                startedRow = activity.toolRowForProbe()
                activity.acceptCommand(HintWireCodec.encode(
                    HintCommand.Tool("s-tool", 1, AssistantRoute.WAKE, turnId = "turn-tool",
                        phase = ToolPhase.END, label = "记笔记")))
            }
            waitForLayout()
            scenario.onActivity { activity ->
                assertSame("the END restyles the row it started, never a second row",
                    startedRow, activity.toolRowForProbe())
                assertEquals("END is the official 50% green #8000FF00", 0x8000FF00.toInt(),
                    activity.toolTextColorForProbe())

                activity.acceptCommand(HintWireCodec.encode(
                    HintCommand.State("s-tool", 1, AssistantRoute.WAKE, turnId = "turn-tool",
                        status = OfficialStatus.ANSWERING.wire, sequence = 2)))
            }
            waitForLayout()
            scenario.onActivity { activity ->
                assertFalse("the answer level retires the finished tool row", activity.toolVisibleForProbe())
            }
        }
    }

    @Test
    fun hidingThePanelStopsTheRunningToolIcon() {
        // Task 17 review M1: the thinking icon was already stopped from onStop/destroy; the tool icon must not outlive
        // the visible window either.
        launchHostPanel().use { scenario ->
            scenario.onActivity { activity ->
                activity.acceptCommand(HintWireCodec.encode(HintCommand.Hello("s-stop", 1)))
                activity.acceptCommand(HintWireCodec.encode(
                    HintCommand.State("s-stop", 1, AssistantRoute.WAKE, turnId = "turn-stop",
                        status = OfficialStatus.TOOL.wire, sequence = 1)))
                activity.acceptCommand(HintWireCodec.encode(
                    HintCommand.Tool("s-stop", 1, AssistantRoute.WAKE, turnId = "turn-stop",
                        phase = ToolPhase.START, label = "记笔记")))
            }
            waitForLayout()
            scenario.onActivity { activity ->
                assertTrue("the START ring runs while the panel is visible", activity.toolIconRunningForProbe())
            }

            scenario.moveToState(Lifecycle.State.CREATED)

            scenario.onActivity { activity ->
                assertFalse("the hidden window must stop the tool ring, as it already does the thinking one",
                    activity.toolIconRunningForProbe())
            }
            scenario.moveToState(Lifecycle.State.RESUMED)
        }
    }

    @Test
    fun theThinkingRowUsesThe22PxIconAnd16PxCopyAndStaysUntilTheAnswerTextArrives() {
        launchHostPanel().use { scenario ->
            scenario.onActivity { activity ->
                activity.acceptCommand(HintWireCodec.encode(HintCommand.Hello("s-think", 1)))
                activity.acceptCommand(HintWireCodec.encode(
                    HintCommand.State("s-think", 1, AssistantRoute.WAKE, turnId = "turn-think",
                        status = OfficialStatus.ANSWERING.wire, sequence = 1)))
            }
            waitForLayout()
            scenario.onActivity { activity ->
                assertTrue("status 3 with no answer text yet still thinks", activity.thinkingVisibleForProbe())
                assertEquals(GlassesPanelSpec.THINKING_ICON_PX, activity.thinkingIconWidthForProbe())
                assertEquals(GlassesPanelSpec.THINKING_TEXT_SIZE_PX,
                    activity.thinkingTextSizeForProbe(), 0.1f)

                activity.acceptCommand(HintWireCodec.encode(
                    HintCommand.Answer("s-think", 1, AssistantRoute.WAKE, turnId = "turn-think",
                        hintId = "h-think", revision = 1, text = "答案来了")))
            }
            waitForLayout()
            scenario.onActivity { activity ->
                assertFalse("the answer row replaces the thinking row", activity.thinkingVisibleForProbe())
                assertEquals("答案来了", requireNotNull(activity.lastMessageRowForProbe()).text.toString())
            }
        }
    }

    @Test
    fun anAnswerRevisionPatchesTheSameRowAndTheFinalShowKeepsIt() {
        launchHostPanel().use { scenario ->
            scenario.onActivity { activity ->
                activity.acceptCommand(HintWireCodec.encode(HintCommand.Hello("s-stream", 1)))
                syncFor(activity)
                activity.acceptCommand(HintWireCodec.encode(
                    HintCommand.Answer("s-stream", 1, AssistantRoute.WAKE, turnId = "turn-stream",
                        hintId = "h-stream", revision = 1, text = "今天")))
            }
            waitForLayout()
            var streamedRow: TextView? = null
            scenario.onActivity { activity ->
                streamedRow = requireNotNull(activity.lastMessageRowForProbe())
                assertEquals("今天", streamedRow?.text.toString())
                activity.acceptCommand(HintWireCodec.encode(
                    HintCommand.Answer("s-stream", 1, AssistantRoute.WAKE, turnId = "turn-stream",
                        hintId = "h-stream", revision = 2, text = "今天晴。")))
            }
            waitForLayout()
            scenario.onActivity { activity ->
                assertSame("a revision must patch the row, not rebuild the list",
                    streamedRow, activity.lastMessageRowForProbe())
                assertEquals("今天晴。", activity.lastMessageRowForProbe()?.text.toString())

                activity.acceptCommand(HintWireCodec.encode(
                    HintCommand.Show("s-stream", 1, sequence = 1, hintId = "h-stream", revision = 1,
                        syncId = "sync-s4", phoneSentNanos = System.nanoTime(), remainingTtlMillis = 30_000,
                        text = "今天晴。")))
            }
            waitForLayout()
            scenario.onActivity { activity ->
                assertSame("the final SHOW replaces the same turn/answer row",
                    streamedRow, activity.lastMessageRowForProbe())
                assertEquals("今天晴。", activity.hintText())

                // A late revision after the final must not reopen or overwrite the row.
                activity.acceptCommand(HintWireCodec.encode(
                    HintCommand.Answer("s-stream", 1, AssistantRoute.WAKE, turnId = "turn-stream",
                        hintId = "h-stream", revision = 9, text = "迟到答案")))
            }
            waitForLayout()
            scenario.onActivity { activity ->
                assertSame(streamedRow, activity.lastMessageRowForProbe())
                assertEquals("今天晴。", activity.lastMessageRowForProbe()?.text.toString())
            }
        }
    }

    @Test
    fun anAsrRevisionPatchesTheSameUserRow() {
        launchHostPanel().use { scenario ->
            scenario.onActivity { activity ->
                activity.acceptCommand(HintWireCodec.encode(HintCommand.Hello("s-asr", 1)))
                activity.acceptCommand(HintWireCodec.encode(
                    HintCommand.Asr("s-asr", 1, AssistantRoute.WAKE, turnId = "turn-asr",
                        revision = 1, text = "帮我看")))
            }
            waitForLayout()
            var userRow: TextView? = null
            scenario.onActivity { activity ->
                userRow = requireNotNull(activity.lastMessageRowForProbe())
                assertEquals("帮我看", userRow?.text.toString())
                activity.acceptCommand(HintWireCodec.encode(
                    HintCommand.Asr("s-asr", 1, AssistantRoute.WAKE, turnId = "turn-asr",
                        revision = 2, text = "帮我看下日程")))
            }
            waitForLayout()
            scenario.onActivity { activity ->
                assertSame("an ASR revision patches the user row in place", userRow,
                    activity.lastMessageRowForProbe())
                assertEquals("帮我看下日程", activity.lastMessageRowForProbe()?.text.toString())
            }
        }
    }

    /**
     * 0.24 S4 Task 17 I1: the production answer is `notify_user` with no `ANSWER` partial. The final `SHOW` must join
     * the user line under the turn the accepted `STATE 3` named and dim that line to 70 %, instead of appending a
     * second, turn-less item.
     */
    @Test
    fun aFinalOnlyNotifyAnswerPairsTheWearersLineAndDimsIt() {
        launchHostPanel().use { scenario ->
            scenario.onActivity { activity ->
                activity.acceptCommand(HintWireCodec.encode(HintCommand.Hello("s-pair", 1)))
                syncFor(activity)
                activity.acceptCommand(HintWireCodec.encode(
                    HintCommand.Asr("s-pair", 1, AssistantRoute.WAKE, turnId = "turn-pair",
                        revision = 1, text = "帮我看下日程")))
            }
            waitForLayout()
            scenario.onActivity { activity ->
                val rows = activity.conversationRowsForProbe()
                assertEquals("only the wearer's line exists yet", 1, rows.size)
                assertEquals("while recognising the line is full green",
                    GlassesPanelSpec.GREEN_NORMAL, rows[0].currentTextColor)
                assertEquals(1, activity.conversationTurnCountForProbe())

                // The phone publishes STATE 3 for the turn before submitting the hint; the hint itself carries no turn.
                activity.acceptCommand(HintWireCodec.encode(
                    HintCommand.State("s-pair", 1, AssistantRoute.WAKE, turnId = "turn-pair",
                        status = OfficialStatus.ANSWERING.wire, sequence = 1)))
                activity.acceptCommand(HintWireCodec.encode(
                    HintCommand.Show("s-pair", 1, sequence = 1, hintId = "h-pair", revision = 1,
                        syncId = "sync-s4", phoneSentNanos = System.nanoTime(), remainingTtlMillis = 30_000,
                        text = "今天十点开会")))
            }
            waitForLayout()
            scenario.onActivity { activity ->
                val rows = activity.conversationRowsForProbe()
                assertEquals("the answer joins the same item instead of appending a second one",
                    1, activity.conversationTurnCountForProbe())
                assertEquals(2, rows.size)
                assertEquals("帮我看下日程", rows[0].text.toString())
                assertEquals("once the answer is in the wearer's line drops to 70%",
                    GlassesPanelSpec.GREEN_NORMAL_70, rows[0].currentTextColor)
                assertEquals("今天十点开会", rows[1].text.toString())
                assertEquals("the answer itself stays full green",
                    GlassesPanelSpec.GREEN_NORMAL, rows[1].currentTextColor)
                assertFalse("the answer text retires the thinking row", activity.thinkingVisibleForProbe())
            }
        }
    }

    // ---------------------------------------------------------------- official GIFs, Markdown and panel visuals (Task 19)

    private fun spansOf(view: TextView): List<Any> {
        val text = view.text as? Spanned ?: return emptyList()
        return text.getSpans(0, text.length, Any::class.java).toList()
    }

    private fun hasSpan(view: TextView, simpleName: String): Boolean =
        spansOf(view).any { it.javaClass.simpleName == simpleName }

    @Test
    fun theAsrWaitingGifIsTheOfficial26By13AndOnlyForStatusZeroAndOne() {
        launchHostPanel().use { scenario ->
            scenario.onActivity { activity ->
                activity.acceptCommand(HintWireCodec.encode(HintCommand.Hello("s-asrgif", 1)))
                activity.acceptCommand(HintWireCodec.encode(
                    HintCommand.State("s-asrgif", 1, AssistantRoute.WAKE, turnId = "turn-asrgif",
                        status = OfficialStatus.EMPTY.wire, sequence = 1)))
            }
            waitForLayout()
            scenario.onActivity { activity ->
                assertTrue("status 0 shows the ASR waiting GIF", activity.asrGifVisibleForProbe())
                assertEquals("the official `imv_asr_loading` is 26 px wide", 26, activity.asrGifWidthForProbe())
                assertEquals(13, activity.asrGifHeightForProbe())
                assertTrue("the official GIF is animated, not a static frame", activity.asrGifAnimatedForProbe())
                assertTrue("the official GIF is playing", activity.asrGifRunningForProbe())
                // `ImageDecoder` applies the resource density (the glasses are 240 dpi = 1.5x), so the 40x20 mdpi GIF
                // reports 60x30 intrinsic pixels here; the view slot above is still the official 26x13 px.
                val density = activity.resources.displayMetrics.density
                assertEquals("the decoded official asset is the 40x20 gif_loading.gif",
                    (40 * density).toInt(), activity.asrGifIntrinsicWidthForProbe())
                assertEquals((20 * density).toInt(), activity.asrGifIntrinsicHeightForProbe())
                val params = requireNotNull(activity.asrGifViewForProbe()?.layoutParams) {
                    "the waiting GIF view must be attached"
                } as LinearLayout.LayoutParams
                assertEquals("official item marginLeft=10", GlassesPanelSpec.ASR_GIF_MARGIN_START_PX,
                    params.marginStart)
                assertEquals("official marginTop=10", GlassesPanelSpec.ASR_GIF_MARGIN_TOP_PX, params.topMargin)

                activity.acceptCommand(HintWireCodec.encode(
                    HintCommand.State("s-asrgif", 1, AssistantRoute.WAKE, turnId = "turn-asrgif",
                        status = OfficialStatus.LISTENING.wire, sequence = 2)))
            }
            waitForLayout()
            scenario.onActivity { activity ->
                assertTrue("status 1 keeps the waiting GIF; the automatic next turn only listens",
                    activity.asrGifVisibleForProbe())
                activity.acceptCommand(HintWireCodec.encode(
                    HintCommand.Said("s-asrgif", 1, sequence = 1, route = AssistantRoute.WAKE,
                        entryId = "said-asrgif", text = "现在几点了")))
                activity.acceptCommand(HintWireCodec.encode(
                    HintCommand.State("s-asrgif", 1, AssistantRoute.WAKE, turnId = "turn-asrgif",
                        status = OfficialStatus.ASR.wire, sequence = 3)))
            }
            waitForLayout()
            scenario.onActivity { activity ->
                assertFalse("status 2's ASR text replaces the waiting GIF", activity.asrGifVisibleForProbe())
            }
        }
    }

    @Test
    fun theThinkingAndToolRowsUseTheOfficialGifsAndStartEnd() {
        launchHostPanel().use { scenario ->
            scenario.onActivity { activity ->
                activity.acceptCommand(HintWireCodec.encode(HintCommand.Hello("s-gifs", 1)))
                activity.acceptCommand(HintWireCodec.encode(
                    HintCommand.State("s-gifs", 1, AssistantRoute.WAKE, turnId = "turn-gifs",
                        status = OfficialStatus.ASR_END.wire, sequence = 1)))
            }
            waitForLayout()
            scenario.onActivity { activity ->
                assertTrue("status 4 paints the thinking row", activity.thinkingVisibleForProbe())
                assertEquals("`imv_loading` is the official `ai_tts_thinking.gif`", "ai_tts_thinking",
                    activity.thinkingGifResourceForProbe())
                assertEquals(GlassesPanelSpec.THINKING_ICON_PX, activity.thinkingGifWidthForProbe())
                assertEquals(GlassesPanelSpec.THINKING_ICON_PX, activity.thinkingGifHeightForProbe())
                assertTrue("the official thinking GIF is animated", activity.thinkingGifAnimatedForProbe())
                assertTrue("the thinking GIF must be running", activity.thinkingGifRunningForProbe())
                val density = activity.resources.displayMetrics.density
                assertEquals("the decoded official asset is the 72x72 ai_tts_thinking.gif",
                    (72 * density).toInt(), activity.thinkingGifIntrinsicWidthForProbe())
                assertEquals((72 * density).toInt(), activity.thinkingGifIntrinsicHeightForProbe())
                assertEquals("the row is exactly the official 22 px tall", 22,
                    activity.thinkingRowHeightForProbe())

                activity.acceptCommand(HintWireCodec.encode(
                    HintCommand.State("s-gifs", 1, AssistantRoute.WAKE, turnId = "turn-gifs",
                        status = OfficialStatus.TOOL.wire, sequence = 2)))
                activity.acceptCommand(HintWireCodec.encode(
                    HintCommand.Tool("s-gifs", 1, AssistantRoute.WAKE, turnId = "turn-gifs",
                        phase = ToolPhase.START, label = "记一条笔记")))
            }
            waitForLayout()
            var startedRow: android.view.View? = null
            scenario.onActivity { activity ->
                assertTrue(activity.toolVisibleForProbe())
                assertEquals("START is the official `ai_agent_tool_start.gif`", "ai_agent_tool_start",
                    activity.toolGifResourceForProbe())
                assertEquals(GlassesPanelSpec.TOOL_ICON_PX, activity.toolGifWidthForProbe())
                assertEquals(GlassesPanelSpec.TOOL_ICON_PX, activity.toolGifHeightForProbe())
                assertTrue(activity.toolGifRunningForProbe())
                assertEquals("the start GIF loops while the action runs", AnimatedImageDrawable.REPEAT_INFINITE,
                    activity.toolGifRepeatCountForProbe())
                assertEquals("the row is exactly the official 22 px tall", 22,
                    requireNotNull(activity.toolRowForProbe()).height)
                startedRow = activity.toolRowForProbe()

                activity.acceptCommand(HintWireCodec.encode(
                    HintCommand.Tool("s-gifs", 1, AssistantRoute.WAKE, turnId = "turn-gifs",
                        phase = ToolPhase.END, label = "记一条笔记")))
            }
            waitForLayout()
            scenario.onActivity { activity ->
                assertSame("END restyles the same row", startedRow, activity.toolRowForProbe())
                assertEquals("END swaps in the official `ai_agent_tool_end.gif`", "ai_agent_tool_end",
                    activity.toolGifResourceForProbe())
                assertEquals("the end GIF plays once, as the official CustomTarget sets loopCount(1)", 0,
                    activity.toolGifRepeatCountForProbe())
                assertEquals(0x8000FF00.toInt(), activity.toolTextColorForProbe())
            }
        }
    }

    @Test
    fun theFinalShowRendersMarkdownBoldListAndCodeWhileThePartialStaysPlain() {
        launchHostPanel().use { scenario ->
            val partial = "**重点**\n\n- 第一项\n- 第二项\n\n`code`"
            scenario.onActivity { activity ->
                activity.acceptCommand(HintWireCodec.encode(HintCommand.Hello("s-md", 1)))
                syncFor(activity, syncId = "sync-md")
                activity.acceptCommand(HintWireCodec.encode(
                    HintCommand.State("s-md", 1, AssistantRoute.WAKE, turnId = "turn-md",
                        status = OfficialStatus.ANSWERING.wire, sequence = 1)))
                activity.acceptCommand(HintWireCodec.encode(
                    HintCommand.Answer("s-md", 1, AssistantRoute.WAKE, turnId = "turn-md",
                        hintId = "h-md", revision = 1, text = partial)))
            }
            waitForLayout()
            var streamedRow: TextView? = null
            scenario.onActivity { activity ->
                streamedRow = requireNotNull(activity.lastMessageRowForProbe())
                assertFalse("a streamed partial is plain text, not parsed Markdown",
                    hasSpan(streamedRow!!, "StrongEmphasisSpan") || hasSpan(streamedRow!!, "CodeSpan") ||
                        hasSpan(streamedRow!!, "BulletListItemSpan"))
                assertTrue("the partial still carries the literal markdown characters",
                    streamedRow!!.text.toString().contains("**重点**"))
                assertEquals("partial text is the official 16 px answer line",
                    GlassesPanelSpec.ASSISTANT_TEXT_SIZE_PX, streamedRow!!.textSize, 0.1f)

                activity.acceptCommand(HintWireCodec.encode(
                    HintCommand.Show("s-md", 1, sequence = 1, hintId = "h-md", revision = 1,
                        syncId = "sync-md", phoneSentNanos = System.nanoTime(), remainingTtlMillis = 30_000,
                        text = partial)))
            }
            waitForLayout()
            scenario.onActivity { activity ->
                val row = requireNotNull(activity.lastMessageRowForProbe())
                assertSame("the final SHOW replaces the same answer row", streamedRow, row)
                assertTrue("bold must become a Markwon strong span", hasSpan(row, "StrongEmphasisSpan"))
                assertTrue("a list must become the official bullet span", hasSpan(row, "BulletListItemSpan"))
                assertTrue("inline code must become a Markwon code span", hasSpan(row, "CodeSpan"))
                assertFalse("the final text no longer shows the source markers", row.text.toString().contains("**"))
                assertEquals(GlassesPanelSpec.GREEN_NORMAL, row.currentTextColor)
            }
        }
    }

    @Test
    fun thePinnedPanelShowsTheOfficial20PxPinAtFortyPercent() {
        launchHostPanel().use { scenario ->
            scenario.onActivity { activity ->
                activity.acceptCommand(HintWireCodec.encode(HintCommand.Hello("s-pin", 1)))
                activity.acceptCommand(HintWireCodec.encode(
                    HintCommand.State("s-pin", 1, AssistantRoute.WAKE, turnId = "turn-pin",
                        status = OfficialStatus.ANSWERING.wire, sequence = 1)))
                assertTrue("ENTER down is consumed",
                    activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER)))
                assertTrue("ENTER up pins",
                    activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER)))
            }
            waitForLayout()
            scenario.onActivity { activity ->
                assertTrue("the pin icon replaces the text", activity.pinIconVisibleForProbe())
                assertFalse(activity.pinTextVisibleForProbe())
                assertEquals("official icon_ai_hold is 20x20 px", 20, activity.pinIconWidthForProbe())
                assertEquals(20, activity.pinIconHeightForProbe())
                assertEquals("official fill/stroke alpha is 40%", 0.4f, activity.pinIconAlphaForProbe(), 0.01f)
            }
        }
    }

    @Test
    fun theRowsUseTheOfficialItemMarginsAndThePanelChromeKeepsItsSizes() {
        launchHostPanel().use { scenario ->
            scenario.onActivity { activity ->
                activity.acceptCommand(HintWireCodec.encode(HintCommand.Hello("s-space", 1)))
                activity.acceptCommand(HintWireCodec.encode(
                    HintCommand.Asr("s-space", 1, AssistantRoute.WAKE, turnId = "turn-space",
                        revision = 1, text = "帮我看下")))
                activity.acceptCommand(HintWireCodec.encode(
                    HintCommand.Answer("s-space", 1, AssistantRoute.WAKE, turnId = "turn-space",
                        hintId = "h-space", revision = 1, text = "好的")))
            }
            waitForLayout()
            scenario.onActivity { activity ->
                val rows = activity.conversationRowsForProbe()
                assertEquals("one user line and one answer line", 2, rows.size)
                val userParams = requireNotNull(rows[0].layoutParams) as LinearLayout.LayoutParams
                assertEquals("ASR item marginTop=10", GlassesPanelSpec.ASR_ROW_MARGIN_TOP_PX, userParams.topMargin)
                assertEquals("ASR item marginBottom=4", GlassesPanelSpec.ASR_ROW_MARGIN_BOTTOM_PX,
                    userParams.bottomMargin)
                assertEquals("ASR item marginStart=10", GlassesPanelSpec.ASR_ROW_MARGIN_SIDE_PX,
                    userParams.marginStart)
                val answerParams = requireNotNull(rows[1].layoutParams) as LinearLayout.LayoutParams
                assertEquals("TTS marginTop=5", GlassesPanelSpec.TTS_ROW_MARGIN_TOP_PX, answerParams.topMargin)
                assertEquals("TTS marginBottom=10", GlassesPanelSpec.TTS_ROW_MARGIN_BOTTOM_PX,
                    answerParams.bottomMargin)
                assertEquals("TTS paddingStart=10", GlassesPanelSpec.TTS_SIDE_PADDING_PX, rows[1].paddingStart)

                assertEquals("the top fade is 30 px", GlassesPanelSpec.MASK_HEIGHT_PX,
                    activity.topMaskHeightForProbe())
                assertEquals("the bottom fade is 30 px", GlassesPanelSpec.MASK_HEIGHT_PX,
                    activity.bottomMaskHeightForProbe())
                assertEquals("the compliance label is 12 px", GlassesPanelSpec.COMPLIANCE_TEXT_SIZE_PX,
                    activity.complianceTextSizeForProbe(), 0.1f)
            }
        }
    }
}
