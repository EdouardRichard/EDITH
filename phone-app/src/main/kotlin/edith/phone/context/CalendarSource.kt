package edith.phone.context

import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import java.util.concurrent.TimeUnit

/**
 * Today's calendar **titles** as one context signal, published under [KEY] (P3.2 Stage B, Task 13).
 *
 * `READ_CALENDAR` is a runtime permission, so unlike Task 11's special access this one can be asked for from the UI;
 * the source still takes the two questions as lambdas, because "granted?" and "what is on today?" are the only things
 * a test needs to control.
 *
 * Titles are user content and are treated as such: at most [MAX_TITLES] of them, joined with `|` for the flat signal
 * map, never logged (the host's `P3_CONTEXT` line carries key names only) and never persisted. Without the permission
 * the key is absent, and a day with nothing in it is also absent - an empty value would be indistinguishable from a
 * permission that was never granted.
 */
internal class CalendarSource(
    private val granted: () -> Boolean,
    private val today: () -> List<String>,
) {

    val isGranted: Boolean get() = runCatching { granted() }.getOrDefault(false)

    fun values(): Map<String, String> {
        if (!isGranted) return emptyMap()
        val titles = runCatching { today() }.getOrNull()
            ?.map { it.trim() }?.filter { it.isNotEmpty() }?.take(MAX_TITLES)
            ?: return emptyMap()
        if (titles.isEmpty()) return emptyMap()
        return mapOf(KEY to titles.joinToString("|"))
    }

    companion object {
        const val KEY = "calendar.todayTitles"

        /** How many titles one signal may carry; the prompt does not need a day's whole agenda. */
        const val MAX_TITLES = 5

        /** The platform half: the real permission check and a `CalendarContract.Instances` query for today. */
        fun android(context: Context): CalendarSource {
            val application = context.applicationContext
            return CalendarSource(
                granted = {
                    ContextCompat.checkSelfPermission(application, android.Manifest.permission.READ_CALENDAR) ==
                        PackageManager.PERMISSION_GRANTED
                },
                today = { titlesToday(application, System.currentTimeMillis()) },
            )
        }

        /**
         * Today's event titles, capped at [MAX_TITLES].
         *
         * `Instances` rather than `Events` so an occurrence inside today is what counts, not the series' start. A
         * provider that refuses (no permission) or throws leaves the key absent, which is the same honest answer.
         */
        private fun titlesToday(context: Context, nowMillis: Long): List<String> {
            val builder = CalendarContract.Instances.CONTENT_URI.buildUpon()
            ContentUris.appendId(builder, nowMillis)
            ContentUris.appendId(builder, nowMillis + TimeUnit.DAYS.toMillis(1) - 1)
            val cursor = context.contentResolver.query(builder.build(),
                arrayOf(CalendarContract.Instances.TITLE), null, null,
                CalendarContract.Instances.BEGIN + " ASC") ?: return emptyList()
            return cursor.use { rows ->
                buildList {
                    while (rows.moveToNext() && size < MAX_TITLES) {
                        rows.getString(0)?.let { add(it) }
                    }
                }
            }
        }
    }
}
