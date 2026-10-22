package com.sheen.rokid.glasses.display

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.view.View

/** Which edge of the conversation window a mask sits on. */
enum class MaskEdge { TOP, BOTTOM }

/**
 * 0.23 P3 (spec §5.1.1): the official 30 px black fades above and below the conversation window.
 *
 * The official assets are 481x30 black alpha gradients; the panel itself has no divider, card border or shadow - the
 * fades are the whole of the depth cue. Each view owns one `LinearGradient`, built on `onSizeChanged` and only drawn,
 * so a scrolling conversation does not allocate a shader per frame.
 */
class GradientMaskView(context: Context, private val edge: MaskEdge) : View(context) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var shader: LinearGradient? = null

    init {
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        if (width <= 0 || height <= 0) {
            shader = null
            return
        }
        shader = LinearGradient(
            0f, 0f, 0f, height.toFloat(),
            MaskGradient.colors(edge, GlassesPanelSpec.BLACK),
            MaskGradient.positions(),
            Shader.TileMode.CLAMP,
        )
    }

    override fun onDraw(canvas: Canvas) {
        val gradient = shader ?: return
        paint.shader = gradient
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        paint.shader = null
    }
}

/**
 * The masks' meaning, without a `Canvas`: the top fades out downwards, the bottom fades in downwards.
 *
 * Direction is the entire contract - swapping the two turns "content emerges from black" into "content vanishes into
 * black" - so it is pinned in JVM tests instead of being judged from a screenshot.
 */
object MaskGradient {
    const val HEIGHT_PX = GlassesPanelSpec.MASK_HEIGHT_PX
    val BLACK: Int = GlassesPanelSpec.BLACK

    fun colors(edge: MaskEdge, black: Int = BLACK): IntArray = when (edge) {
        MaskEdge.TOP -> intArrayOf(black, 0)
        MaskEdge.BOTTOM -> intArrayOf(0, black)
    }

    fun positions(): FloatArray = floatArrayOf(0f, 1f)
}
