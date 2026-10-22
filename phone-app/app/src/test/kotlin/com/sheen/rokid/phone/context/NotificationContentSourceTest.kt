package com.sheen.rokid.phone.context

import android.app.Notification
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Task 3 (0.3 Phase 2): notification content captured for the perception pipeline.
 *
 * D179 supersedes the old category-only rule: title/text/subText/bigText and action labels are now part of what the
 * assistant may perceive. What this test pins is not "content is never read" but "the mapping is exact, absent values
 * are not invented, no action intent/PendingIntent is exposed, and the facade stays honest when access or the binder is
 * gone". The service that owns the ring adds no log line at all, so no content can reach a log from this path.
 */
class NotificationContentSourceTest {

    @Test
    fun `the extras keys are the platform keys`() {
        assertEquals(Notification.EXTRA_TITLE, NotificationContentMapper.KEY_TITLE)
        assertEquals(Notification.EXTRA_TEXT, NotificationContentMapper.KEY_TEXT)
        assertEquals(Notification.EXTRA_SUB_TEXT, NotificationContentMapper.KEY_SUB_TEXT)
        assertEquals(Notification.EXTRA_BIG_TEXT, NotificationContentMapper.KEY_BIG_TEXT)
    }

    @Test
    fun `a posted notification maps every content field`() {
        val content = NotificationContentMapper.map(
            packageName = "com.synthetic.mail",
            appLabel = "Synthetic Mail",
            extras = mapOf(
                NotificationContentMapper.KEY_TITLE to "Synthetic subject",
                NotificationContentMapper.KEY_TEXT to "Synthetic body",
                NotificationContentMapper.KEY_SUB_TEXT to "Synthetic subtext",
                NotificationContentMapper.KEY_BIG_TEXT to "Synthetic big body",
            ),
            category = "msg",
            postedAtEpochMillis = 1_700_000_000_000L,
            ongoing = true,
            actionTitles = listOf("Reply", "Archive"),
        )

        assertEquals(
            NotificationContent(
                packageName = "com.synthetic.mail",
                appLabel = "Synthetic Mail",
                title = "Synthetic subject",
                text = "Synthetic body",
                subText = "Synthetic subtext",
                bigText = "Synthetic big body",
                category = "msg",
                postedAtEpochMillis = 1_700_000_000_000L,
                ongoing = true,
                actionLabels = listOf("Reply", "Archive"),
            ),
            content,
        )
    }

    @Test
    fun `absent and unrelated extras are not invented into content`() {
        val content = NotificationContentMapper.map(
            packageName = "com.synthetic.calendar",
            appLabel = null,
            extras = mapOf(
                "android.progress" to "42",
                NotificationContentMapper.KEY_TITLE to null,
            ),
            category = null,
            postedAtEpochMillis = 0L,
            ongoing = false,
            actionTitles = emptyList(),
        )

        assertNull(content.title)
        assertNull(content.text)
        assertNull(content.subText)
        assertNull(content.bigText)
        assertNull(content.appLabel)
        assertNull(content.category)
        assertTrue(content.actionLabels.isEmpty())
    }

    @Test
    fun `blank content is an absence, not an empty fact`() {
        val content = NotificationContentMapper.map(
            packageName = "com.synthetic.blank",
            appLabel = "   ",
            extras = mapOf(
                NotificationContentMapper.KEY_TITLE to "   ",
                NotificationContentMapper.KEY_TEXT to "",
                NotificationContentMapper.KEY_SUB_TEXT to "\n\t",
                NotificationContentMapper.KEY_BIG_TEXT to null,
            ),
            category = "  ",
            postedAtEpochMillis = 12L,
            ongoing = false,
            actionTitles = emptyList(),
        )

        assertNull(content.appLabel)
        assertNull(content.title)
        assertNull(content.text)
        assertNull(content.subText)
        assertNull(content.bigText)
        assertNull(content.category)
        assertEquals(12L, content.postedAtEpochMillis)
    }

    @Test
    fun `action labels are trimmed and blank actions are dropped`() {
        val content = NotificationContentMapper.map(
            packageName = "com.synthetic.mail",
            appLabel = "Synthetic Mail",
            extras = emptyMap(),
            category = "email",
            postedAtEpochMillis = 1L,
            ongoing = false,
            actionTitles = listOf(" Reply ", "   ", "", "\tArchive\n"),
        )

        assertEquals(listOf("Reply", "Archive"), content.actionLabels)
    }

    @Test
    fun `recent keeps the producer order and asks for exactly the requested limit`() {
        val newest = content("com.synthetic.newest", 3L)
        val middle = content("com.synthetic.middle", 2L)
        val oldest = content("com.synthetic.oldest", 1L)
        val requested = mutableListOf<Int>()
        val source = NotificationContentSource(
            connected = { true },
            recent = { limit ->
                requested += limit
                listOf(newest, middle, oldest)
            },
            category = { "msg" },
        )

        assertEquals(listOf(newest, middle), source.recent(2))
        assertEquals(listOf(2), requested)
        assertTrue(source.isConnected())
    }

