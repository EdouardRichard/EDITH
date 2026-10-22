package com.sheen.rokid.output

import com.sheen.rokid.plugin.*
import com.sheen.rokid.testsupport.*
import kotlin.test.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class ReadableHintAssemblerTest {
    private val clock = FakeClock()
    private val scheduler = ManualScheduler(clock)
    private val hints = mutableListOf<String>()
    private fun assembler(s: DeadlineScheduler = scheduler, limit: Int = ReadableHintAssembler.MAX_CODEPOINTS) =
        ReadableHintAssembler(clock, s, hints::add, 150, limit)
    private fun tick() { clock.advance(150_000_000); scheduler.runDue() }

    @Test fun `first token is not yet readable hint`() {
        val a = assembler(); a.append("建"); tick(); assertTrue(hints.isEmpty())
        a.append("议先澄清问题。"); tick(); assertEquals(listOf("建议先澄清问题。"), hints)
    }
    @Test fun `updates wait for window and replace cumulative complete content`() {
        val a = assembler(); a.append("先澄清。"); clock.advance(99_000_000); scheduler.runDue()
        assertTrue(hints.isEmpty()); a.append("再回答。"); clock.advance(51_000_000); scheduler.runDue()
        assertEquals(listOf("先澄清。再回答。"), hints)
        a.append("最后总结。"); tick(); assertEquals("先澄清。再回答。最后总结。", hints.last())
    }
    @Test fun `split surrogate waits and codepoint limit does not cut pairs`() {
        val a = assembler(limit = 3); a.append("\uD83D"); tick(); assertTrue(hints.isEmpty())
        a.append("\uDE00好。"); tick(); assertEquals(listOf("\uD83D\uDE00好。"), hints)
    }
    @Test fun `malformed surrogate is never displayed`() {
        val a = assembler(); a.append("\uD83D坏。"); a.finish(ModelEnd.COMPLETED); tick()
        assertTrue(hints.isEmpty())
    }
    @Test fun `every complete point is kept rather than the first three`() {
        // The owner's 2026-10-14 ruling removed the three-sentence rule: a hint inside the bound is emitted whole so
        // the glasses can scroll it, so all four bullet points arrive rather than the first three.
        val a = assembler(); a.append("- 澄清\n- 回答\n- 总结\n- 补充\n"); tick()
        assertEquals(listOf("- 澄清\n- 回答\n- 总结\n- 补充"), hints)
    }
    @Test fun `a sentence past the bound is refused whole and says so`() {
        // The bound keeps its original purpose (a hint may not become a document), and a sentence it cannot hold is not
        // cut mid-way: it is reported through `safeCode` like every other refusal in this class.
        val long = "字".repeat(ReadableHintAssembler.MAX_CODEPOINTS + 10) + "。"
        val a = assembler(); a.append(long); a.finish(ModelEnd.COMPLETED); tick()
        assertTrue(hints.isEmpty())
        assertEquals("HINT_TOO_LONG", a.safeCode)

        // And a sentence that fits the raised bound now gets through, where the old 120-codepoint bound cut it.
        val b = assembler(); b.append("word".repeat(31) + "."); b.finish(ModelEnd.COMPLETED); tick()
        assertTrue(hints.isNotEmpty(), "a 125-codepoint sentence must fit the raised bound")
    }
    @Test fun `only completed terminal promotes tail and late deltas are ignored`() {
        for (end in ModelEnd.entries) {
            hints.clear(); val a = assembler(); a.append("简短建议"); a.finish(end); a.append("迟到。"); tick()
            assertEquals(if (end == ModelEnd.COMPLETED) listOf("简短建议") else emptyList(), hints)
        }
    }
    @Test fun `cancel fences even a scheduler ignoring handle cancellation`() {
        val tasks = mutableListOf<() -> Unit>()
        val s = object : DeadlineScheduler {
            override fun at(deadlineNanos: Long, task: () -> Unit): CancellationHandle {
                tasks += task; return FakeCancellation()
            }
        }
        val a = assembler(s); a.append("建议。"); a.cancel(); tasks.forEach { it() }
        assertTrue(hints.isEmpty())
    }
    @Test fun `newline does not promote a single character and numbered points retain line boundaries`() {
        val a = assembler(); a.append("建\n"); tick(); assertTrue(hints.isEmpty())
        // All four points, because the three-sentence rule is gone (owner ruling 2026-10-14).
        val b = assembler(); b.append("1. 澄清\n2. 回答\n3. 总结\n4. 补充\n"); tick()
        assertEquals(listOf("1. 澄清\n2. 回答\n3. 总结\n4. 补充"), hints)
    }
    @Test fun `completed single character is not a readable phrase`() {
        val a = assembler(); a.append("建"); a.finish(ModelEnd.COMPLETED); tick(); assertTrue(hints.isEmpty())
    }
    @Test fun `failed terminal keeps earlier complete sentence without promoting its tail`() {
        val a = assembler(); a.append("先澄清。然后"); a.finish(ModelEnd.FAILED); tick()
        assertEquals(listOf("先澄清。"), hints)
    }
    @Test fun `cancel before scheduler returns cancels late timer handle`() {
        val h = FakeCancellation(); lateinit var a: ReadableHintAssembler
        var callback: (() -> Unit)? = null
        val s = object : DeadlineScheduler {
            override fun at(deadlineNanos: Long, task: () -> Unit): CancellationHandle {
                callback = task; a.cancel(); return h
            }
        }
        a = assembler(s); a.append("建议。"); callback!!()
        assertEquals(1, h.cancelCount); assertTrue(hints.isEmpty())
    }
    @Test fun `newline without a bullet cannot promote an unfinished phrase`() {
        val a = assembler(); a.append("unfinished fragment\n"); tick(); assertTrue(hints.isEmpty())
        a.finish(ModelEnd.COMPLETED); tick(); assertEquals(listOf("unfinished fragment"), hints)
    }
    @Test fun `punctuated bullets preserve newlines in cumulative hint`() {
        val a = assembler(); a.append("- 澄清。\n- 回答。\n- 总结。"); tick()
        assertEquals(listOf("- 澄清。\n- 回答。\n- 总结。"), hints)
    }
    @Test fun `cancel does not wait for blocked downstream emission`() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val cancelled = CountDownLatch(1)
        val a = ReadableHintAssembler(clock, scheduler, { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) })
        a.append("建议。"); clock.advance(150_000_000)
        val emitter = thread(isDaemon = true) { scheduler.runDue() }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        val canceller = thread(isDaemon = true) { a.cancel(); cancelled.countDown() }
        try { assertTrue(cancelled.await(500, TimeUnit.MILLISECONDS), "Cancellation waited for external sink") }
        finally { release.countDown(); emitter.join(5000); canceller.join(5000) }
    }
}
