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
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import me.ash.reader.ui.component.webview.HorizontalScrollAwareWebView
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

/**
 * Which slots need a media state change when a swipe settles onto a new
 * current slot: every off-screen slot is muted, the incoming slot resumes.
 */
internal data class ArticleSwipeMediaUpdate(
    val pauseSlotIndices: List<Int>,
    val resumeSlotIndex: Int,
)

internal fun resolveArticleSwipeMediaUpdate(
    oldCurrentSlotIndex: Int,
    newCurrentSlotIndex: Int,
    slotIndices: List<Int>,
): ArticleSwipeMediaUpdate =
    ArticleSwipeMediaUpdate(
        pauseSlotIndices = slotIndices.filter { it != newCurrentSlotIndex },
        resumeSlotIndex = newCurrentSlotIndex,
    )

private class ArticleSwipeSlot(
    val index: Int,
) {
    var articleId by mutableStateOf<String?>(null)
    var readerState by mutableStateOf<ReaderState?>(null)
    var target by mutableStateOf<ReaderState.PrefetchResult?>(null)
    var previewJob: Job? = null
    var fullJob: Job? = null
    var headlineHeightPx by mutableStateOf(0)
    var scrollSnapshot by mutableStateOf(WebViewScrollSnapshot(0, 0, 0, true, true))
    var webView by mutableStateOf<HorizontalScrollAwareWebView?>(null)
}

