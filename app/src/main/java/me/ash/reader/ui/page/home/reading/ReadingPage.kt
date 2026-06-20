package me.ash.reader.ui.page.home.reading

import android.view.View
import android.webkit.WebChromeClient
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import timber.log.Timber
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import me.ash.reader.R
import me.ash.reader.ui.component.webview.LinkActionDialog
import me.ash.reader.ui.component.webview.LinkActionData
import me.ash.reader.ui.component.webview.WebViewScrollSnapshot
import me.ash.reader.infrastructure.android.TextToSpeechManager
import me.ash.reader.infrastructure.preference.LocalPullToSwitchArticle
import me.ash.reader.infrastructure.preference.LocalReadingAutoHideToolbar
import me.ash.reader.infrastructure.preference.LocalReadingTextLineHeight
import me.ash.reader.infrastructure.preference.LocalSwipeToSwitchArticle
import me.ash.reader.ui.ext.collectAsStateValue
import me.ash.reader.ui.ext.showToast
import me.ash.reader.ui.page.adaptive.ArticleListReaderViewModel
import me.ash.reader.ui.page.adaptive.NavigationAction
import me.ash.reader.ui.page.adaptive.ReaderPage
import me.ash.reader.ui.page.adaptive.ReaderState
import me.ash.reader.ui.page.home.reading.tts.TtsButton

private const val UPWARD = 1
private const val DOWNWARD = -1

