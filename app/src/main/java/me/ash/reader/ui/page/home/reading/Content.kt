package me.ash.reader.ui.page.home.reading

import android.view.View
import android.webkit.WebChromeClient
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import java.util.Date
import kotlinx.coroutines.delay
import me.ash.reader.ui.component.reader.LocalTextContentWidth
import me.ash.reader.ui.component.scrollbar.drawVerticalScrollIndicator
import me.ash.reader.ui.component.webview.RYWebView
import me.ash.reader.ui.component.webview.WebViewScrollSnapshot
import me.ash.reader.ui.ext.extractDomain

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun Content(
    modifier: Modifier = Modifier,
    content: String,
    feedName: String,
    title: String,
    author: String? = null,
    link: String? = null,
    publishedDate: Date,
    scrollState: ScrollState,
    isLoading: Boolean,
    contentPadding: PaddingValues = PaddingValues(),
    scrollToTopRequest: Int = 0,
    onHeadlineMeasured: ((Int) -> Unit)? = null,
    onImageClick: ((imgUrl: String, altText: String) -> Unit)? = null,
    onLinkLongPress: ((url: String, text: String) -> Unit)? = null,
    onAnchorScroll: ((cssTop: Double) -> Unit)? = null,
    onShowCustomView: ((View, WebChromeClient.CustomViewCallback) -> Unit)? = null,
    onHideCustomView: (() -> Unit)? = null,
    onScrollSnapshotChange: ((WebViewScrollSnapshot) -> Unit)? = null,
) {
    val textContentWidth = LocalTextContentWidth.current
    val maxWidthModifier = Modifier.widthIn(max = textContentWidth)
    val uriHandler = LocalUriHandler.current
    val openTitleLink = remember(uriHandler) {
        { uri: String -> uriHandler.openUri(uri) }
    }

    val headline =
        @Composable {
            Column(
                modifier =
                    Modifier
                        .then(maxWidthModifier)
                        .padding(horizontal = 12.dp)
                        .onSizeChanged { onHeadlineMeasured?.invoke(it.height) }
            ) {
                DisableSelection {
                    Metadata(
                        feedName = feedName,
                        title = title,
                        author = author,
                        publishedDate = publishedDate,
                        link = link,
                        onTitleClick = openTitleLink,
                    )
                }
            }
        }

    if (isLoading) {
        Column { LoadingIndicator(modifier = Modifier.size(56.dp)) }
    } else {
        BoxWithConstraints(modifier = modifier.fillMaxSize()) {
            val density = LocalDensity.current
            val viewportHeightPx = with(density) { maxHeight.roundToPx() }

            // While a cold WebView lays the page out, Chromium reports wildly
            // unstable content heights (it can transiently collapse below the
            // viewport - e.g. 50000px -> 1200px -> 200000px). The article
            // scrolls in this outer verticalScroll, so a collapse shrinks
            // maxValue and clamps the user's scroll back to the top - the
            // "snap back while scrolling on first open" bug. Hold the WebView
            // at least the viewport tall until its height stops changing so
            // the scroll range can never collapse underneath the user.
            var isWebViewHeightSettled by remember(content) { mutableStateOf(false) }
            var webViewSizeTick by remember(content) { mutableIntStateOf(0) }
            LaunchedEffect(webViewSizeTick) {
                if (!isWebViewHeightSettled) {
                    delay(1500L)
                    isWebViewHeightSettled = true
                }
            }
            val webViewMinHeight =
                if (isWebViewHeightSettled) 0.dp
                else with(density) { viewportHeightPx.toDp() }

            Column(
                modifier =
                    Modifier
                        .padding(contentPadding)
                        .fillMaxSize()
                        .drawVerticalScrollIndicator(scrollState)
                        .verticalScroll(scrollState),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Column(modifier = Modifier.then(maxWidthModifier)) {
                    Spacer(modifier = Modifier.height(16.dp))
                    headline()

                    RYWebView(
                        modifier =
                            Modifier.fillMaxWidth()
                                .heightIn(min = webViewMinHeight)
                                .onSizeChanged { webViewSizeTick++ },
                        content = content,
                        baseUrl = link,
                        refererDomain = link.extractDomain(),
                        scrollToTopRequest = scrollToTopRequest,
                        onImageClick = onImageClick,
                        onLinkLongPress = onLinkLongPress,
                        onAnchorScroll = onAnchorScroll,
                        onShowCustomView = onShowCustomView,
                        onHideCustomView = onHideCustomView,
                        onScrollSnapshotChange = onScrollSnapshotChange,
                    )
                    Spacer(modifier = Modifier.height(64.dp))
                }
            }
        }
    }
}
