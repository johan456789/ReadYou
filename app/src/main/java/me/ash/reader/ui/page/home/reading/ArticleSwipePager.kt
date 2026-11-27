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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
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
import me.ash.reader.ui.component.webview.ImageSnapshot
import me.ash.reader.ui.component.webview.WebViewScrollSnapshot
import me.ash.reader.ui.page.adaptive.ReaderState
import kotlin.math.abs
import kotlin.math.roundToInt

internal enum class ArticleSwipeDirection {
    Previous,
    Next,
}

/**
 * The scroll axis a gesture is locked onto. Decided once - the first time
 * either axis passes the touch slop - and never revisited for the rest of the
 * touch sequence.
 */
internal enum class ArticleSwipeAxis {
    Horizontal,
    Vertical,
}

/**
 * Decides which axis owns a gesture with the same test the WebView applies in
 * dispatchTouchEvent (horizontal iff |dx| > |dy| once either axis passes the
 * touch slop), so both sides lock onto the same axis on the same event.
 * Returns null while neither axis has passed the slop yet.
 */
internal fun resolveArticleSwipeAxis(
    totalDx: Float,
    totalDy: Float,
    touchSlop: Float,
): ArticleSwipeAxis? {
    if (abs(totalDx) <= touchSlop && abs(totalDy) <= touchSlop) return null
    return if (abs(totalDx) > abs(totalDy)) {
        ArticleSwipeAxis.Horizontal
    } else {
        ArticleSwipeAxis.Vertical
    }
}

/**
 * One-shot scroll-axis decision for a gesture. Feed each movement delta as
 * it arrives: the first time either cumulative axis passes [touchSlop], the
 * axis is decided via [resolveArticleSwipeAxis] - the same |dx| > |dy| test
 * the WebView applies in dispatchTouchEvent - and it never changes again.
 * The monotone lock makes the axis a property of the whole touch sequence
 * instead of a per-move vote, so an L-shaped scroll (vertical first, then
 * curving sideways) can never be claimed by the article swipe after the
 * WebView has already taken it.
 */
internal class SwipeAxisLock(private val touchSlop: Float) {
    var decidedAxis: ArticleSwipeAxis? = null
        private set

    /** Cumulative movement since the gesture started, in px. */
    var totalDx = 0f
        private set
    var totalDy = 0f
        private set

    /** Feeds one movement delta; returns the locked axis, or null while undecided. */
    fun feed(deltaX: Float, deltaY: Float): ArticleSwipeAxis? {
        totalDx += deltaX
        totalDy += deltaY
        if (decidedAxis == null) {
            decidedAxis = resolveArticleSwipeAxis(totalDx, totalDy, touchSlop)
        }
        return decidedAxis
    }
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
 * Where the pages sit while a finger drags them: the gesture's whole movement
 * on top of [caughtOffset], where the pages were when the finger took hold of
 * them (zero at rest, non-zero when the drag catches them mid-settle). The
 * movement before the claim counts too, at rest and mid-settle alike, so a
 * swipe is worth the same distance however late its first events arrive.
 * Bounded to one page either way because only the direct neighbors are laid
 * out.
 */
internal fun articleSwipeDragOffset(
    caughtOffset: Float,
    gestureDx: Float,
    widthPx: Float,
): Float = (caughtOffset + gestureDx).coerceIn(-widthPx, widthPx)

/**
 * Decides where a released drag lands. [dragOffset] is where the pages are,
 * [gestureDx] how far this finger moved; they differ when the drag caught the
 * pages mid-settle. Switching article takes a real swipe towards the neighbor
 * that is showing: a leftover offset from the interrupted settle alone must
 * never switch, nor may a swipe that was merely slowing that settle down.
 */
internal fun resolveArticleSwipeReleaseDirection(
    dragOffset: Float,
    gestureDx: Float,
    threshold: Float,
    layoutDirection: LayoutDirection,
    canLoadPrevious: Boolean,
    canLoadNext: Boolean,
): ArticleSwipeDirection? {
    if (abs(gestureDx) < threshold) return null
    if (dragOffset * gestureDx <= 0f) return null
    return resolveArticleSwipeSettleDirection(
        dragOffset = dragOffset,
        threshold = threshold,
        layoutDirection = layoutDirection,
        canLoadPrevious = canLoadPrevious,
        canLoadNext = canLoadNext,
    )
}

/**
 * How a swipe that the pager already switched to relates to the article the
 * reader state currently holds.
 */
internal enum class ArticleSwipeCommitStatus {
    /** The reader state caught up with the swipe. */
    Committed,

    /** The reader state still holds the article the swipe started from. */
    Waiting,

