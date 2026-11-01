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
 * Footnote refs must jump inside the article. Some feeds write footnote refs
 * as the article's own absolute URL plus the fragment, which used to be
 * handed to the browser.
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

    /**
     * The fragment must reach the scroll script still percent-encoded, because
     * the script decodes it exactly once. `Uri.fragment` is already decoded,
     * and a second decode misses ids containing a literal '%'. The ref drops
     * the trailing slash so the navigation is a different document and the
     * Kotlin fallback handles it; stopPropagation keeps the injected listener
     * out of the way.
     */
    @Test
    fun encodedFragmentDecodesOnceWhenClickPropagationIsStopped() {
        loadArticle(
            article(
                refHref = "https://example.com/post#a%2520b",
                stopPropagation = true,
                footnoteId = "a%20b",
            )
        )

        clickFootnoteRef()

        assertTrue("Expected the article to scroll to the footnote", awaitScroll() > 0)
        assertEquals(emptyList<String>(), openedLinks)
    }

    /**
     * Same host and path on another port is a different server. The Kotlin
     * fallback is the only path taken here, because propagation is stopped.
     */
    @Test
    fun samePathOnDifferentPortOpensExternallyWhenClickPropagationIsStopped() {
        loadArticle(
            article(refHref = "https://example.com:8443/post/#fn-1", stopPropagation = true)
        )

        clickFootnoteRef()

        assertTrue("Expected the other port to be handed to the app", awaitOpenedLink())
        assertEquals("https://example.com:8443/post/#fn-1", openedLinks.first())
    }

    /**
     * The query is part of the document's identity: a link to the same path
     * with a different query is another document, not an in-page jump.
     */
    @Test
    fun samePathWithDifferentQueryOpensExternally() {
        loadArticle(article(refHref = "https://example.com/post?id=2#fn-1"))

        clickFootnoteRef()

        assertTrue("Expected the different query to be handed to the app", awaitOpenedLink())
        assertEquals("https://example.com/post?id=2#fn-1", openedLinks.first())
    }

    /**
     * Same as [samePathWithDifferentQueryOpensExternally], through the Kotlin
     * fallback, because propagation is stopped.
     */
    @Test
    fun samePathWithDifferentQueryOpensExternallyWhenClickPropagationIsStopped() {
        loadArticle(
            article(refHref = "https://example.com/post?id=2#fn-1", stopPropagation = true)
        )

        clickFootnoteRef()

        assertTrue("Expected the different query to be handed to the app", awaitOpenedLink())
        assertEquals("https://example.com/post?id=2#fn-1", openedLinks.first())
    }

    /**
     * A link whose query matches the article's is still an in-page jump.
     */
    @Test
    fun sameQueryFootnoteLinkScrollsInsideArticle() {
        loadArticle(
            article(refHref = "https://example.com/post?id=1#fn-1"),
            base = "https://example.com/post?id=1",
        )

        clickFootnoteRef()

        assertTrue("Expected the article to scroll to the footnote", awaitScroll() > 0)
        assertEquals(emptyList<String>(), openedLinks)
    }

    /**
     * Uri.path decodes, so an article loaded from `/post/a%2Fb` and a link to
     * `/post/a/b` look equal when compared decoded — but they are different
     * documents. The Kotlin fallback must compare the encoded form. The Kotlin
     * path is the only one taken here, because propagation is stopped.
     */
    @Test
    fun encodedSlashInPathOpensExternallyWhenClickPropagationIsStopped() {
        loadArticle(
            article(refHref = "https://example.com/post/a/b#fn-1", stopPropagation = true),
            base = "https://example.com/post/a%2Fb",
        )

        clickFootnoteRef()

        assertTrue("Expected the different path to be handed to the app", awaitOpenedLink())
        assertEquals("https://example.com/post/a/b#fn-1", openedLinks.first())
    }

    /**
     * A link whose encoded path matches the article's is still an in-page
     * jump: the URL API keeps percent-encoding in the pathname, so this also
     * pins the injected handler to the encoded comparison.
     */
    @Test
    fun sameEncodedPathFootnoteLinkScrollsInsideArticle() {
        loadArticle(
            article(refHref = "https://example.com/post/a%2Fb#fn-1"),
            base = "https://example.com/post/a%2Fb",
        )

        clickFootnoteRef()

        assertTrue("Expected the article to scroll to the footnote", awaitScroll() > 0)
        assertEquals(emptyList<String>(), openedLinks)
    }

    /**
     * Same as [sameEncodedPathFootnoteLinkScrollsInsideArticle], through the
     * Kotlin fallback: the added trailing slash makes it a different document,
     * and the comparison must still tolerate it on the encoded form.
     */
    @Test
    fun sameEncodedPathWithTrailingSlashScrollsWhenClickPropagationIsStopped() {
        loadArticle(
            article(
                refHref = "https://example.com/post/a%2Fb/#fn-1",
                stopPropagation = true,
            ),
            base = "https://example.com/post/a%2Fb",
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

    private fun article(
        refHref: String,
        stopPropagation: Boolean = false,
        footnoteId: String = "fn-1",
    ): String {
        val onclick = if (stopPropagation) """ onclick="event.stopPropagation()"""" else ""
        return """
        <p>Intro paragraph with a footnote ref
            <sup id="fnref-1"><a class="footnote-ref"$onclick href="$refHref">1</a></sup>.
        </p>
        ${"<p>Filler paragraph to create scroll distance.</p>".repeat(40)}
        <section class="footnotes">
            <ol><li id="$footnoteId">The footnote text.</li></ol>
        </section>
        """
    }

    private fun clickFootnoteRef() {
        evalJs("document.querySelector('a.footnote-ref').click(); null")
    }

    private fun loadArticle(content: String, base: String = BASE_URL) {
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
                base,
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
                    baseUrl = base
                }
            webViewClient = client
            webView =
                WebView(activity).apply {
                    settings.javaScriptEnabled = true
                    webViewClient = client
                }
            activity.setContentView(webView)
            webView!!.loadDataWithBaseURL(base, html, "text/html", "UTF-8", null)
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
