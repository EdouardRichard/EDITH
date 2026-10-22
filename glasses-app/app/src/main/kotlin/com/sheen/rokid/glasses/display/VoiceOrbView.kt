package com.sheen.rokid.glasses.display

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import android.view.animation.LinearInterpolator

/**
 * 0.23 P3 (spec §5.1.4): the official voice orb, redrawn with the platform's own animation.
 *
 * The official `id_pv` is a 55x45 PAG view whose curves are not available (D level: PAG is a binary vector format and
 * this machine has no parser). What **is** known is the level we must not exceed: the orb is a green head with two
 * square eyes and a shadow, one arc per side while listening, two arcs per side while answering, and a smaller,
 * silent head while thinking. That is what this draws - an approximation whose state behaviour is pinned by
 * [OrbGeometry], not a claim to reproduce the PAG frames.
 *
 * No libpag dependency: the official assets are 36 KB of pure vector, and a glasses process should not carry another
 * `.so` for a 55x45 ornament. `start`/`stop`/`setState` are idempotent because the Activity re-applies them on every
 * inbound message.
 */
class VoiceOrbView(context: Context) : View(context) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val eyePath = Path()
    private val arcBounds = RectF()

    private var state: OrbState = OrbState.IDLE
    private var animator: ValueAnimator? = null
    private var breath: Float = 0f

    /** Changes the state and restarts the animator only when the new state actually breathes. */
    fun setState(state: OrbState) {
        val changed = this.state != state
        this.state = state
        // Starting is idempotent, and it must run even when the state is unchanged: `onPause` stops the animator
        // without changing the state, so a resumed LISTENING/ANSWERING orb would otherwise stay frozen.
        if (state == OrbState.LISTENING || state == OrbState.ANSWERING) start() else stop()
        if (changed) invalidate()
    }

    fun state(): OrbState = state

    /** Starts the breathing animation; idempotent, and a no-op for a state that does not breathe. */
    fun start() {
        if (animator != null) return
        if (state != OrbState.LISTENING && state != OrbState.ANSWERING) return
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = OrbGeometry.BREATH_MILLIS
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = LinearInterpolator()
            addUpdateListener { animation ->
                breath = animation.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    /** Stops the animation and resets the breath; idempotent. */
    fun stop() {
        animator?.cancel()
        animator = null
        breath = 0f
        invalidate()
    }

    override fun onDetachedFromWindow() {
        // A view that leaves the window must not keep the panel's frame loop busy.
        stop()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val scale = OrbGeometry.headScale(state)
        val centerX = width / 2f
        val headCenterY = height * 0.46f
        val headRadiusX = width * 0.30f * scale
        val headRadiusY = height * 0.36f * scale

        // The soft shadow under the head: the official close-ups all show one, and it is what makes the orb look like
        // a bubble above the black panel rather than a sticker on it.
        paint.style = Paint.Style.FILL
        paint.color = GlassesPanelSpec.GREEN_NORMAL
        paint.alpha = 48
        canvas.drawOval(
            centerX - headRadiusX * 0.78f,
            headCenterY + headRadiusY * 0.98f,
            centerX + headRadiusX * 0.78f,
            headCenterY + headRadiusY * 1.28f,
            paint,
        )

        // The wave arcs are drawn first so the head covers their roots, as in the official capture.
        val waves = OrbGeometry.waveCount(state)
        if (waves > 0) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = maxOf(1.5f, width * 0.03f)
            paint.strokeCap = Paint.Cap.ROUND
            paint.color = GlassesPanelSpec.GREEN_NORMAL
            paint.alpha = OrbGeometry.waveAlpha(breath)
            for (side in intArrayOf(-1, 1)) {
                for (index in 0 until waves) {
                    val radius = headRadiusX * (1.25f + 0.32f * index)
                    arcBounds.set(
                        centerX + side * (headRadiusX * 0.85f) - radius,
                        headCenterY - radius,
                        centerX + side * (headRadiusX * 0.85f) + radius,
                        headCenterY + radius,
                    )
                    val startAngle = if (side < 0) 125f else -55f
                    canvas.drawArc(arcBounds, startAngle, 70f, false, paint)
                }
            }
        }

        // Head: a vertical oval with a faint radial highlight, close to the drop shape in the official captures.
        paint.style = Paint.Style.FILL
        paint.color = GlassesPanelSpec.GREEN_NORMAL
        paint.alpha = 255
        canvas.drawOval(
            centerX - headRadiusX,
            headCenterY - headRadiusY,
            centerX + headRadiusX,
            headCenterY + headRadiusY,
            paint,
        )

        // Eyes: two dark rounded squares with a green rim. The official orb's eyes are the only dark part.
        val eyeWidth = width * 0.075f * scale
        val eyeHeight = height * 0.105f * scale
        val eyeGap = width * 0.10f * scale
        for (side in intArrayOf(-1, 1)) {
            val left = centerX + side * eyeGap - eyeWidth / 2f
            val top = headCenterY - eyeHeight / 2f
            eyePath.reset()
            eyePath.addRoundRect(
                left, top, left + eyeWidth, top + eyeHeight,
                eyeWidth * 0.3f, eyeHeight * 0.3f, Path.Direction.CW,
            )
            paint.color = GlassesPanelSpec.BLACK
            canvas.drawPath(eyePath, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = maxOf(1f, width * 0.018f)
            paint.color = GlassesPanelSpec.GREEN_NORMAL
            canvas.drawPath(eyePath, paint)
            paint.style = Paint.Style.FILL
        }
    }
}

/**
 * The orb's decision layer: what a state changes, without a `Canvas`.
 *
 * The drawing above is an approximation of a PAG we cannot read, so the only meaningful contract is the state's shape:
 * thinking is smaller, listening has one arc per side, answering has two, and a breathing alpha stays visible. Pinning
 * these in JVM tests is what keeps "the orb changed state" from being a claim only a screenshot can test.
 */
object OrbGeometry {
    const val WIDTH_PX = GlassesPanelSpec.VOICE_ORB_WIDTH_PX
    const val HEIGHT_PX = GlassesPanelSpec.VOICE_ORB_HEIGHT_PX

    /** Slow enough to read as breathing on a waveguide, short enough not to look frozen. */
    const val BREATH_MILLIS = 1_400L

    private const val MIN_WAVE_ALPHA = 110
    private const val MAX_WAVE_ALPHA_EXTRA = 145

    fun headScale(state: OrbState): Float = when (state) {
        OrbState.THINKING -> 0.75f
        OrbState.IDLE, OrbState.LISTENING, OrbState.ANSWERING -> 1f
    }

    fun waveCount(state: OrbState): Int = when (state) {
        OrbState.LISTENING -> 1
        OrbState.ANSWERING -> 2
        OrbState.IDLE, OrbState.THINKING -> 0
    }

    /** The wave alpha for a breath phase in `[0, 1]`; out-of-range phases are clamped, never wrapped. */
    fun waveAlpha(breath: Float): Int {
        val clamped = breath.coerceIn(0f, 1f)
        return (MIN_WAVE_ALPHA + MAX_WAVE_ALPHA_EXTRA * clamped).toInt()
    }
}
