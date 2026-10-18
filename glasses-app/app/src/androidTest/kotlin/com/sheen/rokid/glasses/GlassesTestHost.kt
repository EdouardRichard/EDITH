package com.sheen.rokid.glasses

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.sheen.rokid.glasses.app.GlassesActivity

/**
 * 0.24 S2 Task 9: launches the Activity in its **debug-only** legacy panel-host mode.
 *
 * Production/CXR `appStart` starts [GlassesActivity] with no extra, which is now a trampoline that starts
 * `GlassesOverlayService` and finishes. The existing view/key/interaction tests need a real Activity window to
 * assert on, and that window is exactly what this extra keeps available. The extra is honoured only in a
 * `BuildConfig.DEBUG` build and only when explicitly set, so it cannot leak into the production entry path.
 */
fun launchHostPanel(): ActivityScenario<GlassesActivity> {
    val intent = Intent(ApplicationProvider.getApplicationContext(), GlassesActivity::class.java)
        .putExtra(GlassesActivity.EXTRA_HOST_PANEL, true)
    return ActivityScenario.launch<GlassesActivity>(intent)
}
