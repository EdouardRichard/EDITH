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
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
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
 * The arrow side is **not** inferred from a popup-content rect: [HelpPopupPositionProvider] positions the window and
 * reports the side and the icon-to-popup x offset in the provider's one coordinate space (parent-window coordinates),
 * so the card and the arrow always match where the window actually went. The popover card exposes that side as its
 * accessibility `stateDescription` (`"above"` / `"below"`), which `HelpPopoverTest` asserts on the device.
 *
 * Only the icon whose title equals [HelpState.openId] renders a popover, so opening another one replaces the previous
 * one structurally. [BackHandler] and the popup's own `dismissOnBackPress` both close it. The state is page-level
 * `remember`, so rotation recreates the page and closes the popover (design §4.1) without any extra code.
 */
@Composable
fun HelpIcon(text: HelpText, state: HelpState) {
    val open = state.openId == text.title

    Box {
        IconButton(
            onClick = {
                if (state.openId == text.title) state.close() else state.open(text.title, text)
            },
            modifier = Modifier.size(48.dp),
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
                onDismiss = { if (state.openId == text.title) state.close() },
            )
        }
    }
    BackHandler(enabled = open) {
        if (state.openId == text.title) state.close()
    }
}

/**
 * The popup itself. The [Popup]'s parent layout node is the icon's [Box], so the platform hands
 * [HelpPopupPositionProvider] the icon bounds in parent-window coordinates and asks it for the window origin in that
 * same space; the provider reports the above/below side and the arrow offset from the very same calculation.
 */
@Composable
private fun HelpPopover(
    text: HelpText,
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
    // The provider fills this in while it positions the window (same calculation, same coordinate space); the card
    // and the arrow below are rendered from that single result instead of from a second, popup-local measurement.
    val placement = positionProvider.placement
    val above = placement?.above == true
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
            Modifier.drawBehind {
                drawArrow(
                    surface = surface,
                    arrowOffsetX = placement?.arrowOffsetX,
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
                    // The side is part of the popover's state (and lets the Compose test assert the flip); it is not
                    // a user-facing description, so it must not replace the title/body semantics.
                    .semantics { stateDescription = if (above) "above" else "below" }
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
 * The side and arrow offset the popup window was actually placed with, derived in the **parent-window** coordinate
 * space the position provider works in. Both values come from the same [HelpPopupPositionProvider.calculatePosition]
 * call that chose the window origin, so the card can never disagree with the window position.
 */
internal data class HelpPlacement(
    /** True when the placed popup's bottom edge is at or above the icon's top edge (the flipped-above case). */
    val above: Boolean,
    /** The icon centre relative to the popup's left edge, i.e. an x in the popup content's local coordinates. */
    val arrowOffsetX: Float,
)

/**
 * Places the popover under the icon, or above it when the space below is too small, and keeps it inside the window.
 *
 * Compose hands the provider the icon's window bounds (the popup's parent layout node is the icon) and the measured
 * popup content size, and the provider returns the popup window origin — all in the **same** parent-window coordinate
 * space. It therefore also reports the above/below side and the arrow offset from that one calculation, instead of
 * letting the popup content compare its own local bounds with the icon's app-window bounds (which mixed two spaces
 * and produced a wrong arrow side whenever the icon top was larger than the popup height).
 */
@Stable
internal class HelpPopupPositionProvider(
    private val gapPx: Int,
    private val marginPx: Int,
) : PopupPositionProvider {

    /** The placement computed by the last [calculatePosition] call; read by the popover for the card/arrow side. */
    var placement: HelpPlacement? by mutableStateOf(null)
        private set

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
        val placedY = y.coerceIn(marginPx, maxY)

        // `anchorBounds`, `x` and `placedY` are all parent-window coordinates here, so this is a real geometric
        // above/below test (and it reflects the final, clamped window origin, not just the "fits below" guess).
        val popupBottom = placedY + popupContentSize.height
        placement = HelpPlacement(
            above = popupBottom <= anchorBounds.top,
            arrowOffsetX = (anchorBounds.center.x - x).toFloat(),
        )
        return IntOffset(x, placedY)
    }
}

/**
 * Draws the little triangle that ties the card to its icon.
 *
 * It lives in the transparent margin reserved on the anchor side, so it protrudes rather than sitting inside the
 * rounded card. It points up when the popover is below the icon and down when the popover was flipped above; its tip
 * follows the icon's centre ([arrowOffsetX] is already relative to the popup's left edge) unless that would leave the
 * card's rounded corner.
 */
private fun DrawScope.drawArrow(
    surface: Color,
    arrowOffsetX: Float?,
    arrowPx: Float,
    cornerPx: Float,
    above: Boolean,
) {
    if (arrowPx <= 0f || size.width <= 0f || size.height <= arrowPx) return
    val half = arrowPx / 2f
    val minX = cornerPx + half
    val maxX = (size.width - cornerPx - half).coerceAtLeast(minX)
    val desiredX = arrowOffsetX ?: size.width / 2f
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
