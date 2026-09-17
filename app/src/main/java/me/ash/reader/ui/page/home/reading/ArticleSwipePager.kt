package me.ash.reader.ui.page.home.reading

import android.view.View
import android.webkit.WebChromeClient
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import me.ash.reader.ui.component.webview.WebViewScrollSnapshot
import me.ash.reader.ui.page.adaptive.ReaderState
import kotlin.math.abs
import kotlin.math.roundToInt

internal enum class ArticleSwipeDirection {
    Previous,
    Next,
}

internal fun resolveArticleSwipeSettleDirection(
    dragOffset: Float,
    threshold: Float,
    layoutDirection: LayoutDirection,
    canLoadPrevious: Boolean,
    canLoadNext: Boolean,
): ArticleSwipeDirection? {
    if (abs(dragOffset) < threshold) return null
    val direction =
        when {
            layoutDirection == LayoutDirection.Ltr && dragOffset < 0f -> ArticleSwipeDirection.Next
            layoutDirection == LayoutDirection.Ltr -> ArticleSwipeDirection.Previous
            dragOffset < 0f -> ArticleSwipeDirection.Previous
            else -> ArticleSwipeDirection.Next
        }
    return when (direction) {
        ArticleSwipeDirection.Previous -> direction.takeIf { canLoadPrevious }
        ArticleSwipeDirection.Next -> direction.takeIf { canLoadNext }
    }
}

internal fun articleSwipePageOffset(
    direction: ArticleSwipeDirection,
    widthPx: Float,
    layoutDirection: LayoutDirection,
): Float =
    when (direction) {
        ArticleSwipeDirection.Previous ->
            if (layoutDirection == LayoutDirection.Ltr) -widthPx else widthPx
        ArticleSwipeDirection.Next ->
            if (layoutDirection == LayoutDirection.Ltr) widthPx else -widthPx
    }

internal fun articleSwipeSettleOffset(
    direction: ArticleSwipeDirection,
    widthPx: Float,
    layoutDirection: LayoutDirection,
): Float = -articleSwipePageOffset(direction, widthPx, layoutDirection)

/**
 * Which way a horizontal drag is heading (i.e. which page is being pulled in),
 * independent of the settle threshold. Null when the finger is at rest.
 */
internal fun articleSwipeDragDirection(
    dragOffset: Float,
    layoutDirection: LayoutDirection,
): ArticleSwipeDirection? {
    if (dragOffset == 0f) return null
    return when {
        layoutDirection == LayoutDirection.Ltr && dragOffset < 0f -> ArticleSwipeDirection.Next
        layoutDirection == LayoutDirection.Ltr -> ArticleSwipeDirection.Previous
        dragOffset < 0f -> ArticleSwipeDirection.Previous
        else -> ArticleSwipeDirection.Next
    }
}

/**
 * A headline measurement only applies to the article the slot currently owns.
 * Prefetched neighbor WebViews can report measurements while their slot has
 * already moved on to a different article, so guard against stale results.
 */
internal fun resolveSlotHeadlineHeightPx(
    slotArticleId: String?,
    measuredArticleId: String?,
    measuredPx: Int,
): Int? =
    measuredPx
        .takeIf { it > 0 && slotArticleId != null && slotArticleId == measuredArticleId }

private class ArticleSwipeSlot(
    val index: Int,
) {
    var articleId by mutableStateOf<String?>(null)
    var readerState by mutableStateOf<ReaderState?>(null)
    var target by mutableStateOf<ReaderState.PrefetchResult?>(null)
    var headlineHeightPx by mutableStateOf(0)
    var scrollSnapshot by mutableStateOf(WebViewScrollSnapshot(0, 0, 0, true, true))
}

