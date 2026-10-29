package com.sheen.rokid.phone.overlay

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.sheen.rokid.phone.R
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.min

/** 0.5: the ball's three light-ring states; the service derives them from the bubble stack and the speech switch. */
enum class BallHalo { NONE, WORKING, SPEAKING }

/**
 * 0.5 (`D210`), corrected by 0.5.1 (`C9`/`C11`/`C15`): the EDITH-icon floating ball, drawn in the small overlay window.
 *
 * The window is three radii square (the icon is one diameter, the light rings live in the margin). The whole window is
 * the touch target; [onAction] receives the pure [BallGestureMachine]'s decisions, and the service turns them into
 * window moves/dictation/app-open actions.
 *
 * 0.5.1 (`C9`): the shadow, the white base, the EDITH icon and the microphone scrim all sit inside **one**
 * `graphicsLayer { alpha }`, so the ball opacity fades the whole ball. The green working/speaking halo and the listening
 * ring are outside that layer and keep their own strength; they do not fade with the ball.
 *
 * 0.5.1 (`C11`): the drag delta is **screen space**. The service records the ball window's origin and supplies it
 * through [windowOrigin]; [BallDragController] combines it with the local pointer position instead of reading the
 * local `positionChange()`, which goes stale as soon as the window follows the finger.
 *
 * 0.5.1 (`C15`): the ring animation exists only while [halo] is not [BallHalo.NONE] (and the listening pulse only while
 * listening), so an idle ball does not keep a frame clock running.
 *
 * Fonts/colors: there is no text here; the icon is the app's own launcher foreground on a white circle, and the
 * thinking light is the approved EDITH-green effect. Nothing in this composable changes the system typography.
 */
@Composable
fun PhoneOnlyBallContent(
    diameterDp: Int,
    opacity: Float,
    micVisible: Boolean,
    listening: Boolean,
    halo: BallHalo,
    windowOrigin: () -> Offset,
    onAction: (BallGestureAction) -> Unit,
) {
    val currentAction by rememberUpdatedState(onAction)
    val currentWindowOrigin by rememberUpdatedState(windowOrigin)
    val green = Color(0xFF40FF5E)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                coroutineScope {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val gesture = BallGestureMachine(slopPx = 12.dp.toPx(), longPressMillis = 500L)
                        val drag = BallDragController()
                        val origin = currentWindowOrigin()
                        var timer: Job? = null
                        try {
                            // The gesture machine decides tap/long/snap from the absolute (screen) pointer position,
                            // so a window move under the finger cannot turn one gesture into another.
                            gesture.down(origin.x + down.position.x, origin.y + down.position.y, nowMillis())
                            drag.onPointerDown(down.position.x, down.position.y, origin.x, origin.y)
                            timer = launch {
                                delay(500L)
                                gesture.longPressStart(nowMillis()).forEach { currentAction(it) }
                            }
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (change.changedToUp()) {
                                    timer?.cancel()
                                    drag.onPointerUp()
                                    gesture.up(nowMillis()).forEach { currentAction(it) }
                                    change.consume()
                                    break
                                }
                                if (change.positionChanged()) {
                                    val at = currentWindowOrigin()
                                    val screenX = at.x + change.position.x
                                    val screenY = at.y + change.position.y
                                    gesture.move(screenX, screenY, nowMillis())
                                    // The controller reports the finger's screen-space movement; the local
                                    // `positionChange()` would shrink to zero (or invert) once the window follows.
                                    val delta = drag.onPointerMove(change.position.x, change.position.y, at.x, at.y)
                                    if (gesture.phase == BallGestureMachine.Phase.DRAGGING) {
                                        val motion = if (drag.isDragging) delta else drag.beginDrag()
                                        if (motion != null && (motion.dx != 0f || motion.dy != 0f)) {
                                            currentAction(BallGestureAction.Drag(motion.dx, motion.dy))
                                        }
                                    }
                                    change.consume()
                                }
                            }
                        } finally {
                            timer?.cancel()
                            // 0.5.1 (review Important #2): this `finally` also runs when Compose cancels the pointer
                            // (`awaitEachGesture` unwinds the block with a CancellationException), where the normal
                            // `up()` path never executes. Ending an open long press here is what stops the service's
                            // accepted BALL lease; a cancelled drag is not snapped and a cancelled press is not a tap.
                            cancelBallPointerSequence(gesture, drag).forEach { currentAction(it) }
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        // The green rings are drawn outside the ball's alpha layer: they keep their strength at any ball opacity.
        if (halo != BallHalo.NONE) {
            HaloEffect(halo = halo, green = green)
        }
        if (listening) {
            ListeningPulse()
        }

        // C9: shadow + white base + icon + microphone scrim in one alpha layer; 20% fades the whole ball.
        Box(
            modifier = Modifier
                .size(diameterDp.dp)
                .graphicsLayer { alpha = opacity.coerceIn(0f, 1f) },
        ) {
            Image(
                painter = painterResource(R.mipmap.ic_launcher_foreground),
                contentDescription = "EDITH 悬浮球",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .shadow(6.dp, CircleShape)
                    .clip(CircleShape)
                    .background(Color.White),
            )
            if (micVisible) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.55f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Default.Mic,
                        contentDescription = "正在听写",
                        tint = Color.White,
                        modifier = Modifier.size((diameterDp / 2).dp),
                    )
                }
            }
        }
    }
}

