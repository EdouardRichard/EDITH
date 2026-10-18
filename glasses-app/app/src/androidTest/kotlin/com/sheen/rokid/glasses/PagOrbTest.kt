package com.sheen.rokid.glasses

import android.os.SystemClock
import android.util.Log
import android.view.ViewGroup
import android.view.WindowManager
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sheen.rokid.contracts.AssistantRoute
import com.sheen.rokid.glasses.app.GlassesActivity
import com.sheen.rokid.glasses.display.GlassesPanelSpec
import com.sheen.rokid.glasses.display.OrbState
import com.sheen.rokid.glasses.display.PagOrbView
import com.sheen.rokid.glasses.display.VoiceOrbView
import com.sheen.rokid.output.HintCommand
import com.sheen.rokid.output.HintWireCodec
import com.sheen.rokid.output.OfficialStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.libpag.PAGFile

/**
 * 0.24 S4 Task 18: the official PAG orb on the glasses (spec §3.4).
 *
 * Two claims are checked here and nowhere else: (1) libpag 4.5.27 can load all nine official `.pag` files on this
 * device/ABI, and (2) the `onAnimationEnd` chain and the per-segment repeat count run on the real view, not only in the
 * JVM tag model. The last test drives the production Activity/host seam (`STATE`/`IDLE` → `PagOrbView`) so a mapping
 * bug cannot hide behind a direct view call.
 *
 * No painted frame rate, power use or wearer legibility claim is made here; the screenshots are a sanity image only.
 */
@RunWith(AndroidJUnit4::class)
class PagOrbTest {

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    /**
     * The glasses' screen-off timeout is 5 s. A scenario launched onto a sleeping display can wake through its
     * `FLAG_KEEP_SCREEN_ON` window flag but still sleep five seconds later, freezing libpag's progress at ~0.02
     * (measured in the Task 18 report, §1.3). A real key wake before each test makes the run about libpag, not the
     * display timeout; the window flag then keeps the screen on for the test body.
     */
    @Before
    fun wakeTheDisplay() {
        instrumentation.uiAutomation.executeShellCommand("input keyevent KEYCODE_WAKEUP").close()
    }

    /**
     * The nine official clips, in the order the chain can touch them (spec §3.4). The names are the asset names in
     * the decoded official APK, not invented ones.
     */
    private val officialAssets = listOf(
        "ai_listener_first.pag",
        "ai_listener_middle.pag",
        "ai_listener_last.pag",
        "ai_think_first.pag",
        "ai_think_middle.pag",
        "ai_think_last.pag",
        "ai_finish_first.pag",
        "ai_finish_middle.pag",
        "ai_finish_last.pag",
    )

    @Test
    fun allNineOfficialPagAssetsLoadOnThisDevice() {
        officialAssets.forEach { name ->
            val path = "pag/$name"
            val bytes = context.assets.open(path).use { it.readBytes() }
            assertTrue("$name must not be empty", bytes.isNotEmpty())
            val file = PAGFile.Load(context.assets, path)
            assertNotNull("libpag must load $path", file)
            Log.i("PagOrbTest", "PAG_DURATION asset=$name bytes=${bytes.size} durationUs=${file!!.duration()}")
            assertTrue("$name must report a positive duration, was ${file!!.duration()}",
                file.duration() > 0)
            assertTrue("$name must decode the official bytes; ${file.duration()}us",
                file.duration() >= 1_000L)
        }
    }

