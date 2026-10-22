package com.sheen.rokid.agent

import com.sheen.rokid.contracts.ImageBudget
import com.sheen.rokid.plugin.ImageRef
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * The fixed trim order (H3, extended by 0.25 R69): type-aware omission → oldest whole turns → least-relevant memory →
 * compression.
 *
 * The order is a **testable constant**, not a comment: [ORDER] is the whole sequence and [COMPRESSION_AFTER] is the
 * prefix that runs synchronously before send. ⓪ ([TYPE_AWARE]) is Task 13's explicit extension of D81: it elides
 * *types* of old content (old UI trees, old images, long non-last tool results) before the whole-turn cut, because an
 * old observation costs a lot and says little. ①/② keep D97's semantics. The third cut ([COMPRESSION]) is not performed
 * by [TurnTrimmer] — it is only *reported* as owed ([TrimReport.compressionOwed]); the asynchronous summary is Task 5's.
 *
 * ## Cut ② is a no-op for every flow that carries a turn group
 *
 * The declared order stays `[TYPE_AWARE, OLDEST_TURNS, MEMORY_EVIDENCE, COMPRESSION]`, but the assembled shape makes the
 * middle cut unreachable in practice, and this is stated here rather than left as a silent lie:
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
    /** 0.25 R69: the type-aware cut that runs before every D97 cut. */
    const val TYPE_AWARE = "TYPE_AWARE"
    const val OLDEST_TURNS = "OLDEST_TURNS"
    const val MEMORY_EVIDENCE = "MEMORY_EVIDENCE"
    const val COMPRESSION = "COMPRESSION"
    val ORDER: List<String> = listOf(TYPE_AWARE, OLDEST_TURNS, MEMORY_EVIDENCE, COMPRESSION)
    val COMPRESSION_AFTER: List<String> = listOf(TYPE_AWARE, OLDEST_TURNS, MEMORY_EVIDENCE)
}

/**
 * How much of ⓪ runs (0.25 R70's deterministic overflow recovery uses the non-standard modes).
 *
 * [STANDARD] is the every-request behaviour the spec fixes: old UI trees and older-than-K images are elided, a long
 * non-last tool result keeps head+tail, and the **last** tool result is exempt. [AGGRESSIVE] is overflow recovery ①:
 * the historical tool rounds (every `TOOL_ROUND` segment before the newest round) are dropped first, then ⓪ runs; the
 * assistant texts the removal brings together are merged, so the result is still a shape `ContextBudgeter` sends.
 * [HARD] is overflow recovery ②: only the newest whole turn and the current input remain (plus request-scoped
 * `RUNTIME_STATE`/`RETRY` metadata and the untouched system slot), and within that turn only the newest tool round,
 * its assistant opener and the closing assistant text survive. The request-scoped dynamic tail
 * (`PERCEPTION`/`MEMORY_EVIDENCE`/`SKILL_BODY`/`RUNTIME_STATE`) is kept, shortened to head + marker + tail; only a
 * frame that is still over budget after that gives a whole origin up, in the fixed order `SKILL_BODY` →
 * `MEMORY_EVIDENCE` → `PERCEPTION`, and every removal is recorded (Task 12, spec §6.6).
 */
enum class TrimMode { STANDARD, AGGRESSIVE, HARD }

/**
 * What one [TurnTrimmer.trim] did to one frame — counts and origins only, never text.
 *
 * @param kept the frame as it will be sent, with `USER_INPUT` still last and the system slot intact.
 * @param droppedTurns how many whole old turns cut ① removed.
 * @param droppedOrigins the origins cut ② removed, in the order they were dropped (least relevant first).
 *   Turn eviction is counted by [droppedTurns], not repeated here.
 * @param compressionOwed 裁到只剩本轮发言仍超预算 ⇒ 压缩这一刀还欠着（由调用方在回合收尾时补上）。
 * @param elidedUiTrees how many old UI-tree tool results ⓪ replaced with a header (Task 13).
 * @param elidedImages how many historical images ⓪ degraded to a text summary (Task 13; newest K = 3 stay whole).
 * @param elidedToolResults how many long non-last tool results ⓪ truncated to head+tail (Task 13).
 * @param truncatedOrigins the origins of HARD-recovery dynamic segments that survived **truncated** (Task 12): the
 *   segment is still in [kept], with `head + "\n...[truncated]...\n" + tail`. A segment that was truncated and then
 *   given up by the still-over-budget cleanup is reported in [droppedOrigins] instead, so the two lists never name the
 *   same segment. Empty for STANDARD/AGGRESSIVE.
 */
data class TrimReport(
    val kept: RequestFrame,
    val droppedTurns: Int,
    val droppedOrigins: List<SegmentOrigin>,
    val compressionOwed: Boolean,
    val elidedUiTrees: Int = 0,
    val elidedImages: Int = 0,
    val elidedToolResults: Int = 0,
    val truncatedOrigins: List<SegmentOrigin> = emptyList(),
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
    val elidedUiTrees: Int = 0,
    val elidedImages: Int = 0,
    val elidedToolResults: Int = 0,
    val truncatedOrigins: List<SegmentOrigin> = emptyList(),
)