private data class ToolbarScrollSample(
    val position: Int,
    val maxScroll: Int,
    val webViewSnapshot: WebViewScrollSnapshot,
    val scrollable: Boolean,
)

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterialApi::class)
@Composable
fun ReadingPage(
    //    navController: NavHostController,
    viewModel: ArticleListReaderViewModel,
    navigationAction: NavigationAction,
    onLoadArticle: (String, Int) -> Unit,
    onNavAction: (NavigationAction) -> Unit,
    onNavigateToStylePage: () -> Unit,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val isPullToSwitchArticleEnabled = LocalPullToSwitchArticle.current.value
    val isSwipeToSwitchArticleEnabled = LocalSwipeToSwitchArticle.current.value
    val readingUiState = viewModel.readingUiState.collectAsStateValue()
    val readerState = viewModel.readerStateStateFlow.collectAsStateValue()
    val contentStateKey =
        when (readerState.content) {
            is ReaderState.Description -> "description"
            is ReaderState.FullContent -> "full_content"
            is ReaderState.Error -> "error"
            ReaderState.Loading -> "loading"
        }
    val scrollState =
        rememberSaveable(readerState.articleId, contentStateKey, saver = ScrollState.Saver) {
            ScrollState(0)
        }

    var isReaderScrollingDown by remember { mutableStateOf(false) }
    var showFullScreenImageViewer by remember { mutableStateOf(false) }
    var webViewScrollSnapshot by remember(contentStateKey, readerState.articleId) {
        mutableStateOf(
            WebViewScrollSnapshot(
                scrollY = 0,
                maxScrollY = 0,
                isAtTop = true,
                isAtBottom = true,
            )
        )
    }
    var headlineHeightPx by remember(contentStateKey, readerState.articleId) { mutableStateOf(0) }
    var scrollToTopRequest by remember(contentStateKey, readerState.articleId) { mutableStateOf(0) }
    
    // Track scroll position for toolbar visibility
    var isScrollable by remember { mutableStateOf(false) }
    var isAtTop by remember { mutableStateOf(true) }
    var isAtBottom by remember { mutableStateOf(true) }

    var currentImageData by remember { mutableStateOf(ImageData()) }

    // Video fullscreen state
    var fullscreenVideoView by remember { mutableStateOf<View?>(null) }
    var fullscreenVideoCallback by remember { mutableStateOf<WebChromeClient.CustomViewCallback?>(null) }
    val isVideoFullscreen = fullscreenVideoView != null

    // Link action dialog state
    var showLinkActionDialog by remember { mutableStateOf(false) }
    var linkActionData by remember { mutableStateOf<LinkActionData?>(null) }

    // Handle back press when video is fullscreen
    BackHandler(enabled = isVideoFullscreen) {
        fullscreenVideoCallback?.onCustomViewHidden()
    }

    val isAutoHideEnabled = LocalReadingAutoHideToolbar.current.value
    val isShowToolBar = readerState.articleId != null && ReaderToolbarState.shouldShowToolbar(
        isAutoHideEnabled = isAutoHideEnabled,
        isScrollable = isScrollable,
        isAtTop = isAtTop,
        isAtBottom = isAtBottom,
        isScrollingDown = isReaderScrollingDown
    )

    var showTopDivider by remember { mutableStateOf(false) }
    var animateNextReaderArticleChange by remember { mutableStateOf(false) }

    LaunchedEffect(readerState.articleId, animateNextReaderArticleChange) {
        if (animateNextReaderArticleChange) {
            delay(350L)
            animateNextReaderArticleChange = false
        }
    }

    //    LaunchedEffect(readerState.listIndex) {
    //        readerState.listIndex?.let {
    //            navController.previousBackStackEntry?.savedStateHandle?.set("articleIndex", it)
    //        }
    //    }

    var bringToTop by remember { mutableStateOf(false) }
    val collapsedHeaderOffsetPx = with(density) { 64.dp.toPx() }.toInt() + headlineHeightPx

    LinkActionDialog(
        visible = showLinkActionDialog,
        linkData = linkActionData,
        onDismissRequest = { showLinkActionDialog = false },
    )

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.surface,
            content = { paddings ->
                Box(modifier = Modifier.fillMaxSize()) {
                if (readerState.articleId != null) {
                    TopBar(
                        isShow = isShowToolBar,
                        isScrolled = showTopDivider,
                        title = readerState.title,
                        link = readerState.link,
                        onClick = { bringToTop = true },
                        navigationAction = navigationAction,
                        onNavButtonClick = onNavAction,
                        onNavigateToStylePage = onNavigateToStylePage,
                    )
                }

                val isNextArticleAvailable = readerState.nextArticle != null
                val isPreviousArticleAvailable = readerState.previousArticle != null

                if (readerState.articleId != null) {
                    val state =
                        rememberPullToLoadState(
                            key = readerState.content,
                            onLoadNext =
                                if (isPullToSwitchArticleEnabled && isNextArticleAvailable) {
                                    {
                                        animateNextReaderArticleChange = true
                                        val (id, index) = readerState.nextArticle
                                        onLoadArticle(id, index)
                                    }
                                } else null,
                            onLoadPrevious =
                                if (isPullToSwitchArticleEnabled && isPreviousArticleAvailable) {
                                    {
                                        animateNextReaderArticleChange = true
                                        val (id, index) = readerState.previousArticle
                                        onLoadArticle(id, index)
                                    }
                                } else null,
                        )

                    val scope = rememberCoroutineScope()

                    LaunchedEffect(bringToTop) {
                        if (bringToTop) {
                            scrollToTopRequest += 1
                            scope
                                .launch {
                                    scrollState.animateScrollTo(0)
                                }
                                .invokeOnCompletion { bringToTop = false }
                        }
                    }

                    showTopDivider =
                        snapshotFlow {
                                scrollState.value >= 120 || !webViewScrollSnapshot.isAtTop
                            }
                            .collectAsStateValue(initial = false)

                    LaunchedEffect(scrollState, webViewScrollSnapshot, collapsedHeaderOffsetPx) {
                        snapshotFlow { scrollState.value }
                            .collect { position ->
                                if (!webViewScrollSnapshot.isAtTop &&
                                    collapsedHeaderOffsetPx > 0 &&
                                    position < collapsedHeaderOffsetPx
                                ) {
                                    scrollState.scrollTo(collapsedHeaderOffsetPx)
                                }
                            }
                    }

                    // Track scroll position for toolbar visibility
                    LaunchedEffect(scrollState, collapsedHeaderOffsetPx) {
                        var lastPosition =
                            scrollState.value.coerceAtMost(collapsedHeaderOffsetPx) +
                                webViewScrollSnapshot.scrollY
                        snapshotFlow {
                            ToolbarScrollSample(
                                position = scrollState.value,
                                maxScroll = scrollState.maxValue,
                                webViewSnapshot = webViewScrollSnapshot,
                                scrollable =
                                    scrollState.maxValue > 0 ||
                                        webViewScrollSnapshot.maxScrollY > 0
                            )
                        }.distinctUntilChanged().collect { sample ->
                            isScrollable = sample.scrollable
                            isAtTop =
                                ReaderToolbarState.isAtTop(sample.position) &&
                                    sample.webViewSnapshot.isAtTop
                            isAtBottom =
                                ReaderToolbarState.isAtBottom(
                                    sample.position,
                                    sample.maxScroll,
                                ) && sample.webViewSnapshot.isAtBottom
                            val combinedPosition =
                                sample.position.coerceAtMost(collapsedHeaderOffsetPx) +
                                    sample.webViewSnapshot.scrollY
                            val delta = combinedPosition - lastPosition
                            if (abs(delta) > 2) {
                                isReaderScrollingDown = delta > 0
                            }
                            lastPosition = combinedPosition
                        }
                    }

                    CompositionLocalProvider(
                        LocalTextStyle provides
                            LocalTextStyle.current.run {
                                merge(
                                    lineHeight =
                                        if (lineHeight.isSpecified)
                                            (lineHeight.value * LocalReadingTextLineHeight.current)
                                                .sp
                                        else TextUnit.Unspecified
                                )
                            }
                    ) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            val currentContentModifier =
                                if (isPullToSwitchArticleEnabled) {
                                    Modifier.pullToLoad(state = state)
                                } else {
                                    Modifier
                                }
                            ReaderArticleTransition(
                                enabled = animateNextReaderArticleChange,
                                readerState = readerState,
                            ) { transitionReaderState ->
                                ArticlePager(
                                    modifier = Modifier.fillMaxSize(),
                                    enabled =
                                        isSwipeToSwitchArticleEnabled &&
                                            !animateNextReaderArticleChange &&
                                            !showFullScreenImageViewer &&
                                            !showLinkActionDialog &&
                                            !isVideoFullscreen,
                                    currentPage = transitionReaderState.toArticlePageContent(),
                                    previousPage =
                                        transitionReaderState.previousPage?.toArticlePageContent(),
                                    nextPage =
                                        transitionReaderState.nextPage?.toArticlePageContent(),
                                    currentScrollState = scrollState,
                                    currentContentModifier = currentContentModifier,
                                    contentPadding = paddings,
                                    scrollToTopRequest = scrollToTopRequest,
                                    onLoadPrevious = {
                                        animateNextReaderArticleChange = false
                                        readerState.previousArticle?.let { (id, index) ->
                                            onLoadArticle(id, index)
                                        }
                                    },
                                    onLoadNext = {
                                        animateNextReaderArticleChange = false
                                        readerState.nextArticle?.let { (id, index) ->
                                            onLoadArticle(id, index)
                                        }
                                    },
                                    onCurrentHeadlineMeasured = { headlineHeightPx = it },
                                    onCurrentImageClick = { imgUrl, altText ->
                                        currentImageData = ImageData(imgUrl, altText)
                                        showFullScreenImageViewer = true
                                    },
                                    onCurrentScrollSnapshotChange = {
                                        webViewScrollSnapshot = it
                                    },
                                    onCurrentLinkLongPress = { url, text ->
                                        linkActionData = LinkActionData(
                                            url = url,
                                            linkText = text.ifEmpty { null },
                                        )
                                        showLinkActionDialog = true
                                    },
                                    onCurrentShowCustomView = { view, callback ->
                                        Timber.tag("ReadingPage").d(
                                            "onShowCustomView lambda called with view=$view"
                                        )
                                        fullscreenVideoView = view
                                        fullscreenVideoCallback = callback
                                    },
                                    onCurrentHideCustomView = {
                                        fullscreenVideoView = null
                                        fullscreenVideoCallback = null
                                    },
                                )
                            }
                            if (isPullToSwitchArticleEnabled) {
                                PullToLoadIndicator(
                                    state = state,
                                    canLoadPrevious = isPreviousArticleAvailable,
                                    canLoadNext = isNextArticleAvailable,
                                )
                            }
                        }
                    }
                }
                // Bottom Bar
                if (readerState.articleId != null) {
                    BottomBar(
                        isShow = isShowToolBar,
                        isRead = readingUiState.isRead,
                        isStarred = readingUiState.isStarred,
                        isNextArticleAvailable = isNextArticleAvailable,
                        isFullContent =
                            readerState.content is ReaderState.FullContent ||
                                readerState.content is ReaderState.Error,
                        onRead = { viewModel.updateReadStatus(it) },
                        onStarred = { viewModel.updateStarredStatus(it) },
                        onNextArticle = {
                            readerState.nextArticle?.let {
                                animateNextReaderArticleChange = true
                                val (id, index) = it
                                onLoadArticle(id, index)
                            }
                        },
                        onFullContent = {
                            if (it) viewModel.renderFullContent()
                            else viewModel.renderDescriptionContent()
                        },
                        ttsButton = {
                            TtsButton(
                                onClick = {
                                    when (it) {
                                        TextToSpeechManager.State.Error -> {
                                            context.showToast("TextToSpeech initialization failed")
                                        }

                                        TextToSpeechManager.State.Idle -> {
                                            viewModel.textToSpeechManager.readHtml(
                                                readerState.content.text ?: ""
                                            )
                                        }

                                        is TextToSpeechManager.State.Reading -> {
                                            viewModel.textToSpeechManager.stop()
                                        }

                                        TextToSpeechManager.State.Preparing -> {
                                            /* no-op */
                                        }
                                    }
                                },
                                state =
                                    viewModel.textToSpeechManager.stateFlow.collectAsStateValue(),
                            )
                        },
                    )
                }
                }
            },
        )

        if (showFullScreenImageViewer) {
            ReaderImageViewer(
                imageData = currentImageData,
                onDownloadImage = {
                    viewModel.downloadImage(
                        it,
                        onSuccess = { context.showToast(context.getString(R.string.image_saved)) },
                        onFailure = {
                            // FIXME: crash the app for error report
                            th ->
                            throw th
                        },
                    )
                },
                onDismissRequest = { showFullScreenImageViewer = false },
            )
        }

        // Fullscreen video overlay
        Timber.tag("ReadingPage").d("Checking fullscreen: isVideoFullscreen=$isVideoFullscreen, view=${fullscreenVideoView}")
        if (isVideoFullscreen) {
            Timber.tag("ReadingPage").d("Rendering fullscreen overlay NOW")
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black),
                contentAlignment = Alignment.Center,
            ) {
                fullscreenVideoView?.let { view ->
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { ctx ->
                            FrameLayout(ctx).apply {
                                addView(
                                    view,
                                    FrameLayout.LayoutParams(
                                        FrameLayout.LayoutParams.MATCH_PARENT,
                                        FrameLayout.LayoutParams.MATCH_PARENT
                                    )
                                )
                            }
                        },
                        update = { },
                        onRelease = { container ->
                            (container as? FrameLayout)?.removeAllViews()
                        }
                    )
                }
            }
        }
    }
}

