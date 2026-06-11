package me.ash.reader.ui.component.webview

import android.annotation.SuppressLint
import android.content.Context
import android.os.SystemClock
import timber.log.Timber
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.webkit.WebChromeClient
import android.webkit.WebView
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import me.ash.reader.infrastructure.preference.LocalOpenLink
import me.ash.reader.infrastructure.preference.LocalOpenLinkSpecificBrowser
import me.ash.reader.infrastructure.preference.LocalReadingBoldCharacters
import me.ash.reader.infrastructure.preference.LocalReadingFonts
import me.ash.reader.infrastructure.preference.LocalReadingImageHorizontalPadding
import me.ash.reader.infrastructure.preference.LocalReadingImageRoundedCorners
import me.ash.reader.infrastructure.preference.LocalReadingPageTonalElevation
import me.ash.reader.infrastructure.preference.LocalReadingSubheadBold
import me.ash.reader.infrastructure.preference.LocalReadingSubheadUpperCase
import me.ash.reader.infrastructure.preference.LocalReadingTextAlign
import me.ash.reader.infrastructure.preference.LocalReadingTextBold
import me.ash.reader.infrastructure.preference.LocalReadingTextFontSize
import me.ash.reader.infrastructure.preference.LocalReadingTextHorizontalPadding
import me.ash.reader.infrastructure.preference.LocalReadingTextLetterSpacing
import me.ash.reader.infrastructure.preference.LocalReadingTextLineHeight
import me.ash.reader.infrastructure.preference.ReadingFontsPreference
import me.ash.reader.ui.ext.ExternalFonts
import me.ash.reader.ui.ext.openURL
import me.ash.reader.ui.ext.surfaceColorAtElevation
import me.ash.reader.ui.theme.palette.alwaysLight

internal val LocalWebViewCreatedForTest = compositionLocalOf<((WebView) -> Unit)?> { null }

data class WebViewScrollSnapshot(
    val scrollY: Int,
    val maxScrollY: Int,
    val isAtTop: Boolean,
    val isAtBottom: Boolean,
)

/**
 * Custom WebView that detects horizontal gestures and tells parent views not to intercept.
 * This allows horizontal scrolling in code blocks to work smoothly without triggering
 * parent vertical scroll or page navigation.
 */
class HorizontalScrollAwareWebView(context: Context) : WebView(context) {
    private var startX = 0f
    private var startY = 0f
    private var isHorizontalGesture: Boolean? = null
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    var loadedContentKey: WebViewContentKey? = null
    var onScrollSnapshotChanged: ((WebViewScrollSnapshot) -> Unit)? = null
    var onImageClick: ((imgUrl: String, altText: String) -> Unit)? = null
    var onLinkLongPress: ((url: String, text: String) -> Unit)? = null
    var handledScrollToTopRequest: Int = 0

    fun emitScrollSnapshot() {
        val maxScrollY = (computeVerticalScrollRange() - computeVerticalScrollExtent()).coerceAtLeast(0)
        onScrollSnapshotChanged?.invoke(
            WebViewScrollSnapshot(
                scrollY = scrollY,
                maxScrollY = maxScrollY,
                isAtTop = !canScrollVertically(-1),
                isAtBottom = !canScrollVertically(1),
            )
        )
    }

    override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
        super.onScrollChanged(l, t, oldl, oldt)
        emitScrollSnapshot()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startX = event.x
                startY = event.y
                isHorizontalGesture = null
            }
            MotionEvent.ACTION_MOVE -> {
                if (isHorizontalGesture == null) {
                    val dx = abs(event.x - startX)
                    val dy = abs(event.y - startY)
                    if (dx > touchSlop || dy > touchSlop) {
                        isHorizontalGesture = dx > dy
                        if (isHorizontalGesture == true) {
                            // For horizontal gestures, tell parents not to intercept
                            parent?.requestDisallowInterceptTouchEvent(true)
                        }
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isHorizontalGesture = null
            }
        }
        return super.dispatchTouchEvent(event)
    }
}

