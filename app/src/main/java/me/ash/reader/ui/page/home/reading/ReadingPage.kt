package me.ash.reader.ui.page.home.reading

import android.view.View
import android.webkit.WebChromeClient
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import android.widget.FrameLayout
import timber.log.Timber
import kotlin.math.abs
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import me.ash.reader.R
import me.ash.reader.ui.component.webview.LinkActionDialog
import me.ash.reader.ui.component.webview.LinkActionData
import me.ash.reader.infrastructure.android.TextToSpeechManager
import me.ash.reader.infrastructure.preference.LocalPullToSwitchArticle
import me.ash.reader.infrastructure.preference.LocalReadingAutoHideToolbar
import me.ash.reader.infrastructure.preference.LocalReadingTextLineHeight
import me.ash.reader.ui.ext.collectAsStateValue
import me.ash.reader.ui.ext.showToast
import me.ash.reader.ui.page.adaptive.ArticleListReaderViewModel
import me.ash.reader.ui.page.adaptive.NavigationAction
import me.ash.reader.ui.page.adaptive.ReaderState
import me.ash.reader.ui.page.home.reading.tts.TtsButton

private const val UPWARD = 1
private const val DOWNWARD = -1

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
    val hapticFeedback = LocalHapticFeedback.current
    val isPullToSwitchArticleEnabled = LocalPullToSwitchArticle.current.value
    val readingUiState = viewModel.readingUiState.collectAsStateValue()
    val readerState = viewModel.readerStateStateFlow.collectAsStateValue()

    var isReaderScrollingDown by remember { mutableStateOf(false) }
    var showFullScreenImageViewer by remember { mutableStateOf(false) }
    
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
                    // Content
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
                                val state =
                                    rememberPullToLoadState(
                                        key = content,
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

                                val scrollState = rememberScrollState()

                                val scope = rememberCoroutineScope()

                                LaunchedEffect(bringToTop) {
                                    if (bringToTop) {
                                        scope
                                            .launch {
                                                scrollState.animateScrollTo(0)
                                            }
                                            .invokeOnCompletion { bringToTop = false }
                                    }
                                }

                                showTopDivider =
                                    snapshotFlow {
                                            scrollState.value >= 120
                                        }
                                        .collectAsStateValue(initial = false)

                                // Track scroll position for toolbar visibility
                                LaunchedEffect(scrollState) {
                                    var lastPosition = scrollState.value
                                    snapshotFlow {
                                        Triple(
                                            scrollState.value,
                                            scrollState.maxValue,
                                            scrollState.maxValue > 0
                                        )
                                    }.distinctUntilChanged().collect { (position, maxScroll, scrollable) ->
                                        isScrollable = scrollable
                                        isAtTop = ReaderToolbarState.isAtTop(position)
                                        isAtBottom = ReaderToolbarState.isAtBottom(position, maxScroll)
                                        // Track scroll direction for toolbar visibility
                                        val delta = position - lastPosition
                                        if (abs(delta) > 2) {
                                            isReaderScrollingDown = delta > 0
                                        }
                                        lastPosition = position
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
                                        val contentModifier = if (isPullToSwitchArticleEnabled) {
                                            Modifier.pullToLoad(
                                                state = state,
                                            )
                                        } else {
                                            Modifier
                                        }
                                        Content(
                                            modifier = contentModifier,
                                            contentPadding = paddings,
                                            content = content.text ?: "",
                                            feedName = feedName,
                                            title = title.toString(),
                                            author = author,
                                            link = link,
                                            publishedDate = publishedDate,
                                            isLoading = content is ReaderState.Loading,
                                            scrollState = scrollState,
                                            onImageClick = { imgUrl, altText ->
                                                currentImageData = ImageData(imgUrl, altText)
                                                showFullScreenImageViewer = true
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
