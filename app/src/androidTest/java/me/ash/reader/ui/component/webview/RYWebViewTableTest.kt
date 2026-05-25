package me.ash.reader.ui.component.webview

import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONTokener
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RYWebViewTableTest {

    private var webView: WebView? = null
    private var scenario: ActivityScenario<WebViewTestActivity>? = null

    @After
    fun tearDown() {
        scenario?.onActivity {
            webView?.destroy()
        }
        scenario?.close()
        webView = null
        scenario = null
    }

    @Test
    fun wideTableScrollsInsideWrapperWithoutWideningPage() {
        val pageLoaded = CountDownLatch(1)
        val html = WebViewHtml.HTML.format(
            WebViewStyle.get(
                fontSize = 24,
                lineHeight = 1.3f,
                letterSpacing = 0f,
                textMargin = 24,
                textColor = 0xFF222222.toInt(),
                textBold = false,
                textAlign = "start",
                boldTextColor = 0xFF111111.toInt(),
                subheadBold = true,
                subheadUpperCase = false,
                imgMargin = 0,
                imgBorderRadius = 0,
                linkTextColor = 0xFF0066CC.toInt(),
                codeTextColor = 0xFF333333.toInt(),
                codeBgColor = 0xFFEFEFEF.toInt(),
                tableMargin = 24,
                selectionTextColor = 0xFFFFFFFF.toInt(),
                selectionBgColor = 0xFF000000.toInt(),
            ),
            "https://example.com/",
            WIDE_TABLE_HTML,
            WebViewScript.get(boldCharacters = false),
        )

        scenario = ActivityScenario.launch(WebViewTestActivity::class.java)
        scenario!!.onActivity { activity ->
            webView = WebView(activity).apply {
                settings.javaScriptEnabled = true
                webViewClient = object : android.webkit.WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        pageLoaded.countDown()
                    }
                }
            }
            activity.setContentView(webView)
            webView!!.loadDataWithBaseURL(
                "https://example.com/",
                html,
                "text/html",
                "UTF-8",
                null,
            )
        }

        assertTrue(pageLoaded.await(5, TimeUnit.SECONDS))

        val metrics = evaluateJavascript(
            """
            (function() {
                const wrapper = document.querySelector('.table-scroll');
                return JSON.stringify({
                    wrapperCount: document.querySelectorAll('.table-scroll').length,
                    pageClientWidth: document.documentElement.clientWidth,
                    pageScrollWidth: document.documentElement.scrollWidth,
                    wrapperClientWidth: wrapper.clientWidth,
                    wrapperScrollWidth: wrapper.scrollWidth
                });
            })()
            """.trimIndent()
        )

        assertEquals(1, metrics.getInt("wrapperCount"))
        assertTrue(
            "Expected wrapper to be horizontally scrollable: $metrics",
            metrics.getInt("wrapperScrollWidth") > metrics.getInt("wrapperClientWidth"),
        )
        assertTrue(
            "Expected page not to be horizontally scrollable: $metrics",
            metrics.getInt("pageScrollWidth") <= metrics.getInt("pageClientWidth") + 1,
        )
    }

    private fun evaluateJavascript(script: String): org.json.JSONObject {
        val latch = CountDownLatch(1)
        var encodedResult: String? = null
        Handler(Looper.getMainLooper()).post {
            webView!!.evaluateJavascript(script) {
                encodedResult = it
                latch.countDown()
            }
        }
        assertTrue(latch.await(5, TimeUnit.SECONDS))
        val json = JSONTokener(encodedResult).nextValue() as String
        return org.json.JSONObject(json)
    }

    private companion object {
        const val WIDE_TABLE_HTML = """
            <p>Before the table.</p>
            <table>
                <thead>
                    <tr>
                        <th style="white-space: nowrap">If you are using a public Wi-Fi network</th>
                        <th style="white-space: nowrap">Always keep the VPN connected</th>
                        <th style="white-space: nowrap">Non-negotiable protection against attacks on shared networks.</th>
                        <th style="white-space: nowrap">WireGuard split tunneling recommendation</th>
                    </tr>
                </thead>
                <tbody>
                    <tr>
                        <td style="white-space: nowrap">At home with a privacy focus</td>
                        <td style="white-space: nowrap">Keep the VPN on</td>
                        <td style="white-space: nowrap">Prevents your ISP from selling browsing data.</td>
                        <td style="white-space: nowrap">Use personal app exclusions only when needed.</td>
                    </tr>
                </tbody>
            </table>
            <p>After the table.</p>
        """
    }
}
