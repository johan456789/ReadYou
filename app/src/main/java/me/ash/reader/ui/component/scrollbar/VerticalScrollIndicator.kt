package me.ash.reader.ui.component.scrollbar

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ScrollIndicatorState
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

private val ThumbColor
    @Composable get() = MaterialTheme.colorScheme.outline.copy(alpha = .5f)

/**
 * Computes the thumb length and position for a track of [trackSize] px along
 * the scrolling axis, inset by [topInsetPx] and [bottomInsetPx].
 *
 * The thumb length is the visible fraction of the content applied to the
 * track: `track * (viewport / contentHeight)`. This is stable for a fixed
 * track and stays proportional for the caller-supplied exact
 * [contentHeightPx] or, when absent, the scroll indicator's estimate.
 *
 * The position is travel-based (`scrollOffset / (contentHeight - viewport)`)
 * so the thumb reaches both track ends when the travel is accurate, and is
 * snapped to the ends via [canScrollBackward] / [canScrollForward] to absorb
 * estimate drift (e.g. a lazy list whose average visible item size shifts).
 *
 * The travel is shortened by the insets so the thumb never slides under
 * overlapping bars (e.g. a collapsing top app bar or a floating filter bar),
 * which would read as shrinking at the list ends.
 */
private fun thumbMetrics(
    state: ScrollIndicatorState,
    contentHeightPx: Float?,
    trackSize: Float,
    topInsetPx: Float,
    bottomInsetPx: Float,
    canScrollBackward: Boolean?,
    canScrollForward: Boolean?,
): Pair<Float, Float> {
    val viewport = state.viewportSize.toFloat()
    val contentHeight = contentHeightPx?.takeIf { it > 0f } ?: state.contentSize.toFloat()
    val trackSpan = (trackSize - topInsetPx - bottomInsetPx).coerceAtLeast(0f)
    val length = (trackSpan * (viewport / contentHeight)).coerceIn(0f, trackSpan)
    val travel = (contentHeight - viewport).coerceAtLeast(1f)
    val progress =
        when {
            canScrollBackward == false -> 0f
            canScrollForward == false -> 1f
            else -> (state.scrollOffset.toFloat() / travel).coerceIn(0f, 1f)
        }
    val position = topInsetPx + progress * (trackSpan - length)
    return length to position
}

@Composable
fun Modifier.drawVerticalScrollIndicator(scrollState: ScrollState): Modifier {
    return this.scrollIndicator(
        VerticalScrollIndicatorFactory(thumbColor = ThumbColor),
        scrollState.scrollIndicatorState!!,
        Orientation.Vertical,
    )
}

/**
 * Draws a vertical scrollbar for [listState] using this node's own bounds as
 * the track. [topInset] and [bottomInset] keep the thumb clear of overlapping
 * bars; pass the bar heights when the indicator is drawn on an overlay that is
 * not itself the scrollable (e.g. an overlay pinned to a stable frame so a
 * collapsing app bar cannot drag the thumb).
 */
@Composable
fun Modifier.drawVerticalScrollIndicator(
    listState: LazyListState,
    contentHeightPx: Float? = null,
    topInset: Dp = 0.dp,
    bottomInset: Dp = 0.dp,
): Modifier {
    val indicatorState = remember(listState) { listState.scrollIndicatorState!! }
    return this.scrollIndicator(
        VerticalScrollIndicatorFactory(
            thumbColor = ThumbColor,
            contentHeightPx = contentHeightPx,
            topInset = topInset,
            bottomInset = bottomInset,
            listState = listState,
        ),
        indicatorState,
        Orientation.Vertical,
    )
}

/**
 * Same visuals and fade behavior as [drawVerticalScrollIndicator], but driven
 * by an externally-scrolled viewport (e.g. a WebView that owns its scrolling)
 * instead of a Compose scroll state.
 *
 * Each page holds its own snapshot, so the indicator survives horizontal
 * article swipes by construction.
 */
