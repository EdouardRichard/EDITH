package com.sheen.rokid.output

import com.sheen.rokid.plugin.CancellationHandle
import com.sheen.rokid.plugin.DeadlineScheduler
import com.sheen.rokid.plugin.ModelEnd
import com.sheen.rokid.plugin.MonotonicClock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The hint assembler's length rule (owner ruling 2026-10-14: 超长通知不能截断，要推送眼镜端滚动显示).
 *
 * This class carried the actual truncation the owner reported, and it was the quietest of the three bounds: it kept
 * **three sentences** (`count == 3`) and stopped at [MAX_CODEPOINTS], so a long notification reached the wearer as its
 * first three sentences with nothing said about the rest. A hint that needs more than one window is scrolled by the
 * glasses, so the bound here exists to keep a *document* off the panel — not to shorten a notification.
 */
class ReadableHintAssemblerLengthTest {

    private class Clock(var now: Long = 0L) : MonotonicClock {
        override fun nowNanos(): Long = now
    }

    private class Scheduler(private val clock: Clock) : DeadlineScheduler {
        private val due = mutableListOf<Pair<Long, () -> Unit>>()
        override fun at(deadlineNanos: Long, task: () -> Unit): CancellationHandle {
            val entry = deadlineNanos to task
            due += entry
            return object : CancellationHandle { override fun cancel() { due.remove(entry) } }
        }
        fun runDue() {
            due.filter { it.first <= clock.now }.toList().forEach { due.remove(it); it.second() }
        }
    }

    private fun assemble(text: String, maxCodePoints: Int = ReadableHintAssembler.MAX_CODEPOINTS): String {
        val clock = Clock()
        val scheduler = Scheduler(clock)
        val emitted = mutableListOf<String>()
        val assembler = ReadableHintAssembler(clock, scheduler, { emitted += it }, maxCodePoints = maxCodePoints)
        assembler.append(text)
        assembler.finish(ModelEnd.COMPLETED)
        clock.now += 1_000_000_000L
        scheduler.runDue()
        return emitted.joinToString("")
    }

    @Test
    fun `a notification of more than three sentences is emitted whole`() {
        // Five sentences, ~75 codepoints: past the old three-sentence rule, well inside the bound.
        val text = "第一句提醒你。第二句也要说。第三句同样重要。第四句不能丢。第五句是结尾。"
        val emitted = assemble(text)

        assertTrue(emitted.contains("第四句"), "the fourth sentence was dropped: $emitted")
        assertTrue(emitted.contains("第五句"), "the fifth sentence was dropped: $emitted")
        assertTrue(emitted.contains("第一句"), "the first sentence was dropped: $emitted")
    }

    @Test
    fun `a notification up to the bound is not truncated`() {
        val text = "这是一句很长的通知内容，需要滚动才能读完。".repeat(12)
        assertTrue(text.length <= ReadableHintAssembler.MAX_CODEPOINTS)
        val emitted = assemble(text)
        assertTrue(
            emitted.length >= text.length - 4,
            "expected the whole notification (${text.length} codepoints), got ${emitted.length}",
        )
    }

    @Test
    fun `a document past the bound is still cut and says so`() {
        // The bound keeps its purpose: a hint may not become a document. Cutting here is the designed outcome, and it
        // is reported through `safeCode` rather than done silently.
        val text = "这是一句很长的通知内容。".repeat(100)
        val clock = Clock()
        val scheduler = Scheduler(clock)
        val emitted = mutableListOf<String>()
        val assembler = ReadableHintAssembler(clock, scheduler, { emitted += it })
        assembler.append(text)
        assembler.finish(ModelEnd.COMPLETED)
        clock.now += 1_000_000_000L
        scheduler.runDue()

        assertTrue(emitted.joinToString("").length <= ReadableHintAssembler.MAX_CODEPOINTS)
        assertEquals("HINT_TOO_LONG", assembler.safeCode)
    }
}
