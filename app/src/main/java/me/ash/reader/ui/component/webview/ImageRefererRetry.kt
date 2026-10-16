package me.ash.reader.ui.component.webview

import java.util.concurrent.ConcurrentHashMap

/**
 * Hotlink-protected image hosts reject the article's own Referer that the WebView sends.
 * When an image fails, the page script ([WebViewHtml.HTML]) re-requests it once with
 * [MARKER] appended, and [WebViewClient] fetches the original URL itself with a
 * same-site Referer (`https://<image host>/`). Hosts that needed this are kept for the
 * life of the process so their later images get the Referer up front.
 */
object ImageRefererRetry {

    const val MARKER = "ry_referer_retry=1"

    private val hosts: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** The original URL when [url] is a marked retry, otherwise null. */
    fun unmark(url: String): String? =
        when {
            url.endsWith("?$MARKER") || url.endsWith("&$MARKER") ->
                url.dropLast(MARKER.length + 1)
            else -> null
        }

    fun isKnown(host: String): Boolean = host in hosts

    fun remember(host: String) {
        hosts.add(host)
    }

    fun forget(host: String) {
        hosts.remove(host)
    }
}
