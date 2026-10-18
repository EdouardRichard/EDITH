package com.sheen.rokid.glasses

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.PixelFormat
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sheen.rokid.contracts.AssistantRoute
import com.sheen.rokid.glasses.app.GlassesActivity
import com.sheen.rokid.glasses.app.GlassesOverlayService
import com.sheen.rokid.glasses.display.PagOrbView
import com.sheen.rokid.output.HintCommand
import com.sheen.rokid.output.HintWireCodec
import com.sheen.rokid.output.OfficialConversation
import com.sheen.rokid.output.OfficialStatus
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 0.24 S2 Task 9: the production default is a foreground service that draws the panel as a
 * `TYPE_APPLICATION_OVERLAY` window; the Activity is only the CXR trampoline.
 *
 * The assertions read the **service's own window**, not a log line: the overlay is really added to the
 * `WindowManager` with the pinned geometry/flags, `UI_READY` really goes out only after a session identity exists,
 * `PANEL_SHOWN` follows the first layout, and `hidePanel` really removes the window and clears the conversation
 * cache. The CXR send is the production send with a test-only sink (there is no phone in an instrumentation run),
 * exactly like the Activity's existing `setStopSenderForProbe`.
 */
@RunWith(AndroidJUnit4::class)
class OverlayServiceTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val serviceIntent get() = Intent(context, GlassesOverlayService::class.java)

    /** Every payload the service tried to send while the test sink was installed. */
    private val sentJson: MutableList<String> = Collections.synchronizedList(mutableListOf())

    private var service: GlassesOverlayService? = null
    private var connection: ServiceConnection? = null

    @Before
    fun requireTheOverlayAppOp() {
        // Granted by the host before the run (`adb -s ... shell appops set ... allow`); a grant failure must fail the
        // instrument rather than produce a green run with no window.
        assertTrue(
            "SYSTEM_ALERT_WINDOW must be granted before OverlayServiceTest " +
                "(adb -s 1901092544027662 shell appops set com.sheen.rokid.glasses SYSTEM_ALERT_WINDOW allow)",
            Settings.canDrawOverlays(context),
        )
    }

    @After
    fun tearDown() {
        service?.let { current ->
            runCatching { instrumentation.runOnMainSync { current.hidePanel() } }
            connection?.let { runCatching { context.unbindService(it) } }
        }
        service = null
        connection = null
        context.stopService(serviceIntent)
        waitUntil("service destroyed") { GlassesOverlayService.instanceForTest == null }
    }

    // ---------------------------------------------------------------- window + UI_READY

    @Test
    fun theServiceAddsTheOverlaySendsUiReadyAfterHelloAndHideRemovesIt() {
        val service = startAndBind()
        service.setSenderForProbe { json ->
            sentJson += json
            true
        }

        // Task 9 review I4: a resident panel must be a real foreground service with a low-importance notification.
        // All three values are read from the platform (ActivityManager / NotificationManager), not from service fields.
        assertTrue("the overlay service must be running as a foreground service",
            onMain { service.serviceForegroundForProbe() })
        assertTrue("the FOREGROUND_ID notification must be active",
            onMain { service.activeForegroundNotificationForProbe() })
        assertEquals("the resident notification must be low importance",
            NotificationManager.IMPORTANCE_LOW, onMain { service.foregroundChannelImportanceForProbe() })

        assertTrue("no UI_READY may be fabricated before a HELLO establishes the identity",
            uiEvents().none { it is HintCommand.UiReady })

        instrumentation.runOnMainSync {
            service.acceptCommand(HintWireCodec.encode(HintCommand.Hello("s-overlay", 3)))
        }
        waitUntil("UI_READY after the first HELLO") {
            uiEvents().count { it is HintCommand.UiReady } == 1
        }
        val ready = uiEvents().filterIsInstance<HintCommand.UiReady>().single()
        assertEquals("UI_READY must name the accepted session, never a fabricated one", "s-overlay", ready.sessionId)
        assertEquals(3L, ready.generation)

        instrumentation.runOnMainSync { service.showPanel(AssistantRoute.WAKE) }
        waitUntil("the overlay window is added") { onMain { service.overlayShownForProbe() } }
        waitUntil("PANEL_SHOWN after the overlay's first layout") {
            uiEvents().any { it is HintCommand.PanelShown }
        }

        val params = onMain { service.overlayParamsForProbe() }
        requireNotNull(params) { "the service must expose the added window params" }
        assertEquals("the panel is an application overlay",
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, params.type)
        assertEquals(480, params.width)
        assertEquals(380, params.height)
        assertEquals("bottom-start anchored", Gravity.BOTTOM or Gravity.START, params.gravity)
        assertEquals(PixelFormat.TRANSLUCENT, params.format)
        val requiredFlags = WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        assertEquals("all four pinned flags must be present", requiredFlags, params.flags and requiredFlags)
        assertEquals("a NOT_FOCUSABLE overlay would not receive the side keys",
            0, params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        val view = onMain { service.overlayViewForProbe() }
        requireNotNull(view) { "the added window must be the panel's root view" }
        assertTrue("the overlay view must be focusable", view.isFocusable)
        assertTrue(view.isFocusableInTouchMode)
        assertTrue("the overlay view must be attached", view.isAttachedToWindow)
        assertTrue("showPanel must hold the screen wake lock", onMain { service.wakeLockHeldForProbe() })

        instrumentation.runOnMainSync { service.hidePanel() }
        waitUntil("UI_CLOSED after removal") { uiEvents().any { it is HintCommand.UiClosed } }
        waitUntil("the overlay window is removed") { !onMain { service.overlayShownForProbe() } }
        assertNull("hidePanel must drop the window reference", onMain { service.overlayViewForProbe() })
        assertFalse("hidePanel must release the wake lock", onMain { service.wakeLockHeldForProbe() })
        val closed = uiEvents().filterIsInstance<HintCommand.UiClosed>().single()
        assertEquals("UI_CLOSED carries the visible route", AssistantRoute.WAKE, closed.route)
    }

    // ---------------------------------------------------------------- reset on close

    @Test
    fun aClosedPanelLeavesNoConversationBehindAndReopensEmpty() {
        val service = startAndBind()
        service.setSenderForProbe { json -> sentJson += json; true }
        instrumentation.runOnMainSync {
            service.acceptCommand(HintWireCodec.encode(HintCommand.Hello("s-reset", 1)))
        }
        waitUntil("UI_READY") { uiEvents().any { it is HintCommand.UiReady } }

        instrumentation.runOnMainSync { service.showPanel(AssistantRoute.WAKE) }
        waitUntil("PANEL_SHOWN") { uiEvents().any { it is HintCommand.PanelShown } }

        instrumentation.runOnMainSync {
            service.acceptCommand(HintWireCodec.encode(
                HintCommand.Sync("sync-reset", 0, 1_000_000, System.nanoTime() + 30_000_000_000L)))
            service.acceptCommand(HintWireCodec.encode(
                HintCommand.Said("s-reset", 1, sequence = 1, route = AssistantRoute.WAKE,
                    entryId = "said-reset", text = "关闭前说的话")))
            service.acceptCommand(HintWireCodec.encode(
                HintCommand.Show("s-reset", 1, sequence = 1, hintId = "h-reset", revision = 1,
                    syncId = "sync-reset", phoneSentNanos = System.nanoTime(), remainingTtlMillis = 30_000,
                    text = "关闭前的答案")))
        }
        waitUntil("the answer is rendered in the service's panel") {
            onMain { service.hintTextForProbe() } == "关闭前的答案"
        }
        assertTrue(onMain { service.contentRevisionForProbe() } > 0)

        instrumentation.runOnMainSync { service.hidePanel() }
        waitUntil("UI_CLOSED") { uiEvents().any { it is HintCommand.UiClosed } }
        assertEquals("closing the panel must clear the conversation cache", "",
            onMain { service.conversationTextForProbe(AssistantRoute.WAKE) })
        assertEquals("and restart the reveal revision", 0L, onMain { service.contentRevisionForProbe() })

        // Re-open without a new HELLO: the service survives, but nothing from the closed window may come back.
        instrumentation.runOnMainSync { service.showPanel(AssistantRoute.WAKE) }
        waitUntil("the second PANEL_SHOWN") {
            uiEvents().filterIsInstance<HintCommand.PanelShown>().size == 2
        }
        waitUntil("the re-opened overlay is added") { onMain { service.overlayShownForProbe() } }
        assertEquals("no conversation survives a close", "",
            onMain { service.conversationTextForProbe(AssistantRoute.WAKE) })
        assertEquals("no answer text survives a close", "", onMain { service.hintTextForProbe() })
        assertEquals("UI_READY is service-scoped and is not fabricated again", 1,
            uiEvents().count { it is HintCommand.UiReady })
    }

    @Test
    fun aCloseCommandHidesThePanelAndClearsTheCache() {
        val service = startAndBind()
        service.setSenderForProbe { json -> sentJson += json; true }
        instrumentation.runOnMainSync {
            service.acceptCommand(HintWireCodec.encode(HintCommand.Hello("s-close", 2)))
            service.showPanel(AssistantRoute.WAKE)
        }
        waitUntil("PANEL_SHOWN") { uiEvents().any { it is HintCommand.PanelShown } }
        instrumentation.runOnMainSync {
            service.acceptCommand(HintWireCodec.encode(
                HintCommand.Sync("sync-close", 0, 1_000_000, System.nanoTime() + 30_000_000_000L)))
            service.acceptCommand(HintWireCodec.encode(
                HintCommand.Show("s-close", 2, sequence = 1, hintId = "h-close", revision = 1,
                    syncId = "sync-close", phoneSentNanos = System.nanoTime(), remainingTtlMillis = 30_000,
                    text = "关掉之前的答案")))
        }
        waitUntil("the answer is on the panel") { onMain { service.hintTextForProbe() } == "关掉之前的答案" }

        instrumentation.runOnMainSync {
            service.acceptCommand(HintWireCodec.encode(HintCommand.Close("s-close", 2, route = null)))
        }
        waitUntil("the CLOSE command removes the overlay") { !onMain { service.overlayShownForProbe() } }
        waitUntil("UI_CLOSED after CLOSE") { uiEvents().any { it is HintCommand.UiClosed } }
        assertEquals("", onMain { service.conversationTextForProbe(AssistantRoute.WAKE) })
    }

    /**
     * Task 9 review I1: a `CLOSE` that arrives while the overlay is already hidden must still clear the cache.
     *
     * The controller keeps accepting `SHOW`/`SAID` while the window is hidden, so a late answer can sit in the cache
     * after BACK hid the panel. A hidden `CLOSE` is the only command that can throw it away before the next show.
     * `UI_CLOSED` must not be sent a second time: the phone already learned that this window hid, and a duplicate would
     * report a transition that did not happen.
     */
    @Test
    fun aCloseWhileHiddenClearsTheCacheAndDoesNotDuplicateUiClosed() {
        val service = startAndBind()
        service.setSenderForProbe { json -> sentJson += json; true }
        instrumentation.runOnMainSync {
            service.acceptCommand(HintWireCodec.encode(HintCommand.Hello("s-hidden-close", 5)))
            service.showPanel(AssistantRoute.WAKE)
        }
        waitUntil("UI_READY") { uiEvents().any { it is HintCommand.UiReady } }
        waitUntil("PANEL_SHOWN") { uiEvents().any { it is HintCommand.PanelShown } }
        instrumentation.runOnMainSync {
            service.acceptCommand(HintWireCodec.encode(
                HintCommand.Sync("sync-hidden-close", 0, 1_000_000, System.nanoTime() + 120_000_000_000L)))
            service.acceptCommand(HintWireCodec.encode(
                HintCommand.Show("s-hidden-close", 5, sequence = 1, hintId = "h-hidden-visible", revision = 1,
                    syncId = "sync-hidden-close", phoneSentNanos = System.nanoTime(), remainingTtlMillis = 120_000,
                    text = "可见时收到的答案")))
        }
        waitUntil("the visible answer is on the panel") {
            onMain { service.hintTextForProbe() } == "可见时收到的答案"
        }

        // BACK / a service hide: the window is gone, the session stays, and UI_CLOSED has been sent once.
        instrumentation.runOnMainSync { service.hidePanel() }
        waitUntil("the first UI_CLOSED") { uiEvents().count { it is HintCommand.UiClosed } == 1 }
        waitUntil("the overlay is hidden") { !onMain { service.overlayShownForProbe() } }

        // Late content accepted while hidden: this is the cache a hidden CLOSE must clear.
        instrumentation.runOnMainSync {
            service.acceptCommand(HintWireCodec.encode(
                HintCommand.Show("s-hidden-close", 5, sequence = 2, hintId = "h-hidden-late", revision = 1,
                    syncId = "sync-hidden-close", phoneSentNanos = System.nanoTime(), remainingTtlMillis = 120_000,
                    text = "隐藏时收到的答案")))
        }
        waitUntil("the hidden panel still stores the late answer in its cache") {
            onMain { service.hintTextForProbe() } == "隐藏时收到的答案" &&
                onMain { service.conversationTextForProbe(AssistantRoute.WAKE) }.contains("隐藏时收到的答案")
        }

        instrumentation.runOnMainSync {
            service.acceptCommand(HintWireCodec.encode(HintCommand.Close("s-hidden-close", 5, route = null)))
        }
        assertEquals("a hidden CLOSE must still clear the conversation cache", "",
            onMain { service.conversationTextForProbe(AssistantRoute.WAKE) })
        assertEquals("a hidden CLOSE must reset the reveal revision", 0L,
            onMain { service.contentRevisionForProbe() })
        assertEquals("a hidden CLOSE must clear the live row", "", onMain { service.hintTextForProbe() })
        assertEquals("a hidden CLOSE must not report a second hidden transition", 1,
            uiEvents().count { it is HintCommand.UiClosed })

        // Re-open: the empty cache is what the wearer sees.
        instrumentation.runOnMainSync { service.showPanel(AssistantRoute.WAKE) }
        waitUntil("the re-opened overlay") { onMain { service.overlayShownForProbe() } }
        assertEquals("no conversation may survive a hidden CLOSE", "",
            onMain { service.conversationTextForProbe(AssistantRoute.WAKE) })
        assertEquals("no answer may survive a hidden CLOSE", "", onMain { service.hintTextForProbe() })
    }

    /**
     * Task 9 review I2 / Task 11 policy: `UI_READY` is per accepted session identity, not once per service.
     *
     * The phone restarting while the resident service stays alive opens a new session identity; that new session must
     * get its own readiness event, while the repeated opening HELLO (the phone repeats it while waiting for the clock
     * exchange) must not duplicate it.
     */
    @Test
    fun uiReadyIsPerAcceptedSessionIdentityNotOncePerService() {
        val service = startAndBind()
        service.setSenderForProbe { json -> sentJson += json; true }

        instrumentation.runOnMainSync {
            service.acceptCommand(HintWireCodec.encode(HintCommand.Hello("s-ready", 1)))
        }
        assertEquals("the first accepted HELLO emits one UI_READY", 1,
            uiEvents().count { it is HintCommand.UiReady })
        assertEquals(1L, uiEvents().filterIsInstance<HintCommand.UiReady>().single().generation)

        instrumentation.runOnMainSync {
            // The phone repeats its opening HELLO while the clock exchange is pending: same identity, no new event.
            service.acceptCommand(HintWireCodec.encode(HintCommand.Hello("s-ready", 1)))
        }
        assertEquals("a repeated HELLO with the same identity must not duplicate UI_READY", 1,
            uiEvents().count { it is HintCommand.UiReady })

        instrumentation.runOnMainSync {
            // A new session generation (e.g. the phone app restarted while this service stayed resident).
            service.acceptCommand(HintWireCodec.encode(HintCommand.Hello("s-ready", 2)))
        }
        waitUntil("UI_READY for the new generation") {
            uiEvents().count { it is HintCommand.UiReady } == 2
        }
        val ready = uiEvents().filterIsInstance<HintCommand.UiReady>()
        assertEquals("the new generation must be named by the new UI_READY", 2L, ready[1].generation)
        assertEquals("UI_READY must name, never fabricate, the accepted session", "s-ready", ready[1].sessionId)
    }

    // ---------------------------------------------------------------- the production overlay's key path

    /**
     * Task 9 review I3: the production overlay's `dispatchKeyEvent` path is the only wearer input on the device.
     *
     * The 21 legacy tests drive the Activity's `onKeyUp`/unhandled-key listener, not the overlay root. This test shows
     * the panel because that is when the window can take focus, waits for the real window focus, then injects the same
     * `KeyEvent`s through `sendKeyDownUpSync` (the platform injection path the existing key tests use). It proves the
     * overlay's handler runs for ENTER and BACK; it does not claim anything about the ring's physical key delivery.
     */
    @Test
    fun theProductionOverlayKeyPathPinsOnEnterAndHidesOnBack() {
        val service = startAndBind()
        service.setSenderForProbe { json -> sentJson += json; true }
        instrumentation.runOnMainSync {
            service.acceptCommand(HintWireCodec.encode(HintCommand.Hello("s-keys", 7)))
            service.showPanel(AssistantRoute.WAKE)
        }
        waitUntil("PANEL_SHOWN") { uiEvents().any { it is HintCommand.PanelShown } }
        waitUntil("the overlay window is focused") { onMain { service.overlayHasWindowFocusForProbe() } }
        assertFalse("nothing may be pinned before ENTER", onMain { service.isPinnedForProbe() })

        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_ENTER)
        waitUntil("ENTER pins the overlay's reading position") { onMain { service.isPinnedForProbe() } }
        assertTrue("a pin must not close the panel", onMain { service.overlayShownForProbe() })

        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        waitUntil("BACK hides the overlay") { !onMain { service.overlayShownForProbe() } }
        waitUntil("UI_CLOSED after the BACK hide") { uiEvents().any { it is HintCommand.UiClosed } }
        assertFalse("the close reset must also drop the pin", onMain { service.isPinnedForProbe() })
        assertFalse("and release the wake lock", onMain { service.wakeLockHeldForProbe() })
    }

    // ---------------------------------------------------------------- Task 13: official BACK/tips/Hi on the overlay

    /**
     * 0.24 S3 Task 13: the production overlay applies the official status matrix end to end.
     *
     * It proves three things on the real window rather than in a JVM fake: the automatic next turn (status 5 → 1)
     * listens without restoring Hi; the tip says "双击打断" only after the pin + status 3; and BACK in that state sends
     * `STOP` and keeps the window, while the pinned idle's BACK removes the window and emits `UI_CLOSED`.
     */
    @Test
    fun theOverlayKeepsHiOffOnContinuationAndRunsThePinnedBackMatrix() {
        val service = startAndBind()
        val stops = Collections.synchronizedList(mutableListOf<HintCommand>())
        service.setSenderForProbe { json -> sentJson += json; true }
        service.setStopSenderForProbe { json ->
            HintWireCodec.decode(json)?.let { stops += it }
            true
        }
        instrumentation.runOnMainSync {
            service.acceptCommand(HintWireCodec.encode(HintCommand.Hello("s-task13", 1)))
            service.showPanel(AssistantRoute.WAKE)
        }
        waitUntil("PANEL_SHOWN") { uiEvents().any { it is HintCommand.PanelShown } }
        waitUntil("the overlay window is focused") { onMain { service.overlayHasWindowFocusForProbe() } }

        instrumentation.runOnMainSync {
            service.acceptCommand(HintWireCodec.encode(
                HintCommand.State("s-task13", 1, AssistantRoute.WAKE, turnId = "t13-1",
                    status = OfficialStatus.LISTENING.wire, sequence = 1)))
        }
        waitUntil("first-open Hi") { onMain { service.hiVisibleForProbe() } }
        assertTrue("status 1 also owns the \"…\" row", onMain { service.listeningVisibleForProbe() })
        assertEquals(OfficialConversation.TIPS_EXIT, onMain { service.tipsTextForProbe() })
        assertTrue("unpinned: 单击钉住 is shown", onMain { service.pinTextVisibleForProbe() })
        assertFalse(onMain { service.pinIconVisibleForProbe() })

        instrumentation.runOnMainSync {
            service.acceptCommand(HintWireCodec.encode(
                HintCommand.State("s-task13", 1, AssistantRoute.WAKE, turnId = "t13-1",
                    status = OfficialStatus.AUDIO_FINISHED.wire, sequence = 2)))
        }
        waitUntil("status 5 hides Hi") { !onMain { service.hiVisibleForProbe() } }
        instrumentation.runOnMainSync {
            service.acceptCommand(HintWireCodec.encode(
                HintCommand.State("s-task13", 1, AssistantRoute.WAKE, turnId = "t13-2",
                    status = OfficialStatus.LISTENING.wire, sequence = 3)))
        }
        waitUntil("the continuation listens") { onMain { service.listeningVisibleForProbe() } }
        assertFalse("showListenerUI never restores Hi", onMain { service.hiVisibleForProbe() })

        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_ENTER)
        waitUntil("ENTER pins on the up") { onMain { service.isPinnedForProbe() } }
        assertFalse("pinned hides 单击钉住", onMain { service.pinTextVisibleForProbe() })
        assertTrue("and shows the pin icon", onMain { service.pinIconVisibleForProbe() })

        instrumentation.runOnMainSync {
            service.acceptCommand(HintWireCodec.encode(
                HintCommand.State("s-task13", 1, AssistantRoute.WAKE, turnId = "t13-2",
                    status = OfficialStatus.ANSWERING.wire, sequence = 4)))
        }
        waitUntil("pinned + answering says interrupt") {
            onMain { service.tipsTextForProbe() } == OfficialConversation.TIPS_STOP
        }
        val closedBefore = uiEvents().count { it is HintCommand.UiClosed }

        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        waitUntil("BACK in pinned answering sends STOP") { synchronized(stops) { stops.isNotEmpty() } }
        waitUntil("the STOP branch is recorded") { onMain { service.lastBackActionForProbe() } == "stop" }
        assertTrue("a sent STOP keeps the panel", onMain { service.overlayShownForProbe() })
        assertEquals("a STOP is not a close", closedBefore, uiEvents().count { it is HintCommand.UiClosed })
        assertTrue("the STOP names the accepted session",
            stops.filterIsInstance<HintCommand.Stop>().single().sessionId == "s-task13")

        instrumentation.runOnMainSync {
            service.acceptCommand(HintWireCodec.encode(
                HintCommand.Idle("s-task13", 1, AssistantRoute.WAKE, sequence = 5)))
        }
        waitUntil("the pinned idle is back to the exit copy") {
            onMain { service.tipsTextForProbe() } == OfficialConversation.TIPS_EXIT
        }
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        waitUntil("the pinned idle's BACK hides the overlay") {
            !onMain { service.overlayShownForProbe() }
        }
        waitUntil("and reports UI_CLOSED") {
            uiEvents().count { it is HintCommand.UiClosed } == closedBefore + 1
        }
        assertEquals("the exit branch is recorded", "exit", onMain { service.lastBackActionForProbe() })
    }

    /**
     * 0.24 S3 Task 13 key timing on the production overlay router: ENTER/BACK act on `ACTION_UP` and consume the
     * `ACTION_DOWN`; the vertical directions page on `ACTION_DOWN` and do not consume the up; left/right are not the
     * panel's keys at all.
     */
    @Test
    fun theOverlayKeyTimingSplitsDownAndUpAndLeavesLeftRightAlone() {
        val service = startAndBind()
        service.setSenderForProbe { _ -> true }
        instrumentation.runOnMainSync {
            service.acceptCommand(HintWireCodec.encode(HintCommand.Hello("s-task13-keys", 1)))
            service.showPanel(AssistantRoute.WAKE)
        }
        waitUntil("the overlay is up") { onMain { service.overlayShownForProbe() } }

        instrumentation.runOnMainSync {
            assertTrue("ENTER down is consumed", service.dispatchKeyForProbe(
                KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER)))
            assertFalse("but does not pin", service.isPinnedForProbe())
            assertTrue("ENTER up is consumed", service.dispatchKeyForProbe(
                KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER)))
            assertTrue("and pins", service.isPinnedForProbe())

            assertTrue("DPAD_DOWN acts on the down", service.dispatchKeyForProbe(
                KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_DOWN)))
            assertFalse("and its up is not consumed", service.dispatchKeyForProbe(
                KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_DOWN)))
            assertTrue("DPAD_UP acts on the down", service.dispatchKeyForProbe(
                KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_UP)))
            assertFalse("and its up is not consumed", service.dispatchKeyForProbe(
                KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_UP)))

            assertFalse("left is not the panel's", service.dispatchKeyForProbe(
                KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_LEFT)))
            assertFalse("right is not the panel's", service.dispatchKeyForProbe(
                KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT)))

            assertTrue("BACK down is consumed", service.dispatchKeyForProbe(
                KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK)))
            assertTrue("but does not hide on the down", service.overlayShownForProbe())
        }
    }

    // ---------------------------------------------------------------- Task 11: PANEL_OPEN / route-scoped CLOSE

    /**
     * Task 11: a hidden panel reopened by `PANEL_OPEN` starts from nothing.
     *
     * The controller keeps accepting `SHOW`/`SAID` while the window is hidden (Task 9 I1), so the cache it accumulated
     * under a hidden window must be thrown away on hidden→visible. The official rule behind this is `showAiDialog`'s
     * `resetDialogUiState` when it is not already showing.
     */
    @Test
    fun panelOpenFromHiddenShowsThePanelAndDropsWhateverArrivedWhileHidden() {
        val service = startAndBind()
        service.setSenderForProbe { json -> sentJson += json; true }
        instrumentation.runOnMainSync {
            service.acceptCommand(HintWireCodec.encode(HintCommand.Hello("s-panel-open", 1)))
        }
        waitUntil("UI_READY") { uiEvents().any { it is HintCommand.UiReady } }

        instrumentation.runOnMainSync {
            service.acceptCommand(HintWireCodec.encode(
                HintCommand.PanelOpen("s-panel-open", 1, AssistantRoute.WAKE)))
        }
        waitUntil("PANEL_OPEN adds the overlay") { onMain { service.overlayShownForProbe() } }
        waitUntil("PANEL_SHOWN") { uiEvents().any { it is HintCommand.PanelShown } }

        instrumentation.runOnMainSync {
            service.acceptCommand(HintWireCodec.encode(
                HintCommand.Sync("sync-panel-open", 0, 1_000_000, System.nanoTime() + 120_000_000_000L)))
            service.acceptCommand(HintWireCodec.encode(
                HintCommand.Show("s-panel-open", 1, sequence = 1, hintId = "h-panel-open", revision = 1,
                    syncId = "sync-panel-open", phoneSentNanos = System.nanoTime(), remainingTtlMillis = 120_000,
                    text = "可见时的答案")))
        }
        waitUntil("the visible answer is rendered") {
            onMain { service.hintTextForProbe() } == "可见时的答案"
        }

        // BACK / a service hide: visible→hidden. The cache is cleared here already; the late content below is the
        // cache that hidden→visible must drop.
        instrumentation.runOnMainSync { service.hidePanel() }
        waitUntil("the first UI_CLOSED") { uiEvents().count { it is HintCommand.UiClosed } == 1 }
        waitUntil("the overlay is hidden") { !onMain { service.overlayShownForProbe() } }

        instrumentation.runOnMainSync {
            service.acceptCommand(HintWireCodec.encode(
                HintCommand.Show("s-panel-open", 1, sequence = 2, hintId = "h-panel-open-hidden", revision = 1,
                    syncId = "sync-panel-open", phoneSentNanos = System.nanoTime(), remainingTtlMillis = 120_000,
                    text = "隐藏时收到的答案")))
        }
        waitUntil("the hidden panel still stores the late answer") {
            onMain { service.hintTextForProbe() } == "隐藏时收到的答案"
        }

        instrumentation.runOnMainSync {
            service.acceptCommand(HintWireCodec.encode(
                HintCommand.PanelOpen("s-panel-open", 1, AssistantRoute.WAKE)))
        }
        waitUntil("the second PANEL_OPEN adds the overlay") { onMain { service.overlayShownForProbe() } }
        waitUntil("the second PANEL_SHOWN") {
            uiEvents().filterIsInstance<HintCommand.PanelShown>().size == 2
        }

        assertEquals("hidden→visible must drop the cache built while hidden", "",
            onMain { service.conversationTextForProbe(AssistantRoute.WAKE) })
        assertEquals("no answer may survive a hidden→visible open", "", onMain { service.hintTextForProbe() })
        assertEquals("the reveal revision must restart on the new window", 0L,
            onMain { service.contentRevisionForProbe() })
    }

    /**
     * Task 11 fix round: `PANEL_OPEN` while visible keeps the official no-reset rule **and re-announces
     * `PANEL_SHOWN`**.
     *
     * `PANEL_SHOWN` is EDITH's correlation/liveness event, not official content. The phone can be visible without a
     * live mapping for a bounded grace (C2's `UI_READY` → `PANEL_SHOWN` before the first `PONG`); if it ever loses the
     * event (or clears the visible flag after the grace), a repeated `PANEL_OPEN` must let it re-correlate with the
     * window the wearer is still looking at. The re-announce is idempotent: the official `showAiDialog` no-op is kept,
     * so no cache reset and no revision change.
     */
    @Test
    fun panelOpenWhileVisibleReannouncesPanelShownWithoutResettingTheCache() {
        val service = startAndBind()
        service.setSenderForProbe { json -> sentJson += json; true }
        instrumentation.runOnMainSync {
            service.acceptCommand(HintWireCodec.encode(HintCommand.Hello("s-panel-visible", 1)))
            service.acceptCommand(HintWireCodec.encode(
                HintCommand.PanelOpen("s-panel-visible", 1, AssistantRoute.WAKE)))
        }
        waitUntil("PANEL_SHOWN") { uiEvents().any { it is HintCommand.PanelShown } }
        instrumentation.runOnMainSync {
            service.acceptCommand(HintWireCodec.encode(
                HintCommand.Sync("sync-panel-visible", 0, 1_000_000, System.nanoTime() + 120_000_000_000L)))
            service.acceptCommand(HintWireCodec.encode(
                HintCommand.Show("s-panel-visible", 1, sequence = 1, hintId = "h-panel-visible", revision = 1,
                    syncId = "sync-panel-visible", phoneSentNanos = System.nanoTime(), remainingTtlMillis = 120_000,
                    text = "可见时保留的答案")))
        }
        waitUntil("the answer is rendered") { onMain { service.hintTextForProbe() } == "可见时保留的答案" }
        val revisionBefore = onMain { service.contentRevisionForProbe() }

        instrumentation.runOnMainSync {
            service.acceptCommand(HintWireCodec.encode(
                HintCommand.PanelOpen("s-panel-visible", 1, AssistantRoute.WAKE)))
        }
        waitUntil("the repeated PANEL_OPEN re-announces PANEL_SHOWN") {
            uiEvents().count { it is HintCommand.PanelShown } == 2
        }

        val shown = uiEvents().filterIsInstance<HintCommand.PanelShown>()
        assertEquals("the re-announce must name the visible route", AssistantRoute.WAKE, shown[1].route)
        assertEquals("and the accepted session, never a fabricated one", "s-panel-visible", shown[1].sessionId)
        assertEquals(1L, shown[1].generation)
        assertTrue("a visible panel stays visible", onMain { service.overlayShownForProbe() })
        assertEquals("a visible PANEL_OPEN must not reset the cache", "可见时保留的答案",
            onMain { service.hintTextForProbe() })
        assertEquals("and must not restart the reveal revision", revisionBefore,
            onMain { service.contentRevisionForProbe() })
    }

    /**
     * 0.24 S4 Task 18 review N1: closing the panel while the official listener loops, then reopening with
     * `PANEL_OPEN`, leaves the fresh/no-work model holding the official `listener` tag. The next `STATE` 1/2 must
     * resume that held listener; `listener_first` is tag-gated while the listener tag is already current, so the orb
     * would otherwise stay frozen until status 4.
     *
     * This drives the production overlay service (not a standalone view), so the close/reopen reset sequence that
     * creates the held state is the real one.
     */
    @Test
    fun panelOpenAfterClosingWhileListeningResumesTheHeldListenerOnTheNextState() {
        val service = startAndBind()
        service.setSenderForProbe { json -> sentJson += json; true }
        instrumentation.runOnMainSync {
            service.acceptCommand(HintWireCodec.encode(HintCommand.Hello("s-held-listener", 1)))
            service.acceptCommand(HintWireCodec.encode(
                HintCommand.PanelOpen("s-held-listener", 1, AssistantRoute.WAKE)))
            service.acceptCommand(HintWireCodec.encode(
                HintCommand.State("s-held-listener", 1, AssistantRoute.WAKE, turnId = "t-held",
                    status = OfficialStatus.LISTENING.wire, sequence = 1)))
        }
        waitUntil("PANEL_SHOWN") { uiEvents().any { it is HintCommand.PanelShown } }
        var orb: PagOrbView? = null
        onMain { orb = findPagOrb(service.overlayViewForProbe()) }
        val view = requireNotNull(orb) { "the production overlay must contain the PAG orb" }
        waitUntil("the listener must reach the looping middle") {
            onMain { view.currentSegmentForProbe() } == "ai_listener_middle.pag"
        }
        assertEquals("listener", onMain { view.currentTagForProbe() })

        var sequence = 1L
        for (status in listOf(OfficialStatus.ASR, OfficialStatus.LISTENING)) {
            // Close while the listener is on screen: the window hides and the conversation cache is cleared.
            instrumentation.runOnMainSync { service.hidePanel() }
            waitUntil("the overlay is hidden") { !onMain { service.overlayShownForProbe() } }

            // Reopen: PANEL_OPEN resets to an empty model, whose no-work hold keeps the official listener tag.
            instrumentation.runOnMainSync {
                service.acceptCommand(HintWireCodec.encode(
                    HintCommand.PanelOpen("s-held-listener", 1, AssistantRoute.WAKE)))
            }
            waitUntil("the reopened overlay is visible") { onMain { service.overlayShownForProbe() } }
            waitUntil("the reopened empty panel holds the listener") {
                onMain { view.currentTagForProbe() } == "listener" &&
                    onMain { view.currentSegmentForProbe() } == "ai_listener_middle.pag" &&
                    !onMain { view.isRunningForProbe() }
            }
            val historyAtHold = onMain { view.historyForProbe() }

            sequence += 1
            instrumentation.runOnMainSync {
                service.acceptCommand(HintWireCodec.encode(
                    HintCommand.State("s-held-listener", 1, AssistantRoute.WAKE, turnId = "t-held",
                        status = status.wire, sequence = sequence)))
            }
            waitUntil("STATE ${status.wire} must resume the held listener, not be tag-gated") {
                onMain { view.isRunningForProbe() }
            }
            assertEquals("the held listener stays loaded", "ai_listener_middle.pag",
                onMain { view.currentSegmentForProbe() })
            assertEquals("the official listener tag is kept", "listener",
                onMain { view.currentTagForProbe() })
            assertFalse("STATE ${status.wire} must not restart from the gated listener_first",
                onMain { view.historyForProbe().drop(historyAtHold.size).contains("ai_listener_first.pag") })
        }
    }

    /**
     * Task 11 fix round: the re-announce is route-scoped. A `PANEL_OPEN` for another route while the WAKE window is up
     * must not claim that other route is shown to the phone (that would let Task 12 start a window for a panel the
     * wearer does not have), and must not reset the visible window either.
     */
    @Test
    fun panelOpenForAnotherRouteWhileVisibleDoesNotReannounce() {
        val service = startAndBind()
        service.setSenderForProbe { json -> sentJson += json; true }
        instrumentation.runOnMainSync {
            service.acceptCommand(HintWireCodec.encode(HintCommand.Hello("s-panel-other-route", 4)))
            service.acceptCommand(HintWireCodec.encode(
                HintCommand.PanelOpen("s-panel-other-route", 4, AssistantRoute.WAKE)))
        }
        waitUntil("PANEL_SHOWN") { uiEvents().any { it is HintCommand.PanelShown } }
        instrumentation.runOnMainSync {
            service.acceptCommand(HintWireCodec.encode(
                HintCommand.Sync("sync-panel-other-route", 0, 1_000_000,
                    System.nanoTime() + 120_000_000_000L)))
            service.acceptCommand(HintWireCodec.encode(
                HintCommand.Show("s-panel-other-route", 4, sequence = 1, hintId = "h-panel-other-route",
                    revision = 1, syncId = "sync-panel-other-route", phoneSentNanos = System.nanoTime(),
                    remainingTtlMillis = 120_000, text = "WAKE 的答案")))
        }
        waitUntil("the answer is rendered") { onMain { service.hintTextForProbe() } == "WAKE 的答案" }
        val revisionBefore = onMain { service.contentRevisionForProbe() }

        instrumentation.runOnMainSync {
            service.acceptCommand(HintWireCodec.encode(
                HintCommand.PanelOpen("s-panel-other-route", 4, AssistantRoute.PROACTIVE)))
        }

        assertEquals("another route's PANEL_OPEN may not re-announce the visible WAKE route", 1,
            uiEvents().count { it is HintCommand.PanelShown })
        assertTrue("the WAKE window stays visible", onMain { service.overlayShownForProbe() })
        assertEquals("and keeps its content", "WAKE 的答案", onMain { service.hintTextForProbe() })
        assertEquals("and its revision", revisionBefore, onMain { service.contentRevisionForProbe() })
    }

    /** Task 11: `CLOSE` is route-scoped - another route's close may not hide the panel in front of the wearer. */
    @Test
    fun aCloseForAnotherRouteDoesNotHideTheVisiblePanel() {
        val service = startAndBind()
        service.setSenderForProbe { json -> sentJson += json; true }
        instrumentation.runOnMainSync {
            service.acceptCommand(HintWireCodec.encode(HintCommand.Hello("s-route-close", 1)))
            service.acceptCommand(HintWireCodec.encode(
                HintCommand.PanelOpen("s-route-close", 1, AssistantRoute.WAKE)))
        }
        waitUntil("PANEL_SHOWN") { uiEvents().any { it is HintCommand.PanelShown } }
        instrumentation.runOnMainSync {
            service.acceptCommand(HintWireCodec.encode(
                HintCommand.Sync("sync-route-close", 0, 1_000_000, System.nanoTime() + 120_000_000_000L)))
            service.acceptCommand(HintWireCodec.encode(
                HintCommand.Show("s-route-close", 1, sequence = 1, hintId = "h-route-close", revision = 1,
                    syncId = "sync-route-close", phoneSentNanos = System.nanoTime(), remainingTtlMillis = 120_000,
                    text = "WAKE 的答案")))
        }
        waitUntil("the WAKE answer is rendered") { onMain { service.hintTextForProbe() } == "WAKE 的答案" }

        instrumentation.runOnMainSync {
            service.acceptCommand(HintWireCodec.encode(
                HintCommand.Close("s-route-close", 1, route = AssistantRoute.PROACTIVE)))
        }

        assertTrue("another route's CLOSE must not hide the visible panel", onMain { service.overlayShownForProbe() })
        assertEquals("and must not clear the visible route's content", "WAKE 的答案",
            onMain { service.hintTextForProbe() })
        assertEquals("and must not report a hidden transition", 0,
            uiEvents().count { it is HintCommand.UiClosed })
    }

    /**
     * Task 11: while hidden, any valid `CLOSE` still clears the cache - window presence is independent of the gate.
     *
     * This is the hidden-close rule (Task 9 I1) with a route that is **not** the last visible one, so a hidden close
     * cannot be implemented by "only the visible route may clear".
     */
    @Test
    fun aHiddenCloseForAnotherRouteStillClearsTheCache() {
        val service = startAndBind()
        service.setSenderForProbe { json -> sentJson += json; true }
        instrumentation.runOnMainSync {
            service.acceptCommand(HintWireCodec.encode(HintCommand.Hello("s-hidden-route", 2)))
            service.acceptCommand(HintWireCodec.encode(
                HintCommand.PanelOpen("s-hidden-route", 2, AssistantRoute.WAKE)))
        }
        waitUntil("PANEL_SHOWN") { uiEvents().any { it is HintCommand.PanelShown } }
        instrumentation.runOnMainSync {
            service.acceptCommand(HintWireCodec.encode(
                HintCommand.Sync("sync-hidden-route", 0, 1_000_000, System.nanoTime() + 120_000_000_000L)))
            service.acceptCommand(HintWireCodec.encode(
                HintCommand.Show("s-hidden-route", 2, sequence = 1, hintId = "h-hidden-route", revision = 1,
                    syncId = "sync-hidden-route", phoneSentNanos = System.nanoTime(), remainingTtlMillis = 120_000,
                    text = "隐藏前的答案")))
        }
        waitUntil("the answer is rendered") { onMain { service.hintTextForProbe() } == "隐藏前的答案" }

        instrumentation.runOnMainSync { service.hidePanel() }
        waitUntil("the first UI_CLOSED") { uiEvents().count { it is HintCommand.UiClosed } == 1 }
        waitUntil("the overlay is hidden") { !onMain { service.overlayShownForProbe() } }

        instrumentation.runOnMainSync {
            service.acceptCommand(HintWireCodec.encode(
                HintCommand.Show("s-hidden-route", 2, sequence = 2, hintId = "h-hidden-route-late", revision = 1,
                    syncId = "sync-hidden-route", phoneSentNanos = System.nanoTime(), remainingTtlMillis = 120_000,
                    text = "隐藏时收到的答案")))
        }
        waitUntil("the hidden cache holds the late answer") {
            onMain { service.hintTextForProbe() } == "隐藏时收到的答案"
        }

        instrumentation.runOnMainSync {
            service.acceptCommand(HintWireCodec.encode(
                HintCommand.Close("s-hidden-route", 2, route = AssistantRoute.PROACTIVE)))
        }

        assertEquals("a hidden CLOSE must clear for any route", "",
            onMain { service.conversationTextForProbe(AssistantRoute.WAKE) })
        assertEquals("a hidden CLOSE must clear the live row", "", onMain { service.hintTextForProbe() })
        assertEquals("a hidden CLOSE must not report a second hidden transition", 1,
            uiEvents().count { it is HintCommand.UiClosed })
    }

    // ---------------------------------------------------------------- the production default is the trampoline

    @Test
    fun theDefaultActivityStartsTheForegroundServiceInsteadOfHostingThePanel() {
        // Prove the default Activity is the only thing that starts the service: stop any prior instance first.
        context.stopService(serviceIntent)
        waitUntil("no service before the trampoline launch") { GlassesOverlayService.instanceForTest == null }

        // Task 9 review I5: launch the real no-extra path through ActivityScenario and prove it reaches DESTROYED.
        // The service-start assertion is unchanged; the destroyed state adds "and the Activity is not hosting a panel".
        val intent = Intent(context, GlassesActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val scenario = ActivityScenario.launch<GlassesActivity>(intent)
        try {
            waitUntil("the default Activity starts GlassesOverlayService") {
                GlassesOverlayService.instanceForTest != null
            }
            val started = requireNotNull(GlassesOverlayService.instanceForTest)
            assertEquals("the trampoline Activity must finish after starting the service",
                Lifecycle.State.DESTROYED, scenario.state)
            assertFalse("the default path must not draw the panel inside the Activity; the service starts hidden",
                onMain { started.overlayShownForProbe() })
        } finally {
            runCatching { scenario.close() }
        }
    }

    // ---------------------------------------------------------------- helpers

    private fun startAndBind(): GlassesOverlayService {
        context.stopService(serviceIntent)
        waitUntil("no stale service instance") { GlassesOverlayService.instanceForTest == null }
        context.startForegroundService(serviceIntent)
        val latch = CountDownLatch(1)
        val serviceConnection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                service = (binder as GlassesOverlayService.LocalBinder).service()
                latch.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName?) = Unit
        }
        connection = serviceConnection
        assertTrue("bindService must accept the local service",
            context.bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE))
        assertTrue("the overlay service must start and bind", latch.await(15, TimeUnit.SECONDS))
        return requireNotNull(service) { "onServiceConnected did not hand over the service" }
    }

    private fun uiEvents(): List<HintCommand> = synchronized(sentJson) {
        sentJson.mapNotNull { json -> HintWireCodec.decode(json) }
    }

    /** Finds the panel's PAG orb in the service's real overlay view tree. */
    private fun findPagOrb(view: View?): PagOrbView? {
        if (view == null) return null
        if (view is PagOrbView) return view
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                findPagOrb(view.getChildAt(index))?.let { return it }
            }
        }
        return null
    }

    private fun <T> onMain(block: () -> T): T {
        var result: T? = null
        instrumentation.runOnMainSync { result = block() }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    private fun waitUntil(what: String, timeoutMillis: Long = 10_000, condition: () -> Boolean) {
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000L
        while (System.nanoTime() < deadline) {
            if (condition()) return
            Thread.sleep(50)
        }
        assertTrue("timed out waiting for $what", condition())
    }
}
