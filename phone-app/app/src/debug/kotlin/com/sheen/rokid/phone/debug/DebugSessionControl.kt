package com.sheen.rokid.phone.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.sheen.rokid.phone.app.EdithApplication
import com.sheen.rokid.phone.app.HostCompositionRoot

/**
 * Debug-only session start/stop instrument (F-01, 2026-10-20).
 *
 * The field acceptance ran **generations 3 and 4** in one process and measured a 2x PCM byte rate. The candidate
 * mechanism is that each session re-arm installs another client audio callback, so the same chunk reaches the
 * adapter twice. That can only be tested by starting a second session **without** restarting the process, which the
 * UI path did not let a shell drive (the drawer's buttons refuse the probe's click).
 *
 * `start`/`stop` are the composition's own production methods, not a copy: the same path the 「开始」 button takes.
 * Debug source set only; it logs the action and the resulting session state, nothing else.
 */
class DebugSessionControl : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_CONTROL) return
        val application = context.applicationContext as? EdithApplication
        val composition = application?.composition
        val action = intent.getStringExtra(EXTRA_ACTION)
        if (composition == null || action == null) {
            android.util.Log.i("EdithP3", "P3_SESSION_CONTROL state=refused reason=NO_COMPOSITION")
            return
        }
        when (action) {
            ACTION_START -> composition.start(intent.getStringExtra(EXTRA_PLUGIN) ?: DEFAULT_PLUGIN)
            ACTION_STOP -> composition.stop()
            ACTION_PAUSE -> composition.pause()
            else -> {
                android.util.Log.i("EdithP3", "P3_SESSION_CONTROL state=refused reason=UNKNOWN_ACTION")
                return
            }
        }
        android.util.Log.i("EdithP3", "P3_SESSION_CONTROL action=$action state=${composition.state.value.session}")
    }

    companion object {
        const val ACTION_CONTROL = "edith.DEBUG_SESSION"
        const val EXTRA_ACTION = "action"
        const val EXTRA_PLUGIN = "plugin"
        const val ACTION_START = "start"
        const val ACTION_STOP = "stop"
        const val ACTION_PAUSE = "pause"
        private const val DEFAULT_PLUGIN = "smart-assistant"
    }
}
