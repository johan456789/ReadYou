package me.ash.reader.ui.component.webview

import android.os.Handler
import android.os.Looper
import android.os.Message
import android.webkit.WebView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RYWebChromeClientWindowTest {

    private var scenario: ActivityScenario<WebViewTestActivity>? = null
    private var host: WebView? = null
    private var client: RYWebChromeClient? = null

    @After
    fun tearDown() {
        // release() destroys WebViews, which must happen on the UI thread.
        scenario?.onActivity {
            client?.release()
            host?.destroy()
        }
        scenario?.close()
        client = null
        host = null
        scenario = null
    }

    @Test
    fun onCreateWindow_handsTransportBackedPopup() {
        val webView = launchHost()
        var transport: WebView.WebViewTransport? = null
        var created = false
        scenario!!.onActivity {
            val msg = Message.obtain(Handler(Looper.getMainLooper()))
            transport = webView.WebViewTransport()
            msg.obj = transport
            created = client!!.onCreateWindow(webView, false, true, msg)
        }
        assertTrue(created)
        assertNotNull(transport!!.webView)
    }

    @Test
    fun onCreateWindow_withoutTransportDropsRequest() {
        val webView = launchHost()
        var created = true
        scenario!!.onActivity {
            val msg = Message.obtain(Handler(Looper.getMainLooper()))
            created = client!!.onCreateWindow(webView, false, true, msg)
        }
        assertFalse(created)
    }

    @Test
    fun onCreateWindow_nonGestureRequestDropped() {
        val webView = launchHost()
        var created = true
        scenario!!.onActivity {
            val msg = Message.obtain(Handler(Looper.getMainLooper()))
            msg.obj = webView.WebViewTransport()
            created = client!!.onCreateWindow(webView, false, false, msg)
        }
        assertFalse(created)
        assertNull(client!!.popupWebView)
    }

    @Test
    fun releaseClearsPopupWebView() {
        val webView = launchHost()
        scenario!!.onActivity {
            val msg = Message.obtain(Handler(Looper.getMainLooper()))
            msg.obj = webView.WebViewTransport()
            assertTrue(client!!.onCreateWindow(webView, false, true, msg))
            assertNotNull(client!!.popupWebView)
            client!!.release()
            assertNull(client!!.popupWebView)
        }
    }

    @Test
    fun popupNavigationOpensLinkExternally() {
        val opened = CountDownLatch(1)
        var openedUrl: String? = null
        val webView = launchHost { url ->
            openedUrl = url
            opened.countDown()
        }
        var popup: WebView? = null
        scenario!!.onActivity {
            val msg = Message.obtain(Handler(Looper.getMainLooper()))
            val transport = webView.WebViewTransport()
            msg.obj = transport
            val created = client!!.onCreateWindow(webView, false, true, msg)
            assertTrue(created)
            popup = transport.webView
            popup!!.loadUrl("https://example.com/target")
        }
        assertTrue(opened.await(8, TimeUnit.SECONDS))
        assertEquals("https://example.com/target", openedUrl)
    }

    private fun launchHost(onOpenLink: (String) -> Unit = {}): WebView {
        val scenario = ActivityScenario.launch(WebViewTestActivity::class.java).also { scenario = it }
        var webView: WebView? = null
        scenario.onActivity { activity ->
            webView = WebView(activity)
            host = webView
            client =
                RYWebChromeClient(
                    onShowCustomViewCallback = null,
                    onHideCustomViewCallback = null,
                    onOpenLink = onOpenLink,
                )
        }
        return webView!!
    }
}
