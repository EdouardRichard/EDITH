package com.sheen.rokid.phone.context

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Process

/**
 * The foreground app as one context signal, published under [KEY] (P3.2 Stage B, Task 11).
 *
 * `PACKAGE_USAGE_STATS` is a **special** permission: it cannot be requested at runtime, only granted by the user in
 * Settings → Special app access → Usage access. So the source is built around two injected questions - "is it granted
 * now?" and "what was resumed last?" - which is what makes the rule below testable without a device.
 *
 * The rule: **an unauthorised or unanswerable query contributes no key at all.** A missing `app.foreground` is a
 * plugin that knows nothing about the foreground app, which is already a case every consumer must handle (a key set
 * with no foreground key); a placeholder (`"unknown"`, an empty string, the App's own package) would be a fact the
 * host made up. The same applies to a throwing query: a broken `UsageStatsManager` must not take the context path, or
 * the proactive turn that reads it, down.
 */
internal class ForegroundAppSource(
    private val query: () -> String?,
    private val granted: () -> Boolean,
) {

    /** Whether the special permission is currently granted; the settings screen's copy reads this. */
    val isGranted: Boolean get() = runCatching { granted() }.getOrDefault(false)

    /** The key and its value, or an empty map when there is nothing true to say. */
    fun values(): Map<String, String> {
        if (!isGranted) return emptyMap()
        val packageName = runCatching { query() }.getOrNull()?.takeIf { it.isNotBlank() } ?: return emptyMap()
        return mapOf(KEY to packageName)
    }

    companion object {
        const val KEY = "app.foreground"

        /**
         * How far back the resumed-activity search looks.
         *
         * Long enough to cover a screen that has been on for a while without a resume (the last resumed package is
         * still the foreground one), short enough that the query stays a small window of events.
         */
        private const val WINDOW_MILLIS = 10 * 60 * 1000L

        /**
         * The platform half: the real `UsageStatsManager` and the real app-op check.
         *
         * `checkOpNoThrow` rather than `unsafeCheckOpNoThrow`: the project's `minSdk` is 28 and the latter is API 29+.
         * The event type is `MOVE_TO_FOREGROUND` for the same reason - on API 29+ `ACTIVITY_RESUMED` is the same
         * constant value, so one name covers every supported release.
         */
        fun android(context: Context): ForegroundAppSource {
            val application = context.applicationContext
            val manager = runCatching {
                application.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            }.getOrNull()
            return ForegroundAppSource(
                query = { manager?.let(::latestResumedPackage) },
                granted = { usageAccessGranted(application) },
            )
        }

        private fun usageAccessGranted(context: Context): Boolean {
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return false
            @Suppress("DEPRECATION")
            val mode = appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
            return mode == AppOpsManager.MODE_ALLOWED
        }

        /** The package of the most recent resumed activity inside the window, or null when there is none. */
        private fun latestResumedPackage(manager: UsageStatsManager): String? {
            val end = System.currentTimeMillis()
            val events = manager.queryEvents(end - WINDOW_MILLIS, end) ?: return null
            val event = UsageEvents.Event()
            var latest: String? = null
            var latestAt = Long.MIN_VALUE
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                if (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND && event.timeStamp >= latestAt) {
                    latest = event.packageName
                    latestAt = event.timeStamp
                }
            }
            return latest
        }
    }
}
