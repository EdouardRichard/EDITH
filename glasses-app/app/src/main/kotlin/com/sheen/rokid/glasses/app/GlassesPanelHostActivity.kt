package com.sheen.rokid.glasses.app

/**
 * 0.24 S5 Task 21 follow-up: the debug-only Activity panel host component.
 *
 * [GlassesActivity] is the production CXR entry and is declared with the translucent `Theme.Edith.CxrScene` so the
 * launcher shows through while no overlay panel is displayed. The instrumentation suite's `EXTRA_HOST_PANEL` seam was
 * written against the old full-screen **opaque** Activity window, so it launches this component instead: it inherits
 * the whole host implementation (view tree, controller, key routing, probes) from [GlassesActivity], and the manifest
 * declares it with the original `Theme.Material.NoActionBar`.
 *
 * This component is `exported=false` and has no intent filter; CXR `appStart` names
 * [GlassesActivity][com.sheen.rokid.glasses.app.GlassesActivity], so this Activity can never be the production scene.
 */
class GlassesPanelHostActivity : GlassesActivity()
