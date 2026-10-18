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
import android.view.WindowManager
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sheen.rokid.contracts.AssistantRoute
import com.sheen.rokid.glasses.app.GlassesActivity
import com.sheen.rokid.glasses.app.GlassesOverlayService
import com.sheen.rokid.output.HintCommand
import com.sheen.rokid.output.HintWireCodec
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
