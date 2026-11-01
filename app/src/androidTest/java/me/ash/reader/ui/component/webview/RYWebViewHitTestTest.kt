package me.ash.reader.ui.component.webview

import android.content.Intent
import android.graphics.PointF
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Link and image gestures must come from native hit testing, not from page
 * JavaScript: the page cannot spoof the URL the dialog shows or the image the
 * viewer opens.
 */
@RunWith(AndroidJUnit4::class)
class RYWebViewHitTestTest {

    private var webView: HorizontalScrollAwareWebView? = null
    private var scenario: ActivityScenario<WebViewTestActivity>? = null
    private val openedLinks = CopyOnWriteArrayList<String>()
    private val linkLongPresses = CopyOnWriteArrayList<Pair<String, String>>()
    private val imageClicks =
        CopyOnWriteArrayList<Triple<String, String, String>>()

    @After
    fun tearDown() {
        scenario?.onActivity {
            webView?.destroy()
        }
        scenario?.close()
        webView = null
        scenario = null
        openedLinks.clear()
        linkLongPresses.clear()
        imageClicks.clear()
    }

    @Test
    fun longPressOnLinkReportsHrefAndAnchorText() {
        loadArticle()

        val center = awaitElementCenter("#l1")
        injectLongPress(center.x, center.y)

        assertTrue("Expected a long-press callback", awaitTrue { linkLongPresses.isNotEmpty() })
        assertEquals("https://other.example.com/page", linkLongPresses.first().first)
        assertEquals("Anchor Text Here", linkLongPresses.first().second)
    }

    /**
     * A link wrapped around an image is a link: the dialog must show the
     * anchor's href, not the image URL that hitTestResult carries.
     */
    @Test
    fun longPressOnLinkWrappedImageReportsAnchorUrl() {
        loadArticle()

        val center = awaitElementCenter("#la")
        injectLongPress(center.x, center.y)

        assertTrue("Expected a long-press callback", awaitTrue { linkLongPresses.isNotEmpty() })
        val url = linkLongPresses.first().first
        assertEquals("https://other.example.com/wrapped", url)
        assertNotEquals(SVG_RED_200, url)
    }

    @Test
    fun tapOnBareImageOpensViewerWithMetadata() {
        loadArticle()
        settleBeforeGesture()

        val center = awaitElementCenter("#im1")
        injectTap(center.x, center.y)

        assertTrue("Expected an image click callback", awaitTrue { imageClicks.isNotEmpty() })
        val (imgUrl, alt, caption) = imageClicks.first()
        assertEquals(SVG_BLUE_200, imgUrl)
        assertEquals("Alt text", alt)
        assertEquals("The caption", caption)
    }

    /**
     * Images retried through the referer fallback keep the original URL in
     * dataset.rySrc (see ImageRefererRetry); the viewer must be handed that
     * original instead of the engine's hit test result.
     */
    @Test
    fun tapOnImageWithRefererRetryReportsOriginalUrl() {
        loadArticle()
        settleBeforeGesture()

        evalJs(
            "(function(){var img=document.querySelector('#im1');" +
                "img.dataset.rySrc='https://orig.example.com/pic.jpg';" +
                "return 'ok';})()"
        )
        val center = awaitElementCenter("#im1")
        injectTap(center.x, center.y)

        assertTrue("Expected an image click callback", awaitTrue { imageClicks.isNotEmpty() })
        assertEquals("https://orig.example.com/pic.jpg", imageClicks.first().first)
    }

    /** A long press that moves beyond the touch slop is a scroll, not an inspect gesture. */
    @Test
    fun longPressDuringHorizontalDragDoesNotFire() {
        loadArticle()

        val center = awaitElementCenter("#l1")
        injectDragThenHold(center.x, center.y)

        assertTrue(
            "Expected no long-press callback during horizontal drag",
            awaitTimeout { linkLongPresses.isEmpty() },
        )
        assertTrue(
            "Expected no image click callback during horizontal drag",
            awaitTimeout { imageClicks.isEmpty() },
        )
    }

    /** Tapping a link must still navigate, exactly as before the change. */
    @Test
    fun tapOnLinkStillNavigates() {
        loadArticle()

        val center = awaitElementCenter("#l1")
        injectTap(center.x, center.y)

        assertTrue("Expected the link to be handed to the app", awaitTrue { openedLinks.isNotEmpty() })
        assertEquals("https://other.example.com/page", openedLinks.first())
    }

    private fun loadArticle() {
        val content = """
        <p>Intro paragraph with <a id="l1" href="https://other.example.com/page">Anchor Text Here</a> in it.</p>
        <p><a id="la" href="https://other.example.com/wrapped"><img id="ima" alt="Wrapped" src="$SVG_RED_200"></a></p>
        <figure><img id="im1" alt="Alt text" src="$SVG_BLUE_200"><figcaption>The caption</figcaption></figure>
        ${"<p>Filler paragraph to give the document some height.</p>".repeat(20)}
        """

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
            webView =
                HorizontalScrollAwareWebView(activity).apply {
                    settings.javaScriptEnabled = true
                    webViewClient = client
                    onLinkLongPress = { url, text -> linkLongPresses.add(url to text) }
                    onImageClick = { imgUrl, alt, caption ->
                        imageClicks.add(Triple(imgUrl, alt, caption))
                    }
                }
            activity.setContentView(webView)
            webView!!.loadDataWithBaseURL(BASE_URL, html, "text/html", "UTF-8", null)
        }

