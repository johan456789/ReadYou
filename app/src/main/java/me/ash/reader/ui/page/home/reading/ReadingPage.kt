package me.ash.reader.ui.page.home.reading

import android.view.View
import android.webkit.WebChromeClient
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import android.widget.FrameLayout
import timber.log.Timber
import me.ash.reader.R
import me.ash.reader.ui.component.webview.LinkActionDialog
import me.ash.reader.ui.component.webview.LinkActionData
import me.ash.reader.ui.component.webview.WebViewScrollSnapshot
import me.ash.reader.infrastructure.android.TextToSpeechManager
import me.ash.reader.infrastructure.preference.ArticleSwitchGesturePreference
import me.ash.reader.infrastructure.preference.LocalArticleSwitchGesture
import me.ash.reader.infrastructure.preference.LocalReadingTextLineHeight
import me.ash.reader.ui.ext.collectAsStateValue
import me.ash.reader.ui.ext.showToast
import me.ash.reader.ui.page.adaptive.ArticleListReaderViewModel
import me.ash.reader.ui.page.adaptive.NavigationAction
import me.ash.reader.ui.page.adaptive.ReaderState
import me.ash.reader.ui.page.home.reading.tts.TtsButton

private const val UPWARD = 1
private const val DOWNWARD = -1

internal fun shouldShowTopDivider(isAtTop: Boolean): Boolean = !isAtTop

internal fun shouldShowTitleInTopBar(scrollY: Int, headlineHeightPx: Int): Boolean =
    headlineHeightPx > 0 && scrollY >= headlineHeightPx