/** The counts-only view of this report: safe to log, `toString()`, or hand to a probe. */
val TrimReport.counts: TrimCounts
    get() = TrimCounts(
        droppedTurns,
        droppedOrigins,
        compressionOwed,
        elidedUiTrees,
        elidedImages,
        elidedToolResults,
        truncatedOrigins,
    )

/**
 * Trims a [RequestFrame] down to a token-unit budget in the fixed order (H2 + H3 + R69). A pure function: it never
 * mutates its input, never touches a store, and calls nothing asynchronous.
 *
 * ⓪ [TrimOrder.TYPE_AWARE] runs first, unconditionally — a stale observation or an expired image is cheaper as a
 * header/summary no matter how much room the frame has. Then ① [TrimOrder.OLDEST_TURNS] (whole old turns) and
 * ② [TrimOrder.MEMORY_EVIDENCE] (least relevant first) run synchronously, on the assembling thread before send.
 * ③ [TrimOrder.COMPRESSION] is only reported ([TrimReport.compressionOwed]) — never performed. In the assembled shape
 * ② is unreachable for any flow that carries a turn group; [TrimOrder]'s KDoc says exactly why.
 *
 * ## ⓪ is type-aware, not a second budget (R69)
 *
 * - **Old UI tree**: a `TOOL_ROUND` output whose envelope carries a non-blank `observation.screen` (the compact UI
 *   tree; Task 8 re-pointed this from the pre-0.3 `data.screen` object), and which is not the *last* tool result,
 *   has **only `observation.screen`** replaced by a header that keeps the action/revision/app, the line count and the
 *   visible refs (`#n…`) but not the full tree. The rest of the envelope — `execution` and the tool-specific `data` —
 *   is preserved verbatim, so an old shell/open result keeps its exit code, candidate list and facts. That is the
 *   "header + 已压缩" rule; refs survive because the model may still act on them, the hundreds of node lines do not.
 *   The pre-0.3 `data.screen` object is a **non-production** shape (a composition without a perception port; production
 *   always injects one) and is deliberately not elided: the production device tree lives in `observation.screen`.
 * - **Old images**: only the newest [IMAGE_KEEP] images across the whole flow stay whole; older
 *   `ToolOutput.images`/`Segment.images` entries are removed and a visible `[image <id> <mime> <w>x<h> (elided)]` text
 *   is appended to the owner. The newest K are the references the context may still contain, which is what keeps
 *   artifact retention (R72) from breaking a live request.
 * - **Long tool result**: a non-last `TOOL_ROUND` output longer than [LONG_TOOL_RESULT_CHARS] is replaced by its head
 *   and tail plus a marker and `truncated: true`. It is deliberately not a small output: only a genuinely long result
 *   is worth the loss of the middle.
 * - **Last tool result**: the newest output is *never* head+tail trimmed by the context; the tool's own 16 KiB cap is
 *   the bound. (Its images still obey the global K = 3 rule, because that price is what the newest work pays.)
 * - `RUNTIME_STATE` (Task 12) is request metadata: never evicted like a turn, but fully counted in the budget.
 *
 * ## The current input is never trimmed — by construction
 *
 * `USER_INPUT` is **excluded from the eviction candidate set by construction**: every cut filters it
 * out, and a turn slice can only be taken from the region *before* it (it is last by
 * [RequestFrame]'s `require`). So if anything survives, it is the input — the input survives because
 * it is never offered for eviction, not because it "happens to" survive.
 *
 * This is **not** the same as "the input always fits": when `USER_INPUT` alone exceeds `limitTokens`
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
 * ## Token accounting — the same unit and the same per-message formula as `ContextBudgeter`
 *
 * [measure] prices one message the way `ContextBudgeter.message(text)` does — [TokenMeter.estimate] token units plus
 * the same 32-unit per-message overhead — plus one [ImageBudget.UNITS_PER_IMAGE] per image the adapter will put on the
 * wire (R68). The two stages therefore price a text and an image identically, and an image-bearing frame cannot be
 * trimmed to a request the send-time budgeter then rejects. [total] adds the same fixed [FIXED_OVERHEAD] that
 * `ContextBudgeter.kt` adds once (`Math.addExact(32L, message(instructions))`).
 *
 * The rendered request is not necessarily the exact sum of those per-segment prices: the gateway folds the leading
 * context into one message and renders a `TOOL_ROUND` as one or more wire items, so the send-time total can differ
 * from [total] by the cost of that rendering. The gateway therefore trims to the session `ContextPolicy.hardLimit`,
 * which reserves the policy's own hard margin (`hardInputLimit - hardMargin`) below the send-time bound — a deliberate
 * stricter bound, not a guarantee that the two totals are identical. The acceptance tests' discriminating assertion is
 * the send-time `prepare` call itself, never a claim that the two totals are identical.
 *
 * A `SUMMARY` segment is **not a turn**: it never participates in turn eviction. But it does count toward [total] —
 * it gets no exemption from the budget check.
 *
 * @param measure the per-segment token-unit cost. Defaults to [defaultSegmentMeasure], which includes image units.
 * @param hardDynamicHeadChars how much of a HARD-kept dynamic tail segment's head survives (Task 12). 400 is a policy
 *   starting point, not a model constant: the caller may tune it.
 * @param hardDynamicTailChars the tail counterpart of [hardDynamicHeadChars]; 100 is likewise a tunable starting point.
 */
