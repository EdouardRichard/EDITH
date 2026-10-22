package com.sheen.rokid.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 0.3 Phase 2 Task 12 (spec §6.6): HARD recovery ② must not silently drop the request-scoped dynamic tail.
 *
 * The tail (`PERCEPTION`/`MEMORY_EVIDENCE`/`SKILL_BODY`/`RUNTIME_STATE`) is kept as a **truncated** segment
 * (`head + "\n...[truncated]...\n" + tail`) and recorded in `TrimReport.truncatedOrigins`; only a frame that is still
 * over budget after truncation gives a whole origin up, in the fixed order `SKILL_BODY` → `MEMORY_EVIDENCE` →
 * `PERCEPTION`, one segment at a time and oldest first, each recorded in `droppedOrigins`. `USER_INPUT` survives
 * because it is never a candidate.
 */
class TurnTrimmerHardTest {

    private fun sys(text: String = "sys") = listOf(Segment(SegmentOrigin.INSTRUCTIONS, true, text))
    private fun frame(vararg flow: Segment) = RequestFrame(sys(), flow.toList(), emptyList())
    private fun userTurn(text: String) = Segment(SegmentOrigin.TRANSCRIPT, true, text, SegmentRole.USER)
    private fun assistant(text: String) = Segment(SegmentOrigin.TRANSCRIPT, true, text, SegmentRole.ASSISTANT)
    private fun user(text: String) = Segment(SegmentOrigin.USER_INPUT, true, text)
    private fun memory(text: String) = Segment(SegmentOrigin.MEMORY_EVIDENCE, false, text)
    private fun skill(text: String) = Segment(SegmentOrigin.SKILL_BODY, true, text)
    private fun perception(text: String) = Segment(SegmentOrigin.PERCEPTION, false, text)
    private fun runtime(text: String) = Segment(SegmentOrigin.RUNTIME_STATE, false, text)

    /** The default policy's shape, repeated here so the assertions are independent of the implementation's internals. */
    private val marker = "\n...[truncated]...\n"
    private val defaultHeadChars = 400
    private val defaultTailChars = 100

    private fun huge(label: String): String =
        "$label-START-" + "x".repeat(800) + "-MIDDLE-" + "x".repeat(800) + "-$label-END"

    private fun truncate(text: String, head: Int = defaultHeadChars, tail: Int = defaultTailChars): String =
        text.take(head) + marker + text.takeLast(tail)

    private fun dynamic(report: TrimReport): List<Segment> = report.kept.conversationFlow.filter {
        it.origin == SegmentOrigin.PERCEPTION ||
            it.origin == SegmentOrigin.MEMORY_EVIDENCE ||
            it.origin == SegmentOrigin.SKILL_BODY ||
            it.origin == SegmentOrigin.RUNTIME_STATE
    }

    // ── truncation: the tail is kept with a visible marker ───────────────────────────────────────────

    @Test
    fun `a huge dynamic tail is kept truncated and marked instead of dropped`() {
        val memoryText = huge("MEMORY")
        val skillText = huge("SKILL")
        val perceptionText = huge("PERCEPTION")
        val f = frame(
            userTurn("u"),
            assistant("a"),
            memory(memoryText),
            skill(skillText),
            perception(perceptionText),
            user("now"),
        )

        val report = TurnTrimmer().trim(f, limitTokens = 1_000_000, mode = TrimMode.HARD)

        assertEquals(
            listOf(
                SegmentOrigin.TRANSCRIPT,
                SegmentOrigin.TRANSCRIPT,
                SegmentOrigin.MEMORY_EVIDENCE,
                SegmentOrigin.SKILL_BODY,
                SegmentOrigin.PERCEPTION,
                SegmentOrigin.USER_INPUT,
            ),
            report.kept.conversationFlow.map { it.origin },
            "the newest turn, the truncated tail and the current input are the whole kept shape",
        )
        val kept = dynamic(report)
        assertEquals(truncate(memoryText), kept[0].text, "memory evidence keeps only head + marker + tail")
        assertEquals(truncate(skillText), kept[1].text, "the skill body keeps only head + marker + tail")
        assertEquals(truncate(perceptionText), kept[2].text, "perception keeps only head + marker + tail")
        kept.forEach { segment ->
            assertEquals(defaultHeadChars + marker.length + defaultTailChars, segment.text.length)
            assertFalse(segment.text.contains("MIDDLE"), "the middle of ${segment.origin} is gone")
        }
        assertEquals(
            listOf(SegmentOrigin.MEMORY_EVIDENCE, SegmentOrigin.SKILL_BODY, SegmentOrigin.PERCEPTION),
            report.truncatedOrigins,
            "each truncated segment is recorded by origin, in flow order",
        )
        assertEquals(report.truncatedOrigins, report.counts.truncatedOrigins, "the count-only view copies the field")
        assertEquals(emptyList(), report.droppedOrigins)
        assertFalse(report.compressionOwed, "the truncated frame fits")
        assertEquals("now", report.kept.conversationFlow.last().text, "the current input survives untouched")
        assertEquals(SegmentOrigin.USER_INPUT, report.kept.conversationFlow.last().origin)
        assertFalse(report.counts.toString().contains("MEMORY-START"), "the report prints origins, never text")
    }

