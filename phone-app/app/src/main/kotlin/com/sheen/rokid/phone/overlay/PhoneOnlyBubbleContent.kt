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
 * 0.5 (`D211`/`D215`/`D217`): the independent message bubbles above the floating ball.
 *
 * The whole window is transparent and non-touchable; this composable only draws. Each message is its own Material card
 * (system `Surface`, `MaterialTheme.typography`, default content colors). The card background uses the user's configured
 * bubble opacity; the text stays fully opaque. A superseded message is one alpha layer at the model's fixed ratio, so
 * the whole card **and** its text fade together - the owner's "old message is a faded dialog box" rule.
 *
 * The stack is bottom-aligned to the ball's top edge; the newest card carries the small tail (right or left, following
 * the docked edge). There is no touch handling here: the window is `FLAG_NOT_TOUCHABLE`, and the ball's small window is
 * the only touch surface.
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
    // keyed on "is anything visible" so it stops when the stack is gone instead of spinning forever.
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
    val gap = 8.dp
    val tailSize = 10.dp
    val maxBubbleWidth = with(density) { (screenWidthPx * 0.72f).toDp() }
    val maxStackHeight = with(density) { (screenHeightPx * 0.45f).toDp() }
    val anchorBottom = ballCenterY - radiusPx - with(density) { 8.dp.toPx() }
    val bottomPad = with(density) { (screenHeightPx - anchorBottom).toDp() }
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
            snapshot.messages.forEachIndexed { index, message ->
                val newest = index == snapshot.messages.lastIndex
                MessageBubble(
                    message = message,
                    newest = newest,
                    tailOnRight = ballEdge == BallEdge.RIGHT,
                    bubbleAlpha = appearance.bubbleAlpha,
                    speaking = speaking && newest && message.kind == BubbleKind.ANSWER,
                    tailSize = tailSize,
                )
            }
        }
    }
}

@Composable
private fun MessageBubble(
    message: VisibleBubble,
    newest: Boolean,
    tailOnRight: Boolean,
    bubbleAlpha: Float,
    speaking: Boolean,
    tailSize: Dp,
) {
    val scheme = MaterialTheme.colorScheme

    Box(Modifier.alpha(if (newest) 1f else message.relativeAlpha)) {
        Surface(
            modifier = Modifier.padding(bottom = if (newest) 4.dp else 0.dp),
            shape = RoundedCornerShape(12.dp),
            color = scheme.surface.copy(alpha = bubbleAlpha),
            contentColor = scheme.onSurface,
            shadowElevation = 3.dp,
            tonalElevation = 0.dp,
        ) {
            Box(
                Modifier.padding(
                    start = 14.dp,
                    end = 14.dp,
                    top = 10.dp,
                    bottom = 10.dp + if (newest) 6.dp else 0.dp,
                ),
            ) {
                MessageRow(message, speaking)
            }
        }
        if (newest) {
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
                drawPath(path, scheme.surface.copy(alpha = bubbleAlpha))
            }
        }
    }
}

@Composable
private fun MessageRow(message: VisibleBubble, speaking: Boolean) {
    when (message.kind) {
        BubbleKind.USER -> Text(
            text = message.text,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 8,
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
                maxLines = 8,
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
