package com.sheen.rokid.phone.actions.intent
import com.sheen.rokid.phone.storage.unavailable
import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * The one Android touch point of the L0/L1 action layer.
 *
 * Keeping `startActivity`/`PackageManager` behind these two seams is what lets the executor be tested on the JVM:
 * `phone-app`'s unit tests run with `unitTests.isReturnDefaultValues = true`, so real `Context`/`PackageManager`
 * calls return defaults and would prove nothing about the decision being tested.
 *
 * The contract between the executor and an implementation:
 * - [action] is set as the intent action;
 * - [data] is either a URI set as the intent's data (an `http(s)` link for `open_url`) or a `package:<name>` URI
 *   addressing the app to launch - the shape `Uri.fromParts("package", name, null)` produces - which an implementation
 *   turns into `Intent.setPackage`;
 * - [extras] are plain intent extras (the alarm fields for `set_alarm`);
 * - implementations own the policy bits the caller cannot express: `ACTION_MAIN` also gets
 *   `CATEGORY_LAUNCHER`, and every intent gets `FLAG_ACTIVITY_NEW_TASK` because the App starts them from its
 *   application context, never from an Activity.
 */
interface IntentLauncher {
    /** true when an activity accepted the intent. */
    fun launch(action: String, data: String?, extras: Map<String, Any>): Boolean
}

/** The `package:<name>` data-URI scheme `open_app` addresses its target with. */
internal const val PACKAGE_URI_SCHEME = "package:"

/**
 * The production [IntentLauncher]: builds the platform intent and starts it.
 *
 * A refusal anywhere (no activity for the intent, a security exception from the system) is a `false`, never a throw:
 * the executor turns `false` into `Refused("LAUNCH_REFUSED")`, which is the honest answer to "the platform would not
 * take this intent".
 */
class ContextIntentLauncher(private val context: Context) : IntentLauncher {
    override fun launch(action: String, data: String?, extras: Map<String, Any>): Boolean = try {
        val intent = Intent(action)
        if (action == Intent.ACTION_MAIN) intent.addCategory(Intent.CATEGORY_LAUNCHER)
        when {
            data == null -> Unit
            data.startsWith(PACKAGE_URI_SCHEME) -> intent.setPackage(data.removePrefix(PACKAGE_URI_SCHEME))
            else -> intent.data = Uri.parse(data)
        }
        extras.forEach { (key, value) -> putExtra(intent, key, value) }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        true
    } catch (refused: Exception) {
        android.util.Log.i("EdithActions",
            "LAUNCH_REFUSED: $action ${refused.javaClass.simpleName}: ${refused.message}")
        false
    }

    private fun putExtra(intent: Intent, key: String, value: Any) {
        when (value) {
            is Int -> intent.putExtra(key, value)
            is Long -> intent.putExtra(key, value)
            is Boolean -> intent.putExtra(key, value)
            is String -> intent.putExtra(key, value)
            else -> intent.putExtra(key, value.toString())
        }
    }
}

/**
 * The production [AppCatalog] over `PackageManager`.
 *
 * API 30+ package visibility is why the manifest declares `QUERY_ALL_PACKAGES`. The K40 still returned **one**
 * installed application (`getInstalledApplications(0)` = 1, `MATCH_ALL` = 1) while the launcher activity query returned
 * 31, so the filter was the API choice, not the permission: **enumerate the launchable set with
 * `queryIntentActivities`, not `getInstalledApplications`.** This is the same live set the shell's `open(list)` sees,
 * and it is what makes a spoken app name resolvable at all (P1/T1/T4/P4).
 *
 * A failure to enumerate is an empty catalog (the resolver then refuses), never a throw: the package manager is
 * device state, and a device without a usable package manager must produce an honest refusal.
 */
class PackageManagerAppCatalog(private val context: Context) : AppCatalog {
    override fun visibleApps(): List<AppEntry> = try {
        val manager = context.packageManager
        if (manager == null) emptyList() else {
            val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            manager.queryIntentActivities(launcherIntent, 0).mapNotNull { resolveInfo ->
                // One unreadable package must not hide the rest of the catalog.
                try {
                    val packageName = resolveInfo.activityInfo?.packageName ?: return@mapNotNull null
                    AppEntry(
                        packageName = packageName,
                        label = resolveInfo.loadLabel(manager)?.toString()?.takeIf { it.isNotBlank() } ?: packageName,
                        // The resolver only needs "has a launch intent"; the launcher query already proved that.
                        launchIntentAction = LAUNCH_MARKER,
                    )
                } catch (unreadable: Exception) {
                    android.util.Log.i("EdithActions",
                        "APP_SKIPPED: ${resolveInfo.activityInfo?.packageName} ${unreadable.javaClass.simpleName}")
                    null
                }
            }.distinctBy { it.packageName }
        }
    } catch (unavailable: Exception) {
        android.util.Log.i("EdithActions",
            "APP_CATALOG_UNAVAILABLE: ${unavailable.javaClass.simpleName}: ${unavailable.message}")
        emptyList()
    }

    /**
     * Sentinel value for "has a launch intent but its action is null". Non-blank so the resolver's
     * `isNullOrBlank` filter keeps the entry; distinct from any real action string.
     */
    private companion object { const val LAUNCH_MARKER = "__has_launch_intent__" }
}
