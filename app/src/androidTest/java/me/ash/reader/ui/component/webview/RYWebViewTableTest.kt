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
        loadArticle(WIDE_TABLE_HTML)

        val metrics = evaluateJavascript(
            """
            (function() {
                const wrapper = document.querySelector('.table-scroll');
                const firstHeader = document.querySelector('th');
                return JSON.stringify({
                    wrapperClass: wrapper.className,
                    wrapperCount: document.querySelectorAll('.table-scroll').length,
                    pageClientWidth: document.documentElement.clientWidth,
                    pageScrollWidth: document.documentElement.scrollWidth,
                    wrapperClientWidth: wrapper.clientWidth,
                    wrapperScrollWidth: wrapper.scrollWidth,
                    firstHeaderBackground: getComputedStyle(firstHeader).backgroundColor,
                    firstHeaderColor: getComputedStyle(firstHeader).color
                });
            })()
            """.trimIndent()
        )

        assertEquals(1, metrics.getInt("wrapperCount"))
        assertTrue(metrics.getString("wrapperClass").contains("table-scroll--reader"))
        assertEquals("rgba(0, 0, 0, 0)", metrics.getString("firstHeaderBackground"))
        assertEquals("rgb(17, 17, 17)", metrics.getString("firstHeaderColor"))
        assertTrue(
            "Expected wrapper to be horizontally scrollable: $metrics",
            metrics.getInt("wrapperScrollWidth") > metrics.getInt("wrapperClientWidth"),
        )
        assertTrue(
            "Expected page not to be horizontally scrollable: $metrics",
            metrics.getInt("pageScrollWidth") <= metrics.getInt("pageClientWidth") + 1,
        )
    }

    @Test
    fun lightDarkTableStyleIsPreserved() {
        loadArticle(DYNAMIC_TABLE_HTML)

        val metrics = evaluateJavascript(
            """
            (function() {
                const wrapper = document.querySelector('.table-scroll');
                return JSON.stringify({
                    wrapperClass: wrapper.className,
                    wrapperCount: document.querySelectorAll('.table-scroll').length
                });
            })()
            """.trimIndent()
        )

        assertEquals(1, metrics.getInt("wrapperCount"))
        assertTrue(metrics.getString("wrapperClass").contains("table-scroll--dynamic"))
    }

    @Test
    fun nestedTablesAreNormalizedWithoutScrollSizing() {
        loadArticle(NESTED_TABLE_HTML)

        val metrics = evaluateJavascript(
            """
            (function() {
                const wrapper = document.querySelector('.table-scroll');
                const outerTable = wrapper.children[0];
                const nestedTable = wrapper.querySelector('td table');
                const nestedHeader = nestedTable.querySelector('th');
                const outerStyle = getComputedStyle(outerTable);
                const nestedStyle = getComputedStyle(nestedTable);
                return JSON.stringify({
                    wrapperClass: wrapper.className,
                    wrapperCount: document.querySelectorAll('.table-scroll').length,
                    outerMinWidth: outerStyle.minWidth,
                    nestedMinWidth: nestedStyle.minWidth,
                    nestedHeaderBackground: getComputedStyle(nestedHeader).backgroundColor,
                    nestedHeaderColor: getComputedStyle(nestedHeader).color
                });
            })()
            """.trimIndent()
        )

        assertEquals(1, metrics.getInt("wrapperCount"))
        assertTrue(metrics.getString("wrapperClass").contains("table-scroll--reader"))
        assertEquals("100%", metrics.getString("outerMinWidth"))
        assertTrue(
            "Expected nested table not to receive root scroll sizing: $metrics",
            metrics.getString("nestedMinWidth") != "100%",
        )
        assertEquals("rgba(0, 0, 0, 0)", metrics.getString("nestedHeaderBackground"))
        assertEquals("rgb(17, 17, 17)", metrics.getString("nestedHeaderColor"))
    }

    private fun loadArticle(content: String) {
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
            content,
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
        val json =
            JSONTokener(encodedResult ?: "null").nextValue() as? String
                ?: throw AssertionError("Expected JavaScript to return encoded JSON, got: $encodedResult")
        return org.json.JSONObject(json)
    }

    private companion object {
        const val WIDE_TABLE_HTML = """
            <p>Before the table.</p>
            <table>
                <thead>
                    <tr>
                        <th style="white-space: nowrap; background-color: rgb(0, 64, 96); color: white">If you are using a public Wi-Fi network</th>
                        <th style="white-space: nowrap; background-color: rgb(0, 128, 64); color: white">Always keep the VPN connected</th>
                        <th style="white-space: nowrap; background-color: rgb(220, 112, 0); color: white">Non-negotiable protection against attacks on shared networks.</th>
                        <th style="white-space: nowrap; background-color: rgb(128, 144, 152); color: white">WireGuard split tunneling recommendation</th>
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

        const val DYNAMIC_TABLE_HTML = """
            <style>
                table.dynamic-theme th {
                    background-color: light-dark(rgb(240, 240, 240), rgb(24, 24, 24));
                    color: light-dark(rgb(0, 0, 0), rgb(255, 255, 255));
                }
            </style>
            <table class="dynamic-theme">
                <tr>
                    <th style="white-space: nowrap">If you are...</th>
                    <th style="white-space: nowrap">Then do this...</th>
                </tr>
                <tr>
                    <td style="white-space: nowrap">On public Wi-Fi</td>
                    <td style="white-space: nowrap">Always on</td>
                </tr>
            </table>
        """

        const val NESTED_TABLE_HTML = """
            <table>
                <tr>
                    <th style="white-space: nowrap">Outer header</th>
                    <th style="white-space: nowrap">Outer detail</th>
                </tr>
                <tr>
                    <td style="white-space: nowrap">Outer row</td>
                    <td>
                        <table>
                            <tr>
                                <th style="white-space: nowrap; background-color: rgb(0, 64, 96); color: white">Nested header</th>
                                <th style="white-space: nowrap; background-color: rgb(0, 128, 64); color: white">Nested detail</th>
                            </tr>
                            <tr>
                                <td style="white-space: nowrap">Nested row</td>
                                <td style="white-space: nowrap">Nested value</td>
                            </tr>
                        </table>
                    </td>
                </tr>
            </table>
        """
    }
}
