package com.sheen.rokid.glasses.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import com.sheen.rokid.contracts.AssistantRoute
import com.sheen.rokid.glasses.bridge.RokidBridgeEndpoint
import com.sheen.rokid.output.HintCommand
import com.sheen.rokid.output.HintWireCodec

/**
 * 0.24 S2 Task 9: the production panel host (D158).
 *
 * A foreground service with a low-importance notification owns the CXR listener and a `TYPE_APPLICATION_OVERLAY`
 * window, so the panel can appear over another app and while the glasses screen is off. The trampoline Activity only
 * starts this service; the service survives the Activity, and a panel close hides the window without ending the
 * session.
 *
 * The overlay geometry is the official panel's bottom box: 480×380 at `BOTTOM|START`, translucent, not touchable and
 * focusable (S1 proved the side keys arrive at a focusable `TYPE_APPLICATION_OVERLAY`). The screen wake lock is the
 * platform mechanism because the vendor SDK has no wake API (official FAQ documents only `FLAG_KEEP_SCREEN_ON` and
 * states that the ring's power key cannot be intercepted); it is acquired when the window is shown and released when
 * it is hidden or the service dies, with the same ten-minute local max hold the S1 probe used.
 *
 * `UI_READY` is only ever sent with the session identity the controller actually accepted. Before a `HELLO` there is
 * nothing to name, so the send is deferred and recorded, and the first accepted `HELLO` sends it. `PANEL_SHOWN`
 * follows the overlay's first layout; `UI_CLOSED` follows removal.
 *
 * This task does not implement the phone handshake, `PANEL_OPEN`/`STATE`/`IDLE` routing or the conversation state
 * machine (Tasks 10-19). The only inbound lifecycle command handled here is `CLOSE`, whose Task 9 obligation is to
 * hide the window and clear the glasses cache via [GlassesPanelController.resetForClose].
 */
class GlassesOverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var powerManager: PowerManager
    private val mainHandler = Handler(Looper.getMainLooper())

    /** The shared Android panel half; the Activity test host builds the same class. */
    private lateinit var host: GlassesPanelHost

    private var overlayRoot: FrameLayout? = null
    private var overlayParams: WindowManager.LayoutParams? = null
    private var wakeLock: PowerManager.WakeLock? = null

    /** True once `UI_READY` has actually gone out in this service lifetime. */
    private var uiReadySent = false

    /** A `PANEL_SHOWN` whose layout already happened before the session identity arrived; sent on the first HELLO. */
    private var pendingPanelShownRoute: AssistantRoute? = null

    /** Test-only CXR send sink; production leaves it null and uses [RokidBridgeEndpoint]. */
    @Volatile
    private var senderForProbe: ((String) -> Boolean)? = null

    private val callbacks = object : GlassesPanelHost.Callbacks {
        override fun hidePanel() = this@GlassesOverlayService.hidePanel()

        override fun showPanel(route: AssistantRoute) = this@GlassesOverlayService.showPanel(route)

        override fun keepAwake(hold: Boolean) {
            if (hold) acquireWakeLock() else releaseWakeLock()
        }
    }

    /** Local binder so an instrumentation test can drive the same service the Activity starts. */
    inner class LocalBinder : Binder() {
        fun service(): GlassesOverlayService = this@GlassesOverlayService
    }

    private val binder = LocalBinder()

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        startForegroundCompat()
        host = GlassesPanelHost(this, callbacks, GlassesPanelHost.WakePolicy.PANEL_VISIBLE)
        // The endpoint outlives every screen; the listener points at the resident service.
        RokidBridgeEndpoint.instance.setListener { json -> acceptCommand(json) }
        instanceForTest = this
        // No identity before a HELLO, so this records the deferral instead of fabricating a session.
        maybeSendUiReady()
        Log.i(TAG, "SERVICE ready canDrawOverlays=${android.provider.Settings.canDrawOverlays(this)}")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "SERVICE onStartCommand overlay=${overlayRoot != null}")
        // Recovery only: the phone detects a dead service via the missing UI_READY/PONG and calls appStart again.
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        runCatching { hidePanel() }
        releaseWakeLock()
        RokidBridgeEndpoint.instance.setListener(null)
        if (this::host.isInitialized) host.destroy()
        instanceForTest = null
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        Log.i(TAG, "SERVICE onDestroy")
        super.onDestroy()
    }

    // ---------------------------------------------------------------- window

    /**
     * Adds the overlay window for [route] and starts the panel. Idempotent: a repeated show while the window exists
     * only re-focuses it, so no cache reset can be triggered by a duplicate `PANEL_OPEN` (Task 11).
     */
    fun showPanel(route: AssistantRoute = AssistantRoute.WAKE) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { showPanel(route) }
            return
        }
        if (overlayRoot != null) {
            overlayRoot?.requestFocus()
            Log.i(TAG, "OVERLAY already-visible route=${route.name}")
            return
        }
        acquireWakeLock()
        val root = OverlayRoot(this).apply {
            isFocusable = true
            isFocusableInTouchMode = true
        }
        // A re-opened panel reuses the same host view (the controller and its state survive the close), so detach it
        // from the removed window root before attaching it to the new one.
        (host.root.parent as? android.view.ViewGroup)?.removeView(host.root)
        root.addView(host.root, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        val params = WindowManager.LayoutParams(
            WIDTH_PX,
            HEIGHT_PX,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.START
            x = 0
            y = 0
            setTitle(WINDOW_TITLE)
        }
        try {
            windowManager.addView(root, params)
        } catch (t: Throwable) {
            Log.e(TAG, "OVERLAY add_failed=${t.javaClass.simpleName}:${t.message}")
            releaseWakeLock()
            return
        }
        overlayRoot = root
        overlayParams = params
        root.requestFocus()
        host.installSideTouch(root)
        host.onStart()
        Log.i(TAG, "OVERLAY added type=TYPE_APPLICATION_OVERLAY gravity=BOTTOM|START size=${WIDTH_PX}x${HEIGHT_PX} " +
            "format=TRANSLUCENT flags=NOT_TOUCHABLE|LAYOUT_NO_LIMITS|LAYOUT_IN_SCREEN|KEEP_SCREEN_ON focusable=true")
        root.viewTreeObserver.addOnGlobalLayoutListener(object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                if (overlayRoot !== root || !root.isAttachedToWindow || root.width == 0) return
                root.viewTreeObserver.removeOnGlobalLayoutListener(this)
                Log.i(TAG, "OVERLAY laid_out width=${root.width} height=${root.height}")
                sendPanelShown(route)
            }
        })
    }

    /**
     * Removes the overlay, releases the wake lock and clears the panel's conversation cache.
     *
     * The service itself stays alive: a panel close is not the end of the phone session (spec §5.1).
     */
    fun hidePanel() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { hidePanel() }
            return
        }
        val root = overlayRoot ?: run {
            Log.i(TAG, "PANEL_HIDE ignored: no overlay")
            return
        }
        overlayRoot = null
        overlayParams = null
        val route = host.selectedRoute()
        pendingPanelShownRoute = null
        runCatching { windowManager.removeView(root) }
            .onFailure { Log.w(TAG, "OVERLAY remove_failed=${it.javaClass.simpleName}:${it.message}") }
        runCatching { root.removeAllViews() }
            .onFailure { Log.w(TAG, "OVERLAY detach_failed=${it.javaClass.simpleName}:${it.message}") }
        host.onStop()
        releaseWakeLock()
        // Task 9: a closed panel leaves no conversation behind. The session identity is kept (see resetForClose).
        host.resetForClose()
        sendUiClosed(route)
        Log.i(TAG, "PANEL_HIDDEN route=${route.name} conversation_cleared=true")
    }

    /** The overlay must be the focused window to receive the side keys, so its root owns the key path. */
    private inner class OverlayRoot(context: Context) : FrameLayout(context) {
        override fun dispatchKeyEvent(event: KeyEvent): Boolean =
            if (host.dispatchKeyEvent(event)) true else super.dispatchKeyEvent(event)
    }

    // ---------------------------------------------------------------- inbound

    /**
     * The production bridge entry point. The bridge calls from its callback thread, so a decoded command is posted to
     * the UI thread; the returned value is the decode answer, read on the caller's thread like the old Activity did.
     */
    fun acceptCommand(json: String): Boolean {
        if (Looper.myLooper() == Looper.getMainLooper()) return acceptOnMain(json)
        val understood = HintWireCodec.decode(json) != null
        if (understood) {
            mainHandler.post { acceptOnMain(json) }
        } else {
            Log.i(TAG, "inbound undecodable chars=${json.length}")
        }
        return understood
    }

    private fun acceptOnMain(json: String): Boolean {
        val command = HintWireCodec.decode(json)
        if (command is HintCommand.Close) {
            onClose(command)
            return true
        }
        val accepted = host.accept(json)
        if (accepted) maybeSendUiReady()
        return accepted
    }

    /**
     * Task 9's only lifecycle command: a valid `CLOSE` hides the panel and clears the cache. Route scoping and the
     * phone's panel-state handshake are Task 11; here a stale identity is refused rather than allowed to close a newer
     * session's window.
     */
    private fun onClose(command: HintCommand.Close) {
        val identity = host.currentIdentity()
        if (identity == null || identity.first != command.sessionId || identity.second != command.generation) {
            Log.i(TAG, "CLOSE refused: session or generation mismatch")
            return
        }
        Log.i(TAG, "CLOSE accepted session=${command.sessionId} route=${command.route?.name ?: "ALL"}")
        hidePanel()
    }

    // ---------------------------------------------------------------- outbound UI events

    /**
     * `UI_READY` is service-scoped and needs the session identity the controller accepted. Before the first HELLO the
     * send is deferred (and logged); a failed send is retried on the next accepted command rather than recorded as
     * sent.
     */
    private fun maybeSendUiReady() {
        if (uiReadySent) return
        val identity = host.currentIdentity() ?: run {
            Log.i(TAG, "UI_READY deferred: no session identity yet")
            return
        }
        val sent = send(HintCommand.UiReady(identity.first, identity.second))
        uiReadySent = sent
        Log.i(TAG, "UI_READY session=${identity.first} generation=${identity.second} sent=$sent")
        val pending = pendingPanelShownRoute
        if (pending != null && sent) {
            pendingPanelShownRoute = null
            sendPanelShown(pending)
        }
    }

    /** Sent after the overlay's first layout, with the accepted identity only. */
    private fun sendPanelShown(route: AssistantRoute) {
        val identity = host.currentIdentity()
        if (identity == null) {
            pendingPanelShownRoute = route
            Log.i(TAG, "PANEL_SHOWN deferred: no session identity yet route=${route.name}")
            return
        }
        val sent = send(HintCommand.PanelShown(identity.first, identity.second, route))
        Log.i(TAG, "PANEL_SHOWN route=${route.name} sent=$sent")
    }

    /** Sent after the overlay has been removed; the resident service is still alive. */
    private fun sendUiClosed(route: AssistantRoute) {
        val identity = host.currentIdentity()
        if (identity == null) {
            Log.i(TAG, "UI_CLOSED deferred: no session identity to name")
            return
        }
        val sent = send(HintCommand.UiClosed(identity.first, identity.second, route))
        Log.i(TAG, "UI_CLOSED route=${route.name} sent=$sent")
    }

    /** The one outbound seam for UI events; the test sink replaces the CXR send and observes the exact payload. */
    private fun send(command: HintCommand): Boolean {
        val json = runCatching { HintWireCodec.encode(command) }.getOrElse { failure ->
            Log.w(TAG, "encode failed for ${command::class.simpleName}", failure)
            return false
        }
        return runCatching { senderForProbe?.invoke(json) ?: RokidBridgeEndpoint.instance.send(json) }
            .getOrElse { failure ->
                Log.w(TAG, "send failed for ${command::class.simpleName}", failure)
                false
            }
    }

    // ---------------------------------------------------------------- foreground + wake lock

    /**
     * Starts the foreground service.
     *
     * The manifest type is `specialUse` (S1 measured that this Android 12 build accepts it). The explicit typed call
     * is attempted first; older ROMs may reject the type constant added in API 34, in which case the two-argument call
     * uses the manifest type and the rejection is logged rather than read as "the overlay failed".
     */
    private fun startForegroundCompat() {
        createChannel()
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentTitle("EDITH")
            .setContentText("面板服务运行中")
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                startForeground(FOREGROUND_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
                Log.i(TAG, "FOREGROUND start type=explicit(specialUse)")
                return
            } catch (t: Throwable) {
                Log.w(TAG, "FOREGROUND explicit specialUse rejected: ${t.javaClass.simpleName}:${t.message}")
            }
        }
        try {
            startForeground(FOREGROUND_ID, notification)
            Log.i(TAG, "FOREGROUND start type=manifest(specialUse)")
        } catch (t: Throwable) {
            Log.e(TAG, "FOREGROUND start FAILED: ${t.javaClass.simpleName}:${t.message}")
        }
    }

    private fun createChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "EDITH panel", NotificationManager.IMPORTANCE_LOW),
        )
    }

    @Suppress("DEPRECATION")
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val lock = powerManager.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "edith:overlay",
        ).apply {
            setReferenceCounted(false)
            acquire(WAKE_LOCK_TIMEOUT_MS)
        }
        wakeLock = lock
        Log.i(TAG, "WAKE_LOCK acquire held=${lock.isHeld} interactive=${powerManager.isInteractive}")
    }

    private fun releaseWakeLock() {
        val lock = wakeLock ?: return
        wakeLock = null
        runCatching { if (lock.isHeld) lock.release() }
        Log.i(TAG, "WAKE_LOCK release held=${lock.isHeld}")
    }

    // ---------------------------------------------------------------- test seams (production entry points)

    /** Test-only CXR sink, the service-side mirror of the Activity's `setStopSenderForProbe`. */
    fun setSenderForProbe(sender: ((String) -> Boolean)?) {
        senderForProbe = sender
    }

    fun overlayShownForProbe(): Boolean = overlayRoot != null

    fun overlayViewForProbe(): View? = overlayRoot

    fun overlayParamsForProbe(): WindowManager.LayoutParams? = overlayParams

    fun wakeLockHeldForProbe(): Boolean = wakeLock?.isHeld == true

    fun hintTextForProbe(): String = host.hintText()

    fun conversationTextForProbe(route: AssistantRoute): String = host.conversationText(route)

    fun contentRevisionForProbe(): Long = host.contentRevisionForProbe()

    companion object {
        private const val TAG = "EdithGlasses"
        private const val CHANNEL_ID = "edith-overlay"
        private const val FOREGROUND_ID = 1
        private const val WINDOW_TITLE = "EDITH-PANEL"

        /** The 480×640 canvas; the official panel occupies its bottom 380 px. */
        private const val WIDTH_PX = 480
        private const val HEIGHT_PX = 380

        /** Same local max hold as the S1 probe; the phone's screen-off path needs the display on, not forever. */
        private const val WAKE_LOCK_TIMEOUT_MS = 10 * 60 * 1000L

        /**
         * Test-only: the live service, so the trampoline test can prove that launching the default Activity started the
         * service (the alternative would have been to infer it from a log line). Cleared in [onDestroy].
         */
        @Volatile
        internal var instanceForTest: GlassesOverlayService? = null
    }
}
