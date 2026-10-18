package com.sheen.rokid.glasses

import android.content.Context
import android.view.KeyEvent
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sheen.rokid.contracts.AssistantRoute
import com.sheen.rokid.glasses.app.GlassesActivity
import com.sheen.rokid.output.HintCommand
import com.sheen.rokid.output.HintWireCodec
import com.sheen.rokid.output.OfficialConversation
import com.sheen.rokid.output.OfficialStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * 0.23 P4 device check (U-7/U-8): one route, the 1 px/frame ticker, the pin and page keys, and the window reset.
 *
 * Keys are injected with [android.app.Instrumentation.sendKeyDownUpSync], which is the same delivery path the temple
 * pad's `KeyEvent`s take into the Activity; physical hand feel is a separate evidence class and stays `NotMeasured`.
 *
 * Screenshots are taken by the host from the marker file (`tools/0.23-glasses-capture.ps1`) - this class is an
 * instrument, the assertions are the gate.
 */
@RunWith(AndroidJUnit4::class)
class GlassesInteractionTest {

    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun mark(state: String) {
        File(context.getExternalFilesDir(null), MARKER).writeText(state)
    }

    private fun settle(millis: Long = 700) {
        // Not `waitForIdleSync`: the orb's breathing animation means this screen never reaches "idle".
        Thread.sleep(millis)
    }

    private fun key(code: Int) {
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(code)
        settle(500)
    }

    /**
     * Real key injection goes to whatever window has focus. A preceding test finishing can leave the launcher focused for
     * a moment, and then the injected ENTER/BACK never reaches this panel. Wait for the host window before the first key.
     */
    private fun awaitWindowFocus(scenario: ActivityScenario<GlassesActivity>, timeoutMillis: Long = 5_000) {
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000L
        while (System.nanoTime() < deadline) {
            var focused = false
            scenario.onActivity { activity -> focused = activity.hasWindowFocus() }
            if (focused) return
            Thread.sleep(50)
        }
        error("the panel window did not take focus within ${timeoutMillis}ms")
    }

    private fun accept(activity: GlassesActivity, command: HintCommand) {
        activity.acceptCommand(HintWireCodec.encode(command))
    }