private data class ArticlePageContent(
    val articleId: String,
    val feedName: String,
    val title: String,
    val author: String?,
    val link: String?,
    val publishedDate: java.util.Date,
    val content: ReaderState.ContentState,
)

internal enum class ArticleSwipeSettleTarget {
    Positive,
    Negative,
}

private data class ArticlePagerSlot(
    val page: ArticlePageContent?,
    val position: Int,
)

internal fun settleArticleSwipeTarget(
    offsetPx: Float,
    pageWidthPx: Float,
    thresholdFraction: Float = 0.25f,
): ArticleSwipeSettleTarget? {
    if (pageWidthPx <= 0f) return null
    val threshold = pageWidthPx * thresholdFraction
    return when {
        offsetPx >= threshold -> ArticleSwipeSettleTarget.Positive
        offsetPx <= -threshold -> ArticleSwipeSettleTarget.Negative
        else -> null
    }
}

private fun ReaderState.toArticlePageContent(): ArticlePageContent =
    ArticlePageContent(
        articleId = articleId.orEmpty(),
        feedName = feedName,
        title = title.orEmpty(),
        author = author,
        link = link,
        publishedDate = publishedDate,
        content = content,
    )

private fun ReaderPage.toArticlePageContent(): ArticlePageContent =
    ArticlePageContent(
        articleId = articleId,
        feedName = feedName,
        title = title.orEmpty(),
        author = author,
        link = link,
        publishedDate = publishedDate,
        content = content,
    )

