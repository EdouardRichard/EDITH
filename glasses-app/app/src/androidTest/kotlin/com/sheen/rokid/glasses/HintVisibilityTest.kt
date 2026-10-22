package com.sheen.rokid.glasses
import com.sheen.rokid.glasses.display.HintPresentation

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sheen.rokid.contracts.AssistantRoute
import com.sheen.rokid.output.HintCommand
import com.sheen.rokid.output.HintWireCodec
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
        launchHostPanel().use { scenario ->
            scenario.onActivity { activity ->
                activity.acceptCommand(HintWireCodec.encode(HintCommand.Hello("s-1", 1)))
                activity.acceptCommand(HintWireCodec.encode(
                    HintCommand.Sync("sync-1", offsetLowerNanos = 0, offsetUpperNanos = 1_000_000,
                        validUntilGlassNanos = System.nanoTime() + 30_000_000_000L)))
                activity.acceptCommand(HintWireCodec.encode(HintCommand.Show(
                    sessionId = "s-1", generation = 1, sequence = 1, hintId = "h-1", revision = 1, syncId = "sync-1",
                    phoneSentNanos = System.nanoTime(), remainingTtlMillis = 5_000, text = "先讲结论。再讲背景")))

                assertEquals("the answer is shown verbatim; the official panel does not split sentences into lines",
                    "先讲结论。再讲背景", activity.hintText())

                activity.acceptCommand(HintWireCodec.encode(HintCommand.Clear("s-1", 1, sequence = 2)))

                assertEquals("a clear must empty the view", "", activity.hintText())
            }
        }
    }

    @Test
    fun aShowWithNoMappingIsNotRenderedAtAll() {
        launchHostPanel().use { scenario ->
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
        launchHostPanel().use { scenario ->
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
        launchHostPanel().use { scenario ->
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
     *
     * Phase C repair: this assertion used to read `HintPresentation.MAX_POINTS`, a constant that was removed in
     * P3.2-CLIP and left this file unable to compile. What it has to prove is the same thing it always did - the text
     * is taller than the window, so an overflow is observable - and the window is the ScrollView's visible height.
     */
    @Test
    fun aHintLongerThanTheLayoutShowsIsObservableAsScrollable() {
        launchHostPanel().use { scenario ->
            scenario.onActivity { activity ->
                activity.acceptCommand(HintWireCodec.encode(HintCommand.Hello("s-1", 1)))
                activity.acceptCommand(HintWireCodec.encode(
                    HintCommand.Sync("sync-1", offsetLowerNanos = 0, offsetUpperNanos = 1_000_000,
                        validUntilGlassNanos = System.nanoTime() + 30_000_000_000L)))
                // Near the wire's 400-code-point bound: at the official 16 px the list fits a handful of lines, so a
                // shorter text (the old 182-character one) no longer overflows the window at all.
                val long = buildString {
                    repeat(12) { index ->
                        append("第${index + 1}点：这是用于溢出证据的合成长文本，长度足以超出眼镜列表窗口。")
                    }
                }.take(390)
                activity.acceptCommand(HintWireCodec.encode(HintCommand.Show(
                    sessionId = "s-1", generation = 1, sequence = 1, hintId = "h-long", revision = 1,
                    syncId = "sync-1", phoneSentNanos = System.nanoTime(), remainingTtlMillis = 5_000, text = long)))
            }
            // `maxScrollY` describes the **last** layout, so the assertion has to wait for the pass that lays this
            // text out - reading it inside the same `onActivity` would read the previous (empty) layout.
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity { activity ->
                val maxScroll = activity.maxScrollY()
                assertTrue("a hint that overflows the layout must be observable as scrollable, saw max=$maxScroll",
                    maxScroll > 0)
            }
        }
    }

    /**
     * Phase C (spec §6.6): an utterance the phone reports lands in **its own** conversation.
     *
     * An utterance does **not** bring its route to the front: what the wearer is looking at follows what the assistant
     * is *telling* them (`SHOW`), not what they said. An earlier version of this test asserted the opposite - a
     * `selectedRoute() == PROACTIVE` after a `SAID` - which contradicted `GlassesActivity.onSaid` and could never
     * have passed. Nothing caught it, because `connectedDebugAndroidTest` is forbidden in this project (it would wipe
     * the phone's 1.03 GiB provision), so an instrumentation assertion is a contract, not a gate.
     */
    @Test
    fun anUtteranceLandsOnItsOwnRouteWithoutMovingTheView() {
        launchHostPanel().use { scenario ->
            scenario.onActivity { activity ->
                activity.acceptCommand(HintWireCodec.encode(HintCommand.Hello("s-1", 1)))
                activity.acceptCommand(HintWireCodec.encode(
                    HintCommand.Said("s-1", 1, sequence = 1, route = AssistantRoute.PROACTIVE,
                        entryId = "said-1", text = "我待会儿要开会")))
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity { activity ->
                assertEquals("the utterance must be in the proactive conversation",
                    "我待会儿要开会", activity.conversationText(AssistantRoute.PROACTIVE))
                assertEquals("and the wake conversation must not have it",
                    "", activity.conversationText(AssistantRoute.WAKE))
                assertEquals("an utterance is not something to look at, so the view stays where it was",
                    AssistantRoute.WAKE, activity.selectedRoute())
            }
        }
    }

    /** A `SHOW` **does** bring its route to the front: that is what makes a proactive hint visible at all. */
    @Test
    fun aShowBringsItsOwnRouteToTheFront() {
        launchHostPanel().use { scenario ->
            scenario.onActivity { activity ->
                activity.acceptCommand(HintWireCodec.encode(HintCommand.Hello("s-1", 1)))
                activity.acceptCommand(HintWireCodec.encode(
                    HintCommand.Sync("sync-1", offsetLowerNanos = 0, offsetUpperNanos = 1_000_000,
                        validUntilGlassNanos = System.nanoTime() + 30_000_000_000L)))
                activity.acceptCommand(HintWireCodec.encode(HintCommand.Show(
                    sessionId = "s-1", generation = 1, sequence = 1, hintId = "h-p", revision = 1, syncId = "sync-1",
                    phoneSentNanos = System.nanoTime(), remainingTtlMillis = 5_000, text = "主动提醒一句",
                    route = AssistantRoute.PROACTIVE)))
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity { activity ->
                assertEquals(AssistantRoute.PROACTIVE, activity.selectedRoute())
                assertEquals("主动提醒一句", activity.conversationText(AssistantRoute.PROACTIVE))
            }
        }
    }

    /**
     * The Phase C change to what a `CLEAR` means: the live hint is retired, the conversation is **not** erased. A
     * context view the phone can wipe is not a context view.
     */
    @Test
    fun aClearRetiresTheLiveHintButKeepsTheConversation() {
        launchHostPanel().use { scenario ->
            scenario.onActivity { activity ->
                activity.acceptCommand(HintWireCodec.encode(HintCommand.Hello("s-1", 1)))
                activity.acceptCommand(HintWireCodec.encode(
                    HintCommand.Sync("sync-1", offsetLowerNanos = 0, offsetUpperNanos = 1_000_000,
                        validUntilGlassNanos = System.nanoTime() + 30_000_000_000L)))
                activity.acceptCommand(HintWireCodec.encode(HintCommand.Show(
                    sessionId = "s-1", generation = 1, sequence = 1, hintId = "h-1", revision = 1, syncId = "sync-1",
                    phoneSentNanos = System.nanoTime(), remainingTtlMillis = 5_000, text = "答案在这里")))

                assertEquals("答案在这里", activity.hintText())

                activity.acceptCommand(HintWireCodec.encode(HintCommand.Clear("s-1", 1, sequence = 2)))
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity { activity ->
                assertEquals("a clear must retire the live hint", "", activity.hintText())
                assertEquals("but the conversation the wearer reads back from must survive it",
                    "答案在这里", activity.conversationText(AssistantRoute.WAKE))
            }
        }
    }

    /** An utterance the gates refuse must not enter the conversation: nobody read it, so it is not part of it. */
    @Test
    fun anUtteranceForAnotherSessionNeverEntersTheConversation() {
        launchHostPanel().use { scenario ->
            scenario.onActivity { activity ->
                activity.acceptCommand(HintWireCodec.encode(HintCommand.Hello("s-1", 1)))
                activity.acceptCommand(HintWireCodec.encode(
                    HintCommand.Said("s-other", 1, sequence = 1, route = AssistantRoute.WAKE,
                        entryId = "said-x", text = "上一个会话说的话")))
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity { activity ->
                assertEquals("another session's words must never appear here",
                    "", activity.conversationText(AssistantRoute.WAKE))
            }
        }
    }
}
