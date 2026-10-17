package com.sheen.rokid.phone.actions
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
 * 2. exactly one entry whose label equals the query, case-insensitively and trimmed, wins over any substring match →
 *    [LaunchResolution.Found];
 * 3. more than one exact match → [LaunchResolution.Ambiguous] with all exact candidates;
 * 4. exactly one entry whose label contains the query → [LaunchResolution.Found];
 * 5. more than one partial match → [LaunchResolution.Ambiguous] with all partial candidates;
 * 6. anything else → [LaunchResolution.NotFound].
 */
class AppLaunchResolver(private val catalog: AppCatalog) {
    data class LaunchTarget(val packageName: String, val label: String)

    fun resolve(spokenName: String): LaunchResolution {
        val query = spokenName.trim()
        if (query.isEmpty()) return LaunchResolution.NotFound
        val openable = catalog.visibleApps()
            .filter { !it.launchIntentAction.isNullOrBlank() && it.label.isNotBlank() }
        val exact = openable.filter { it.label.trim().equals(query, ignoreCase = true) }
        if (exact.size == 1) return LaunchResolution.Found(exact.single().toTarget())
        // Two apps sharing a label is ambiguity, not a tie to break arbitrarily.
        if (exact.size > 1) return LaunchResolution.Ambiguous(exact.map { it.toTarget() })
        val partial = openable.filter { it.label.contains(query, ignoreCase = true) }
        return when {
            partial.size == 1 -> LaunchResolution.Found(partial.single().toTarget())
            partial.size > 1 -> LaunchResolution.Ambiguous(partial.map { it.toTarget() })
            else -> LaunchResolution.NotFound
        }
    }

    private fun AppEntry.toTarget() = LaunchTarget(packageName, label.trim())
}
