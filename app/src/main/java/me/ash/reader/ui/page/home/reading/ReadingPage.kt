package me.ash.reader.ui.page.home.reading

import android.view.View
import android.webkit.WebChromeClient
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
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
import androidx.compose.runtime.withFrameNanos
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
                                        val (id, index) = readerState.nextArticle
                                        onLoadArticle(id, index)
                                    }
                                } else null,
                            onLoadPrevious =
                                if (isPullToSwitchArticleEnabled && isPreviousArticleAvailable) {
                                    {
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
                            ArticlePager(
                                modifier = Modifier.fillMaxSize(),
                                enabled =
                                    isSwipeToSwitchArticleEnabled &&
                                        !showFullScreenImageViewer &&
                                        !showLinkActionDialog &&
                                        !isVideoFullscreen,
                                currentPage = readerState.toArticlePageContent(),
                                previousPage = readerState.previousPage?.toArticlePageContent(),
                                nextPage = readerState.nextPage?.toArticlePageContent(),
                                currentScrollState = scrollState,
                                currentContentModifier = currentContentModifier,
                                contentPadding = paddings,
                                scrollToTopRequest = scrollToTopRequest,
                                onLoadPrevious = {
                                    readerState.previousArticle?.let { (id, index) ->
                                        onLoadArticle(id, index)
                                    }
                                },
                                onLoadNext = {
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
    var pendingSettleTarget by remember { mutableStateOf<ArticleSwipeSettleTarget?>(null) }
    val currentOnLoadPrevious by rememberUpdatedState(onLoadPrevious)
    val currentOnLoadNext by rememberUpdatedState(onLoadNext)
    val positivePage = if (layoutDirection == LayoutDirection.Ltr) previousPage else nextPage
    val negativePage = if (layoutDirection == LayoutDirection.Ltr) nextPage else previousPage
    val canMovePositive = positivePage != null
    val canMoveNegative = negativePage != null
    val animatedOffsetPx by
        animateFloatAsState(
            targetValue = targetOffsetPx,
            animationSpec = if (isDraggingPager || shouldSnapOffset) snap() else spring(),
            label = "ArticlePagerOffset",
            finishedListener = {
                if (shouldSnapOffset) {
                    shouldSnapOffset = false
                }
                when (pendingSettleTarget) {
                    ArticleSwipeSettleTarget.Positive -> {
                        pendingSettleTarget = null
                        if (layoutDirection == LayoutDirection.Ltr) {
                            currentOnLoadPrevious()
                        } else {
                            currentOnLoadNext()
                        }
                    }

                    ArticleSwipeSettleTarget.Negative -> {
                        pendingSettleTarget = null
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
        pendingSettleTarget = null
        isDraggingPager = false
        shouldSnapOffset = true
        targetOffsetPx = 0f
        withFrameNanos { }
        shouldSnapOffset = false
    }

    Box(
        modifier =
            modifier
                .clipToBounds()
                .pagerDrag(
                    enabled = enabled,
                    offset = targetOffsetPx,
                    onOffsetChange = { targetOffsetPx = it },
                    canMovePositive = canMovePositive,
                    canMoveNegative = canMoveNegative,
                    layoutDirection = layoutDirection,
                    onDragStateChange = { isDraggingPager = it },
                    onSettle = { target, settleOffset ->
                        pendingSettleTarget = target
                        targetOffsetPx = settleOffset
                    },
                )
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val pageWidthPx = with(LocalDensity.current) { maxWidth.toPx() }
            positivePage?.let { page ->
                ArticlePageSurface(
                    page = page,
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .offset {
                                IntOffset(
                                    x = (-pageWidthPx + animatedOffsetPx).roundToInt(),
                                    y = 0,
                                )
                            },
                    contentPadding = contentPadding,
                )
            }
            negativePage?.let { page ->
                ArticlePageSurface(
                    page = page,
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .offset {
                                IntOffset(
                                    x = (pageWidthPx + animatedOffsetPx).roundToInt(),
                                    y = 0,
                                )
                            },
                    contentPadding = contentPadding,
                )
            }
            ArticlePageSurface(
                page = currentPage,
                modifier =
                    currentContentModifier
                        .fillMaxSize()
                        .offset {
                            IntOffset(
                                x = animatedOffsetPx.roundToInt(),
                                y = 0,
                            )
                        },
                contentPadding = contentPadding,
                scrollState = currentScrollState,
                scrollToTopRequest = scrollToTopRequest,
                onHeadlineMeasured = onCurrentHeadlineMeasured,
                onImageClick = onCurrentImageClick,
                onLinkLongPress = onCurrentLinkLongPress,
                onShowCustomView = onCurrentShowCustomView,
                onHideCustomView = onCurrentHideCustomView,
                onScrollSnapshotChange = onCurrentScrollSnapshotChange,
            )
        }
    }
}

@Composable
private fun Modifier.pagerDrag(
    enabled: Boolean,
    offset: Float,
    onOffsetChange: (Float) -> Unit,
    canMovePositive: Boolean,
    canMoveNegative: Boolean,
    layoutDirection: LayoutDirection,
    onDragStateChange: (Boolean) -> Unit,
    onSettle: (ArticleSwipeSettleTarget?, Float) -> Unit,
): Modifier {
    if (!enabled) return this

    val currentOnOffsetChange by rememberUpdatedState(onOffsetChange)
    val currentOnDragStateChange by rememberUpdatedState(onDragStateChange)
    val currentOnSettle by rememberUpdatedState(onSettle)

    return pointerInput(enabled, canMovePositive, canMoveNegative, layoutDirection) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            var pageWidth = size.width.toFloat()
            var dragOffset = offset
            val drag =
                awaitHorizontalTouchSlopOrCancellation(down.id) { change, overSlop ->
                    pageWidth = size.width.toFloat()
                    currentOnDragStateChange(true)
                    dragOffset =
                        constrainedPagerOffset(
                            offset = dragOffset + overSlop,
                            pageWidth = pageWidth,
                            canMovePositive = canMovePositive,
                            canMoveNegative = canMoveNegative,
                        )
                    currentOnOffsetChange(dragOffset)
                    change.consume()
                } ?: return@awaitEachGesture

            horizontalDrag(drag.id) { change ->
                dragOffset =
                    constrainedPagerOffset(
                        offset = dragOffset + change.positionChange().x,
                        pageWidth = pageWidth,
                        canMovePositive = canMovePositive,
                        canMoveNegative = canMoveNegative,
                    )
                currentOnOffsetChange(dragOffset)
                change.consume()
            }

            currentOnDragStateChange(false)
            when (settleArticleSwipeTarget(dragOffset, pageWidth)) {
                ArticleSwipeSettleTarget.Positive ->
                    if (canMovePositive) {
                        currentOnSettle(ArticleSwipeSettleTarget.Positive, pageWidth)
                    } else {
                        currentOnSettle(null, 0f)
                    }

                ArticleSwipeSettleTarget.Negative ->
                    if (canMoveNegative) {
                        currentOnSettle(ArticleSwipeSettleTarget.Negative, -pageWidth)
                    } else {
                        currentOnSettle(null, 0f)
                    }

                null -> currentOnSettle(null, 0f)
            }
        }
    }
}

private fun constrainedPagerOffset(
    offset: Float,
    pageWidth: Float,
    canMovePositive: Boolean,
    canMoveNegative: Boolean,
): Float {
    val min = if (canMoveNegative) -pageWidth else 0f
    val max = if (canMovePositive) pageWidth else 0f
    return offset.coerceIn(min, max)
}

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
