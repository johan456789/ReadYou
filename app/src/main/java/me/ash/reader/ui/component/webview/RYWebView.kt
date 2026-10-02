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
import java.util.concurrent.atomic.AtomicBoolean
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
import me.ash.reader.infrastructure.preference.LocalReadingTitleAlign
import me.ash.reader.infrastructure.preference.LocalReadingTitleBold
import me.ash.reader.infrastructure.preference.LocalReadingTitleUpperCase
import me.ash.reader.infrastructure.preference.ReadingFontsPreference
import me.ash.reader.infrastructure.preference.ReadingTitleAlignPreference
import me.ash.reader.ui.ext.ExternalFonts
import me.ash.reader.ui.ext.formatAsString
import me.ash.reader.ui.ext.openURL
import me.ash.reader.ui.ext.surfaceColorAtElevation
import me.ash.reader.ui.theme.palette.alwaysLight

internal val LocalWebViewCreatedForTest = compositionLocalOf<((WebView) -> Unit)?> { null }

data class WebViewScrollSnapshot(
    val scrollY: Int,
    val maxScrollY: Int,
    val viewportHeight: Int = 0,
    val isAtTop: Boolean,
    val isAtBottom: Boolean,
)

/**
 * Custom WebView that detects horizontal gestures and tells parent views not to intercept.
 * HTML elements that scroll horizontally, media with native controls, and touch-action pan-y/none elements claim horizontal gestures.
 */
class HorizontalScrollAwareWebView(context: Context) : WebView(context) {
    private var startX = 0f
    private var startY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var isHorizontalGesture: Boolean? = null
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    var loadedContentKey: WebViewContentKey? = null
    var onScrollSnapshotChanged: ((WebViewScrollSnapshot) -> Unit)? = null
    var onHeadlineMeasured: ((Int) -> Unit)? = null
    var onImageClick: ((imgUrl: String, altText: String, caption: String) -> Unit)? = null
    var onLinkLongPress: ((url: String, text: String) -> Unit)? = null
    var onAnchorScroll: ((cssTop: Double) -> Unit)? = null
    var handledScrollToTopRequest: Int = 0
    private val touchStartsInHorizontalScrollableContent = AtomicBoolean(false)
    private val touchStartsInMediaContent = AtomicBoolean(false)

    fun setTouchStartsInHorizontalScrollableContent(isScrollable: Boolean) {
        touchStartsInHorizontalScrollableContent.set(isScrollable)
        if (isScrollable) {
            post {
                if (isHorizontalGesture == true && touchStartsInHorizontalScrollableContent.get()) {
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
            }
        }
    }

    fun setTouchStartsInMediaContent(isMedia: Boolean) {
        touchStartsInMediaContent.set(isMedia)
        if (isMedia) {
            post {
                if (isHorizontalGesture != null && touchStartsInMediaContent.get()) {
                    if (abs(lastX - startX) > touchSlop && abs(lastX - startX) > abs(lastY - startY) / 2f) {
                        isHorizontalGesture = true
                        parent?.requestDisallowInterceptTouchEvent(true)
                    }
                }
            }
        }
    }

    fun resetTouchStartsInHorizontalScrollableContent() {
        touchStartsInHorizontalScrollableContent.set(false)
        touchStartsInMediaContent.set(false)
    }

    fun emitScrollSnapshot() {
        val contentHeight = computeVerticalScrollRange()
        val viewportHeight = computeVerticalScrollExtent()
        val maxScrollY = (contentHeight - viewportHeight).coerceAtLeast(0)
        onScrollSnapshotChanged?.invoke(
            WebViewScrollSnapshot(
                scrollY = scrollY,
                maxScrollY = maxScrollY,
                viewportHeight = viewportHeight,
                isAtTop = scrollY <= 0,
                isAtBottom = scrollY >= maxScrollY,
            )
        )
    }

    override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
        super.onScrollChanged(l, t, oldl, oldt)
        if (!headlineMeasured && t > 0) {
            // Fallback: if the page-finish measurement never landed, measure on
            // first real scroll so the top-bar title still has a threshold.
            headlineMeasured = true
            runHeadlineMeasure(settleCheckToken)
        }
        emitScrollSnapshot()
    }

    private var settleCheckToken = 0
    private var headlineMeasured = false

