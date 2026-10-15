package edith.glasses

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import android.animation.ValueAnimator
import android.view.animation.LinearInterpolator

/**
 * The loading ring drawn on the glasses (Phase C, spec §6.6 加载动画: 点线圆圈旋转).
 *
 * A ring of [LoadingIndicator.DOTS] dots whose brightness runs around the circle as it turns, so the direction of
 * rotation is readable from a single frame — a uniform ring would look like a still image.
 *
 * The geometry and the angle are the untestable half; the timing is [LoadingIndicator.angleFor], which is a pure
 * function and is unit-tested. [start] and [stop] are idempotent because the caller re-applies them on every inbound
 * message, and restarting an animation on each of those would make the ring stutter instead of turn.
 */
class LoadingIndicatorView(context: Context) : View(context) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#00E676") }
    private var animator: ValueAnimator? = null

    fun start() {
        if (animator != null) return
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = LoadingIndicator.TURN_MILLIS
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { invalidate() }
            start()
        }
        invalidate()
    }

    fun stop() {
        animator?.cancel()
        animator = null
        invalidate()
    }

    override fun onDetachedFromWindow() {
        // A view that is gone must not keep an animator alive: it would hold the panel's frame loop open.
        stop()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        if (animator == null) return
        val centerX = width / 2f
        val centerY = height / 2f
        val radius = (minOf(width, height) / 2f) - DOT_RADIUS_PX
        if (radius <= DOT_RADIUS_PX) return
        val angle = LoadingIndicator.angleFor(animator?.currentPlayTime ?: 0L)
        for (index in 0 until LoadingIndicator.DOTS) {
            val phase = index.toFloat() / LoadingIndicator.DOTS
            val radians = Math.toRadians((angle + phase * 360f).toDouble())
            val x = centerX + radius * Math.cos(radians).toFloat()
            val y = centerY + radius * Math.sin(radians).toFloat()
            // Brightness runs around the ring: the dots behind the leading one fade, which is what reads as motion.
            paint.alpha = (60 + 195 * phase).toInt()
            canvas.drawCircle(x, y, DOT_RADIUS_PX, paint)
        }
    }

    private companion object {
        const val DOT_RADIUS_PX = 5f
    }
}