@Composable
fun Modifier.drawWebViewScrollIndicator(
    scrollYPx: Int,
    contentHeightPx: Int,
    viewportHeightPx: Int,
    isAtTop: Boolean,
    isAtBottom: Boolean,
): Modifier {
    val thumbColor = MaterialTheme.colorScheme.outline.copy(alpha = .5f)
    val alpha = remember { Animatable(0f) }
    LaunchedEffect(scrollYPx) {
        alpha.snapTo(1f)
        delay(3000)
        alpha.animateTo(0f, animationSpec = spring())
    }
    return this.drawWithContent {
        drawContent()

        val content = contentHeightPx.toFloat()
        val viewport = viewportHeightPx.toFloat()
        // Don't draw when everything fits.
        if (content <= 0f || viewport <= 0f || content <= viewport) return@drawWithContent
        if (alpha.value == 0f) return@drawWithContent

        val trackSize = size.height
        val thumbLength = (trackSize * (viewport / content)).coerceIn(0f, trackSize)
        if (thumbLength >= trackSize) return@drawWithContent

        val travel = (content - viewport).coerceAtLeast(1f)
        val progress =
            when {
                isAtTop -> 0f
                isAtBottom -> 1f
                else -> (scrollYPx.toFloat() / travel).coerceIn(0f, 1f)
            }
        val thumbPosition = progress * (trackSize - thumbLength)
        val thumbThicknessPx = 4.dp.toPx()
        val x =
            if (layoutDirection == LayoutDirection.Rtl) 0f
            else size.width - thumbThicknessPx
        drawRoundRect(
            cornerRadius = CornerRadius(thumbThicknessPx / 2),
            color = thumbColor,
            topLeft = Offset(x, thumbPosition),
            size = Size(thumbThicknessPx, thumbLength),
            alpha = alpha.value,
        )
    }
}

data class VerticalScrollIndicatorFactory(
    val thumbThickness: Dp = 4.dp,
    val padding: Dp = 0.dp,
    val thumbColor: Color = Color.Gray,
    val contentHeightPx: Float? = null,
    val topInset: Dp = 0.dp,
    val bottomInset: Dp = 0.dp,
    /**
     * When provided, the thumb snaps to the track ends using the list's
     * can-scroll bounds, which absorbs estimate drift. Read inside the draw
     * phase so a change only schedules a redraw (a changed factory value in
     * composition would recreate the node and restart its fade animation).
     */
    val listState: LazyListState? = null,
) : ScrollIndicatorFactory {
    // The node is the core of the ScrollIndicator, handling the drawing logic.
    override fun createNode(
        state: ScrollIndicatorState,
        orientation: Orientation,
    ): DelegatableNode {
        return object : Modifier.Node(), DrawModifierNode {

            private val alpha = Animatable(0f)

            override fun onAttach() {
                coroutineScope.launch {
                    snapshotFlow { state.scrollOffset }
                        .collectLatest {
                            alpha.snapTo(1f)
                            delay(3000)
                            alpha.animateTo(0f, animationSpec = spring())
                        }
                }
            }

            override fun ContentDrawScope.draw() {
                // Draw the original content.
                drawContent()

                // Don't draw the scrollbar if the content fits within the viewport.
                if (state.contentSize <= state.viewportSize) return

                if (alpha.value == 0f) return

                // The thumb is sized against this node's own draw area, which is
                // stable even when the node is an overlay rather than the
                // scrollable itself.
                val trackSize = if (orientation == Orientation.Vertical) size.height else size.width
                val topInsetPx = topInset.toPx()
                val bottomInsetPx = bottomInset.toPx()
                val trackSpan = (trackSize - topInsetPx - bottomInsetPx).coerceAtLeast(0f)

                // Calculate the thumb's size and position along the scrolling axis.
                val (thumbLength, thumbPosition) =
                    thumbMetrics(
                        state = state,
                        contentHeightPx = contentHeightPx,
                        trackSize = trackSize,
                        topInsetPx = topInsetPx,
                        bottomInsetPx = bottomInsetPx,
                        canScrollBackward = listState?.canScrollBackward,
                        canScrollForward = listState?.canScrollForward,
                    )

                // Don't draw a thumb that would fill the track.
                if (thumbLength >= trackSpan) return

                val thumbThicknessPx = thumbThickness.toPx()
                val paddingPx = padding.toPx()

                // Determine the scrollbar size and thumb position based on the orientation.
                val (topLeft, size) =
                    when (orientation) {
                        Orientation.Vertical -> {
                            val x =
                                if (layoutDirection == LayoutDirection.Rtl) {
                                    paddingPx
                                } else {
                                    size.width - thumbThicknessPx - paddingPx
                                }
                            Offset(x, thumbPosition) to Size(thumbThicknessPx, thumbLength)
                        }
                        Orientation.Horizontal -> {
                            val y = size.height - thumbThicknessPx - paddingPx
                            Offset(thumbPosition, y) to Size(thumbLength, thumbThicknessPx)
                        }
                    }

                // Draw the scrollbar thumb.
                drawRoundRect(
                    cornerRadius = CornerRadius(x = thumbThicknessPx / 2),
                    color = thumbColor,
                    topLeft = topLeft,
                    size = size,
                    alpha = alpha.value,
                )
            }
        }
    }
}