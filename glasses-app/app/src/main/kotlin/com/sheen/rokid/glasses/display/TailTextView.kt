package com.sheen.rokid.glasses.display

import android.content.Context
import android.graphics.Canvas
import android.widget.TextView

/**
 * 0.23 U6: a text view that shows the **last** [maxVisibleLines] lines of over-long text.
 *
 * The official panel caps a wearer line at four lines but keeps the newest words; `android.text.TextUtils.TruncateAt.START`
 * cannot express that: AOSP's `Layout.calculateEllipsis` only applies start ellipsis when the layout has **one** line
 * (`mMaximumVisibleLineCount == 1`), so a `maxLines = 4` + `ellipsize = START` `TextView` silently shows the *first*
 * four lines - the opposite of what the wearer needs. This view lays the full text out and draws its tail instead.
 *
 * The official `StaticLayoutView` does the same: `getLastLines` returns the substring at the tail's first line and then
 * sets `ellipsize = START` on that multi-line layout, where AOSP ignores it. No leading `…` is therefore drawn - adding
 * one would invent a signal the official panel does not show.
 *
 * It stays a `TextView` so the existing row plumbing, accessibility text and probe types do not fork.
 */
class TailTextView(
    context: Context,
    private val maxVisibleLines: Int = 4,
) : TextView(context) {

    init {
        require(maxVisibleLines > 0) { "MAX_VISIBLE_LINES_MUST_BE_POSITIVE" }
        // The full layout is needed to know which lines are the tail; `super.onDraw` is clipped and translated below.
        maxLines = Int.MAX_VALUE
        ellipsize = null
    }

    fun maxVisibleLinesForProbe(): Int = maxVisibleLines

    /** The lines the view actually shows, bottom-last; empty before the first layout pass. */
    fun visibleLineTextForProbe(): List<String> {
        val layout = layout ?: return emptyList()
        val first = (layout.lineCount - maxVisibleLines).coerceAtLeast(0)
        return (first until layout.lineCount).map { index ->
            text.subSequence(layout.getLineStart(index), layout.getLineEnd(index))
                .toString()
                .trimEnd('\n')
        }
    }

    fun isTailClippedForProbe(): Boolean = (layout?.lineCount ?: 0) > maxVisibleLines

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val layout = layout ?: return
        if (layout.lineCount == 0) return
        val first = (layout.lineCount - maxVisibleLines).coerceAtLeast(0)
        val tailHeight = layout.getLineBottom(layout.lineCount - 1) - layout.getLineTop(first)
        setMeasuredDimension(
            measuredWidth,
            paddingTop + paddingBottom + tailHeight,
        )
    }

    override fun onDraw(canvas: Canvas) {
        val layout = layout ?: return
        if (layout.lineCount <= maxVisibleLines) {
            super.onDraw(canvas)
            return
        }
        // Use the tail's own line metrics, not line 0's: an emoji/fallback line in the tail can be taller.
        val first = layout.lineCount - maxVisibleLines
        val top = layout.getLineTop(first)
        val tailHeight = layout.getLineBottom(layout.lineCount - 1) - top
        canvas.save()
        canvas.clipRect(
            paddingLeft,
            paddingTop,
            width - paddingRight,
            paddingTop + tailHeight,
        )
        canvas.translate(0f, -top.toFloat())
        super.onDraw(canvas)
        canvas.restore()
    }
}