@Composable
fun ArticleSwipePager(
    currentReaderState: ReaderState,
    contentPadding: PaddingValues,
    enabled: Boolean,
    onLoadArticle: (String, Int) -> Unit,
    loadPreview: suspend (String, Int?) -> ReaderState,
    onBringToTopHandled: () -> Unit,
    bringToTopRequest: Int,
    onCurrentHeadlineMeasured: (Int) -> Unit,
    onCurrentScrollSnapshotChange: (WebViewScrollSnapshot) -> Unit,
    onTitleLayersChange: (List<TopBarTitleLayer>) -> Unit,
    onImageClick: (String, String) -> Unit,
    onLinkLongPress: (String, String) -> Unit,
    onShowCustomView: (View, WebChromeClient.CustomViewCallback) -> Unit,
    onHideCustomView: () -> Unit,
) {
    val slots =
        remember {
            List(3) { index -> ArticleSwipeSlot(index = index) }
        }
    var previousSlotIndex by remember { mutableIntStateOf(1) }
    var currentSlotIndex by remember { mutableIntStateOf(0) }
    var nextSlotIndex by remember { mutableIntStateOf(2) }
    var pendingSwipeCommitArticleId by remember { mutableStateOf<String?>(null) }
    var dragOffsetPx by remember { mutableFloatStateOf(0f) }
    var isSettling by remember { mutableStateOf(false) }
    val settleOffset = remember { Animatable(0f) }
    val layoutDirection = LocalLayoutDirection.current
    val scope = rememberCoroutineScope()

    LaunchedEffect(currentReaderState.articleId, currentReaderState.content) {
        val articleId = currentReaderState.articleId ?: return@LaunchedEffect
        val existingSlotIndex = slots.indexOfFirst { it.articleId == articleId }
        if (pendingSwipeCommitArticleId == articleId && existingSlotIndex != -1) {
            currentSlotIndex = existingSlotIndex
            slots[existingSlotIndex].readerState = currentReaderState
            slots[existingSlotIndex].target = null
            pendingSwipeCommitArticleId = null
            return@LaunchedEffect
        }

        val currentSlot = slots[currentSlotIndex]
        if (currentSlot.articleId == articleId) {
            currentSlot.readerState = currentReaderState
            currentSlot.target = null
        } else if (pendingSwipeCommitArticleId == null) {
            previousSlotIndex = 1
            currentSlotIndex = 0
            nextSlotIndex = 2
            slots.forEach { slot ->
                if (slot.index == currentSlotIndex) {
                    slot.articleId = articleId
                    slot.readerState = currentReaderState
                    slot.target = null
                    slot.headlineHeightPx = 0
                } else {
                    slot.articleId = null
                    slot.readerState = null
                    slot.target = null
                    slot.headlineHeightPx = 0
                }
            }
        }
    }

    val visibleCurrentState = slots[currentSlotIndex].readerState ?: currentReaderState
    LaunchedEffect(
        visibleCurrentState.articleId,
        visibleCurrentState.previousArticle,
        visibleCurrentState.nextArticle,
        currentSlotIndex,
    ) {
        val currentId = visibleCurrentState.articleId ?: return@LaunchedEffect
        val previous = visibleCurrentState.previousArticle
        val next = visibleCurrentState.nextArticle
        val availableSlots = slots.filter { it.index != currentSlotIndex }.map { it.index }

        val existingPrevious =
            previous?.let { target ->
                availableSlots.firstOrNull { slots[it].articleId == target.articleId }
            }
        val existingNext =
            next?.let { target ->
                availableSlots.firstOrNull {
                    it != existingPrevious && slots[it].articleId == target.articleId
                }
            }
        val remaining = availableSlots.filter { it != existingPrevious && it != existingNext }

        previousSlotIndex = existingPrevious ?: remaining.firstOrNull() ?: previousSlotIndex
        nextSlotIndex =
            existingNext
                ?: remaining.firstOrNull { it != previousSlotIndex }
                ?: nextSlotIndex

        suspend fun loadInto(slotIndex: Int, target: ReaderState.PrefetchResult?) {
            val slot = slots[slotIndex]
            if (target == null) {
                slot.articleId = null
                slot.readerState = null
                slot.target = null
                slot.headlineHeightPx = 0
                return
            }
            slot.target = target
            if (slot.articleId == target.articleId && slot.readerState != null) return
            slot.articleId = target.articleId
            slot.readerState = ReaderState(articleId = target.articleId)
            slot.headlineHeightPx = 0
            val preview = loadPreview(target.articleId, target.index)
            if (slot.articleId == target.articleId && currentId == visibleCurrentState.articleId) {
                slot.readerState = preview
            }
        }

        launch { loadInto(previousSlotIndex, previous) }
        launch { loadInto(nextSlotIndex, next) }
    }

    // The headline measurement is per-slot state so that a prefetched neighbor
    // can be measured off-screen. When a slot becomes current (or the parent
    // reloads the article after a swipe), seed the top-bar threshold from that
    // slot's stored value. Live measurements for the current slot are forwarded
    // directly below, so this only needs to fire on slot/article changes.
    val currentSlot = slots[currentSlotIndex]
    LaunchedEffect(
        currentSlotIndex,
        currentSlot.articleId,
        currentReaderState.articleId,
    ) {
        // Only seed once the parent has actually switched to this slot's
        // article; seeding against the previous article's scroll position would
        // make the top bar flash the new title before the page settles at top.
        if (
            currentSlot.articleId != null &&
                currentSlot.articleId == currentReaderState.articleId
        ) {
            onCurrentHeadlineMeasured(currentSlot.headlineHeightPx)
        }
    }

    // Animate each slot's "title should show" boolean so a title fades in and
    // out as its headline scrolls past. During a swipe these alphas are
    // multiplied by the drag progress, so the crossfade tracks the finger
    // instead of running on an independent bar-level animation.
    val slotTitleAlpha =
        slots.map { slot ->
            key(slot.index) {
                animateFloatAsState(
                    targetValue =
                        if (
                            shouldShowTitleInTopBar(
                                slot.scrollSnapshot.scrollY,
                                slot.headlineHeightPx,
                            )
                        ) {
                            1f
                        } else {
                            0f
                        },
                    animationSpec = tween(durationMillis = 200),
                    label = "slotTitleAlpha",
                ).value
            }
        }

    BoxWithConstraints(
        modifier =
            Modifier
                .fillMaxSize()
                .clipToBounds()
    ) {
        val widthPx = with(LocalDensity.current) { maxWidth.toPx() }

        // Crossfade the top-bar title with the horizontal drag: the outgoing
        // slot's title fades out and the incoming slot's fades in, each only if
        // its own headline is scrolled out of view. The incoming layer is
        // invisible when that article is still at the top.
        val swipeProgress = (abs(dragOffsetPx) / widthPx).coerceIn(0f, 1f)
        val incomingSlotIndex =
            articleSwipeDragDirection(dragOffsetPx, layoutDirection)?.let { direction ->
                when (direction) {
                    ArticleSwipeDirection.Next -> nextSlotIndex
                    ArticleSwipeDirection.Previous -> previousSlotIndex
                }
            }
        val titleLayers =
            buildList {
                val outgoingTitle = visibleCurrentState.title
                val outgoingAlpha = slotTitleAlpha[currentSlotIndex] * (1f - swipeProgress)
                if (outgoingAlpha > 0.01f && !outgoingTitle.isNullOrBlank()) {
                    add(TopBarTitleLayer(outgoingTitle, outgoingAlpha))
                }
                if (incomingSlotIndex != null) {
                    val incomingTitle = slots[incomingSlotIndex].readerState?.title
                    val incomingAlpha = slotTitleAlpha[incomingSlotIndex] * swipeProgress
                    if (incomingAlpha > 0.01f && !incomingTitle.isNullOrBlank()) {
                        add(TopBarTitleLayer(incomingTitle, incomingAlpha))
                    }
                }
            }
        SideEffect { onTitleLayersChange(titleLayers) }

        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .then(
                        if (enabled) {
                            Modifier.articleSwipePointerInput(
                                layoutDirection = layoutDirection,
                                gesturesEnabled = !isSettling,
                                getCurrentState = { slots[currentSlotIndex].readerState },
                                getPreviousTarget = { slots[previousSlotIndex].target },
                                getNextTarget = { slots[nextSlotIndex].target },
                                onSettlePrevious = {
                                    scope.launch {
                                        val target =
                                            slots[previousSlotIndex].target ?: return@launch
                                        isSettling = true
                                        try {
                                            settleOffset.snapTo(dragOffsetPx)
                                            settleOffset.animateTo(
                                                targetValue =
                                                    articleSwipeSettleOffset(
                                                        direction = ArticleSwipeDirection.Previous,
                                                        widthPx = widthPx,
                                                        layoutDirection = layoutDirection,
                                                    ),
                                                animationSpec =
                                                    spring(
                                                        dampingRatio = Spring.DampingRatioNoBouncy,
                                                        stiffness = Spring.StiffnessMediumLow,
                                                    ),
                                            ) {
                                                dragOffsetPx = value
                                            }
                                            val oldCurrent = currentSlotIndex
                                            currentSlotIndex = previousSlotIndex
                                            nextSlotIndex = oldCurrent
                                            previousSlotIndex =
                                                slots
                                                    .first { slot ->
                                                        slot.index != currentSlotIndex &&
                                                            slot.index != nextSlotIndex
                                                    }
                                                    .index
                                            pendingSwipeCommitArticleId = target.articleId
                                            dragOffsetPx = 0f
                                            settleOffset.snapTo(0f)
                                            onLoadArticle(target.articleId, target.index)
                                        } finally {
                                            isSettling = false
                                        }
                                    }
                                },
                                onSettleNext = {
                                    scope.launch {
                                        val target = slots[nextSlotIndex].target ?: return@launch
                                        isSettling = true
                                        try {
                                            settleOffset.snapTo(dragOffsetPx)
                                            settleOffset.animateTo(
                                                targetValue =
                                                    articleSwipeSettleOffset(
                                                        direction = ArticleSwipeDirection.Next,
                                                        widthPx = widthPx,
                                                        layoutDirection = layoutDirection,
                                                    ),
                                                animationSpec =
                                                    spring(
                                                        dampingRatio = Spring.DampingRatioNoBouncy,
                                                        stiffness = Spring.StiffnessMediumLow,
                                                    ),
                                            ) {
                                                dragOffsetPx = value
                                            }
                                            val oldCurrent = currentSlotIndex
                                            currentSlotIndex = nextSlotIndex
                                            previousSlotIndex = oldCurrent
                                            nextSlotIndex =
                                                slots
                                                    .first { slot ->
                                                        slot.index != currentSlotIndex &&
                                                            slot.index != previousSlotIndex
                                                    }
                                                    .index
                                            pendingSwipeCommitArticleId = target.articleId
                                            dragOffsetPx = 0f
                                            settleOffset.snapTo(0f)
                                            onLoadArticle(target.articleId, target.index)
                                        } finally {
                                            isSettling = false
                                        }
                                    }
                                },
                                onCancel = {
                                    scope.launch {
                                        isSettling = true
                                        try {
                                            settleOffset.snapTo(dragOffsetPx)
                                            settleOffset.animateTo(
                                                targetValue = 0f,
                                                animationSpec =
                                                    spring(
                                                        dampingRatio = Spring.DampingRatioNoBouncy,
                                                        stiffness = Spring.StiffnessMediumLow,
                                                    ),
                                            ) {
                                                dragOffsetPx = value
                                            }
                                            dragOffsetPx = 0f
                                            settleOffset.snapTo(0f)
                                        } finally {
                                            isSettling = false
                                        }
                                    }
                                },
                                onDragOffsetChange = { dragOffsetPx = it },
                            )
                        } else {
                            Modifier
                        }
                    )
        ) {
            val pages =
                listOf(
                    previousSlotIndex to
                        articleSwipePageOffset(
                            direction = ArticleSwipeDirection.Previous,
                            widthPx = widthPx,
                            layoutDirection = layoutDirection,
                        ),
                    currentSlotIndex to 0f,
                    nextSlotIndex to
                        articleSwipePageOffset(
                            direction = ArticleSwipeDirection.Next,
                            widthPx = widthPx,
                            layoutDirection = layoutDirection,
                        ),
                ).distinctBy { it.first }

            pages.forEach { (slotIndex, baseOffset) ->
                val slot = slots[slotIndex]
                val state = slot.readerState ?: return@forEach
                key(slot.index) {
                    Box(
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .offset {
                                    IntOffset((baseOffset + dragOffsetPx).roundToInt(), 0)
                                }
                    ) {
                        val isCurrent = slotIndex == currentSlotIndex
                        ArticleSwipePageContent(
                            readerState = state,
                            contentPadding = contentPadding,
                            bringToTopRequest = if (isCurrent) bringToTopRequest else 0,
                            onBringToTopHandled = onBringToTopHandled,
                            onHeadlineMeasured = { px ->
                                resolveSlotHeadlineHeightPx(
                                    slotArticleId = slot.articleId,
                                    measuredArticleId = state.articleId,
                                    measuredPx = px,
                                )?.let { measuredPx ->
                                    slot.headlineHeightPx = measuredPx
                                    if (isCurrent) onCurrentHeadlineMeasured(measuredPx)
                                }
                            },
                            onScrollSnapshotChange = { snapshot ->
                                slot.scrollSnapshot = snapshot
                                if (isCurrent) onCurrentScrollSnapshotChange(snapshot)
                            },
                            onImageClick = onImageClick,
                            onLinkLongPress = onLinkLongPress,
                            onShowCustomView = onShowCustomView,
                            onHideCustomView = onHideCustomView,
                        )
                    }
                }
            }
        }
    }
}

