package me.ash.reader.ui.component.webview

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.util.Log
import android.webkit.JavascriptInterface
import me.ash.reader.infrastructure.preference.ReadingFontsPreference

object WebViewLayout {

    @SuppressLint("SetJavaScriptEnabled")
    fun get(
        context: Context,
        readingFontsPreference: ReadingFontsPreference,
        webViewClient: WebViewClient,
        webChromeClient: RYWebChromeClient? = null,
        onImageClick: ((imgUrl: String, altText: String) -> Unit)? = null,
        onLinkLongPress: ((url: String, text: String) -> Unit)? = null,
    ): HorizontalScrollAwareWebView {
        Log.d("WebViewLayout", "Creating WebView with webChromeClient=$webChromeClient")
        return HorizontalScrollAwareWebView(context).apply {
            this.webViewClient = webViewClient
            webChromeClient?.let { 
                Log.d("WebViewLayout", "Setting webChromeClient: $it")
                this.webChromeClient = it 
            } ?: Log.d("WebViewLayout", "webChromeClient is null, not setting")
            scrollBarSize = 0
            isHorizontalScrollBarEnabled = false
            isVerticalScrollBarEnabled = true
            setBackgroundColor(Color.TRANSPARENT)
            with(this.settings) {
                standardFontFamily =
                    when (readingFontsPreference) {
                        ReadingFontsPreference.Cursive -> "cursive"
                        ReadingFontsPreference.Monospace -> "monospace"
                        ReadingFontsPreference.SansSerif -> "sans-serif"
                        ReadingFontsPreference.Serif -> "serif"
                        ReadingFontsPreference.GoogleSans -> {
                            allowFileAccess = true
                            allowFileAccessFromFileURLs = true
                            "sans-serif"
                        }
                        ReadingFontsPreference.External -> {
                            allowFileAccess = true
                            allowFileAccessFromFileURLs = true
                            "sans-serif"
                        }

                        else -> "sans-serif"
                    }
                domStorageEnabled = true
                javaScriptEnabled = true
                mediaPlaybackRequiresUserGesture = false
                addJavascriptInterface(
                    object : JavaScriptInterface {
                        @JavascriptInterface
                        override fun onImgTagClick(imgUrl: String?, alt: String?) {
                            if (onImageClick != null && imgUrl != null) {
                                onImageClick.invoke(imgUrl, alt ?: "")
                            }
                        }

                        @JavascriptInterface
                        override fun onLinkLongPress(url: String?, text: String?) {
                            if (onLinkLongPress != null && url != null) {
                                onLinkLongPress.invoke(url, text ?: "")
                            }
                        }
                    },
                    JavaScriptInterface.NAME,
                )
                setSupportZoom(false)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    isAlgorithmicDarkeningAllowed = true
                }
            }
        }
    }
}
