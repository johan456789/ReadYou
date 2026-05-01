package me.ash.reader.ui.component.webview

import android.util.Log
import android.view.View
import android.webkit.WebChromeClient

class RYWebChromeClient(
    private val onShowCustomViewCallback: (View, CustomViewCallback) -> Unit,
    private val onHideCustomViewCallback: () -> Unit,
) : WebChromeClient() {

    private var customView: View? = null
    private var customViewCallback: CustomViewCallback? = null

    override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
        Log.i("RYWebChromeClient", "onShowCustomView called, view=$view, callback=$callback")
        if (customView != null) {
            Log.i("RYWebChromeClient", "customView already exists, ignoring duplicate call")
            return
        }
        if (view == null || callback == null) {
            Log.w("RYWebChromeClient", "view or callback is null, returning")
            return
        }

        Log.i("RYWebChromeClient", "Setting fullscreen view")
        customView = view
        customViewCallback = callback
        Log.i("RYWebChromeClient", "Calling onShowCustomViewCallback lambda")
        onShowCustomViewCallback(view, callback)
        Log.i("RYWebChromeClient", "onShowCustomViewCallback lambda completed")
    }

    override fun onHideCustomView() {
        Log.i("RYWebChromeClient", "onHideCustomView called")
        if (customView == null) {
            Log.w("RYWebChromeClient", "customView is null, returning")
            return
        }

        Log.i("RYWebChromeClient", "Hiding fullscreen view")
        customViewCallback?.onCustomViewHidden()
        customView = null
        customViewCallback = null
        onHideCustomViewCallback()
    }

    fun isShowingCustomView(): Boolean = customView != null

    fun releaseCustomView() {
        customViewCallback?.onCustomViewHidden()
        customView = null
        customViewCallback = null
    }
}