@Composable
private fun ReaderArticleTransition(
    enabled: Boolean,
    readerState: ReaderState,
    content: @Composable (ReaderState) -> Unit,
) {
    if (!enabled) {
        content(readerState)
        return
    }

    AnimatedContent(
        targetState = readerState,
        transitionSpec = {
            val direction =
                when {
                    initialState.nextArticle?.articleId == targetState.articleId -> UPWARD
                    initialState.previousArticle?.articleId == targetState.articleId -> DOWNWARD
                    initialState.articleId == targetState.articleId -> {
                        when (targetState.content) {
                            is ReaderState.Description -> DOWNWARD
                            else -> UPWARD
                        }
                    }

                    else -> UPWARD
                }
            val exit = 100
            val enter = exit * 2
            (slideInVertically(
                initialOffsetY = { (it * 0.2f * direction).toInt() },
                animationSpec =
                    spring(
                        dampingRatio = .9f,
                        stiffness = Spring.StiffnessLow,
                        visibilityThreshold = IntOffset.VisibilityThreshold,
                    ),
            ) +
                fadeIn(
                    tween(
                        delayMillis = exit,
                        durationMillis = enter,
                        easing = LinearOutSlowInEasing,
                    )
                )) togetherWith
                (slideOutVertically(
                    targetOffsetY = { (it * -0.2f * direction).toInt() },
                    animationSpec =
                        spring(
                            dampingRatio = Spring.DampingRatioNoBouncy,
                            stiffness = Spring.StiffnessLow,
                            visibilityThreshold = IntOffset.VisibilityThreshold,
                        ),
                ) +
                    fadeOut(tween(durationMillis = exit, easing = FastOutLinearInEasing)))
        },
        label = "ReaderArticleTransition",
    ) { transitionReaderState ->
        content(transitionReaderState)
    }
}

