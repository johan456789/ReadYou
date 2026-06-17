package me.ash.reader.ui.component.webview

import android.annotation.SuppressLint
import android.content.Context
import android.content.MutableContextWrapper
import android.graphics.Color
import android.webkit.JavascriptInterface
import me.ash.reader.infrastructure.preference.ReadingFontsPreference

@Suppress("DEPRECATION")
object WebViewLayout {
    private var retainedWebView: HorizontalScrollAwareWebView? = null
    private const val PREWARM_BASE_URL = "about:blank"
    private const val PREWARM_HTML =
        """
        <!DOCTYPE html>
        <html>
        <head>
            <meta name="viewport" content="width=device-width, initial-scale=1" />
        </head>
        <body></body>
        </html>
        """

    fun prewarm(
        context: Context,
        readingFontsPreference: ReadingFontsPreference,
    ) {
        if (retainedWebView != null) return
        retainedWebView =
            buildRetainedWebView(
                context = context.applicationContext,
                readingFontsPreference = readingFontsPreference,
            )
    }

    fun obtain(
        context: Context,
        readingFontsPreference: ReadingFontsPreference,
        webViewClient: WebViewClient,
        webChromeClient: RYWebChromeClient? = null,
        onImageClick: ((imgUrl: String, altText: String) -> Unit)? = null,
        onLinkLongPress: ((url: String, text: String) -> Unit)? = null,
    ): HorizontalScrollAwareWebView {
        val webView =
            retainedWebView?.also {
                retainedWebView = null
                (it.context as? MutableContextWrapper)?.baseContext = context
            } ?: createWebView(
                    context = MutableContextWrapper(context),
                    readingFontsPreference = readingFontsPreference,
                    webViewClient = webViewClient,
                    webChromeClient = webChromeClient,
                    onImageClick = onImageClick,
                    onLinkLongPress = onLinkLongPress,
                )
        configureWebView(
            webView = webView,
            readingFontsPreference = readingFontsPreference,
            webViewClient = webViewClient,
            webChromeClient = webChromeClient,
            onImageClick = onImageClick,
            onLinkLongPress = onLinkLongPress,
        )
        return webView
    }

