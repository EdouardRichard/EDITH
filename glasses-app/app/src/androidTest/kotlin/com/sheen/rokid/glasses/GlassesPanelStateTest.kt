package com.sheen.rokid.glasses

import android.view.ViewGroup
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sheen.rokid.contracts.AssistantRoute
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
                assertEquals(GlassesPanelSpec.GREEN_NORMAL_70, userRow.currentTextColor)
            }
        }
    }
}