    companion object {
        private const val MEASURE_HEADLINE_JS =
            "(function(){var h=document.getElementById('ry-headline');" +
                "if(!h) return -1;" +
                "return Math.round((h.getBoundingClientRect().bottom + window.scrollY)" +
                " * window.devicePixelRatio);})()"

        /**
         * Silences every audible source in the page: pauses inline
         * `<audio>`/`<video>` and parks `<iframe>` embeds (YouTube etc.) by
         * pointing them at about:blank. Evaluating just `pause()` on media
         * elements is not enough - cross-origin embeds are separate documents
         * and keep playing. Parked frames are restored by [RESUME_EMBEDS_JS].
         */
        internal const val PAUSE_ALL_MEDIA_JS =
            "(function(){try{" +
                "var els=document.querySelectorAll('audio,video');" +
                "for(var i=0;i<els.length;i++){try{els[i].pause();}catch(e){}}" +
                "var ifs=document.querySelectorAll('iframe');" +
                "for(var j=0;j<ifs.length;j++){try{" +
                "var f=ifs[j];" +
                "if(f.dataset.ryParked!=='1'&&f.src&&f.src!=='about:blank'){" +
                "f.dataset.rySrc=f.src;f.dataset.ryParked='1';f.src='about:blank';}" +
                "}catch(e){}}" +
                "}catch(e){}})()"

        /** Reverses the iframe parking done by [PAUSE_ALL_MEDIA_JS]. */
        internal const val RESUME_EMBEDS_JS =
            "(function(){try{" +
                "var ifs=document.querySelectorAll('iframe');" +
                "for(var j=0;j<ifs.length;j++){try{" +
                "var f=ifs[j];" +
                "if(f.dataset.ryParked==='1'){f.src=f.dataset.rySrc||f.src;f.dataset.ryParked='0';}" +
                "}catch(e){}}" +
                "}catch(e){}})()"
    }

    override fun loadDataWithBaseURL(
        baseUrl: String?,
        data: String,
        mimeType: String?,
        encoding: String?,
        historyUrl: String?,
    ) {
        // New article invalidates any pending settle checks from the previous content.
        settleCheckToken++
        headlineMeasured = false
        loadStartedAtMs = android.os.SystemClock.elapsedRealtime()
        awaitingFirstDraw = true
        docState = "html"
        Timber.tag("RYPool")
            .d("load id=%08x chars=%d paused=%b", System.identityHashCode(this), data.length, mediaPaused)
        super.loadDataWithBaseURL(baseUrl, data, mimeType, encoding, historyUrl)
    }

    override fun loadUrl(url: String) {
        settleCheckToken++
        headlineMeasured = false
        loadStartedAtMs = android.os.SystemClock.elapsedRealtime()
        awaitingFirstDraw = false
        docState = url ?: "-"
        Timber.tag("RYPool").d("loadUrl id=%08x url=%s", System.identityHashCode(this), url)
        super.loadUrl(url)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        Timber.tag("RYPool").d("attach id=%08x", System.identityHashCode(this))
    }

    override fun onDetachedFromWindow() {
        Timber.tag("RYPool").d("detach id=%08x", System.identityHashCode(this))
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: android.graphics.Canvas) {
        super.onDraw(canvas)
        if (awaitingFirstDraw) {
            awaitingFirstDraw = false
            Timber.tag("RYPool").d(
                "firstDraw id=%08x msAfterLoad=%d paused=%b",
                System.identityHashCode(this),
                android.os.SystemClock.elapsedRealtime() - loadStartedAtMs,
                mediaPaused,
            )
        }
    }

    private var loadStartedAtMs = 0L
    fun msSinceLoad(): Long = android.os.SystemClock.elapsedRealtime() - loadStartedAtMs
    var awaitingFirstDraw = false
        private set
    var docState = "none"
        private set

    fun cancelPendingSettleCheck() {
        settleCheckToken++
    }

    /**
     * Stops audible media without destroying page state (scroll position and
     * DOM are preserved, unlike [WebViewLayout.recycle][me.ash.reader.ui.component.webview.WebViewLayout.recycle]).
     * Must be called on the UI thread.
     *
     * Deliberately does NOT call [android.webkit.WebView.onPause]: that stops the
     * renderer from presenting frames, so a page whose raster is still in flight
     * (large documents, images decoding) freezes half-painted - the pane shows
     * the top of the article and a blank bottom half until it is resumed. Media
     * silencing is done in-page instead, which leaves the renderer free to finish
     * painting.
     */
    fun pauseMediaPlayback() {
        mediaPaused = true
        Timber.tag("RYPool")
            .d(
                "pause id=%08x awaitingDraw=%b",
                System.identityHashCode(this),
                awaitingFirstDraw,
            )
        runCatching { evaluateJavascript(PAUSE_ALL_MEDIA_JS, null) }
    }

