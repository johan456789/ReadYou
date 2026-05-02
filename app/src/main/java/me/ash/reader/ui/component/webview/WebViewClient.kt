package me.ash.reader.ui.component.webview

import android.content.Context
import android.net.http.SslError
import android.util.Log
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import me.ash.reader.ui.ext.isUrl
import java.io.DataInputStream
import java.net.HttpURLConnection
import java.net.URI

const val INJECTION_TOKEN = "/android_asset_font/"

class WebViewClient(
    private val context: Context,
    private val refererDomain: String?,
    private val onOpenLink: (url: String) -> Unit,
) : WebViewClient() {

    override fun shouldInterceptRequest(
        view: WebView?,
        request: WebResourceRequest?,
    ): WebResourceResponse? {
        val url = request?.url?.toString()
        if (url != null && url.contains(INJECTION_TOKEN)) {
            try {
                val assetPath = url.substring(
                    url.indexOf(INJECTION_TOKEN) + INJECTION_TOKEN.length,
                    url.length
                )
                return WebResourceResponse(
                    "text/HTML",
                    "UTF-8",
                    context.assets.open(assetPath)
                )
            } catch (e: Exception) {
                Log.e("RLog", "WebView shouldInterceptRequest: $e")
            }
        } else if (url != null && url.isUrl()) {
            try {
                var connection = URI.create(url).toURL().openConnection() as HttpURLConnection
                if (connection.responseCode == 403) {
                    connection.disconnect()
                    connection = URI.create(url).toURL().openConnection() as HttpURLConnection
                    connection.setRequestProperty("Referer", refererDomain)
                    val inputStream = DataInputStream(connection.inputStream)
                    return WebResourceResponse(connection.contentType, "UTF-8", inputStream)
                }
            } catch (e: Exception) {
                Log.e("RLog", "shouldInterceptRequest url: $e")
            }
        }
        return super.shouldInterceptRequest(view, request)
    }

    override fun onPageFinished(view: WebView?, url: String?) {
        super.onPageFinished(view, url)
        view!!.evaluateJavascript(OnImgClickScript, null)
        view.evaluateJavascript(OnLinkLongPressScript, null)
    }

    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
        if (null == request?.url) return false
        val url = request.url.toString()
        
        // Don't intercept concrete media URLs - let the WebView handle them natively.
        val lowerUrl = url.lowercase()
        if (
            lowerUrl.endsWith(".mp4") ||
            lowerUrl.endsWith(".webm") ||
            lowerUrl.endsWith(".m3u8") ||
            lowerUrl.endsWith(".ogg")
        ) {
            return false
        }
        
        if (url.isNotEmpty()) onOpenLink(url)
        return true
    }

    override fun onReceivedError(
        view: WebView?,
        request: WebResourceRequest?,
        error: WebResourceError?,
    ) {
        super.onReceivedError(view, request, error)
        Log.e("RLog", "RYWebView onReceivedError: $error")
    }

    override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
        handler?.cancel()
    }

    companion object {
        private const val OnImgClickScript = """
            javascript:(function() {
                var imgs = document.getElementsByTagName("img");
                for(var i = 0; i < imgs.length; i++){
                    imgs[i].pos = i;
                    imgs[i].onclick = function(event) {
                        event.preventDefault();
                        window.${JavaScriptInterface.NAME}.onImgTagClick(this.src, this.alt);
                    }
                }
            })()
            """

        private const val OnLinkLongPressScript = """
            javascript:(function() {
                var links = document.getElementsByTagName("a");
                var longPressTimer = null;
                var longPressDuration = 500;
                var touchStartX = 0;
                var touchStartY = 0;
                var moveThreshold = 10;

                for(var i = 0; i < links.length; i++){
                    (function(link) {
                        link.addEventListener('touchstart', function(event) {
                            touchStartX = event.touches[0].clientX;
                            touchStartY = event.touches[0].clientY;
                            longPressTimer = setTimeout(function() {
                                event.preventDefault();
                                event.stopPropagation();
                                var href = link.href || '';
                                var text = link.innerText || link.textContent || '';
                                window.${JavaScriptInterface.NAME}.onLinkLongPress(href, text.trim());
                            }, longPressDuration);
                        }, {passive: false});

                        link.addEventListener('touchmove', function(event) {
                            if (longPressTimer) {
                                var dx = Math.abs(event.touches[0].clientX - touchStartX);
                                var dy = Math.abs(event.touches[0].clientY - touchStartY);
                                if (dx > moveThreshold || dy > moveThreshold) {
                                    clearTimeout(longPressTimer);
                                    longPressTimer = null;
                                }
                            }
                        });

                        link.addEventListener('touchend', function(event) {
                            if (longPressTimer) {
                                clearTimeout(longPressTimer);
                                longPressTimer = null;
                            }
                        });

                        link.addEventListener('touchcancel', function(event) {
                            if (longPressTimer) {
                                clearTimeout(longPressTimer);
                                longPressTimer = null;
                            }
                        });

                        link.addEventListener('contextmenu', function(event) {
                            event.preventDefault();
                            var href = link.href || '';
                            var text = link.innerText || link.textContent || '';
                            window.${JavaScriptInterface.NAME}.onLinkLongPress(href, text.trim());
                        });
                    })(links[i]);
                }
            })()
            """
    }
}