    @Test
    fun theStatusChainRunsOnAnimationEndAndRestartsAfterTheFinishTail() {
        launchHostPanel().use { scenario ->
            keepScreenOn(scenario)
            var orb: PagOrbView? = null
            scenario.onActivity { activity ->
                // A standalone orb inside the real Activity window: the panel's own PAG view is driven by the host's
                // model in the next test, and letting the empty host model keep re-applying idle would clobber a
                // direct chain here (it did: the first device run ended on finish_last after the pause/resume).
                val view = PagOrbView(activity)
                activity.addContentView(view, ViewGroup.LayoutParams(
                    GlassesPanelSpec.VOICE_ORB_WIDTH_PX,
                    GlassesPanelSpec.VOICE_ORB_HEIGHT_PX))
                view.start()
                view.setStatus(OfficialStatus.LISTENING)
                orb = view
            }
            val view = requireNotNull(orb)

            // status 0/1/2: listener_first (once) -> listener_middle (loop).
            assertTrue("listener_first must hand over to the looping middle",
                awaitNewSegment(scenario, view, "ai_listener_middle.pag", 0))
            scenario.onActivity {
                assertFalse("the official assets must load on this device, not the fallback",
                    view.isFallbackForProbe())
                assertEquals("ai_listener_middle.pag", view.currentSegmentForProbe())
                assertEquals("the official listener tag covers first and middle",
                    "listener", view.currentTagForProbe())
                assertEquals("a middle clip loops", 0, view.repeatCountForProbe())
                assertTrue("the looping middle is playing", view.isRunningForProbe())
                assertEquals(OrbState.LISTENING, view.state())
            }

            // A loop means still playing after one full clip, on the same segment.
            SystemClock.sleep(durationMs("ai_listener_middle.pag") + 300)
            scenario.onActivity {
                assertTrue("a looping middle must still play after one clip length",
                    view.isRunningForProbe())
                assertEquals("ai_listener_middle.pag", view.currentSegmentForProbe())
            }

            // The host's hide/show contract: `stop()` pauses the clip, `start()` resumes the same segment.
            scenario.onActivity { view.stop() }
            scenario.onActivity {
                assertFalse("a hidden panel stops the orb", view.isRunningForProbe())
            }
            scenario.onActivity { view.start() }
            scenario.onActivity {
                assertTrue("a shown panel resumes the orb", view.isRunningForProbe())
                assertEquals("ai_listener_middle.pag", view.currentSegmentForProbe())
            }

            // status 4: listener_last -> think_first -> think_middle (loop), each once in between.
            var mark = 0
            scenario.onActivity {
                mark = view.historyForProbe().size
                view.setStatus(OfficialStatus.ASR_END)
            }
            assertTrue("status 4 must reach the looping thought",
                awaitNewSegment(scenario, view, "ai_think_middle.pag", mark))
            scenario.onActivity {
                assertEquals("status 4 chain",
                    listOf("ai_listener_last.pag", "ai_think_first.pag", "ai_think_middle.pag"),
                    view.historyForProbe().drop(mark))
                assertEquals("think_middle", view.currentTagForProbe())
                assertEquals(0, view.repeatCountForProbe())
                assertEquals(OrbState.THINKING, view.state())
            }

            // status 3: think_last -> finish_first -> finish_middle (loop).
            scenario.onActivity {
                mark = view.historyForProbe().size
                view.setStatus(OfficialStatus.ANSWERING)
            }
            assertTrue("status 3 must reach the looping finish",
                awaitNewSegment(scenario, view, "ai_finish_middle.pag", mark))
            scenario.onActivity {
                assertEquals("status 3 chain",
                    listOf("ai_think_last.pag", "ai_finish_first.pag", "ai_finish_middle.pag"),
                    view.historyForProbe().drop(mark))
                assertEquals("finish_middle", view.currentTagForProbe())
                assertEquals(0, view.repeatCountForProbe())
                assertEquals(OrbState.ANSWERING, view.state())
            }

            // status 5: finish_last, once, and the next status 1 restarts from listener_first.
            scenario.onActivity {
                mark = view.historyForProbe().size
                view.setStatus(OfficialStatus.AUDIO_FINISHED)
            }
            assertTrue("status 5 must play the finish tail",
                awaitNewSegment(scenario, view, "ai_finish_last.pag", mark))
            scenario.onActivity {
                assertEquals("finish_last", view.currentTagForProbe())
                assertEquals("a tail plays once", 1, view.repeatCountForProbe())
                assertEquals(OrbState.ANSWERING, view.state())
            }
            scenario.onActivity {
                mark = view.historyForProbe().size
                view.setStatus(OfficialStatus.LISTENING)
            }
            assertTrue("the next listening status must restart the listener chain",
                awaitNewSegment(scenario, view, "ai_listener_middle.pag", mark))
            scenario.onActivity {
                assertEquals("continuous conversation restart",
                    listOf("ai_listener_first.pag", "ai_listener_middle.pag"),
                    view.historyForProbe().drop(mark))
            }

            // IDLE: finish_last and hold the last frame (no further segment, no loop).
            scenario.onActivity {
                mark = view.historyForProbe().size
                view.setIdle()
            }
            assertTrue("IDLE must play the finish tail",
                awaitNewSegment(scenario, view, "ai_finish_last.pag", mark))
            scenario.onActivity {
                assertEquals("idle stops at the finish tail", "finish_last", view.currentTagForProbe())
                assertEquals("ai_finish_last.pag", view.currentSegmentForProbe())
                assertEquals(OrbState.IDLE, view.state())
            }
            SystemClock.sleep(durationMs("ai_finish_last.pag") + 300)
            scenario.onActivity {
                assertFalse("an idle orb holds the last frame, it does not loop",
                    view.isRunningForProbe())
                assertEquals("ai_finish_last.pag", view.currentSegmentForProbe())
            }

            // The official IDLE command is idempotent: re-publishing the same idle must not replay the tail.
            scenario.onActivity {
                mark = view.historyForProbe().size
                view.setIdle()
                view.setIdle()
            }
            SystemClock.sleep(200)
            scenario.onActivity {
                assertEquals("a repeated official IDLE must not replay finish_last",
                    emptyList<String>(), view.historyForProbe().drop(mark))
                assertEquals("ai_finish_last.pag", view.currentSegmentForProbe())
                assertFalse("the tail stays stopped", view.isRunningForProbe())
            }
        }
    }