@Composable
private fun ArticlePager(
    modifier: Modifier = Modifier,
    enabled: Boolean,
    currentPage: ArticlePageContent,
    previousPage: ArticlePageContent?,
    nextPage: ArticlePageContent?,
    currentScrollState: ScrollState,
    currentContentModifier: Modifier,
    contentPadding: androidx.compose.foundation.layout.PaddingValues,
    scrollToTopRequest: Int,
    onLoadPrevious: () -> Unit,
    onLoadNext: () -> Unit,
    onCurrentHeadlineMeasured: (Int) -> Unit,
    onCurrentImageClick: (imgUrl: String, altText: String) -> Unit,
    onCurrentLinkLongPress: (url: String, text: String) -> Unit,
    onCurrentShowCustomView: (View, WebChromeClient.CustomViewCallback) -> Unit,
    onCurrentHideCustomView: () -> Unit,
    onCurrentScrollSnapshotChange: (WebViewScrollSnapshot) -> Unit,
) {
    val layoutDirection = LocalLayoutDirection.current
    var targetOffsetPx by remember { mutableFloatStateOf(0f) }
    var isDraggingPager by remember { mutableStateOf(false) }
    var shouldSnapOffset by remember { mutableStateOf(false) }
    var slots by remember {
        mutableStateOf(
            initialArticlePagerSlots(
                currentPage = currentPage,
                previousPage = previousPage,
                nextPage = nextPage,
                layoutDirection = layoutDirection,
            )
        )
    }
    var pendingCommitTarget by remember { mutableStateOf<ArticleSwipeSettleTarget?>(null) }
    val currentOnLoadPrevious by rememberUpdatedState(onLoadPrevious)
    val currentOnLoadNext by rememberUpdatedState(onLoadNext)
    val animatedOffsetPx by
        animateFloatAsState(
            targetValue = targetOffsetPx,
            animationSpec = if (isDraggingPager || shouldSnapOffset) snap() else spring(),
            label = "ArticlePagerOffset",
            finishedListener = {
                if (shouldSnapOffset) {
                    shouldSnapOffset = false
                }
                when (pendingCommitTarget) {
                    ArticleSwipeSettleTarget.Positive -> {
                        pendingCommitTarget = null
                        if (layoutDirection == LayoutDirection.Ltr) {
                            currentOnLoadPrevious()
                        } else {
                            currentOnLoadNext()
                        }
                    }

                    ArticleSwipeSettleTarget.Negative -> {
                        pendingCommitTarget = null
                        if (layoutDirection == LayoutDirection.Ltr) {
                            currentOnLoadNext()
                        } else {
                            currentOnLoadPrevious()
                        }
                    }

                    null -> Unit
                }
            },
        )

    LaunchedEffect(currentPage.articleId) {
        slots =
            reconcileArticlePagerSlots(
                slots = slots,
                currentPage = currentPage,
                previousPage = previousPage,
                nextPage = nextPage,
                layoutDirection = layoutDirection,
            )
        pendingCommitTarget = null
        isDraggingPager = false
        shouldSnapOffset = true
        targetOffsetPx = 0f
    }

    LaunchedEffect(previousPage?.articleId, nextPage?.articleId, currentPage.articleId) {
        if (!isDraggingPager && pendingCommitTarget == null && targetOffsetPx == 0f) {
            slots =
                reconcileArticlePagerSlots(
                    slots = slots,
                    currentPage = currentPage,
                    previousPage = previousPage,
                    nextPage = nextPage,
                    layoutDirection = layoutDirection,
                )
        }
    }

    Box(
        modifier =
            modifier
                .clipToBounds()
                .pagerDrag(
                    enabled = enabled && pendingCommitTarget == null,
                    offset = targetOffsetPx,
                    canMovePositive = slots.any { it.position == -1 && it.page != null },
                    canMoveNegative = slots.any { it.position == 1 && it.page != null },
                    layoutDirection = layoutDirection,
                    onDragStart = { initialOffset ->
                        isDraggingPager = true
                        targetOffsetPx = initialOffset
                    },
                    onDrag = { targetOffsetPx = it },
                    onCancel = {
                        isDraggingPager = false
                        targetOffsetPx = 0f
                    },
                    onSettle = { target, settleOffset ->
                        pendingCommitTarget = target
                        isDraggingPager = false
                        targetOffsetPx = settleOffset
                    },
                    onSettleBack = {
                        isDraggingPager = false
                        targetOffsetPx = 0f
                    },
                )
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val pageWidthPx = with(LocalDensity.current) { maxWidth.toPx() }
            slots.forEach { slot ->
                val page = slot.page ?: return@forEach
                val isCurrentPage = page.articleId == currentPage.articleId
                val slotModifier =
                    (if (isCurrentPage) currentContentModifier else Modifier)
                        .fillMaxSize()
                        .offset {
                            IntOffset(
                                x = (slot.position * pageWidthPx + animatedOffsetPx).roundToInt(),
                                y = 0,
                            )
                        }
                ArticlePageSurface(
                    page = page,
                    modifier = slotModifier,
                    contentPadding = contentPadding,
                    scrollState =
                        if (isCurrentPage) currentScrollState
                        else
                            rememberSaveable(
                                page.articleId,
                                page.content.contentStateKey(),
                                saver = ScrollState.Saver,
                            ) {
                                ScrollState(0)
                            },
                    scrollToTopRequest = if (isCurrentPage) scrollToTopRequest else 0,
                    onHeadlineMeasured = if (isCurrentPage) onCurrentHeadlineMeasured else null,
                    onImageClick = if (isCurrentPage) onCurrentImageClick else null,
                    onLinkLongPress = if (isCurrentPage) onCurrentLinkLongPress else null,
                    onShowCustomView = if (isCurrentPage) onCurrentShowCustomView else null,
                    onHideCustomView = if (isCurrentPage) onCurrentHideCustomView else null,
                    onScrollSnapshotChange =
                        if (isCurrentPage) onCurrentScrollSnapshotChange else null,
                )
            }
        }
    }
}

