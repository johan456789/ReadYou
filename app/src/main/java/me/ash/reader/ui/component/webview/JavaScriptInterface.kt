package me.ash.reader.ui.component.webview

import android.webkit.JavascriptInterface

interface JavaScriptInterface {

    @JavascriptInterface
    fun onImgTagClick(imgUrl: String?, alt: String?)

    @JavascriptInterface
    fun onLinkLongPress(url: String?, text: String?)

    companion object {

        const val NAME = "JavaScriptInterface"
    }
}
