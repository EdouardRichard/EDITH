package edith.agent

/**
 * The fixed trim order (H3): oldest whole turns → least-relevant memory → compression.
 *
 * The order is a **testable constant**, not a comment: [ORDER] is the whole sequence and
 * [COMPRESSION_AFTER] is the prefix that runs synchronously before send. The third cut
 * ([COMPRESSION]) is not performed by [TurnTrimmer] — it is only *reported* as owed
 * ([TrimReport.compressionOwed]); the asynchronous summary is Task 5's.
 */
object TrimOrder {
    const val OLDEST_TURNS = "OLDEST_TURNS"
    const val MEMORY_EVIDENCE = "MEMORY_EVIDENCE"
    const val COMPRESSION = "COMPRESSION"
    val ORDER: List<String> = listOf(OLDEST_TURNS, MEMORY_EVIDENCE, COMPRESSION)
    val COMPRESSION_AFTER: List<String> = listOf(OLDEST_TURNS, MEMORY_EVIDENCE)
}

/**
 * What one [TurnTrimmer.trim] did to one frame — counts and origins only, never text.
 *
 * @param kept the frame as it will be sent, with `USER_INPUT` still last and the system slot intact.
 * @param droppedTurns how many whole old turns cut ① removed.
 * @param droppedOrigins the origins cut ② removed, in the order they were dropped (least relevant first).
 *   Turn eviction is counted by [droppedTurns], not repeated here.
 * @param compressionOwed 裁到只剩本轮发言仍超预算 ⇒ 压缩这一刀还欠着（由调用方在回合收尾时补上）。
 */
data class TrimReport(
    val kept: RequestFrame,
    val droppedTurns: Int,
    val droppedOrigins: List<SegmentOrigin>,
    val compressionOwed: Boolean,
)

/**
 * Trims a [RequestFrame] down to a byte budget in the fixed order (H2 + H3). A pure function: it
 * never mutates its input, never touches a store, and calls nothing asynchronous.
 *
 * ① [TrimOrder.OLDEST_TURNS] (whole old turns) and ② [TrimOrder.MEMORY_EVIDENCE] (least relevant
 * first) run here, synchronously, on the assembling thread before send. ③ [TrimOrder.COMPRESSION] is
 * only reported ([TrimReport.compressionOwed]) — never performed.
 *
 * ## The current input is never trimmed — by construction
 *
 * `USER_INPUT` is **excluded from the eviction candidate set by construction**: every cut filters it
 * out, and a turn slice can only be taken from the region *before* it (it is last by
 * [RequestFrame]'s `require`). So if anything survives, it is the input — the input survives because
 * it is never offered for eviction, not because it "happens to" survive.
 *
 * This is **not** the same as "the input always fits": when `USER_INPUT` alone exceeds `limitBytes`
 * the kept frame still carries it and [TrimReport.compressionOwed] is `true`. Refusing an oversized
 * input stays `ContextBudgeter`'s existing `ConfigurationRejected` rule — out of scope here, and this
 * class never emits an oversized input into a send path that would bypass that rule.
 *
 * ## Byte accounting — the same unit as `ContextBudgeter`
 *
 * [measure] is the arithmetic `ContextBudgeter` uses for one message (UTF-8 bytes + 32, see
 * `ContextBudgeter.kt`'s `message(text)`), and [total] adds the same fixed [FIXED_OVERHEAD] that
 * `ContextBudgeter.kt` adds once (`Math.addExact(32L, message(instructions))`). Sharing the unit is
 * the point: "trimmed to fit" and the send-time check must not disagree by 32.
 *
 * A `SUMMARY` segment is **not a turn**: it never participates in turn eviction. But it does count
 * toward [total] — it gets no exemption from the budget check.
 *
 * @param measure the per-segment byte cost. Defaults to [utf8BytesPlusOverhead].
 */
class TurnTrimmer(private val measure: (Segment) -> Int = { utf8BytesPlusOverhead(it.text) }) {

    fun trim(frame: RequestFrame, limitBytes: Int): TrimReport {
        val flow = frame.conversationFlow.toMutableList()
        var droppedTurns = 0
        val droppedOrigins = mutableListOf<SegmentOrigin>()

        fun total(): Int = FIXED_OVERHEAD +
            measure(Segment(SegmentOrigin.INSTRUCTIONS, true, frame.systemText)) +
            flow.sumOf(measure)

        // ① 旧整回合：一组 = 一条 role=USER 的 TRANSCRIPT（或它引发的 TOOL_ROUND 段）到下一个
        // USER 之前。整组淘汰 ⇒ 永远不发"半个回合"。从最旧一组开始，只在还超预算时才落。
        while (total() > limitBytes) {
            val start = flow.indexOfFirst { isTurnStart(it) }
            if (start < 0) break
            val next = flow.subList(start + 1, flow.size).indexOfFirst { isTurnStart(it) }
            val stop = if (next < 0) flow.size else start + 1 + next
            // 绝不吞掉 USER_INPUT 与 SUMMARY：它们不在回合里。索引降序删，否则索引会漂。
            val slice = (start until stop).filter { isEvictable(flow[it]) }
            if (slice.isEmpty()) break
            slice.sortedDescending().forEach { flow.removeAt(it) }
            droppedTurns++
        }

        // ② 最不相关记忆：按相关性整条丢，从最不相关（列表末尾）开始。SUMMARY 与 USER_INPUT 不参与。
        var index = flow.lastIndex
        while (total() > limitBytes && index >= 0) {
            if (flow[index].origin == SegmentOrigin.MEMORY_EVIDENCE) {
                droppedOrigins += flow.removeAt(index).origin
            }
            index--
        }

        return TrimReport(
            kept = frame.copy(conversationFlow = flow.toList()),
            droppedTurns = droppedTurns,
            droppedOrigins = droppedOrigins.toList(),
            compressionOwed = total() > limitBytes,
        )
    }

    /** A whole-turn group opens at a `TRANSCRIPT` segment whose [Segment.role] is [SegmentRole.USER]. */
    private fun isTurnStart(segment: Segment): Boolean =
        segment.origin == SegmentOrigin.TRANSCRIPT && segment.role == SegmentRole.USER

    /**
     * Whether a segment may enter the eviction candidate set. `USER_INPUT` (AC-5) and `SUMMARY`
     * (a summary is not a turn) never may — this filter is the construction that keeps the current
     * input out of every cut.
     */
    private fun isEvictable(segment: Segment): Boolean =
        segment.origin != SegmentOrigin.USER_INPUT && segment.origin != SegmentOrigin.SUMMARY
}

/**
 * The fixed `+32` `ContextBudgeter` adds once on top of the per-message costs
 * (`Math.addExact(32L, message(request.instructions))`). [TurnTrimmer.total] must include it, or a
 * frame trimmed to exactly `limit` is 32 over the send-time check.
 */
internal const val FIXED_OVERHEAD = 32

/**
 * 与 `ContextBudgeter` 同口径：UTF-8 字节 + 每条 32 的记账开销（`ContextBudgeter.message()` 用同一算式）。
 */
internal fun utf8BytesPlusOverhead(text: String): Int = text.toByteArray(Charsets.UTF_8).size + 32
