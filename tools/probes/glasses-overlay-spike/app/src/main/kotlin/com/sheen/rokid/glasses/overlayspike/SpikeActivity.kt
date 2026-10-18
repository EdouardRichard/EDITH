package com.sheen.rokid.glasses.overlayspike

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.Log

/**
 * S1 throwaway probe shell trampoline.
 *
 * It does exactly one thing: start [SpikeOverlayService] and finish. The service is the thing under test (foreground
 * lifetime, `SYSTEM_ALERT_WINDOW` overlay, focus, wake lock). Keeping the Activity this thin is what makes the
 * screen-off half of the gate meaningful: whatever lights the panel, it is the Activity-start ⇒ service-start chain,
 * not a long-lived visible Activity.
 *
 * The line logged here is the "before" reading of the screen-off test: `interactive` at the moment the shell
 * trampoline was created, before the service acquires its wake lock.
 */
class SpikeActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val power = getSystemService(PowerManager::class.java)
        Log.i(
            TAG,
            "SPIKE_ACTIVITY onCreate interactive=${power?.isInteractive} " +
                "canDrawOverlays=${Settings.canDrawOverlays(this)} sdk=${Build.VERSION.SDK_INT}",
        )
        val intent = Intent(this, SpikeOverlayService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        finish()
    }

    private companion object {
        const val TAG = "SpikeOverlay"
    }
}
