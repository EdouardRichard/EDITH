package com.sheen.rokid.glasses

import android.content.Context
import android.view.KeyEvent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sheen.rokid.contracts.AssistantRoute
import com.sheen.rokid.glasses.app.GlassesActivity
import com.sheen.rokid.output.HintCommand
import com.sheen.rokid.output.HintWireCodec
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

    private fun accept(activity: GlassesActivity, command: HintCommand) {
        activity.acceptCommand(HintWireCodec.encode(command))
    }

    @Test
    fun theTickerPinsPagesAndANewSessionClearsTheWindow() {
        ActivityScenario.launch(GlassesActivity::class.java).use { scenario ->
            val longText = buildString {
                repeat(12) { index -> append("第${index + 1}点：这是用于滚动证据的合成长文本，长度足以超出眼镜列表窗口。") }
            }.take(390)

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
            settle(1_500)

            scenario.onActivity { activity ->
                assertTrue("the ticker must have advanced: first=$first now=${activity.scrollOffsetY()}",
                    activity.scrollOffsetY() > first)
                assertTrue("and it must still be inside the bound: ${activity.scrollOffsetY()} <= ${activity.maxScrollY()}",
                    activity.scrollOffsetY() <= activity.maxScrollY())
            }
            mark("long-mid")
            settle(1_500)

            key(KeyEvent.KEYCODE_ENTER)
            var pinnedOffset = 0
            scenario.onActivity { activity ->
                assertTrue("ENTER must pin the reading position", activity.isPinnedForProbe())
                assertFalse("a pinned list must not keep ticking", activity.tickerRunningForProbe())
                pinnedOffset = activity.scrollOffsetY()
            }
            mark("pinned")
            settle(2_000)
            scenario.onActivity { activity ->
                assertEquals("a pinned offset must not move", pinnedOffset, activity.scrollOffsetY())
            }

            // New content first, even while pinned: the next answer is brought into view but the ticker stays stopped.
            scenario.onActivity { activity ->
                accept(activity, HintCommand.Show("s-p4", 1, sequence = 2, hintId = "h-p4-new", revision = 1,
                    syncId = "sync-p4", phoneSentNanos = System.nanoTime(), remainingTtlMillis = 120_000,
                    text = "新到达的一条短回答"))
            }
            settle(900)
            scenario.onActivity { activity ->
                assertTrue(activity.isPinnedForProbe())
                assertFalse(activity.tickerRunningForProbe())
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
}
