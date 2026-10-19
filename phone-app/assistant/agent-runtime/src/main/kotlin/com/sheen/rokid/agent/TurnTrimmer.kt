package com.sheen.rokid.agent

/**
 * The fixed trim order (H3): oldest whole turns → least-relevant memory → compression.
 *
 * The order is a **testable constant**, not a comment: [ORDER] is the whole sequence and
 * [COMPRESSION_AFTER] is the prefix that runs synchronously before send. The third cut
 * ([COMPRESSION]) is not performed by [TurnTrimmer] — it is only *reported* as owed
 * ([TrimReport.compressionOwed]); the asynchronous summary is Task 5's.
 *
 * ## Cut ② is a no-op for every flow that carries a turn group
 *
 * The declared order stays `[OLDEST_TURNS, MEMORY_EVIDENCE, COMPRESSION]`, but the assembled shape makes the middle
 * cut unreachable in practice, and this is stated here rather than left as a silent lie:
 *
 * - The frame is assembled in the production order — store snapshot → perception → memory → `USER_INPUT`
 *   ([TurnRunner.assemble]) — so the `MEMORY_EVIDENCE` segments sit **after** the last turn group.
 * - Cut ① removes the slice `(group start until the next turn start)`, and `flow.size` when no later turn follows.
 *   For the last group that slice therefore **contains the memory segments**, so they leave with ① and are charged
 *   to [TrimReport.droppedTurns] — not to ②.
 * - ① runs until the flow fits or no turn group is left. It never stops with a turn group still present and the
 *   memory behind it, so ② only ever fires on a flow with **no turn group at all** (`[memory, USER_INPUT]`, e.g. the
 *   first turn of a session).
 *
 * The owner-approved consequence (2026-10-16, "memory leaves with the turn boundary or not at all") is that memory
 * is no longer shed independently of a turn. The `MEMORY_EVIDENCE` cut is still implemented and still tested
 * (`a memory is dropped from the least relevant end first`), and it is the only cut that can act when there is no
 * turn to give up.
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
 * The counts of one trim and nothing else — what a probe or a log line may read.
 *
 * [TrimReport] cannot serve as that surface: it carries [TrimReport.kept], a [RequestFrame] whose segments hold the
 * surviving conversation text, so `TrimReport.toString()` would print bodies that a count-only probe must never
 * expose. This type has no text to print.
 */
data class TrimCounts(
    val droppedTurns: Int,
    val droppedOrigins: List<SegmentOrigin>,
    val compressionOwed: Boolean,
)

/** The counts-only view of this report: safe to log, `toString()`, or hand to a probe. */
val TrimReport.counts: TrimCounts
    get() = TrimCounts(droppedTurns, droppedOrigins, compressionOwed)

/**
 * Trims a [RequestFrame] down to a byte budget in the fixed order (H2 + H3). A pure function: it
 * never mutates its input, never touches a store, and calls nothing asynchronous.
 *
 * ① [TrimOrder.OLDEST_TURNS] (whole old turns) and ② [TrimOrder.MEMORY_EVIDENCE] (least relevant
 * first) run here, synchronously, on the assembling thread before send. ③ [TrimOrder.COMPRESSION] is
 * only reported ([TrimReport.compressionOwed]) — never performed. In the assembled shape ② is
 * unreachable for any flow that carries a turn group; [TrimOrder]'s KDoc says exactly why.
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
 * ## Cut ① always terminates
 *
 * Each ① iteration removes at least the group's own first segment: [isTurnStart] implies a
 * `TRANSCRIPT` origin, which [isEvictable] never rejects. `flow` therefore strictly shrinks and
 * [total] strictly falls, so the loop cannot spin and an empty candidate slice is unreachable — there
 * is deliberately no empty-slice guard (one existed and was dead code).
 *
 * ## Byte accounting — the same unit and the same total as `ContextBudgeter`
 *
 * [measure] is the arithmetic `ContextBudgeter` uses for one message (UTF-8 bytes + 32, see
 * `ContextBudgeter.kt`'s `message(text)`), and [total] adds the same fixed [FIXED_OVERHEAD] that
 * `ContextBudgeter.kt` adds once (`Math.addExact(32L, message(instructions))`). The two totals are
 * therefore **structurally identical** against the same `minOf(...)` limit, and a frame whose
 * `total()` is at or under that limit is accepted by the send-time check. (The gateway still trims to
 * `limit - 32` as a deliberate safety margin; that is stricter, never the other way round.)
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
            // slice 非空是构造保证：`start` 自身落在 [start, stop) 内，且 isTurnStart ⇒ TRANSCRIPT
            // ⇒ isEvictable 必为真。故不存在（也不需要）空集分支。每轮至少删掉 flow[start]，
            // flow 单调变短、total() 单调变小 ⇒ 循环必终止，① 不会空转。
            val slice = (start until stop).filter { isEvictable(flow[it]) }
            slice.sortedDescending().forEach { flow.removeAt(it) }
            droppedTurns++
        }

        // ② 最不相关记忆：按相关性整条丢，从最不相关（列表末尾）开始。SUMMARY 与 USER_INPUT 不参与。
        // 装配形状下（记忆段在最后一组之后）这一刀通常已经没有对象：① 的最后一组 slice 一直延伸到
        // `flow.size`，把记忆段一起带走并记在 droppedTurns 上（见 `TrimOrder` 的 KDoc）。它保留下来是为了
        // 没有任何回合组可让的请求（`[memory, USER_INPUT]`），以及为后续阶段保留"按相关性单独让出记忆"的能力。
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
     * input out of every cut. `RUNTIME_STATE` is protected for the same reason (R59): it is request
     * metadata, not an old turn, and it is only a few hundred characters.
     */
    private fun isEvictable(segment: Segment): Boolean =
        segment.origin != SegmentOrigin.USER_INPUT &&
            segment.origin != SegmentOrigin.SUMMARY &&
            segment.origin != SegmentOrigin.RUNTIME_STATE
}

/**
 * The fixed `+32` `ContextBudgeter` adds once on top of the per-message costs
 * (`Math.addExact(32L, message(request.instructions))`). It is part of the **shared** formula, not a
 * correction: `TurnTrimmer.total` and `ContextBudgeter.prepare` count the same terms, so including it
 * here is what makes the two agree exactly. (`ModelClientGateway.windowBytes` subtracts one more 32 as
 * a deliberate margin.)
 */
internal const val FIXED_OVERHEAD = 32

/**
 * 与 `ContextBudgeter` 同口径：UTF-8 字节 + 每条 32 的记账开销（`ContextBudgeter.message()` 用同一算式）。
 */
internal fun utf8BytesPlusOverhead(text: String): Int = text.toByteArray(Charsets.UTF_8).size + 32
