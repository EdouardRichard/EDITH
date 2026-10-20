package com.sheen.rokid.phone.actions.intent
/**
 * One app the device makes visible to the App.
 *
 * [launchIntentAction] is the action of the package's launch intent, or null when the package has none; the resolver
 * treats "no launch intent" as "not openable" rather than attempting a launch that would fail on the device.
 */
data class AppEntry(val packageName: String, val label: String, val launchIntentAction: String?)

/**
 * The package-manager seam.
 *
 * It exists so the name -> package decision is a pure JVM function: `phone-app`'s unit tests run with
 * `unitTests.isReturnDefaultValues = true`, where a real `PackageManager` returns null for everything, so driving the
 * resolver through `Context` would test nothing.
 */
interface AppCatalog {
    fun visibleApps(): List<AppEntry>
}

/**
 * The three possible outcomes of resolving a spoken app name.
 *
 * Spec §3.8.2 C: ambiguity must return candidates, never silently pick one.
 */
sealed class LaunchResolution {
    data class Found(val target: AppLaunchResolver.LaunchTarget) : LaunchResolution()
    data class Ambiguous(val candidates: List<AppLaunchResolver.LaunchTarget>) : LaunchResolution()
    object NotFound : LaunchResolution()
}

/**
 * Resolves a spoken app name to a [LaunchResolution] describing the outcome.
 *
 * The measured rule this implements ("tools must expose ambiguity instead of heuristically picking"): a launch is
 * irreversible and invisible, so a guess would open the wrong app with no way for the user or the audit trail to tell.
 * Precedence:
 * 1. a blank query resolves to [LaunchResolution.NotFound] (there is no name to match);
 * 2. exactly one openable label equals or contains the query → [LaunchResolution.Found];
 * 3. more than one such label → [LaunchResolution.Ambiguous] with every candidate.
 *
 * An exact label **does not** hide a second app whose label contains the same word. The P4 corpus (`文件`) exposed the
 * difference: `com.google.android.documentsui` is labelled exactly `文件` and `com.android.fileexplorer` is `文件管理`;
 * letting the exact one win silently opened one while the other was a plausible target. A launch is irreversible, so
 * the conservative set is the one the user can choose from. This is a deliberate behavior change from the old
 * exact-first rule (2026-10-20, 0.26 P4).
 */
class AppLaunchResolver(private val catalog: AppCatalog) {
    data class LaunchTarget(val packageName: String, val label: String)

    fun resolve(spokenName: String): LaunchResolution {
        val query = spokenName.trim()
        if (query.isEmpty()) return LaunchResolution.NotFound
        val openable = catalog.visibleApps()
            .filter { !it.launchIntentAction.isNullOrBlank() && it.label.isNotBlank() }
        // Exact and substring candidates are one set on purpose: P4's "文件" has one exact (`文件`) and one near
        // (`文件管理`) target, and silently picking the exact one is exactly the guess the resolver exists to avoid.
        val matches = openable
            .filter { it.label.trim().equals(query, ignoreCase = true) || it.label.contains(query, ignoreCase = true) }
            .distinctBy { it.packageName }
        return when {
            matches.size == 1 -> LaunchResolution.Found(matches.single().toTarget())
            matches.size > 1 -> LaunchResolution.Ambiguous(matches.map { it.toTarget() })
            else -> LaunchResolution.NotFound
        }
    }

    private fun AppEntry.toTarget() = LaunchTarget(packageName, label.trim())
}
