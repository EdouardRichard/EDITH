package com.sheen.rokid.glasses.app

import android.app.Activity
import android.content.Intent
import android.graphics.Color
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
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.sheen.rokid.contracts.AssistantRoute
import com.sheen.rokid.glasses.BuildConfig
import com.sheen.rokid.glasses.bridge.RokidBridgeEndpoint
import com.sheen.rokid.glasses.display.OrbState
import com.sheen.rokid.glasses.display.PagOrbView

/**
 * The CXR entry point.
 *
 * **Production/CXR `appStart` (no extra) is a trampoline**: when `SYSTEM_ALERT_WINDOW` is granted it starts
 * [GlassesOverlayService] and finishes; otherwise it shows a minimal permission screen and starts the settings page.
 * It never hosts the panel in that mode. The resident service, not this Activity, owns the overlay, the wake lock and
 * the CXR listener, which is what lets the panel appear over another app and while the glasses screen is off (D158).
 *
 * **The debug-only test host** (`EXTRA_HOST_PANEL`, honoured only in `BuildConfig.DEBUG`) keeps the old full-screen
 * Activity panel for the existing instrumentation suite. It is a test seam, not a product path: CXR `appStart` never
 * sets the extra, so the production default above is always the trampoline.
 *
 * Everything the old Activity did after Task 7/8 (view, controller, rendering glue) now lives in [GlassesPanelHost];
 * this class only chooses the entry mode, owns the Activity window's keep-screen-on flag and forwards the probes.
 */
class GlassesActivity : Activity() {

    private var host: GlassesPanelHost? = null
    private var permissionScreen: View? = null

    private val powerManager by lazy { getSystemService(PowerManager::class.java) }

    /** Held only while something is on screen that needs to be seen (see [holdAwakeFor]). */
    private var hintWakeLock: PowerManager.WakeLock? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!isHostPanelTestRequest()) {
            if (!Settings.canDrawOverlays(this)) {
                Log.i(TAG, "TRAMPOLINE canDrawOverlays=false permission_screen=true")
                showPermissionScreen()
                return
            }
            startOverlayService()
            finish()
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
        // Returning from the overlay-permission settings page: the screen is showing, so start the service and finish.
        if (permissionScreen != null && Settings.canDrawOverlays(this)) {
            startOverlayService()
            finish()
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
        holdAwakeFor(false)
        super.onDestroy()
    }

    // ---------------------------------------------------------------- entry modes

    /** `BuildConfig.DEBUG`-gated test seam; see the class comment. */
    private fun isHostPanelTestRequest(): Boolean =
        BuildConfig.DEBUG && intent?.getBooleanExtra(EXTRA_HOST_PANEL, false) == true

    private fun startOverlayService() {
        val result = runCatching {
            startForegroundService(Intent(this, GlassesOverlayService::class.java))
        }
        Log.i(TAG, "TRAMPOLINE canDrawOverlays=true startForegroundService=${result.isSuccess}")
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

    fun thinkingTextSizeForProbe(): Float = host?.thinkingTextSizeForProbe() ?: 0f

    fun toolVisibleForProbe(): Boolean = host?.toolVisibleForProbe() ?: false

    fun toolTextForProbe(): String = host?.toolTextForProbe().orEmpty()

    fun toolRowForProbe(): android.view.View? = host?.toolRowForProbe()

    fun toolIconWidthForProbe(): Int = host?.toolIconWidthForProbe() ?: 0

    fun toolLabelTextSizeForProbe(): Float = host?.toolLabelTextSizeForProbe() ?: 0f

    fun toolTextColorForProbe(): Int = host?.toolTextColorForProbe() ?: 0

    fun toolIconRunningForProbe(): Boolean = host?.toolIconRunningForProbe() == true

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

    companion object {
        /**
         * Test-only launch extra (`BuildConfig.DEBUG` only): keep the legacy Activity panel host for the existing
         * instrumentation suite. Production/CXR `appStart` never sets it.
         */
        const val EXTRA_HOST_PANEL = "com.sheen.rokid.glasses.extra.HOST_PANEL"

        private const val TAG = "EdithGlasses"

        /** Same bound as before Task 9: a hint that is never cleared cannot hold the window forever. */
        private const val HINT_HOLD_MILLIS = 15_000L
    }
}