/** The working/speaking light: a rotating green arc pair plus a breathing radial glow. */
@Composable
private fun HaloEffect(halo: BallHalo, green: Color) {
    val transition = rememberInfiniteTransition(label = "phone-only-halo")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(2_600, easing = LinearEasing)),
        label = "halo-angle",
    )
    val breath by transition.animateFloat(
        initialValue = 0.72f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900), repeatMode = RepeatMode.Reverse),
        label = "halo-breath",
    )
    val intensity = when (halo) {
        BallHalo.WORKING -> 1f
        BallHalo.SPEAKING -> 0.45f
        BallHalo.NONE -> 0f
    }
    Canvas(Modifier.fillMaxSize()) {
        if (intensity <= 0f) return@Canvas
        val radius = min(size.width, size.height) / 2f
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(green.copy(alpha = 0.34f * intensity * breath), Color.Transparent),
                center = center,
                radius = radius * 0.95f,
            ),
            radius = radius * 0.95f,
            center = center,
        )
        val ring = radius * 0.72f
        rotate(angle, pivot = center) {
            drawArc(
                color = green.copy(alpha = 0.92f * intensity * breath),
                startAngle = 0f,
                sweepAngle = 120f,
                useCenter = false,
                topLeft = Offset(center.x - ring, center.y - ring),
                size = Size(ring * 2f, ring * 2f),
                style = Stroke(width = radius * 0.045f, cap = StrokeCap.Round),
            )
            drawArc(
                color = green.copy(alpha = 0.55f * intensity * breath),
                startAngle = 186f,
                sweepAngle = 70f,
                useCenter = false,
                topLeft = Offset(center.x - ring, center.y - ring),
                size = Size(ring * 2f, ring * 2f),
                style = Stroke(width = radius * 0.035f, cap = StrokeCap.Round),
            )
        }
    }
}

/** The white ring that expands once per beat while the ball's own dictation is listening. */
@Composable
private fun ListeningPulse() {
    val transition = rememberInfiniteTransition(label = "phone-only-listen")
    val pulse by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1_400, easing = LinearEasing)),
        label = "listen-pulse",
    )
    Canvas(Modifier.fillMaxSize()) {
        val radius = min(size.width, size.height) / 2f
        val rr = radius * (0.58f + pulse * 0.28f)
        drawCircle(
            color = Color.White.copy(alpha = 0.55f * (1f - pulse)),
            radius = rr,
            center = center,
            style = Stroke(width = 2.dp.toPx()),
        )
    }
}

private fun nowMillis(): Long = System.nanoTime() / 1_000_000L