@Composable
fun ReadingPage(
    viewModel: ArticleListReaderViewModel,
    navigationAction: NavigationAction,
    onLoadArticle: (String, Int) -> Unit,
    onNavAction: (NavigationAction) -> Unit,
    onNavigateToStylePage: () -> Unit,
) {
    val context = LocalContext.current
    val articleSwitchGesture = LocalArticleSwitchGesture.current
    val isSwipeToSwitchArticleEnabled =
        articleSwitchGesture is ArticleSwitchGesturePreference.HorizontalSwipe
    val readingUiState = viewModel.readingUiState.collectAsStateValue()
    val readerState = viewModel.readerStateStateFlow.collectAsStateValue()
    val contentStateKey =
        when (readerState.content) {
            is ReaderState.Description -> "description"
            is ReaderState.FullContent -> "full_content"
            is ReaderState.Error -> "error"
            ReaderState.Loading -> "loading"
        }

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

    // The WebView owns vertical scrolling; the top bar follows its native
    // position. Derive visibility during composition instead of in an effect so
    // that when the article (and its title) changes, visibility is recomputed in
    // the same recomposition and cannot briefly show the new title for the old
    // article's scroll position.
    val showTopDivider = shouldShowTopDivider(webViewScrollSnapshot.isAtTop)
    val showTitleInTopBar =
        shouldShowTitleInTopBar(webViewScrollSnapshot.scrollY, headlineHeightPx)
    LaunchedEffect(
        showTopDivider,
        showTitleInTopBar,
        webViewScrollSnapshot.scrollY,
        webViewScrollSnapshot.maxScrollY,
        headlineHeightPx,
    ) {
        Timber.tag("ReaderTopBar").d(
            "divider=%s title=%s scrollY=%d max=%d headlinePx=%d",
            showTopDivider,
            showTitleInTopBar,
            webViewScrollSnapshot.scrollY,
            webViewScrollSnapshot.maxScrollY,
            headlineHeightPx,
        )
    }

    var bringToTop by remember { mutableStateOf(false) }

    // The swipe pager computes a crossfading pair of title layers from the live
    // drag progress. The other reading path (tap / next-article animations) has
    // no drag, so it drives a single layer from the scroll visibility.
    var swipeTitleLayers by remember { mutableStateOf<List<TopBarTitleLayer>>(emptyList()) }
    val fallbackTitleAlpha by
        animateFloatAsState(
            targetValue = if (showTitleInTopBar) 1f else 0f,
            animationSpec = tween(durationMillis = 200),
            label = "fallbackTitleAlpha",
        )
    // Keep the outgoing article's title while the fallback bar fades out so a
    // newly opened article's title cannot flash for the previous scroll position.
    var fallbackTitle by remember { mutableStateOf(readerState.title) }
    LaunchedEffect(showTitleInTopBar, readerState.title) {
        if (showTitleInTopBar) fallbackTitle = readerState.title
    }
    val topBarTitleLayers =
        if (isSwipeToSwitchArticleEnabled) {
            swipeTitleLayers
        } else {
            val text = fallbackTitle
            if (fallbackTitleAlpha > 0.01f && !text.isNullOrBlank()) {
                listOf(TopBarTitleLayer(text, fallbackTitleAlpha))
            } else {
                emptyList()
            }
        }

    LinkActionDialog(
        visible = showLinkActionDialog,
        linkData = linkActionData,
        onDismissRequest = { showLinkActionDialog = false },
    )

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.surface,
            topBar = {
                if (readerState.articleId != null) {
                    TopBar(
                        isScrolled = showTopDivider,
                        titleLayers = topBarTitleLayers,
                        link = readerState.link,
                        onClick = {
                            scrollToTopRequest += 1
                            bringToTop = true
                        },
                        navigationAction = navigationAction,
                        onNavButtonClick = onNavAction,
                        onNavigateToStylePage = onNavigateToStylePage,
                    )
                }
            },
            bottomBar = {
                if (readerState.articleId != null) {
                    BottomBar(
                        isRead = readingUiState.isRead,
                        isStarred = readingUiState.isStarred,
                        isNextArticleAvailable = readerState.nextArticle != null,
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
            },
            content = { paddings ->
                if (readerState.articleId != null) {
                    // Content
                    if (isSwipeToSwitchArticleEnabled) {
                        CompositionLocalProvider(
                            LocalTextStyle provides
                                LocalTextStyle.current.run {
                                    merge(
                                        lineHeight =
                                            if (lineHeight.isSpecified)
                                                (lineHeight.value * LocalReadingTextLineHeight.current).sp
                                            else TextUnit.Unspecified
                                    )
                                }
                        ) {
                            ArticleSwipePager(
                                currentReaderState = readerState,
                                contentPadding = paddings,
                                enabled =
                                    !showFullScreenImageViewer &&
                                        !showLinkActionDialog &&
                                        !isVideoFullscreen,
                                onLoadArticle = onLoadArticle,
                                loadPreview = viewModel::previewReaderState,
                                bringToTopRequest = scrollToTopRequest,
                                onBringToTopHandled = { bringToTop = false },
                                onCurrentHeadlineMeasured = { headlineHeightPx = it },
                                onCurrentScrollSnapshotChange = {
                                    webViewScrollSnapshot = it
                                },
                                onTitleLayersChange = { swipeTitleLayers = it },
                                onImageClick = { imgUrl, altText ->
                                    currentImageData = ImageData(imgUrl, altText)
                                    showFullScreenImageViewer = true
                                },
                                onLinkLongPress = { url, text ->
                                    linkActionData =
                                        LinkActionData(
                                            url = url,
                                            linkText = text.ifEmpty { null },
                                        )
                                    showLinkActionDialog = true
                                },
                                onShowCustomView = { view, callback ->
                                    Timber.tag("ReadingPage")
                                        .d("onShowCustomView lambda called with view=$view")
                                    fullscreenVideoView = view
                                    fullscreenVideoCallback = callback
                                },
                                onHideCustomView = {
                                    fullscreenVideoView = null
                                    fullscreenVideoCallback = null
                                },
                            )
                        }
                    } else {
                        AnimatedContent(
                        targetState = readerState,
                        transitionSpec = {
                            val direction =
                                when {
                                    initialState.nextArticle?.articleId == targetState.articleId ->
                                        UPWARD
                                    initialState.previousArticle?.articleId ==
                                        targetState.articleId -> DOWNWARD
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
                                    fadeOut(
                                        tween(durationMillis = exit, easing = FastOutLinearInEasing)
                                    ))
                        },
                        label = "",
                    ) {
                        remember { it }
                            .run {
                                LaunchedEffect(bringToTop) {
                                    if (bringToTop) {
                                        bringToTop = false
                                    }
                                }

                                CompositionLocalProvider(
                                    LocalTextStyle provides
                                        LocalTextStyle.current.run {
                                            merge(
                                                lineHeight =
                                                    if (lineHeight.isSpecified)
                                                        (lineHeight.value *
                                                                LocalReadingTextLineHeight.current)
                                                            .sp
                                                    else TextUnit.Unspecified
                                            )
                                        }
                                ) {
                                    Box(
                                        modifier = Modifier.fillMaxSize(),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Content(
                                            contentPadding = paddings,
                                            content = content.text ?: "",
                                            feedName = feedName,
                                            title = title.toString(),
                                            author = author,
                                            link = link,
                                            publishedDate = publishedDate,
                                            isLoading = content is ReaderState.Loading,
                                            scrollToTopRequest = scrollToTopRequest,
                                            onHeadlineMeasured = { headlineHeightPx = it },
                                            onImageClick = { imgUrl, altText ->
                                                currentImageData = ImageData(imgUrl, altText)
                                                showFullScreenImageViewer = true
                                            },
                                            onScrollSnapshotChange = {
                                                webViewScrollSnapshot = it
                                            },
                                            onLinkLongPress = { url, text ->
                                                linkActionData = LinkActionData(
                                                    url = url,
                                                    linkText = text.ifEmpty { null },
                                                )
                                                showLinkActionDialog = true
                                            },
                                            onShowCustomView = { view, callback ->
                                                Timber.tag("ReadingPage").d("onShowCustomView lambda called with view=$view")
                                                fullscreenVideoView = view
                                                fullscreenVideoCallback = callback
                                            },
                                            onHideCustomView = {
                                                fullscreenVideoView = null
                                                fullscreenVideoCallback = null
                                            },
                                        )
                                    }
                                }
                            }
                    }
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