data class WebViewContentKey(val baseUrl: String, val html: String, val fontSize: Int)

@Composable
fun RYWebView(
    modifier: Modifier = Modifier,
    content: String,
    baseUrl: String? = null,
    refererDomain: String? = null,
    scrollToTopRequest: Int = 0,
    onImageClick: ((imgUrl: String, altText: String) -> Unit)? = null,
    onLinkLongPress: ((url: String, text: String) -> Unit)? = null,
    onShowCustomView: ((View, WebChromeClient.CustomViewCallback) -> Unit)? = null,
    onHideCustomView: (() -> Unit)? = null,
    onScrollSnapshotChange: ((WebViewScrollSnapshot) -> Unit)? = null,
) {
    val context = LocalContext.current
    val openLink = LocalOpenLink.current
    val openLinkSpecificBrowser = LocalOpenLinkSpecificBrowser.current
    val tonalElevation = LocalReadingPageTonalElevation.current
    val backgroundColor =
        MaterialTheme.colorScheme.surfaceColorAtElevation(tonalElevation.value.dp).toArgb()
    val selectionTextColor = Color.Black.toArgb()
    val selectionBgColor = (MaterialTheme.colorScheme.tertiaryContainer alwaysLight true).toArgb()
    val textColor: Int = MaterialTheme.colorScheme.onSurfaceVariant.toArgb()
    val textBold: Boolean = LocalReadingTextBold.current.value
    val textAlign: String = LocalReadingTextAlign.current.toTextAlignCSS()
    val textMargin: Int = LocalReadingTextHorizontalPadding.current
    val boldTextColor: Int = MaterialTheme.colorScheme.onSurface.toArgb()
    val linkTextColor: Int = MaterialTheme.colorScheme.primary.toArgb()
    val subheadBold: Boolean = LocalReadingSubheadBold.current.value
    val subheadUpperCase: Boolean = LocalReadingSubheadUpperCase.current.value
    val readingFonts = LocalReadingFonts.current
    val fontSize: Int = LocalReadingTextFontSize.current
    val letterSpacing: Float = LocalReadingTextLetterSpacing.current
    val lineHeight: Float = LocalReadingTextLineHeight.current
    val imgMargin: Int = LocalReadingImageHorizontalPadding.current
    val imgBorderRadius: Int = LocalReadingImageRoundedCorners.current
    val codeTextColor: Int = MaterialTheme.colorScheme.tertiary.toArgb()
    val codeBgColor: Int =
        MaterialTheme.colorScheme.surfaceColorAtElevation((tonalElevation.value + 6).dp).toArgb()
    val boldCharacters = LocalReadingBoldCharacters.current
    val onWebViewCreatedForTest = LocalWebViewCreatedForTest.current

    val currentOpenLink by rememberUpdatedState(openLink)
    val currentOpenLinkSpecificBrowser by rememberUpdatedState(openLinkSpecificBrowser)
    val onScrollSnapshotChangeState by rememberUpdatedState(onScrollSnapshotChange)
    val dynamicWebViewClient = remember(context, refererDomain) {
        WebViewClient(
            context = context,
            refererDomain = refererDomain,
            onOpenLink = { url ->
                context.openURL(url, currentOpenLink, currentOpenLinkSpecificBrowser)
            },
        )
    }

    val onShowCustomViewState by rememberUpdatedState(onShowCustomView)
    val onHideCustomViewState by rememberUpdatedState(onHideCustomView)
    val webChromeClient = remember {
        RYWebChromeClient(
            onShowCustomViewCallback = { view, callback ->
                onShowCustomViewState?.invoke(view, callback)
            },
            onHideCustomViewCallback = {
                onHideCustomViewState?.invoke()
            },
        )
    }

    val webView by
        remember {
            mutableStateOf(
                WebViewLayout.acquire(
                    context = context,
                    readingFontsPreference = readingFonts,
                    webViewClient = dynamicWebViewClient,
                    webChromeClient = null,
                    onImageClick = onImageClick,
                    onLinkLongPress = onLinkLongPress,
                ).also { onWebViewCreatedForTest?.invoke(it) }
            )
        }

    val fontPath =
        if (readingFonts is ReadingFontsPreference.External)
            ExternalFonts.FontType.ReadingFont.toPath(context)
        else if (readingFonts is ReadingFontsPreference.GoogleSans) {
            "/android_res/font/google_sans_flex.ttf"
        } else null
    val htmlBaseUrl = baseUrl ?: "about:blank"
    val articleHtml by
        produceState<String?>(initialValue = null, content, htmlBaseUrl, fontSize, fontPath, lineHeight, letterSpacing, textMargin, textColor, textBold, textAlign, boldTextColor, subheadBold, subheadUpperCase, imgMargin, imgBorderRadius, linkTextColor, codeTextColor, codeBgColor, selectionTextColor, selectionBgColor, boldCharacters.value) {
            val buildStartedAtMs = SystemClock.elapsedRealtime()
            value =
                withContext(Dispatchers.Default) {
                    WebViewHtml.HTML.format(
                        WebViewStyle.get(
                            fontSize = fontSize,
                            fontPath = fontPath,
                            lineHeight = lineHeight,
                            letterSpacing = letterSpacing,
                            textMargin = textMargin,
                            textColor = textColor,
                            textBold = textBold,
                            textAlign = textAlign,
                            boldTextColor = boldTextColor,
                            subheadBold = subheadBold,
                            subheadUpperCase = subheadUpperCase,
                            imgMargin = imgMargin,
                            imgBorderRadius = imgBorderRadius,
                            linkTextColor = linkTextColor,
                            codeTextColor = codeTextColor,
                            codeBgColor = codeBgColor,
                            tableMargin = textMargin,
                            selectionTextColor = selectionTextColor,
                            selectionBgColor = selectionBgColor,
                        ),
                        htmlBaseUrl,
                        content,
                        WebViewScript.get(boldCharacters.value),
                    )
                }
            Timber.tag("RYWebViewPerf").d(
                "html build in %d ms (%d chars)",
                SystemClock.elapsedRealtime() - buildStartedAtMs,
                value?.length ?: 0,
            )
        }

    DisposableEffect(Unit) {
        onDispose {
            WebViewLayout.release(webView)
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { webView },
        update = { wv ->
            wv.onScrollSnapshotChanged = { snapshot -> onScrollSnapshotChangeState?.invoke(snapshot) }
            if (wv.webViewClient !== dynamicWebViewClient) {
                wv.webViewClient = dynamicWebViewClient
            }
            wv.webChromeClient =
                if (onShowCustomView != null && onHideCustomView != null) webChromeClient else null
            wv.settings.defaultFontSize = fontSize
            wv.settings.standardFontFamily =
                when (readingFonts) {
                    ReadingFontsPreference.Cursive -> "cursive"
                    ReadingFontsPreference.Monospace -> "monospace"
                    ReadingFontsPreference.SansSerif -> "sans-serif"
                    ReadingFontsPreference.Serif -> "serif"
                    else -> "sans-serif"
                }
            val html = articleHtml ?: return@AndroidView
            val contentKey = WebViewContentKey(htmlBaseUrl, html, fontSize)
            if (wv.loadedContentKey != contentKey) {
                wv.loadedContentKey = contentKey
                wv.loadDataWithBaseURL(
                    htmlBaseUrl,
                    html,
                    "text/HTML",
                    "UTF-8",
                    null,
                )
                wv.post { wv.emitScrollSnapshot() }
            }
            if (scrollToTopRequest != 0 && scrollToTopRequest != wv.handledScrollToTopRequest) {
                wv.handledScrollToTopRequest = scrollToTopRequest
                wv.post {
                    wv.scrollTo(0, 0)
                    wv.emitScrollSnapshot()
                }
            }
        },
    )
}
