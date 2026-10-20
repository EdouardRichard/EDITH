package com.sheen.rokid.glasses.app

import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
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
import com.sheen.rokid.glasses.accessibility.GlassesAccessibilityService
import com.sheen.rokid.glasses.accessibility.PanelWindowPolicy
import com.sheen.rokid.glasses.bridge.RokidBridgeEndpoint
import com.sheen.rokid.output.HintCommand
import com.sheen.rokid.output.HintWireCodec

/**
 * 0.24 S2 Task 9: the production panel host (D158).
 *
 * A foreground service with a low-importance notification owns the CXR listener and a `TYPE_APPLICATION_OVERLAY`
 * window, so the panel can appear over another app and while the glasses screen is off. The transparent
 * [GlassesActivity] scene host starts this service and stays resumed (the CUSTOM_APP session needs the glasses app in
 * front); the service survives a panel close, and a panel close hides the window without ending the session.
 *
 * The overlay geometry is the official panel's bottom box: 480×380 at `BOTTOM|START`, translucent, not touchable and
 * focusable (S1 proved the side keys arrive at a focusable `TYPE_APPLICATION_OVERLAY`). The screen wake lock is the
 * platform mechanism because the vendor SDK has no wake API (official FAQ documents only `FLAG_KEEP_SCREEN_ON` and
 * states that the ring's power key cannot be intercepted); it is acquired when the window is shown and released when
 * it is hidden or the service dies, with the same ten-minute local max hold the S1 probe used.
 *
 * `UI_READY` is only ever sent with the session identity the controller actually accepted. Before a `HELLO` there is
 * nothing to name, so the send is deferred and recorded, and the first accepted `HELLO` sends it. It is **per accepted
 * session identity**, not once per service: a phone restart while this service stays resident opens a new session
 * generation and must receive a new `UI_READY`, while a repeated `HELLO` for the same identity must not duplicate it.
 * `PANEL_SHOWN` follows the overlay's first layout; `UI_CLOSED` follows the visible-to-hidden transition.
 *
 * This task implements the S2 panel lifecycle: `PANEL_OPEN` shows a hidden window (resetting the hidden cache) and
 * leaves an already-visible one's content untouched while re-announcing `PANEL_SHOWN` for the visible route, `CLOSE`
 * is route-scoped and clears the hidden/visible cache without ending
 * the resident session, and the phone's `UI_READY`/`UI_CLOSED`/`PANEL_SHOWN` handshake is driven from here. The S5 Task
 * 20 integration adds the screen/wear/reconnect edges: the wake lock is a waking screen lock with a bounded hold, a CXR
 * disconnect (the bridge's own `onDisconnected`) hides the panel, releases the lock, clears the cache and stops this
 * service so the next wake's `appStart` starts a fresh one, and the phone's wear-off arrives as the wire's ALL-route
 * `CLOSE`.
 */
class GlassesOverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var powerManager: PowerManager
    private val mainHandler = Handler(Looper.getMainLooper())

    /** The shared Android panel half; the Activity test host builds the same class. */
    private lateinit var host: GlassesPanelHost

    private var overlayRoot: FrameLayout? = null
    private var overlayParams: WindowManager.LayoutParams? = null
    /**
     * The manager that actually added [overlayRoot]. With the accessibility service this is the service's own
     * `WindowManager` (a `TYPE_ACCESSIBILITY_OVERLAY` cannot be added with this application context's token); the
     * fallback is [windowManager]. Removal must use the same manager.
     */
    private var overlayWindowManager: WindowManager? = null
    /** The full-screen opaque backdrop that hides the vendor HUD; non-null only in accessibility-overlay mode. */
    private var backdropRoot: FrameLayout? = null
    private var wakeLock: PowerManager.WakeLock? = null

    /** The timeout the current acquire call was given; read by [wakeLockTimeoutAppliedForProbe]. */
    private var wakeLockTimeoutAppliedMs = 0L

    /**
     * The route the visible overlay was opened for, or null while hidden.
     *
     * Kept beside the window rather than inferred from the controller's rendered model: `CLOSE` is route-scoped and
     * must match the window the wearer is looking at, even before the first `SHOW` selects a conversation.
     */
    private var visibleRoute: AssistantRoute? = null

    /**
     * The session identity `UI_READY` was last sent for, or null when it has never gone out.
     *
     * Per identity rather than one boolean: the resident service can outlive the phone app, and the next accepted
     * session must announce itself. A repeated `HELLO` for the same identity is not a new session and must not produce
     * a duplicate event.
     */
    private var lastUiReadyIdentity: Pair<String, Long>? = null

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
        // Task 20: the CXR-S bridge reports the link's own death. It arrives on the bridge thread; the handler posts.
        RokidBridgeEndpoint.instance.setDisconnectionListener { onCxrDisconnected() }
        // A panel drawn through the accessibility overlay disappears with that service. Hide our state as well, so a
        // later PANEL_OPEN re-adds it instead of reporting an already-visible window that no longer exists.
        GlassesAccessibilityService.onLost = {
            mainHandler.post {
                if (overlayRoot != null) {
                    Log.i(TAG, "A11Y_LOST hide_panel=true")
                    hidePanel()
                }
            }
        }
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
        GlassesAccessibilityService.onLost = null
        runCatching { hidePanel() }
        releaseWakeLock()
        RokidBridgeEndpoint.instance.setDisconnectionListener(null)
        RokidBridgeEndpoint.instance.setListener(null)
        if (this::host.isInitialized) host.destroy()
        // Safety net (Task 21 fix): the scene host exists only for this CXR session. `onCxrDisconnected` already ends
        // it, but a service destroyed by any other path must not leave a resumed transparent Activity behind.
        GlassesActivity.finishSceneHost()
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
        visibleRoute = route
        acquireWakeLock()
        val accessibilityManager = GlassesAccessibilityService.windowManagerOrNull()
        val useAccessibilityOverlay = accessibilityManager != null
        val panelWindowManager = accessibilityManager ?: windowManager
        // The panel is the key target while visible; the transparent scene must not compete for focus.
        if (useAccessibilityOverlay) GlassesActivity.setSceneFocusable(false)
        if (PanelWindowPolicy.needsBackdrop(useAccessibilityOverlay)) {
            addBackdrop(panelWindowManager)
        }
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
            PanelWindowPolicy.type(useAccessibilityOverlay),
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
            panelWindowManager.addView(root, params)
        } catch (t: Throwable) {
            Log.e(TAG, "OVERLAY add_failed=${t.javaClass.simpleName}:${t.message}")
            removeBackdrop(panelWindowManager)
            if (useAccessibilityOverlay) GlassesActivity.setSceneFocusable(true)
            visibleRoute = null
            releaseWakeLock()
            return
        }
        overlayRoot = root
        overlayParams = params
        overlayWindowManager = panelWindowManager
        root.requestFocus()
        host.installSideTouch(root)
        host.onStart()
        Log.i(TAG, "OVERLAY added type=${typeName(params.type)} accessibility=$useAccessibilityOverlay " +
            "gravity=BOTTOM|START size=${WIDTH_PX}x${HEIGHT_PX} " +
            "format=TRANSLUCENT flags=NOT_TOUCHABLE|LAYOUT_NO_LIMITS|LAYOUT_IN_SCREEN|KEEP_SCREEN_ON focusable=true " +
            "backdrop=${backdropRoot != null}")
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
     * Removes the overlay when it is visible, releases the wake lock and **always** clears the panel's conversation
     * cache.
     *
     * The reset is independent of window presence: the controller keeps accepting `SHOW`/`SAID` while the panel is
     * hidden, so a `CLOSE` in that state must still throw the hidden cache away or it would reappear on the next show
     * (Task 9 review I1). `UI_CLOSED` reports a **visible-to-hidden transition**, so it is sent only when this call
     * actually removed a window; an already-hidden close clears the cache without reporting a second close.
     *
     * The service itself stays alive: a panel close is not the end of the phone session (spec §5.1).
     */
    fun hidePanel() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { hidePanel() }
            return
        }
        val root = overlayRoot
        val manager = overlayWindowManager ?: windowManager
        val wasAccessibilityOverlay = overlayParams?.type == WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY ||
            backdropRoot != null
        if (root == null) {
            overlayWindowManager = null
            removeBackdrop(manager)
            if (wasAccessibilityOverlay) GlassesActivity.setSceneFocusable(true)
            pendingPanelShownRoute = null
            visibleRoute = null
            // No window to remove, but the hidden cache is still a cache: a valid CLOSE must clear it.
            host.resetForClose()
            Log.i(TAG, "PANEL_HIDDEN hidden=true conversation_cleared=true ui_closed=already-hidden")
            return
        }
        overlayRoot = null
        overlayParams = null
        overlayWindowManager = null
        val route = visibleRoute ?: host.selectedRoute()
        visibleRoute = null
        pendingPanelShownRoute = null
        runCatching { manager.removeView(root) }
            .onFailure { Log.w(TAG, "OVERLAY remove_failed=${it.javaClass.simpleName}:${it.message}") }
        runCatching { root.removeAllViews() }
            .onFailure { Log.w(TAG, "OVERLAY detach_failed=${it.javaClass.simpleName}:${it.message}") }
        removeBackdrop(manager)
        // Keep a focused window after the accessibility panel goes away; otherwise the top RESUMED transparent scene
        // has no focus target and this ROM raises an ANR and force-finishes it.
        if (wasAccessibilityOverlay) GlassesActivity.setSceneFocusable(true)
        host.onStop()
        releaseWakeLock()
        // Task 9: a closed panel leaves no conversation behind. The session identity is kept (see resetForClose).
        host.resetForClose()
        sendUiClosed(route)
        Log.i(TAG, "PANEL_HIDDEN route=${route.name} conversation_cleared=true")
    }

    /**
     * Adds the full-screen opaque layer that hides the vendor home HUD.
     *
     * Only meaningful for `TYPE_ACCESSIBILITY_OVERLAY`: the vendor HUD is a system window at 271000, above every
     * `TYPE_APPLICATION_OVERLAY` (121000), so a panel-only app overlay cannot cover it. The backdrop and the panel are
     * added through the same accessibility-service manager; the backdrop is NOT focusable, and the panel added after
     * it remains the focused window that receives the side keys.
     */
    private fun addBackdrop(manager: WindowManager) {
        if (backdropRoot != null) return
        val view = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val params = WindowManager.LayoutParams(
            PanelWindowPolicy.CANVAS_WIDTH_PX,
            PanelWindowPolicy.CANVAS_HEIGHT_PX,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.OPAQUE,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
            setTitle(BACKDROP_TITLE)
        }
        try {
            manager.addView(view, params)
            backdropRoot = view
            Log.i(TAG, "BACKDROP added type=TYPE_ACCESSIBILITY_OVERLAY " +
                "size=${PanelWindowPolicy.CANVAS_WIDTH_PX}x${PanelWindowPolicy.CANVAS_HEIGHT_PX} opaque")
        } catch (t: Throwable) {
            Log.e(TAG, "BACKDROP add_failed=${t.javaClass.simpleName}:${t.message}")
        }
    }

    private fun removeBackdrop(manager: WindowManager) {
        val view = backdropRoot ?: return
        backdropRoot = null
        runCatching { manager.removeView(view) }
            .onFailure { Log.w(TAG, "BACKDROP remove_failed=${it.javaClass.simpleName}:${it.message}") }
        Log.i(TAG, "BACKDROP removed")
    }

    private fun typeName(type: Int): String = when (type) {
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY -> "TYPE_ACCESSIBILITY_OVERLAY"
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY -> "TYPE_APPLICATION_OVERLAY"
        else -> "type=$type"
    }

    /** The overlay must be the focused window to receive the side keys, so its root owns the key path. */
    private inner class OverlayRoot(context: Context) : FrameLayout(context) {
        override fun dispatchKeyEvent(event: KeyEvent): Boolean =
            if (host.dispatchKeyEvent(event)) true else super.dispatchKeyEvent(event)
    }

    // ---------------------------------------------------------------- CXR disconnect (Task 20)

    /**
     * The CXR-S link went away (spec §5.7): hide the panel, release the waking screen lock, clear the cache and stop
     * this resident service.
     *
     * Stopping is the recovery contract, not an extra flourish: the phone only learns that a resident service is
     * missing from the absent `UI_READY`/`PONG`, and only calls `appStart` when it needs a scene. A service kept alive
     * across a dead CXR session would answer the next HELLO with the old identity and its first `UI_READY` could race
     * the new session; stopping it means the next wake starts a clean one. The phone's `CLOSE` path deliberately does
     * **not** stop the service - only the link's own death does.
     */
    private fun onCxrDisconnected() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { onCxrDisconnected() }
            return
        }
        Log.i(TAG, "CXR_DISCONNECTED overlay=${overlayRoot != null}")
        hidePanel()
        // The CXR session is over: end the transparent scene host too, so it cannot outlive the session it exists for
        // and the next `appStart` starts a fresh scene (Task 21 fix).
        GlassesActivity.finishSceneHost()
        Log.i(TAG, "CXR_DISCONNECTED service_stop_requested=true")
        stopSelf()
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
        if (command is HintCommand.PanelOpen) {
            onPanelOpen(command)
            return true
        }
        if (command is HintCommand.Close) {
            onClose(command)
            return true
        }
        val accepted = host.accept(json)
        if (accepted) maybeSendUiReady()
        return accepted
    }

    /**
     * Task 11 `PANEL_OPEN`: show the hidden panel, with a cache reset only on hidden→visible.
     *
     * A duplicate open for an already-visible panel keeps the official no-reset behaviour - the official
     * `showAiDialog` only calls `setAiItemInfo` for an `isShowing()` dialog and never `resetDialogUiState`. EDITH adds
     * one idempotent fact: for the **same route** it re-sends `PANEL_SHOWN` with the accepted identity. `PANEL_SHOWN`
     * is EDITH's correlation/liveness event, and the phone can have missed the original one (or cleared its visible
     * flag after the readiness grace); without the re-announce, that recovery path would wait for a `PANEL_SHOWN` that
     * the official no-op can never produce. No content, cache or revision is touched, and a `PANEL_OPEN` for another
     * route keeps the plain no-op because that route is not the one on screen.
     */
    private fun onPanelOpen(command: HintCommand.PanelOpen) {
        val identity = host.currentIdentity()
        if (identity == null || identity.first != command.sessionId || identity.second != command.generation) {
            Log.i(TAG, "PANEL_OPEN refused: session or generation mismatch")
            return
        }
        if (overlayRoot != null) {
            val sameRoute = visibleRoute == command.route
            Log.i(TAG, "PANEL_OPEN already-visible route=${command.route.name} reset=false reannounce=$sameRoute")
            if (sameRoute) sendPanelShown(command.route)
            return
        }
        Log.i(TAG, "PANEL_OPEN accepted session=${command.sessionId} route=${command.route.name} reset=hidden-to-visible")
        host.resetForOpen()
        showPanel(command.route)
    }

    /**
     * Task 11 `CLOSE`, route-scoped: only the visible route's close hides the window.
     *
     * While hidden, any valid close still clears the cache - that is the Task 9 I1 rule and it is independent of window
     * presence. `route == null` is the official ALL form and closes the visible panel.
     */
    private fun onClose(command: HintCommand.Close) {
        val identity = host.currentIdentity()
        if (identity == null || identity.first != command.sessionId || identity.second != command.generation) {
            Log.i(TAG, "CLOSE refused: session or generation mismatch")
            return
        }
        val visible = visibleRoute
        val closeRoute = command.route
        if (visible != null && closeRoute != null && closeRoute != visible) {
            Log.i(TAG, "CLOSE ignored route=${closeRoute.name} visible=${visible.name}")
            return
        }
        Log.i(TAG, "CLOSE accepted session=${command.sessionId} route=${closeRoute?.name ?: "ALL"} " +
            "visible=${visible?.name ?: "none"}")
        hidePanel()
    }

    // ---------------------------------------------------------------- outbound UI events

    /**
     * Sends `UI_READY` once per accepted session identity.
     *
     * Before the first HELLO there is nothing to name, so the send is deferred (and logged). A repeated HELLO for the
     * identity already announced is not a new session and produces no duplicate. A failed send is not recorded as
     * announced, so the next accepted command retries it. A new accepted identity always sends a new event, which is
     * what lets the phone re-establish readiness after its own restart while this resident service kept running.
     */
    private fun maybeSendUiReady() {
        val identity = host.currentIdentity() ?: run {
            Log.i(TAG, "UI_READY deferred: no session identity yet")
            return
        }
        if (identity == lastUiReadyIdentity) {
            flushPendingPanelShown()
            return
        }
        val sent = send(HintCommand.UiReady(identity.first, identity.second))
        if (sent) {
            lastUiReadyIdentity = identity
            Log.i(TAG, "UI_READY session=${identity.first} generation=${identity.second} sent=true")
            flushPendingPanelShown()
        } else {
            Log.i(TAG, "UI_READY session=${identity.first} generation=${identity.second} sent=false (will retry)")
        }
    }

    /** Sends a `PANEL_SHOWN` that was deferred until an accepted identity existed. */
    private fun flushPendingPanelShown() {
        val pending = pendingPanelShownRoute ?: return
        pendingPanelShownRoute = null
        sendPanelShown(pending)
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
        val timeoutMs = WAKE_LOCK_TIMEOUT_MS
        val lock = powerManager.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "edith:overlay",
        ).apply {
            setReferenceCounted(false)
            acquire(timeoutMs)
        }
        wakeLock = lock
        wakeLockTimeoutAppliedMs = timeoutMs
        Log.i(TAG, "WAKE_LOCK acquire held=${lock.isHeld} interactive=${powerManager.isInteractive} " +
            "timeoutMs=$timeoutMs")
    }

    private fun releaseWakeLock() {
        val lock = wakeLock ?: return
        wakeLock = null
        wakeLockTimeoutAppliedMs = 0L
        runCatching { if (lock.isHeld) lock.release() }
        Log.i(TAG, "WAKE_LOCK release held=${lock.isHeld}")
    }

    // ---------------------------------------------------------------- test seams (production entry points)

    /** Test-only CXR sink, the service-side mirror of the Activity's `setStopSenderForProbe`. */
    fun setSenderForProbe(sender: ((String) -> Boolean)?) {
        senderForProbe = sender
    }

    /** The host's one outbound seam for the control commands (STOP/PIN); test-only. */
    fun setStopSenderForProbe(sender: ((String) -> Boolean)?) {
        host.setStopSenderForProbe(sender)
    }

    fun overlayShownForProbe(): Boolean = overlayRoot != null

    fun overlayViewForProbe(): View? = overlayRoot

    fun overlayParamsForProbe(): WindowManager.LayoutParams? = overlayParams

    fun backdropShownForProbe(): Boolean = backdropRoot != null

    fun overlayTypeForProbe(): Int? = overlayParams?.type

    fun accessibilityOverlayForProbe(): Boolean = overlayParams?.type ==
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY

    fun wakeLockHeldForProbe(): Boolean = wakeLock?.isHeld == true

    /**
     * The platform's own acquired lock description (`WakeLock{… held/refCount}`), for the S5 wake-lock check.
     *
     * This Android 12 build's `toString()` does not print the flags, so the flag half is read back from the platform
     * object's own field by [wakeLockFlagsForProbe] and the hold half is the value the acquire call was given
     * ([wakeLockTimeoutAppliedForProbe]) - both are facts about the object this service actually asked the platform
     * for, not claims about a constant.
     */
    fun wakeLockDescriptionForProbe(): String? = wakeLock?.toString()

    /** The acquired lock's platform flags (`SCREEN_BRIGHT_WAKE_LOCK | ACQUIRE_CAUSES_WAKEUP` = 0x1000000a). */
    fun wakeLockFlagsForProbe(): Int = readWakeLockField("mFlags", -1)

    /**
     * The timeout (ms) this service actually passed to `PowerManager.WakeLock.acquire`, or 0 while not held.
     *
     * The client-side `WakeLock` does not retain the timeout - it is sent to the power service and this Android 12
     * build's `toString()` only prints `held/refCount` - so the honest checkable fact on this side is the value the
     * acquire call was given. The flag half is read back from the platform object above.
     */
    fun wakeLockTimeoutAppliedForProbe(): Long = if (wakeLock?.isHeld == true) wakeLockTimeoutAppliedMs else 0L

    private fun <T> readWakeLockField(name: String, fallback: T): T {
        val lock = wakeLock ?: return fallback
        return runCatching {
            @Suppress("UNCHECKED_CAST")
            PowerManager.WakeLock::class.java.getDeclaredField(name)
                .apply { isAccessible = true }
                .get(lock) as T
        }.getOrDefault(fallback)
    }

    /**
     * Drives the exact production CXR-disconnect path (`onCxrDisconnected`) from the instrumentation suite.
     *
     * The real callback can only come from a live CXR-S bridge; this seam exercises the same method the bridge's
     * `onDisconnected` installs, so the hide/stop behaviour is proved on the production service without pretending the
     * real link was exercised (that stays `NotMeasured` until the Task 21 walkthrough).
     */
    fun onCxrDisconnectedForProbe() = onCxrDisconnected()

    fun overlayHasWindowFocusForProbe(): Boolean = overlayRoot?.hasWindowFocus() == true

    fun isPinnedForProbe(): Boolean = host.isPinnedForProbe()

    fun listeningVisibleForProbe(): Boolean = host.listeningVisibleForProbe()

    fun hiVisibleForProbe(): Boolean = host.hiVisibleForProbe()

    fun pinTextVisibleForProbe(): Boolean = host.pinTextVisibleForProbe()

    fun pinIconVisibleForProbe(): Boolean = host.pinIconVisibleForProbe()

    fun tipsTextForProbe(): String = host.tipsTextForProbe()

    fun lastBackActionForProbe(): String? = host.lastBackActionForProbe()

    /** Drives the production overlay's key router directly (the same path as the focused window's dispatch). */
    fun dispatchKeyForProbe(event: KeyEvent): Boolean = host.dispatchKeyEvent(event)

    /**
     * Task 9 review I4: read the platform's own record instead of a service field.
     *
     * `getRunningServices` is deprecated but still returns this app's services (and only this app's) with the
     * `foreground` flag, which is the system's answer to "is this service foreground", not ours.
     */
    @Suppress("DEPRECATION")
    fun serviceForegroundForProbe(): Boolean {
        val manager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val self = ComponentName(this, GlassesOverlayService::class.java)
        return manager.getRunningServices(Int.MAX_VALUE).any { it.service == self && it.foreground }
    }

    /** True when the platform actually holds the `FOREGROUND_ID` notification for this app. */
    fun activeForegroundNotificationForProbe(): Boolean {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        return manager.activeNotifications.any { it.packageName == packageName && it.id == FOREGROUND_ID }
    }

    /** The posted channel's importance, read back from the platform; -1 means the channel is missing. */
    fun foregroundChannelImportanceForProbe(): Int {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        return manager.getNotificationChannel(CHANNEL_ID)?.importance ?: -1
    }

    fun hintTextForProbe(): String = host.hintText()

    fun conversationTextForProbe(route: AssistantRoute): String = host.conversationText(route)

    fun contentRevisionForProbe(): Long = host.contentRevisionForProbe()

    companion object {
        private const val TAG = "EdithGlasses"
        private const val CHANNEL_ID = "edith-overlay"
        private const val FOREGROUND_ID = 1
        private const val WINDOW_TITLE = "EDITH-PANEL"
        private const val BACKDROP_TITLE = "EDITH-BACKDROP"

        /** The 480×640 canvas; the official panel occupies its bottom 380 px. */
        private const val WIDTH_PX = 480
        private const val HEIGHT_PX = 380

        /** Same local max hold as the S1 probe; the phone's screen-off path needs the display on, not forever. */
        private const val WAKE_LOCK_TIMEOUT_MS = 10 * 60 * 1000L

        /**
         * Test-only: the live service, so the scene-host test can prove that launching the default Activity started the
         * service (the alternative would have been to infer it from a log line). Cleared in [onDestroy].
         */
        @Volatile
        internal var instanceForTest: GlassesOverlayService? = null
    }
}
