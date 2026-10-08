package edith.glasses

/**
 * What the wearer actually sees, decided without Android so it can be tested off-device.
 *
 * The rule the product fixes is "at most three points, nothing invented, nothing clipped mid-word": the phone already
 * bounds a hint to [edith.output.HintWireCodec.MAX_TEXT_CODEPOINTS] codepoints, so this is about **layout**, not about
 * silently dropping content. [truncated] exists so a caller can record that a hint had more points than the glasses
 * showed, rather than leaving the loss invisible.
 */
object HintPresentation {
    /** How many points the fixed layout shows; the region is sized for three short lines. */
    const val MAX_POINTS = 3

    /** Sentence and line boundaries a hint may be split on. Deliberately conservative: no mid-word breaking. */
    private val separators = Regex("[\\r\\n]+|[。；;!?！？]")

    /** The points to show, at most [MAX_POINTS]; a blank or absent hint renders as nothing at all. */
    fun points(text: String?): List<String> {
        if (text.isNullOrBlank()) return emptyList()
        return text.split(separators).map { it.trim() }.filter { it.isNotEmpty() }.take(MAX_POINTS)
    }

    /** The exact string the view shows: one point per line, or empty when there is nothing to display. */
    fun displayText(text: String?): String = points(text).joinToString("\n")

    /** True when the hint carried more points than the layout shows, so the caller can record the shortfall. */
    fun truncated(text: String?): Boolean {
        if (text.isNullOrBlank()) return false
        return text.split(separators).count { it.isNotBlank() } > MAX_POINTS
    }
}
