package com.sheen.rokid.glasses.app

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.sheen.rokid.contracts.AssistantRoute
import com.sheen.rokid.glasses.BuildConfig
import com.sheen.rokid.glasses.bridge.RokidBridgeEndpoint
import com.sheen.rokid.glasses.display.OrbState
import com.sheen.rokid.glasses.display.PagOrbView
import java.lang.ref.WeakReference

/**
 * The CXR entry point.
 *
 * **Production/CXR `appStart` (no extra) is the CXR scene host**: when `SYSTEM_ALERT_WINDOW` is granted it makes this
 * Activity a transparent, non-interactive scene (1×1 transparent content, transparent window, `FLAG_NOT_FOCUSABLE |
 * FLAG_NOT_TOUCHABLE`) and starts [GlassesOverlayService], and it **stays RESUMED**. It never hosts the panel in that
 * mode. Keeping the scene resumed is a CXR requirement, not a visual choice: the CUSTOM_APP session requires the
 * glasses app to be in front, and finishing it lets the launcher resume, which makes CXR report
 * `onGlassAppResume=false` and terminate the session with `GLASSES_APP_EXIT` (Task 21 measured failure). The resident
 * service, not this Activity, owns the overlay, the wake lock and the CXR listener, which is what lets the panel
 * appear over another app and while the glasses screen is off (D158). The Activity is finished only when the CXR
 * scene/session ends: [GlassesOverlayService] calls [finishSceneHost] from its disconnect/destroy path, so it cannot
 * outlive the session. Without the overlay permission it shows the minimal permission screen first and switches to the
 * same transparent scene host once the grant is back.
 *
 * **The scene must not cover the launcher (Task 21 follow-up)**: keeping it resumed is necessary but not sufficient.
 * With the old opaque Activity theme, ActivityManager still treated the scene as a full-screen opaque window and
 * stopped the app behind it, so with the 480×380 panel hidden the composited screen was black. The manifest declares
 * this Activity with the translucent `Theme.Edith.CxrScene` (`android:windowIsTranslucent=true`); that is what keeps
 * the launcher (or any previous app) visible behind the empty scene. A runtime `Window.setFormat(TRANSLUCENT)` cannot
 * do it: the translucent decision is read from the Activity theme before the process starts.
 *
 * **The debug-only test host** (`EXTRA_HOST_PANEL`, honoured only in `BuildConfig.DEBUG`) keeps the old full-screen
 * Activity panel for the existing instrumentation suite. It is a test seam, not a product path: CXR `appStart` never
 * sets the extra, so the production default above is always the scene host. The extra is hosted by
 * [GlassesPanelHostActivity], the one component declared with the old opaque theme, so the translucent scene theme
 * cannot change the window the interaction/visual tests were written against.
 *
 * Everything the old Activity did after Task 7/8 (view, controller, rendering glue) now lives in [GlassesPanelHost];
 * this class only chooses the entry mode, owns the Activity window's keep-screen-on flag and forwards the probes.
 */
open class GlassesActivity : Activity() {

    private var host: GlassesPanelHost? = null
    private var permissionScreen: View? = null

    /** True while this Activity is the transparent CXR scene host (production default). */
    private var sceneHost = false

    /** The 1×1 transparent content view; kept so the device test can read what was really installed. */
    private var sceneContent: View? = null

    private val powerManager by lazy { getSystemService(PowerManager::class.java) }