@Composable
fun ArticleSwipePager(
    currentReaderState: ReaderState,
    contentPadding: PaddingValues,
    enabled: Boolean,
    onLoadArticle: (String) -> Unit,
    loadPreview: suspend (String) -> ReaderState,
    loadFullPreview: suspend (String) -> ReaderState?,
    swipeNeighborTarget:
        (articleId: String, isNext: Boolean) -> ReaderState.PrefetchResult?,
    onBringToTopHandled: () -> Unit,
    bringToTopRequest: Int,
    onCurrentHeadlineMeasured: (Int) -> Unit,
    onCurrentScrollSnapshotChange: (WebViewScrollSnapshot) -> Unit,
    onTitleLayersChange: (List<TopBarTitleLayer>) -> Unit,
    onVisibleArticleChange: (String?) -> Unit,
    onImageClick: (String, String, String) -> Unit,
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

    fun setSlotWebView(slotIndex: Int, webView: HorizontalScrollAwareWebView?) {
        // The pool can hand the same physical WebView to another slot, so
        // only the current owner may keep a reference to it.
        if (webView != null) {
            slots.forEach { slot ->
                if (slot.index != slotIndex && slot.webView === webView) slot.webView = null
            }
        }
        slots.firstOrNull { it.index == slotIndex }?.webView = webView
        if (webView == null) return
        // A pooled WebView still carries the pause state of the slot it served
        // last, and the pool resumes it on obtain without touching that flag -
        // so it can reach this pane as "paused" while actually running. Assert
        // the role before the pane loads: the current pane must be free to
        // render (a stale flag would arm the deferred re-pause against it and
        // freeze the article as soon as it opens), and every off-screen pane
        // must start paused so its primed document is resumed only long enough
        // to present its first frame.
        if (slotIndex == currentSlotIndex) {
            webView.resumeMediaPlayback()
        } else {
            webView.pauseMediaPlayback()
        }
    }

    fun applyArticleSwipeMediaUpdate(update: ArticleSwipeMediaUpdate) {
        update.pauseSlotIndices.forEach { index ->
            slots.firstOrNull { it.index == index }?.webView?.pauseMediaPlayback()
        }
        slots.firstOrNull { it.index == update.resumeSlotIndex }?.webView?.resumeMediaPlayback()
    }

    // Neighbor prefetch pipeline. [loadInto] assigns an article to a slot and
    // fetches its preview; both it and [kickSlotPreview] are idempotent so the
    // settle-time kick, the post-settle rotation, and the swipe-start retry can
    // all call them safely. The preview job is tracked per slot so a retry can
    // tell "still fetching" apart from "fetch died".
    fun fetchPreview(slotIndex: Int, target: ReaderState.PrefetchResult) {
        val slot = slots.firstOrNull { it.index == slotIndex } ?: return
        slot.previewJob?.cancel()
        slot.previewJob = scope.launch {
            val preview = loadPreview(target.articleId)
            if (slot.articleId != target.articleId) return@launch
            slot.readerState = preview
            // The preview falls back to the feed body, which on full-content
            // feeds is only a summary; upgrade the pane to the fetched article
            // so a swipe never reveals a half-empty page. Cancelable per slot
            // so re-priming (or a settle that assigns the current state) wins.
            slot.fullJob?.cancel()
            slot.fullJob = scope.launch {
                val full = loadFullPreview(target.articleId) ?: return@launch
                if (slot.articleId == target.articleId) slot.readerState = full
            }
        }
    }

    fun loadInto(slotIndex: Int, target: ReaderState.PrefetchResult?) {
        val slot = slots.firstOrNull { it.index == slotIndex } ?: return
        if (target == null) {
            slot.previewJob?.cancel()
            slot.previewJob = null
            slot.fullJob?.cancel()
            slot.fullJob = null
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
        fetchPreview(slotIndex, target)
    }

    fun kickSlotPreview(slotIndex: Int) {
        val slot = slots.firstOrNull { it.index == slotIndex } ?: return
        val target = slot.target ?: return
        if (slot.articleId != target.articleId) {
            loadInto(slotIndex, target)
            return
        }
        if (slot.previewJob?.isActive == true) return
        if (slot.readerState == null || slot.readerState?.content is ReaderState.Loading) {
            fetchPreview(slotIndex, target)
            return
        }
        // Preview already landed on the feed body; retry the full-content
        // upgrade if the first attempt was cancelled or failed (swipe start is
        // the last chance to fetch before the pane is revealed).
        if (
            slot.fullJob?.isActive != true && slot.readerState?.content is ReaderState.Description
        ) {
            slot.fullJob = scope.launch {
                val full = loadFullPreview(target.articleId) ?: return@launch
                if (slot.articleId == target.articleId) slot.readerState = full
            }
        }
    }

    // The pane that gets revealed by the NEXT swipe is primed at settle time by
    // loading one article past the incoming one. That normally comes from the
    // incoming slot's ReaderState - but when its preview is still in flight the
    // state carries no neighbors, so the priming silently no-ops and the pane
    // keeps showing the article it held three swipes ago (the stale WebView the
    // next drag then reveals). Fall back to the article-list snapshot so the far
    // pane is always assigned inside the settle animation instead of waiting for
    // the post-settle commit, which lands a few frames before the next swipe.
    fun resolveFarTarget(
        incomingSlotIndex: Int,
        direction: ArticleSwipeDirection,
    ): ReaderState.PrefetchResult? {
        val incoming = slots.firstOrNull { it.index == incomingSlotIndex } ?: return null
        incoming.readerState?.let { state ->
            val neighbor =
                when (direction) {
                    ArticleSwipeDirection.Next -> state.nextArticle
                    ArticleSwipeDirection.Previous -> state.previousArticle
                }
            if (neighbor != null) return neighbor
        }
        val incomingId = incoming.articleId ?: return null
        return swipeNeighborTarget(incomingId, direction == ArticleSwipeDirection.Next)
    }

    // Diagnostic: dump pager state on every slot/current change so blank panes
    // can be attributed to WebView pool timing from logcat alone.
    val slotStateKey =
        buildString {
            append("cur=").append(currentSlotIndex)
            append(" prev=").append(previousSlotIndex)
            append(" next=").append(nextSlotIndex)
            append(" pending=").append(pendingSwipeCommitArticleId)
            slots.forEach { slot ->
                val wv = slot.webView
                append(" |s").append(slot.index)
                append(" id=").append(slot.articleId ?: "-")
                append(" wv=").append(Integer.toHexString(System.identityHashCode(wv)))
                append(" doc=").append(wv?.docState)
                append(" drawn=").append(wv != null && !wv.awaitingFirstDraw)
                append(" maxScroll=").append(slot.scrollSnapshot.maxScrollY)
                append(" vp=").append(slot.scrollSnapshot.viewportHeight)
                append(" content=").append(slot.readerState?.content?.let { it::class.simpleName })
            }
        }
    LaunchedEffect(slotStateKey) {
        timber.log.Timber.tag("RYSwiper").d("%s", slotStateKey)
    }

    // Diagnostic: while a finger is held mid-swipe, sample slot state so we can
    // see whether the incoming pane's WebView keeps loading/drawing during the hold.
    val isDraggingMidSwipe = dragOffsetPx != 0f
    LaunchedEffect(isDraggingMidSwipe) {
        if (!isDraggingMidSwipe) return@LaunchedEffect
        while (true) {
            timber.log.Timber.tag("RYSwiper").d("dragTick off=%.0f %s", dragOffsetPx, slotStateKey)
            kotlinx.coroutines.delay(250)
        }
    }

    LaunchedEffect(currentReaderState.articleId, currentReaderState.content) {
        val articleId = currentReaderState.articleId ?: return@LaunchedEffect
        val existingSlotIndex = slots.indexOfFirst { it.articleId == articleId }
        if (pendingSwipeCommitArticleId == articleId && existingSlotIndex != -1) {
            currentSlotIndex = existingSlotIndex
            slots[existingSlotIndex].previewJob?.cancel()
            slots[existingSlotIndex].previewJob = null
            slots[existingSlotIndex].fullJob?.cancel()
            slots[existingSlotIndex].fullJob = null
            slots[existingSlotIndex].readerState = currentReaderState
            slots[existingSlotIndex].target = null
            pendingSwipeCommitArticleId = null
            return@LaunchedEffect
        }

        val currentSlot = slots[currentSlotIndex]
        if (currentSlot.articleId == articleId) {
            currentSlot.previewJob?.cancel()
            currentSlot.previewJob = null
            currentSlot.fullJob?.cancel()
            currentSlot.fullJob = null
            currentSlot.readerState = currentReaderState
            currentSlot.target = null
        } else if (pendingSwipeCommitArticleId == null) {
            previousSlotIndex = 1
            currentSlotIndex = 0
            nextSlotIndex = 2
            slots.forEach { slot ->
                slot.previewJob?.cancel()
                slot.previewJob = null
                slot.fullJob?.cancel()
                slot.fullJob = null
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
        if (visibleCurrentState.articleId == null) return@LaunchedEffect
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

        loadInto(previousSlotIndex, previous)
        loadInto(nextSlotIndex, next)
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
        // During an active drag (including the settle animation, where
        // dragOffsetPx is driven to its target) report the incoming slot so
        // taps act on the article on screen, not the outgoing one.
        SideEffect {
            onVisibleArticleChange(
                incomingSlotIndex?.let { slots[it].articleId }
                    ?: visibleCurrentState.articleId
            )
        }

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
                                onKickNeighbors = {
                                    kickSlotPreview(previousSlotIndex)
                                    kickSlotPreview(nextSlotIndex)
                                },
                                onSettlePrevious = {
                                    scope.launch {
                                        val target =
                                            slots[previousSlotIndex].target ?: return@launch
                                        timber.log.Timber.tag("RYSwiper")
                                            .d("settle PREV -> %s", target.articleId)
                                        // Mirror of the settle-NEXT kick: the pane that becomes
                                        // "previous" after this swipe needs the article before the
                                        // incoming one, and its rebuild should overlap the settle
                                        // animation rather than wait for it.
                                        resolveFarTarget(
                                                incomingSlotIndex = previousSlotIndex,
                                                direction = ArticleSwipeDirection.Previous,
                                            )
                                            ?.let { far -> loadInto(nextSlotIndex, far) }
                                        // Silence the outgoing article up front so its
                                        // audio/video cannot play under the incoming page.
                                        applyArticleSwipeMediaUpdate(
                                            resolveArticleSwipeMediaUpdate(
                                                oldCurrentSlotIndex = currentSlotIndex,
                                                newCurrentSlotIndex = previousSlotIndex,
                                                slotIndices = slots.map { it.index },
                                            )
                                        )
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
                                            onLoadArticle(target.articleId)
                                        } finally {
                                            isSettling = false
                                        }
                                    }
                                },
                                onSettleNext = {
                                    scope.launch {
                                        val target = slots[nextSlotIndex].target ?: return@launch
                                        timber.log.Timber.tag("RYSwiper")
                                            .d("settle NEXT -> %s", target.articleId)
                                        // The pane that becomes "next" after this swipe has to
                                        // show a different article. Kick its rebuild NOW so the
                                        // preview fetch + html build + paint overlap the ~0.9s
                                        // settle animation instead of running after it; otherwise
                                        // this pane still holds the article from three swipes ago
                                        // when the user swipes again as soon as gestures re-enable.
                                        resolveFarTarget(
                                                incomingSlotIndex = nextSlotIndex,
                                                direction = ArticleSwipeDirection.Next,
                                            )
                                            ?.let { far -> loadInto(previousSlotIndex, far) }
                                        // Silence the outgoing article up front so its
                                        // audio/video cannot play under the incoming page.
                                        applyArticleSwipeMediaUpdate(
                                            resolveArticleSwipeMediaUpdate(
                                                oldCurrentSlotIndex = currentSlotIndex,
                                                newCurrentSlotIndex = nextSlotIndex,
                                                slotIndices = slots.map { it.index },
                                            )
                                        )
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
                                            onLoadArticle(target.articleId)
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
                            onWebViewCreated = { webView -> setSlotWebView(slotIndex, webView) },
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
    onKickNeighbors: () -> Unit,
): Modifier =
    pointerInput(layoutDirection, gesturesEnabled) {
        if (!gesturesEnabled) return@pointerInput
        val thresholdPx = 96.dp.toPx()
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            timber.log.Timber.tag("RYSwiper").d("down")
            // Safety net: if a neighbor's preview fetch never completed (failed,
            // cancelled), restart it the moment the user reaches to swipe so the
            // pane they are about to reveal is not stuck empty. No-op otherwise.
            onKickNeighbors()
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
            timber.log.Timber.tag("RYSwiper").d("up offset=%d", dragOffset.roundToInt())

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
    onWebViewCreated: ((HorizontalScrollAwareWebView?) -> Unit)? = null,
    onHeadlineMeasured: (Int) -> Unit,
    onScrollSnapshotChange: (WebViewScrollSnapshot) -> Unit,
    onImageClick: (String, String, String) -> Unit,
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
            onWebViewCreated = onWebViewCreated,
        )
    }
}