private fun Modifier.articleSwipePointerInput(
    layoutDirection: LayoutDirection,
    gesturesEnabled: Boolean,
    getCurrentState: () -> ReaderState?,
    getPreviousTarget: () -> ReaderState.PrefetchResult?,
    getNextTarget: () -> ReaderState.PrefetchResult?,
    onSettlePrevious: () -> Unit,
    onSettleNext: () -> Unit,
    onCancel: () -> Unit,
    onDragOffsetChange: (Float) -> Unit,
): Modifier =
    pointerInput(layoutDirection, gesturesEnabled) {
        if (!gesturesEnabled) return@pointerInput
        val thresholdPx = 96.dp.toPx()
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            var dragOffset = 0f
            var totalDragOffset = 0f
            var totalOffset = Offset.Zero
            val drag =
                awaitHorizontalTouchSlopOrCancellation(down.id) { change, overSlop ->
                    totalDragOffset += overSlop
                    totalOffset += change.positionChange()
                    // Only claim the gesture for clearly horizontal drags (like ViewPager2:
                    // horizontal must exceed slop AND dominate vertical). Otherwise a
                    // slightly diagonal vertical scroll would get stolen mid-flight and
                    // stutter against the WebView's own vertical scroll.
                    val horizontalDominant = abs(totalOffset.x) > abs(totalOffset.y)
                    val canDrag =
                        horizontalDominant &&
                            canDragInDirection(
                                offset = totalDragOffset,
                                layoutDirection = layoutDirection,
                                canLoadPrevious = getPreviousTarget() != null,
                                canLoadNext = getNextTarget() != null,
                            )
                    dragOffset = if (canDrag) totalDragOffset else 0f
                    onDragOffsetChange(dragOffset)
                    if (canDrag) {
                        change.consume()
                    }
                } ?: return@awaitEachGesture

            horizontalDrag(drag.id) { change ->
                if (change.changedToUpIgnoreConsumed()) return@horizontalDrag
                totalDragOffset += change.positionChange().x
                dragOffset =
                    if (
                        canDragInDirection(
                            offset = totalDragOffset,
                            layoutDirection = layoutDirection,
                            canLoadPrevious = getPreviousTarget() != null,
                            canLoadNext = getNextTarget() != null,
                        )
                    ) {
                        totalDragOffset
                    } else {
                        0f
                    }
                onDragOffsetChange(dragOffset)
                change.consume()
            }

            val hasCurrent = getCurrentState()?.articleId != null
            if (!hasCurrent || abs(dragOffset) < thresholdPx) {
                onCancel()
                return@awaitEachGesture
            }
            when (
                resolveArticleSwipeSettleDirection(
                    dragOffset = dragOffset,
                    threshold = thresholdPx,
                    layoutDirection = layoutDirection,
                    canLoadPrevious = getPreviousTarget() != null,
                    canLoadNext = getNextTarget() != null,
                )
            ) {
                ArticleSwipeDirection.Previous -> onSettlePrevious()
                ArticleSwipeDirection.Next -> onSettleNext()
                null -> onCancel()
            }
        }
    }