@Composable
private fun Modifier.pagerDrag(
    enabled: Boolean,
    offset: Float,
    canMovePositive: Boolean,
    canMoveNegative: Boolean,
    layoutDirection: LayoutDirection,
    onDragStart: (Float) -> Unit,
    onDrag: (Float) -> Unit,
    onCancel: () -> Unit,
    onSettle: (ArticleSwipeSettleTarget, Float) -> Unit,
    onSettleBack: () -> Unit,
): Modifier {
    if (!enabled) return this

    val currentOnDragStart by rememberUpdatedState(onDragStart)
    val currentOnDrag by rememberUpdatedState(onDrag)
    val currentOnCancel by rememberUpdatedState(onCancel)
    val currentOnSettle by rememberUpdatedState(onSettle)
    val currentOnSettleBack by rememberUpdatedState(onSettleBack)

    return pointerInput(enabled, canMovePositive, canMoveNegative, layoutDirection) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            var pageWidth = size.width.toFloat()
            var dragOffset = offset
            var activeTarget: ArticleSwipeSettleTarget? = null
            val drag =
                awaitHorizontalTouchSlopOrCancellation(down.id) { change, overSlop ->
                    pageWidth = size.width.toFloat()
                    val target =
                        if (overSlop >= 0f) {
                            ArticleSwipeSettleTarget.Positive
                        } else {
                            ArticleSwipeSettleTarget.Negative
                        }
                    val canMove =
                        when (target) {
                            ArticleSwipeSettleTarget.Positive -> canMovePositive
                            ArticleSwipeSettleTarget.Negative -> canMoveNegative
                        }
                    if (!canMove) {
                        activeTarget = null
                        dragOffset = 0f
                    } else {
                        activeTarget = target
                        dragOffset =
                            constrainedPagerOffset(
                                offset = overSlop,
                                pageWidth = pageWidth,
                                target = target,
                            )
                        currentOnDragStart(dragOffset)
                    }
                    change.consume()
                } ?: return@awaitEachGesture

            horizontalDrag(drag.id) { change ->
                activeTarget?.let { target ->
                    dragOffset =
                        constrainedPagerOffset(
                            offset = dragOffset + change.positionChange().x,
                            pageWidth = pageWidth,
                            target = target,
                        )
                    currentOnDrag(dragOffset)
                }
                change.consume()
            }

            val settledTarget = activeTarget
            if (settledTarget == null) {
                currentOnCancel()
                return@awaitEachGesture
            }
            if (settleArticleSwipeTarget(dragOffset, pageWidth) == settledTarget) {
                currentOnSettle(settledTarget, settledOffset(settledTarget, pageWidth))
            } else {
                currentOnSettleBack()
            }
        }
    }
}

