package com.sheen.rokid.phone.overlay

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
import androidx.compose.ui.geometry.Offset
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
import kotlinx.coroutines.flow.collect
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

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val composition = app.liveComposition
        if (composition == null) {
            P3Log.line("P5_PHONE_ONLY state=refused reason=NO_COMPOSITION")
            stopSelf()
            return
        }
        if (!composition.detachGate.enabled.value) {
            P3Log.line("P5_PHONE_ONLY state=refused reason=MODE_OFF")
            stopSelf()
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            P3Log.line("P5_PHONE_ONLY state=refused reason=NO_OVERLAY_PERMISSION")
            stopSelf()
            return
        }
        root = composition
        if (!startForegroundNotification()) {
            stopSelf()
            return
        }
        windowManager = getSystemService(WindowManager::class.java)
        measure()
        addWindows()
        collectState(composition)
        P3Log.line("P5_PHONE_ONLY state=on")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    override fun onDestroy() {
        // 0.5.1 C3/T8 handoff: a service teardown owns the BALL lease this press acquired, so it ends that lease and
        // never the composer's. It must **cancel**, not stop: a stop forces the turn's final and `onDictationFinal(BALL)`
        // sends it through `requestTypedMessage`, so a shutdown would publish a user turn the wearer never finished.
        // A refused press has nothing to stop and must not touch the composer's lease. T8 still owns the bubble clear
        // and Notice expiry here; this only releases the dictation lease.
        if (ballPress.end()) root?.cancelDictation(DictationTarget.BALL)
        scope.cancel()
        ballView?.let { runCatching { windowManager?.removeView(it) } }
        bubbleView?.let { runCatching { windowManager?.removeView(it) } }
        ballView = null
        bubbleView = null
        lifecycleOwner?.let {
            it.onPause(); it.onStop(); it.onDestroy()
        }
        lifecycleOwner = null
        if (foregroundStarted) {
            runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
            foregroundStarted = false
        }
        P3Log.line("P5_PHONE_ONLY state=off")
        super.onDestroy()
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

    private fun ComposeView.installLifecycle(owner: OverlayLifecycleOwner) {
        // A ComposeView in a Service has no Activity; the ViewTree owner is what gives its recomposer a lifecycle. The
        // class is present in the APK (the app's own lifecycle observers run) but the lifecycle-runtime AAR does not
        // expose it to Kotlin's compile classpath in this KMP-variant graph, so it is resolved reflectively here. The
        // alternative would be a plain-View rewrite of the whole overlay.
        runCatching {
            val cls = Class.forName("androidx.lifecycle.ViewTreeLifecycleOwner")
            cls.getMethod(
                "set",
                android.view.View::class.java,
                androidx.lifecycle.LifecycleOwner::class.java,
            ).invoke(null, this, owner)
        }.onFailure {
            P3Log.line("P5_PHONE_ONLY state=viewtree_lifecycle_failed reason=${it.javaClass.simpleName}")
        }
        // Compose also refuses to attach unless the view propagates a SavedStateRegistryOwner; the overlay's content
        // uses no `rememberSaveable`, but AndroidComposeView checks the owner unconditionally on attach.
        runCatching {
            val cls = Class.forName("androidx.savedstate.ViewTreeSavedStateRegistryOwner")
            cls.getMethod(
                "set",
                android.view.View::class.java,
                androidx.savedstate.SavedStateRegistryOwner::class.java,
            ).invoke(null, this, owner)
        }.onFailure {
            P3Log.line("P5_PHONE_ONLY state=viewtree_savedstate_failed reason=${it.javaClass.simpleName}")
        }
    }

    private fun addWindows() {
        val manager = windowManager ?: return
        val composition = root ?: return
        val owner = OverlayLifecycleOwner().also {
            it.onCreate(); it.onStart(); it.onResume()
        }
        lifecycleOwner = owner

        val bubble = ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            installLifecycle(owner)
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
            installLifecycle(owner)
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
                        // C11: the service records the ball window's origin; the content adds the event's local
                        // position to get the screen-space pointer position the drag controller consumes.
                        windowOrigin = {
                            val params = ballParams
                            if (params == null) Offset.Zero else Offset(params.x.toFloat(), params.y.toFloat())
                        },
                        onAction = ::onBallAction,
                    )
                }
            }
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

        try {
            manager.addView(bubble, bubbleParams)
            bubbleView = bubble
            manager.addView(ball, params)
            ballView = ball
            ballParams = params
            updateBallWindow()
        } catch (failure: Exception) {
            P3Log.line("P5_PHONE_ONLY state=refused reason=WINDOW_${failure.javaClass.simpleName}")
            stopSelf()
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
            composition.detachGate.enabled.collect { enabled ->
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
            BallGestureAction.Tap -> openApp()
            is BallGestureAction.Drag -> dragBy(action.dx, action.dy)
            BallGestureAction.Snap -> snap()
            BallGestureAction.LongPressStart -> beginLongPress()
            BallGestureAction.LongPressEnd -> endLongPress()
        }
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

    private fun openApp() {
        runCatching {
            startActivity(
                Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
        P3Log.line("P5_BALL event=tap")
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
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
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
        private const val WINDOW_FACTOR = 3.6f
        private const val HALO_POLL_MILLIS = 250L
        private const val PRESS_MILLIS = 120L

        /**
         * Starts or stops the overlay to match the mode and the permission. Only the production composition may start
         * it; the model must already be ready because the overlay only draws a host that exists.
         *
         * [requestedEnabled] is the mode transition's **requested** value. The detach gate invokes its mode-changed
         * callback while `enabled` still holds the old value (it updates after the save), so reading the gate here on
         * an enable transition stopped the service instead of starting it. `MainActivity.onResume` passes null and the
         * gate is read, which is correct at resume time.
         */
        fun sync(
            context: Context,
            composition: HostCompositionRoot?,
            requestedEnabled: Boolean? = null,
        ) {
            val intent = Intent(context, PhoneOnlyOverlayService::class.java)
            val enabled = requestedEnabled ?: (composition?.detachGate?.enabled?.value == true)
            val permitted = Settings.canDrawOverlays(context)
            if (composition == null || !enabled || !permitted) {
                P3Log.line(
                    "P5_PHONE_ONLY state=stop reason=" + when {
                        composition == null -> "NO_COMPOSITION"
                        !enabled -> "MODE_OFF"
                        else -> "NO_OVERLAY_PERMISSION"
                    },
                )
                runCatching { context.stopService(intent) }
                return
            }
            runCatching { ContextCompat.startForegroundService(context, intent) }
                .onFailure { P3Log.line("P5_PHONE_ONLY state=start_failed reason=${it.javaClass.simpleName}") }
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
