package me.ash.reader.ui.component.webview

import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RYWebViewMediaLifecycleTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun articleWebViewIsRecycledWhenRemovedFromComposition() {
        var showArticle by mutableStateOf(true)
        var articleWebView: WebView? = null

        composeRule.setContent {
            MaterialTheme {
                CompositionLocalProvider(
                    LocalWebViewCreatedForTest provides { articleWebView = it },
                ) {
                    if (showArticle) {
                        RYWebView(content = MEDIA_ARTICLE_HTML)
                    }
                }
            }
        }

        composeRule.waitUntil {
            articleWebView != null
        }

        composeRule.runOnIdle {
            showArticle = false
        }
        composeRule.waitForIdle()

        assertNotNull(articleWebView)
        composeRule.runOnUiThread {
            val webView = articleWebView as HorizontalScrollAwareWebView
            assertNull(webView.parent)
            assertNull(webView.loadedContentKey)
            assertNull(webView.webChromeClient)
            assertEquals(0, webView.scrollY)
        }
    }

    @Test
    fun pauseAllMediaJsTargetsAudioAndVideo() {
        val js = HorizontalScrollAwareWebView.PAUSE_ALL_MEDIA_JS
        assert(js.contains("audio"))
        assert(js.contains("video"))
        assert(js.contains("pause()"))
    }

    @Test
    fun pausingOffScreenWebViewPreservesContent() {
        var articleWebView: WebView? = null

        composeRule.setContent {
            MaterialTheme {
                CompositionLocalProvider(
                    LocalWebViewCreatedForTest provides { articleWebView = it },
                ) {
                    RYWebView(content = MEDIA_ARTICLE_HTML)
                }
            }
        }

        composeRule.waitUntil {
            articleWebView != null
        }

        // Pausing (swipe-away path) must not blank the page the way recycle()
        // does: the article stays loaded so swiping back is instant.
        composeRule.runOnUiThread {
            val webView = articleWebView as HorizontalScrollAwareWebView
            webView.pauseMediaPlayback()
            webView.resumeMediaPlayback()
            assertNotNull(webView.parent)
            assertNotNull(webView.loadedContentKey)
        }
    }

    private companion object {
        const val MEDIA_ARTICLE_HTML = """
            <p>Article with embedded audio.</p>
            <audio src="data:audio/wav;base64,UklGRiQAAABXQVZFZm10IBAAAAABAAEAESsAACJWAAACABAAZGF0YQAAAAA=" controls></audio>
            <video src="data:video/mp4;base64," controls></video>
        """
    }
}
