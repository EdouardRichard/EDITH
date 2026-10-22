package edith.phone.wakelockprobe

import android.app.Activity
import android.app.KeyguardManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.widget.TextView

/**
 * D5/D6 throwaway spike (2026-10-19). One activity, several measurement modes:
 *  - `wakelock` / `activity` / `both`: D5's wake measurements (kept so the D5 rounds stay reproducible).
 *  - `+dismiss`: when a keyguard is actually locked, ask `KeyguardManager.requestDismissKeyguard` and record
 *    which callback fired, the keyguard state before/after and how long it took. The PC does not wake or
 *    dismiss anything; the app must do it.
 *
 * The `trigger` extra only labels *who started this activity* (`am_start`, `broadcast`, `fsi`); it is not
 * used to change behaviour. That label is part of the summary line so a PASS can be attributed to the
 * right trigger path.
 *
 * Summary line (one grep-able INFO line):
 *
 *   KEYGUARD_PROBE mode=... keyguardBefore=... secure=... interactiveBefore=... dismiss=...
 *   keyguardAfter=... elapsedMs=... trigger=...
 *
 * `elapsedMs` here is total onCreate->summary; `interactiveElapsedMs` is onCreate->first observed
 * interactive; `dismissElapsedMs` is requestDismissKeyguard->callback. The line keeps `dismiss=` one of
 * `not_requested|not_attempted|requested|succeeded|error|cancelled|exception:...` so the verdict is a
 * machine-readable token instead of prose.
 */
class ProbeActivity : Activity() {

    companion object {
        private const val TAG = "KEYGUARD_PROBE"
        private const val WAKE_LOCK_TIMEOUT_MS = 30_000L
        private const val POLL_INTERVAL_MS = 10L
        private const val MEASURE_WINDOW_MS = 3_000L
        private const val VISIBLE_HOLD_MS = 200L
        private const val DEFAULT_HOLD_MS = VISIBLE_HOLD_MS
        private const val DISMISS_WAIT_MS = 5_000L
        private const val DISMISS_SETTLE_MS = 300L

        const val EXTRA_MODE = "mode"
        const val EXTRA_TRIGGER = "trigger"
        const val EXTRA_HOLD_MILLIS = "holdMillis"
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var powerManager: PowerManager
    private lateinit var keyguardManager: KeyguardManager

    private var wakeLock: PowerManager.WakeLock? = null
    private lateinit var mode: String
    private lateinit var tokens: Set<String>
    private lateinit var trigger: String

    private var createdAt = 0L
    private var interactiveBefore = false
    private var keyguardBefore = false
    private var keyguardSecure = false
    private var holdMillis = DEFAULT_HOLD_MS

    private var firstInteractiveAtMs = -1L
    private var dismissRequested = false
    private var dismissState = "not_requested"
    private var dismissCallbackAtMs = -1L
    private var dismissRequestedAtMs = -1L
    private var reported = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        createdAt = SystemClock.elapsedRealtime()
        powerManager = getSystemService(POWER_SERVICE) as PowerManager
        keyguardManager = getSystemService(KEYGUARD_SERVICE) as KeyguardManager

        mode = intent.getStringExtra(EXTRA_MODE)?.trim().orEmpty().ifEmpty { "wakelock" }
        tokens = mode.split('+', ',')
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
            .toSet()
        trigger = intent.getStringExtra(EXTRA_TRIGGER)?.trim().orEmpty().ifEmpty { "am_start" }
        holdMillis = intent.getLongExtra(EXTRA_HOLD_MILLIS, DEFAULT_HOLD_MS).coerceIn(0L, 30_000L)

        // Read the pre-trigger state before applying any flag or wake lock.
        interactiveBefore = powerManager.isInteractive
        keyguardBefore = keyguardManager.isKeyguardLocked
        keyguardSecure = keyguardManager.isKeyguardSecure

        logEvent(
            event = "onCreate",
            extra = "interactiveBefore=$interactiveBefore keyguardBefore=$keyguardBefore " +
                "keyguardSecure=$keyguardSecure trigger=$trigger holdMillis=$holdMillis",
        )

        // showWhenLocked does not light the screen; it only keeps the activity visible over a keyguard.
        setShowWhenLocked(true)
        // Only modes that are supposed to test the Activity path may set turnScreenOn. Setting it in
        // `mode=wakelock` would let the Activity wake the screen and hide whether the wake lock did.
        if (usesActivityPath()) {
            setTurnScreenOn(true)
        }
        if (usesWakeLockPath()) {
            acquireScreenWakeLock()
        }

        // A real window is needed for the activity to count as visible; the theme is translucent.
        setContentView(
            TextView(this).apply {
                text = "EDITH D5/D6 probe mode=$mode trigger=$trigger"
                textSize = 12f
            },
        )

        startPolling()
    }

    override fun onResume() {
        super.onResume()
        logEvent(event = "onResume", extra = "interactive=${powerManager.isInteractive}")
        maybeRequestDismiss()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        releaseWakeLock()
        super.onDestroy()
    }

    private fun usesWakeLockPath(): Boolean = tokens.contains("wakelock") || tokens.contains("both")

    private fun usesActivityPath(): Boolean = tokens.contains("activity") || tokens.contains("both")

