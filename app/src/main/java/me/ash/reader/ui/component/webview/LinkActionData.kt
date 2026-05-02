package me.ash.reader.ui.component.webview

import java.net.URI

data class LinkActionData(
    val url: String,
    val linkText: String? = null,
) {
    fun fallbackTitle(): String {
        return runCatching {
            URI(url).host?.removePrefix("www.")?.takeIf { it.isNotBlank() }
        }.getOrNull() ?: url
    }

    fun displayUrl(maxLength: Int): String {
        return if (url.length <= maxLength) {
            url
        } else {
            url.take(maxLength - 1) + "…"
        }
    }
}