class TurnTrimmer(
    private val measure: (Segment) -> Int = ::defaultSegmentMeasure,
    private val hardDynamicHeadChars: Int = 400,
    private val hardDynamicTailChars: Int = 100,
) {

    fun trim(frame: RequestFrame, limitTokens: Int, mode: TrimMode = TrimMode.STANDARD): TrimReport {
        val hard = if (mode == TrimMode.HARD) hardTruncate(frame.conversationFlow) else null
        val prepared = when (mode) {
            TrimMode.STANDARD -> frame.conversationFlow
            TrimMode.AGGRESSIVE -> dropOlderToolRounds(frame.conversationFlow)
            TrimMode.HARD -> hard!!.flow
        }
        val hardDroppedTurns = if (mode == TrimMode.HARD) {
            val lastTurn = frame.conversationFlow.indexOfLast { isTurnStart(it) }
            if (lastTurn < 0) 0 else frame.conversationFlow.take(lastTurn).count { isTurnStart(it) }
        } else {
            0
        }
        val typeAware = applyTypeAware(prepared)
        val flow = typeAware.flow.toMutableList()
        var droppedTurns = hardDroppedTurns
        val droppedOrigins = mutableListOf<SegmentOrigin>()
        // Task 12: indexes into `flow` of the HARD dynamic segments that were shortened. `applyTypeAware` maps the list
        // one-to-one in order (it never reorders or resizes), so [HardTruncation.truncatedIndexes] stay valid here; every
        // removal below goes through `removeSegmentAt`, which keeps them pointing at the same segments.
        val truncatedIndexes = hard?.truncatedIndexes?.toMutableSet() ?: mutableSetOf()

        fun total(): Int = FIXED_OVERHEAD +
            measure(Segment(SegmentOrigin.INSTRUCTIONS, true, frame.systemText)) +
            flow.sumOf(measure)

        /** Removes one segment and shifts the truncated-index bookkeeping. */
        fun removeSegmentAt(index: Int): Segment {
            val removed = flow.removeAt(index)
            truncatedIndexes.remove(index)
            val shifted = truncatedIndexes.filter { it > index }
            if (shifted.isNotEmpty()) {
                truncatedIndexes.removeAll(shifted.toSet())
                shifted.forEach { truncatedIndexes += it - 1 }
            }
            return removed
        }

        // HARD only (Task 12, spec §6.6): truncation is not enough, so give up whole dynamic origins in the declared
        // order — SKILL_BODY, then MEMORY_EVIDENCE, then PERCEPTION — one segment at a time, the oldest/first first.
        // The loop never touches USER_INPUT/RUNTIME_STATE/RETRY/the newest turn: those origins are not in its order, and
        // it stops the moment the frame fits. Every removal is recorded in `droppedOrigins`.
        if (mode == TrimMode.HARD) {
            for (origin in HARD_CLEANUP_ORDER) {
                while (total() > limitTokens) {
                    val index = flow.indexOfFirst { it.origin == origin }
                    if (index < 0) break
                    droppedOrigins += removeSegmentAt(index).origin
                }
            }
        }

        // ① 旧整回合：一组 = 一条 role=USER 的 TRANSCRIPT（或它引发的 TOOL_ROUND 段）到下一个
        // USER 之前。整组淘汰 ⇒ 永远不发"半个回合"。从最旧一组开始，只在还超预算时才落。
        while (total() > limitTokens) {
            val start = flow.indexOfFirst { isTurnStart(it) }
            if (start < 0) break
            val next = flow.subList(start + 1, flow.size).indexOfFirst { isTurnStart(it) }
            val stop = if (next < 0) flow.size else start + 1 + next
            // 绝不吞掉 USER_INPUT 与 SUMMARY：它们不在回合里。索引降序删，否则索引会漂。
            // slice 非空是构造保证：`start` 自身落在 [start, stop) 内，且 isTurnStart ⇒ TRANSCRIPT
            // ⇒ isEvictable 必为真。故不存在（也不需要）空集分支。每轮至少删掉 flow[start]，
            // flow 单调变短、total() 单调变小 ⇒ 循环必终止，① 不会空转。
            val slice = (start until stop).filter { isEvictable(flow[it]) }
            slice.sortedDescending().forEach { removeSegmentAt(it) }
            droppedTurns++
        }

        // ② 最不相关记忆：按相关性整条丢，从最不相关（列表末尾）开始。SUMMARY 与 USER_INPUT 不参与。
        // 装配形状下（记忆段在最后一组之后）这一刀通常已经没有对象：① 的最后一组 slice 一直延伸到
        // `flow.size`，把记忆段一起带走并记在 droppedTurns 上（见 `TrimOrder` 的 KDoc）。它保留下来是为了
        // 没有任何回合组可让的请求（`[memory, USER_INPUT]`），以及为后续阶段保留"按相关性单独让出记忆"的能力。
        var index = flow.lastIndex
        while (total() > limitTokens && index >= 0) {
            if (flow[index].origin == SegmentOrigin.MEMORY_EVIDENCE) {
                droppedOrigins += removeSegmentAt(index).origin
            }
            index--
        }

        // Task 12: a segment that was truncated and then given up is reported as dropped, not truncated.
        val truncatedOrigins = flow.indices
            .filter { it in truncatedIndexes }
            .map { flow[it].origin }

        return TrimReport(
            kept = frame.copy(conversationFlow = flow.toList()),
            droppedTurns = droppedTurns,
            droppedOrigins = droppedOrigins.toList(),
            compressionOwed = total() > limitTokens,
            elidedUiTrees = typeAware.elidedUiTrees,
            elidedImages = typeAware.elidedImages,
            elidedToolResults = typeAware.elidedToolResults,
            truncatedOrigins = truncatedOrigins,
        )
    }

    // ── ⓪ type-aware omission ────────────────────────────────────────────────────────────────────

    private data class TypeAwareResult(
        val flow: List<Segment>,
        val elidedUiTrees: Int,
        val elidedImages: Int,
        val elidedToolResults: Int,
    )

    /** The two new text/truncation rules, then the global image-retention rule. Order is the spec's 1–2–3. */
    private fun applyTypeAware(flow: List<Segment>): TypeAwareResult {
        val lastOutput = lastOutputEntry(flow)
        var elidedUiTrees = 0
        var elidedToolResults = 0
        val textEdited = flow.mapIndexed { segmentIndex, segment ->
            if (segment.origin != SegmentOrigin.TOOL_ROUND) return@mapIndexed segment
            val entries = parseToolRound(segment.text) ?: return@mapIndexed segment
            var changed = false
            val rewritten = entries.mapIndexed { entryIndex, entry ->
                if (entry.string("type") != "output") return@mapIndexed entry
                val isLast = lastOutput != null &&
                    lastOutput.segmentIndex == segmentIndex &&
                    lastOutput.entryIndex == entryIndex
                if (isLast) return@mapIndexed entry
                val output = entry.string("output") ?: return@mapIndexed entry
                val header = elideUiTree(output)
                if (header != null) {
                    elidedUiTrees++
                    changed = true
                    return@mapIndexed entry.withOutput(header, truncated = false)
                }
                if (output.length > LONG_TOOL_RESULT_CHARS) {
                    elidedToolResults++
                    changed = true
                    return@mapIndexed entry.withOutput(headTail(output), truncated = true)
                }
                entry
            }
            if (!changed) segment else segment.copy(text = JsonArray(rewritten).toString())
        }
        val images = retainNewestImages(textEdited)
        return TypeAwareResult(
            flow = images.flow,
            elidedUiTrees = elidedUiTrees,
            elidedImages = images.elided,
            elidedToolResults = elidedToolResults,
        )
    }

    private data class OutputRef(val segmentIndex: Int, val entryIndex: Int)

    /** The last `type: "output"` entry in the flow: the one tool result the context may not trim. */
    private fun lastOutputEntry(flow: List<Segment>): OutputRef? {
        var found: OutputRef? = null
        flow.forEachIndexed { segmentIndex, segment ->
            if (segment.origin != SegmentOrigin.TOOL_ROUND) return@forEachIndexed
            val entries = parseToolRound(segment.text) ?: return@forEachIndexed
            entries.forEachIndexed { entryIndex, entry ->
                if (entry.string("type") == "output") found = OutputRef(segmentIndex, entryIndex)
            }
        }
        return found
    }

    /**
     * A tool result whose `observation.screen` is a compact UI tree, with **only that member** replaced by a header
     * that keeps the refs and the summary; `execution` and `data` stay byte-for-byte the tool's result.
     *
     * 0.3 Phase 2.2 Task 8 re-point: the unified envelope
     * (`{execution, observation|null, data}`) carries the screen tree in `observation.screen` as a string; `data` no
     * longer repeats it. The optional `data.action`/`data.rev`/`data.app` metadata is read only when the tool kept those
     * tool-specific facts (the `device` tool does), so a shell/open observation still elides with `?` placeholders.
     * A result whose observation is `null` — a pure tool, or an unmigrated tool whose `data` happens to hold a screen
     * object — is deliberately **not** a UI tree this cut can elide: reading `data.screen` again is what Task 8 removed.
     * The legacy no-perception `data.screen` is a non-production composition, documented on [TurnTrimmer]; ⓪ remains
     * effective for the production device UI tree in `observation.screen`.
     */
    private fun elideUiTree(output: String): String? {
        val envelope = parseObject(output) ?: return null
        val observation = envelope["observation"] as? JsonObject ?: return null
        val screen = observation.string("screen")?.takeIf { it.isNotBlank() } ?: return null
        val data = envelope["data"] as? JsonObject
        val action = data?.string("action") ?: "?"
        val revision = data?.let { (it["rev"] as? JsonPrimitive)?.contentOrNull } ?: "?"
        val app = data?.string("app") ?: observation.string("foregroundPackage")
        val lines = screen.lineSequence().count { it.isNotBlank() }
        val refs = REF_PATTERN.findAll(screen).map { it.value }.distinct().take(MAX_ELIDED_REFS).toList()
        val header = buildString {
            append("[screen observation elided: action=").append(action)
            append(" rev=").append(revision)
            if (app != null) append(" app=").append(app)
            append(" lines=").append(lines)
            append(" refs=").append(if (refs.isEmpty()) "-" else refs.joinToString(","))
            append(" · 已压缩]")
        }
        // Replace exactly `observation.screen`; every other member of the envelope (and of the observation) survives.
        val rewrittenObservation = JsonObject(
            LinkedHashMap(observation).apply { put("screen", JsonPrimitive(header)) },
        )
        return JsonObject(
            LinkedHashMap(envelope).apply { put("observation", rewrittenObservation) },
        ).toString()
    }

    private fun headTail(text: String): String {
        val head = text.take(TOOL_RESULT_HEAD_CHARS)
        val tail = text.takeLast(TOOL_RESULT_TAIL_CHARS)
        val elided = text.length - head.length - tail.length
        return "$head\n...[truncated: $elided chars elided]...\n$tail"
    }

    private data class ImageRetention(val flow: List<Segment>, val elided: Int)

    private sealed interface ImageSlot {
        val segmentIndex: Int
        val imageIndex: Int
    }

    private data class ToolImageSlot(
        override val segmentIndex: Int,
        override val imageIndex: Int,
        val entryIndex: Int,
    ) : ImageSlot

    private data class SegmentImageSlot(
        override val segmentIndex: Int,
        override val imageIndex: Int,
    ) : ImageSlot

    /**
     * Keeps the newest [IMAGE_KEEP] images across the whole flow and turns every older reference into a text summary.
     *
     * The two carriers are visited in flow order — a `TOOL_ROUND` output's `images` array first, then a non-tool
     * segment's structured [Segment.images] — so "newest" is the same order the wire will render.
     */
    private fun retainNewestImages(flow: List<Segment>): ImageRetention {
        val slots = mutableListOf<ImageSlot>()
        flow.forEachIndexed { segmentIndex, segment ->
            if (segment.origin == SegmentOrigin.TOOL_ROUND) {
                parseToolRound(segment.text)?.forEachIndexed { entryIndex, entry ->
                    if (entry.string("type") != "output") return@forEachIndexed
                    val images = entry["images"] as? JsonArray ?: return@forEachIndexed
                    images.forEachIndexed { imageIndex, _ ->
                        slots += ToolImageSlot(segmentIndex, imageIndex, entryIndex)
                    }
                }
            }
            segment.images.forEachIndexed { imageIndex, _ ->
                slots += SegmentImageSlot(segmentIndex, imageIndex)
            }
        }
        if (slots.size <= IMAGE_KEEP) return ImageRetention(flow, 0)
        val elided = slots.take(slots.size - IMAGE_KEEP).toSet()
        val bySegment = elided.groupBy { it.segmentIndex }
        var count = 0
        val rewritten = flow.mapIndexed { segmentIndex, segment ->
            val gone = bySegment[segmentIndex] ?: return@mapIndexed segment
            count += gone.size
            if (segment.origin == SegmentOrigin.TOOL_ROUND) {
                val entries = parseToolRound(segment.text)
                val toolGone = gone.filterIsInstance<ToolImageSlot>().groupBy { it.entryIndex }
                var rewrittenSegment = segment
                if (entries != null && toolGone.isNotEmpty()) {
                    val rewrittenEntries = entries.mapIndexed { entryIndex, entry ->
                        val indexes = toolGone[entryIndex]?.map { it.imageIndex }?.toSet() ?: return@mapIndexed entry
                        val images = entry["images"] as? JsonArray ?: return@mapIndexed entry
                        val kept = mutableListOf<JsonElement>()
                        val summaries = mutableListOf<String>()
                        images.forEachIndexed { imageIndex, image ->
                            if (imageIndex in indexes) {
                                summaries += imageSummary(image as? JsonObject)
                            } else {
                                kept += image
                            }
                        }
                        val output = entry.string("output").orEmpty()
                        val text = (listOf(output) + summaries).filter { it.isNotEmpty() }.joinToString("\n")
                        entry.withOutput(text, truncated = false, images = kept)
                    }
                    rewrittenSegment = segment.copy(text = JsonArray(rewrittenEntries).toString())
                }
                val segmentGone = gone.filterIsInstance<SegmentImageSlot>().map { it.imageIndex }.toSet()
                if (segmentGone.isEmpty()) return@mapIndexed rewrittenSegment
                val keptImages = rewrittenSegment.images.filterIndexed { imageIndex, _ -> imageIndex !in segmentGone }
                val summaries = rewrittenSegment.images
                    .filterIndexed { imageIndex, _ -> imageIndex in segmentGone }
                    .map(::imageSummary)
                rewrittenSegment.copy(
                    text = (listOf(rewrittenSegment.text) + summaries).joinToString("\n"),
                    images = keptImages,
                )
            } else {
                val segmentGone = gone.filterIsInstance<SegmentImageSlot>().map { it.imageIndex }.toSet()
                val kept = segment.images.filterIndexed { imageIndex, _ -> imageIndex !in segmentGone }
                val summaries = segment.images.filterIndexed { imageIndex, image -> imageIndex in segmentGone }
                    .map { image -> imageSummary(image) }
                segment.copy(
                    text = (listOf(segment.text) + summaries).joinToString("\n"),
                    images = kept,
                )
            }
        }
        return ImageRetention(rewritten, count)
    }

    private fun imageSummary(image: JsonObject?): String {
        val id = image?.string("artifactId") ?: "?"
        val mime = image?.string("mime") ?: "?"
        val width = image?.get("width")?.let { (it as? JsonPrimitive)?.intOrNull } ?: 0
        val height = image?.get("height")?.let { (it as? JsonPrimitive)?.intOrNull } ?: 0
        return "[image $id $mime ${width}x$height (elided)]"
    }

    private fun imageSummary(image: ImageRef): String =
        "[image ${image.artifactId} ${image.mime} ${image.width}x${image.height} (elided)]"

    /** Rebuilds one output entry, preserving key order and adding the truncation marker only when asked. */
    private fun JsonObject.withOutput(
        output: String,
        truncated: Boolean,
        images: List<JsonElement>? = null,
    ): JsonObject {
        val map = LinkedHashMap<String, JsonElement>(this)
        map["output"] = JsonPrimitive(output)
        if (truncated) map["truncated"] = JsonPrimitive(true)
        if (images != null) {
            if (images.isEmpty()) map.remove("images") else map["images"] = JsonArray(images)
        }
        return JsonObject(map)
    }

    // ── recovery modes (R70) ─────────────────────────────────────────────────────────────────────

    /**
     * Overflow recovery ①: every `TOOL_ROUND` segment before the newest contiguous tool-round run leaves the context.
     *
     * The newest run is kept intact so the current call/output pairing is not broken; older rounds are the expensive
     * history the model can live without for one emergency resend. Conversation text is untouched — except that two
     * assistant texts separated only by a dropped round become adjacent, a shape the endpoint and `ContextBudgeter`
     * reject by design (an assistant text may follow another only after a tool round). Those two texts are one
     * assistant turn that happened to continue across the round, so they are merged into one message instead of leaving
     * an unsendable transcript. Recovery ② removes the remaining conversation if it is still too large.
     */
    private fun dropOlderToolRounds(flow: List<Segment>): List<Segment> {
        val lastTool = flow.indexOfLast { it.origin == SegmentOrigin.TOOL_ROUND }
        if (lastTool < 0) return flow
        var newestRunStart = lastTool
        while (newestRunStart > 0 && flow[newestRunStart - 1].origin == SegmentOrigin.TOOL_ROUND) {
            newestRunStart--
        }
        val withoutOldRounds = flow.filterIndexed { index, segment ->
            segment.origin != SegmentOrigin.TOOL_ROUND || index >= newestRunStart
        }
        return mergeAdjacentAssistantTexts(withoutOldRounds)
    }

    /**
     * Merges every pair of adjacent assistant transcript texts into one message, preserving flow order.
     *
     * This is what keeps recovery ① sendable: dropping a tool round brings the assistant opener that introduced it and
     * the assistant reply that followed it together, and `ContextBudgeter` rejects two assistant texts in a row unless
     * a tool round sits between them. Merging keeps the text whole while restoring the
     * `USER → ASSISTANT → (tool run →) ASSISTANT` shape the endpoint accepts. The production transcript attaches no
     * images to these texts; if a frame does, they are concatenated rather than dropped.
     */
    private fun mergeAdjacentAssistantTexts(flow: List<Segment>): List<Segment> {
        val merged = mutableListOf<Segment>()
        for (segment in flow) {
            val previous = merged.lastOrNull()
            if (previous != null && isAssistantTranscript(previous) && isAssistantTranscript(segment)) {
                merged[merged.lastIndex] = previous.copy(
                    text = previous.text + "\n\n" + segment.text,
                    images = previous.images + segment.images,
                )
            } else {
                merged += segment
            }
        }
        return merged
    }

    /** A conversation-flow assistant text: `ContextBudgeter` renders a `TRANSCRIPT` without `USER` as the assistant. */
    private fun isAssistantTranscript(segment: Segment): Boolean =
        segment.origin == SegmentOrigin.TRANSCRIPT && segment.role != SegmentRole.USER

    /**
     * Overflow recovery ②: system slot + the newest whole turn + the current input, reduced to the newest tool round.
     *
     * Within the newest turn only the latest contiguous tool-round run survives, together with its assistant opener
     * (a tool run must be preceded by an assistant text, and the user turn must not end on a lone half) and the
     * assistant text(s) that follow it (the turn's closing answer). The older rounds of the same turn are exactly the
     * "compression was not enough" cost this cut exists to remove; keeping them let a multi-round turn end
     * `CONTEXT_OVERFLOW` at a size this truncation already fits. A newest turn with no tool round keeps its text only.
     * Request-scoped `RUNTIME_STATE`/`RETRY` metadata and `USER_INPUT` always survive in flow order.
     *
     * ## The dynamic tail is kept, shortened (Task 12, spec §6.6)
     *
     * A request-scoped tail segment (`PERCEPTION`/`MEMORY_EVIDENCE`/`SKILL_BODY`/`RUNTIME_STATE`) is **not** dropped
     * whole: it survives as `head + "\n...[truncated]...\n" + tail` (the cap is the constructor's
     * [hardDynamicHeadChars]/[hardDynamicTailChars] policy pair; an already-shorter segment is unchanged), and its
     * index is returned in [HardTruncation.truncatedIndexes] so the report can name the origin. A stored `SUMMARY` is
     * still exactly the "old context" this hard cut removes, and it is not in the dynamic tail. If the shortened frame
     * is still over budget, `trim` cleans whole dynamic origins in the declared order (SKILL_BODY → MEMORY_EVIDENCE →
     * PERCEPTION, recorded in `droppedOrigins`); this function only truncates, it does not measure.
     *
     * The returned indexes address the returned list; `trim` keeps them in sync while it removes segments.
     */
    private fun hardTruncate(flow: List<Segment>): HardTruncation {
        val lastTurn = flow.indexOfLast { isTurnStart(it) }
        val kept = if (lastTurn < 0) flow else flow.subList(lastTurn, flow.size)
        val lastTool = kept.indexOfLast { it.origin == SegmentOrigin.TOOL_ROUND }
        val survivors = if (lastTool < 0) {
            kept.filter {
                it.origin == SegmentOrigin.TRANSCRIPT ||
                    it.origin == SegmentOrigin.USER_INPUT ||
                    it.origin == SegmentOrigin.RETRY ||
                    it.origin in HARD_DYNAMIC_ORIGINS
            }
        } else {
            var newestRunStart = lastTool
            while (newestRunStart > 0 && kept[newestRunStart - 1].origin == SegmentOrigin.TOOL_ROUND) {
                newestRunStart--
            }
            // The assistant text immediately before the newest run. Every production round is opened by one (AgentLoop
            // appends a placeholder when the model sent no text); if a frame has none, the run is kept as it is and the
            // send-time budgeter remains the judge of the orphaned-call shape.
            val opener = (newestRunStart - 1 downTo 0).firstOrNull { index -> isAssistantTranscript(kept[index]) }
            // The turn's own opening user text: the history must open on a user item and the turn must not be a lone half.
            val turnUser = (0 until newestRunStart).firstOrNull { index ->
                kept[index].origin == SegmentOrigin.TRANSCRIPT && kept[index].role == SegmentRole.USER
            }
            val keep = mutableSetOf<Int>()
            // Request-scoped metadata, the dynamic tail and the current input are never evicted, wherever the turn put them.
            kept.forEachIndexed { index, segment ->
                if (segment.origin == SegmentOrigin.USER_INPUT ||
                    segment.origin == SegmentOrigin.RETRY ||
                    segment.origin in HARD_DYNAMIC_ORIGINS
                ) {
                    keep += index
                }
            }
            turnUser?.let(keep::add)
            opener?.let(keep::add)
            (newestRunStart..lastTool).forEach(keep::add)
            // Everything the assistant said after its last tool round is the turn's closing answer, kept in flow order.
            for (index in (lastTool + 1) until kept.size) {
                if (kept[index].origin == SegmentOrigin.TRANSCRIPT) keep += index
            }
            kept.filterIndexed { index, _ -> index in keep }
        }
        val truncatedIndexes = mutableSetOf<Int>()
        val shortened = survivors.mapIndexed { index, segment ->
            if (segment.origin !in HARD_DYNAMIC_ORIGINS) return@mapIndexed segment
            val text = truncateDynamicText(segment.text) ?: return@mapIndexed segment
            truncatedIndexes += index
            segment.copy(text = text)
        }
        return HardTruncation(shortened, truncatedIndexes)
    }

    /** The shortened HARD survivors plus the indexes (into [flow]) of the dynamic segments that were shortened. */
    private data class HardTruncation(val flow: List<Segment>, val truncatedIndexes: Set<Int>)

    /**
     * The head+tail form of one dynamic-tail segment, or `null` when the segment is already short enough to keep whole.
     *
     * The cap pair is a policy starting point, not a model constant. The marker is fixed text, never the segment's
     * content, so the kept request says exactly where the middle went. Both cuts are code-point safe: a cap that lands
     * inside a surrogate pair drops the orphan half instead of emitting an unpaired surrogate into the request.
     */
    private fun truncateDynamicText(text: String): String? {
        val head = text.takeCodePointSafe(hardDynamicHeadChars)
        val tail = text.takeLastCodePointSafe(hardDynamicTailChars)
        if (text.length <= head.length + tail.length + HARD_TRUNCATED_MARKER.length) return null
        return head + HARD_TRUNCATED_MARKER + tail
    }

    /** `take(count)` without splitting a surrogate pair; an orphan high surrogate is dropped. */
    private fun String.takeCodePointSafe(count: Int): String {
        if (count <= 0) return ""
        if (count >= length) return this
        val end = if (Character.isHighSurrogate(this[count - 1]) && Character.isLowSurrogate(this[count])) {
            count - 1
        } else {
            count
        }
        return substring(0, end)
    }

    /** `takeLast(count)` without splitting a surrogate pair; an orphan low surrogate is dropped. */
    private fun String.takeLastCodePointSafe(count: Int): String {
        if (count <= 0) return ""
        if (count >= length) return this
        val start = length - count
        val from = if (Character.isLowSurrogate(this[start]) && Character.isHighSurrogate(this[start - 1])) {
            start + 1
        } else {
            start
        }
        return substring(from)
    }

    // ── shared helpers ───────────────────────────────────────────────────────────────────────────

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

    private fun parseToolRound(text: String): List<JsonObject>? = try {
        val array = Json.parseToJsonElement(text) as? JsonArray ?: return null
        if (array.any { it !is JsonObject }) return null
        array.map { it as JsonObject }
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun parseObject(text: String): JsonObject? = try {
        Json.parseToJsonElement(text) as? JsonObject
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun JsonObject.string(name: String): String? =
        (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content

    companion object {
        /** R69: the newest K images stay whole; older ones become a text summary. */
        const val IMAGE_KEEP: Int = 3

        /** R69: a longer non-last tool result is worth only its head and tail. */
        const val LONG_TOOL_RESULT_CHARS: Int = 4_096

        /** R69: how much of a long tool result's head and tail survive. */
        const val TOOL_RESULT_HEAD_CHARS: Int = 2_048
        const val TOOL_RESULT_TAIL_CHARS: Int = 512

        /** A screen header keeps its visible refs, bounded so the header cannot become the new cost. */
        const val MAX_ELIDED_REFS: Int = 24

        /**
         * Task 12: the exact marker between a HARD-kept dynamic segment's head and tail. It is fixed text — never a
         * character count — so the kept request states that a middle was removed without pricing it.
         */
        const val HARD_TRUNCATED_MARKER: String = "\n...[truncated]...\n"

        /**
         * Task 12: request-scoped context keeps its origin when the HARD cut shortens it. `RUNTIME_STATE` is here as
         * well because it is request metadata, not a turn, and it uses the same cap as the rest of the tail.
         */
        private val HARD_DYNAMIC_ORIGINS = setOf(
            SegmentOrigin.PERCEPTION,
            SegmentOrigin.MEMORY_EVIDENCE,
            SegmentOrigin.SKILL_BODY,
            SegmentOrigin.RUNTIME_STATE,
        )

        /**
         * Task 12: the fixed order a still-over-budget HARD frame gives whole dynamic origins up in. It is a declared
         * order, not flow order, and each origin is removed one segment at a time (oldest/first first). `USER_INPUT`,
         * `RUNTIME_STATE`, `RETRY` and the newest turn are deliberately absent: the cleanup may never pick them.
         */
        private val HARD_CLEANUP_ORDER = listOf(
            SegmentOrigin.SKILL_BODY,
            SegmentOrigin.MEMORY_EVIDENCE,
            SegmentOrigin.PERCEPTION,
        )

        private val REF_PATTERN = Regex("#n\\d+")
    }
}

/**
 * The fixed `+32` `ContextBudgeter` adds once on top of the per-message costs
 * (`Math.addExact(32L, message(request.instructions))`), in token units like every other term. It is a
 * structural part of the shared formula: `TurnTrimmer.total` adds it once and `ContextBudgeter.prepare`
 * adds it once. (`ContextPolicy.hardLimit` is the trim window; it already reserves the policy's hard margin.)
 */
internal const val FIXED_OVERHEAD = 32

/**
 * 与 `ContextBudgeter.message()` 同口径：TokenMeter token 单位 + 每条 32 的记账开销。
 */
internal fun tokenUnitsPlusOverhead(text: String): Int = TokenMeter.estimate(text) + 32

/**
 * The default per-segment price: the text cost above plus one [ImageBudget.UNITS_PER_IMAGE] for every image the
 * adapter will put on the wire — a `TOOL_ROUND` output's `images` array (the production carrier) and a structured
 * [Segment.images] reference. R68: `ContextBudgeter` charges the same per image, so the two stages cannot disagree.
 */
internal fun defaultSegmentMeasure(segment: Segment): Int {
    var cost = tokenUnitsPlusOverhead(segment.text)
    cost += imageUnits(segment.images.size)
    if (segment.origin == SegmentOrigin.TOOL_ROUND) cost += imageUnits(toolRoundImageCount(segment.text))
    return cost
}

private fun imageUnits(count: Int): Int =
    (count.toLong() * ImageBudget.UNITS_PER_IMAGE).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

/** How many image refs a `TOOL_ROUND` segment's JSON carries; 0 for a non-tool or unreadable body. */
internal fun toolRoundImageCount(text: String): Int = try {
    val array = Json.parseToJsonElement(text) as? JsonArray ?: return 0
    array.sumOf { element ->
        val obj = element as? JsonObject ?: return@sumOf 0
        (obj["images"] as? JsonArray)?.size ?: 0
    }
} catch (_: IllegalArgumentException) {
    0
}
