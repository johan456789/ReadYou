package me.ash.reader.ui.page.home.reading

import android.view.View
import android.webkit.WebChromeClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
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
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import android.widget.FrameLayout
import androidx.core.view.HapticFeedbackConstantsCompat
import timber.log.Timber
import me.ash.reader.R
import me.ash.reader.ui.component.webview.LinkActionDialog
import me.ash.reader.ui.component.webview.LinkActionData
import me.ash.reader.ui.component.webview.WebViewScrollSnapshot
import me.ash.reader.infrastructure.android.TextToSpeechManager
import me.ash.reader.infrastructure.preference.LocalReadingTextLineHeight
import me.ash.reader.infrastructure.preference.LocalSharedContent
import me.ash.reader.ui.ext.collectAsStateValue
import me.ash.reader.ui.ext.showToast
import me.ash.reader.ui.page.adaptive.ArticleListReaderViewModel
import me.ash.reader.ui.page.adaptive.NavigationAction
import me.ash.reader.ui.page.adaptive.ReaderState
import me.ash.reader.ui.page.home.reading.tts.TtsButton

internal fun shouldShowTopDivider(isAtTop: Boolean): Boolean = !isAtTop

internal fun shouldShowTitleInTopBar(scrollY: Int, headlineHeightPx: Int): Boolean =
    headlineHeightPx > 0 && scrollY >= headlineHeightPx

@Composable
fun ReadingPage(
    viewModel: ArticleListReaderViewModel,
    navigationAction: NavigationAction,
    onLoadArticle: (String) -> Unit,
    onNavAction: (NavigationAction) -> Unit,
    onNavigateToStylePage: () -> Unit,
) {
    val context = LocalContext.current
    val sharedContent = LocalSharedContent.current
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

    // The article the user currently sees. The pager reports the incoming slot
    // from drag start (not only after settle), so taps act on the article on
    // screen. Keyed by article so reopening the reader can never serve the
    // previous article's id for a frame.
    var visibleArticleId by remember(readerState.articleId) { mutableStateOf<String?>(null) }
    val actionArticleId = visibleArticleId ?: readerState.articleId
    // Read/star toggles derive their direction from the displayed flags, which
    // track the committed article: only offer them once the committed article
    // is the targeted one (or nothing is displayed yet on first open).
    val actionsTargetDisplayed =
        readingUiState.articleWithFeed?.article?.id?.let { it == actionArticleId } ?: true

    fun shareArticle(articleId: String) {
        viewModel.shareArticle(articleId) { title, link ->
            sharedContent.share(context, title, link)
        }
    }

    var swipeTitleLayers by remember { mutableStateOf<List<TopBarTitleLayer>>(emptyList()) }
    val topBarTitleLayers = swipeTitleLayers

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
                        articleId = actionArticleId,
                        onClick = {
                            scrollToTopRequest += 1
                        },
                        navigationAction = navigationAction,
                        onNavButtonClick = onNavAction,
                        onNavigateToStylePage = onNavigateToStylePage,
                        onShareClick = ::shareArticle,
                    )
                }
            },
            bottomBar = {
                if (readerState.articleId != null) {
                    BottomBar(
                        articleId = actionArticleId,
                        isRead = readingUiState.isRead,
                        isStarred = readingUiState.isStarred,
                        actionsEnabled = actionsTargetDisplayed,
                        isNextArticleAvailable = readerState.nextArticle != null,
                        isFullContent =
                            readerState.content is ReaderState.FullContent ||
                                readerState.content is ReaderState.Error,
                        onRead = viewModel::updateReadStatus,
                        onStarred = viewModel::updateStarredStatus,
                        onNextArticle = {
                            readerState.nextArticle?.let { onLoadArticle(it.articleId) }
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
                            loadFullPreview = viewModel::neighborFullReaderState,
                            swipeNeighborTarget = viewModel::swipeNeighborTarget,
                            bringToTopRequest = scrollToTopRequest,
                            onBringToTopHandled = {},
                            onCurrentHeadlineMeasured = { headlineHeightPx = it },
                            onCurrentScrollSnapshotChange = {
                                webViewScrollSnapshot = it
                            },
                            onTitleLayersChange = { swipeTitleLayers = it },
                            onVisibleArticleChange = { visibleArticleId = it },
                            onImageClick = { imgUrl, altText, caption ->
                                currentImageData = ImageData(imgUrl, altText, caption.trim())
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
            val view = LocalView.current
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black),
                contentAlignment = Alignment.Center,
            ) {
                fullscreenVideoView?.let { videoView ->
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { ctx ->
                            FrameLayout(ctx).apply {
                                addView(
                                    videoView,
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

                Row(
                    modifier =
                        Modifier.fillMaxWidth()
                            .safeDrawingPadding()
                            .align(Alignment.TopCenter),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(
                        onClick = {
                            view.performHapticFeedback(HapticFeedbackConstantsCompat.KEYBOARD_TAP)
                            fullscreenVideoCallback?.onCustomViewHidden()
                        },
                        colors =
                            IconButtonDefaults.iconButtonColors(
                                containerColor = Color.Black.copy(alpha = 0.5f),
                                contentColor = Color.White,
                            ),
                        modifier = Modifier.padding(4.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Close,
                            contentDescription = stringResource(id = R.string.close),
                        )
                    }

                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}
