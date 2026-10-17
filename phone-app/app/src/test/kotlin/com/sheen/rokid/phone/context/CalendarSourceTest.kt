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
 */
class CalendarSourceTest {

    @Test
    fun withThePermissionTodaysTitlesAreJoinedIntoOneValue() {
        val source = CalendarSource(granted = { true }, today = { listOf("站会", "体检") })

        assertEquals(mapOf("calendar.todayTitles" to "站会|体检"), source.values())
    }

    @Test
    fun withoutThePermissionTheKeyIsAbsent() {
        val source = CalendarSource(granted = { false }, today = { listOf("站会") })

        assertTrue(source.values().isEmpty())
        assertFalse(source.isGranted)
    }

    @Test
    fun atMostFiveTitlesAreCarried() {
        val source = CalendarSource(granted = { true },
            today = { listOf("一", "二", "三", "四", "五", "六", "七") })

        assertEquals(mapOf("calendar.todayTitles" to "一|二|三|四|五"), source.values())
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
