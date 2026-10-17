package edith.phone

import com.sheen.rokid.plugin.StorageHandle
import com.sheen.rokid.phone.plugin.smarthelper.memory.MemoryKind
import com.sheen.rokid.phone.plugin.smarthelper.memory.MemoryLog
import com.sheen.rokid.phone.plugin.smarthelper.memory.MemorySource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * P3.4 §3.4: the session page's window onto long-term memory - 可查、可删、可清空.
 *
 * The document belongs to the plugin ([MemoryLog]); this page is a **reader plus two delete verbs**, and it renders the
 * fields that are in the document and nothing else. Two promises are pinned here: the key is the plugin's own constant
 * (a second literal would show an empty page and look like "nothing was ever remembered"), and a document this build
 * cannot read renders as empty rather than taking the page down.
 */
class MemoryLogViewTest {

    private class FakeStorage : StorageHandle {
        val values = mutableMapOf<String, ByteArray>()
        override fun read(key: String): ByteArray? = values[key]
        override fun write(key: String, value: ByteArray) { values[key] = value }
        override fun delete(key: String) { values.remove(key) }
        override fun clear() = values.clear()
    }

    private val storage = FakeStorage()

    private fun store(now: Long = 1_700_000_000_000L) =
        MemoryLog(storage = storage, now = { now }, log = { })

    private fun view() = MemoryLogView(storage)

    /** The same rendering the page promises, computed independently so the test does not assume a machine time zone. */
    private fun expectedStamp(millis: Long): String =
        Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))

    @Test fun `the key is the plugin's own constant`() {
        assertEquals("the page must read the key the plugin writes", MemoryLog.KEY, MemoryLogView.KEY)
    }

    @Test fun `the page renders the stored fact with its kind and time`() {
        store().record(MemoryKind.PREFERENCE, "我喜欢喝茶", MemorySource.EXPLICIT)

        val rendered = view().lines().single()

        assertEquals("我喜欢喝茶", rendered.text)
        assertEquals("偏好", rendered.kind)
        assertEquals(expectedStamp(1_700_000_000_000L), rendered.stamp)
    }

    @Test fun `the rendered line carries the text, the kind and the time and nothing else`() {
        // The page has no field for an id, a use count or a source, so a reader cannot be shown one. This is a property
        // of the shape rather than of a formatter with a good memory.
        store().record(MemoryKind.ROUTINE, "常打电话", MemorySource.AUTO)

        val rendered = view().render(view().lines().single())

        assertEquals("${expectedStamp(1_700_000_000_000L)} | 常做任务 | 常打电话", rendered)
    }

    @Test fun `lines are newest first and respect the limit`() {
        val log = store(now = 1_700_000_000_000L)
        log.record(MemoryKind.FACT, "我的爱好是喝茶", MemorySource.AUTO)
        MemoryLog(storage = storage, now = { 1_700_000_060_000L }, log = { })
            .record(MemoryKind.FACT, "我的城市是杭州", MemorySource.AUTO)

        val all = view().lines()
        val capped = view().lines(limit = 1)

        assertEquals(listOf("我的城市是杭州", "我的爱好是喝茶"), all.map { it.text })
        assertEquals(listOf("我的城市是杭州"), capped.map { it.text })
    }

    @Test fun `deleting one entry removes exactly that entry`() {
        val log = store()
        log.record(MemoryKind.FACT, "我的爱好是喝茶", MemorySource.AUTO)
        log.record(MemoryKind.FACT, "我的城市是杭州", MemorySource.AUTO)
        val doomed = view().lines().first { it.text == "我的爱好是喝茶" }

        view().delete(doomed.id)

        assertEquals(listOf("我的城市是杭州"), view().lines().map { it.text })
    }

    @Test fun `clearing removes the document itself`() {
        store().record(MemoryKind.FACT, "我的爱好是喝茶", MemorySource.AUTO)

        view().clear()

        assertNull("清空后文档必须不在", storage.read(MemoryLog.KEY))
        assertTrue(view().lines().isEmpty())
    }

    @Test fun `a document this build cannot read renders as empty instead of throwing`() {
        storage.write(MemoryLog.KEY, "not json at all".toByteArray(Charsets.UTF_8))

        assertTrue(view().lines().isEmpty())
    }

    @Test fun `an absent document renders as empty`() {
        assertTrue(view().lines().isEmpty())
    }
}