    @Test
    fun `a non-positive limit asks for nothing`() {
        var calls = 0
        val source = NotificationContentSource(
            connected = { true },
            recent = {
                calls += 1
                listOf(content("com.synthetic.one", 1L))
            },
            category = { null },
        )

        assertTrue(source.recent(0).isEmpty())
        assertTrue(source.recent(-5).isEmpty())
        assertEquals(0, calls)
    }

    @Test
    fun `recent is bounded by the sixteen-entry ring`() {
        var requested = -1
        val many = (1..20).map { content("com.synthetic.$it", it.toLong()) }
        val source = NotificationContentSource(
            connected = { true },
            recent = { limit ->
                requested = limit
                many
            },
            category = { null },
        )

        val recent = source.recent(50)

        assertEquals(16, requested)
        assertEquals(16, recent.size)
        assertEquals(many.take(16), recent)
    }

    @Test
    fun `a producer that ignores the limit cannot widen it`() {
        val many = (1..5).map { content("com.synthetic.$it", it.toLong()) }
        val source = NotificationContentSource(
            connected = { true },
            recent = { many },
            category = { null },
        )

        assertEquals(many.take(2), source.recent(2))
    }

    @Test
    fun `read tells a failed producer apart from an empty notification list`() {
        // Final review I3: a producer failure must become `unavailable(provider)`, not `empty`. The read result carries
        // the availability fact so the builder can report it instead of guessing from an empty list.
        val throwing = NotificationContentSource(
            connected = { true },
            recent = { error("dead binder") },
            category = { null },
        )
        val failed = throwing.read(3)
        assertFalse("a failed producer is not available", failed.available)
        assertTrue(failed.items.isEmpty())

        val disconnected = NotificationContentSource(
            connected = { false },
            recent = { error("must not be called without the grant") },
            category = { null },
        )
        val noAccess = disconnected.read(3)
        assertFalse("no listener grant is not an available read", noAccess.available)
        assertTrue(noAccess.items.isEmpty())

        val empty = NotificationContentSource(
            connected = { true },
            recent = { emptyList() },
            category = { null },
        )
        val emptyRead = empty.read(3)
        assertTrue("a working producer with nothing to report is available", emptyRead.available)
        assertTrue(emptyRead.items.isEmpty())
    }

    @Test
    fun `without the listener grant there is no content and no category`() {
        var calls = 0
        val source = NotificationContentSource(
            connected = { false },
            recent = {
                calls += 1
                listOf(content("com.synthetic.stale", 1L))
            },
            category = { "msg" },
        )

        assertFalse(source.isConnected())
        assertTrue(source.recent(3).isEmpty())
        assertNull(source.latestCategory())
        assertEquals(0, calls)
    }

    @Test
    fun `a throwing producer degrades to unavailable instead of crashing`() {
        val throwing = NotificationContentSource(
            connected = { true },
            recent = { error("dead binder") },
            category = { error("dead binder") },
        )

        assertTrue(throwing.recent(3).isEmpty())
        assertNull(throwing.latestCategory())

        val throwingConnection = NotificationContentSource(
            connected = { error("dead binder") },
            recent = { emptyList() },
            category = { null },
        )
        assertFalse(throwingConnection.isConnected())
        assertTrue(throwingConnection.recent(3).isEmpty())
    }

    @Test
    fun `the latest category is trimmed but never invented`() {
        val source = NotificationContentSource(
            connected = { true },
            recent = { emptyList() },
            category = { "  alarm  " },
        )
        assertEquals("alarm", source.latestCategory())

        val blank = NotificationContentSource(
            connected = { true },
            recent = { emptyList() },
            category = { "   " },
        )
        assertNull(blank.latestCategory())
    }

    @Test
    fun `the ring returns the newest sixteen first and forgets older ones`() {
        val ring = NotificationContentRing()
        (1L..20L).forEach { ring.add(content("com.synthetic.$it", it)) }

        val recent = ring.snapshot(16)

        assertEquals(16, recent.size)
        assertEquals("com.synthetic.20", recent.first().packageName)
        assertEquals("com.synthetic.5", recent.last().packageName)
    }

    @Test
    fun `a non-positive ring limit returns nothing`() {
        val ring = NotificationContentRing()
        ring.add(content("com.synthetic.one", 1L))

        assertTrue(ring.snapshot(0).isEmpty())
        assertTrue(ring.snapshot(-1).isEmpty())
    }

    @Test
    fun `clearing the ring drops everything`() {
        val ring = NotificationContentRing()
        ring.add(content("com.synthetic.one", 1L))

        ring.clear()

        assertTrue(ring.snapshot(16).isEmpty())
    }

    @Test
    fun `seeding replaces the ring with the active notifications oldest first`() {
        val ring = NotificationContentRing()
        ring.add(content("com.synthetic.stale", 99L))

        ring.seed(
            listOf(
                content("com.synthetic.old", 1L),
                content("com.synthetic.new", 2L),
            ),
        )

        assertEquals(
            listOf("com.synthetic.new", "com.synthetic.old"),
            ring.snapshot(16).map { it.packageName },
        )
    }

    private fun content(packageName: String, postedAt: Long) = NotificationContent(
        packageName = packageName,
        appLabel = null,
        title = null,
        text = null,
        subText = null,
        bigText = null,
        category = null,
        postedAtEpochMillis = postedAt,
        ongoing = false,
        actionLabels = emptyList(),
    )
}