    @Test
    fun theTickerPinsPagesAndANewSessionClearsTheWindow() {
        launchHostPanel().use { scenario ->
            awaitWindowFocus(scenario)
            val longText = buildString {
                repeat(12) { index -> append("第${index + 1}点：这是用于滚动证据的合成长文本，长度足以超出眼镜列表窗口。") }
            }.take(384).chunked(24).joinToString("\n")

            scenario.onActivity { activity ->
                accept(activity, HintCommand.Hello("s-p4", 1))
                accept(activity, HintCommand.Sync("sync-p4", 0, 1_000_000,
                    System.nanoTime() + 120_000_000_000L))
                accept(activity, HintCommand.Show("s-p4", 1, sequence = 1, hintId = "h-p4-long", revision = 1,
                    syncId = "sync-p4", phoneSentNanos = System.nanoTime(), remainingTtlMillis = 120_000,
                    text = longText))
            }
            settle(350)
            var first = 0
            var max = 0
            scenario.onActivity { activity ->
                first = activity.scrollOffsetY()
                max = activity.maxScrollY()
            }
            assertTrue("the long synthetic answer must overflow the window, max=$max", max > 0)
            mark("long-start")
            settle(500)

            scenario.onActivity { activity ->
                assertTrue("the ticker must have advanced: first=$first now=${activity.scrollOffsetY()}",
                    activity.scrollOffsetY() > first)
                assertTrue("and it must still be inside the bound: ${activity.scrollOffsetY()} <= ${activity.maxScrollY()}",
                    activity.scrollOffsetY() <= activity.maxScrollY())
            }
            mark("long-mid")
            settle(500)

            // Task 19 timing correction: `key()` settles 500 ms, and with the official item margins the remaining
            // (max - offset) travel at the pin is ~28 px, so the ticker legitimately reached the list bottom during
            // that 500 ms and stopped - the assertion then measured "end of list", not "pin stopped the ticker". The
            // pin is checked a few frames after the key instead, which is what this assertion has always meant.
            InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_ENTER)
            settle(50)
            var pinnedOffset = 0
            scenario.onActivity { activity ->
                assertTrue("ENTER must pin the reading position", activity.isPinnedForProbe())
                assertTrue("official pin must not stop the ticker", activity.tickerRunningForProbe())
                assertFalse("pinned: the 单击钉住 hint is hidden", activity.pinTextVisibleForProbe())
                assertTrue("pinned: the pin icon is shown", activity.pinIconVisibleForProbe())
                pinnedOffset = activity.scrollOffsetY()
            }
            mark("pinned")
            settle(2_000)
            scenario.onActivity { activity ->
                assertTrue("the ticker must keep advancing after the pin: was $pinnedOffset " +
                    "now=${activity.scrollOffsetY()}", activity.scrollOffsetY() > pinnedOffset)
            }

            // New content first, even while pinned: the next answer is brought into view and the ticker continues
            // (the pin is not a ticker condition in the official panel).
            scenario.onActivity { activity ->
                accept(activity, HintCommand.Show("s-p4", 1, sequence = 2, hintId = "h-p4-new", revision = 1,
                    syncId = "sync-p4", phoneSentNanos = System.nanoTime(), remainingTtlMillis = 120_000,
                    text = "新到达的一条短回答"))
            }
            settle(900)
            scenario.onActivity { activity ->
                assertTrue(activity.isPinnedForProbe())
                val expected = activity.lastMessageTopForProbe().coerceAtMost(activity.maxScrollY())
                assertEquals("the new entry's top must be in view while pinned", expected,
                    activity.scrollOffsetY())
            }
            mark("new-entry-pinned")
            settle(2_000)

            var beforePage = 0
            scenario.onActivity { activity -> beforePage = activity.scrollOffsetY() }
            assertTrue("the paging case needs room above the current offset, saw $beforePage", beforePage > 0)
            key(KeyEvent.KEYCODE_DPAD_UP)
            scenario.onActivity { activity ->
                assertTrue("DPAD_UP must page towards the top: was $beforePage now=${activity.scrollOffsetY()}",
                    activity.scrollOffsetY() < beforePage)
                assertTrue("a manual page must not unpin the wearer", activity.isPinnedForProbe())
            }
            mark("paged")
            settle(2_000)

            // A second ENTER is not an unpin: the official hold has no cancel path. The manual page keeps the ticker
            // stopped, and the pin must not restart it either.
            key(KeyEvent.KEYCODE_ENTER)
            settle(500)
            scenario.onActivity { activity ->
                assertTrue("a second ENTER must not unpin", activity.isPinnedForProbe())
                assertTrue("pinned: the pin icon stays", activity.pinIconVisibleForProbe())
                assertFalse("the manual page (not the pin) is what stops the ticker",
                    activity.tickerRunningForProbe())
            }

            // A new session is a new window: both conversations empty, wake route, unpinned, nothing to scroll.
            scenario.onActivity { activity -> accept(activity, HintCommand.Hello("s-p4b", 2)) }
            settle(900)
            scenario.onActivity { activity ->
                assertEquals("a new session must start with an empty wake conversation",
                    "", activity.conversationText(AssistantRoute.WAKE))
                assertEquals("and an empty proactive conversation",
                    "", activity.conversationText(AssistantRoute.PROACTIVE))
                assertEquals("a new window opens on wake", AssistantRoute.WAKE, activity.selectedRoute())
                assertFalse("and is unpinned", activity.isPinnedForProbe())
                assertEquals("with nothing left to scroll", 0, activity.maxScrollY())
            }
            mark("new-session")
            settle(2_000)

            mark("done")
        }
    }

    private companion object {
        const val MARKER = "visual-state"
    }

    /**
     * 0.24 S3 Task 13 (spec §3.3/§5.3): the official three-state BACK.
     *
     * Pinned + status 3 sends STOP and keeps the window; after the phone's IDLE the same BACK (pinned, not answering)
     * exits. The test observes the STOP the production controller actually sent, not a log line.
     */
    @Test
    fun aBackWhilePinnedAndAnsweringStopsThenThePinnedIdleBackExits() {
        launchHostPanel().use { scenario ->
            awaitWindowFocus(scenario)
            scenario.onActivity { activity ->
                activity.setStopSenderForProbe { true }
                accept(activity, HintCommand.Hello("s-p5-back", 1))
                accept(activity, HintCommand.State("s-p5-back", 1, AssistantRoute.WAKE,
                    turnId = "turn-p5-back", status = OfficialStatus.LISTENING.wire, sequence = 1))
            }
            settle(500)
            key(KeyEvent.KEYCODE_ENTER)
            scenario.onActivity { activity ->
                assertTrue("the matrix case needs the pin", activity.isPinnedForProbe())
                accept(activity, HintCommand.State("s-p5-back", 1, AssistantRoute.WAKE,
                    turnId = "turn-p5-back", status = OfficialStatus.ANSWERING.wire, sequence = 2))
                assertEquals("pinned + status 3 is the one interrupt copy",
                    OfficialConversation.TIPS_STOP, activity.tipsTextForProbe())
            }
            settle(500)

            key(KeyEvent.KEYCODE_BACK)
            settle(700)
            scenario.onActivity { activity ->
                assertEquals("pinned ANSWERING BACK is a STOP, not an exit", "stop",
                    activity.lastBackActionForProbe())
                assertEquals(true, activity.lastBackStopSentForProbe())
                assertFalse("and the window stays open for the phone's IDLE", activity.isFinishing)
            }

            scenario.onActivity { activity ->
                accept(activity, HintCommand.Idle("s-p5-back", 1, AssistantRoute.WAKE, sequence = 3))
                assertEquals("after the phone's idle the pinned panel says exit again",
                    OfficialConversation.TIPS_EXIT, activity.tipsTextForProbe())
            }
            settle(500)
            key(KeyEvent.KEYCODE_BACK)
            settle(1_000)
            assertEquals("a pinned but not-answering BACK must exit the glasses window",
                Lifecycle.State.DESTROYED, scenario.state)
        }
    }

    @Test
    fun aManualPageStopsTheRunningTickerAndTheNextEntryRestartsIt() {
        val longText = buildString {
            repeat(12) { index -> append("第${index + 1}点：这是用于翻页证据的合成长文本，长度足以超出眼镜列表窗口。") }
        }.take(390)
        launchHostPanel().use { scenario ->
            awaitWindowFocus(scenario)
            scenario.onActivity { activity ->
                accept(activity, HintCommand.Hello("s-p4-page", 1))
                accept(activity, HintCommand.Sync("sync-page", 0, 1_000_000,
                    System.nanoTime() + 60_000_000_000L))
                accept(activity, HintCommand.Show("s-p4-page", 1, sequence = 1, hintId = "h-page-1", revision = 1,
                    syncId = "sync-page", phoneSentNanos = System.nanoTime(), remainingTtlMillis = 60_000,
                    text = longText))
            }
            settle(700)
            scenario.onActivity { activity ->
                assertTrue("the ticker must be running on a long hint", activity.tickerRunningForProbe())
            }

            key(KeyEvent.KEYCODE_DPAD_DOWN)
            settle(400)
            scenario.onActivity { activity ->
                assertFalse("a manual page must stop the ticker", activity.tickerRunningForProbe())
            }

            scenario.onActivity { activity ->
                accept(activity, HintCommand.Show("s-p4-page", 1, sequence = 2, hintId = "h-page-2", revision = 1,
                    syncId = "sync-page", phoneSentNanos = System.nanoTime(), remainingTtlMillis = 60_000,
                    text = longText))
            }
            settle(700)
            scenario.onActivity { activity ->
                assertTrue("the next entry must resume the ticker", activity.tickerRunningForProbe())
            }
        }
    }

    /**
     * 0.24 S2 Task 8 fix: a valid SHOW that replaces an equal entry (same hint id and text, higher revision) is still
     * new content. The pre-extraction Activity revealed on every accepted SHOW, including while pinned and paged;
     * `PanelModel.contentRevision` is the explicit controller-owned signal that keeps that behavior.
     */
    @Test
    fun anIdenticalShowWithAHigherRevisionStillRevealsTheNewestLine() {
        val longText = buildString {
            repeat(12) { index -> append("第${index + 1}点：这是用于重复内容证据的合成长文本，长度足以超出眼镜列表窗口。") }
        }.take(390)
        launchHostPanel().use { scenario ->
            awaitWindowFocus(scenario)
            scenario.onActivity { activity ->
                accept(activity, HintCommand.Hello("s-s2-repeat", 1))
                accept(activity, HintCommand.Sync("sync-s2-repeat", 0, 1_000_000,
                    System.nanoTime() + 120_000_000_000L))
                accept(activity, HintCommand.Show("s-s2-repeat", 1, sequence = 1, hintId = "h-s2-short", revision = 1,
                    syncId = "sync-s2-repeat", phoneSentNanos = System.nanoTime(), remainingTtlMillis = 120_000,
                    text = "第一句"))
                accept(activity, HintCommand.Show("s-s2-repeat", 1, sequence = 2, hintId = "h-s2-repeat", revision = 1,
                    syncId = "sync-s2-repeat", phoneSentNanos = System.nanoTime(), remainingTtlMillis = 120_000,
                    text = longText))
            }
            settle(900)
            key(KeyEvent.KEYCODE_ENTER)
            var revealTarget = 0
            scenario.onActivity { activity ->
                assertTrue("the long answer must be scrollable before paging", activity.maxScrollY() > 0)
                assertTrue("ENTER must pin the reading position", activity.isPinnedForProbe())
                revealTarget = activity.lastMessageTopForProbe().coerceAtMost(activity.maxScrollY())
                assertTrue("the newest line must be below the list top, saw $revealTarget", revealTarget > 0)
            }
            // Page to the top so a restored position is demonstrably not the reveal target.
            repeat(6) { key(KeyEvent.KEYCODE_DPAD_UP) }
            var paged = 0
            scenario.onActivity { activity ->
                paged = activity.scrollOffsetY()
                assertTrue("the page keys must move away from the newest line, saw $paged", paged < revealTarget)
            }
            // Same hint id and text; only the revision is newer, so ConversationLog replaces an equal entry.
            scenario.onActivity { activity ->
                accept(activity, HintCommand.Show("s-s2-repeat", 1, sequence = 3, hintId = "h-s2-repeat", revision = 2,
                    syncId = "sync-s2-repeat", phoneSentNanos = System.nanoTime(), remainingTtlMillis = 120_000,
                    text = longText))
            }
            settle(900)
            scenario.onActivity { activity ->
                val expected = activity.lastMessageTopForProbe().coerceAtMost(activity.maxScrollY())
                assertTrue("the pin must survive the reveal", activity.isPinnedForProbe())
                assertTrue("an identical accepted SHOW must reveal the newest line again, not restore $paged; " +
                    "expected at least $expected, saw ${activity.scrollOffsetY()}",
                    activity.scrollOffsetY() >= expected)
            }
        }
    }

    @Test
    fun aBackWhilePinnedAnsweringWithADeadLinkExitsInsteadOfTrappingTheWearer() {
        // A STOP that could not be sent is not an interrupt: the phone cannot clear the level, so keeping the window
        // open would trap the wearer until the local 60 s bound.
        launchHostPanel().use { scenario ->
            awaitWindowFocus(scenario)
            scenario.onActivity { activity ->
                activity.setStopSenderForProbe { false }
                accept(activity, HintCommand.Hello("s-p5-dead", 1))
                accept(activity, HintCommand.State("s-p5-dead", 1, AssistantRoute.WAKE,
                    turnId = "turn-p5-dead", status = OfficialStatus.LISTENING.wire, sequence = 1))
            }
            settle(300)
            key(KeyEvent.KEYCODE_ENTER)
            scenario.onActivity { activity ->
                assertTrue("the dead-link case is the pinned answering one", activity.isPinnedForProbe())
                accept(activity, HintCommand.State("s-p5-dead", 1, AssistantRoute.WAKE,
                    turnId = "turn-p5-dead", status = OfficialStatus.ANSWERING.wire, sequence = 2))
            }
            settle(500)

            key(KeyEvent.KEYCODE_BACK)
            settle(1_000)
            assertEquals("the dead-link BACK must exit the window", Lifecycle.State.DESTROYED, scenario.state)
        }
    }

    /**
     * 0.24 S3 Task 13 (official `AiAssistantDialog.onKeyUp` + `ErAIRecyclerView.dispatchKeyEvent`): ENTER/BACK act on
     * the up and consume the down; the vertical keys act on the down; left/right are returned to the window.
     *
     * This calls the Activity's real dispatch path, not the controller directly. DPAD down/up consumption is pinned on
     * the production overlay in [OverlayServiceTest] because the Activity test host routes those through its unhandled
     * key listener.
     */
    @Test
    fun theActivityKeyPathActsOnEnterUpAndLeavesTheSideDirectionsAlone() {
        launchHostPanel().use { scenario ->
            awaitWindowFocus(scenario)
            scenario.onActivity { activity ->
                accept(activity, HintCommand.Hello("s-p5-keys", 1))
                assertTrue("ENTER down is consumed", activity.dispatchKeyEvent(
                    KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER)))
                assertFalse("but the pin happens on the up", activity.isPinnedForProbe())
                assertTrue("ENTER up is consumed", activity.dispatchKeyEvent(
                    KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER)))
                assertTrue("and pins once", activity.isPinnedForProbe())
                assertFalse(activity.pinTextVisibleForProbe())
                assertTrue(activity.pinIconVisibleForProbe())

                assertTrue("BACK down is consumed", activity.dispatchKeyEvent(
                    KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK)))
                assertFalse("BACK down does not exit", activity.isFinishing)

                assertFalse("left is not the panel's", activity.dispatchKeyEvent(
                    KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_LEFT)))
                assertFalse("right is not the panel's", activity.dispatchKeyEvent(
                    KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT)))
            }
        }
    }
}