    /**
     * Counterpart to [pauseMediaPlayback]: lets a visible page play media again
     * and restores embeds parked by [PAUSE_ALL_MEDIA_JS]. Must be called on the
     * UI thread.
     */
    fun resumeMediaPlayback() {
        mediaPaused = false
        Timber.tag("RYPool")
            .d(
                "resume id=%08x awaitingDraw=%b",
                System.identityHashCode(this),
                awaitingFirstDraw,
            )
        runCatching { evaluateJavascript(RESUME_EMBEDS_JS, null) }
        runCatching { onResume() }
    }

    /**
     * Called once the WebView has presented the first frame of a freshly loaded
     * page. Media is silenced here too: a page that started playing audio or
     * video on its own before the slot was marked paused must not keep playing
     * under the visible article. The renderer is left running so the rest of the
     * document - images, lower tiles - can finish painting.
     */
    fun onPageCommittedVisible() {
        if (!mediaPaused) return
        runCatching { evaluateJavascript(PAUSE_ALL_MEDIA_JS, null) }
    }

    var mediaPaused = false
        private set

    /**
     * Called on page finish; the headline is text-only so its height is stable
     * regardless of article images below. Measure it (plus once more after web
     * fonts settle) so the top bar can show the title once it scrolls past.
     */
    fun notifyPageFinished() {
        val token = ++settleCheckToken
        post { runHeadlineMeasure(token) }
        postDelayed({ runHeadlineMeasure(token) }, 500)
        postDelayed({ runHeadlineMeasure(token) }, 1500)
        postDelayed({ runHeadlineMeasure(token) }, 3000)
    }

    private fun runHeadlineMeasure(token: Int) {
        if (token != settleCheckToken) return
        runCatching {
            evaluateJavascript(MEASURE_HEADLINE_JS) { result ->
                if (token != settleCheckToken) return@evaluateJavascript
                val px = result?.trim()?.trim('"')?.toDoubleOrNull()?.toInt()
                    ?: return@evaluateJavascript
                Timber.tag("RYWebView").d("headline measure raw=%s px=%d", result, px)
                if (px > 0) {
                    headlineMeasured = true
                    onHeadlineMeasured?.invoke(px)
                }
            }
        }
        emitScrollSnapshot()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startX = event.x
                startY = event.y
                isHorizontalGesture = null
                resetTouchStartsInHorizontalScrollableContent()
            }
            MotionEvent.ACTION_MOVE -> {
                lastX = event.x
                lastY = event.y
                if (isHorizontalGesture == null) {
                    val dx = abs(event.x - startX)
                    val dy = abs(event.y - startY)
                    if (dx > touchSlop || dy > touchSlop) {
                        isHorizontalGesture =
                            if (touchStartsInMediaContent.get()) dx > touchSlop && dx > dy / 2f
                            else dx > dy
                        if (isHorizontalGesture == true && (touchStartsInHorizontalScrollableContent.get() || touchStartsInMediaContent.get())) {
                            parent?.requestDisallowInterceptTouchEvent(true)
                        }
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isHorizontalGesture = null
                resetTouchStartsInHorizontalScrollableContent()
            }
        }
        return super.dispatchTouchEvent(event)
    }
}

data class WebViewContentKey(val baseUrl: String, val html: String, val fontSize: Int)

/**
 * A rendered article document plus the exact inputs it was built from.
 *
 * [produceState] restarts after composition, so for the first frame after an
 * article change the produced value still holds the *previous* document while
 * the new inputs are already in scope. Pairing them lets the loader wait for
 * the rebuild instead of pushing the old article into a freshly primed pane.
 */
private data class HtmlDocument(val inputs: List<Any?>, val html: String)

