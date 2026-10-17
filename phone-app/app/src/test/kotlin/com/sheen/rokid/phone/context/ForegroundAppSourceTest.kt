package com.sheen.rokid.phone.context

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Task 11: the foreground app as one context key.
 *
 * The rule the whole source exists to keep is the **degradation** rule (spec §16.1#1, plan Task 11): an unauthorised
 * `PACKAGE_USAGE_STATS` or a query that cannot answer must leave the key **absent**, never invent a placeholder. A
 * missing key is a plugin that sees no foreground app; a placeholder would be a plugin acting on a lie.
 */
class ForegroundAppSourceTest {

    @Test
    fun aGrantedQueryNamesTheForegroundApp() {
        val source = ForegroundAppSource(query = { "com.example" }, granted = { true })

        assertEquals(mapOf("app.foreground" to "com.example"), source.values())
        assertTrue(source.isGranted)
    }

    @Test
    fun withoutThePermissionTheKeyIsAbsentRatherThanAPlaceholder() {
        val source = ForegroundAppSource(query = { "com.example" }, granted = { false })

        assertTrue("an ungranted source must add no key at all", source.values().isEmpty())
        assertFalse(source.isGranted)
    }

    @Test
    fun aQueryThatCannotAnswerAddsNoKey() {
        assertTrue(ForegroundAppSource(query = { null }, granted = { true }).values().isEmpty())
        assertTrue(ForegroundAppSource(query = { "   " }, granted = { true }).values().isEmpty())
        assertTrue(
            "a throwing query is a missing value, never a crash in the context path",
            ForegroundAppSource(query = { error("no usage stats") }, granted = { true }).values().isEmpty(),
        )
    }

    @Test
    fun aThrowingPermissionCheckIsARefusal() {
        val source = ForegroundAppSource(query = { "com.example" }, granted = { throw SecurityException("denied") })

        assertTrue(source.values().isEmpty())
        assertFalse(source.isGranted)
    }
}
