package com.sheen.rokid.output

import com.sheen.rokid.contracts.AssistantRoute
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Phase C (spec §6.6 / §6.7): the conversation the wearer reads back from.
 *
 * One implementation serves both screens — the glasses render it, the phone's assistant tab renders it — so the two
 * cannot disagree about what was said or in which order. It is bounded on purpose: a session that runs for an hour
 * appends two entries per turn, and an unbounded list would be rebuilt on every render on a device with no input path
 * to scroll it.
 *
 * The two routes never see each other's entries: they are two independent conversations (spec §1.3 C3), and the whole
 * point of the route switch on both screens is that switching it changes which one is in front of you.
 */
class ConversationLogTest {

    private fun entry(id: String, route: AssistantRoute = AssistantRoute.WAKE,
        role: ConversationRole = ConversationRole.USER, text: String = "第 $id 条",
        turnId: String? = null) =
        ConversationEntry(id, route, role, text, turnId)

    @Test
    fun `an entry is appended and read back in order`() {
        val log = ConversationLog()
        assertTrue(log.append(entry("a", text = "第一句")))
        assertTrue(log.append(entry("b", role = ConversationRole.ASSISTANT, text = "第二句")))
        assertEquals(listOf("第一句", "第二句"), log.entries(AssistantRoute.WAKE).map { it.text })
        assertEquals(2, log.size())
    }

    @Test
    fun `a second entry with the same id replaces the text and keeps its place`() {
        // A hint that is re-sent for the same turn carries the same `hintId` with a new revision: the wearer must see
        // one message that changed, not the same message twice.
        val log = ConversationLog()
        log.append(entry("h-1", role = ConversationRole.ASSISTANT, text = "第一版"))
        log.append(entry("h-2", role = ConversationRole.ASSISTANT, text = "另一条"))
        assertTrue(log.append(entry("h-1", role = ConversationRole.ASSISTANT, text = "第二版")))
        assertEquals(listOf("第二版", "另一条"), log.entries(AssistantRoute.WAKE).map { it.text },
            "the revision stays where the original was, so the conversation does not reorder under the wearer")
    }

    @Test
    fun `the two routes never see each other`() {
        val log = ConversationLog()
        log.append(entry("a", route = AssistantRoute.WAKE, text = "唤醒说的话"))
        log.append(entry("b", route = AssistantRoute.PROACTIVE, text = "主动说的话"))
        assertEquals(listOf("唤醒说的话"), log.entries(AssistantRoute.WAKE).map { it.text })
        assertEquals(listOf("主动说的话"), log.entries(AssistantRoute.PROACTIVE).map { it.text })
    }

    @Test
    fun `the oldest entry is dropped at the entry bound`() {
        val log = ConversationLog(maxEntries = 3, maxChars = 1_000)
        (1..4).forEach { log.append(entry("e$it", text = "第 $it 条")) }
        assertEquals(listOf("第 2 条", "第 3 条", "第 4 条"), log.entries(AssistantRoute.WAKE).map { it.text })
    }

    @Test
    fun `the oldest entry is dropped at the character bound`() {
        val log = ConversationLog(maxEntries = 40, maxChars = 10)
        log.append(entry("a", text = "12345678"))
        log.append(entry("b", text = "abcdefgh"))
        assertEquals(listOf("abcdefgh"), log.entries(AssistantRoute.WAKE).map { it.text })
    }

    @Test
    fun `one over-long entry is kept rather than emptying the conversation`() {
        // The bound exists to stop unbounded growth, not to make the screen blank: a single message longer than the
        // whole budget is still the thing that was most recently said.
        val log = ConversationLog(maxEntries = 40, maxChars = 10)
        log.append(entry("a", text = "这一条比整个预算都长"))
        assertEquals(1, log.entries(AssistantRoute.WAKE).size)
        assertEquals("这一条比整个预算都长", log.entries(AssistantRoute.WAKE).single().text)
    }

    @Test
    fun `the bounds are counted per route`() {
        val log = ConversationLog(maxEntries = 2, maxChars = 1_000)
        log.append(entry("w1")); log.append(entry("w2"))
        log.append(entry("p1", route = AssistantRoute.PROACTIVE))
        log.append(entry("p2", route = AssistantRoute.PROACTIVE))
        log.append(entry("p3", route = AssistantRoute.PROACTIVE))
        assertEquals(listOf("第 w1 条", "第 w2 条"), log.entries(AssistantRoute.WAKE).map { it.text },
            "filling the proactive route must not evict the wake conversation")
        assertEquals(listOf("第 p2 条", "第 p3 条"), log.entries(AssistantRoute.PROACTIVE).map { it.text })
    }

    @Test
    fun `a blank entry is refused and the log survives`() {
        val log = ConversationLog()
        assertFalse(log.append(entry("e", text = "   ")))
        assertFalse(log.append(entry(" ", text = "文本")))
        assertTrue(log.entries(AssistantRoute.WAKE).isEmpty())
    }

    @Test
    fun `clear empties both routes`() {
        val log = ConversationLog()
        log.append(entry("a"))
        log.append(entry("b", route = AssistantRoute.PROACTIVE))
        log.clear()
        assertEquals(0, log.size())
        assertTrue(log.entries(AssistantRoute.WAKE).isEmpty())
        assertTrue(log.entries(AssistantRoute.PROACTIVE).isEmpty())
    }

