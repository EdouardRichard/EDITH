package com.sheen.rokid.glasses

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sheen.rokid.contracts.AssistantRoute
import com.sheen.rokid.output.HintCommand
import com.sheen.rokid.output.HintWireCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * 0.23 P6 evidence instrument for U-11 (D154): the glasses show one route and never mix the other one in.
 *
 * The test reads the production route/log state **and** the live row's View text (`hintText()`); the host screenshot is
 * the panel-level renderer evidence for both rows. The injected text is synthetic; no real conversation content and no
 * real model turn is involved. This is a renderer-level device check, not a model round trip - the plugin half is pinned
 * by JVM tests.
 */
@RunWith(AndroidJUnit4::class)
class GlassesRouteIsolationTest {

    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun mark(state: String) {
        File(context.getExternalFilesDir(null), MARKER).writeText(state)
    }

    /** Same reason as `GlassesVisualStateTest`: the answering orb keeps an animator running, so "idle" is not reachable. */
    private fun settle() {
        Thread.sleep(700)
    }

    @Test
    fun aProactiveShowNeverMixesWakeContent() {
        launchHostPanel().use { scenario ->
            scenario.onActivity { activity ->
                activity.acceptCommand(HintWireCodec.encode(HintCommand.Hello("s-p6", 1)))
                activity.acceptCommand(HintWireCodec.encode(
                    HintCommand.Sync("sync-p6", offsetLowerNanos = 0, offsetUpperNanos = 1_000_000,
                        validUntilGlassNanos = System.nanoTime() + 30_000_000_000L)))
                activity.acceptCommand(HintWireCodec.encode(
                    HintCommand.Said("s-p6", 1, sequence = 1, route = AssistantRoute.WAKE,
                        entryId = "said-wake", text = "唤醒说的话")))
                activity.acceptCommand(HintWireCodec.encode(
                    HintCommand.Show("s-p6", 1, sequence = 2, hintId = "h-proactive", revision = 1,
                        syncId = "sync-p6", phoneSentNanos = System.nanoTime(), remainingTtlMillis = 30_000,
                        text = "主动提醒一句", route = AssistantRoute.PROACTIVE)))
                activity.acceptCommand(HintWireCodec.encode(
                    HintCommand.Said("s-p6", 1, sequence = 3, route = AssistantRoute.PROACTIVE,
                        entryId = "said-proactive", text = "主动说的话")))
            }
            settle()
            scenario.onActivity { activity ->
                assertEquals(AssistantRoute.PROACTIVE, activity.selectedRoute())
                assertTrue("the live row must render the proactive hint",
                    activity.hintText().contains("主动提醒一句"))
                assertTrue("主动提醒一句", activity.conversationText(AssistantRoute.PROACTIVE).contains("主动提醒一句"))
                assertTrue("the proactive route owns its own utterance",
                    activity.conversationText(AssistantRoute.PROACTIVE).contains("主动说的话"))
                assertEquals("唤醒说的话", activity.conversationText(AssistantRoute.WAKE))
                assertFalse(activity.conversationText(AssistantRoute.WAKE).contains("主动提醒一句"))
                assertFalse(activity.conversationText(AssistantRoute.WAKE).contains("主动说的话"))
            }
            mark("proactive")
            Thread.sleep(8_000)
            mark("done")
        }
    }

    private companion object {
        const val MARKER = "panel-route"
    }
}