    fun recycle(webView: HorizontalScrollAwareWebView) {
        (webView.webChromeClient as? RYWebChromeClient)?.releaseCustomView()
        (webView.parent as? android.view.ViewGroup)?.removeView(webView)
        webView.loadedContentKey = null
        webView.onScrollSnapshotChanged = null
        webView.onImageClick = null
        webView.onLinkLongPress = null
        webView.handledScrollToTopRequest = 0
        webView.touchStartsInHorizontalScrollableContent = false
        runCatching { webView.stopLoading() }
        runCatching { webView.loadUrl("about:blank") }
        runCatching { webView.onPause() }
        runCatching { webView.clearHistory() }
        runCatching { webView.scrollTo(0, 0) }
        runCatching { webView.webChromeClient = null }
        runCatching { webView.webViewClient = android.webkit.WebViewClient() }
        (webView.context as? MutableContextWrapper)?.baseContext = webView.context.applicationContext
        retainedWebView?.let(::destroyRetainedWebView)
        retainedWebView = webView
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun get(
        context: Context,
        readingFontsPreference: ReadingFontsPreference,
        webViewClient: WebViewClient,
        webChromeClient: RYWebChromeClient? = null,
        onImageClick: ((imgUrl: String, altText: String) -> Unit)? = null,
        onLinkLongPress: ((url: String, text: String) -> Unit)? = null,
    ): HorizontalScrollAwareWebView =
        obtain(
            context = context,
            readingFontsPreference = readingFontsPreference,
            webViewClient = webViewClient,
            webChromeClient = webChromeClient,
            onImageClick = onImageClick,
            onLinkLongPress = onLinkLongPress,
        )

    private fun buildRetainedWebView(
        context: Context,
        readingFontsPreference: ReadingFontsPreference,
    ): HorizontalScrollAwareWebView =
        createWebView(
            context = MutableContextWrapper(context),
            readingFontsPreference = readingFontsPreference,
            webViewClient = android.webkit.WebViewClient(),
            webChromeClient = null,
            onImageClick = null,
            onLinkLongPress = null,
        ).also { webView ->
            webView.loadDataWithBaseURL(
                PREWARM_BASE_URL,
                PREWARM_HTML,
                "text/html",
                "UTF-8",
                null,
            )
        }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(
        context: Context,
        readingFontsPreference: ReadingFontsPreference,
        webViewClient: android.webkit.WebViewClient,
        webChromeClient: RYWebChromeClient?,
        onImageClick: ((imgUrl: String, altText: String) -> Unit)?,
        onLinkLongPress: ((url: String, text: String) -> Unit)?,
    ): HorizontalScrollAwareWebView =
        HorizontalScrollAwareWebView(context).apply {
            scrollBarSize = 0
            isHorizontalScrollBarEnabled = false
            isVerticalScrollBarEnabled = true
            setBackgroundColor(Color.TRANSPARENT)
            addJavascriptInterface(
                object : JavaScriptInterface {
                    @JavascriptInterface
                    override fun onImgTagClick(imgUrl: String?, alt: String?) {
                        if (imgUrl != null) {
                            this@apply.onImageClick?.invoke(imgUrl, alt ?: "")
                        }
                    }

                    @JavascriptInterface
                    override fun onLinkLongPress(url: String?, text: String?) {
                        if (url != null) {
                            this@apply.onLinkLongPress?.invoke(url, text ?: "")
                        }
                    }

                    @JavascriptInterface
                    override fun onHorizontalScrollableTouchStart(isScrollable: Boolean) {
                        this@apply.touchStartsInHorizontalScrollableContent = isScrollable
                        if (isScrollable) {
                            this@apply.parent?.requestDisallowInterceptTouchEvent(true)
                        }
                    }
                },
                JavaScriptInterface.NAME,
            )
            configureWebView(
                webView = this,
                readingFontsPreference = readingFontsPreference,
                webViewClient = webViewClient,
                webChromeClient = webChromeClient,
                onImageClick = onImageClick,
                onLinkLongPress = onLinkLongPress,
            )
        }

    private fun configureWebView(
        webView: HorizontalScrollAwareWebView,
        readingFontsPreference: ReadingFontsPreference,
        webViewClient: android.webkit.WebViewClient,
        webChromeClient: RYWebChromeClient?,
        onImageClick: ((imgUrl: String, altText: String) -> Unit)?,
        onLinkLongPress: ((url: String, text: String) -> Unit)?,
    ) {
        webView.onImageClick = onImageClick
        webView.onLinkLongPress = onLinkLongPress
        webView.webViewClient = webViewClient
        webView.webChromeClient = webChromeClient
        webView.onResume()
        with(webView.settings) {
            standardFontFamily =
                when (readingFontsPreference) {
                    ReadingFontsPreference.Cursive -> "cursive"
                    ReadingFontsPreference.Monospace -> "monospace"
                    ReadingFontsPreference.SansSerif -> "sans-serif"
                    ReadingFontsPreference.Serif -> "serif"
                    ReadingFontsPreference.GoogleSans,
                    ReadingFontsPreference.External,
                    ReadingFontsPreference.System -> "sans-serif"
                }
            allowFileAccess =
                readingFontsPreference == ReadingFontsPreference.GoogleSans ||
                    readingFontsPreference == ReadingFontsPreference.External
            allowFileAccessFromFileURLs = allowFileAccess
            domStorageEnabled = true
            javaScriptEnabled = true
            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            mediaPlaybackRequiresUserGesture = false
            setSupportZoom(false)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                isAlgorithmicDarkeningAllowed = true
            }
        }
    }

    private fun destroyRetainedWebView(webView: HorizontalScrollAwareWebView) {
        (webView.parent as? android.view.ViewGroup)?.removeView(webView)
        runCatching { webView.stopLoading() }
        runCatching { webView.loadUrl("about:blank") }
        runCatching { webView.removeAllViews() }
        runCatching { webView.destroy() }
    }
}
