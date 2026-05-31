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
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RYWebViewMediaLifecycleTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun articleWebViewIsDestroyedWhenRemovedFromComposition() {
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
        assertThrows(
            "Expected a removed article WebView to be destroyed so in-page audio/video cannot keep running.",
            Throwable::class.java,
        ) {
            articleWebView!!.evaluateJavascript("document.querySelector('audio').paused", null)
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
