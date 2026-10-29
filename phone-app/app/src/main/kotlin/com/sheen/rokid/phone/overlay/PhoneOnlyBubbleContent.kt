package com.sheen.rokid.phone.overlay

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.collect

/**
 * 0.5 (`D211`/`D215`/`D217`), corrected by 0.5.1 (`C6`/`C7`/`C13`): the independent message bubbles above the ball.
 *
 * The whole window is transparent and non-touchable; this composable only draws. Each message is its own Material card
 * (system `Surface`, `MaterialTheme.typography`, default content colors). The card's base color is the **opaque**
 * [androidx.compose.material3.MaterialTheme.colorScheme.surface], and the whole bubble - card, text, tail and shadow -
 * sits in one [Modifier.alpha] layer: the newest message renders at the configured bubble opacity, a superseded one at
 * `oldMessageAlpha` times its removal fade. System fonts and content colors are unchanged.
 *
 * The stack is bottom-aligned to the ball's top edge; the newest card carries the small tail (right or left, following
 * the docked edge). Its anchor, available height, width and 8-line text cap come from the pure [BubbleLayout], the same
 * numbers the JVM tests pin and T7 reuses for the ball's top-dock reserve. There is no touch handling here: the window
 * is `FLAG_NOT_TOUCHABLE`, and the ball's small window is the only touch surface.
 */
@Composable
fun PhoneOnlyBubbleContent(
    repository: PhoneBubbleRepository,
    appearance: PhoneOnlyAppearance,
    ballEdge: BallEdge,
    ballCenterX: Float,
    ballCenterY: Float,
    radiusPx: Float,
    screenWidthPx: Int,
    screenHeightPx: Int,
    speaking: Boolean,
) {
    var snapshot by remember { mutableStateOf(repository.snapshotNow()) }

    // Event-driven updates (a new message, a tool phase, idle) plus a frame loop for the 2 s/3 s fades. The loop is
    // keyed on "is anything visible" so it stops when the stack is gone instead of spinning forever. A local Notice
    // owns its own deadline in the model, so the same loop is what makes it disappear without a `TurnIdle`.
    LaunchedEffect(repository) {
        repository.snapshots.collect { snapshot = repository.snapshotNow() }
    }
    LaunchedEffect(snapshot.messages.isNotEmpty()) {
        while (snapshot.messages.isNotEmpty()) {
            withFrameNanos { snapshot = repository.snapshotNow() }
        }
    }

    if (snapshot.messages.isEmpty() || snapshot.stackAlpha <= 0.001f) return
    if (appearance.bubbleAlpha <= 0.001f) return

    val density = LocalDensity.current
    // The gap is BubbleLayout's single value: T7's BallPlacement consumes the same constant for its top reserve.
    val gap = BubbleLayout.GAP_DP.dp
    val tailSize = 10.dp
    val gapPx = with(density) { gap.toPx() }
    val anchorBottomPx = BubbleLayout.anchorBottomPx(ballCenterY, radiusPx, gapPx)
    val availableHeightPx = BubbleLayout.availableHeightPx(screenHeightPx, anchorBottomPx)
    val maxBubbleWidth = with(density) { BubbleLayout.availableWidthPx(screenWidthPx).toDp() }
    val maxStackHeight = with(density) { availableHeightPx.toDp() }
    val bottomPad = with(density) { (screenHeightPx - anchorBottomPx).toDp() }
    // The 8-line clamp is BubbleLayout's rule; a large font scale or a short stack lowers it below 8.
    val maxLines = BubbleLayout.maxLines(
        availableHeightPx,
        with(density) { MaterialTheme.typography.bodyLarge.lineHeight.toPx() },
    )
    // Right edge of the bubble sits 14 dp in from the ball's left edge (mirrored for a left-edge ball).
    val sidePad = if (ballEdge == BallEdge.RIGHT) {
        with(density) { (screenWidthPx - (ballCenterX - radiusPx - 14.dp.toPx())).toDp() }
    } else {
        with(density) { (ballCenterX + radiusPx + 14.dp.toPx()).toDp() }
    }

    Box(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .align(if (ballEdge == BallEdge.RIGHT) Alignment.BottomEnd else Alignment.BottomStart)
                .padding(
                    bottom = bottomPad,
                    start = if (ballEdge == BallEdge.LEFT) sidePad else 12.dp,
                    end = if (ballEdge == BallEdge.RIGHT) sidePad else 12.dp,
                )
                .widthIn(max = maxBubbleWidth)
                .heightIn(max = maxStackHeight)
                .clipToBounds()
                .alpha(snapshot.stackAlpha),
            horizontalAlignment = if (ballEdge == BallEdge.RIGHT) Alignment.End else Alignment.Start,
            verticalArrangement = Arrangement.spacedBy(gap),
        ) {
            snapshot.messages.forEach { message ->
                MessageBubble(
                    message = message,
                    appearance = appearance,
                    tailOnRight = ballEdge == BallEdge.RIGHT,
                    speaking = speaking && message.newest && message.kind == BubbleKind.ANSWER,
                    maxLines = maxLines,
                    tailSize = tailSize,
                )
            }
        }
    }
}

