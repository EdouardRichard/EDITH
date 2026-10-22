package com.sheen.rokid.phone.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.sheen.rokid.phone.app.EdithApplication

/**
 * Debug-only 「语音朗读」 switch driver (0.23 P5 evidence).
 *
 * The 0.23 P5 wake evidence needs the switch both ways: `P5_CHIME state=disabled` with it off, `state=played` with it
 * on. Flipping it through the settings tab would need a scroll gesture, and the phone-side rule is that every
 * human-operation simulation goes through `edith.phone.a11yprobe` (D95); a debug receiver that calls the **production**
 * `HostCompositionRoot.setSpeechEnabled` is the smaller, auditable alternative and exercises the same persisted switch.
 *
 * It exists **only in the debug source set** (see `src/debug/AndroidManifest.xml`) and is exported so `am broadcast`
 * can reach it; a release build contains no receiver.
 *
 * ```
 * adb -s 2696813b shell am broadcast -a edith.DEBUG_SPEECH \
 *   -n com.sheen.rokid.phone/.debug.DebugSpeechToggle --ez enabled true
 * ```
 *
 * It logs the requested and committed boolean only - never any audio, transcript or credential.
 */
class DebugSpeechToggle : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        if (!intent.hasExtra(EXTRA_ENABLED)) {
            android.util.Log.i("EdithP5", "P5_SPEECH_TOGGLE refused reason=MISSING_ENABLED")
            return
        }
        val enabled = intent.getBooleanExtra(EXTRA_ENABLED, false)
        val application = context.applicationContext as? EdithApplication
        val root = application?.liveComposition
        if (root == null) {
            android.util.Log.i("EdithP5", "P5_SPEECH_TOGGLE refused reason=NO_COMPOSITION")
            return
        }
        runCatching { root.setSpeechEnabled(enabled) }
            .onFailure { android.util.Log.i("EdithP5", "P5_SPEECH_TOGGLE failed reason=${it.javaClass.simpleName}") }
        android.util.Log.i("EdithP5",
            "P5_SPEECH_TOGGLE enabled=$enabled speech=${root.speechEnabled.value}")
    }

    companion object {
        const val ACTION = "edith.DEBUG_SPEECH"
        const val EXTRA_ENABLED = "enabled"
    }
}
