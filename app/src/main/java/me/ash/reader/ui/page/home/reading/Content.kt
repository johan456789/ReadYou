package me.ash.reader.ui.page.home.reading

import android.view.View
import android.webkit.WebChromeClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.util.Date
import me.ash.reader.ui.component.scrollbar.drawWebViewScrollIndicator
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
    isLoading: Boolean,
    contentPadding: PaddingValues = PaddingValues(),
    scrollToTopRequest: Int = 0,
    onHeadlineMeasured: ((Int) -> Unit)? = null,
    onImageClick: ((imgUrl: String, altText: String) -> Unit)? = null,
    onLinkLongPress: ((url: String, text: String) -> Unit)? = null,
    onScrollSnapshotChange: ((WebViewScrollSnapshot) -> Unit)? = null,
    onShowCustomView: ((View, WebChromeClient.CustomViewCallback) -> Unit)? = null,
    onHideCustomView: (() -> Unit)? = null,
) {
    // NetNewsWire-style: the WebView owns ALL vertical scrolling (headline is
    // rendered inside the page). There is no outer scroll container, so content
    // height swings can never rewrite a Compose scroll range underneath the user.
    // The scrollbar is the same list-style Compose indicator as the article
    // list, driven by WebView metrics; each page holds its own snapshot so it
    // survives horizontal article swipes.
    if (isLoading) {
        Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            LoadingIndicator(modifier = Modifier.padding(56.dp))
        }
    } else {
        var snapshot by remember(content) {
            mutableStateOf(WebViewScrollSnapshot(0, 0, 0, true, true))
        }
        Box(
            modifier =
                modifier
                    .fillMaxSize()
                    .padding(contentPadding)
                    .drawWebViewScrollIndicator(
                        scrollYPx = snapshot.scrollY,
                        contentHeightPx = snapshot.maxScrollY + snapshot.viewportHeight,
                        viewportHeightPx = snapshot.viewportHeight,
                        isAtTop = snapshot.isAtTop,
                        isAtBottom = snapshot.isAtBottom,
                    ),
            contentAlignment = Alignment.TopCenter,
        ) {
            RYWebView(
                modifier = Modifier.fillMaxSize(),
                content = content,
                baseUrl = link,
                refererDomain = link.extractDomain(),
                headlineTitle = title,
                headlineFeedName = feedName,
                headlineAuthor = author,
                publishedDate = publishedDate,
                scrollToTopRequest = scrollToTopRequest,
                onImageClick = onImageClick,
                onLinkLongPress = onLinkLongPress,
                onHeadlineMeasured = onHeadlineMeasured,
                onShowCustomView = onShowCustomView,
                onHideCustomView = onHideCustomView,
                onScrollSnapshotChange = {
                    snapshot = it
                    onScrollSnapshotChange?.invoke(it)
                },
            )
        }
    }
}
