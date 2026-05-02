package me.ash.reader.ui.component.webview

data class LinkActionData(
    val url: String,
    val linkText: String? = null,
) {
    fun displayUrl(maxLength: Int): String {
        return if (url.length <= maxLength) {
            url
        } else {
            url.take(maxLength - 1) + "…"
        }
    }
}
