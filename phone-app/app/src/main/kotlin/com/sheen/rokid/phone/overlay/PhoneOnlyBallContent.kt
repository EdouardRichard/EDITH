package com.sheen.rokid.phone.overlay

import android.view.MotionEvent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.ExperimentalComposeUiApi
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
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.sheen.rokid.phone.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
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
 * window moves/dictation actions.
 *
 * 0.5.1 (`C9`): the shadow, the white base, the EDITH icon and the microphone scrim all sit inside **one**
 * `graphicsLayer { alpha }`, so the ball opacity fades the whole ball. The green working/speaking halo and the listening
 * ring are outside that layer and keep their own strength; they do not fade with the ball.
 *
 * 0.5.1 (`C11`): the drag delta is **screen space**. The pointer stream comes from the raw `MotionEvent`
 * (`rawX`/`rawY`), so a window move under the finger cannot shift the event coordinates; the ball's centre follows the
 * finger's absolute screen position (`DragTo`). The earlier local-position-plus-window-origin approach still jittered
 * because the origin and the local sample were taken at different moments.
 *
 * 0.5.1 (`C15`): the ring animation exists only while [halo] is not [BallHalo.NONE] (and the listening pulse only while
 * listening), so an idle ball does not keep a frame clock running.
 *
 * Fonts/colors: there is no text here; the icon is the app's own launcher foreground on a white circle, and the
 * thinking light is the approved EDITH-green effect. Nothing in this composable changes the system typography.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun PhoneOnlyBallContent(
    diameterDp: Int,
    opacity: Float,
    micVisible: Boolean,
    listening: Boolean,
    halo: BallHalo,
    onAction: (BallGestureAction) -> Unit,
) {
    val currentAction by rememberUpdatedState(onAction)
    val scope = rememberCoroutineScope()
    val slopPx = with(LocalDensity.current) { 12.dp.toPx() }
    val ballPointer = remember(scope, slopPx) {
        BallPointerInput(scope, slopPx, 500L) { currentAction(it) }
    }
    val green = Color(0xFF40FF5E)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInteropFilter { event -> ballPointer.onTouchEvent(event) },
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

/**
 * 0.5.1 (`C11` defect fix): the ball's pointer stream uses the raw `MotionEvent` screen coordinates.
 *
 * The Compose pointer's local position is relative to the window at dispatch time, while the window moves under the
 * finger through `updateViewLayout`; pairing the local sample with a later `getLocationOnScreen()` still jittered. The
 * raw position is the finger's screen point regardless of where the window currently is, and the service places the
 * ball's centre there (`DragTo`), so the ball stays under the finger instead of accumulating a grab offset.
 */
private class BallPointerInput(
    private val scope: CoroutineScope,
    private val slopPx: Float,
    private val longPressMillis: Long,
    private val action: (BallGestureAction) -> Unit,
) {
    private var gesture: BallGestureMachine? = null
    private var timer: Job? = null

    fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> start(event.rawX, event.rawY)
            MotionEvent.ACTION_MOVE -> move(event.rawX, event.rawY)
            MotionEvent.ACTION_UP -> finish()
            MotionEvent.ACTION_CANCEL -> cancel()
        }
        return true
    }

    private fun start(x: Float, y: Float) {
        cancel()
        val machine = BallGestureMachine(slopPx = slopPx, longPressMillis = longPressMillis)
        gesture = machine
        machine.down(x, y, nowMillis())
        action(BallGestureAction.DragTo(x, y))
        timer = scope.launch {
            delay(longPressMillis)
            machine.longPressStart(nowMillis()).forEach(action)
        }
    }

    private fun move(x: Float, y: Float) {
        val machine = gesture ?: return
        machine.move(x, y, nowMillis())
        if (machine.phase == BallGestureMachine.Phase.DRAGGING) {
            action(BallGestureAction.DragTo(x, y))
        }
    }

    private fun finish() {
        val machine = gesture ?: return
        timer?.cancel()
        machine.up(nowMillis()).forEach(action)
        clear()
    }

    private fun cancel() {
        val machine = gesture ?: return
        timer?.cancel()
        machine.cancel().forEach(action)
        clear()
    }

    private fun clear() {
        gesture = null
        timer = null
    }
}

private fun nowMillis(): Long = System.nanoTime() / 1_000_000L
