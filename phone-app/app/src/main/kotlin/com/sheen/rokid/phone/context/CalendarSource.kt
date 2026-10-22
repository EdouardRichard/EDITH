package com.sheen.rokid.phone.context

import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import java.util.concurrent.TimeUnit

/**
 * What today's calendar can say (edith-0.3 Phase 2 Task 4; spec §7.2).
 *
 * [available] is the permission/provider question and [titles] is the content question, deliberately kept apart: a
 * granted day with no events is `available=true, titles=[]` (`calendar=empty` downstream), while a refused permission
 * or a throwing provider is `available=false` (`calendar=unavailable`). The two must not collapse into one empty answer.
 */
internal data class CalendarSnapshot(val available: Boolean, val titles: List<String>)

/**
 * Today's calendar **titles** as one context signal, published under [KEY] (P3.2 Stage B, Task 13).
 *
 * `READ_CALENDAR` is a runtime permission, so unlike Task 11's special access this one can be asked for from the UI;
 * the source still takes the two questions as lambdas, because "granted?" and "what is on today?" are the only things
 * a test needs to control.
 *
 * Titles are user content and are treated as such: at most [MAX_TITLES] of them, joined with `|` for the flat signal
 * map, never logged (the host's `P3_CONTEXT` line carries key names only) and never persisted. [values] keeps the
 * old flat-map contract for existing consumers and is derived from [snapshot], so the two can never disagree. Without
 * the permission the key is absent, and a day with nothing in it is also absent - an empty value would be
 * indistinguishable from a permission that was never granted. [snapshot] is where the two cases stay distinguishable
 * for the perception path (edith-0.3 Phase 2 Task 4).
 */
internal class CalendarSource(
    private val granted: () -> Boolean,
    private val today: () -> List<String>,
) {

    val isGranted: Boolean get() = runCatching { granted() }.getOrDefault(false)

    /** The permission answer and today's (trimmed, blank-free, capped) titles, without losing "empty" vs "unavailable". */
    fun snapshot(): CalendarSnapshot {
        if (!isGranted) return CalendarSnapshot(available = false, titles = emptyList())
        val titles = runCatching { today() }.getOrNull()
            ?: return CalendarSnapshot(available = false, titles = emptyList())
        return CalendarSnapshot(
            available = true,
            titles = titles.map { it.trim() }.filter { it.isNotEmpty() }.take(MAX_TITLES),
        )
    }

    fun values(): Map<String, String> {
        val snapshot = snapshot()
        if (!snapshot.available || snapshot.titles.isEmpty()) return emptyMap()
        return mapOf(KEY to snapshot.titles.joinToString("|"))
    }

    companion object {
        const val KEY = "calendar.todayTitles"

        /** How many titles one signal may carry; the prompt does not need a day's whole agenda. */
        const val MAX_TITLES = 5

        /** The platform half: the real permission check and a `CalendarContract.Instances` query for today. */
        fun android(context: Context): CalendarSource {
            val application = context.applicationContext ?: context
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
         * provider that refuses (no permission) or throws leaves the snapshot unavailable, which is the same honest
         * answer as a missing permission. A provider that answers **no cursor at all** is a failure, not an empty day
         * (R8): [titlesFrom] throws, so [snapshot] reports `available=false` instead of inventing a free day.
         */
        private fun titlesToday(context: Context, nowMillis: Long): List<String> {
            val builder = CalendarContract.Instances.CONTENT_URI.buildUpon()
            ContentUris.appendId(builder, nowMillis)
            ContentUris.appendId(builder, nowMillis + TimeUnit.DAYS.toMillis(1) - 1)
            return titlesFrom(
                context.contentResolver.query(
                    builder.build(),
                    arrayOf(CalendarContract.Instances.TITLE),
                    null,
                    null,
                    CalendarContract.Instances.BEGIN + " ASC",
                ),
            )
        }

        /**
         * Reads today's titles from an already-issued cursor.
         *
         * A null cursor means the content provider failed (dead provider, refused binder, revoked grant); throwing is
         * what lets [snapshot] keep "unavailable" apart from a granted day with no events (R8). Extracted from
         * [titlesToday] so this rule is JVM-testable without an Android `ContentResolver`.
         */
        internal fun titlesFrom(cursor: android.database.Cursor?): List<String> {
            val rows = cursor
                ?: throw IllegalStateException("calendar provider returned no cursor")
            return rows.use { cursorRows ->
                buildList {
                    while (cursorRows.moveToNext() && size < MAX_TITLES) {
                        cursorRows.getString(0)?.let { add(it) }
                    }
                }
            }
        }
    }
}
