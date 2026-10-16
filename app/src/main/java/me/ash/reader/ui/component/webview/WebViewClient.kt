package me.ash.reader.ui.component.webview

import android.content.Context
import android.net.Uri
import android.net.http.SslError
import timber.log.Timber
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import me.ash.reader.ui.ext.originReferer
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URI

const val INJECTION_TOKEN = "/android_asset_font/"

class WebViewClient(
    private val context: Context,
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
                Timber.tag("RLog").e(e, "WebView shouldInterceptRequest")
            }
        } else if (url != null && request?.isForMainFrame == false) {
            // Everything else loads through the WebView with its own Referer (the
            // article's site). Only a failed image retry, or a host that already
            // needed one, is fetched here with the image host as Referer.
            val retryUrl = ImageRefererRetry.unmark(url)
            val targetUrl = retryUrl ?: url
            val host = Uri.parse(targetUrl).host
            if (host != null && (retryUrl != null || ImageRefererRetry.isKnown(host))) {
                fetchWithOriginReferer(targetUrl, host, request)?.let { return it }
            }
        }
        return super.shouldInterceptRequest(view, request)
    }

    private fun fetchWithOriginReferer(
        url: String,
        host: String,
        request: WebResourceRequest,
    ): WebResourceResponse? {
        val referer = url.originReferer() ?: return null
        return try {
            val connection = URI.create(url).toURL().openConnection() as HttpURLConnection
            connection.connectTimeout = RESOURCE_TIMEOUT_MS
            connection.readTimeout = RESOURCE_TIMEOUT_MS
            request.requestHeaders.forEach { (name, value) ->
                if (!name.equals("Referer", true) && !name.equals("Accept-Encoding", true)) {
                    connection.setRequestProperty(name, value)
                }
            }
            connection.setRequestProperty("Referer", referer)
            val status = connection.responseCode
            // WebResourceResponse rejects 3xx; a redirect HttpURLConnection would not
            // follow (e.g. https -> http) is left to the WebView.
            if (status in 300..399) {
                connection.disconnect()
                return null
            }
            if (status in 200..299) ImageRefererRetry.remember(host) else ImageRefererRetry.forget(host)
            val body =
                if (status >= 400) connection.errorStream ?: ByteArrayInputStream(ByteArray(0))
                else connection.inputStream
            val contentType = connection.contentType
            WebResourceResponse(
                contentType?.substringBefore(';')?.trim(),
                contentType?.substringAfter("charset=", "")?.trim()?.ifBlank { null },
                status,
                connection.responseMessage?.ifBlank { null } ?: "OK",
                connection.headerFields
                    .filterKeys {
                        it != null &&
                            !it.equals("Content-Encoding", true) &&
                            !it.equals("Content-Length", true)
                    }
                    .mapValues { it.value.joinToString(", ") },
                body,
            )
        } catch (e: Exception) {
            Timber.tag("RLog").e(e, "fetchWithOriginReferer url")
            null
        }
    }

    override fun onPageCommitVisible(view: WebView?, url: String?) {
        super.onPageCommitVisible(view, url)
        val wv = view as? HorizontalScrollAwareWebView
        Timber.tag("RYPool")
            .d(
                "commitVisible id=%08x msAfterLoad=%d",
                System.identityHashCode(view),
                wv?.msSinceLoad() ?: -1L,
            )
        // The page has presented its first frame, so a slot that was resumed
        // only to render can go back to being paused off-screen.
        wv?.onPageCommittedVisible()
    }

    override fun onPageFinished(view: WebView?, url: String?) {
        super.onPageFinished(view, url)
        Timber.tag("RYPool")
            .d("pageFinished id=%08x url=%s", System.identityHashCode(view), url)
        view!!.evaluateJavascript(OnImgClickScript, null)
        view.evaluateJavascript(OnLinkLongPressScript, null)
        view.evaluateJavascript(OnAnchorClickScript, null)
        (view as? HorizontalScrollAwareWebView)?.notifyPageFinished()
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
        Timber.tag("RLog").e("RYWebView onReceivedError: $error")
    }

    override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
        handler?.cancel()
    }

    companion object {
        private const val RESOURCE_TIMEOUT_MS = 10_000

        private const val OnImgClickScript = """
            javascript:(function() {
                var imgs = document.getElementsByTagName("img");
                for(var i = 0; i < imgs.length; i++){
                    imgs[i].pos = i;
                    imgs[i].onclick = function(event) {
                        event.preventDefault();
                        var caption = '';
                        var figure = this.closest ? this.closest('figure') : null;
                        if (figure) {
                            var figcaption = figure.getElementsByTagName('figcaption')[0];
                            if (figcaption) { caption = figcaption.innerText || ''; }
                        }
                        window.${JavaScriptInterface.NAME}.onImgTagClick(this.dataset.rySrc || this.src, this.alt, caption);
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
                var suppressNextClick = false;

                for(var i = 0; i < links.length; i++){
                    (function(link) {
                        link.addEventListener('touchstart', function(event) {
                            touchStartX = event.touches[0].clientX;
                            touchStartY = event.touches[0].clientY;
                            longPressTimer = setTimeout(function() {
                                suppressNextClick = true;
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

                        link.addEventListener('click', function(event) {
                            if (suppressNextClick) {
                                event.preventDefault();
                                event.stopPropagation();
                                suppressNextClick = false;
                            }
                        }, true);
                    })(links[i]);
                }
            })()
            """

        private const val OnAnchorClickScript = """
            javascript:(function() {
                if (window.__anchorClickBound) return;
                window.__anchorClickBound = true;
                document.addEventListener('click', function(event) {
                    var link = event.target.closest('a');
                    if (!link) return;
                    var href = link.getAttribute('href') || '';
                    if (href.charAt(0) !== '#') return;
                    if (event.target.tagName === 'IMG') return;
                    var fragment = href.substring(1);
                    // The WebView owns vertical scrolling, so scroll natively in-page.
                    event.preventDefault();
                    event.stopPropagation();
                    if (fragment === '') {
                        document.body.scrollIntoView();
                        return;
                    }
                    try {
                        fragment = decodeURIComponent(fragment);
                    } catch (e) {}
                    var target = document.getElementById(fragment);
                    if (!target) {
                        var namedElements = document.getElementsByName(fragment);
                        if (namedElements.length > 0) {
                            target = namedElements[0];
                        }
                    }
                    if (!target) return;
                    target.scrollIntoView();
                }, false);
            })()
            """
    }
}