private fun canDragInDirection(
    offset: Float,
    layoutDirection: LayoutDirection,
    canLoadPrevious: Boolean,
    canLoadNext: Boolean,
): Boolean {
    if (offset == 0f) return true
    val direction =
        when {
            layoutDirection == LayoutDirection.Ltr && offset < 0f -> ArticleSwipeDirection.Next
            layoutDirection == LayoutDirection.Ltr -> ArticleSwipeDirection.Previous
            offset < 0f -> ArticleSwipeDirection.Previous
            else -> ArticleSwipeDirection.Next
    }
    return when (direction) {
        ArticleSwipeDirection.Previous -> canLoadPrevious
        ArticleSwipeDirection.Next -> canLoadNext
    }
}

@Composable
private fun ArticleSwipePageContent(
    readerState: ReaderState,
    contentPadding: PaddingValues,
    bringToTopRequest: Int,
    onBringToTopHandled: () -> Unit,
    onHeadlineMeasured: (Int) -> Unit,
    onScrollSnapshotChange: (WebViewScrollSnapshot) -> Unit,
    onImageClick: (String, String) -> Unit,
    onLinkLongPress: (String, String) -> Unit,
    onShowCustomView: (View, WebChromeClient.CustomViewCallback) -> Unit,
    onHideCustomView: () -> Unit,
) {
    LaunchedEffect(bringToTopRequest) {
        if (bringToTopRequest != 0) {
            onBringToTopHandled()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Content(
            contentPadding = contentPadding,
            content = readerState.content.text ?: "",
            feedName = readerState.feedName,
            title = readerState.title.toString(),
            author = readerState.author,
            link = readerState.link,
            publishedDate = readerState.publishedDate,
            isLoading = readerState.content is ReaderState.Loading,
            scrollToTopRequest = bringToTopRequest,
            onHeadlineMeasured = onHeadlineMeasured,
            onImageClick = onImageClick,
            onScrollSnapshotChange = onScrollSnapshotChange,
            onLinkLongPress = onLinkLongPress,
            onShowCustomView = onShowCustomView,
            onHideCustomView = onHideCustomView,
        )
    }
}
