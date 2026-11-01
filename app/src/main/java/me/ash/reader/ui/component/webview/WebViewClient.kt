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
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URI

const val INJECTION_TOKEN = "/android_asset_font/"

class WebViewClient(
    private val context: Context,
    private val onOpenLink: (url: String) -> Unit,
) : WebViewClient() {

    /**
     * The article URL the current document was loaded with. [WebView.getUrl]
     * reports "about:blank" for `loadDataWithBaseURL`, so the client needs the
     * base URL explicitly to recognise links back into the same article.
     */
    var baseUrl: String? = null

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
        view!!.evaluateJavascript(OnAnchorClickScript, null)
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

        // Some feeds write footnote refs as the article's own URL plus the
        // fragment instead of a bare "#fn-1". That is still an in-page jump,
        // not an external link: scroll the WebView instead of handing it to
        // the browser.
        if (view != null && isSameDocumentFragment(request.url)) {
            // The script in scrollToFragment decodes the fragment itself, so
            // hand over the still-encoded form. Uri.fragment is already
            // decoded, and decoding twice misses ids with a literal '%'.
            scrollToFragment(view, request.url.encodedFragment.orEmpty())
            return true
        }

        if (url.isNotEmpty()) onOpenLink(url)
        return true
    }

    private fun isSameDocumentFragment(target: Uri): Boolean {
        if (target.fragment == null) return false
        val current = baseUrl?.let(Uri::parse) ?: return false
        if (current.scheme.isNullOrEmpty() || current.host.isNullOrEmpty()) return false
        if (!target.scheme.equals(current.scheme, ignoreCase = true)) return false
        if (!target.host.equals(current.host, ignoreCase = true)) return false
        if (effectivePort(target) != effectivePort(current)) return false
        // A query can select a different document, so it must match exactly.
        if (target.encodedQuery != current.encodedQuery) return false
        // Compare encoded paths: Uri.path decodes, which conflates an encoded
        // slash inside a segment with a real one. Servers add or drop the
        // trailing slash on article URLs.
        return target.encodedPath.orEmpty().trimEnd('/') ==
            current.encodedPath.orEmpty().trimEnd('/')
    }

    /** `Uri.port` is -1 when omitted; fall back to the scheme's default port. */
    private fun effectivePort(uri: Uri): Int =
        when {
            uri.port != -1 -> uri.port
            uri.scheme.equals("https", ignoreCase = true) -> 443
            uri.scheme.equals("http", ignoreCase = true) -> 80
            else -> -1
        }

    private fun scrollToFragment(view: WebView, fragment: String) {
        view.evaluateJavascript(
            """
            (function() {
                var fragment = ${JSONObject.quote(fragment)};
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
                if (target) target.scrollIntoView();
            })()
            """,
            null,
        )
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

        private const val OnAnchorClickScript = """
            javascript:(function() {
                if (window.__anchorClickBound) return;
                window.__anchorClickBound = true;
                // Returns the fragment when the link points back at this same
                // article, whether it is written as "#fn-1" or as the article's
                // absolute URL plus "#fn-1" (some feeds write footnote links this
                // way). Returns null for links to other pages.
                function samePageFragment(href) {
                    if (href.charAt(0) === '#') return href.substring(1);
                    var resolved, base;
                    try {
                        resolved = new URL(href, document.baseURI);
                        base = new URL(document.baseURI);
                    } catch (e) {
                        return null;
                    }
                    if (!resolved.hash || resolved.hash === '#') return null;
                    if (resolved.protocol !== base.protocol) return null;
                    if (resolved.host !== base.host) return null;
                    if (resolved.search !== base.search) return null;
                    function trimSlash(path) { return path.replace(/\/+$/, ''); }
                    if (trimSlash(resolved.pathname) !== trimSlash(base.pathname)) return null;
                    return resolved.hash.substring(1);
                }
                document.addEventListener('click', function(event) {
                    var link = event.target.closest('a');
                    if (!link) return;
                    var href = link.getAttribute('href') || '';
                    var fragment = samePageFragment(href);
                    if (fragment === null) return;
                    if (event.target.tagName === 'IMG') return;
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