    /**
     * 0.24 S4 Task 18 review I1: `OrbState.IDLE` is "no work", not the official `showIdleStatus`.
     *
     * The spec separates the two (spec §5.6:353): a fresh/legacy no-work panel stops (the old `VoiceOrbView` only
     * stopped its breathing animator), while only the official IDLE command plays `finish_last`. This test also pins the
     * dedupe: the host re-applies the same model on every render, so a repeated `setState(IDLE)` must be a no-op.
     */
    @Test
    fun noWorkIdleStopsWithoutTheFinishTailAndRepeatedIdleDoesNotReplay() {
        launchHostPanel().use { scenario ->
            keepScreenOn(scenario)
            var orb: PagOrbView? = null
            scenario.onActivity { activity ->
                val view = PagOrbView(activity)
                activity.addContentView(view, ViewGroup.LayoutParams(
                    GlassesPanelSpec.VOICE_ORB_WIDTH_PX,
                    GlassesPanelSpec.VOICE_ORB_HEIGHT_PX))
                view.start()
                view.setState(OrbState.IDLE)
                orb = view
            }
            val view = requireNotNull(orb)

            scenario.onActivity {
                assertEquals("a fresh no-work idle must load no clip",
                    emptyList<String>(), view.historyForProbe())
                assertEquals("", view.currentSegmentForProbe())
                assertEquals("no clip means the official none tag", "none", view.currentTagForProbe())
                assertFalse("a fresh no-work idle is stopped", view.isRunningForProbe())
            }

            // A running listener clip + no-work idle: stop/hold where it is, never load the finish tail.
            scenario.onActivity { view.setStatus(OfficialStatus.LISTENING) }
            assertTrue("the listener clip must start", awaitNewSegment(scenario, view, "ai_listener_middle.pag", 0))
            var mark = 0
            scenario.onActivity {
                mark = view.historyForProbe().size
                view.setState(OrbState.IDLE)
            }
            scenario.onActivity {
                assertEquals("no-work idle must not load anything", emptyList<String>(),
                    view.historyForProbe().drop(mark))
                assertEquals("the listener clip is held", "ai_listener_middle.pag",
                    view.currentSegmentForProbe())
                assertFalse("no-work idle stops the clip", view.isRunningForProbe())
            }

            // The host re-renders the same model on every update; repeated no-work idle is deduped.
            scenario.onActivity {
                view.setState(OrbState.IDLE)
                view.setState(OrbState.IDLE)
            }
            SystemClock.sleep(200)
            scenario.onActivity {
                assertEquals("repeated no-work idle must not load or replay anything", emptyList<String>(),
                    view.historyForProbe().drop(mark))
                assertFalse("and it stays stopped", view.isRunningForProbe())
            }

            // The no-work hold must not swallow the official IDLE command: it still plays the finish tail once.
            scenario.onActivity { view.setIdle() }
            assertTrue("official IDLE must still play finish_last after a no-work hold",
                awaitNewSegment(scenario, view, "ai_finish_last.pag", mark))
            scenario.onActivity {
                assertEquals("finish_last", view.currentTagForProbe())
                assertEquals("ai_finish_last.pag", view.currentSegmentForProbe())
                assertEquals(OrbState.IDLE, view.state())
            }

            // ... and it stays deduped, exactly like an official IDLE with no hold before it.
            var tailMark = 0
            scenario.onActivity {
                tailMark = view.historyForProbe().size
                view.setIdle()
            }
            SystemClock.sleep(200)
            scenario.onActivity {
                assertEquals("a repeated official IDLE after a no-work hold must not replay the tail",
                    emptyList<String>(), view.historyForProbe().drop(tailMark))
            }
        }
    }