@Composable
fun RYWebView(
    modifier: Modifier = Modifier,
    content: String,
    baseUrl: String? = null,
    refererDomain: String? = null,
    headlineTitle: String = "",
    headlineFeedName: String = "",
    headlineAuthor: String? = null,
    publishedDate: java.util.Date? = null,
    scrollToTopRequest: Int = 0,
    onImageClick: ((imgUrl: String, altText: String, caption: String) -> Unit)? = null,
    onLinkLongPress: ((url: String, text: String) -> Unit)? = null,
    onAnchorScroll: ((cssTop: Double) -> Unit)? = null,
    onHeadlineMeasured: ((Int) -> Unit)? = null,
    onShowCustomView: ((View, WebChromeClient.CustomViewCallback) -> Unit)? = null,
    onHideCustomView: (() -> Unit)? = null,
    onScrollSnapshotChange: ((WebViewScrollSnapshot) -> Unit)? = null,
    onWebViewCreated: ((HorizontalScrollAwareWebView?) -> Unit)? = null,
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
    val titleBold = LocalReadingTitleBold.current
    val titleUpperCase = LocalReadingTitleUpperCase.current
    val titleAlignCss =
        when (LocalReadingTitleAlign.current) {
            ReadingTitleAlignPreference.Center -> "center"
            ReadingTitleAlignPreference.End -> "end"
            ReadingTitleAlignPreference.Justify -> "justify"
            else -> "start"
        }
    val headlineTitleColor: Int = MaterialTheme.colorScheme.onSurface.toArgb()
    val headlineLabelColor: Int =
        MaterialTheme.colorScheme.outline.copy(alpha = .7f).toArgb()
    val textContentWidth = me.ash.reader.ui.component.reader.LocalTextContentWidth.current
    val density = androidx.compose.ui.platform.LocalDensity.current
    val contentMaxWidthPx =
        remember(textContentWidth, density) {
            with(density) { textContentWidth.roundToPx() }
        }
    val dateString =
        remember(context, publishedDate) {
            publishedDate?.formatAsString(context, atHourMinute = true).orEmpty()
        }

    val currentOpenLink by rememberUpdatedState(openLink)
    val currentOpenLinkSpecificBrowser by rememberUpdatedState(openLinkSpecificBrowser)
    val onScrollSnapshotChangeState by rememberUpdatedState(onScrollSnapshotChange)
    val onHeadlineMeasuredState by rememberUpdatedState(onHeadlineMeasured)
    val onWebViewCreatedState by rememberUpdatedState(onWebViewCreated)
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
    // New-window requests (`target="_blank"` in embeds, `window.open`) only
    // arrive through the chrome client, so it must be installed even on
    // screens that do not wire fullscreen video handling.
    val webChromeClient =
        remember(onShowCustomView != null, onHideCustomView != null) {
            RYWebChromeClient(
                onShowCustomViewCallback =
                    if (onShowCustomView == null) null
                    else {
                        { view, callback -> onShowCustomViewState?.invoke(view, callback) }
                    },
                onHideCustomViewCallback =
                    if (onHideCustomView == null) null else {
                        { onHideCustomViewState?.invoke() }
                    },
                onOpenLink = { url ->
                    context.openURL(url, currentOpenLink, currentOpenLinkSpecificBrowser)
                },
            )
        }

    val webView by
        remember {
            mutableStateOf(
                WebViewLayout.obtain(
                    context = context,
                    readingFontsPreference = readingFonts,
                    webViewClient = dynamicWebViewClient,
                    webChromeClient = null,
                    onImageClick = onImageClick,
                    onLinkLongPress = onLinkLongPress,
                    onAnchorScroll = onAnchorScroll,
                ).also {
                    onWebViewCreatedForTest?.invoke(it)
                    onWebViewCreatedState?.invoke(it)
                }
            )
        }

    val fontPath =
        if (readingFonts is ReadingFontsPreference.External)
            ExternalFonts.FontType.ReadingFont.toPath(context)
        else if (readingFonts is ReadingFontsPreference.GoogleSans) {
            "/android_res/font/google_sans_flex.ttf"
        } else null
    val htmlBaseUrl = baseUrl ?: "about:blank"
    val headlineHtml =
        remember(
            headlineTitle,
            headlineFeedName,
            headlineAuthor,
            dateString,
            htmlBaseUrl,
            titleUpperCase.value,
        ) {
            buildHeadlineHtml(
                title = headlineTitle,
                feedName = headlineFeedName,
                author = headlineAuthor,
                dateString = dateString,
                link = htmlBaseUrl,
                upperCaseTitle = titleUpperCase.value,
            )
        }
    val htmlInputs =
        listOf(
            content,
            headlineHtml,
            htmlBaseUrl,
            fontSize,
            fontPath,
            lineHeight,
            letterSpacing,
            textMargin,
            textColor,
            textBold,
            textAlign,
            boldTextColor,
            subheadBold,
            subheadUpperCase,
            imgMargin,
            imgBorderRadius,
            linkTextColor,
            codeTextColor,
            codeBgColor,
            selectionTextColor,
            selectionBgColor,
            headlineTitleColor,
            headlineLabelColor,
            titleBold.value,
            titleUpperCase.value,
            titleAlignCss,
            contentMaxWidthPx,
            boldCharacters.value,
        )
    val articleHtml by
        produceState<HtmlDocument?>(initialValue = null, htmlInputs) {
            val inputs = htmlInputs
            val buildStartedAtMs = SystemClock.elapsedRealtime()
            value = null
            val html =
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
                            titleColor = headlineTitleColor,
                            labelColor = headlineLabelColor,
                            titleBold = titleBold.value,
                            titleUpperCase = titleUpperCase.value,
                            titleAlign = titleAlignCss,
                            contentMaxWidthPx = contentMaxWidthPx,
                        ),
                        htmlBaseUrl,
                        headlineHtml,
                        content,
                        WebViewScript.get(boldCharacters.value),
                    )
                }
            value = HtmlDocument(inputs, html)
            Timber.tag("RYWebViewPerf").d(
                "html build in %d ms (%d chars)",
                SystemClock.elapsedRealtime() - buildStartedAtMs,
                html.length,
            )
        }

    DisposableEffect(Unit) {
        onDispose {
            // Clear the owner's reference first: the pooled WebView may be
            // handed to another slot afterwards, which then owns it.
            onWebViewCreatedState?.invoke(null)
            WebViewLayout.recycle(webView)
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { webView },
        update = { wv ->
            wv.onScrollSnapshotChanged = { snapshot -> onScrollSnapshotChangeState?.invoke(snapshot) }
            wv.onHeadlineMeasured = { px -> onHeadlineMeasuredState?.invoke(px) }
            if (wv.webViewClient !== dynamicWebViewClient) {
                wv.webViewClient = dynamicWebViewClient
            }
            wv.webChromeClient = webChromeClient
            wv.settings.defaultFontSize = fontSize
            wv.settings.standardFontFamily =
                when (readingFonts) {
                    ReadingFontsPreference.Cursive -> "cursive"
                    ReadingFontsPreference.Monospace -> "monospace"
                    ReadingFontsPreference.SansSerif -> "sans-serif"
                    ReadingFontsPreference.Serif -> "serif"
                    else -> "sans-serif"
                }
            val document = articleHtml ?: return@AndroidView
            // The html still belongs to the previous article / style; the rebuild
            // is on its way. Loading it now would prime this pane with the wrong
            // document and the real one would have to fight it for the surface.
            if (document.inputs != htmlInputs) return@AndroidView
            val html = document.html
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

private fun String.escapeHtml(): String =
    replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

private fun buildHeadlineHtml(
    title: String,
    feedName: String,
    author: String?,
    dateString: String,
    link: String,
    upperCaseTitle: Boolean,
): String {
    if (title.isBlank() && feedName.isBlank()) return ""
    val displayTitle = (if (upperCaseTitle) title.uppercase() else title).escapeHtml()
    val titleHtml =
        if (displayTitle.isNotBlank() && link.isNotBlank() && link != "about:blank") {
            """<div class="ry-title" dir="auto"><a href="${link.escapeHtml()}">$displayTitle</a></div>"""
        } else if (displayTitle.isNotBlank()) {
            """<div class="ry-title" dir="auto">$displayTitle</div>"""
        } else {
            ""
        }
    val authorHtml =
        if (!author.isNullOrBlank()) {
            """<div class="ry-author" dir="auto">${author.escapeHtml()}</div>"""
        } else {
            ""
        }
    val feedHtml =
        if (feedName.isNotBlank()) {
            """<div class="ry-feed" dir="auto">${feedName.escapeHtml()}</div>"""
        } else {
            ""
        }
    val dateHtml =
        if (dateString.isNotBlank()) {
            """<div class="ry-date" dir="auto">${dateString.escapeHtml()}</div>"""
        } else {
            ""
        }
    return dateHtml + titleHtml + authorHtml + feedHtml
}
