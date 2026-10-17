package com.sheen.rokid.glasses

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sheen.rokid.contracts.AssistantRoute
import com.sheen.rokid.glasses.app.GlassesActivity
import com.sheen.rokid.glasses.display.GlassesPanelSpec
import com.sheen.rokid.glasses.display.OrbState
import com.sheen.rokid.glasses.display.TailTextView
import com.sheen.rokid.output.HintCommand
import com.sheen.rokid.output.HintWireCodec
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

    @Test
    fun aBusyWakeShowsListeningThenThinkingThenTheAnswer() {
        ActivityScenario.launch(GlassesActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertEquals("nothing is happening yet", OrbState.IDLE, activity.orbStateForProbe())
                assertFalse(activity.listeningVisibleForProbe())
                assertFalse(activity.thinkingVisibleForProbe())
            }

            scenario.onActivity { activity ->
                activity.acceptCommand(HintWireCodec.encode(HintCommand.Hello("s-p3", 1)))
                activity.acceptCommand(HintWireCodec.encode(
                    HintCommand.Busy("s-p3", 1, AssistantRoute.WAKE, busy = true)))
            }
            waitForLayout()
            scenario.onActivity { activity ->
                assertTrue("a busy wake before the wearer speaks is the listening state",
                    activity.listeningVisibleForProbe())
                assertFalse(activity.thinkingVisibleForProbe())
                assertEquals(OrbState.LISTENING, activity.orbStateForProbe())
                assertEquals(GlassesPanelSpec.LISTENING_TEXT, activity.listeningTextForProbe())
            }

            scenario.onActivity { activity ->
                activity.acceptCommand(HintWireCodec.encode(
                    HintCommand.Said("s-p3", 1, sequence = 1, route = AssistantRoute.WAKE,
                        entryId = "said-p3", text = "现在几点了")))
            }
            waitForLayout()
            scenario.onActivity { activity ->
                assertFalse("once the wearer's line is in, listening is over",
                    activity.listeningVisibleForProbe())
                assertTrue("and the thinking row takes over", activity.thinkingVisibleForProbe())
                assertEquals(OrbState.THINKING, activity.orbStateForProbe())
                assertEquals(GlassesPanelSpec.THINKING_TEXT, activity.thinkingTextForProbe())
            }

            scenario.onActivity { activity ->
                activity.acceptCommand(syncJson())
                activity.acceptCommand(HintWireCodec.encode(
                    HintCommand.Show("s-p3", 1, sequence = 3, hintId = "h-p3", revision = 1,
                        syncId = "sync-p3", phoneSentNanos = System.nanoTime(), remainingTtlMillis = 5_000,
                        text = "现在是凌晨十二点")))
            }
            waitForLayout()
            scenario.onActivity { activity ->
                assertEquals(OrbState.ANSWERING, activity.orbStateForProbe())
                assertFalse(activity.listeningVisibleForProbe())
                assertFalse(activity.thinkingVisibleForProbe())
                assertEquals("现在是凌晨十二点", activity.hintText())
            }
        }
    }

    @Test
    fun aProactiveTurnThinksOnlyOnItsOwnRoute() {
        ActivityScenario.launch(GlassesActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.acceptCommand(HintWireCodec.encode(HintCommand.Hello("s-p3", 1)))
                activity.acceptCommand(HintWireCodec.encode(
                    HintCommand.Busy("s-p3", 1, AssistantRoute.PROACTIVE, busy = true)))
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
            }
            waitForLayout()
            scenario.onActivity { activity ->
                assertEquals(AssistantRoute.PROACTIVE, activity.selectedRoute())
                assertTrue("with the answer retired and the turn still busy, the proactive route thinks",
                    activity.thinkingVisibleForProbe())
                assertEquals(OrbState.THINKING, activity.orbStateForProbe())
            }
        }
    }

    @Test
    fun thePanelCarriesTheOfficialFixedCopy() {
        ActivityScenario.launch(GlassesActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertEquals(GlassesPanelSpec.COMPLIANCE_TEXT, activity.complianceTextForProbe())
                assertEquals(GlassesPanelSpec.TIPS_EXIT, activity.tipsTextForProbe())
            }
        }
    }

    @Test
    fun theWearersLineShowsTheNewestFourLinesAndAlignsLeft() {
        ActivityScenario.launch(GlassesActivity::class.java).use { scenario ->
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