    /**
     * 0.24 S4 Task 18 review N1: a no-work hold keeps the official tag (status 4 needs `listener`), so the next
     * listening status must resume/replay the held listener clip instead of asking for `listener_first`, which the
     * official tag gate refuses while the tag is already `listener`.
     *
     * This is the view half of the routine repro: close/hide while status 0/1/2 is playing, then come back to a
     * fresh/no-work model. All of status 0/1/2 must wake the held listener; status 4 must still chain from the kept
     * `listener` tag, which is why the fix must not blind-reset the tag.
     */
    @Test
    fun aHeldListenerResumesOnTheNextListeningStatusInsteadOfBeingTagGated() {
        launchHostPanel().use { scenario ->
            keepScreenOn(scenario)
            var orb: PagOrbView? = null
            scenario.onActivity { activity ->
                val view = PagOrbView(activity)
                activity.addContentView(view, ViewGroup.LayoutParams(
                    GlassesPanelSpec.VOICE_ORB_WIDTH_PX,
                    GlassesPanelSpec.VOICE_ORB_HEIGHT_PX))
                view.start()
                view.setStatus(OfficialStatus.LISTENING)
                orb = view
            }
            val view = requireNotNull(orb)
            assertTrue("the listener chain must reach the looping middle",
                awaitNewSegment(scenario, view, "ai_listener_middle.pag", 0))

            listOf(OfficialStatus.EMPTY, OfficialStatus.LISTENING, OfficialStatus.ASR).forEach { status ->
                var mark = 0
                scenario.onActivity {
                    view.setState(OrbState.IDLE)
                    mark = view.historyForProbe().size
                }
                scenario.onActivity {
                    assertFalse("no-work hold stops the listener before $status",
                        view.isRunningForProbe())
                    assertEquals("the held tag stays the official listener", "listener",
                        view.currentTagForProbe())
                    assertEquals("ai_listener_middle.pag", view.currentSegmentForProbe())
                }

                scenario.onActivity { view.setStatus(status) }
                scenario.onActivity {
                    assertTrue("$status must resume the held listener, not be tag-gated",
                        view.isRunningForProbe())
                    assertEquals("ai_listener_middle.pag", view.currentSegmentForProbe())
                    assertEquals("listener", view.currentTagForProbe())
                    assertEquals(OrbState.LISTENING, view.state())
                    assertFalse("$status must not restart from the gated listener_first",
                        view.historyForProbe().drop(mark).contains("ai_listener_first.pag"))
                }
            }

            // The tag is kept on purpose: status 4 still hands the listener over to the thought chain.
            var mark = 0
            scenario.onActivity {
                view.setState(OrbState.IDLE)
                mark = view.historyForProbe().size
                view.setStatus(OfficialStatus.ASR_END)
            }
            assertTrue("status 4 must still chain from the kept listener tag",
                awaitNewSegment(scenario, view, "ai_think_middle.pag", mark))
            scenario.onActivity {
                assertEquals("the status-4 chain",
                    listOf("ai_listener_last.pag", "ai_think_first.pag", "ai_think_middle.pag"),
                    view.historyForProbe().drop(mark))
            }
        }
    }