    /** The reader state moved to some other article; the swipe is void. */
    Superseded,
}

internal fun resolveArticleSwipeCommitStatus(
    readerArticleId: String,
    pendingArticleId: String,
    pendingBaseArticleId: String?,
): ArticleSwipeCommitStatus =
    when (readerArticleId) {
        pendingArticleId -> ArticleSwipeCommitStatus.Committed
        pendingBaseArticleId -> ArticleSwipeCommitStatus.Waiting
        else -> ArticleSwipeCommitStatus.Superseded
    }

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
    onImageClick: (String, String, String, ImageSnapshot?) -> Unit,
    onLinkLongPress: (String, String, String?) -> Unit,
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
    // The article the reader state held when the pending swipe was decided, so
    // a reader state that has not caught up yet can be told apart from one
    // that navigated somewhere else.
    var pendingSwipeBaseArticleId by remember { mutableStateOf<String?>(null) }
    // Offset of the current page from its resting position. The slots rotate
    // the moment a swipe is decided, so this always describes "the current
    // page plus how far it is from rest" - a state a new drag can pick up at
    // any point of the settle animation.
    var dragOffsetPx by remember { mutableFloatStateOf(0f) }
    var isDragging by remember { mutableStateOf(false) }
    val settleOffset = remember { Animatable(0f) }
    val settleJob = remember { arrayOfNulls<Job>(1) }
    val layoutDirection = LocalLayoutDirection.current
    val scope = rememberCoroutineScope()
    // The gesture handler outlives recompositions, so it must not read these
    // through the values captured when it started.
    val latestReaderState by rememberUpdatedState(currentReaderState)
    val latestOnLoadArticle by rememberUpdatedState(onLoadArticle)

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

    // Glides the current page to rest from wherever it is. Nothing is waiting
    // for the animation to end, so a new drag may cancel it at any frame.
    fun settleToRest() {
        settleJob[0]?.cancel()
        settleJob[0] =
            scope.launch {
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
            }
    }