    @Test
    fun `a short dynamic segment is left untouched`() {
        val f = frame(
            userTurn("u"),
            assistant("a"),
            memory("memory short"),
            skill("skill short"),
            perception("perception short"),
            runtime("runtime short"),
            user("now"),
        )

        val report = TurnTrimmer().trim(f, limitTokens = 1_000_000, mode = TrimMode.HARD)

        assertEquals(
            listOf("memory short", "skill short", "perception short", "runtime short"),
            dynamic(report).map { it.text },
            "a segment already inside head + tail + marker is unchanged",
        )
        assertEquals(emptyList(), report.truncatedOrigins)
        assertEquals(emptyList(), report.droppedOrigins)
        assertFalse(report.compressionOwed)
        assertEquals("now", report.kept.conversationFlow.last().text)
    }

    @Test
    fun `runtime state is truncated by the same cap`() {
        val runtimeText = huge("RUNTIME")
        val f = frame(runtime(runtimeText), user("now"))

        val report = TurnTrimmer().trim(f, limitTokens = 1_000_000, mode = TrimMode.HARD)

        assertEquals(listOf(SegmentOrigin.RUNTIME_STATE, SegmentOrigin.USER_INPUT), report.kept.conversationFlow.map { it.origin })
        assertEquals(truncate(runtimeText), report.kept.conversationFlow.first().text)
        assertEquals(listOf(SegmentOrigin.RUNTIME_STATE), report.truncatedOrigins)
        assertEquals("now", report.kept.conversationFlow.last().text)
    }

    @Test
    fun `the truncation window is a configurable policy value`() {
        val text = "HEAD-" + "y".repeat(100) + "-TAIL-END"
        val trimmer = TurnTrimmer(hardDynamicHeadChars = 10, hardDynamicTailChars = 5)

        val report = trimmer.trim(frame(memory(text), user("now")), limitTokens = 1_000_000, mode = TrimMode.HARD)

        assertEquals(text.take(10) + marker + text.takeLast(5), report.kept.conversationFlow.first().text)
        assertEquals(listOf(SegmentOrigin.MEMORY_EVIDENCE), report.truncatedOrigins)
    }

    @Test
    fun `a character cut never splits a surrogate pair`() {
        // Final review minor: `String.take(n)` / `takeLast(n)` count UTF-16 units, so a cut could keep half of an emoji
        // (an unpaired surrogate). The head cut lands on a high surrogate and the tail cut on a low surrogate here; both
        // must drop the orphan half and keep the rest of the text well-formed.
        val emoji = "\uD83D\uDE00"
        val headSplit = "x".repeat(399) + emoji + "y".repeat(800)
        val tailSplit = "x".repeat(800) + emoji + "q".repeat(99)
        val f = frame(
            userTurn("u"),
            perception(headSplit),
            perception(tailSplit),
            user("now"),
        )

        val report = TurnTrimmer().trim(f, limitTokens = 1_000_000, mode = TrimMode.HARD)

        val kept = dynamic(report).filter { it.origin == SegmentOrigin.PERCEPTION }
        assertEquals(2, kept.size)
        assertEquals("x".repeat(399), kept[0].text.substringBefore(marker), "the cut drops the orphan high surrogate")
        assertEquals("q".repeat(99), kept[1].text.substringAfter(marker), "the cut drops the orphan low surrogate")
        kept.forEach { segment ->
            assertFalse(hasUnpairedSurrogate(segment.text), "a truncated segment must stay well-formed: ${segment.text}")
        }
    }