private fun constrainedPagerOffset(
    offset: Float,
    pageWidth: Float,
    target: ArticleSwipeSettleTarget,
): Float {
    val min = if (target == ArticleSwipeSettleTarget.Negative) -pageWidth else 0f
    val max = if (target == ArticleSwipeSettleTarget.Positive) pageWidth else 0f
    return offset.coerceIn(min, max)
}

private fun settledOffset(target: ArticleSwipeSettleTarget, pageWidthPx: Float): Float =
    when (target) {
        ArticleSwipeSettleTarget.Positive -> pageWidthPx
        ArticleSwipeSettleTarget.Negative -> -pageWidthPx
    }

private fun initialArticlePagerSlots(
    currentPage: ArticlePageContent,
    previousPage: ArticlePageContent?,
    nextPage: ArticlePageContent?,
    layoutDirection: LayoutDirection,
): List<ArticlePagerSlot> =
    listOf(
        ArticlePagerSlot(
            page = previousPage,
            position = previousPagePosition(layoutDirection),
        ),
        ArticlePagerSlot(page = currentPage, position = 0),
        ArticlePagerSlot(
            page = nextPage,
            position = nextPagePosition(layoutDirection),
        ),
    )

private fun reconcileArticlePagerSlots(
    slots: List<ArticlePagerSlot>,
    currentPage: ArticlePageContent,
    previousPage: ArticlePageContent?,
    nextPage: ArticlePageContent?,
    layoutDirection: LayoutDirection,
): List<ArticlePagerSlot> {
    if (slots.isEmpty()) {
        return initialArticlePagerSlots(currentPage, previousPage, nextPage, layoutDirection)
    }
    val previousPosition = previousPagePosition(layoutDirection)
    val nextPosition = nextPagePosition(layoutDirection)
    val assignments =
        mapOf(
            0 to currentPage,
            previousPosition to previousPage,
            nextPosition to nextPage,
        )
    val usedPositions = mutableSetOf<Int>()
    val matchedSlotIndexes = mutableSetOf<Int>()
    val reconciled =
        slots.mapIndexed { index, slot ->
            val matchingPosition =
                assignments.entries.firstOrNull { (_, page) ->
                    page != null && page.articleId == slot.page?.articleId
                }?.key
            if (matchingPosition != null && matchingPosition !in usedPositions) {
                usedPositions += matchingPosition
                matchedSlotIndexes += index
                slot.copy(page = assignments[matchingPosition], position = matchingPosition)
            } else {
                slot
            }
        }
    return reconciled.mapIndexed { index, slot ->
        if (index in matchedSlotIndexes) return@mapIndexed slot
        val availablePosition =
            listOf(previousPosition, 0, nextPosition).firstOrNull { it !in usedPositions }
                ?: slot.position
        usedPositions += availablePosition
        slot.copy(page = assignments[availablePosition], position = availablePosition)
    }
}

