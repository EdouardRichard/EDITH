package edith.phone.actions

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
 * Resolves a spoken app name to the one app it names, or to `null` when that cannot be decided.
 *
 * The measured rule this implements ("tools must expose ambiguity instead of heuristically picking"): a launch is
 * irreversible and invisible, so a guess would open the wrong app with no way for the user or the audit trail to tell.
 * Precedence:
 * 1. a blank query resolves to nothing (there is no name to match);
 * 2. exactly one entry whose label equals the query, case-insensitively and trimmed, wins over any substring match;
 * 3. otherwise exactly one entry whose label contains the query wins;
 * 4. anything else - no match, or two equally good matches - is `null`.
 */
class AppLaunchResolver(private val catalog: AppCatalog) {
    data class LaunchTarget(val packageName: String, val label: String)

    fun resolve(spokenName: String): LaunchTarget? {
        val query = spokenName.trim()
        if (query.isEmpty()) return null
        val openable = catalog.visibleApps()
            .filter { !it.launchIntentAction.isNullOrBlank() && it.label.isNotBlank() }
        val exact = openable.filter { it.label.trim().equals(query, ignoreCase = true) }
        if (exact.size == 1) return exact.single().toTarget()
        // Two apps sharing a label is ambiguity, not a tie to break arbitrarily.
        if (exact.size > 1) return null
        val partial = openable.filter { it.label.contains(query, ignoreCase = true) }
        return if (partial.size == 1) partial.single().toTarget() else null
    }

    private fun AppEntry.toTarget() = LaunchTarget(packageName, label.trim())
}