    /** True when [text] contains a high surrogate without a low one (or a lone low surrogate), i.e. half an emoji. */
    private fun hasUnpairedSurrogate(text: String): Boolean {
        var index = 0
        while (index < text.length) {
            val char = text[index]
            when {
                Character.isHighSurrogate(char) -> {
                    if (index + 1 >= text.length || !Character.isLowSurrogate(text[index + 1])) return true
                    index += 2
                }
                Character.isLowSurrogate(char) -> return true
                else -> index += 1
            }
        }
        return false
    }

    // ── cleanup: only when the truncated frame is still over budget ──────────────────────────────────

    @Test
    fun `a still-too-small frame cleans origins in the declared order and records them`() {
        val f = frame(
            userTurn("u"),
            assistant("a"),
            memory(huge("MEMORY")),
            skill(huge("SKILL")),
            perception(huge("PERCEPTION")),
            user("now"),
        )

        // Truncated, the frame costs 650 units: 32 fixed + 33 system + 33 + 33 (the turn) + 3 × 162 (each maximum
        // segment) + 33 (input). At 97 even the three dynamic origins do not make it fit (164 > 97), so all three are
        // cleaned and the newest turn group is then evicted by cut ① (98 > 97 still owes compression).
        val report = TurnTrimmer().trim(f, limitTokens = 97, mode = TrimMode.HARD)

        assertEquals(
            listOf(SegmentOrigin.SKILL_BODY, SegmentOrigin.MEMORY_EVIDENCE, SegmentOrigin.PERCEPTION),
            report.droppedOrigins,
            "the cleanup order is SKILL_BODY → MEMORY_EVIDENCE → PERCEPTION, never flow order",
        )
        assertEquals(emptyList(), report.truncatedOrigins, "a segment that was cleaned is reported as dropped, not truncated")
        assertEquals(1, report.droppedTurns, "the no-longer-fitting newest turn group is cut ①, not this loop")
        assertEquals(listOf(SegmentOrigin.USER_INPUT), report.kept.conversationFlow.map { it.origin })
        assertEquals("now", report.kept.conversationFlow.single().text, "the current input survives every cleanup")
        assertTrue(report.compressionOwed, "still over after all cleanups")
    }

    @Test
    fun `the cleanup stops as soon as the frame fits`() {
        val f = frame(
            userTurn("u"),
            assistant("a"),
            memory(huge("MEMORY")),
            skill(huge("SKILL")),
            perception(huge("PERCEPTION")),
            user("now"),
        )

        // 650 units before cleanup; one SKILL_BODY (162) brings it to 488, inside 600, so nothing else is given up.
        val report = TurnTrimmer().trim(f, limitTokens = 600, mode = TrimMode.HARD)

        assertEquals(listOf(SegmentOrigin.SKILL_BODY), report.droppedOrigins)
        assertEquals(
            listOf(
                SegmentOrigin.TRANSCRIPT,
                SegmentOrigin.TRANSCRIPT,
                SegmentOrigin.MEMORY_EVIDENCE,
                SegmentOrigin.PERCEPTION,
                SegmentOrigin.USER_INPUT,
            ),
            report.kept.conversationFlow.map { it.origin },
            "memory and perception stay (truncated); the newest turn is not evicted",
        )
        assertEquals(listOf(SegmentOrigin.MEMORY_EVIDENCE, SegmentOrigin.PERCEPTION), report.truncatedOrigins)
        assertFalse(report.compressionOwed, "one origin was enough")
        assertEquals("now", report.kept.conversationFlow.last().text)
    }

    @Test
    fun `the cleanup removes the oldest segment of an origin first`() {
        val older = huge("MEMORY-OLD")
        val newer = huge("MEMORY-NEW")
        val f = frame(
            userTurn("u"),
            assistant("a"),
            memory(older),
            memory(newer),
            skill(huge("SKILL")),
            user("now"),
        )

        // 650 units before cleanup; at 400 SKILL_BODY goes and then the first (oldest) memory, leaving 326 inside 400.
        val report = TurnTrimmer().trim(f, limitTokens = 400, mode = TrimMode.HARD)

        assertEquals(listOf(SegmentOrigin.SKILL_BODY, SegmentOrigin.MEMORY_EVIDENCE), report.droppedOrigins)
        val keptMemory = report.kept.conversationFlow.filter { it.origin == SegmentOrigin.MEMORY_EVIDENCE }
        assertEquals(1, keptMemory.size)
        assertEquals(truncate(newer), keptMemory.single().text, "the newer memory is the one that survives")
        assertEquals(listOf(SegmentOrigin.MEMORY_EVIDENCE), report.truncatedOrigins)
        assertEquals("now", report.kept.conversationFlow.last().text)
    }
}