private fun previousPagePosition(layoutDirection: LayoutDirection): Int =
    if (layoutDirection == LayoutDirection.Ltr) -1 else 1

private fun nextPagePosition(layoutDirection: LayoutDirection): Int =
    if (layoutDirection == LayoutDirection.Ltr) 1 else -1

@Composable
private fun ArticlePageSurface(
    page: ArticlePageContent,
    modifier: Modifier = Modifier,
    contentPadding: androidx.compose.foundation.layout.PaddingValues,
    scrollState: ScrollState =
        rememberSaveable(page.articleId, page.content.contentStateKey(), saver = ScrollState.Saver) {
            ScrollState(0)
        },
    scrollToTopRequest: Int = 0,
    onHeadlineMeasured: ((Int) -> Unit)? = null,
    onImageClick: ((imgUrl: String, altText: String) -> Unit)? = null,
    onLinkLongPress: ((url: String, text: String) -> Unit)? = null,
    onShowCustomView: ((View, WebChromeClient.CustomViewCallback) -> Unit)? = null,
    onHideCustomView: (() -> Unit)? = null,
    onScrollSnapshotChange: ((WebViewScrollSnapshot) -> Unit)? = null,
) {
    Content(
        modifier = modifier,
        contentPadding = contentPadding,
        content = page.content.text ?: "",
        feedName = page.feedName,
        title = page.title,
        author = page.author,
        link = page.link,
        publishedDate = page.publishedDate,
        isLoading = page.content is ReaderState.Loading,
        scrollState = scrollState,
        scrollToTopRequest = scrollToTopRequest,
        onHeadlineMeasured = onHeadlineMeasured,
        onImageClick = onImageClick,
        onScrollSnapshotChange = onScrollSnapshotChange,
        onLinkLongPress = onLinkLongPress,
        onShowCustomView = onShowCustomView,
        onHideCustomView = onHideCustomView,
    )
}

private fun ReaderState.ContentState.contentStateKey(): String =
    when (this) {
        is ReaderState.Description -> "description"
        is ReaderState.FullContent -> "full_content"
        is ReaderState.Error -> "error"
        ReaderState.Loading -> "loading"
    }
