package me.ash.reader.ui.component.webview

import android.graphics.Bitmap
import android.os.Message
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.annotation.VisibleForTesting
import timber.log.Timber

class RYWebChromeClient(
    private val onShowCustomViewCallback: ((View, CustomViewCallback) -> Unit)?,
    private val onHideCustomViewCallback: (() -> Unit)?,
    private val onOpenLink: (url: String) -> Unit,
) : WebChromeClient() {

    private var customView: View? = null
    private var customViewCallback: CustomViewCallback? = null

    /**
     * Invisible WebView backing the current new-window request. New windows
     * (`target="_blank"`, `window.open()`) only expose their destination
     * through WebView navigation callbacks, so the window is created here,
     * its first real URL is captured, and the URL is handed to [onOpenLink]
     * instead of ever being rendered.
     */
    @VisibleForTesting internal var popupWebView: WebView? = null

    override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
        Timber.tag("RYWebChromeClient").i("onShowCustomView called, view=$view, callback=$callback")
        if (onShowCustomViewCallback == null) {
            // No fullscreen handler on this screen. Match the platform adapter,
            // which reports the request as handled-but-hidden when no client
            // callback exists, so the player does not stay stuck fullscreen.
            Timber.tag("RYWebChromeClient")
                .i("no fullscreen handler wired, hiding custom view request")
            callback?.onCustomViewHidden()
            return
        }
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
        onShowCustomViewCallback.invoke(view, callback)
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
        onHideCustomViewCallback?.invoke()
    }

    override fun onCreateWindow(
        view: WebView?,
        isDialog: Boolean,
        isUserGesture: Boolean,
        resultMsg: Message?,
    ): Boolean {
        Timber.tag("RYWebChromeClient")
            .i("onCreateWindow isDialog=%b isUserGesture=%b", isDialog, isUserGesture)
        if (!isUserGesture) {
            // Script-initiated window.open() (popunders, ad loops) carries no
            // gesture. Upstream dropped every new-window request, so dropping
            // only these keeps pages from spamming external opens now that
            // multi-window support is enabled; real taps report true.
            Timber.tag("RYWebChromeClient").i("ignoring non-gesture new-window request")
            return false
        }
        val host = view ?: return false
        val msg = resultMsg ?: return false
        // The transport carrying the new window's WebView lives in the
        // message's obj field, not the message itself; without it the
        // request can only be dropped.
        val transport = msg.obj as? WebView.WebViewTransport
        if (transport == null) {
            Timber.tag("RYWebChromeClient").w("no WebViewTransport in result message")
            return false
        }

        // The popup only exists to reveal the requested URL: the first real
        // navigation is forwarded to onOpenLink and never loads here.
        // openURL handles arbitrary schemes (mailto:, intent:, tel:, ...) the
        // same way the main WebViewClient does, so forward everything except
        // WebView-internal about: URLs.
        // Decision: media URLs (.mp4, .m3u8, ...) opened from a new window go
        // external too — the popup never renders, so in-app playback here is
        // impossible; same-tab media links still play in-app via WebViewClient.
        // Do not "fix" this by porting the host-navigation check.
        var openedExternally = false
        fun openExternally(url: String?): Boolean {
            if (url.isNullOrBlank() || url.startsWith("about:")) return false
            if (openedExternally) return true
            openedExternally = true
            Timber.tag("RYWebChromeClient").i("new-window URL -> opening externally: %s", url)
            onOpenLink(url)
            return true
        }

        val popup =
            // Application context: the popup is never attached to a window and
            // shows no UI, so there is no reason to pin the Activity. JS stays
            // off on purpose — the popup only captures the first URL; the
            // script-initiated open() case never gets this far (gesture gate).
            WebView(host.context.applicationContext).apply {
                settings.javaScriptEnabled = false
                webViewClient =
                    object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(
                            view: WebView?,
                            request: WebResourceRequest?,
                        ): Boolean = openExternally(request?.url?.toString())

                        override fun onPageStarted(
                            view: WebView?,
                            url: String?,
                            favicon: Bitmap?,
                        ) {
                            if (openExternally(url)) {
                                view?.stopLoading()
                            }
                        }
                    }
            }
        releasePopup()
        popupWebView = popup
        transport.webView = popup
        msg.sendToTarget()
        return true
    }

    override fun onCloseWindow(window: WebView?) {
        Timber.tag("RYWebChromeClient").i("onCloseWindow called")
        if (window !== null && window === popupWebView) {
            popupWebView = null
            runCatching { window.destroy() }
        }
    }

    fun isShowingCustomView(): Boolean = customView != null

    fun releaseCustomView() {
        if (customView == null) return
        Timber.tag("RYWebChromeClient").i("Hiding fullscreen view")
        clearCustomView()
        onHideCustomViewCallback?.invoke()
    }

    /** Releases both the fullscreen view and the hidden popup WebView. */
    fun release() {
        releaseCustomView()
        releasePopup()
    }

    private fun releasePopup() {
        popupWebView?.let { popup ->
            popupWebView = null
            runCatching { popup.stopLoading() }
            runCatching { popup.destroy() }
        }
    }

    private fun clearCustomView() {
        customViewCallback?.onCustomViewHidden()
        customView = null
        customViewCallback = null
    }
}
