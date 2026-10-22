package com.sheen.rokid.glasses.overlayspike

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.WindowManager

/**
 * S1 throwaway probe overlay service.
 *
 * The gate is a platform question, so this service adds exactly the pieces the product will need and logs each one so
 * the evidence can name which one was measured:
 *
 * 1. a foreground service (`specialUse` in the manifest) that owns the window beyond the trampoline Activity;
 * 2. `startForeground` before any window work, so a rejected service type is recorded as its own failure instead of
 *    being hidden by the overlay;
 * 3. `WindowManager.addView` with `TYPE_APPLICATION_OVERLAY`, `gravity=BOTTOM`, 480x380, `TRANSLUCENT`, and the flags
 *    `NOT_TOUCHABLE | LAYOUT_NO_LIMITS | LAYOUT_IN_SCREEN | KEEP_SCREEN_ON`. Leaving `FLAG_NOT_FOCUSABLE` off is the
 *    whole side-key hypothesis; the view additionally requests focus and logs whether the window really has it;
 * 4. `SCREEN_BRIGHT_WAKE_LOCK | ACQUIRE_CAUSES_WAKEUP` acquired before the window is added. The vendor SDK has no
 *    wake API (official FAQ documents only `FLAG_KEEP_SCREEN_ON` and states the ring's `KEY_POWER` cannot be
 *    intercepted by an app), so the platform wake lock is the only mechanism the screen-off half can use - the same
 *    mechanism the current glasses app already uses for hints (`2026-10-08-p2-device-closure.md` §3).
 *
 * The service intentionally has no stop path. It is a throwaway probe; the operator stops it with
 * `am force-stop <package>` to release the wake lock before the screen-off measurement.
 */
class SpikeOverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var powerManager: PowerManager
    private val handler = Handler(Looper.getMainLooper())
    private var wakeLock: PowerManager.WakeLock? = null
    private var overlay: SpikeOverlayView? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        createChannel()
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentTitle("EDITH S1 overlay spike")
            .setContentText("foreground service holding the status overlay")
            .setOngoing(true)
            .build()
        try {
            startForeground(FOREGROUND_ID, notification)
            Log.i(TAG, "SPIKE_SERVICE onCreate foreground=ok type=manifest(specialUse)")
        } catch (t: Throwable) {
            // A rejected foreground-service type must not be read as "the overlay failed": it is its own finding.
            Log.e(TAG, "SPIKE_SERVICE onCreate foreground=FAILED error=${t.javaClass.simpleName}:${t.message}")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(
            TAG,
            "SPIKE_SERVICE onStartCommand interactive=${powerManager.isInteractive} " +
                "canDrawOverlays=${Settings.canDrawOverlays(this)} overlay=${overlay != null}",
        )
        showOverlay()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun showOverlay() {
        val existing = overlay
        if (existing != null) {
            existing.requestFocus()
            Log.i(TAG, "SPIKE_OVERLAY already-visible requestFocus focus=${existing.hasWindowFocus()}")
            return
        }

        acquireWakeLock()

        val view = SpikeOverlayView(this)
        val params = WindowManager.LayoutParams(
            WIDTH_PX,
            HEIGHT_PX,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM
            x = 0
            y = 0
            setTitle(WINDOW_TITLE)
        }

        try {
            windowManager.addView(view, params)
            overlay = view
            view.requestFocus()
            Log.i(
                TAG,
                "SPIKE_OVERLAY added type=TYPE_APPLICATION_OVERLAY gravity=BOTTOM size=${WIDTH_PX}x${HEIGHT_PX} " +
                    "format=TRANSLUCENT flags=NOT_TOUCHABLE|LAYOUT_NO_LIMITS|LAYOUT_IN_SCREEN|KEEP_SCREEN_ON " +
                    "focusable=true focusableInTouchMode=true",
            )
        } catch (t: Throwable) {
            Log.e(TAG, "SPIKE_OVERLAY add_failed=${t.javaClass.simpleName}:${t.message}")
            return
        }

        // The window is added asynchronously; this second sample is the one that can say whether it got focus and
        // whether the screen lit.
        handler.postDelayed({
            val current = overlay
            Log.i(
                TAG,
                "SPIKE_STATE interactive=${powerManager.isInteractive} hasWindowFocus=${current?.hasWindowFocus()} " +
                    "isShown=${current?.isShown} wakeLockHeld=${wakeLock?.isHeld}",
            )
        }, STATE_DELAY_MS)
    }

    private fun acquireWakeLock() {
        val lock = powerManager.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "EDITH-S1-SPIKE:screen",
        )
        lock.acquire(WAKE_LOCK_TIMEOUT_MS)
        wakeLock = lock
        Log.i(
            TAG,
            "SPIKE_WAKE_LOCK acquire tag=EDITH-S1-SPIKE:screen held=${lock.isHeld} " +
                "interactive=${powerManager.isInteractive}",
        )
    }

    private fun releaseWakeLock() {
        val lock = wakeLock ?: return
        wakeLock = null
        if (lock.isHeld) lock.release()
        Log.i(TAG, "SPIKE_WAKE_LOCK release held=${lock.isHeld}")
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        overlay?.let { view ->
            runCatching { windowManager.removeView(view) }
                .onFailure { Log.w(TAG, "SPIKE_OVERLAY remove_failed=${it.javaClass.simpleName}:${it.message}") }
        }
        overlay = null
        releaseWakeLock()
        Log.i(TAG, "SPIKE_SERVICE onDestroy")
        super.onDestroy()
    }

    private fun createChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "EDITH S1 spike", NotificationManager.IMPORTANCE_LOW),
        )
    }

    private companion object {
        const val TAG = "SpikeOverlay"
        const val CHANNEL_ID = "spike"
        const val FOREGROUND_ID = 1
        const val WINDOW_TITLE = "EDITH-S1-SPIKE"

        /** The panel is 480x640; the product panel occupies the bottom 380 px and the probe uses the same box. */
        const val WIDTH_PX = 480
        const val HEIGHT_PX = 380

        const val STATE_DELAY_MS = 1_500L
        const val WAKE_LOCK_TIMEOUT_MS = 10 * 60 * 1000L
    }
}