    /**
     * The production host starts from an empty model (no `STATE` yet). That model is idle but it is **not** the
     * official idle command, so the panel must not play the finish tail on open; the later status chain still runs.
     */
    @Test
    fun aFreshHostPanelDoesNotPlayTheFinishTail() {
        launchHostPanel().use { scenario ->
            keepScreenOn(scenario)
            scenario.onActivity { activity ->
                val orb = panelOrb(activity)
                assertEquals("a fresh window has no official status", OrbState.IDLE,
                    activity.orbStateForProbe())
                assertEquals("a fresh empty model must load no PAG clip", emptyList<String>(),
                    orb.historyForProbe())
                assertFalse("a fresh empty model must not play the finish tail", orb.isRunningForProbe())
            }

            // The fix must not block the normal chain once the official status arrives.
            scenario.onActivity { activity ->
                activity.acceptCommand(HintWireCodec.encode(HintCommand.Hello("s-fresh", 1)))
                activity.acceptCommand(HintWireCodec.encode(
                    HintCommand.State("s-fresh", 1, AssistantRoute.WAKE, turnId = "t-fresh",
                        status = OfficialStatus.LISTENING.wire, sequence = 1)))
            }
            assertTrue("the first official status still starts the listener chain",
                awaitActivitySegment(scenario, "ai_listener_middle.pag", 0))
        }
    }

    /**
     * The production seam: `STATE`/`IDLE` as the phone sends them reach the panel's `PagOrbView`.
     *
     * This is the regression guard for the host's model→status decision (Task 17's `orbStateFor` is not replaced, it
     * now also names the exact clip): a direct `PagOrbView` call cannot prove the Activity uses it.
     */
    @Test
    fun theStatusCommandsDriveThePanelOrbThroughTheHost() {
        launchHostPanel().use { scenario ->
            keepScreenOn(scenario)
            scenario.onActivity { activity ->
                activity.acceptCommand(HintWireCodec.encode(HintCommand.Hello("s-pag", 1)))
                activity.acceptCommand(HintWireCodec.encode(
                    HintCommand.State("s-pag", 1, AssistantRoute.WAKE, turnId = "t-pag",
                        status = OfficialStatus.LISTENING.wire, sequence = 1)))
            }
            assertTrue("STATE 1 must start the listener chain",
                awaitActivitySegment(scenario, "ai_listener_middle.pag", 0))
            scenario.onActivity { activity ->
                assertEquals(OrbState.LISTENING, activity.orbStateForProbe())
                val orb = panelOrb(activity)
                assertFalse("the device assets must load", orb.isFallbackForProbe())
            }

            var mark = 0
            scenario.onActivity { activity ->
                mark = panelOrb(activity).historyForProbe().size
                activity.acceptCommand(HintWireCodec.encode(
                    HintCommand.State("s-pag", 1, AssistantRoute.WAKE, turnId = "t-pag",
                        status = OfficialStatus.ASR_END.wire, sequence = 2)))
            }
            assertTrue("STATE 4 must reach the looping thought",
                awaitActivitySegment(scenario, "ai_think_middle.pag", mark))
            scenario.onActivity { activity ->
                assertEquals(OrbState.THINKING, activity.orbStateForProbe())
            }

            scenario.onActivity { activity ->
                activity.acceptCommand(HintWireCodec.encode(
                    HintCommand.State("s-pag", 1, AssistantRoute.WAKE, turnId = "t-pag",
                        status = OfficialStatus.AUDIO_FINISHED.wire, sequence = 3)))
            }
            scenario.onActivity { activity ->
                assertEquals(OrbState.ANSWERING, activity.orbStateForProbe())
                assertEquals("STATE 5 must load the finish tail", "finish_last",
                    panelOrb(activity).currentTagForProbe())
            }

            scenario.onActivity { activity ->
                activity.acceptCommand(HintWireCodec.encode(
                    HintCommand.Idle("s-pag", 1, AssistantRoute.WAKE, sequence = 4)))
            }
            scenario.onActivity { activity ->
                assertEquals("IDLE is the showIdleStatus state", OrbState.IDLE,
                    activity.orbStateForProbe())
                assertEquals("ai_finish_last.pag", panelOrb(activity).currentSegmentForProbe())
            }
        }
    }

