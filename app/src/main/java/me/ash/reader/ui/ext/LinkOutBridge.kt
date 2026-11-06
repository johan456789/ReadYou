package me.ash.reader.ui.ext

/**
 * Process-wide outbound-link tap notification. [Context.openURL] is the single
 * choke point every outbound link in the app flows through (WebView body
 * links, the link action dialog, browser-mode articles, misc UI), but it is a
 * plain Context extension with no injection site — so the reading-insights
 * recorder registers itself here at construction and receives every tap.
 *
 * Kept intentionally minimal: one nullable listener, no state. Link openers
 * that already have article context call the recorder directly instead and the
 * recorder de-duplicates the double emission.
 */
object LinkOutBridge {

    @Volatile
    var listener: ((url: String) -> Unit)? = null

    fun emit(url: String) {
        listener?.invoke(url)
    }
}
