package com.sheen.rokid.phone.overlay

import android.app.AppOpsManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.WindowInsets
import android.view.WindowManager
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.sheen.rokid.contracts.AssistantRoute
import com.sheen.rokid.phone.R
import com.sheen.rokid.phone.app.EdithApplication
import com.sheen.rokid.phone.app.HostCompositionRoot
import com.sheen.rokid.phone.app.MainActivity
import com.sheen.rokid.phone.audio.DictationStartResult
import com.sheen.rokid.phone.audio.DictationState
import com.sheen.rokid.phone.audio.DictationTarget
import com.sheen.rokid.phone.platform.P3Log
import com.sheen.rokid.phone.ui.dictationStartStatusText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * 0.5 (`D210`/`D214`): the phone-only mode's floating ball and message bubbles, as two overlay windows.
 *
 * Why a foreground service: the ball must outlive the App being backgrounded (it is the phone-only interaction surface,
 * and the owner's requirement is that it appears on the desktop as soon as the mode is on). The service owns no session
 * - `SessionService` remains the session host - it only draws and translates touches into production host calls. The
 * notification is the platform's price for a persistent overlay; it is low importance and silent.
 *
 * Two windows, deliberately:
 * - a small **touchable** ball window (`TYPE_APPLICATION_OVERLAY`, `FLAG_LAYOUT_NO_LIMITS`, about 3.6 ball radii so the
 *   thinking light ring fits). `FLAG_LAYOUT_NO_LIMITS` is what lets a docked ball centre sit on the screen edge and be
 *   half hidden;
 * - a full-screen **non-touchable** bubble window. It never blocks an app below it, and drawing the stack in a
 *   full-screen `Box` avoids measuring a wrap-content window on every message.
 *
 * Compose in a service has no Activity lifecycle owner; [OverlayLifecycleOwner] supplies one. Every state the UI needs is
 * a `StateFlow` owned here; the repository/model keep the timing rules, so the service stays a thin adapter.
 *
 * Lifecycle: started/stopped only by [sync] when the mode is on and the overlay permission is granted; `START_NOT_STICKY`
 * (a killed process does not resurrect a ball in the background); a mode-off or permission loss stops it immediately.
 *
 * 0.5.1 (`C12`/`C14`/`C15`): the live permission is watched with `AppOpsManager.startWatchingMode(SYSTEM_ALERT_WINDOW)`
 * so a **runtime revoke** removes the windows, drops the notification and stops the service (unregistered in
 * [onDestroy]); the service publishes [isRunning]/[startFailure] for the settings page instead of letting the page infer
 * 「已启用」 from `Settings.canDrawOverlays`; a start failure (owner install, window add, foreground) is published for a
 * visible retry rather than only logged; and teardown cancels the BALL dictation (no final) and clears the bubble stack
 * so a re-enabled overlay never replays old messages.
 */
class PhoneOnlyOverlayService : Service() {

    private val app get() = application as EdithApplication
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var root: HostCompositionRoot? = null
    private var windowManager: WindowManager? = null
    private var lifecycleOwner: OverlayLifecycleOwner? = null
    private var ballView: ComposeView? = null
    private var bubbleView: ComposeView? = null
    private var ballParams: WindowManager.LayoutParams? = null

    private val position = MutableStateFlow<BallPosition?>(null)
    private val micVisible = MutableStateFlow(false)
    private val listening = MutableStateFlow(false)
    private val halo = MutableStateFlow(BallHalo.NONE)
    private val speaking = MutableStateFlow(false)

    private var radiusPx = 0f
    private var windowSizePx = 0
    private var screenWidthPx = 0
    private var screenHeightPx = 0

    /** `BubbleLayout.GAP_DP` at the display density; the one gap shared by the bubble anchor and the ball reserve. */
    private var gapPx = 0f
    private var topInsetPx = 0f
    private var bottomInsetPx = 0f

    /**
     * 0.5.1 C3: whether the current long press really acquired the BALL lease. Only that press may stop it on release;
     * a refused press must leave the composer's live lease alone. Also decides the microphone indicator.
     */
    private val ballPress = BallPressLease()
    private var moveJob: Job? = null
    private var foregroundStarted = false

    /** 0.5.1 C12: the live overlay-permission watcher; registered when the overlay starts, unregistered in [onDestroy]. */
    private var appOps: AppOpsManager? = null
    private var overlayWatcher: AppOpsManager.OnOpChangedListener? = null

    /** One service instance performs its start once, in the first [onStartCommand]. */
    private var startAttempted = false

    /**
     * 0.5.1 C15: true only once this instance published `state=on`. A refused or failed instance must not log
     * `state=off` on destroy - that line means "the running overlay stopped", not "a start was refused".
     */
    private var started = false

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * 0.5.1 (`C15`): the start work runs in [onStartCommand], not `onCreate`, because this is the only place a `Service`
     * receives its start intent - and that intent carries [sync]'s **explicit** requested value. The 0.5 half-fix taught
     * `sync` to use the requested value, but the service still re-read the live gate in `onCreate`: the detach gate
     * invokes its callback before it publishes the new value, so on the off -> on transition the service could see
     * `false` and stop the ball the user had just asked for. The live gate stays the fallback for a start that did not
     * come through sync. `onStartCommand` runs before the foreground-service deadline, so promoting here is safe.
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!startAttempted) {
            startAttempted = true
            startOverlay(intent)
        }
        return START_NOT_STICKY
    }

    private fun startOverlay(intent: Intent?) {
        val composition = app.liveComposition
        if (composition == null) {
            refuseStart(PhoneOnlyStartStopPolicy.Reason.NO_COMPOSITION)
            return
        }
        val explicitlyRequested = intent?.getBooleanExtra(EXTRA_REQUESTED_ENABLED, false) == true
        if (!explicitlyRequested && !composition.detachGate.enabled.value) {
            refuseStart(PhoneOnlyStartStopPolicy.Reason.MODE_OFF)
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            refuseStart(PhoneOnlyStartStopPolicy.Reason.NO_OVERLAY_PERMISSION)
            return
        }
        root = composition
        if (!startForegroundNotification()) {
            failStart(PhoneOnlyStartFailure.FOREGROUND)
            return
        }
        // 0.5.1 C12: a revoke after this point stops the overlay immediately, without waiting for a resume.
        registerOverlayWatcher()
        windowManager = getSystemService(WindowManager::class.java)
        measure()
        if (!addWindows(composition)) return
        collectState(composition)
        started = true
        PhoneOnlyOverlayRuntime.running()
        P3Log.line("P5_PHONE_ONLY state=on")
    }

    override fun onDestroy() {
        // 0.5.1 C12: unregister first, so a callback racing the teardown cannot touch removed windows.
        unregisterOverlayWatcher()
        // 0.5.1 C3/T8 handoff: a service teardown owns the BALL lease this press acquired, so it ends that lease and
        // never the composer's. It must **cancel**, not stop: a stop forces the turn's final and `onDictationFinal(BALL)`
        // sends it through `requestTypedMessage`, so a shutdown would publish a user turn the wearer never finished.
        // A refused press has nothing to stop and must not touch the composer's lease.
        if (ballPress.end()) root?.cancelDictation(DictationTarget.BALL)
        // 0.5.1 C15: drop the stack after the dictation is cancelled (so no final message is published); a re-enabled
        // overlay must not replay the previous instance's bubbles.
        root?.phoneBubble?.clear()
        PhoneOnlyOverlayRuntime.stopped()
        scope.cancel()
        removeOverlayViews()
        lifecycleOwner?.let {
            it.onPause(); it.onStop(); it.onDestroy()
        }
        lifecycleOwner = null
        if (foregroundStarted) {
            runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
            foregroundStarted = false
        }
        // 0.5.1 C15: `state=off` means "the running overlay stopped". A refused/failed start already logged its own
        // line and must not masquerade as a normal stop.
        if (started) P3Log.line("P5_PHONE_ONLY state=off")
        super.onDestroy()
    }

    // ---- lifecycle of the start decision -------------------------------------------------------------------------

    /** 0.5.1 C15: the service was asked to run but cannot; this is a refusal, not a stop of a running overlay. */
    private fun refuseStart(reason: PhoneOnlyStartStopPolicy.Reason) {
        P3Log.line("P5_PHONE_ONLY state=refused reason=$reason")
        PhoneOnlyOverlayRuntime.stopped()
        stopSelf()
    }

    /**
     * 0.5.1 C12/C14: this instance cannot reach `state=on`. The reason is published for the settings page (visible
     * with a retry) instead of only being written to logcat.
     */
    private fun failStart(failure: PhoneOnlyStartFailure, detail: String? = null) {
        val suffix = if (detail == null) "" else " reason=$detail"
        P3Log.line("P5_PHONE_ONLY state=failed start_failure=$failure$suffix")
        PhoneOnlyOverlayRuntime.failed(failure)
        stopSelf()
    }

    // ---- overlay permission watcher ------------------------------------------------------------------------------

    /**
     * 0.5.1 C12: watch `SYSTEM_ALERT_WINDOW` for **this package** while the overlay runs.
     *
     * `AppOpsManager.startWatchingMode` is the platform's runtime-revoke channel for a special app-op: without it the
     * ball would stay on screen (and the foreground notification would keep claiming it is running) until the next
     * resume. The listener re-reads `Settings.canDrawOverlays`, because the same watcher also fires for a re-grant.
     */
    private fun registerOverlayWatcher() {
        val manager = getSystemService(AppOpsManager::class.java)
        if (manager == null) {
            P3Log.line("P5_PHONE_ONLY state=watcher_failed reason=NO_APP_OPS")
            return
        }
        val listener = AppOpsManager.OnOpChangedListener { op, changedPackage ->
            // AppOpsManager invokes its watcher from a Binder thread; the window removal and foreground teardown must
            // run on the service's main thread (and the scope is cancelled on destroy, so a late callback is dropped).
            scope.launch {
                val change = PhoneOnlyStartStopPolicy.onAppOpChanged(
                    op = op,
                    changedPackage = changedPackage,
                    packageName = packageName,
                    permissionGranted = Settings.canDrawOverlays(this@PhoneOnlyOverlayService),
                )
                if (change == PhoneOnlyStartStopPolicy.AppOpChange.STOP) stopForPermissionLoss()
            }
        }
        val watching = runCatching {
            manager.startWatchingMode(AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW, packageName, listener)
        }.isSuccess
        if (!watching) {
            // The overlay still runs; only the runtime-revoke half is unavailable. T12 records the real K40 callback.
            P3Log.line("P5_PHONE_ONLY state=watcher_failed reason=START_WATCHING")
            return
        }
        appOps = manager
        overlayWatcher = listener
    }

    private fun unregisterOverlayWatcher() {
        val manager = appOps
        val listener = overlayWatcher
        appOps = null
        overlayWatcher = null
        if (manager != null && listener != null) {
            runCatching { manager.stopWatchingMode(listener) }
        }
    }

    /**
     * 0.5.1 C12: the user revoked 「显示在其他应用上层」 while the overlay was running.
     *
     * Remove the windows and the notification now, and stop the service; [onDestroy] unregisters the watcher and logs
     * the running state's `state=off`. The settings page sees `PermissionMissing` (the grant is the action it needs).
     */
    private fun stopForPermissionLoss() {
        P3Log.line("P5_PHONE_ONLY state=revoked reason=PERMISSION_REVOKED")
        PhoneOnlyOverlayRuntime.stopped()
        removeOverlayViews()
        if (foregroundStarted) {
            runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
            foregroundStarted = false
        }
        stopSelf()
    }

    /** Removes both overlay windows; idempotent so revoke, a half-added window and `onDestroy` can all call it. */
    private fun removeOverlayViews() {
        ballView?.let { runCatching { windowManager?.removeView(it) } }
        bubbleView?.let { runCatching { windowManager?.removeView(it) } }
        ballView = null
        bubbleView = null
        ballParams = null
    }

    // ---- window setup -------------------------------------------------------------------------------------------

    private fun measure() {
        val metrics = resources.displayMetrics
        screenWidthPx = metrics.widthPixels
        screenHeightPx = metrics.heightPixels
        val density = metrics.density
        radiusPx = BALL_DIAMETER_DP / 2f * density
        windowSizePx = (radiusPx * WINDOW_FACTOR).roundToInt()
        gapPx = BubbleLayout.GAP_DP * density
        val insets = readSystemBarInsets()
        topInsetPx = insets.first
        bottomInsetPx = insets.second
        position.value = BallPlacement.initial(
            screenWidthPx, screenHeightPx, radiusPx, gapPx, topInsetPx, bottomInsetPx,
        )
    }

    /**
     * 0.5.1 (`C15`): the live status-bar/navigation-bar insets, replacing the 0.5 build's 48 dp / 96 dp constants.
     *
     * `WindowMetrics` is the platform source on API 30+. A service context has no window of its own, so a vendor build
     * can refuse or zero the metrics; the system's own `status_bar_height`/`navigation_bar_height` resources are the
     * fallback (still live platform values, and the only source below API 30). T12 records the actual K40 values.
     */
    private fun readSystemBarInsets(): Pair<Float, Float> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val metrics = runCatching {
                (getSystemService(WINDOW_SERVICE) as? WindowManager)?.currentWindowMetrics
            }.getOrNull()
            val bars = metrics?.windowInsets?.getInsets(
                WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars(),
            )
            if (bars != null && (bars.top > 0 || bars.bottom > 0)) {
                return bars.top.toFloat() to bars.bottom.toFloat()
            }
        }
        return systemDimenPx("status_bar_height") to systemDimenPx("navigation_bar_height")
    }

    /** One of Android's own navigation/status bar dimensions in pixels; zero when the platform does not define it. */
    private fun systemDimenPx(name: String): Float {
        val id = resources.getIdentifier(name, "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id).toFloat() else 0f
    }

    /**
     * 0.5.1 (`C14`): installs the ViewTree owners a `ComposeView` in a Service needs, and reports success.
     *
     * Both owners stay reflective. `C14` asks for a direct import "where possible"; a direct
     * `import androidx.lifecycle.ViewTreeLifecycleOwner` was tried and does **not** compile in this graph - the Kotlin
     * compile classpath exposes the lifecycle KMP metadata artifact, not the Android AAR's `androidMain` class (recorded
     * in `DECISIONS` §21.1 / `CONTRACTS` §8). The install is no longer best-effort, though: a failure means Compose
     * cannot attach, so it is returned and surfaced as a visible [PhoneOnlyStartFailure.LIFECYCLE_OWNER] start failure
     * (the 0.5 build only logged it).
     */
    private fun ComposeView.installLifecycle(owner: OverlayLifecycleOwner): Boolean {
        val lifecycleOwnerSet = runCatching {
            val cls = Class.forName("androidx.lifecycle.ViewTreeLifecycleOwner")
            cls.getMethod(
                "set",
                android.view.View::class.java,
                androidx.lifecycle.LifecycleOwner::class.java,
            ).invoke(null, this, owner)
        }.onFailure {
            P3Log.line("P5_PHONE_ONLY state=viewtree_lifecycle_failed reason=${it.javaClass.simpleName}")
        }.isSuccess
        // Compose refuses to attach unless the view also propagates a SavedStateRegistryOwner; the overlay's content
        // uses no `rememberSaveable`, but AndroidComposeView checks the owner unconditionally on attach.
        val savedStateOwnerSet = runCatching {
            val cls = Class.forName("androidx.savedstate.ViewTreeSavedStateRegistryOwner")
            cls.getMethod(
                "set",
                android.view.View::class.java,
                androidx.savedstate.SavedStateRegistryOwner::class.java,
            ).invoke(null, this, owner)
        }.onFailure {
            P3Log.line("P5_PHONE_ONLY state=viewtree_savedstate_failed reason=${it.javaClass.simpleName}")
        }.isSuccess
        return lifecycleOwnerSet && savedStateOwnerSet
    }

    /** Adds the bubble and ball windows; false means this instance cannot run and has published its failure. */
    private fun addWindows(composition: HostCompositionRoot): Boolean {
        val manager = windowManager
        if (manager == null) {
            failStart(PhoneOnlyStartFailure.WINDOW, detail = "NO_WINDOW_MANAGER")
            return false
        }
        val owner = OverlayLifecycleOwner().also {
            it.onCreate(); it.onStart(); it.onResume()
        }
        lifecycleOwner = owner

        val bubble = ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            isFocusable = false
            importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
            setContent {
                val appearance by composition.phoneOnlyAppearance.collectAsState()
                val pos by position.collectAsState()
                val speakingState by speaking.collectAsState()
                MaterialTheme {
                    pos?.let { current ->
                        PhoneOnlyBubbleContent(
                            repository = composition.phoneBubble,
                            appearance = appearance,
                            ballEdge = current.edge,
                            ballCenterX = current.centerX,
                            ballCenterY = current.centerY,
                            radiusPx = radiusPx,
                            screenWidthPx = screenWidthPx,
                            screenHeightPx = screenHeightPx,
                            speaking = speakingState,
                        )
                    }
                }
            }
        }
        // C14: a missing ViewTree owner means Compose cannot attach; surface it as a visible start failure instead of
        // letting `addView` fail later with an opaque IllegalStateException.
        if (!bubble.installLifecycle(owner)) {
            failStart(PhoneOnlyStartFailure.LIFECYCLE_OWNER)
            return false
        }
        val bubbleParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }

        val ball = ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            isFocusable = false
            setContent {
                val appearance by composition.phoneOnlyAppearance.collectAsState()
                val mic by micVisible.collectAsState()
                val listeningState by listening.collectAsState()
                val haloState by halo.collectAsState()
                MaterialTheme {
                    PhoneOnlyBallContent(
                        diameterDp = BALL_DIAMETER_DP,
                        opacity = appearance.ballAlpha,
                        micVisible = mic,
                        listening = listeningState,
                        halo = haloState,
                        onAction = ::onBallAction,
                    )
                }
            }
        }
        if (!ball.installLifecycle(owner)) {
            failStart(PhoneOnlyStartFailure.LIFECYCLE_OWNER)
            return false
        }
        val params = WindowManager.LayoutParams(
            windowSizePx,
            windowSizePx,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }

        return try {
            manager.addView(bubble, bubbleParams)
            bubbleView = bubble
            manager.addView(ball, params)
            ballView = ball
            ballParams = params
            updateBallWindow()
            true
        } catch (failure: Exception) {
            // A half-added pair must not stay on screen (the bubble may already be attached when the ball is refused).
            removeOverlayViews()
            failStart(PhoneOnlyStartFailure.WINDOW, detail = failure.javaClass.simpleName)
            false
        }
    }

    private fun updateBallWindow() {
        val manager = windowManager ?: return
        val view = ballView ?: return
        val params = ballParams ?: return
        val current = position.value ?: return
        params.x = (current.centerX - windowSizePx / 2f).roundToInt()
        params.y = (current.centerY - windowSizePx / 2f).roundToInt()
        runCatching { manager.updateViewLayout(view, params) }
    }

    // ---- state --------------------------------------------------------------------------------------------------

    private fun collectState(composition: HostCompositionRoot) {
        scope.launch {
            // 0.5.1 C15: skip the collector's initial value. On the off -> on transition the explicit start can reach
            // here while the gate still holds its old `false` (it publishes after its mode-changed callback); treating
            // that first emission as "mode off" would stop the ball the user just enabled. A later false is a real
            // mode-off transition and stops the overlay.
            composition.detachGate.enabled.drop(1).collect { enabled ->
                if (!enabled) stopSelf()
            }
        }
        scope.launch {
            composition.dictationUi.collect { ui ->
                val ownsBall = ui.owner == DictationTarget.BALL
                listening.value = ownsBall && ui.state == DictationState.Listening
                // 0.5.1 C3: only a BALL listening lease (or the accepted press that owns it) shows the microphone. A
                // refused press must not, and neither must the composer's listening state.
                micVisible.value = ballPress.micVisible(listening.value)
            }
        }
        scope.launch {
            composition.ballDictationNotice.collect { notice ->
                if (notice != null) {
                    composition.phoneBubble.onEvent(PhoneBubbleEvent.Notice(AssistantRoute.WAKE, notice))
                    composition.clearBallDictationNotice()
                }
            }
        }
        scope.launch {
            while (isActive) {
                halo.value = when {
                    composition.phoneBubble.isAgentWorking() -> BallHalo.WORKING
                    composition.phoneBubble.isAnswering() && composition.speechEnabled.value -> BallHalo.SPEAKING
                    else -> BallHalo.NONE
                }
                speaking.value = composition.phoneBubble.isAnswering() && composition.speechEnabled.value
                delay(HALO_POLL_MILLIS)
            }
        }
    }

    // ---- gestures -----------------------------------------------------------------------------------------------

    private fun onBallAction(action: BallGestureAction) {
        when (action) {
            BallGestureAction.Tap -> P3Log.line("P5_BALL event=tap")
            is BallGestureAction.Drag -> dragBy(action.dx, action.dy)
            is BallGestureAction.DragTo -> dragTo(action.x, action.y)
            BallGestureAction.Snap -> snap()
            BallGestureAction.LongPressStart -> beginLongPress()
            BallGestureAction.LongPressEnd -> endLongPress()
        }
    }

    private fun dragTo(screenX: Float, screenY: Float) {
        moveJob?.cancel()
        val current = position.value ?: return
        val (x, y) = BallPlacement.clampDragCenter(
            screenX, screenY,
            screenWidthPx, screenHeightPx, radiusPx, gapPx, topInsetPx, bottomInsetPx,
        )
        position.value = current.copy(
            centerX = x,
            centerY = y,
            edge = BallPlacement.nearestEdge(x, screenWidthPx),
        )
        updateBallWindow()
        P3Log.line("P5_BALL event=drag x=${x.roundToInt()} y=${y.roundToInt()}")
    }

    private fun dragBy(dx: Float, dy: Float) {
        moveJob?.cancel()
        val current = position.value ?: return
        val (x, y) = BallPlacement.clampDragCenter(
            current.centerX + dx, current.centerY + dy,
            screenWidthPx, screenHeightPx, radiusPx, gapPx, topInsetPx, bottomInsetPx,
        )
        position.value = current.copy(
            centerX = x,
            centerY = y,
            edge = BallPlacement.nearestEdge(x, screenWidthPx),
        )
        updateBallWindow()
        P3Log.line("P5_BALL event=drag x=${x.roundToInt()} y=${y.roundToInt()}")
    }

    private fun snap() {
        val current = position.value ?: return
        val target = BallPlacement.snapAfterDrag(
            current.centerX, current.centerY,
            screenWidthPx, screenHeightPx, radiusPx, gapPx, topInsetPx, bottomInsetPx,
            snapThresholdPx = SNAP_THRESHOLD_DP * resources.displayMetrics.density,
        )
        animateTo(target, BallPlacement.SNAP_DURATION_MILLIS)
        P3Log.line("P5_BALL event=snap edge=${target.edge} x=${target.centerX.roundToInt()} y=${target.centerY.roundToInt()}")
    }

    private fun beginLongPress() {
        val composition = root ?: return
        val current = position.value ?: return
        val target = current.copy(
            centerX = BallPlacement.visibleCenterX(current.edge, screenWidthPx, radiusPx, 8f * resources.displayMetrics.density),
        )
        animateTo(target, PRESS_MILLIS)
        P3Log.line("P5_BALL event=long_press edge=${current.edge}")
        // 0.5.1 C4: a long press is the wearer taking the microphone back. If an answer is still speaking, stop it and
        // drop its echo window before asking for the BALL lease; otherwise the answer's own voice and the half-duplex
        // filter would swallow the manual dictation that is now the phone-only model's next-sentence entry point.
        composition.stopCurrentSpeechForBall()
        val result = composition.startDictation(DictationTarget.BALL)
        // 0.5.1 C3 (`review Important #2`): record whether this press really acquired the lease. The microphone is only
        // shown for an accepted press (or a live BALL lease); and only an accepted press may stop the lease on release.
        // A refused press - most importantly `busy` while the composer is listening - owns nothing.
        ballPress.begin(result)
        micVisible.value = ballPress.micVisible(listening.value)
        if (result != DictationStartResult.Accepted) {
            val copy = ballDictationRefusalNotice(result)
            composition.phoneBubble.onEvent(PhoneBubbleEvent.Notice(AssistantRoute.WAKE, copy))
            P3Log.line("P5_BALL event=dictation_refused reason=$result")
        }
    }

    private fun endLongPress() {
        val composition = root ?: return
        // 0.5.1 C3: release stops the BALL lease only if this press acquired it; an owner-aware stop for a lease the
        // ball never held is a no-op by contract, but the overlay must not even ask. A refused press therefore leaves
        // the composer's live lease, its final and the draft alone.
        if (ballPress.end()) composition.stopDictation(DictationTarget.BALL)
        micVisible.value = ballPress.micVisible(listening.value)
        snap()
        P3Log.line("P5_BALL event=long_press_end")
    }

    private fun animateTo(target: BallPosition, durationMillis: Long) {
        moveJob?.cancel()
        val from = position.value ?: return
        moveJob = scope.launch {
            val steps = (durationMillis / 16L).coerceAtLeast(1L)
            for (i in 1..steps) {
                val t = (i.toFloat() / steps).coerceIn(0f, 1f)
                val eased = 1f - (1f - t) * (1f - t)
                position.value = BallPosition(
                    edge = target.edge,
                    centerX = from.centerX + (target.centerX - from.centerX) * eased,
                    centerY = from.centerY + (target.centerY - from.centerY) * eased,
                )
                updateBallWindow()
                delay(16L)
            }
            position.value = target
            updateBallWindow()
        }
    }

    // ---- foreground ---------------------------------------------------------------------------------------------

    private fun startForegroundNotification(): Boolean {
        val manager = getSystemService(NotificationManager::class.java) ?: return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "纯手机模式悬浮球",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    setShowBadge(false)
                    enableVibration(false)
                },
            )
        }
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            // 0.5.1 C15: an app-owned monochrome icon instead of `android.R.drawable` (OEM framework icons vary, and the
            // platform renders this as an alpha mask).
            .setSmallIcon(R.drawable.ic_edith_notification)
            .setContentTitle("纯手机模式")
            .setContentText("EDITH 悬浮球正在运行")
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        return try {
            if (Build.VERSION.SDK_INT >= 34) {
                ServiceCompat.startForeground(
                    this, NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            foregroundStarted = true
            true
        } catch (failure: Exception) {
            P3Log.line("P5_PHONE_ONLY state=foreground_failed reason=${failure.javaClass.simpleName}")
            // The type can be rejected on some vendor builds; a service without a type is the last honest attempt (on
            // API 34+ a missing type is itself an error, so this path is for the 28-33 range).
            runCatching { startForeground(NOTIFICATION_ID, notification) }
                .onSuccess { foregroundStarted = true }
                .isSuccess
        }
    }

    companion object {
        private const val CHANNEL_ID = "phone_only_overlay"
        private const val NOTIFICATION_ID = 0x0E11
        private const val BALL_DIAMETER_DP = 56
        private const val SNAP_THRESHOLD_DP = 24f
        private const val WINDOW_FACTOR = 3.6f
        private const val HALO_POLL_MILLIS = 250L
        private const val PRESS_MILLIS = 120L

        /**
         * 0.5.1 C15: the explicit requested value `sync` hands to the service.
         *
         * Without it the service re-read the live gate when it started. The detach gate invokes its mode-changed
         * callback **before** it publishes the new `enabled` value, so on the off -> on transition the service could
         * still see `false` and stop the ball the user had just asked for.
         */
        private const val EXTRA_REQUESTED_ENABLED = "com.sheen.rokid.phone.overlay.EXTRA_REQUESTED_ENABLED"

        /**
         * 0.5.1 C12/C14: the service's real run state, for the settings page.
         *
         * Only this service publishes it; the settings page reads it instead of inferring 「已启用」 from
         * `Settings.canDrawOverlays`.
         */
        internal val isRunning: StateFlow<Boolean> get() = PhoneOnlyOverlayRuntime.isRunning
        internal val startFailure: StateFlow<PhoneOnlyStartFailure?> get() = PhoneOnlyOverlayRuntime.startFailure

        /**
         * Starts, stops or leaves the overlay running to match the mode, the permission and the service's real state.
         *
         * [requestedEnabled] is the mode transition's **explicit** value; null means the caller only has the live gate
         * (`MainActivity.onResume`). The decision itself lives in [PhoneOnlyStartStopPolicy] (JVM-tested); this function
         * is the adapter that performs it, and its log distinguishes a **refused** requested start from an ordinary
         * **stopped** overlay (`C15`).
         */
        fun sync(
            context: Context,
            composition: HostCompositionRoot?,
            requestedEnabled: Boolean? = null,
        ) {
            val intent = Intent(context, PhoneOnlyOverlayService::class.java)
            when (
                val decision = PhoneOnlyStartStopPolicy.sync(
                    requestedEnabled = requestedEnabled,
                    gateEnabled = composition?.detachGate?.enabled?.value == true,
                    hasComposition = composition != null,
                    permissionGranted = Settings.canDrawOverlays(context),
                    running = isRunning.value,
                )
            ) {
                PhoneOnlyStartStopPolicy.Decision.Start -> {
                    PhoneOnlyOverlayRuntime.startRequested()
                    val start = intent.putExtra(EXTRA_REQUESTED_ENABLED, true)
                    runCatching { ContextCompat.startForegroundService(context, start) }
                        .onFailure {
                            // The foreground start itself was refused (background limits / OEM). Publish it so the
                            // settings page can show the reason and offer the retry, not just write a log line.
                            P3Log.line(
                                "P5_PHONE_ONLY state=failed start_failure=FOREGROUND " +
                                    "reason=${it.javaClass.simpleName}",
                            )
                            PhoneOnlyOverlayRuntime.failed(PhoneOnlyStartFailure.FOREGROUND)
                        }
                }
                is PhoneOnlyStartStopPolicy.Decision.Stop -> {
                    P3Log.line(
                        "P5_PHONE_ONLY state=${if (decision.refused) "refused" else "stopped"} " +
                            "reason=${decision.reason}",
                    )
                    runCatching { context.stopService(intent) }
                }
                PhoneOnlyStartStopPolicy.Decision.KeepRunning -> Unit
            }
        }
    }
}