    /**
     * The failure path is a stated requirement (spec §3.4): if a clip cannot load, the panel must keep working with the
     * pre-Task-18 drawn orb and log `PAG_LOAD_FAILED`. The internal asset-prefix constructor is the only way to force
     * that failure on a device whose assets are present; production still uses `PagOrbView(context)`.
     */
    @Test
    fun aMissingClipFallsBackToTheDrawnOrbAndKeepsTheProbeState() {
        launchHostPanel().use { scenario ->
            keepScreenOn(scenario)
            var orb: PagOrbView? = null
            scenario.onActivity { activity ->
                val view = PagOrbView(activity, "pag-missing/")
                activity.addContentView(view, ViewGroup.LayoutParams(
                    GlassesPanelSpec.VOICE_ORB_WIDTH_PX,
                    GlassesPanelSpec.VOICE_ORB_HEIGHT_PX))
                view.start()
                view.setStatus(OfficialStatus.LISTENING)
                orb = view
            }
            scenario.onActivity {
                val view = requireNotNull(orb)
                assertTrue("a missing clip must switch to the drawn fallback", view.isFallbackForProbe())
                assertEquals("the fallback still reports the panel's state", OrbState.LISTENING, view.state())
                assertEquals("the PAG child is replaced, not stacked", 1, view.childCount)
                assertTrue("the fallback is the pre-Task-18 drawing", view.getChildAt(0) is VoiceOrbView)
            }
        }
    }

    // ---------------------------------------------------------------- helpers

    /**
     * The glasses' screen-off timeout is 5 s. These tests are about an animation callback chain, so the window must
     * stay visible long enough to observe it; this is the platform `FLAG_KEEP_SCREEN_ON` the production panel's
     * wake claim also relies on. Without it the display sleeps mid-clip and libpag's progress freezes (measured:
     * progress 0.894 with `isPlaying=true`).
     */
    private fun keepScreenOn(scenario: ActivityScenario<GlassesActivity>) {
        scenario.onActivity { activity ->
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun panelOrb(activity: GlassesActivity): PagOrbView =
        requireNotNull(activity.orbViewForProbe()) { "the Activity host must expose the panel's PagOrbView" }

    private fun awaitNewSegment(
        scenario: ActivityScenario<GlassesActivity>,
        orb: PagOrbView,
        asset: String,
        fromIndex: Int,
        timeoutMs: Long = 10_000,
    ): Boolean {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            var seen = false
            scenario.onActivity { seen = orb.historyForProbe().drop(fromIndex).contains(asset) }
            if (seen) return true
            SystemClock.sleep(50)
        }
        var history = emptyList<String>()
        var status = ""
        scenario.onActivity {
            history = orb.historyForProbe()
            status = "tag=${orb.currentTagForProbe()} segment=${orb.currentSegmentForProbe()} " +
                "playing=${orb.isRunningForProbe()} progress=${orb.progressForProbe()} " +
                "durationUs=${orb.durationForProbe()}"
        }
        throw AssertionError("$asset did not load within $timeoutMs ms; history=$history $status")
    }

    private fun awaitActivitySegment(
        scenario: ActivityScenario<GlassesActivity>,
        asset: String,
        fromIndex: Int,
        timeoutMs: Long = 10_000,
    ): Boolean {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            var seen = false
            scenario.onActivity { activity ->
                seen = activity.orbViewForProbe()?.historyForProbe()?.drop(fromIndex)?.contains(asset) == true
            }
            if (seen) return true
            SystemClock.sleep(50)
        }
        throw AssertionError("$asset did not load through the Activity within $timeoutMs ms")
    }

    private fun durationMs(asset: String): Long {
        val file = PAGFile.Load(context.assets, "pag/$asset")
        requireNotNull(file) { "$asset must load to measure its duration" }
        return (file.duration() / 1_000L).coerceAtLeast(100L)
    }
}
