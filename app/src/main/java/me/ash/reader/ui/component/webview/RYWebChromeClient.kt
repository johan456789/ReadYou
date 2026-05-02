package me.ash.reader.ui.component.webview

import timber.log.Timber
import android.view.View
import android.webkit.WebChromeClient

class RYWebChromeClient(
    private val onShowCustomViewCallback: (View, CustomViewCallback) -> Unit,
    private val onHideCustomViewCallback: () -> Unit,
) : WebChromeClient() {

    private var customView: View? = null
    private var customViewCallback: CustomViewCallback? = null

    override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
        Timber.tag("RYWebChromeClient").i("onShowCustomView called, view=$view, callback=$callback")
        if (customView != null) {
            Timber.tag("RYWebChromeClient").i("customView already exists, ignoring duplicate call")
            return
        }
        if (view == null || callback == null) {
            Timber.tag("RYWebChromeClient").w("view or callback is null, returning")
            return
        }

        Timber.tag("RYWebChromeClient").i("Setting fullscreen view")
        customView = view
        customViewCallback = callback
        Timber.tag("RYWebChromeClient").i("Calling onShowCustomViewCallback lambda")
        onShowCustomViewCallback(view, callback)
        Timber.tag("RYWebChromeClient").i("onShowCustomViewCallback lambda completed")
    }

    override fun onHideCustomView() {
        Timber.tag("RYWebChromeClient").i("onHideCustomView called")
        if (customView == null) {
            Timber.tag("RYWebChromeClient").w("customView is null, returning")
            return
        }

        Timber.tag("RYWebChromeClient").i("Hiding fullscreen view")
        clearCustomView()
        onHideCustomViewCallback()
    }

    fun isShowingCustomView(): Boolean = customView != null

    fun releaseCustomView() {
        if (customView == null) return
        clearCustomView()
        onHideCustomViewCallback()
    }

    private fun clearCustomView() {
        customViewCallback?.onCustomViewHidden()
        customView = null
        customViewCallback = null
    }
}
