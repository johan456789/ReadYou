package me.ash.reader.ui.component.scrollbar

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ScrollIndicatorState
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

private val ThumbColor
    @Composable get() = MaterialTheme.colorScheme.outline.copy(alpha = .5f)

/**
 * Thumb metrics with a length that stays constant while scrolling.
 *
 * Lazy lists estimate their total content size from the average size of the
 * currently visible items, so anything that changes the visible mix (tall
 * banners/spacers entering or leaving, headers versus rows) makes an
 * estimate-based thumb breathe while scrolling. Instead the length is
 * derived from the content item count, which only changes on genuine
 * content changes (expand/collapse): length = viewport * (viewport /
 * typicalRow / contentItems). Static header/footer items are excluded via
 * [VerticalScrollIndicatorFactory.staticKeys]. The absolute size is
 * approximate (it assumes rows near [TypicalContentRowHeight]) but it does
 * not move while scrolling, which is what matters. Position stays
 * pixel-driven and is normalized by the same estimate in numerator and
 * denominator, which cancels most of its wobble. The travel is shortened by
 * [VerticalScrollIndicatorFactory.bottomInset] so the thumb never slides
 * under overlapping bottom bars (e.g. a floating filter bar), which would
 * read as the thumb shrinking at the list end.
 */
private val TypicalContentRowHeight = 56.dp

private fun stableThumbMetrics(
    state: ScrollIndicatorState,
    listState: LazyListState,
    staticKeys: Set<Any>,
    bottomInsetPx: Float,
    density: Density,
): Pair<Float, Float> {
    val totalItems =
        (listState.layoutInfo.totalItemsCount - staticKeys.size).coerceAtLeast(1)
    val typicalRowPx = with(density) { TypicalContentRowHeight.toPx() }
    val visibleTypical = state.viewportSize / typicalRowPx
    val length = state.viewportSize * (visibleTypical / totalItems)
    val span = (state.viewportSize - bottomInsetPx - length).coerceAtLeast(0f)
    val travel = (state.contentSize - state.viewportSize).coerceAtLeast(1)
    val position = (state.scrollOffset * span / travel).coerceIn(0f, span)
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

@Composable
fun Modifier.drawVerticalScrollIndicator(
    listState: LazyListState,
    stableThumb: Boolean = false,
    staticKeys: Set<Any> = emptySet(),
    bottomInset: Dp = 0.dp,
): Modifier {
    return this.scrollIndicator(
        VerticalScrollIndicatorFactory(
            thumbColor = ThumbColor,
            listState = listState,
            stableThumb = stableThumb,
            staticKeys = staticKeys,
            bottomInset = bottomInset,
        ),
        listState.scrollIndicatorState!!,
        Orientation.Vertical,
    )
}

data class VerticalScrollIndicatorFactory(
    val thumbThickness: Dp = 4.dp,
    val padding: Dp = 0.dp,
    val thumbColor: Color = Color.Gray,
    val listState: LazyListState? = null,
    val stableThumb: Boolean = false,
    val staticKeys: Set<Any> = emptySet(),
    val bottomInset: Dp = 0.dp,
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

                // Calculate the thumb's size and position along the scrolling axis.
                // Clamp the position so the thumb keeps its full length at the
                // list end instead of being drawn past the viewport edge.
                val (thumbLength, thumbPosition) =
                    if (stableThumb && listState != null) {
                        stableThumbMetrics(
                            state,
                            listState,
                            staticKeys,
                            bottomInset.toPx(),
                            this,
                        )
                    } else {
                        val visibleContentRatio = state.viewportSize.toFloat() / state.contentSize
                        val length = state.viewportSize * visibleContentRatio
                        val position =
                            (state.scrollOffset * visibleContentRatio)
                                .coerceAtMost(state.viewportSize - length)
                        length to position
                    }

                // Don't draw a thumb that would fill the viewport.
                if (thumbLength >= state.viewportSize) return

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