    /** Held only while something is on screen that needs to be seen (see [holdAwakeFor]). */
    private var hintWakeLock: PowerManager.WakeLock? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!isHostPanelTestRequest()) {
            if (!Settings.canDrawOverlays(this)) {
                Log.i(TAG, "SCENE canDrawOverlays=false permission_screen=true")
                showPermissionScreen()
                return
            }
            // Claim the scene before starting the service: a disconnect that races this launch must find a scene to
            // end, and the service is only created after this onCreate returns anyway.
            enterSceneHost()
            startOverlayService()
            return
        }

        // ---------------------------- debug-only legacy test host (instrumentation only)
        Log.i(TAG, "HOST_PANEL debug extra -> Activity panel host")
        // Turns the panel on when this Activity is shown; the ring's power key cannot be intercepted by an app
        // (official FAQ, 应用自启与按键拦截自定义), so this is the standard Android request the old screen used.
        setTurnScreenOn(true)
        // Phase C (spec §6.6): the window background is transparent so the waveguide does not show a green cast.
        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        val host = GlassesPanelHost(
            context = this,
            callbacks = object : GlassesPanelHost.Callbacks {
                override fun hidePanel() = finish()
                override fun showPanel(route: AssistantRoute) = Unit
                override fun keepAwake(hold: Boolean) = holdAwakeFor(hold)
            },
        )
        this.host = host
        // Registered on the **content view**, as the official sample does; this is the known-good placement on this ROM.
        host.installSideTouch(findViewById(android.R.id.content) ?: host.root)
        setContentView(host.root)
        // The endpoint outlives this Activity, so it is given a listener rather than a reference to it.
        RokidBridgeEndpoint.instance.setListener { json -> acceptCommand(json) }
    }

    override fun onResume() {
        super.onResume()
        val host = host
        if (host != null) {
            host.onStart()
            return
        }
        if (sceneHost) return
        // Returning from the overlay-permission settings page: the screen is showing, so switch to the transparent
        // scene host and start the service without finishing (the CXR session needs the resumed Activity).
        if (permissionScreen != null && Settings.canDrawOverlays(this)) {
            enterSceneHost()
            startOverlayService()
        }
    }

    override fun onPause() {
        super.onPause()
        host?.onStop()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val host = host
        if (host != null && host.onKeyDown(keyCode, event)) return true
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean =
        host?.onKeyUp(keyCode, event) ?: super.onKeyUp(keyCode, event)

    override fun onDestroy() {
        val host = host
        if (host != null) {
            host.destroy()
            RokidBridgeEndpoint.instance.setListener(null)
        }
        releaseSceneHost()
        holdAwakeFor(false)
        super.onDestroy()
    }

    // ---------------------------------------------------------------- entry modes

    /** `BuildConfig.DEBUG`-gated test seam; see the class comment. */
    private fun isHostPanelTestRequest(): Boolean =
        BuildConfig.DEBUG && intent?.getBooleanExtra(EXTRA_HOST_PANEL, false) == true

    /**
     * Turns this Activity into the invisible CXR scene: no panel, no visible content, no focus or touch.
     *
     * The window is translucent with a transparent background and its only content is one 1×1 transparent view, so
     * nothing of this Activity is drawn; the service's 480×380 `TYPE_APPLICATION_OVERLAY` is the only
     * visible/interactive surface. The Activity itself is not finished: CXR needs a resumed glasses Activity for the
     * CUSTOM_APP session to stay `Started` (Task 21 measured `GLASSES_APP_EXIT` when it finished).
     */
    private fun enterSceneHost() {
        if (sceneHost) return
        sceneHost = true
        permissionScreen = null
        // The translucent format keeps the window surface from compositing as an opaque layer; the background and
        // content below draw nothing at all.
        window.setFormat(PixelFormat.TRANSLUCENT)
        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        window.addFlags(
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
        )
        val content = View(this).apply { setBackgroundColor(Color.TRANSPARENT) }
        setContentView(content, ViewGroup.LayoutParams(SCENE_CONTENT_PX, SCENE_CONTENT_PX))
        sceneContent = content
        claimSceneHost()
        Log.i(TAG, "SCENE transparent host active canDrawOverlays=true themeTranslucent=${windowIsTranslucentTheme()} " +
            "flags=NOT_FOCUSABLE|NOT_TOUCHABLE content=${SCENE_CONTENT_PX}x$SCENE_CONTENT_PX")
    }

    /**
     * The theme attribute the platform reads to decide whether this is a translucent ActivityRecord.
     *
     * ActivityManager resolves it from the manifest theme **before** the process starts; that is why only
     * `Theme.Edith.CxrScene` (`android:windowIsTranslucent=true`) keeps the launcher visible behind the empty scene.
     * It is logged so the device evidence quotes the platform value instead of our constant.
     */
    private fun windowIsTranslucentTheme(): Boolean {
        val attrs = theme.obtainStyledAttributes(intArrayOf(android.R.attr.windowIsTranslucent))
        return try {
            attrs.getBoolean(0, false)
        } finally {
            attrs.recycle()
        }
    }

    /**
     * BACK belongs to the panel while it is visible; when the scene host holds focus (panel hidden) it must not finish
     * the CXR scene. The old non-focusable scene never saw BACK; the accessibility-overlay close re-enables scene focus
     * so the system always has a focused window, and this keeps that window from ending the session.
     */
    override fun onBackPressed() {
        if (sceneHost) {
            Log.i(TAG, "SCENE back ignored: the panel owns BACK while visible")
            return
        }
        super.onBackPressed()
    }

    /**
     * Registers this Activity as the one the service's scene-end hook finishes. A duplicate launch replaces the
     * previous scene host (CXR may issue `appStart` again) so exactly one transparent scene instance stays alive.
     */
    private fun claimSceneHost() {
        val previous = sceneHostRef?.get()
        sceneHostRef = WeakReference(this)
        if (previous != null && previous !== this) {
            Log.i(TAG, "SCENE replacing the previous scene host")
            runCatching { previous.finish() }
        }
    }

    /** Drops the static reference when this Activity goes away, so a dead scene cannot be finished or retained. */
    private fun releaseSceneHost() {
        if (sceneHostRef?.get() === this) sceneHostRef = null
        if (!sceneHost) return
        sceneHost = false
        sceneContent = null
    }

    private fun startOverlayService() {
        val result = runCatching {
            startForegroundService(Intent(this, GlassesOverlayService::class.java))
        }
        Log.i(TAG, "SCENE startForegroundService=${result.isSuccess}")
    }

    /**
     * The minimal permission screen (no panel): a short explanation and a button that opens the system's
     * "display over other apps" page. `onResume` re-checks the app-op, so coming back with permission granted starts
     * the service without a second tap.
     */
    private fun showPermissionScreen() {
        val margin = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, 24f, resources.displayMetrics).toInt()
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.BLACK)
            setPadding(margin, margin, margin, margin)
        }
        column.addView(TextView(this).apply {
            text = "需要悬浮窗权限"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            gravity = Gravity.CENTER
        })
        column.addView(TextView(this).apply {
            text = "EDITH 的面板需要显示在其他界面之上。请在设置中允许本应用显示在其他应用的上层，然后返回。"
            setTextColor(Color.LTGRAY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            gravity = Gravity.CENTER
            setPadding(0, margin, 0, margin)
        })
        column.addView(Button(this).apply {
            text = "打开设置"
            setOnClickListener { openOverlaySettings() }
        })
        permissionScreen = column
        setContentView(column)
    }

    private fun openOverlaySettings() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName"),
        )
        runCatching { startActivity(intent) }
            .onFailure { Log.w(TAG, "overlay settings page unavailable", it) }
    }

    // ---------------------------------------------------------------- wake claim (Activity window only)

    /**
     * Keeps the Activity window awake while something is being shown. The production overlay service owns its own
     * screen wake lock; this path exists for the debug test host and is the same mechanism as before Task 9.
     */
    @Suppress("DEPRECATION")
    private fun holdAwakeFor(hold: Boolean) {
        if (hold) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            if (hintWakeLock == null) {
                hintWakeLock = powerManager.newWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP, "edith:hint")
                    .apply {
                        setReferenceCounted(false)
                        acquire(HINT_HOLD_MILLIS)
                    }
            }
            return
        }
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hintWakeLock?.let { lock -> runCatching { if (lock.isHeld) lock.release() } }
        hintWakeLock = null
    }

    // ---------------------------------------------------------------- probes (the existing instrumentation contract)

    fun acceptCommand(json: String): Boolean = host?.accept(json) ?: false

    fun hintText(): String = host?.hintText().orEmpty()

    fun conversationText(route: AssistantRoute): String = host?.conversationText(route).orEmpty()

    fun selectedRoute(): AssistantRoute = host?.selectedRoute() ?: AssistantRoute.WAKE

    fun orbStateForProbe(): OrbState = host?.orbStateForProbe() ?: OrbState.IDLE

    /**
     * 0.24 S4 Task 18: the panel's PAG orb itself.
     *
     * The Activity getter is the replacement for the Task 7 `VoiceOrbView` cast: the panel now owns a [PagOrbView]
     * (with its own fallback inside), and the device test asserts its tags/clips rather than inferring the animation.
     */
    fun orbViewForProbe(): PagOrbView? = host?.orbViewForProbe()

    fun listeningVisibleForProbe(): Boolean = host?.listeningVisibleForProbe() ?: false

    fun hiVisibleForProbe(): Boolean = host?.hiVisibleForProbe() ?: false

    fun pinTextVisibleForProbe(): Boolean = host?.pinTextVisibleForProbe() ?: false

    fun pinIconVisibleForProbe(): Boolean = host?.pinIconVisibleForProbe() ?: false

    fun thinkingVisibleForProbe(): Boolean = host?.thinkingVisibleForProbe() ?: false

    fun thinkingIconWidthForProbe(): Int = host?.thinkingIconWidthForProbe() ?: 0

    fun thinkingGifWidthForProbe(): Int = host?.thinkingGifWidthForProbe() ?: 0

    fun thinkingGifHeightForProbe(): Int = host?.thinkingGifHeightForProbe() ?: 0

    fun thinkingGifResourceForProbe(): String = host?.thinkingGifResourceForProbe().orEmpty()

    fun thinkingGifRunningForProbe(): Boolean = host?.thinkingGifRunningForProbe() == true

    fun thinkingGifAnimatedForProbe(): Boolean = host?.thinkingGifAnimatedForProbe() == true

    fun thinkingGifIntrinsicWidthForProbe(): Int = host?.thinkingGifIntrinsicWidthForProbe() ?: 0

    fun thinkingGifIntrinsicHeightForProbe(): Int = host?.thinkingGifIntrinsicHeightForProbe() ?: 0

    fun thinkingRowHeightForProbe(): Int = host?.thinkingRowHeightForProbe() ?: 0

    fun thinkingTextSizeForProbe(): Float = host?.thinkingTextSizeForProbe() ?: 0f

    fun toolVisibleForProbe(): Boolean = host?.toolVisibleForProbe() ?: false

    fun toolTextForProbe(): String = host?.toolTextForProbe().orEmpty()

    fun toolRowForProbe(): android.view.View? = host?.toolRowForProbe()

    fun toolIconWidthForProbe(): Int = host?.toolIconWidthForProbe() ?: 0

    fun toolGifWidthForProbe(): Int = host?.toolGifWidthForProbe() ?: 0

    fun toolGifHeightForProbe(): Int = host?.toolGifHeightForProbe() ?: 0

    fun toolGifResourceForProbe(): String = host?.toolGifResourceForProbe().orEmpty()

    fun toolGifRunningForProbe(): Boolean = host?.toolGifRunningForProbe() == true

    fun toolGifRepeatCountForProbe(): Int = host?.toolGifRepeatCountForProbe() ?: Int.MIN_VALUE

    fun toolLabelTextSizeForProbe(): Float = host?.toolLabelTextSizeForProbe() ?: 0f

    fun toolTextColorForProbe(): Int = host?.toolTextColorForProbe() ?: 0

    fun toolIconRunningForProbe(): Boolean = host?.toolIconRunningForProbe() == true

    // 0.24 S4 Task 19: official GIFs and panel chrome.
    fun asrGifVisibleForProbe(): Boolean = host?.asrGifVisibleForProbe() == true

    fun asrGifViewForProbe(): android.view.View? = host?.asrGifViewForProbe()

    fun asrGifWidthForProbe(): Int = host?.asrGifWidthForProbe() ?: 0

    fun asrGifHeightForProbe(): Int = host?.asrGifHeightForProbe() ?: 0

    fun asrGifRunningForProbe(): Boolean = host?.asrGifRunningForProbe() == true

    fun asrGifAnimatedForProbe(): Boolean = host?.asrGifAnimatedForProbe() == true

    fun asrGifIntrinsicWidthForProbe(): Int = host?.asrGifIntrinsicWidthForProbe() ?: 0

    fun asrGifIntrinsicHeightForProbe(): Int = host?.asrGifIntrinsicHeightForProbe() ?: 0

    fun topMaskHeightForProbe(): Int = host?.topMaskHeightForProbe() ?: 0

    fun bottomMaskHeightForProbe(): Int = host?.bottomMaskHeightForProbe() ?: 0

    fun complianceTextSizeForProbe(): Float = host?.complianceTextSizeForProbe() ?: 0f

    fun pinIconWidthForProbe(): Int = host?.pinIconWidthForProbe() ?: 0

    fun pinIconHeightForProbe(): Int = host?.pinIconHeightForProbe() ?: 0

    fun pinIconAlphaForProbe(): Float = host?.pinIconAlphaForProbe() ?: 0f

    fun listeningTextForProbe(): String = host?.listeningTextForProbe().orEmpty()

    fun thinkingTextForProbe(): String = host?.thinkingTextForProbe().orEmpty()

    fun tipsTextForProbe(): String = host?.tipsTextForProbe().orEmpty()

    fun complianceTextForProbe(): String = host?.complianceTextForProbe().orEmpty()

    fun lastMessageRowForProbe(): TextView? = host?.lastMessageRowForProbe()

    fun conversationRowsForProbe(): List<TextView> = host?.conversationRowsForProbe().orEmpty()

    fun conversationTurnCountForProbe(): Int = host?.conversationTurnCountForProbe() ?: 0

    fun scrollOffsetY(): Int = host?.scrollOffsetY() ?: 0

    fun maxScrollY(): Int = host?.maxScrollY() ?: 0

    fun isPinnedForProbe(): Boolean = host?.isPinnedForProbe() == true

    fun tickerRunningForProbe(): Boolean = host?.tickerRunningForProbe() == true

    fun lastBackActionForProbe(): String? = host?.lastBackActionForProbe()

    fun lastBackStopSentForProbe(): Boolean? = host?.lastBackStopSentForProbe()

    fun setStopSenderForProbe(sender: ((String) -> Boolean)?) {
        host?.setStopSenderForProbe(sender)
    }

    fun lastMessageTopForProbe(): Int = host?.lastMessageTopForProbe() ?: 0

    /** True while the permission screen (the no-overlay-grant path) is the Activity's content. */
    fun permissionScreenVisibleForProbe(): Boolean = permissionScreen != null

    // ------------------------------------------------ scene-host probes (the production default, Task 21 fix)

    /** True while this Activity is the transparent CXR scene host instead of the debug panel host. */
    fun isSceneHostForProbe(): Boolean = sceneHost

    /** True only for the debug `EXTRA_HOST_PANEL` path (the production path must never build the panel). */
    fun isPanelHostForProbe(): Boolean = host != null

    /** The scene host window's real flags, read from `WindowManager.LayoutParams` (not a constant of ours). */
    fun sceneHostWindowFlagsForProbe(): Int = window.attributes.flags

    /** The scene host window's surface format; `PixelFormat.TRANSLUCENT` means the window is not an opaque layer. */
    fun sceneHostWindowFormatForProbe(): Int = window.attributes.format

    /** The window background the platform will draw behind the scene content. */
    fun sceneHostBackgroundTransparentForProbe(): Boolean =
        (window.decorView.background as? ColorDrawable)?.color == Color.TRANSPARENT

    fun sceneHostContentWidthForProbe(): Int = sceneContent?.layoutParams?.width ?: -1

    fun sceneHostContentHeightForProbe(): Int = sceneContent?.layoutParams?.height ?: -1

    companion object {
        /**
         * Test-only launch extra (`BuildConfig.DEBUG` only): keep the legacy Activity panel host for the existing
         * instrumentation suite. Production/CXR `appStart` never sets it.
         */
        const val EXTRA_HOST_PANEL = "com.sheen.rokid.glasses.extra.HOST_PANEL"

        private const val TAG = "EdithGlasses"

        /** Same bound as before Task 9: a hint that is never cleared cannot hold the window forever. */
        private const val HINT_HOLD_MILLIS = 15_000L

        /** The scene host's only content: one transparent pixel-sized view (the window itself is full-screen). */
        private const val SCENE_CONTENT_PX = 1

        /**
         * The transparent CXR scene host, held weakly so a finished scene can never be retained by the service.
         *
         * The overlay service calls [finishSceneHost] from its CXR-disconnect/`onDestroy` path: the CUSTOM_APP session
         * and the scene must end together, otherwise the next `appStart` would find a scene that belongs to the dead
         * session. The reference is dropped in [releaseSceneHost] (`onDestroy`).
         */
        @Volatile
        private var sceneHostRef: WeakReference<GlassesActivity>? = null

        /** True while a scene host is registered; the service's scene-end hook needs one to finish (test-only). */
        internal fun sceneHostPresentForTest(): Boolean = sceneHostRef?.get() != null

        /**
         * Gives the transparent scene host the focused-window role while an accessibility-overlay panel is hidden.
         *
         * Removing a focused `TYPE_ACCESSIBILITY_OVERLAY` panel otherwise left the top RESUMED scene with no focused
         * window; this Android 12 build then raised `ANR in com.sheen.rokid.glasses: Application does not have a
         * focused window` and force-finished the scene (ending the CXR session). The scene is normally NOT_FOCUSABLE so
         * the launcher owns focus; this toggle is used only around an accessibility-overlay show/hide cycle. When the
         * panel is visible the scene is non-focusable again, so the panel receives the side keys.
         */
        internal fun setSceneFocusable(focusable: Boolean) {
            val activity = sceneHostRef?.get() ?: return
            activity.runOnUiThread {
                if (!activity.sceneHost || activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                if (focusable) {
                    activity.window.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
                } else {
                    activity.window.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
                }
                Log.i(TAG, "SCENE focusable=$focusable")
            }
        }

        /**
         * Ends the transparent scene host. Called by [GlassesOverlayService] when the CXR link is gone (the scene's
         * reason to exist) and from the service's own `onDestroy` as a safety net. Main-thread safe: the service
         * already posts there, and any other caller is marshalled onto the Activity's thread. Idempotent.
         */
        fun finishSceneHost() {
            val activity = sceneHostRef?.get() ?: return
            sceneHostRef = null
            activity.runOnUiThread {
                if (!activity.isFinishing && !activity.isDestroyed) {
                    Log.i(TAG, "SCENE finish: CXR scene/session ended")
                    activity.finish()
                }
            }
        }
    }
}
