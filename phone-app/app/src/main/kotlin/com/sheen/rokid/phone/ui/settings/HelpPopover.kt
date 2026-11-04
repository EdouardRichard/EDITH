package com.sheen.rokid.phone.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties

/**
 * 0.52 design §4.1: the `(?)` icon and its popover.
 *
 * The icon is an independent 48dp click target ([IconButton]); it has its own `contentDescription`
 * (`查看{title}说明`) and never forwards the tap to an enclosing row. The popover is a **separate [Popup] window**,
 * not an in-row overlay, so a card's clip or a scrolling page cannot cut it off. It is anchored to the icon bounds,
 * flips above the icon when the space below is insufficient, is capped at ~330dp wide / 60% of the screen height,
 * and scrolls its body when the copy is taller. The optional impact line stays outside the scroll region so it is
 * always readable.
 *
 * Only the icon whose title equals [HelpState.openId] renders a popover, so opening another one replaces the previous
 * one structurally. [BackHandler] and the popup's own `dismissOnBackPress` both close it. The state is page-level
 * `remember`, so rotation recreates the page and closes the popover (design §4.1) without any extra code.
 */
@Composable
fun HelpIcon(text: HelpText, state: HelpState) {
    val open = state.openId == text.title
    // The anchor is positional, not content-dependent: do not key this on `text`, or a content change would drop the
    // last known icon bounds until the next layout move (and leave the flip/arrow decision without an anchor).
    var anchorBounds by remember { mutableStateOf<Rect?>(null) }

    Box {
        IconButton(
            onClick = {
                if (state.openId == text.title) state.close() else state.open(text.title, text)
            },
            modifier = Modifier
                .size(48.dp)
                .onGloballyPositioned { coordinates -> anchorBounds = coordinates.boundsInWindow() },
        ) {
            Icon(
                Icons.Default.HelpOutline,
                contentDescription = "查看${text.title}说明",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (open) {
            HelpPopover(
                text = state.openText ?: text,
                anchorBounds = anchorBounds,
                onDismiss = { if (state.openId == text.title) state.close() },
            )
        }
    }
    BackHandler(enabled = open) {
        if (state.openId == text.title) state.close()
    }
}

/**
 * The popup itself. The `(?)` icon is the [Popup]'s parent layout node, so the platform hands the popup the icon's
 * window bounds as `anchorBounds`; the same bounds are tracked here for the arrow and the flip decision.
 */
@Composable
private fun HelpPopover(
    text: HelpText,
    anchorBounds: Rect?,
    onDismiss: () -> Unit,
) {
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val arrowSize = 8.dp
    val corner = 12.dp
    val maxWidth = minOf(330.dp, (configuration.screenWidthDp.dp - 32.dp).coerceAtLeast(1.dp))
    val maxHeight = configuration.screenHeightDp.dp * 0.6f

    val gapPx = with(density) { 4.dp.roundToPx() }
    val marginPx = with(density) { 12.dp.roundToPx() }
    val arrowPx = with(density) { arrowSize.toPx() }
    val cornerPx = with(density) { corner.toPx() }
    val positionProvider = remember(gapPx, marginPx) { HelpPopupPositionProvider(gapPx, marginPx) }

    var popupBounds by remember { mutableStateOf<Rect?>(null) }
    val bounds = popupBounds
    val above = anchorBounds != null && bounds != null && bounds.bottom <= anchorBounds.top + 1f
    val surface = MaterialTheme.colorScheme.surface

    Popup(
        popupPositionProvider = positionProvider,
        onDismissRequest = onDismiss,
        properties = PopupProperties(
            focusable = true,
            dismissOnClickOutside = true,
            dismissOnBackPress = true,
        ),
    ) {
        Box(
            Modifier
                .onGloballyPositioned { coordinates -> popupBounds = coordinates.boundsInWindow() }
                .drawBehind {
                    drawArrow(
                        surface = surface,
                        anchorX = anchorBounds?.center?.x,
                        popupLeft = popupBounds?.left ?: 0f,
                        arrowPx = arrowPx,
                        cornerPx = cornerPx,
                        above = above,
                    )
                },
        ) {
            Column(
                Modifier
                    .widthIn(max = maxWidth)
                    .heightIn(max = maxHeight)
                    // The arrow margin sits on the anchor side only; keeping the total height unchanged means the
                    // first-frame flip decision cannot move the window (no layout loop).
                    .padding(top = if (above) 0.dp else arrowSize, bottom = if (above) arrowSize else 0.dp)
                    .shadow(6.dp, RoundedCornerShape(corner))
                    .background(surface, RoundedCornerShape(corner))
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Text(
                    text.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text.body,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    // Weighted, non-filling: the title and the impact line are measured first, so a long body takes
                    // exactly the height that is left and scrolls instead of pushing the impact line out of the card.
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState()),
                )
                text.impact?.let { impact ->
                    Spacer(Modifier.height(8.dp))
                    Text(
                        impact,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

/**
 * Places the popover under the icon, or above it when the space below is too small, and keeps it inside the window.
 *
 * Compose hands the provider the icon's window bounds (the popup's parent layout node is the icon), so no manual
 * screen-position bookkeeping is needed. The result is the popup window position in window coordinates.
 */
internal class HelpPopupPositionProvider(
    private val gapPx: Int,
    private val marginPx: Int,
) : PopupPositionProvider {

    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val fitsBelow =
            anchorBounds.bottom + gapPx + popupContentSize.height + marginPx <= windowSize.height
        val y = if (fitsBelow) anchorBounds.bottom + gapPx else anchorBounds.top - gapPx - popupContentSize.height
        val maxX = (windowSize.width - popupContentSize.width - marginPx).coerceAtLeast(marginPx)
        val x = (anchorBounds.center.x - popupContentSize.width / 2).coerceIn(marginPx, maxX)
        val maxY = (windowSize.height - popupContentSize.height - marginPx).coerceAtLeast(marginPx)
        return IntOffset(x, y.coerceIn(marginPx, maxY))
    }
}

/**
 * Draws the little triangle that ties the card to its icon.
 *
 * It lives in the transparent margin reserved on the anchor side, so it protrudes rather than sitting inside the
 * rounded card. It points up when the popover is below the icon and down when the popover was flipped above; its tip
 * follows the icon's centre unless that would leave the card's rounded corner.
 */
private fun DrawScope.drawArrow(
    surface: Color,
    anchorX: Float?,
    popupLeft: Float,
    arrowPx: Float,
    cornerPx: Float,
    above: Boolean,
) {
    if (arrowPx <= 0f || size.width <= 0f || size.height <= arrowPx) return
    val half = arrowPx / 2f
    val minX = cornerPx + half
    val maxX = (size.width - cornerPx - half).coerceAtLeast(minX)
    val desiredX = if (anchorX == null) size.width / 2f else anchorX - popupLeft
    val centreX = desiredX.coerceIn(minX, maxX)
    val path = Path().apply {
        if (above) {
            moveTo(centreX - half, size.height - arrowPx)
            lineTo(centreX + half, size.height - arrowPx)
            lineTo(centreX, size.height)
        } else {
            moveTo(centreX - half, arrowPx)
            lineTo(centreX + half, arrowPx)
            lineTo(centreX, 0f)
        }
        close()
    }
    drawPath(path, surface)
}