/**
 * 0.5.1 C3 (`review Important #2`): one long press's record of whether it really owns the BALL dictation lease.
 *
 * The composer page and the floating ball share the host's one dictation lease. A long press that is **refused** -
 * `busy` because the composer is already listening, `no_session`, or any other retryable cause - owns nothing: its
 * release must not stop the composer's lease, and its microphone indicator must not appear. Only a press whose
 * `startDictation(BALL)` returned `Accepted` may stop the BALL lease, exactly once, on release or service teardown.
 *
 * Live on the service's main thread only (like the gesture callbacks that drive it), so it needs no lock.
 */
internal class BallPressLease {
    private var pressed = false
    private var accepted = false

    /** Records one start result; returns whether this press acquired the BALL lease. */
    fun begin(result: DictationStartResult): Boolean {
        pressed = true
        accepted = result == DictationStartResult.Accepted
        return accepted
    }

    /**
     * Ends the press and returns whether the BALL lease must be stopped.
     *
     * True only once, and only for an accepted press. A refused press answers false, so its release leaves the
     * composer's live lease (and its final/draft) untouched.
     */
    fun end(): Boolean {
        val stop = pressed && accepted
        pressed = false
        accepted = false
        return stop
    }

    /**
     * Whether the ball's microphone indicator may be shown: an accepted press, or a live BALL listening state.
     *
     * A refused press shows no microphone; the composer's own listening state is never a BALL state, because the
     * service only passes the `owner == BALL && Listening` observation in.
     */
    fun micVisible(ballListening: Boolean): Boolean = ballListening || (pressed && accepted)
}

/**
 * 0.5.1 C15: the ball's local notice for a refused start.
 *
 * The overlay cannot show the composer's inline status line, so it turns the returned result into a short local
 * notice. The wording comes from the same [dictationStartStatusText] the composer row uses; the fallback is only for a
 * result that has no copy (which cannot be a refusal).
 */
internal fun ballDictationRefusalNotice(result: DictationStartResult): String =
    dictationStartStatusText(result) ?: "无法开始听写"
