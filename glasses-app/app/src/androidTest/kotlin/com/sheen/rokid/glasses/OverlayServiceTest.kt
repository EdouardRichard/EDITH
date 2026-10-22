package com.sheen.rokid.glasses

import android.app.Activity
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.IBinder
import android.os.PowerManager
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
import com.sheen.rokid.glasses.accessibility.GlassesAccessibilityService
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
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 0.24 S2 Task 9: the production default is a foreground service that draws the panel as an overlay; since
 * 2026-10-20 it uses `TYPE_ACCESSIBILITY_OVERLAY` when the glasses accessibility service is bound (so the vendor HUD
 * can be covered) and falls back to `TYPE_APPLICATION_OVERLAY` otherwise. The Activity is the CXR scene host (Task 21
 * fix: it stays RESUMED but transparent and non-interactive, it never draws the panel).
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
        // startForeground posts asynchronously on this build; wait for the platform's active notification instead of
        // racing the post.
        waitUntil("the FOREGROUND_ID notification to become active") {
            onMain { service.activeForegroundNotificationForProbe() }
        }
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
        val accessibilityOverlay = GlassesAccessibilityService.isConnected()
        assertEquals(
            "the panel uses the accessibility overlay when the glasses a11y service is bound, otherwise the app overlay",
            if (accessibilityOverlay) WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            else WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            params.type,
        )
        assertEquals("only the accessibility overlay needs the HUD-hiding backdrop",
            accessibilityOverlay, onMain { service.backdropShownForProbe() })
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
        assertFalse("hidePanel must remove the accessibility backdrop too",
            onMain { service.backdropShownForProbe() })
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

    // ------------------------------------------- the production default: a transparent CXR scene host, not a trampoline

    /**
     * 0.24 S5 Task 21 owner fix (decision A): the default no-extra Activity must **stay RESUMED** as the CXR scene.
     *
     * The Task 9 design finished the Activity after starting the service. Measured on the device, the launcher then
     * resumes, CXR reports `onGlassAppResume=false` and terminates the CUSTOM_APP session with `GLASSES_APP_EXIT`;
     * the 5 s grace expires before the delayed `appStart` can bring anything up, so the production panel path never
     * obtains a live session. The scene host now starts the same foreground service but does not finish; it is
     * transparent and non-interactive so the service's 480×380 focusable overlay remains the only visible surface.
     */
    @Test
    fun theDefaultActivityKeepsTheTransparentCxrSceneAliveAndStartsTheService() {
        // Prove the default Activity is the only thing that starts the service: stop any prior instance first.
        context.stopService(serviceIntent)
        waitUntil("no service before the scene launch") { GlassesOverlayService.instanceForTest == null }

        val display = holdDisplayOn()
        val intent = Intent(context, GlassesActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val scenario = ActivityScenario.launch<GlassesActivity>(intent)
        try {
            waitUntil("the default Activity starts GlassesOverlayService") {
                GlassesOverlayService.instanceForTest != null
            }
            val started = requireNotNull(GlassesOverlayService.instanceForTest)
            assertEquals(
                "the production Activity must stay RESUMED as the CXR scene: finishing it lets the launcher resume " +
                    "and CXR terminates the session with GLASSES_APP_EXIT",
                Lifecycle.State.RESUMED, scenario.state)
            assertFalse("the default path must not draw the panel inside the Activity; the service starts hidden",
                onMain { started.overlayShownForProbe() })

            var sceneHost = false
            var panelHost = true
            var flags = 0
            var format = 0
            var backgroundTransparent = false
            var contentWidth = -1
            var contentHeight = -1
            var themeTranslucent = false
            scenario.onActivity { activity ->
                sceneHost = activity.isSceneHostForProbe()
                panelHost = activity.isPanelHostForProbe()
                flags = activity.sceneHostWindowFlagsForProbe()
                format = activity.sceneHostWindowFormatForProbe()
                backgroundTransparent = activity.sceneHostBackgroundTransparentForProbe()
                contentWidth = activity.sceneHostContentWidthForProbe()
                contentHeight = activity.sceneHostContentHeightForProbe()
                themeTranslucent = windowIsTranslucent(activity)
            }
            assertTrue("the production path must enter the transparent scene host", sceneHost)
            assertFalse("the scene host must never build the debug Activity panel", panelHost)
            assertTrue("the scene host must not take key focus from the service's focusable overlay " +
                "(flags=$flags)", flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0)
            assertTrue("the scene host must not take touches (flags=$flags)",
                flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE != 0)
            assertEquals("the scene window must be translucent, or it composites as opaque black over the launcher",
                PixelFormat.TRANSLUCENT, format)
            assertTrue("the scene Activity theme must declare android:windowIsTranslucent; a runtime " +
                "Window.setFormat alone leaves the ActivityRecord opaque and ActivityManager stops the launcher " +
                "behind it (measured: translucent=false, launcher task visibleRequested=false)", themeTranslucent)
            assertTrue("the scene window background must be transparent", backgroundTransparent)
            assertEquals("the scene host keeps only a 1x1 transparent content view", 1, contentWidth)
            assertEquals(1, contentHeight)
            assertTrue("the scene host must be registered with the service's scene-finish hook",
                GlassesActivity.sceneHostPresentForTest())
        } finally {
            runCatching { scenario.close() }
            runCatching { display.release() }
        }
    }

    /**
     * 0.24 S5 Task 21 owner fix (decision A): the transparent scene host may not outlive the CXR session.
     *
     * `GlassesOverlayService.onCxrDisconnected` is the production scene-end path (the bridge's own `onDisconnected`);
     * it already hides the panel and stops the service, and must also finish the Activity it was started from, so the
     * next `appStart` starts a fresh scene instead of reusing a dead one.
     */
    @Test
    fun aCxrDisconnectFinishesTheTransparentSceneHostActivity() {
        context.stopService(serviceIntent)
        waitUntil("no service before the scene launch") { GlassesOverlayService.instanceForTest == null }

        val display = holdDisplayOn()
        val intent = Intent(context, GlassesActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val scenario = ActivityScenario.launch<GlassesActivity>(intent)
        try {
            waitUntil("the scene host and its service") { GlassesOverlayService.instanceForTest != null }
            assertEquals("precondition: the production Activity is the RESUMED scene host",
                Lifecycle.State.RESUMED, scenario.state)
            val service = requireNotNull(GlassesOverlayService.instanceForTest)

            instrumentation.runOnMainSync { service.onCxrDisconnectedForProbe() }

            waitUntil("a CXR disconnect finishes the transparent scene host") {
                scenario.state == Lifecycle.State.DESTROYED
            }
            waitUntil("the scene-finish hook drops the dead Activity") {
                !GlassesActivity.sceneHostPresentForTest()
            }
        } finally {
            runCatching { scenario.close() }
            runCatching { display.release() }
        }
    }

    /**
     * 0.24 S5 Task 21 follow-up: with no panel shown, the launcher behind the scene must really be on screen.
     *
     * The Task 21 fix made the scene RESUMED and translucent, but the Activity stayed a **full-screen opaque**
     * ActivityRecord (no `windowIsTranslucent` in its theme): the launcher task was forced invisible
     * (`visibleRequested=false`) and the composited result was a black screen whenever the 480×380 overlay was
     * hidden. A resume-only assertion cannot see that, so this test puts the real launcher behind the scene and
     * reads the display: the launcher's centre hint band must still be drawn through the scene window. It also
     * proves the reported sequence - panel shown, panel hidden - leaves the launcher visible and the scene resumed.
     */
    @Test
    fun theSceneWindowLetsTheLauncherShowThroughWhenThePanelIsHidden() {
        context.stopService(serviceIntent)
        waitUntil("no service before the launcher-visibility scene launch") {
            GlassesOverlayService.instanceForTest == null
        }

        val display = holdDisplayOn()
        val scenario = try {
            // The launcher is the "previous app" a wearer sees between turns. HOME is a key event, not a UI tap.
            instrumentation.uiAutomation.executeShellCommand("input keyevent KEYCODE_HOME").close()
            waitForLauncherBandVisible("the launcher must be the visible app before the scene launches")

            ActivityScenario.launch<GlassesActivity>(
                Intent(context, GlassesActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (t: Throwable) {
            runCatching { display.release() }
            throw t
        }
        try {
            waitUntil("the default scene host and its service") { GlassesOverlayService.instanceForTest != null }
            val service = requireNotNull(GlassesOverlayService.instanceForTest) {
                "the scene Activity must start GlassesOverlayService"
            }
            assertEquals("precondition: the production Activity is the RESUMED scene host",
                Lifecycle.State.RESUMED, scenario.state)
            assertFalse("with no PANEL_OPEN the service must not draw a panel", onMain {
                service.overlayShownForProbe()
            })
            val hiddenPanelBand = waitForLauncherBandVisible(
                "the launcher must show through the scene while the panel is hidden")
            assertTrue("the launcher centre band must be really drawn, not an opaque scene (non-black pixels=" +
                "$hiddenPanelBand)", hiddenPanelBand >= LAUNCHER_BAND_MIN_PIXELS)

            // The reported failure was after a panel cycle: show the real overlay, hide it, then read the display.
            onMain {
                service.setSenderForProbe { true }
                service.acceptCommand(HintWireCodec.encode(HintCommand.Hello("s-scene-launcher", 1)))
                service.acceptCommand(HintWireCodec.encode(
                    HintCommand.PanelOpen("s-scene-launcher", 1, AssistantRoute.WAKE)))
            }
            waitUntil("the panel to appear over the scene") { onMain { service.overlayShownForProbe() } }
            onMain { service.hidePanel() }
            waitUntil("the panel to be hidden again") { !onMain { service.overlayShownForProbe() } }
            assertEquals("a panel close is not a CXR scene end: the Activity must stay RESUMED",
                Lifecycle.State.RESUMED, scenario.state)
            assertNotNull("a panel close must keep the resident service alive",
                GlassesOverlayService.instanceForTest)
            val afterHideBand = waitForLauncherBandVisible(
                "the launcher must still show through after the panel is hidden")
            assertTrue("the launcher must be visible after hiding the panel (non-black pixels=$afterHideBand)",
                afterHideBand >= LAUNCHER_BAND_MIN_PIXELS)
        } finally {
            runCatching { scenario.close() }
            runCatching { display.release() }
        }
    }

    /**
     * The translucent theme is production-only. The debug `EXTRA_HOST_PANEL` seam must keep the old full-screen,
     * opaque Activity window it has always had, otherwise the existing interaction/visual instrumentation would be
     * asserting a different window than the one it was written against.
     *
     * This is the regression guard for the Task 21 follow-up split: `GlassesActivity` is the translucent CXR scene
     * component, while the debug extra is hosted by `GlassesPanelHostActivity` with the original
     * `Theme.Material.NoActionBar`.
     */
    @Test
    fun theDebugHostPanelActivityKeepsItsOpaqueWindow() {
        launchHostPanel().use { scenario ->
            scenario.onActivity { activity ->
                assertTrue("the debug extra must still build the Activity panel host", activity.isPanelHostForProbe())
                assertFalse("the debug host must not become the production CXR scene", activity.isSceneHostForProbe())
                assertFalse("the translucent theme must stay production-only; the debug host keeps its opaque " +
                    "window for the existing interaction/visual suite", windowIsTranslucent(activity))
            }
        }
    }

    // ---------------------------------------------------------------- helpers

    /**
     * Holds the glasses display on for the duration of a scene-host test.
     *
     * This is a test precondition, not a product claim: this ROM's 5 s screen timeout would otherwise put the display
     * to sleep a couple of seconds after launch, which pauses/stops the (correctly transparent) Activity and makes
     * `ActivityScenario` report `CREATED`. A sleeping display is a separate acceptance concern (the panel's own wake
     * lock covers the wake path); the production Activity itself must never hold the screen on.
     */
    private fun holdDisplayOn(): PowerManager.WakeLock {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val lock = powerManager.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "edith:test-scene-host",
        )
        lock.setReferenceCounted(false)
        lock.acquire(TEST_DISPLAY_HOLD_MILLIS)
        waitUntil("the glasses display to wake for the scene-host test") { powerManager.isInteractive }
        return lock
    }

    /**
     * The theme the platform actually applied to [activity].
     *
     * `android:windowIsTranslucent` is an Activity **theme** attribute, read by PackageManager/ActivityManager before
     * the process starts; it is what makes the ActivityRecord translucent and keeps the activity below visible. A
     * runtime `Window.setFormat(PixelFormat.TRANSLUCENT)` does not change it.
     */
    private fun windowIsTranslucent(activity: Activity): Boolean {
        val attrs = activity.theme.obtainStyledAttributes(intArrayOf(android.R.attr.windowIsTranslucent))
        return try {
            attrs.getBoolean(0, false)
        } finally {
            attrs.recycle()
        }
    }

    private fun screenshot(): Bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot()) {
        "UiAutomation.takeScreenshot() returned null; the display must be interactive"
    }

    /**
     * The non-black pixels in the launcher's centre hint band of the **composited display**.
     *
     * The glasses launcher draws its green hint text and icons in this band over a black background. A full-screen
     * opaque scene replaces the band with black (measured 0 non-black pixels on the broken build), while a
     * translucent scene lets the same launcher pixels through (measured ~1 900). The clock/signal line sits outside
     * the band, so a screenshot pair taken seconds apart is comparable.
     */
    private fun launcherBandNonBlack(bitmap: Bitmap): Int {
        var count = 0
        val bottom = minOf(LAUNCHER_BAND_BOTTOM, bitmap.height)
        val right = minOf(LAUNCHER_BAND_RIGHT, bitmap.width)
        for (y in LAUNCHER_BAND_TOP until bottom) {
            for (x in LAUNCHER_BAND_LEFT until right) {
                val color = bitmap.getPixel(x, y)
                if (Color.red(color) > BAND_CHANNEL_THRESHOLD ||
                    Color.green(color) > BAND_CHANNEL_THRESHOLD ||
                    Color.blue(color) > BAND_CHANNEL_THRESHOLD
                ) {
                    count++
                }
            }
        }
        return count
    }

    private fun waitForLauncherBandVisible(what: String, timeoutMillis: Long = 10_000): Int {
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000L
        var last = -1
        while (System.nanoTime() < deadline) {
            last = launcherBandNonBlack(screenshot())
            if (last >= LAUNCHER_BAND_MIN_PIXELS) return last
            Thread.sleep(200)
        }
        fail("$what (last non-black pixels in the launcher band=$last, need >= $LAUNCHER_BAND_MIN_PIXELS)")
        return last
    }

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

    private companion object {
        /** Long enough for one scene-host test, short enough that a broken release cannot pin the panel forever. */
        const val TEST_DISPLAY_HOLD_MILLIS = 30_000L

        /** The launcher's centre hint band on the 480×640 home screen; the clock/signal line is outside it. */
        const val LAUNCHER_BAND_TOP = 300
        const val LAUNCHER_BAND_BOTTOM = 450
        const val LAUNCHER_BAND_LEFT = 40
        const val LAUNCHER_BAND_RIGHT = 440

        /** Measured ~1 900 non-black pixels here on the launcher; the opaque broken scene measured 0. */
        const val LAUNCHER_BAND_MIN_PIXELS = 800

        /** Any channel above this counts as drawn content (the launcher background is black). */
        const val BAND_CHANNEL_THRESHOLD = 40
    }
}
