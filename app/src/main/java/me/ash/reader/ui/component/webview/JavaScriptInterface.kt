package me.ash.reader.ui.component.webview

import android.webkit.JavascriptInterface

/**
 * The only surface page JavaScript can call into native. Deliberately limited
 * to boolean gesture signals: the page is an untrusted source, so no URLs and
 * no text cross from it into the app. Link and image URLs are sourced natively
 * from hit testing instead.
 */
interface JavaScriptInterface {

    @JavascriptInterface
    fun onHorizontalScrollableTouchStart(isScrollable: Boolean)

    @JavascriptInterface
    fun onMediaTouchStart(isMedia: Boolean)

    companion object {

        const val NAME = "JavaScriptInterface"
    }
}