    @Test
    fun `the returned list cannot be used to write into the log`() {
        // The glasses rebuild their view from this list; a caller that could mutate it would be a second writer.
        val log = ConversationLog()
        log.append(entry("a"))
        log.append(entry("b"))
        val read = log.entries(AssistantRoute.WAKE) as MutableList<ConversationEntry>
        read.clear()
        assertEquals(listOf("a", "b"), log.entries(AssistantRoute.WAKE).map { it.entryId },
            "clearing the snapshot must not touch the log")
    }

    @Test
    fun `a revision of the oldest entry is not deleted by the append that wrote it`() {
        // A replaced entry keeps its position, so without this the very append that updated an old message could evict
        // it - and the caller was told the append succeeded.
        val log = ConversationLog(maxEntries = 2, maxChars = 1_000)
        log.append(entry("old", text = "第一版"))
        log.append(entry("new", text = "另一条"))
        assertTrue(log.append(entry("old", text = "修订版")))
        assertEquals(listOf("修订版", "另一条"), log.entries(AssistantRoute.WAKE).map { it.text })
    }

    @Test
    fun `several threads writing and reading at once never corrupt the log`() {
        // The glasses write from the bridge's callback thread and read from the UI thread; the phone writes from the
        // host's drain thread while Compose reads. An unsynchronised LinkedHashMap iterated during an insert is a
        // ConcurrentModificationException waiting for a slow frame.
        val log = ConversationLog(maxEntries = 8, maxChars = 400)
        val threads = (0 until 4).map { writer ->
            Thread {
                repeat(200) { index ->
                    log.append(entry("w$writer-$index", text = "第 $writer-$index 条"))
                    log.entries(AssistantRoute.WAKE).size
                    log.size()
                }
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join(10_000) }
        assertTrue(threads.none { it.isAlive }, "no writer may still be blocked")
        assertTrue(log.entries(AssistantRoute.WAKE).size <= 8, "the bound must survive concurrent writers")
    }

    @Test
    fun `an impossible bound is refused rather than silently ignored`() {
        assertFalse(runCatching { ConversationLog(maxEntries = 0) }.isSuccess)
        assertFalse(runCatching { ConversationLog(maxChars = 0) }.isSuccess)
    }

    @Test
    fun `a turn id is carried with the entry it was appended with`() {
        // 0.24 S2: the wearer's utterance and the assistant reply that answers it share one turn id, so a screen can
        // tie them together without re-deriving the pairing from order. The log carries the id; it does not mint it.
        val log = ConversationLog()
        assertTrue(log.append(entry("asr-t1", turnId = "t1")))
        assertTrue(log.append(entry("say-h1", role = ConversationRole.ASSISTANT, turnId = "t1")))
        assertEquals(listOf("t1", "t1"), log.entries(AssistantRoute.WAKE).map { it.turnId })
    }

    @Test
    fun `an entry appended without a turn id reads back with null`() {
        // Producers that predate the turn id keep working: the log must not invent a turn for them.
        val log = ConversationLog()
        log.append(entry("a"))
        assertNull(log.entries(AssistantRoute.WAKE).single().turnId)
    }

    @Test
    fun `an entry built without a turn id defaults it to null`() {
        assertNull(ConversationEntry("a", AssistantRoute.WAKE, ConversationRole.USER, "文本").turnId,
            "every existing constructor call must keep its meaning")
    }

    @Test
    fun `a repeated id does not add a second entry`() {
        val log = ConversationLog()
        assertTrue(log.append(entry("asr-t1", text = "第一版", turnId = "t1")))
        assertTrue(log.append(entry("asr-t1", text = "第二版", turnId = "t1")))
        assertEquals(1, log.entries(AssistantRoute.WAKE).size)
        assertEquals("第二版", log.entries(AssistantRoute.WAKE).single().text)
    }

    @Test
    fun `a same-id update keeps its place and takes the turn id it was sent with`() {
        val log = ConversationLog()
        log.append(entry("say-h1", role = ConversationRole.ASSISTANT, text = "第一版", turnId = "t1"))
        log.append(entry("say-h2", role = ConversationRole.ASSISTANT, text = "另一条", turnId = "t2"))
        assertTrue(log.append(entry("say-h1", role = ConversationRole.ASSISTANT, text = "第二版", turnId = "t3")))
        assertEquals(listOf("第二版", "另一条"), log.entries(AssistantRoute.WAKE).map { it.text },
            "a revision stays where the original was, so the conversation does not reorder under the wearer")
        assertEquals(listOf("t3", "t2"), log.entries(AssistantRoute.WAKE).map { it.turnId },
            "the stored entry is the last one appended under that id")
    }

    @Test
    fun `eviction still keeps the newest entry and its turn id`() {
        val log = ConversationLog(maxEntries = 2, maxChars = 1_000)
        (1..3).forEach { log.append(entry("e$it", text = "第 $it 条", turnId = "t$it")) }
        assertEquals(listOf("第 2 条", "第 3 条"), log.entries(AssistantRoute.WAKE).map { it.text })
        assertEquals(listOf("t2", "t3"), log.entries(AssistantRoute.WAKE).map { it.turnId })
    }
}
