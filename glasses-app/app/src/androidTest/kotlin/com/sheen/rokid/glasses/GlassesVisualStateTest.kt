package com.sheen.rokid.glasses

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sheen.rokid.contracts.AssistantRoute
import com.sheen.rokid.output.HintCommand
import com.sheen.rokid.output.HintWireCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * 0.23 P3 evidence instrument: steps the panel through the four official states and leaves a marker the host polls.
 *
 * The screenshots are taken from the host (`adb exec-out screencap -p`), so the test's only job is to make each state
 * stay on screen long enough and to say which one it is. The marker is a state name, never any content. This class is
 * an instrument, not a gate: the assertions live in [GlassesPanelStateTest].
 */
@RunWith(AndroidJUnit4::class)
class GlassesVisualStateTest {

    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun mark(state: String) {
        val file = File(context.getExternalFilesDir(null), MARKER)
        file.writeText(state)
    }

    private fun hold(state: String, millis: Long) {
        mark(state)
        Thread.sleep(millis)
    }

    /**
     * Lets the UI thread run the posted commands and one layout pass.
     *
     * Deliberately **not** `waitForIdleSync`: the orb's breathing animator runs while listening/answering, so "idle"
     * is not a state this screen reaches, and the instrumentation wait would block until the busy level expired.
     */
    private fun settle() {
        Thread.sleep(700)
    }

    @Test
    fun captureTheFourOfficialStates() {
        launchHostPanel().use { scenario ->
            // The default activity is the idle empty state: the official panel with the orb and the fixed copy.
            settle()
            hold("empty", 8_000)

            scenario.onActivity { activity ->
                activity.acceptCommand(HintWireCodec.encode(HintCommand.Hello("s-visual", 1)))
                activity.acceptCommand(HintWireCodec.encode(
                    HintCommand.Busy("s-visual", 1, AssistantRoute.WAKE, busy = true)))
            }
            settle()
            hold("listening", 8_000)

            scenario.onActivity { activity ->
                // Re-state the level with the utterance: the visual run can spend more than a minute between steps
                // (the panel is held awake and the instrumentation waits for layout), and the level is bounded at 60 s
                // by design. Re-stating is what the phone does on every turn anyway.
                activity.acceptCommand(HintWireCodec.encode(
                    HintCommand.Busy("s-visual", 1, AssistantRoute.WAKE, busy = true)))
                activity.acceptCommand(HintWireCodec.encode(
                    HintCommand.Said("s-visual", 1, sequence = 1, route = AssistantRoute.WAKE,
                        entryId = "said-visual-1", text = "现在几点了")))
            }
            settle()
            // P6 review: the screenshot is only evidence if the thinking row is actually on screen when the marker is
            // written. The first P6 capture showed an idle panel (tips "双击退出"), so assert the state here; if this
            // fails the instrument is wrong, not the product, and the capture must be retried.
            scenario.onActivity { activity ->
                assertTrue("the thinking row must be visible for the thinking screenshot",
                    activity.thinkingVisibleForProbe())
                assertEquals("正在思考", activity.thinkingTextForProbe())
            }
            hold("thinking", 8_000)

            scenario.onActivity { activity ->
                activity.acceptCommand(HintWireCodec.encode(
                    HintCommand.Sync("sync-visual", 0, 1_000_000, System.nanoTime() + 60_000_000_000L)))
                activity.acceptCommand(HintWireCodec.encode(
                    HintCommand.Busy("s-visual", 1, AssistantRoute.WAKE, busy = false)))
                activity.acceptCommand(HintWireCodec.encode(
                    HintCommand.Show("s-visual", 1, sequence = 3, hintId = "h-visual-1", revision = 1,
                        syncId = "sync-visual", phoneSentNanos = System.nanoTime(), remainingTtlMillis = 30_000,
                        text = "现在是凌晨十二点三十分。")))
                activity.acceptCommand(HintWireCodec.encode(
                    HintCommand.Said("s-visual", 1, sequence = 4, route = AssistantRoute.WAKE,
                        entryId = "said-visual-2", text = "你有什么能力")))
                activity.acceptCommand(HintWireCodec.encode(
                    HintCommand.Show("s-visual", 1, sequence = 5, hintId = "h-visual-2", revision = 1,
                        syncId = "sync-visual", phoneSentNanos = System.nanoTime(), remainingTtlMillis = 30_000,
                        text = "我能陪你聊天解闷，也能帮你查天气、翻译外语或者算数学题。")))
            }
            settle()
            hold("multiturn", 10_000)

            mark("done")
        }
    }

    /**
     * 0.24 S4 Task 19: the official GIFs, the tool row and the Markdown answer, held long enough for a host capture.
     *
     * This is an instrument, not a gate; [GlassesPanelStateTest] asserts the same properties. It exists because the
     * task's screenshots are captured from the host (`adb exec-out screencap`), which can only see what the panel is
     * actually showing when the marker changes.
     */
    @Test
    fun captureOfficialGifsToolRowAndMarkdown() {
        launchHostPanel().use { scenario ->
            settle()
            hold("empty", 1_000)

            scenario.onActivity { activity ->
                activity.acceptCommand(HintWireCodec.encode(HintCommand.Hello("s-visual-gif", 1)))
                activity.acceptCommand(HintWireCodec.encode(
                    HintCommand.State("s-visual-gif", 1, AssistantRoute.WAKE, turnId = "turn-visual-gif",
                        status = 4, sequence = 1)))
            }
            settle()
            hold("gif-thinking", 6_000)

            scenario.onActivity { activity ->
                activity.acceptCommand(HintWireCodec.encode(
                    HintCommand.State("s-visual-gif", 1, AssistantRoute.WAKE, turnId = "turn-visual-gif",
                        status = 7, sequence = 2)))
                activity.acceptCommand(HintWireCodec.encode(
                    HintCommand.Tool("s-visual-gif", 1, AssistantRoute.WAKE, turnId = "turn-visual-gif",
                        phase = com.sheen.rokid.contracts.ToolPhase.START, label = "打开 App")))
            }
            settle()
            hold("gif-tool-start", 6_000)

            scenario.onActivity { activity ->
                activity.acceptCommand(HintWireCodec.encode(
                    HintCommand.Tool("s-visual-gif", 1, AssistantRoute.WAKE, turnId = "turn-visual-gif",
                        phase = com.sheen.rokid.contracts.ToolPhase.END, label = "打开 App")))
            }
            settle()
            hold("gif-tool-end", 6_000)

            scenario.onActivity { activity ->
                activity.acceptCommand(HintWireCodec.encode(
                    HintCommand.Sync("sync-visual-gif", 0, 1_000_000, System.nanoTime() + 60_000_000_000L)))
                activity.acceptCommand(HintWireCodec.encode(
                    HintCommand.State("s-visual-gif", 1, AssistantRoute.WAKE, turnId = "turn-visual-gif",
                        status = 3, sequence = 3)))
                activity.acceptCommand(HintWireCodec.encode(
                    HintCommand.Show("s-visual-gif", 1, sequence = 1, hintId = "h-visual-gif", revision = 1,
                        syncId = "sync-visual-gif", phoneSentNanos = System.nanoTime(), remainingTtlMillis = 30_000,
                        text = "**重点**\n\n- 第一项\n- 第二项\n\n`code`")))
            }
            settle()
            hold("markdown", 8_000)

            mark("done")
        }
    }

    private companion object {
        const val MARKER = "visual-state"
    }
}
