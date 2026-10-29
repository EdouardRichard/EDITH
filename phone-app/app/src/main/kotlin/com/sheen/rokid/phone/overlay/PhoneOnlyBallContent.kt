package com.sheen.rokid.phone.overlay

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
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.input.pointer.positionChange
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
 * 0.5 (`D210`): the EDITH-icon floating ball, drawn in the small overlay window.
 *
 * The window is three radii square (the icon is one diameter, the light rings live in the margin). The whole window is
 * the touch target; [onAction] receives the pure [BallGestureMachine]'s decisions, and the service turns them into
 * window moves/dictation/app-open actions.
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
    onAction: (BallGestureAction) -> Unit,
) {
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
    val pulse by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1_400, easing = LinearEasing)),
        label = "listen-pulse",
    )
    val green = Color(0xFF40FF5E)
    val currentAction by rememberUpdatedState(onAction)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                coroutineScope {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val gesture = BallGestureMachine(slopPx = 12.dp.toPx(), longPressMillis = 500L)
                        gesture.down(down.position.x, down.position.y, nowMillis())
                        var timer: Job? = launch {
                            delay(500L)
                            gesture.longPressStart(nowMillis()).forEach { currentAction(it) }
                        }
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (change.changedToUp()) {
                                timer?.cancel()
                                gesture.up(nowMillis()).forEach { currentAction(it) }
                                change.consume()
                                break
                            }
                            if (change.positionChanged()) {
                                gesture.move(change.position.x, change.position.y, nowMillis())
                                if (gesture.phase == BallGestureMachine.Phase.DRAGGING) {
                                    // The service moves the window under the finger, so the machine's stored local
                                    // position is stale after every move. The event's own delta is the finger motion.
                                    val delta = change.positionChange()
                                    if (delta != Offset.Zero) currentAction(BallGestureAction.Drag(delta.x, delta.y))
                                }
                                change.consume()
                            }
                        }
                        timer?.cancel()
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val radius = min(size.width, size.height) / 2f
            val intensity = when (halo) {
                BallHalo.WORKING -> 1f
                BallHalo.SPEAKING -> 0.45f
                BallHalo.NONE -> 0f
            }
            if (intensity > 0f) {
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
            if (listening) {
                val rr = radius * (0.58f + pulse * 0.28f)
                drawCircle(
                    color = Color.White.copy(alpha = 0.55f * (1f - pulse)),
                    radius = rr,
                    center = center,
                    style = Stroke(width = 2.dp.toPx()),
                )
            }
        }

        Image(
            painter = painterResource(R.mipmap.ic_launcher_foreground),
            contentDescription = "EDITH 悬浮球",
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(diameterDp.dp)
                .shadow(6.dp, CircleShape)
                .clip(CircleShape)
                .background(Color.White)
                .graphicsLayer { alpha = opacity.coerceIn(0f, 1f) },
        )

        if (micVisible) {
            Box(
                modifier = Modifier
                    .size(diameterDp.dp)
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

private fun nowMillis(): Long = System.nanoTime() / 1_000_000L
