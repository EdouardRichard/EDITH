package com.sheen.rokid.glasses.display

/** How one answer revision is put on the answer row. */
enum class AnswerTextMode {
    /** The streamed partial: assign the text as-is, no parse, no span churn while deltas are arriving. */
    PLAIN,

    /** The final `SHOW`: Markwon parses bold/list/code and the row becomes a `Spannable`. */
    MARKDOWN,
}

/**
 * 0.24 S4 Task 19 (spec §3.4/§5.6): only the final answer is Markdown; the streamed partial stays plain.
 *
 * The official `AiAdapter` calls `markwon.setMarkdown(ttsTv, ...)` on the settled TTS text. EDITH receives one
 * full-text `ANSWER` revision per model delta and Task 17 patches that `TextView` synchronously; parsing Markdown on
 * every delta would run the parser dozens of times a second for a line that is still being assembled. The task's
 * "partial plain/throttled Markwon (throttle ≤100 ms)" therefore takes the plain branch: the partial is assigned
 * directly, and the final `SHOW` - the only authoritative full text - is parsed by Markwon. The reveal/layout pass
 * behind the text keeps its official 50 ms coalescing ([RevealCoalescer]), which is inside the same ≤100 ms bound.
 *
 * This policy is pure so a future "parse everything" shortcut fails a JVM test instead of only costing device frames.
 */
object MarkdownPolicy {

    /**
     * The upper bound the task puts on a throttled partial Markdown pass. The plain branch does no parse at all, so it
     * is trivially inside the bound; the constant stays here so the render and its test cannot disagree about the cap.
     */
    const val MAX_PARTIAL_THROTTLE_MILLIS = 100L

    fun modeFor(final: Boolean): AnswerTextMode =
        if (final) AnswerTextMode.MARKDOWN else AnswerTextMode.PLAIN
}
