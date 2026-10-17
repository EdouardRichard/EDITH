package com.sheen.rokid.output

import com.sheen.rokid.plugin.*
import java.text.BreakIterator
import java.util.Locale

class ReadableHintAssembler(
    private val clock: MonotonicClock,
    private val scheduler: DeadlineScheduler,
    private val emit: (String) -> Unit,
    private val mergeMillis: Long = 150,
    private val maxCodePoints: Int = MAX_CODEPOINTS,
) {
    init { require(mergeMillis in 100..200); require(maxCodePoints > 0) }

    companion object {
        /**
         * How long an assembled hint may become (codepoints).
         *
         * The owner's 2026-10-14 ruling: 超长通知不能截断，要推送眼镜端滚动显示. A hint taller than the glasses' window is
         * slid through it (`HintAutoScroll`), so this bound exists to keep a **document** off the panel, not to make a
         * notification shorter. It is kept equal to `com.sheen.rokid.output.HintWireCodec.MAX_TEXT_CODEPOINTS` and
         * `com.sheen.rokid.host.HintPolicy`'s bound, and a test asserts all three agree.
         */
        const val MAX_CODEPOINTS = 400
    }
    private val buffer = StringBuilder()
    private var terminal: ModelEnd? = null
    private var cancelled = false
    private var pending = false
    private var timer: CancellationHandle? = null
    private var last = ""
    var safeCode: String? = null
        private set

    @Synchronized fun append(delta: String) {
        if (cancelled || terminal != null || delta.isEmpty()) return
        buffer.append(delta)
        schedule()
    }
    @Synchronized fun finish(end: ModelEnd) {
        if (cancelled || terminal != null) return
        terminal = end
        schedule()
    }
    @Synchronized fun cancel() {
        if (cancelled) return
        cancelled = true; buffer.clear(); pending = false
        timer?.cancel(); timer = null
    }
    private fun schedule() {
        if (pending) return
        pending = true
        val delay = mergeMillis * 1_000_000
        val now = clock.nowNanos()
        val handle = scheduler.at(if (now > Long.MAX_VALUE - delay) Long.MAX_VALUE else now + delay) {
            val ready = synchronized(this) {
                if (!cancelled && pending) {
                    pending = false; timer = null
                    val hint = readable()
                    if (hint.isNotEmpty() && hint != last) { last = hint; hint } else null
                } else null
            }
            if (ready != null && synchronized(this) { !cancelled }) emit(ready)
        }
        if (cancelled || !pending) handle.cancel() else timer = handle
    }
    private fun readable(): String {
        val text = buffer.toString()
        val points = mutableListOf<Pair<String, Boolean>>()
        var start = 0
        var lineStart = false
        // BreakIterator supplies language-aware boundaries; terminal punctuation and newline
        // additionally define Chinese sentences and streamed bullet boundaries.
        val iterator = BreakIterator.getSentenceInstance(Locale.CHINESE)
        iterator.setText(text)
        val boundaries = mutableSetOf<Int>()
        var boundary = iterator.first()
        while (boundary != BreakIterator.DONE) { boundaries += boundary; boundary = iterator.next() }
        for (i in text.indices) {
            val ch = text[i]
            val newlinePoint = ch == '\n' && Regex("^(?:[-•*]|\\d+[.)、])\\s+.+").matches(text.substring(start, i).trim())
            if (ch in "。！？" || newlinePoint || (i + 1 in boundaries && ch in ".!?")) {
                val segment = text.substring(start, i + 1)
                points += segment.trim() to (lineStart || segment.startsWith('\n') || segment.startsWith("\r\n"))
                start = i + 1; lineStart = ch == '\n'
            }
        }
        if (terminal == ModelEnd.COMPLETED && start < text.length) points += text.substring(start).trim() to lineStart
        var result = ""
        var count = 0
        for ((point, newLine) in points) {
            if (point.isEmpty()) continue
            val content = point.replace(Regex("^(?:[-•*]|\\d+[.)、])\\s*"), "").trimEnd('。', '！', '？', '.', '!', '?').trim()
            if (content.codePointCount(0, content.length) < 2) break
            if (!validUnicode(point)) { safeCode = "HINT_INVALID_UNICODE"; break }
            val separator = if (result.isNotEmpty() && newLine) "\n" else ""
            val candidate = result + separator + point
            // The **bound** is the stopping rule, not a sentence count. An earlier version stopped at three sentences
            // as well, which is what truncated a long notification to its first three sentences with nothing said -
            // the defect the owner reported on 2026-10-14. A hint the bound allows is displayed whole and scrolled by
            // the glasses; a hint past it is cut here and reported through `safeCode`.
            if (candidate.codePointCount(0, candidate.length) > maxCodePoints) {
                safeCode = "HINT_TOO_LONG"; break
            }
            result = candidate
            count++
        }
        return result
    }
    private fun validUnicode(text: String): Boolean {
        var i = 0
        while (i < text.length) {
            if (text[i].isHighSurrogate()) {
                if (i + 1 >= text.length || !text[i + 1].isLowSurrogate()) return false
                i += 2
            } else { if (text[i].isLowSurrogate()) return false; i++ }
        }
        return true
    }
}