    private fun acquireScreenWakeLock() {
        try {
            @Suppress("DEPRECATION")
            val lock = powerManager.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "edith:wakelock-probe",
            )
            lock.setReferenceCounted(false)
            lock.acquire(WAKE_LOCK_TIMEOUT_MS)
            wakeLock = lock
            logEvent(
                event = "wakeLockAcquired",
                extra = "flags=SCREEN_BRIGHT_WAKE_LOCK|ACQUIRE_CAUSES_WAKEUP timeoutMs=$WAKE_LOCK_TIMEOUT_MS",
            )
        } catch (t: Throwable) {
            logEvent(event = "wakeLockFailed", extra = "error=${t.javaClass.simpleName}:${t.message}")
        }
    }

    private fun startPolling() {
        handler.post(
            object : Runnable {
                override fun run() {
                    if (reported) return
                    if (firstInteractiveAtMs < 0 && powerManager.isInteractive) {
                        firstInteractiveAtMs = SystemClock.elapsedRealtime() - createdAt
                        logEvent(event = "interactiveOn", extra = "interactiveElapsedMs=$firstInteractiveAtMs")
                        // Hold the window so the PC can dump window/activity state while it is up.
                        handler.postDelayed({ finishAndReport() }, holdMillis)
                        return
                    }
                    val elapsed = SystemClock.elapsedRealtime() - createdAt
                    if (elapsed >= MEASURE_WINDOW_MS) {
                        finishAndReport()
                        return
                    }
                    handler.postDelayed(this, POLL_INTERVAL_MS)
                }
            },
        )
    }

    private fun maybeRequestDismiss() {
        if (!tokens.contains("dismiss") || dismissRequested) return
        dismissRequested = true
        if (!keyguardManager.isKeyguardLocked) {
            dismissState = "not_attempted"
            dismissCallbackAtMs = SystemClock.elapsedRealtime()
            logEvent(event = "dismissSkipped", extra = "reason=keyguard_not_locked")
            return
        }
        try {
            keyguardManager.requestDismissKeyguard(
                this,
                object : KeyguardManager.KeyguardDismissCallback() {
                    override fun onDismissSucceeded() {
                        onDismissCallback("succeeded", "dismissSucceeded")
                    }

                    override fun onDismissError() {
                        onDismissCallback("error", "dismissError")
                    }

                    override fun onDismissCancelled() {
                        onDismissCallback("cancelled", "dismissCancelled")
                    }
                },
            )
            dismissState = "requested"
            dismissRequestedAtMs = SystemClock.elapsedRealtime()
            logEvent(event = "dismissRequested", extra = "keyguardLocked=${keyguardManager.isKeyguardLocked}")
        } catch (t: Throwable) {
            dismissState = "exception:${t.javaClass.simpleName}"
            dismissCallbackAtMs = SystemClock.elapsedRealtime()
            logEvent(event = "dismissException", extra = "error=${t.javaClass.simpleName}:${t.message}")
        }
    }

    private fun onDismissCallback(state: String, event: String) {
        dismissState = state
        dismissCallbackAtMs = SystemClock.elapsedRealtime()
        val latency = if (dismissRequestedAtMs >= 0) dismissCallbackAtMs - dismissRequestedAtMs else -1L
        logEvent(event = event, extra = "dismissCallback=$state dismissElapsedMs=$latency")
        // Re-check the keyguard after a short settle and report even if the poll already wanted to finish.
        handler.postDelayed({ finishAndReport() }, DISMISS_SETTLE_MS)
    }

    private fun finishAndReport() {
        if (reported) return
        val now = SystemClock.elapsedRealtime()
        val elapsed = now - createdAt
        // Give an in-flight KeyguardDismissCallback a short chance to land before the summary line.
        if (dismissState == "requested" && elapsed < DISMISS_WAIT_MS) {
            handler.postDelayed({ finishAndReport() }, 100)
            return
        }
        // Let the window manager finish the keyguard transition before reading keyguardAfter.
        if (dismissCallbackAtMs >= 0 && now - dismissCallbackAtMs < DISMISS_SETTLE_MS) {
            handler.postDelayed({ finishAndReport() }, 100)
            return
        }
        reported = true

        val interactiveAfter = powerManager.isInteractive
        val keyguardAfter = keyguardManager.isKeyguardLocked
        val dismissElapsedMs =
            if (dismissRequestedAtMs >= 0 && dismissCallbackAtMs >= 0) {
                dismissCallbackAtMs - dismissRequestedAtMs
            } else {
                -1L
            }
        Log.i(
            TAG,
            "KEYGUARD_PROBE mode=$mode keyguardBefore=$keyguardBefore secure=$keyguardSecure " +
                "interactiveBefore=$interactiveBefore dismiss=$dismissState keyguardAfter=$keyguardAfter " +
                "elapsedMs=$elapsed trigger=$trigger interactiveAfter=$interactiveAfter " +
                "interactiveElapsedMs=$firstInteractiveAtMs dismissElapsedMs=$dismissElapsedMs " +
                "wakeLockHeld=${wakeLock?.isHeld == true}",
        )
        logEvent(
            event = "finish",
            extra = "interactiveAfter=$interactiveAfter keyguardAfter=$keyguardAfter dismiss=$dismissState",
        )
        releaseWakeLock()
        finish()
    }

    private fun releaseWakeLock() {
        val lock = wakeLock ?: return
        wakeLock = null
        try {
            if (lock.isHeld) lock.release()
            logEvent(event = "wakeLockReleased")
        } catch (t: Throwable) {
            logEvent(event = "wakeLockReleaseFailed", extra = "error=${t.javaClass.simpleName}:${t.message}")
        }
    }

    private fun logEvent(event: String, extra: String = "") {
        Log.i(TAG, "KEYGUARD_PROBE_EVENT mode=$mode event=$event trigger=$trigger $extra".trimEnd())
    }
}
