package com.sheen.rokid.glasses

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sheen.rokid.contracts.AssistantRoute
import com.sheen.rokid.glasses.app.GlassesOverlayService
import com.sheen.rokid.output.HintCommand
import com.sheen.rokid.output.HintWireCodec
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 0.24 S5 Task 20: the screen-off/wake and CXR-disconnect behaviour of the production overlay service.
 *
 * Why this is separate from [OverlayServiceTest]: that suite pins the S2 window lifecycle; this one pins the S5
 * integration facts around it - the wake lock is a **waking** screen lock with the S1 10-minute bound, showing a panel
 * while the glasses are asleep really turns the display on, and a CXR disconnect hides the panel, releases the lock and
 * stops the resident service so the next wake's `appStart` starts a fresh one.
 *
 * The real CXR-S `onDisconnected` can only be produced by a live phone session. The disconnect tests drive the exact
 * production method through a test seam; "real CXR disconnect" remains `NotMeasured` until the Task 21 walkthrough.
 */
@RunWith(AndroidJUnit4::class)
class OverlayScreenOffTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val serviceIntent get() = Intent(context, GlassesOverlayService::class.java)
    private val powerManager get() = context.getSystemService(Context.POWER_SERVICE) as PowerManager

    /** Every payload the service tried to send while the test sink was installed. */
    private val sentJson: MutableList<String> = Collections.synchronizedList(mutableListOf())

    private var service: GlassesOverlayService? = null
    private var connection: ServiceConnection? = null

    @Before
    fun requireTheOverlayAppOp() {
        // Granted by the host before the run; a grant failure must fail the instrument rather than produce a green run
        // with no window (the same precondition OverlayServiceTest uses).
        assertTrue(
            "SYSTEM_ALERT_WINDOW must be granted before OverlayScreenOffTest " +
                "(adb -s 1901092544027662 shell appops set com.sheen.rokid.glasses SYSTEM_ALERT_WINDOW allow)",
            Settings.canDrawOverlays(context),
        )
    }

    @After
    fun tearDown() {
        service?.let { current ->
            runCatching { instrumentation.runOnMainSync { current.hidePanel() } }
        }
        connection?.let { runCatching { context.unbindService(it) } }
        service = null
        connection = null
        context.stopService(serviceIntent)
        waitUntil("service destroyed") { GlassesOverlayService.instanceForTest == null }
    }

    // ---------------------------------------------------------------- wake lock

    /**
     * The wake lock the panel holds must be the **waking** screen lock with the S1 local max hold.
     *
     * Reading the platform's own `WakeLock.toString()` keeps this from being a claim about our constants: the flags
     * (`SCREEN_BRIGHT_WAKE_LOCK | ACQUIRE_CAUSES_WAKEUP`) and `timeout=600000` ms are the actual acquired object. The
     * display-on effect itself is the separate device test below.
     */
    @Test
    fun showPanelAcquiresTheWakingScreenLockWithTheTenMinuteBoundAndHideReleasesIt() {
        val service = startAndBind()
        service.setSenderForProbe { json -> sentJson += json; true }
        assertFalse("nothing may hold the screen awake before the panel is shown",
            onMain { service.wakeLockHeldForProbe() })

        instrumentation.runOnMainSync {
            service.acceptCommand(HintWireCodec.encode(HintCommand.Hello("s-wakelock", 1)))
            service.showPanel(AssistantRoute.WAKE)
        }
        waitUntil("the overlay window is added") { onMain { service.overlayShownForProbe() } }
        assertTrue("showing the panel must hold the screen wake lock",
            onMain { service.wakeLockHeldForProbe() })

        val description = onMain { service.wakeLockDescriptionForProbe() }
        val flags = onMain { service.wakeLockFlagsForProbe() }
        val timeout = onMain { service.wakeLockTimeoutAppliedForProbe() }
        assertEquals("the lock must be SCREEN_BRIGHT_WAKE_LOCK|ACQUIRE_CAUSES_WAKEUP " +
            "(flags=$flags description=$description)", 0x1000000a, flags)
        assertEquals("the S1 10-minute local max hold must be the timeout passed to acquire() " +
            "(timeout=$timeout description=$description)", 600_000L, timeout)

        instrumentation.runOnMainSync { service.hidePanel() }
        waitUntil("the overlay window is removed") { !onMain { service.overlayShownForProbe() } }
        assertFalse("hiding the panel must release the wake lock",
            onMain { service.wakeLockHeldForProbe() })
    }

    /**
     * The actual screen-off gate: with the glasses asleep, showing the panel acquires a wake lock that lights the
     * display. Run this one explicitly after `adb shell input keyevent 26`; a platform that is already interactive is
     * skipped rather than turned green by a precondition the test did not create.
     */
    @Test
    fun showingThePanelWhileTheScreenIsOffTurnsTheScreenOn() {
        assumeFalse("put the glasses to sleep before this check (adb shell input keyevent 26)",
            powerManager.isInteractive)
        Log.i(TAG, "P3_SCREEN_CHECK state=off_before_show")

        val service = startAndBind()
        service.setSenderForProbe { json -> sentJson += json; true }
        instrumentation.runOnMainSync {
            service.acceptCommand(HintWireCodec.encode(HintCommand.Hello("s-screen-off", 1)))
            service.showPanel(AssistantRoute.WAKE)
        }
        waitUntil("the wake lock turns the display on", 15_000) { powerManager.isInteractive }
        waitUntil("the overlay window is added") { onMain { service.overlayShownForProbe() } }
        Log.i(TAG, "P3_SCREEN_CHECK state=on_after_show interactive=${powerManager.isInteractive} " +
            "wakeLockHeld=${onMain { service.wakeLockHeldForProbe() }}")

        assertTrue("the display must be interactive after the panel acquired the waking lock",
            powerManager.isInteractive)
        assertTrue("and the overlay must be visible under that lock", onMain { service.overlayShownForProbe() })

        instrumentation.runOnMainSync { service.hidePanel() }
        waitUntil("the overlay window is removed") { !onMain { service.overlayShownForProbe() } }
    }

    // ---------------------------------------------------------------- CXR disconnect

    /**
     * A CXR disconnect is the end of this resident panel process's usefulness: hide the window, release the lock,
     * clear the cache, and stop the service. The phone detects the missing `UI_READY`/`PONG` on the next wake and calls
     * `appStart`, which starts a fresh service - that is the recovery path, and it only works if the old one is gone.
     *
     * The service is bound by this test, so `stopSelf()` takes effect when the test releases the binding; the
     * assertion reads that platform fact (`instanceForTest == null` from `onDestroy`), not a service flag.
     */
    @Test
    fun aCxrDisconnectHidesThePanelReleasesTheWakeLockAndStopsTheService() {
        val service = startAndBind()
        service.setSenderForProbe { json -> sentJson += json; true }
        instrumentation.runOnMainSync {
            service.acceptCommand(HintWireCodec.encode(HintCommand.Hello("s-disconnect", 2)))
            service.showPanel(AssistantRoute.WAKE)
        }
        waitUntil("PANEL_SHOWN") { uiEvents().any { it is HintCommand.PanelShown } }
        instrumentation.runOnMainSync {
            service.acceptCommand(HintWireCodec.encode(
                HintCommand.Sync("sync-disconnect", 0, 1_000_000, System.nanoTime() + 120_000_000_000L)))
            service.acceptCommand(HintWireCodec.encode(
                HintCommand.Show("s-disconnect", 2, sequence = 1, hintId = "h-disconnect", revision = 1,
                    syncId = "sync-disconnect", phoneSentNanos = System.nanoTime(), remainingTtlMillis = 120_000,
                    text = "断连前的答案")))
        }
        waitUntil("the answer is rendered") { onMain { service.hintTextForProbe() } == "断连前的答案" }
        assertTrue("precondition: the panel holds the screen lock",
            onMain { service.wakeLockHeldForProbe() })
        val closedBefore = uiEvents().count { it is HintCommand.UiClosed }

        instrumentation.runOnMainSync { service.onCxrDisconnectedForProbe() }

        assertFalse("a CXR disconnect must hide the overlay", onMain { service.overlayShownForProbe() })
        assertFalse("and release the wake lock", onMain { service.wakeLockHeldForProbe() })
        assertEquals("and drop the panel conversation cache", "",
            onMain { service.conversationTextForProbe(AssistantRoute.WAKE) })
        assertEquals("the visible panel's close is still reported before the link went away",
            closedBefore + 1, uiEvents().count { it is HintCommand.UiClosed })

        val bound = requireNotNull(connection) { "the test must hold the binding it released below" }
        instrumentation.runOnMainSync { context.unbindService(bound) }
        connection = null
        waitUntil("the service stops itself after the CXR disconnect") {
            GlassesOverlayService.instanceForTest == null
        }
    }

    // ---------------------------------------------------------------- overlay over another app (host capture)

    /**
     * Host-driven capture: put the launcher in front, then run this test with `-e edith.overlayCapture true`. It shows
     * the panel over the other app and holds it until the host has taken its screenshot/`dumpsys` read. Skipped in
     * ordinary runs so a slow, deliberately-held window never becomes part of the normal suite.
     */
    @Test
    fun panelOverAnotherAppStaysVisibleAndFocusedForHostCapture() {
        assumeTrue("host device capture only: -e edith.overlayCapture true",
            InstrumentationRegistry.getArguments().getString("edith.overlayCapture") == "true")
        val service = startAndBind()
        service.setSenderForProbe { json -> sentJson += json; true }
        instrumentation.runOnMainSync {
            service.acceptCommand(HintWireCodec.encode(HintCommand.Hello("s-capture", 1)))
            service.showPanel(AssistantRoute.WAKE)
        }
        waitUntil("the overlay window is added") { onMain { service.overlayShownForProbe() } }
        waitUntil("the overlay window is focused over the other app") {
            onMain { service.overlayHasWindowFocusForProbe() }
        }
        Log.i(TAG, "P3_OVERLAY_CAPTURE state=visible focus=true " +
            "wakeLockHeld=${onMain { service.wakeLockHeldForProbe() }}")

        // The host waits for that marker, then takes the screenshot and the window dump.
        Thread.sleep(HOST_CAPTURE_WINDOW_MILLIS)

        instrumentation.runOnMainSync { service.hidePanel() }
        waitUntil("the overlay window is removed") { !onMain { service.overlayShownForProbe() } }
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

    private companion object {
        const val TAG = "EdithGlasses"
        const val HOST_CAPTURE_WINDOW_MILLIS = 8_000L
    }
}
