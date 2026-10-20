package com.sheen.rokid.phone.context

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Task 13: today's calendar titles as one context key.
 *
 * Titles are user content, so this source is deliberately narrow: at most [CalendarSource.MAX_TITLES] of them, joined
 * with `|`, never logged, and the key is **absent** without `READ_CALENDAR`. A refused or throwing query contributes
 * nothing rather than an empty placeholder - a plugin must be able to tell "no permission" from "an empty day".
 *
 * edith-0.3 Phase 2 Task 4 makes that distinction explicit: [CalendarSource.snapshot] is `available=false` only for a
 * refused/absent permission or a throwing provider, while a granted day with no events is `available=true` with no
 * titles. [CalendarSource.values] remains the old flat map and is derived from the snapshot.
 */
class CalendarSourceTest {

    @Test
    fun withThePermissionTodaysTitlesAreJoinedIntoOneValue() {
        val source = CalendarSource(granted = { true }, today = { listOf("站会", "体检") })

        assertEquals(mapOf("calendar.todayTitles" to "站会|体检"), source.values())
    }

    @Test
    fun withoutThePermissionTheSnapshotIsUnavailableAndTheKeyIsAbsent() {
        val source = CalendarSource(granted = { false }, today = { listOf("站会") })

        assertEquals(CalendarSnapshot(available = false, titles = emptyList()), source.snapshot())
        assertTrue(source.values().isEmpty())
        assertFalse(source.isGranted)
    }

    @Test
    fun aGrantedEmptyDayIsAvailableWithNoTitlesButStillAddsNoKey() {
        val source = CalendarSource(granted = { true }, today = { emptyList() })

        assertEquals(
            "an empty day is a fact, not a missing permission",
            CalendarSnapshot(available = true, titles = emptyList()),
            source.snapshot(),
        )
        assertTrue("the flat map still omits an empty day", source.values().isEmpty())
    }

    @Test
    fun aThrowingProviderIsUnavailableRatherThanAnEmptyDay() {
        val source = CalendarSource(granted = { true }, today = { error("provider died") })

        assertEquals(CalendarSnapshot(available = false, titles = emptyList()), source.snapshot())
        assertTrue(source.values().isEmpty())
    }

    @Test
    fun aThrowingPermissionCheckIsUnavailable() {
        val source = CalendarSource(granted = { error("permission service died") }, today = { listOf("站会") })

        assertEquals(CalendarSnapshot(available = false, titles = emptyList()), source.snapshot())
        assertFalse(source.isGranted)
    }

    @Test
    fun aNullProviderCursorFailsTheSnapshotInsteadOfReadingAsAnEmptyDay() {
        // R8: the platform's ContentResolver.query can answer null (dead provider / refused binder). That is a
        // provider failure, not "granted with no events", so the read throws and the snapshot stays unavailable.
        val source = CalendarSource(granted = { true }, today = { CalendarSource.titlesFrom(null) })

        assertEquals(CalendarSnapshot(available = false, titles = emptyList()), source.snapshot())
        assertTrue("the flat map still omits a failed provider", source.values().isEmpty())
    }

    @Test
    fun atMostFiveTitlesAreCarried() {
        val source = CalendarSource(granted = { true },
            today = { listOf("一", "二", "三", "四", "五", "六", "七") })

        assertEquals(CalendarSnapshot(available = true, titles = listOf("一", "二", "三", "四", "五")),
            source.snapshot())
        assertEquals(mapOf("calendar.todayTitles" to "一|二|三|四|五"), source.values())
    }

    @Test
    fun titlesAreTrimmedAndBlankOnesDropped() {
        val source = CalendarSource(granted = { true }, today = { listOf("", "站会", "   ", " 体检 ") })

        assertEquals(CalendarSnapshot(available = true, titles = listOf("站会", "体检")), source.snapshot())
        assertEquals(mapOf("calendar.todayTitles" to "站会|体检"), source.values())
    }

    @Test
    fun blankTitlesAreDroppedAndAnEmptyDayAddsNoKey() {
        assertEquals(mapOf("calendar.todayTitles" to "站会|体检"),
            CalendarSource(granted = { true }, today = { listOf("", "站会", "   ", "体检") }).values())
        assertTrue(CalendarSource(granted = { true }, today = { emptyList() }).values().isEmpty())
        assertTrue(
            "a throwing provider is a missing value, never a crash in the context path",
            CalendarSource(granted = { true }, today = { error("provider died") }).values().isEmpty(),
        )
    }
}