    // Makes the neighbor in [direction] the current page. Everything the next
    // gesture depends on is in place when this returns - the outgoing page is
    // the swipe-back target, the pane behind the incoming one is primed - so
    // a swipe can be followed by another one without waiting for the settle
    // animation or for the reader state to catch up.
    fun switchToNeighbor(direction: ArticleSwipeDirection, widthPx: Float) {
        val incomingSlotIndex =
            when (direction) {
                ArticleSwipeDirection.Next -> nextSlotIndex
                ArticleSwipeDirection.Previous -> previousSlotIndex
            }
        val farSlotIndex =
            when (direction) {
                ArticleSwipeDirection.Next -> previousSlotIndex
                ArticleSwipeDirection.Previous -> nextSlotIndex
            }
        val target = slots[incomingSlotIndex].target ?: return
        timber.log.Timber.tag("RYSwiper").d("settle %s -> %s", direction, target.articleId)
        val outgoingSlot = slots[currentSlotIndex]
        // The far pane has to show the article past the incoming one. It is
        // off-screen on the side the pages are moving away from, so its
        // rebuild overlaps the settle animation.
        val farTarget = resolveFarTarget(incomingSlotIndex, direction)
        // Silence the outgoing article up front so its audio/video cannot
        // play under the incoming page.
        applyArticleSwipeMediaUpdate(
            resolveArticleSwipeMediaUpdate(
                oldCurrentSlotIndex = currentSlotIndex,
                newCurrentSlotIndex = incomingSlotIndex,
                slotIndices = slots.map { it.index },
            )
        )
        currentSlotIndex = incomingSlotIndex
        when (direction) {
            ArticleSwipeDirection.Next -> {
                previousSlotIndex = outgoingSlot.index
                nextSlotIndex = farSlotIndex
            }
            ArticleSwipeDirection.Previous -> {
                nextSlotIndex = outgoingSlot.index
                previousSlotIndex = farSlotIndex
            }
        }
        outgoingSlot.target = outgoingSlot.articleId?.let { ReaderState.PrefetchResult(it) }
        kickSlotPreview(outgoingSlot.index)
        loadInto(farSlotIndex, farTarget)
        // Same pixels, now measured from the incoming page.
        dragOffsetPx += articleSwipePageOffset(direction, widthPx, layoutDirection)
        pendingSwipeBaseArticleId = latestReaderState.articleId
        pendingSwipeCommitArticleId = target.articleId
        latestOnLoadArticle(target.articleId)
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

    // Swapping the incoming pane for a loading indicator while it is still
    // sliding in would flash, so a reader state that is still loading is only
    // adopted once the pages are at rest; the pane shows its primed content
    // until then.
    val isSwipeInFlight = dragOffsetPx != 0f
    LaunchedEffect(
        currentReaderState.articleId,
        currentReaderState.content,
        pendingSwipeCommitArticleId,
        isSwipeInFlight,
    ) {
        val articleId = currentReaderState.articleId ?: return@LaunchedEffect
        pendingSwipeCommitArticleId?.let { pending ->
            when (
                resolveArticleSwipeCommitStatus(
                    readerArticleId = articleId,
                    pendingArticleId = pending,
                    pendingBaseArticleId = pendingSwipeBaseArticleId,
                )
            ) {
                ArticleSwipeCommitStatus.Waiting -> return@LaunchedEffect
                ArticleSwipeCommitStatus.Committed,
                ArticleSwipeCommitStatus.Superseded -> pendingSwipeCommitArticleId = null
            }
        }

        val currentSlot = slots[currentSlotIndex]
        if (currentSlot.articleId == articleId) {
            if (
                isSwipeInFlight &&
                    currentReaderState.content is ReaderState.Loading &&
                    currentSlot.readerState != null
            ) {
                return@LaunchedEffect
            }
            currentSlot.previewJob?.cancel()
            currentSlot.previewJob = null
            currentSlot.fullJob?.cancel()
            currentSlot.fullJob = null
            currentSlot.readerState = currentReaderState
            currentSlot.target = null
        } else {
            settleJob[0]?.cancel()
            dragOffsetPx = 0f
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
        val visibleArticleId = visibleCurrentState.articleId ?: return@LaunchedEffect
        // A pane that became current before its preview landed carries no
        // neighbors yet. Fall back to the list snapshot so that gap neither
        // blocks the next swipe nor wipes the page that is still sliding out.
        val previous =
            visibleCurrentState.previousArticle
                ?: swipeNeighborTarget(visibleArticleId, false)
        val next =
            visibleCurrentState.nextArticle
                ?: swipeNeighborTarget(visibleArticleId, true)
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
        // While a finger drags, report the incoming slot so taps act on the
        // article being pulled in. Once the drag is released the pages glide
        // towards the current slot, which by then is the article they land on.
        SideEffect {
            onVisibleArticleChange(
                incomingSlotIndex?.takeIf { isDragging }?.let { slots[it].articleId }
                    ?: visibleCurrentState.articleId
            )
        }
        // The gesture handler goes away with [enabled]; never leave the pages
        // parked off-center behind whatever disabled it.
        LaunchedEffect(enabled) {
            if (!enabled && dragOffsetPx != 0f && settleJob[0]?.isActive != true) {
                isDragging = false
                settleToRest()
            }
        }

        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .then(
                        if (enabled) {
                            Modifier.articleSwipePointerInput(
                                layoutDirection = layoutDirection,
                                getDragOffset = { dragOffsetPx },
                                getPreviousTarget = { slots[previousSlotIndex].target },
                                getNextTarget = { slots[nextSlotIndex].target },
                                onKickNeighbors = {
                                    kickSlotPreview(previousSlotIndex)
                                    kickSlotPreview(nextSlotIndex)
                                },
                                onDragStart = {
                                    settleJob[0]?.cancel()
                                    isDragging = true
                                },
                                onDragOffsetChange = { dragOffsetPx = it },
                                onDragEnd = { direction, pagerWidthPx ->
                                    isDragging = false
                                    if (
                                        direction != null &&
                                            slots[currentSlotIndex].readerState?.articleId != null
                                    ) {
                                        switchToNeighbor(direction, pagerWidthPx)
                                    }
                                    settleToRest()
                                },
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

/**
 * Horizontal article swipe with a one-shot scroll-axis lock.
 *
 * [SwipeAxisLock] decides the axis the first time either axis of the gesture
 * passes the touch slop and never revisits it: horizontal drags page between
 * articles, everything else belongs to the WebView's vertical scroll. An
 * L-shaped scroll (vertical first, then curving sideways) therefore stays a
 * scroll for its entire life instead of claiming the horizontal swipe
 * mid-gesture after the article has already moved.
 */
private fun Modifier.articleSwipePointerInput(
    layoutDirection: LayoutDirection,
    getDragOffset: () -> Float,
    getPreviousTarget: () -> ReaderState.PrefetchResult?,
    getNextTarget: () -> ReaderState.PrefetchResult?,
    onKickNeighbors: () -> Unit,
    onDragStart: () -> Unit,
    onDragOffsetChange: (Float) -> Unit,
    onDragEnd: (direction: ArticleSwipeDirection?, widthPx: Float) -> Unit,
): Modifier =
    pointerInput(layoutDirection) {
        val thresholdPx = 96.dp.toPx()
        val touchSlop = viewConfiguration.touchSlop
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            timber.log.Timber.tag("RYSwiper").d("down")
            // A fresh lock per gesture: the decision must never leak movement
            // totals or a locked axis across touch sequences.
            val axisLock = SwipeAxisLock(touchSlop)
            // Safety net: if a neighbor's preview fetch never completed (failed,
            // cancelled), restart it the moment the user reaches to swipe so the
            // pane they are about to reveal is not stuck empty. No-op otherwise.
            onKickNeighbors()
            var dragOffset = 0f
            var pointerId = down.id
            var claimed = false
            var released = false
            var horizontalLockLogged = false
            // Where the pages were when this gesture took hold of them.
            // Non-zero when the drag catches the pages while the previous
            // swipe is still settling.
            var caughtOffset = 0f

            fun pagerWidthPx() = size.width.toFloat()

            fun resolveDragOffset(): Float {
                val offset =
                    articleSwipeDragOffset(
                        caughtOffset = caughtOffset,
                        gestureDx = axisLock.totalDx,
                        widthPx = pagerWidthPx(),
                    )
                return if (
                    canDragInDirection(
                        offset = offset,
                        layoutDirection = layoutDirection,
                        canLoadPrevious = getPreviousTarget() != null,
                        canLoadNext = getNextTarget() != null,
                    )
                ) {
                    offset
                } else {
                    0f
                }
            }

            try {
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == pointerId } ?: break
                    if (change.changedToUpIgnoreConsumed()) {
                        // Keep dragging with a replacement finger, like the drag
                        // gesture detectors this replaces.
                        val replacement =
                            event.changes.firstOrNull { it.pressed && it.id != pointerId }
                        if (replacement == null) break
                        pointerId = replacement.id
                        continue
                    }
                    val delta = change.positionChange()
                    val axis = axisLock.feed(delta.x, delta.y)

                    if (!claimed) {
                        when (axis) {
                            null -> continue
                            ArticleSwipeAxis.Vertical -> {
                                timber.log.Timber
                                    .tag("RYSwiper")
                                    .d("lock vertical dx=%.0f dy=%.0f", axisLock.totalDx, axisLock.totalDy)
                                break
                            }
                            ArticleSwipeAxis.Horizontal -> {
                                if (!horizontalLockLogged) {
                                    timber.log.Timber
                                        .tag("RYSwiper")
                                        .d("lock horizontal dx=%.0f dy=%.0f", axisLock.totalDx, axisLock.totalDy)
                                    horizontalLockLogged = true
                                }
                            }
                        }
                        // Horizontal is locked in; claim the gesture as soon as the
                        // drag points at an available neighbor. While it points at a
                        // side without one, keep tracking without consuming so the
                        // direction can still be reversed, as before. A settle that
                        // is still running keeps running until then, and is picked
                        // up from wherever it has brought the pages.
                        caughtOffset = getDragOffset()
                        if (
                            !canDragInDirection(
                                offset =
                                    if (caughtOffset == 0f) axisLock.totalDx else caughtOffset,
                                layoutDirection = layoutDirection,
                                canLoadPrevious = getPreviousTarget() != null,
                                canLoadNext = getNextTarget() != null,
                            )
                        ) {
                            continue
                        }
                        claimed = true
                        onDragStart()
                        dragOffset = resolveDragOffset()
                        onDragOffsetChange(dragOffset)
                        change.consume()
                        continue
                    }

                    dragOffset = resolveDragOffset()
                    onDragOffsetChange(dragOffset)
                    change.consume()
                }
                released = true
            } finally {
                if (claimed) {
                    timber.log.Timber
                        .tag("RYSwiper")
                        .d(
                            "up offset=%d dx=%.0f released=%b",
                            dragOffset.roundToInt(),
                            axisLock.totalDx,
                            released,
                        )
                    // A gesture torn down mid-drag (released == false) still has
                    // to let go of the pages; it just never switches article.
                    onDragEnd(
                        if (released) {
                            resolveArticleSwipeReleaseDirection(
                                dragOffset = dragOffset,
                                gestureDx = axisLock.totalDx,
                                threshold = thresholdPx,
                                layoutDirection = layoutDirection,
                                canLoadPrevious = getPreviousTarget() != null,
                                canLoadNext = getNextTarget() != null,
                            )
                        } else {
                            null
                        },
                        pagerWidthPx(),
                    )
                }
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
    onImageClick: (String, String, String, ImageSnapshot?) -> Unit,
    onLinkLongPress: (String, String, String?) -> Unit,
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