        assertTrue("Document never finished loading", awaitDocumentReady())
        // The engine only reports IMAGE_TYPE hit tests for loaded images.
        assertTrue("Images never finished loading", awaitImagesLoaded())
    }

    private fun awaitDocumentReady(): Boolean =
        awaitTrue {
            evalJs("String(document.readyState)")?.trim('"') == "complete"
        }

    private fun awaitImagesLoaded(): Boolean =
        awaitTrue {
            evalJs(
                "String(Array.from(document.images).every(function(i){return i.complete;}))"
            )?.trim('"') == "true"
        }

    /**
     * The center of an element in WebView view coordinates. getBoundingClientRect
     * is in CSS pixels; with the fixed `initial-scale=1` viewport the view scale
     * factor is the display density.
     */
    private fun awaitElementCenter(selector: String): PointF {
        var center: PointF? = null
        awaitTrue {
            center = elementCenter(selector)
            center != null && center!!.x > 0f && center!!.y > 0f
        }
        return center ?: throw AssertionError("Element not found or not laid out: $selector")
    }

    private fun elementCenter(selector: String): PointF? {
        val coords =
            evalJs(
                "(function(){var e=document.querySelector('$selector');" +
                    "if(!e) return ''; var r=e.getBoundingClientRect();" +
                    "return '' + (r.left + r.width/2) + ',' + (r.top + r.height/2);})()"
            )
                ?.trim('"')
                ?.split(',') ?: return null
        if (coords.size != 2) return null
        val x = coords[0].toFloatOrNull() ?: return null
        val y = coords[1].toFloatOrNull() ?: return null
        val density =
            InstrumentationRegistry.getInstrumentation().targetContext.resources
                .displayMetrics.density
        return PointF(x * density, y * density)
    }

    private fun dispatchTouchAsync(action: Int, x: Float, y: Float, downTime: Long) {
        val eventTime = SystemClock.uptimeMillis()
        Handler(Looper.getMainLooper()).post {
            webView?.dispatchTouchEvent(
                MotionEvent.obtain(downTime, eventTime, action, x, y, 0)
            )
        }
    }

    private fun injectLongPress(x: Float, y: Float) {
        val downTime = SystemClock.uptimeMillis()
        dispatchTouchAsync(MotionEvent.ACTION_DOWN, x, y, downTime)
        SystemClock.sleep(LONG_PRESS_HOLD_MS)
        dispatchTouchAsync(MotionEvent.ACTION_UP, x, y, downTime)
        SystemClock.sleep(100)
    }

    private fun injectTap(x: Float, y: Float) {
        val downTime = SystemClock.uptimeMillis()
        dispatchTouchAsync(MotionEvent.ACTION_DOWN, x, y, downTime)
        SystemClock.sleep(TAP_DURATION_MS)
        dispatchTouchAsync(MotionEvent.ACTION_UP, x, y, downTime)
        SystemClock.sleep(100)
    }

    /** Lets the renderer settle like a real user would before tapping. */
    private fun settleBeforeGesture() = SystemClock.sleep(800)

    private fun injectDragThenHold(x: Float, y: Float) {
        val downTime = SystemClock.uptimeMillis()
        val density =
            InstrumentationRegistry.getInstrumentation().targetContext.resources
                .displayMetrics.density
        dispatchTouchAsync(MotionEvent.ACTION_DOWN, x, y, downTime)
        SystemClock.sleep(50)
        var dx = 0f
        for (i in 1..DRAG_STEP_COUNT) {
            dx += density * 10
            dispatchTouchAsync(MotionEvent.ACTION_MOVE, x + dx, y, downTime)
            SystemClock.sleep(30)
        }
        SystemClock.sleep(LONG_PRESS_HOLD_MS)
        dispatchTouchAsync(MotionEvent.ACTION_UP, x + dx, y, downTime)
        SystemClock.sleep(100)
    }

    private fun evalJs(script: String): String? {
        val latch = java.util.concurrent.CountDownLatch(1)
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

    private fun awaitTrue(condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(POLL_INTERVAL_MS)
        }
        return false
    }

    /** Waits out the full timeout window before checking a must-stay-false condition. */
    private fun awaitTimeout(condition: () -> Boolean): Boolean {
        Thread.sleep(TIMEOUT_MS)
        return condition()
    }

    private companion object {
        const val BASE_URL = "https://example.com/post/"
        const val TIMEOUT_MS = 5_000L
        const val POLL_INTERVAL_MS = 50L
        const val TAP_DURATION_MS = 60L
        const val LONG_PRESS_HOLD_MS = 900L
        const val DRAG_STEP_COUNT = 8

        /**
         * A 200x200 red SVG. Must be a real, loaded image with a distinct URL:
         * the engine only reports IMAGE_TYPE hit tests for loaded images, and
         * the metadata lookup tells the page's images apart by URL.
         */
        const val SVG_RED_200 =
            "data:image/svg+xml;base64,PHN2ZyB4bWxucz0naHR0cDovL3d3dy53My5vcmcvMjAwMC9zdmcnIHdpZHRoPScyMDAnIGhlaWdodD0nMjAwJz48cmVjdCB3aWR0aD0nMjAwJyBoZWlnaHQ9JzIwMCcgZmlsbD0ncmVkJy8+PC9zdmc+"

        /** A 200x200 blue SVG, distinct from [SVG_RED_200]. */
        const val SVG_BLUE_200 =
            "data:image/svg+xml;base64,PHN2ZyB4bWxucz0naHR0cDovL3d3dy53My5vcmcvMjAwMC9zdmcnIHdpZHRoPScyMDAnIGhlaWdodD0nMjAwJz48cmVjdCB3aWR0aD0nMjAwJyBoZWlnaHQ9JzIwMCcgZmlsbD0nYmx1ZScvPjwvc3ZnPg=="
    }
}
