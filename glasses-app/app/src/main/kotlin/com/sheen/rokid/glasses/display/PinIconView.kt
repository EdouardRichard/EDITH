package com.sheen.rokid.glasses.display

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View

/**
 * 0.23 P4 (spec §5.2.4): the official 20x20 pin, drawn instead of a text label once the wearer pins the reading
 * position.
 *
 * The official `icon_ai_hold` is a vector at `GREEN_NORMAL` with `fillAlpha`/`strokeAlpha` 0.4; the shape is a round pin
 * head on a short stem. This is a small approximation of that vector - the meaning (a pin, at 40% green) is the part
 * the wearer reads, and `GlassesPanelSpec.PIN_ICON_ALPHA` pins the opacity.
 */
class PinIconView(context: Context) : View(context) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = GlassesPanelSpec.GREEN_NORMAL
        alpha = (GlassesPanelSpec.PIN_ICON_ALPHA * 255).toInt()
        style = Paint.Style.FILL
    }

    /** Instrumentation probe: the official `fillAlpha`/`strokeAlpha` 0.4. */
    fun alphaForProbe(): Float = paint.alpha / 255f

    override fun onDraw(canvas: Canvas) {
        val centerX = width / 2f
        val headRadius = minOf(width, height) * 0.26f
        val headCenterY = height * 0.33f
        canvas.drawCircle(centerX, headCenterY, headRadius, paint)
        // The stem: a short vertical line from the head to a small base, so it reads as a pin rather than a dot.
        val stemWidth = maxOf(2f, width * 0.10f)
        canvas.drawRect(
            centerX - stemWidth / 2f,
            headCenterY + headRadius * 0.6f,
            centerX + stemWidth / 2f,
            height * 0.86f,
            paint,
        )
        canvas.drawRect(centerX - width * 0.28f, height * 0.86f, centerX + width * 0.28f, height * 0.94f, paint)
    }
}
