package edith.glasses

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import edith.output.HintCommand
import edith.output.HintWireCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * T18 device-side view check (plan Task 18 Step 2): what is on the glasses after a `SHOW`, and after a `CLEAR`.
 *
 * This asserts the **committed view**, not a log line. It says nothing about whether a wearer could read it - human
 * visibility is a separate evidence class and is deliberately not claimed here.
 *
 * The whole inbound path is real except the bridge itself: the same `acceptCommand` the CXR callback drives is fed the
 * same bytes the phone encodes, so a decode or ordering regression fails here rather than on a device.
 */
@RunWith(AndroidJUnit4::class)
class HintVisibilityTest {

    @Test
    fun aShowBecomesVisibleAndAClearEmptiesTheView() {
        ActivityScenario.launch(GlassesActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.acceptCommand(HintWireCodec.encode(HintCommand.Hello("s-1", 1)))
                activity.acceptCommand(HintWireCodec.encode(
                    HintCommand.Sync("sync-1", offsetLowerNanos = 0, offsetUpperNanos = 1_000_000,
                        validUntilGlassNanos = System.nanoTime() + 30_000_000_000L)))
                activity.acceptCommand(HintWireCodec.encode(HintCommand.Show(
                    sessionId = "s-1", generation = 1, sequence = 1, hintId = "h-1", revision = 1, syncId = "sync-1",
                    phoneSentNanos = System.nanoTime(), remainingTtlMillis = 5_000, text = "先讲结论。再讲背景")))

                assertEquals("先讲结论\n再讲背景", activity.hintText())

                activity.acceptCommand(HintWireCodec.encode(HintCommand.Clear("s-1", 1, sequence = 2)))

                assertEquals("a clear must empty the view", "", activity.hintText())
            }
        }
    }

    @Test
    fun aShowWithNoMappingIsNotRenderedAtAll() {
        ActivityScenario.launch(GlassesActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.acceptCommand(HintWireCodec.encode(HintCommand.Hello("s-1", 1)))
                // No SYNC: the glasses have no mapping for this syncId, so the deadline cannot be trusted.
                activity.acceptCommand(HintWireCodec.encode(HintCommand.Show(
                    sessionId = "s-1", generation = 1, sequence = 1, hintId = "h-1", revision = 1,
                    syncId = "never-synced", phoneSentNanos = System.nanoTime(), remainingTtlMillis = 5_000,
                    text = "不该出现")))

                assertEquals("an unmapped hint must never reach the screen", "", activity.hintText())
            }
        }
    }

    @Test
    fun anUndecodableMessageIsIgnoredRatherThanThrown() {
        ActivityScenario.launch(GlassesActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                // The bridge callback thread must survive garbage: a throw here would take the session down.
                assertEquals(false, activity.acceptCommand("not json at all"))
                assertEquals(false, activity.acceptCommand(""))
                assertEquals("", activity.hintText())
            }
        }
    }

    @Test
    fun aPingIsAcceptedAndLeavesTheViewAlone() {
        ActivityScenario.launch(GlassesActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                // The answer itself goes out on the real bridge (no session is live here, so the send is refused and
                // logged); what this pins is that a PING is understood and never touches the screen.
                assertEquals(true, activity.acceptCommand(HintWireCodec.encode(HintCommand.Ping("sync-1", 1_000))))

                assertEquals("", activity.hintText())
            }
        }
    }

    /**
     * P3.2 Stage 0: the fixed region wraps, and a plain `TextView` with `maxLines` clips the overflow **silently**.
     * This pins that the overflow is at least **observable** (the reading the capacity probe depends on), not that any
     * particular length is legible - wearer readability stays a separate evidence class.
     */
    @Test
    fun aHintLongerThanTheLayoutShowsIsObservableAsClipped() {
        ActivityScenario.launch(GlassesActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.acceptCommand(HintWireCodec.encode(HintCommand.Hello("s-1", 1)))
                activity.acceptCommand(HintWireCodec.encode(
                    HintCommand.Sync("sync-1", offsetLowerNanos = 0, offsetUpperNanos = 1_000_000,
                        validUntilGlassNanos = System.nanoTime() + 30_000_000_000L)))
                // Three points, each long enough to wrap several times: the shaping limit is 3 points, the layout
                // limit is 6 lines, so this must overflow.
                val long = "第一点需要足够长才能折成好几行用来撑满这块小小的显示区域。".repeat(2) +
                    "第二点同样需要足够长才能折成好几行用来撑满这块小小的显示区域。".repeat(2) +
                    "第三点也一样需要足够长才能折成好几行用来撑满这块小小的显示区域".repeat(2)
                activity.acceptCommand(HintWireCodec.encode(HintCommand.Show(
                    sessionId = "s-1", generation = 1, sequence = 1, hintId = "h-long", revision = 1,
                    syncId = "sync-1", phoneSentNanos = System.nanoTime(), remainingTtlMillis = 5_000, text = long)))
            }
            // `lineCount` describes the **last** layout, so the assertion has to wait for the pass that lays this
            // text out - reading it inside the same `onActivity` would read the previous (empty) layout.
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity { activity ->
                val lines = activity.renderedLineCount()
                assertTrue("a hint that overflows the layout must be observable as clipped, saw $lines lines",
                    lines > HintPresentation.MAX_POINTS * 2)
            }
        }
    }
}
