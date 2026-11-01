package me.ash.reader.ui.component.webview

import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Footnote refs must jump inside the article. Some servers (FreshRSS with
 * full-content retrieval, Inoreader, ...) rewrite "#fn-1" to the article's own
 * absolute URL plus the fragment, which used to be handed to the browser.
 */
@RunWith(AndroidJUnit4::class)
class RYWebViewAnchorTest {

    private var webView: WebView? = null
    private var webViewClient: WebViewClient? = null
    private var scenario: ActivityScenario<WebViewTestActivity>? = null
    private val openedLinks = CopyOnWriteArrayList<String>()

    @After
    fun tearDown() {
        scenario?.onActivity {
            webView?.destroy()
        }
        scenario?.close()
        webView = null
        webViewClient = null
        scenario = null
        openedLinks.clear()
    }

    @Test
    fun relativeFootnoteLinkScrollsInsideArticle() {
        loadArticle(article(refHref = "#fn-1"))

        clickFootnoteRef()

        assertTrue("Expected the article to scroll to the footnote", awaitScroll() > 0)
        assertEquals(emptyList<String>(), openedLinks)
    }

    @Test
    fun absoluteSamePageFootnoteLinkScrollsInsideArticle() {
        loadArticle(article(refHref = "$BASE_URL#fn-1"))

        clickFootnoteRef()

        assertTrue("Expected the article to scroll to the footnote", awaitScroll() > 0)
        assertEquals(emptyList<String>(), openedLinks)
    }

    @Test
    fun absoluteLinkOnDifferentHostWithSameNameStillOpensExternally() {
        loadArticle(article(refHref = "https://www.example.com/post/#fn-1"))

        clickFootnoteRef()

        assertTrue("Expected the other host to be handed to the app", awaitOpenedLink())
        assertEquals("https://www.example.com/post/#fn-1", openedLinks.first())
    }

    @Test
    fun absoluteSamePageFootnoteLinkWithoutTrailingSlashScrollsInsideArticle() {
        loadArticle(article(refHref = "https://example.com/post#fn-1"))

        clickFootnoteRef()

        assertTrue("Expected the article to scroll to the footnote", awaitScroll() > 0)
        assertEquals(emptyList<String>(), openedLinks)
    }

    /**
     * Sites often stop click propagation on their own links, so the injected
     * handler never sees the click. The navigation then has to be recognised in
     * `shouldOverrideUrlLoading` instead.
     */
    @Test
    fun samePageFootnoteLinkScrollsWhenClickPropagationIsStopped() {
        loadArticle(
            article(refHref = "https://example.com/post#fn-1", stopPropagation = true)
        )

        clickFootnoteRef()

        assertTrue("Expected the article to scroll to the footnote", awaitScroll() > 0)
        assertEquals(emptyList<String>(), openedLinks)
    }

    @Test
    fun pageUrlMatchesArticleBaseUrl() {
        loadArticle(article(refHref = "#fn-1"))

        assertEquals(BASE_URL, webViewClient?.baseUrl)
        assertEquals(BASE_URL, evalJs("document.baseURI")?.trim('"'))
    }

    @Test
    fun externalLinkWithFragmentStillOpensExternally() {
        loadArticle(article(refHref = "https://other.example.com/post/#fn-1"))

        clickFootnoteRef()

        assertTrue("Expected the external link to be handed to the app", awaitOpenedLink())
        assertEquals("https://other.example.com/post/#fn-1", openedLinks.first())
    }

    private fun article(refHref: String, stopPropagation: Boolean = false): String {
        val onclick = if (stopPropagation) """ onclick="event.stopPropagation()"""" else ""
        return """
        <p>Intro paragraph with a footnote ref
            <sup id="fnref-1"><a class="footnote-ref"$onclick href="$refHref">1</a></sup>.
        </p>
        ${"<p>Filler paragraph to create scroll distance.</p>".repeat(40)}
        <section class="footnotes">
            <ol><li id="fn-1">The footnote text.</li></ol>
        </section>
        """
    }

    private fun clickFootnoteRef() {
        evalJs("document.querySelector('a.footnote-ref').click(); null")
    }

    private fun loadArticle(content: String) {
        val html =
            WebViewHtml.HTML.format(
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
                BASE_URL,
                "",
                content,
                WebViewScript.get(boldCharacters = false),
            )

        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        scenario =
            ActivityScenario.launch(
                Intent(targetContext, WebViewTestActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        scenario!!.onActivity { activity ->
            val client =
                WebViewClient(activity, onOpenLink = { openedLinks.add(it) }).apply {
                    baseUrl = BASE_URL
                }
            webViewClient = client
            webView =
                WebView(activity).apply {
                    settings.javaScriptEnabled = true
                    webViewClient = client
                }
            activity.setContentView(webView)
            webView!!.loadDataWithBaseURL(BASE_URL, html, "text/html", "UTF-8", null)
        }

        assertTrue("Anchor click handler was not installed", awaitAnchorClickBound())
    }

    private fun awaitAnchorClickBound(): Boolean =
        awaitTrue {
            evalJs("String(window.__anchorClickBound === true)")?.trim('"') == "true"
        }

    private fun awaitScroll(): Int {
        var scrollY = 0f
        awaitTrue {
            scrollY = evalJs("String(window.scrollY)")?.trim('"')?.toFloatOrNull() ?: 0f
            scrollY > 0f
        }
        return scrollY.toInt()
    }

    private fun awaitOpenedLink(): Boolean = awaitTrue { openedLinks.isNotEmpty() }

    private fun awaitTrue(condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(POLL_INTERVAL_MS)
        }
        return false
    }

    private fun evalJs(script: String): String? {
        val latch = CountDownLatch(1)
        var result: String? = null
        Handler(Looper.getMainLooper()).post {
            webView!!.evaluateJavascript(script) {
                result = it
                latch.countDown()
            }
        }
        assertTrue("Timed out evaluating JavaScript", latch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS))
        return result
    }

    private companion object {
        const val BASE_URL = "https://example.com/post/"
        const val TIMEOUT_MS = 5_000L
        const val POLL_INTERVAL_MS = 50L
    }
}
