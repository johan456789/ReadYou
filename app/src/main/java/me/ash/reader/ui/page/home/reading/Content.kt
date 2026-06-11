package me.ash.reader.ui.page.home.reading

import android.view.View
import android.webkit.WebChromeClient
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import java.util.Date
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
        Column(
            modifier =
                modifier
                    .padding(top = contentPadding.calculateTopPadding())
                    .fillMaxSize()
                    .drawVerticalScrollIndicator(scrollState)
                    .verticalScroll(scrollState),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(modifier = Modifier.then(maxWidthModifier)) {
                Spacer(modifier = Modifier.height(64.dp))
                headline()

                RYWebView(
                    modifier = Modifier.fillMaxSize(),
                    content = content,
                    baseUrl = link,
                    refererDomain = link.extractDomain(),
                    scrollToTopRequest = scrollToTopRequest,
                    onImageClick = onImageClick,
                    onLinkLongPress = onLinkLongPress,
                    onShowCustomView = onShowCustomView,
                    onHideCustomView = onHideCustomView,
                    onScrollSnapshotChange = onScrollSnapshotChange,
                )
                Spacer(modifier = Modifier.height(128.dp))
                Spacer(modifier = Modifier.height(contentPadding.calculateBottomPadding()))
            }
        }
    }
}