@Composable
private fun MessageBubble(
    message: VisibleBubble,
    appearance: PhoneOnlyAppearance,
    tailOnRight: Boolean,
    speaking: Boolean,
    maxLines: Int,
    tailSize: Dp,
) {
    val scheme = MaterialTheme.colorScheme
    // 0.5.1 C7: one alpha layer around card + text + tail + shadow. The card base stays the opaque system surface
    // inside the layer, so the rendered card and text both equal the layer's value.
    val layerAlpha = BubbleRenderMath.layerAlpha(
        newest = message.newest,
        bubbleAlpha = appearance.bubbleAlpha,
        fade = BubbleRenderMath.fade(message.newest, message.relativeAlpha),
        oldMessageAlpha = appearance.oldMessageAlpha,
    )

    Box(Modifier.alpha(layerAlpha)) {
        Surface(
            modifier = Modifier.padding(bottom = if (message.newest) 4.dp else 0.dp),
            shape = RoundedCornerShape(12.dp),
            color = scheme.surface,
            contentColor = scheme.onSurface,
            shadowElevation = 3.dp,
            tonalElevation = 0.dp,
        ) {
            Box(
                Modifier.padding(
                    start = 14.dp,
                    end = 14.dp,
                    top = 10.dp,
                    bottom = 10.dp + if (message.newest) 6.dp else 0.dp,
                ),
            ) {
                MessageRow(message, speaking, maxLines)
            }
        }
        if (message.newest) {
            Canvas(
                modifier = Modifier
                    .align(if (tailOnRight) Alignment.BottomEnd else Alignment.BottomStart)
                    .size(width = 16.dp, height = tailSize),
            ) {
                val w = size.width
                val h = size.height
                val path = androidx.compose.ui.graphics.Path()
                if (tailOnRight) {
                    path.moveTo(w * 0.10f, 0f)
                    path.lineTo(w, 0f)
                    path.lineTo(w * 0.25f, h)
                } else {
                    path.moveTo(0f, 0f)
                    path.lineTo(w * 0.90f, 0f)
                    path.lineTo(w * 0.75f, h)
                }
                path.close()
                drawPath(path, scheme.surface)
            }
        }
    }
}

@Composable
private fun MessageRow(message: VisibleBubble, speaking: Boolean, maxLines: Int) {
    when (message.kind) {
        BubbleKind.USER -> Text(
            text = message.text,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
        )
        BubbleKind.THINKING -> Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(10.dp))
            Text(message.text, style = MaterialTheme.typography.bodyMedium)
        }
        BubbleKind.TOOL_RUNNING -> Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(10.dp))
            Text(message.text, style = MaterialTheme.typography.bodyMedium)
        }
        BubbleKind.TOOL_DONE -> Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(message.text, style = MaterialTheme.typography.bodyMedium)
        }
        BubbleKind.ANSWER -> Row(verticalAlignment = Alignment.Top) {
            Text(
                text = message.text,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = maxLines,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (speaking) {
                Spacer(Modifier.width(8.dp))
                Icon(
                    imageVector = Icons.Default.VolumeUp,
                    contentDescription = "正在朗读",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        BubbleKind.NOTICE -> Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Default.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(message.text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
