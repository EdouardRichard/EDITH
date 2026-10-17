package com.sheen.rokid.glasses.display

import com.sheen.rokid.contracts.AssistantRoute
import com.sheen.rokid.output.HintWireCodec

/**
 * What the wearer actually sees, decided without Android so it can be tested off-device.
 *
 * The rule the product fixes is "short points, nothing invented, nothing clipped mid-word". The phone bounds a hint to
 * [HintWireCodec.MAX_TEXT_CODEPOINTS] codepoints, so the only job left here is **layout**: split the text on sentence
 * boundaries, one point per line, and show all of them.
 *
 * **0.23 P4:** production now shows the raw answer text and lets [HintTicker] walk it; the sentence-splitting helpers
 * below are retained only for the pure unit tests that pin the old contract. Do not wire them back into the Activity.
 */
object HintPresentation {
    /**
     * The most codepoints this layer will render.
     *
     * Not a product rule - a defence. The phone already bounds every hint on the wire, and a hint that somehow arrives
     * longer than that is a bug worth reporting ([truncated]), not a document worth laying out.
     */
    const val MAX_RENDER_CODEPOINTS = HintWireCodec.MAX_TEXT_CODEPOINTS

    /** Sentence and line boundaries a hint may be split on. Deliberately conservative: no mid-word breaking. */
    private val separators = Regex("[\\r\\n]+|[。；;!?！？]")

    /** The points to show, all of them; a blank or absent hint renders as nothing at all. */
    fun points(text: String?): List<String> {
        if (text.isNullOrBlank()) return emptyList()
        return text.take(MAX_RENDER_CODEPOINTS).split(separators).map { it.trim() }.filter { it.isNotEmpty() }
    }

    /** The exact string the view shows: one point per line, or empty when there is nothing to display. */
    fun displayText(text: String?): String = points(text).joinToString("\n")

    /**
     * True when the hint carried more than this layer will render, so the caller can record the shortfall.
     *
     * Only reachable with text the phone should never have sent; a merely *long* hint is no longer truncated, it
     * scrolls.
     */
    fun truncated(text: String?): Boolean {
        if (text.isNullOrBlank()) return false
        return text.length > MAX_RENDER_CODEPOINTS
    }

    /**
     * What the layout actually did with the hint, as one greppable line.
     *
     * 0.23 P4 replaced the paced slide with the official 1 px/frame ticker, so the old `maxLines` / `clipped` /
     * `scrollMillis` fields are gone: the fact that matters now is that the ticker is the engine and how far it can
     * travel (`maxOffset`). The line count is only knowable after a layout pass, so the caller reports it from a
     * `post {}` - and this formatter is separate from the reading so it stays testable off-device. Counts and sizes,
     * never the text: the same rule as every other `P3_` line.
     */
    fun renderReport(lines: Int, chars: Int, maxOffsetPx: Int): String =
        "hint rendered lines=$lines chars=$chars ticker=true maxOffset=$maxOffsetPx"

    /**
     * What the bridge actually **received**, as one greppable line.
     *
     * `chars` is the received length of the payload string, not the sent one. Added by the 2026-10-14 ceiling
     * measurement: above a measured transport ceiling the payload arrives **cut short**, and `undecodable` alone
     * cannot tell "truncated in transit" from "the codec refused a well-formed message". The received length is the
     * fact that separates them - and it is a count, never the text (the same rule as every other `P3_`/bridge line).
     */
    fun inboundReport(json: String, decoded: String?): String =
        "inbound ${decoded ?: "undecodable"} chars=${json.length}"

    /**
     * What the conversation view did with one route, as one greppable line (Phase C).
     *
     * The same rule as every other `P3_`/bridge line: counts and a route name, never the text. It exists because
     * "the wearer saw nothing" and "the wearer saw a conversation that stopped growing" look identical in a log that
     * only reports hints — and the second one is what a broken `SAID` path produces.
     */
    fun conversationReport(route: AssistantRoute, entries: Int, chars: Int): String =
        "conversation route=${route.name} entries=$entries chars=$chars"
}
