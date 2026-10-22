package com.sheen.rokid.glasses

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.sheen.rokid.glasses.app.GlassesActivity
import com.sheen.rokid.glasses.app.GlassesPanelHostActivity

/**
 * 0.24 S2 Task 9: launches the Activity in its **debug-only** legacy panel-host mode.
 *
 * Production/CXR `appStart` starts [GlassesActivity] with no extra, which is the transparent CXR scene that starts
 * `GlassesOverlayService`. The existing view/key/interaction tests need a real full-screen opaque Activity window to
 * assert on, and that window is exactly what [GlassesPanelHostActivity] (the extra's dedicated component, Task 21
 * follow-up) keeps available: it inherits the whole host implementation from [GlassesActivity] and is declared with
 * the original `Theme.Material.NoActionBar`, not the translucent scene theme. The extra is honoured only in a
 * `BuildConfig.DEBUG` build and only when explicitly set, so it cannot leak into the production entry path.
 */
fun launchHostPanel(): ActivityScenario<GlassesActivity> {
    val intent = Intent(ApplicationProvider.getApplicationContext(), GlassesPanelHostActivity::class.java)
        .putExtra(GlassesActivity.EXTRA_HOST_PANEL, true)
    return ActivityScenario.launch<GlassesActivity>(intent)
}
