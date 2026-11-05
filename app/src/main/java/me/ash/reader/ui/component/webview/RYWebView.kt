package me.ash.reader.ui.component.webview

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.SystemClock
import timber.log.Timber
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.webkit.WebChromeClient
import android.webkit.WebView
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONTokener
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import me.ash.reader.infrastructure.preference.LocalOpenLink
import me.ash.reader.infrastructure.preference.LocalOpenLinkSpecificBrowser
import me.ash.reader.infrastructure.preference.LocalReadingBoldCharacters
import me.ash.reader.infrastructure.preference.LocalReadingFonts
import me.ash.reader.infrastructure.preference.LocalReadingImageHorizontalPadding
import me.ash.reader.infrastructure.preference.LocalReadingImageRoundedCorners
import me.ash.reader.infrastructure.preference.LocalReadingPageTonalElevation
import me.ash.reader.infrastructure.preference.LocalReadingSubheadBold
import me.ash.reader.infrastructure.preference.LocalReadingSubheadUpperCase
import me.ash.reader.infrastructure.preference.LocalReadingTextAlign
import me.ash.reader.infrastructure.preference.LocalReadingTextBold
import me.ash.reader.infrastructure.preference.LocalReadingTextFontSize
import me.ash.reader.infrastructure.preference.LocalReadingTextHorizontalPadding
import me.ash.reader.infrastructure.preference.LocalReadingTextLetterSpacing
import me.ash.reader.infrastructure.preference.LocalReadingTextLineHeight
import me.ash.reader.infrastructure.preference.LocalReadingTitleAlign
import me.ash.reader.infrastructure.preference.LocalReadingTitleBold
import me.ash.reader.infrastructure.preference.LocalReadingTitleUpperCase
import me.ash.reader.infrastructure.preference.ReadingFontsPreference
import me.ash.reader.infrastructure.preference.ReadingTitleAlignPreference
import me.ash.reader.ui.ext.ExternalFonts
import me.ash.reader.ui.ext.formatAsString
import me.ash.reader.ui.ext.openURL
import me.ash.reader.ui.ext.surfaceColorAtElevation
import me.ash.reader.ui.theme.palette.alwaysLight

internal val LocalWebViewCreatedForTest = compositionLocalOf<((WebView) -> Unit)?> { null }

data class WebViewScrollSnapshot(
    val scrollY: Int,
    val maxScrollY: Int,
    val viewportHeight: Int = 0,
    val isAtTop: Boolean,
    val isAtBottom: Boolean,
)

/**
 * Custom WebView that detects horizontal gestures and tells parent views not to intercept.
 * HTML elements that scroll horizontally, media with native controls, and touch-action pan-y/none elements claim horizontal gestures.
 */
class HorizontalScrollAwareWebView(context: Context) : WebView(context) {
    private var startX = 0f
    private var startY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var isHorizontalGesture: Boolean? = null
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    var loadedContentKey: WebViewContentKey? = null
    var onScrollSnapshotChanged: ((WebViewScrollSnapshot) -> Unit)? = null
    var onHeadlineMeasured: ((Int) -> Unit)? = null
    var onImageClick: ((imgUrl: String, altText: String, caption: String) -> Unit)? = null
    var onLinkLongPress: ((url: String, text: String) -> Unit)? = null
    var handledScrollToTopRequest: Int = 0
    private val touchStartsInHorizontalScrollableContent = AtomicBoolean(false)
    private val touchStartsInMediaContent = AtomicBoolean(false)

    init {
        // Link and image gestures are sourced from native hit testing, which the
        // page cannot spoof: URLs come from the engine, never from page
        // JavaScript. No injected page script may hand URLs or text into the app.
        // Only real anchors open the dialog; EMAIL/PHONE/GEO hit types are the
        // engine's auto-detection of plain text, not links, and stay inert.
        isLongClickable = true
        setOnLongClickListener {
            val hit = hitTestResult
            // Both the hit test data and the focused-node info are computed
            // asynchronously by the engine and are often not yet populated
            // while the finger is still down. Ask right away (fast path for
            // when the data is already there), retry shortly, and ask once
            // more when the touch ends - by then the engine has settled and
            // the query is reliable. See dispatchTouchEvent.
            linkGestureTouch = touchSequence
            requestLinkHrefAsync()
            postDelayed({ retryLinkHrefQuery() }, LINK_NODE_RETRY_MS)
            when (hit.type) {
                WebView.HitTestResult.SRC_ANCHOR_TYPE,
                WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE -> true
                else -> false
            }
        }
    }

    /**
     * Asks the engine for the focused node's anchor URL and text. The reply
     * bundle carries "url" (the anchor href) and "title" (the anchor's visible
     * text). Must be called while the long-pressed node is still focused, i.e.
     * for the current touch sequence.
     *
     * Replies are deduplicated per touch sequence, so the fast path, the retry
     * and the gesture-end query can all be issued without double-firing.
     */
    private fun requestLinkHrefAsync() {
        val message = Message()
        message.target = object : Handler(Looper.getMainLooper()) {
            override fun handleMessage(msg: Message) {
                val bundle = msg.data
                val url = bundle?.getString("url")
                if (linkGestureTouch != touchSequence || linkGestureHandled) return
                if (!url.isNullOrEmpty()) {
                    linkGestureHandled = true
                    onLinkLongPress?.invoke(url, bundle.getString("title").orEmpty().trim())
                }
            }
        }
        // Deferred queries can outlive the view when the article is recycled
        // mid-gesture; a destroyed WebView must not be asked for node info.
        runCatching { requestFocusNodeHref(message) }
    }

    private fun retryLinkHrefQuery() {
        if (linkGestureTouch != touchSequence || linkGestureHandled) return
        requestLinkHrefAsync()
    }

    private var touchSequence = 0
    private var linkGestureTouch = -1
    private var linkGestureHandled = false

    private var tapDownX = 0f
    private var tapDownY = 0f
    private var tapDownEventTime = 0L
    private var tapCandidate = false
    private var gestureEndHitType = 0
    private var gestureEndHitExtra: String? = null
    private var downHitType = 0
    private var downHitExtra: String? = null

    /**
     * Tap detection for bare images. Tap is any gesture that stays within the
     * touch slop and ends before a long press would fire. Only
     * [WebView.HitTestResult.IMAGE_TYPE] opens the viewer; a link-wrapped image
     * is a link and navigates as usual.
     */
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val handled = super.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                tapDownX = event.x
                tapDownY = event.y
                tapDownEventTime = event.eventTime
                tapCandidate = true
            }
            MotionEvent.ACTION_POINTER_DOWN -> tapCandidate = false
            MotionEvent.ACTION_MOVE -> {
                if (tapCandidate &&
                    (abs(event.x - tapDownX) > touchSlop || abs(event.y - tapDownY) > touchSlop)
                ) {
                    tapCandidate = false
                }
            }
            MotionEvent.ACTION_UP -> {
                val isTap = tapCandidate &&
                    abs(event.x - tapDownX) <= touchSlop &&
                    abs(event.y - tapDownY) <= touchSlop &&
                    event.eventTime - tapDownEventTime < ViewConfiguration.getLongPressTimeout()
                tapCandidate = false
                if (isTap && !resolveImageTap()) {
                    // One more read a frame later: the hit test data may land
                    // just after the gesture ends.
                    val tappedSequence = touchSequence
                    postDelayed({
                        if (tappedSequence != touchSequence) return@postDelayed
                        runCatching {
                            val hit = hitTestResult
                            val extra = hit.extra
                            if (hit.type == WebView.HitTestResult.IMAGE_TYPE &&
                                !extra.isNullOrEmpty()
                            ) {
                                resolveImageMetadata(extra, tapDownX, tapDownY)
                            }
                        }
                    }, TAP_HIT_RETRY_MS)
                }
            }
            MotionEvent.ACTION_CANCEL -> tapCandidate = false
        }
        return handled
    }

    /**
     * Opens the image viewer for a tap when the engine reported a bare image,
     * preferring the freshest read of the hit test data. Returns false when
     * neither read saw an image, leaving the decision to the later retry.
     */
    private fun resolveImageTap(): Boolean {
        val type = gestureEndHitType.takeIf { it != 0 } ?: downHitType
        val extra = gestureEndHitExtra ?: downHitExtra
        if (type != WebView.HitTestResult.IMAGE_TYPE) return false
        val imgUrl = extra
        if (imgUrl.isNullOrEmpty()) return false
        resolveImageMetadata(imgUrl, tapDownX, tapDownY)
        return true
    }

    /**
     * Fills in what hit testing cannot know: which element among same-URL
     * twins was tapped, the un-rewritten image URL (images retried with a
     * referer marker keep the original in `dataset.rySrc`, see
     * [ImageRefererRetry]), plus the alt text and figcaption. Kotlin initiates
     * the pull, so the page only answers with data - the returned string is
     * decoded, never executed.
     *
     * Identity stays anchored to the engine: the page may only annotate an
     * image whose URL the engine reported, and `rySrc` is accepted solely when
     * the engine URL is exactly its referer-retry-marked form - a shape only
     * our retry flow can produce, so a page-writable attribute can never
     * override the engine's answer.
     */
    private fun resolveImageMetadata(imgUrl: String, tapViewX: Float, tapViewY: Float) {
        val density = resources.displayMetrics.density
        val cssX = tapViewX / density
        val cssY = tapViewY / density
        val script = """
            (function() {
                var u = ${JSONObject.quote(imgUrl)};
                function matches(e) {
                    return e.tagName === 'IMG' &&
                        (e.currentSrc === u || e.src === u);
                }
                // The engine's hit test repeated in DOM terms: the whole
                // element stack at the tap point, filtered by the engine URL,
                // so geometry can never introduce a different image.
                var hit = document.elementsFromPoint($cssX, $cssY).find(matches);
                if (!hit) {
                    // Fallback for layout shifts between tap and reply.
                    hit = Array.from(document.images).find(function(i) {
                        return i.currentSrc === u || i.src === u; });
                }
                if (!hit) return JSON.stringify({rySrc: null, alt: '', caption: ''});
                var fig = hit.closest('figure');
                var cap = fig ? fig.querySelector('figcaption') : null;
                return JSON.stringify({rySrc: hit.dataset.rySrc || null,
                                       alt: hit.alt || '', caption: cap ? cap.innerText : ''});
            })()
        """.trimIndent()
        runCatching {
            val gesture = touchSequence
            val contentKey = loadedContentKey
            evaluateJavascript(script) { result ->
                // The WebView may have moved on (new gesture, new article)
                // before the page answered; a stale reply opens a stale viewer.
                if (gesture != touchSequence || contentKey != loadedContentKey) {
                    return@evaluateJavascript
                }
                val (pageRySrc, altText, caption) = parseImageMetadata(result)
                val rySrc = pageRySrc
                    ?.takeIf { ImageRefererRetry.unmark(imgUrl) == it }
                onImageClick?.invoke(rySrc ?: imgUrl, altText, caption)
            }
        }
    }

    /** evaluateJavascript delivers the JSON string double-encoded; unwrap both layers. */
    private fun parseImageMetadata(raw: String?): Triple<String?, String, String> {
        val json = runCatching {
            JSONTokener(raw.orEmpty().trim()).nextValue() as? String
        }.getOrNull() ?: return Triple(null, "", "")
        val obj = runCatching { JSONObject(json) }.getOrNull() ?: return Triple(null, "", "")
        val rySrc = if (obj.isNull("rySrc")) null else obj.optString("rySrc").takeIf { it.isNotEmpty() }
        return Triple(rySrc, obj.optString("alt"), obj.optString("caption"))
    }

    fun setTouchStartsInHorizontalScrollableContent(isScrollable: Boolean) {
        touchStartsInHorizontalScrollableContent.set(isScrollable)
        if (isScrollable) {
            post {
                if (isHorizontalGesture == true && touchStartsInHorizontalScrollableContent.get()) {
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
            }
        }
    }

    fun setTouchStartsInMediaContent(isMedia: Boolean) {
        touchStartsInMediaContent.set(isMedia)
        if (isMedia) {
            post {
                if (isHorizontalGesture != null && touchStartsInMediaContent.get()) {
                    if (abs(lastX - startX) > touchSlop && abs(lastX - startX) > abs(lastY - startY) / 2f) {
                        isHorizontalGesture = true
                        parent?.requestDisallowInterceptTouchEvent(true)
                    }
                }
            }
        }
    }

    fun resetTouchStartsInHorizontalScrollableContent() {
        touchStartsInHorizontalScrollableContent.set(false)
        touchStartsInMediaContent.set(false)
    }

    fun emitScrollSnapshot() {
        val contentHeight = computeVerticalScrollRange()
        val viewportHeight = computeVerticalScrollExtent()
        val maxScrollY = (contentHeight - viewportHeight).coerceAtLeast(0)
        onScrollSnapshotChanged?.invoke(
            WebViewScrollSnapshot(
                scrollY = scrollY,
                maxScrollY = maxScrollY,
                viewportHeight = viewportHeight,
                isAtTop = scrollY <= 0,
                isAtBottom = scrollY >= maxScrollY,
            )
        )
    }

    override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
        super.onScrollChanged(l, t, oldl, oldt)
        if (!headlineMeasured && t > 0) {
            // Fallback: if the page-finish measurement never landed, measure on
            // first real scroll so the top-bar title still has a threshold.
            headlineMeasured = true
            runHeadlineMeasure(settleCheckToken)
        }
        emitScrollSnapshot()
    }

    private var settleCheckToken = 0
    private var headlineMeasured = false

    companion object {
        private const val MEASURE_HEADLINE_JS =
            "(function(){var h=document.getElementById('ry-headline');" +
                "if(!h) return -1;" +
                "return Math.round((h.getBoundingClientRect().bottom + window.scrollY)" +
                " * window.devicePixelRatio);})()"

        /** Delay before the post-gesture focused-node query, letting the engine settle. */
        private const val LINK_NODE_SETTLE_MS = 30L

        /** Delay before the mid-hold retry of the focused-node query. */
        private const val LINK_NODE_RETRY_MS = 250L

        /** Delay before sampling the hit test data after a touch down. */
        private const val TAP_HIT_SAMPLE_MS = 20L

        /** Delay before the post-gesture retry of the image tap hit test. */
        private const val TAP_HIT_RETRY_MS = 150L

        /**
         * Silences every audible source in the page: pauses inline
         * `<audio>`/`<video>` and parks `<iframe>` embeds (YouTube etc.) by
         * pointing them at about:blank. Evaluating just `pause()` on media
         * elements is not enough - cross-origin embeds are separate documents
         * and keep playing. Parked frames are restored by [RESUME_EMBEDS_JS].
         */
        internal const val PAUSE_ALL_MEDIA_JS =
            "(function(){try{" +
                "var els=document.querySelectorAll('audio,video');" +
                "for(var i=0;i<els.length;i++){try{els[i].pause();}catch(e){}}" +
                "var ifs=document.querySelectorAll('iframe');" +
                "for(var j=0;j<ifs.length;j++){try{" +
                "var f=ifs[j];" +
                "if(f.dataset.ryParked!=='1'&&f.src&&f.src!=='about:blank'){" +
                "f.dataset.rySrc=f.src;f.dataset.ryParked='1';f.src='about:blank';}" +
                "}catch(e){}}" +
                "}catch(e){}})()"

        /** Reverses the iframe parking done by [PAUSE_ALL_MEDIA_JS]. */
        internal const val RESUME_EMBEDS_JS =
            "(function(){try{" +
                "var ifs=document.querySelectorAll('iframe');" +
                "for(var j=0;j<ifs.length;j++){try{" +
                "var f=ifs[j];" +
                "if(f.dataset.ryParked==='1'){f.src=f.dataset.rySrc||f.src;f.dataset.ryParked='0';}" +
                "}catch(e){}}" +
                "}catch(e){}})()"
    }

    override fun loadDataWithBaseURL(
        baseUrl: String?,
        data: String,
        mimeType: String?,
        encoding: String?,
        historyUrl: String?,
    ) {
        // New article invalidates any pending settle checks from the previous content.
        settleCheckToken++
        headlineMeasured = false
        loadStartedAtMs = android.os.SystemClock.elapsedRealtime()
        awaitingFirstDraw = true
        docState = "html"
        Timber.tag("RYPool")
            .d("load id=%08x chars=%d paused=%b", System.identityHashCode(this), data.length, mediaPaused)
        super.loadDataWithBaseURL(baseUrl, data, mimeType, encoding, historyUrl)
    }

    override fun loadUrl(url: String) {
        settleCheckToken++
        headlineMeasured = false
        loadStartedAtMs = android.os.SystemClock.elapsedRealtime()
        awaitingFirstDraw = false
        docState = url ?: "-"
        Timber.tag("RYPool").d("loadUrl id=%08x url=%s", System.identityHashCode(this), url)
        super.loadUrl(url)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        Timber.tag("RYPool").d("attach id=%08x", System.identityHashCode(this))
    }

    override fun onDetachedFromWindow() {
        Timber.tag("RYPool").d("detach id=%08x", System.identityHashCode(this))
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: android.graphics.Canvas) {
        super.onDraw(canvas)
        if (awaitingFirstDraw) {
            awaitingFirstDraw = false
            Timber.tag("RYPool").d(
                "firstDraw id=%08x msAfterLoad=%d paused=%b",
                System.identityHashCode(this),
                android.os.SystemClock.elapsedRealtime() - loadStartedAtMs,
                mediaPaused,
            )
        }
    }

    private var loadStartedAtMs = 0L
    fun msSinceLoad(): Long = android.os.SystemClock.elapsedRealtime() - loadStartedAtMs
    var awaitingFirstDraw = false
        private set
    var docState = "none"
        private set

    fun cancelPendingSettleCheck() {
        settleCheckToken++
    }

    /**
     * Stops audible media without destroying page state (scroll position and
     * DOM are preserved, unlike [WebViewLayout.recycle][me.ash.reader.ui.component.webview.WebViewLayout.recycle]).
     * Must be called on the UI thread.
     *
     * Deliberately does NOT call [android.webkit.WebView.onPause]: that stops the
     * renderer from presenting frames, so a page whose raster is still in flight
     * (large documents, images decoding) freezes half-painted - the pane shows
     * the top of the article and a blank bottom half until it is resumed. Media
     * silencing is done in-page instead, which leaves the renderer free to finish
     * painting.
     */
    fun pauseMediaPlayback() {
        mediaPaused = true
        Timber.tag("RYPool")
            .d(
                "pause id=%08x awaitingDraw=%b",
                System.identityHashCode(this),
                awaitingFirstDraw,
            )
        runCatching { evaluateJavascript(PAUSE_ALL_MEDIA_JS, null) }
    }

    /**
     * Counterpart to [pauseMediaPlayback]: lets a visible page play media again
     * and restores embeds parked by [PAUSE_ALL_MEDIA_JS]. Must be called on the
     * UI thread.
     */
    fun resumeMediaPlayback() {
        mediaPaused = false
        Timber.tag("RYPool")
            .d(
                "resume id=%08x awaitingDraw=%b",
                System.identityHashCode(this),
                awaitingFirstDraw,
            )
        runCatching { evaluateJavascript(RESUME_EMBEDS_JS, null) }
        runCatching { onResume() }
    }

    /**
     * Called once the WebView has presented the first frame of a freshly loaded
     * page. Media is silenced here too: a page that started playing audio or
     * video on its own before the slot was marked paused must not keep playing
     * under the visible article. The renderer is left running so the rest of the
     * document - images, lower tiles - can finish painting.
     */
    fun onPageCommittedVisible() {
        if (!mediaPaused) return
        runCatching { evaluateJavascript(PAUSE_ALL_MEDIA_JS, null) }
    }

    var mediaPaused = false
        private set

    /**
     * Called on page finish; the headline is text-only so its height is stable
     * regardless of article images below. Measure it (plus once more after web
     * fonts settle) so the top bar can show the title once it scrolls past.
     */
    fun notifyPageFinished() {
        val token = ++settleCheckToken
        post { runHeadlineMeasure(token) }
        postDelayed({ runHeadlineMeasure(token) }, 500)
        postDelayed({ runHeadlineMeasure(token) }, 1500)
        postDelayed({ runHeadlineMeasure(token) }, 3000)
    }

    private fun runHeadlineMeasure(token: Int) {
        if (token != settleCheckToken) return
        runCatching {
            evaluateJavascript(MEASURE_HEADLINE_JS) { result ->
                if (token != settleCheckToken) return@evaluateJavascript
                val px = result?.trim()?.trim('"')?.toDoubleOrNull()?.toInt()
                    ?: return@evaluateJavascript
                Timber.tag("RYWebView").d("headline measure raw=%s px=%d", result, px)
                if (px > 0) {
                    headlineMeasured = true
                    onHeadlineMeasured?.invoke(px)
                }
            }
        }
        emitScrollSnapshot()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startX = event.x
                startY = event.y
                isHorizontalGesture = null
                resetTouchStartsInHorizontalScrollableContent()
                touchSequence++
                linkGestureHandled = false
                gestureEndHitType = 0
                gestureEndHitExtra = null
                downHitType = 0
                downHitExtra = null
                // Sample the hit test just after the down settles. The engine
                // computes it asynchronously and may drop it again before the
                // touch ends, so the tap path keeps the earliest read.
                val sampledSequence = touchSequence
                postDelayed({
                    if (sampledSequence != touchSequence) return@postDelayed
                    runCatching {
                        val hit = hitTestResult
                        downHitType = hit.type
                        downHitExtra = hit.extra
                    }
                }, TAP_HIT_SAMPLE_MS)
            }
            MotionEvent.ACTION_MOVE -> {
                lastX = event.x
                lastY = event.y
                if (isHorizontalGesture == null) {
                    val dx = abs(event.x - startX)
                    val dy = abs(event.y - startY)
                    if (dx > touchSlop || dy > touchSlop) {
                        isHorizontalGesture =
                            if (touchStartsInMediaContent.get()) dx > touchSlop && dx > dy / 2f
                            else dx > dy
                        if (isHorizontalGesture == true && (touchStartsInHorizontalScrollableContent.get() || touchStartsInMediaContent.get())) {
                            parent?.requestDisallowInterceptTouchEvent(true)
                        }
                    }
                }
            }
            MotionEvent.ACTION_UP -> {
                // The engine may wipe its hit test data while processing the
                // release, so the last chance to read it for a tap is right
                // before that. A scrolled gesture is filtered out by the tap
                // check afterwards.
                val hit = hitTestResult
                gestureEndHitType = hit.type
                gestureEndHitExtra = hit.extra
                // A long-pressed link whose focused-node query came back empty
                // while the finger was down: the engine has settled the node by
                // the time the touch ends, so ask once more.
                scheduleLinkQueryOnGestureEnd()
                isHorizontalGesture = null
                resetTouchStartsInHorizontalScrollableContent()
            }
            MotionEvent.ACTION_CANCEL -> {
                scheduleLinkQueryOnGestureEnd()
                gestureEndHitType = 0
                gestureEndHitExtra = null
                isHorizontalGesture = null
                resetTouchStartsInHorizontalScrollableContent()
            }
        }
        return super.dispatchTouchEvent(event)
    }

    private fun scheduleLinkQueryOnGestureEnd() {
        if (linkGestureTouch != touchSequence || linkGestureHandled) return
        postDelayed({
            if (linkGestureTouch != touchSequence || linkGestureHandled) return@postDelayed
            requestLinkHrefAsync()
        }, LINK_NODE_SETTLE_MS)
    }
}

data class WebViewContentKey(val baseUrl: String, val html: String, val fontSize: Int)

/**
 * A rendered article document plus the exact inputs it was built from.
 *
 * [produceState] restarts after composition, so for the first frame after an
 * article change the produced value still holds the *previous* document while
 * the new inputs are already in scope. Pairing them lets the loader wait for
 * the rebuild instead of pushing the old article into a freshly primed pane.
 */
private data class HtmlDocument(val inputs: List<Any?>, val html: String)

@Composable
fun RYWebView(
    modifier: Modifier = Modifier,
    content: String,
    baseUrl: String? = null,
    headlineTitle: String = "",
    headlineFeedName: String = "",
    headlineAuthor: String? = null,
    publishedDate: java.util.Date? = null,
    scrollToTopRequest: Int = 0,
    onImageClick: ((imgUrl: String, altText: String, caption: String) -> Unit)? = null,
    onLinkLongPress: ((url: String, text: String) -> Unit)? = null,
    onHeadlineMeasured: ((Int) -> Unit)? = null,
    onShowCustomView: ((View, WebChromeClient.CustomViewCallback) -> Unit)? = null,
    onHideCustomView: (() -> Unit)? = null,
    onScrollSnapshotChange: ((WebViewScrollSnapshot) -> Unit)? = null,
    onWebViewCreated: ((HorizontalScrollAwareWebView?) -> Unit)? = null,
) {
    val context = LocalContext.current
    val openLink = LocalOpenLink.current
    val openLinkSpecificBrowser = LocalOpenLinkSpecificBrowser.current
    val tonalElevation = LocalReadingPageTonalElevation.current
    val backgroundColor =
        MaterialTheme.colorScheme.surfaceColorAtElevation(tonalElevation.value.dp).toArgb()
    val selectionTextColor = Color.Black.toArgb()
    val selectionBgColor = (MaterialTheme.colorScheme.tertiaryContainer alwaysLight true).toArgb()
    val textColor: Int = MaterialTheme.colorScheme.onSurfaceVariant.toArgb()
    val textBold: Boolean = LocalReadingTextBold.current.value
    val textAlign: String = LocalReadingTextAlign.current.toTextAlignCSS()
    val textMargin: Int = LocalReadingTextHorizontalPadding.current
    val boldTextColor: Int = MaterialTheme.colorScheme.onSurface.toArgb()
    val linkTextColor: Int = MaterialTheme.colorScheme.primary.toArgb()
    val subheadBold: Boolean = LocalReadingSubheadBold.current.value
    val subheadUpperCase: Boolean = LocalReadingSubheadUpperCase.current.value
    val readingFonts = LocalReadingFonts.current
    val fontSize: Int = LocalReadingTextFontSize.current
    val letterSpacing: Float = LocalReadingTextLetterSpacing.current
    val lineHeight: Float = LocalReadingTextLineHeight.current
    val imgMargin: Int = LocalReadingImageHorizontalPadding.current
    val imgBorderRadius: Int = LocalReadingImageRoundedCorners.current
    val codeTextColor: Int = MaterialTheme.colorScheme.tertiary.toArgb()
    val codeBgColor: Int =
        MaterialTheme.colorScheme.surfaceColorAtElevation((tonalElevation.value + 6).dp).toArgb()
    val boldCharacters = LocalReadingBoldCharacters.current
    val onWebViewCreatedForTest = LocalWebViewCreatedForTest.current
    val titleBold = LocalReadingTitleBold.current
    val titleUpperCase = LocalReadingTitleUpperCase.current
    val titleAlignCss =
        when (LocalReadingTitleAlign.current) {
            ReadingTitleAlignPreference.Center -> "center"
            ReadingTitleAlignPreference.End -> "end"
            ReadingTitleAlignPreference.Justify -> "justify"
            else -> "start"
        }
    val headlineTitleColor: Int = MaterialTheme.colorScheme.onSurface.toArgb()
    val headlineLabelColor: Int =
        MaterialTheme.colorScheme.outline.copy(alpha = .7f).toArgb()
    val textContentWidth = me.ash.reader.ui.component.reader.LocalTextContentWidth.current
    val density = androidx.compose.ui.platform.LocalDensity.current
    val contentMaxWidthPx =
        remember(textContentWidth, density) {
            with(density) { textContentWidth.roundToPx() }
        }
    val dateString =
        remember(context, publishedDate) {
            publishedDate?.formatAsString(context, atHourMinute = true).orEmpty()
        }

    val currentOpenLink by rememberUpdatedState(openLink)
    val currentOpenLinkSpecificBrowser by rememberUpdatedState(openLinkSpecificBrowser)
    val onScrollSnapshotChangeState by rememberUpdatedState(onScrollSnapshotChange)
    val onHeadlineMeasuredState by rememberUpdatedState(onHeadlineMeasured)
    val onWebViewCreatedState by rememberUpdatedState(onWebViewCreated)
    val dynamicWebViewClient = remember(context) {
        WebViewClient(
            context = context,
            onOpenLink = { url ->
                context.openURL(url, currentOpenLink, currentOpenLinkSpecificBrowser)
            },
        )
    }

    val onShowCustomViewState by rememberUpdatedState(onShowCustomView)
    val onHideCustomViewState by rememberUpdatedState(onHideCustomView)
    // New-window requests (`target="_blank"` in embeds, `window.open`) only
    // arrive through the chrome client, so it must be installed even on
    // screens that do not wire fullscreen video handling.
    val webChromeClient =
        remember(onShowCustomView != null, onHideCustomView != null) {
            RYWebChromeClient(
                onShowCustomViewCallback =
                    if (onShowCustomView == null) null
                    else {
                        { view, callback -> onShowCustomViewState?.invoke(view, callback) }
                    },
                onHideCustomViewCallback =
                    if (onHideCustomView == null) null else {
                        { onHideCustomViewState?.invoke() }
                    },
                onOpenLink = { url ->
                    context.openURL(url, currentOpenLink, currentOpenLinkSpecificBrowser)
                },
            )
        }

    val webView by
        remember {
            mutableStateOf(
                WebViewLayout.obtain(
                    context = context,
                    readingFontsPreference = readingFonts,
                    webViewClient = dynamicWebViewClient,
                    webChromeClient = null,
                    onImageClick = onImageClick,
                    onLinkLongPress = onLinkLongPress,
                ).also {
                    onWebViewCreatedForTest?.invoke(it)
                    onWebViewCreatedState?.invoke(it)
                }
            )
        }

    val fontPath =
        if (readingFonts is ReadingFontsPreference.External)
            ExternalFonts.FontType.ReadingFont.toPath(context)
        else if (readingFonts is ReadingFontsPreference.GoogleSans) {
            // Weight-only instance of res/font/google_sans_flex.ttf (~270 KB vs 4 MB):
            // the other variation axes are unused by the page but made every
            // cold load inflate and sanitize the full file.
            "/android_asset/fonts/google_sans_flex_web.ttf"
        } else null
    val fontUnicodeRange =
        if (readingFonts is ReadingFontsPreference.GoogleSans) {
            WebViewStyle.GOOGLE_SANS_UNICODE_RANGE
        } else null
    val htmlBaseUrl = baseUrl ?: "about:blank"
    val headlineHtml =
        remember(
            headlineTitle,
            headlineFeedName,
            headlineAuthor,
            dateString,
            htmlBaseUrl,
            titleUpperCase.value,
        ) {
            buildHeadlineHtml(
                title = headlineTitle,
                feedName = headlineFeedName,
                author = headlineAuthor,
                dateString = dateString,
                link = htmlBaseUrl,
                upperCaseTitle = titleUpperCase.value,
            )
        }
    val htmlInputs =
        listOf(
            content,
            headlineHtml,
            htmlBaseUrl,
            fontSize,
            fontPath,
            lineHeight,
            letterSpacing,
            textMargin,
            textColor,
            textBold,
            textAlign,
            boldTextColor,
            subheadBold,
            subheadUpperCase,
            imgMargin,
            imgBorderRadius,
            linkTextColor,
            codeTextColor,
            codeBgColor,
            selectionTextColor,
            selectionBgColor,
            headlineTitleColor,
            headlineLabelColor,
            titleBold.value,
            titleUpperCase.value,
            titleAlignCss,
            contentMaxWidthPx,
            boldCharacters.value,
        )
    val articleHtml by
        produceState<HtmlDocument?>(initialValue = null, htmlInputs) {
            val inputs = htmlInputs
            val buildStartedAtMs = SystemClock.elapsedRealtime()
            value = null
            val html =
                withContext(Dispatchers.Default) {
                    WebViewHtml.HTML.format(
                        WebViewStyle.get(
                            fontSize = fontSize,
                            fontPath = fontPath,
                            fontUnicodeRange = fontUnicodeRange,
                            lineHeight = lineHeight,
                            letterSpacing = letterSpacing,
                            textMargin = textMargin,
                            textColor = textColor,
                            textBold = textBold,
                            textAlign = textAlign,
                            boldTextColor = boldTextColor,
                            subheadBold = subheadBold,
                            subheadUpperCase = subheadUpperCase,
                            imgMargin = imgMargin,
                            imgBorderRadius = imgBorderRadius,
                            linkTextColor = linkTextColor,
                            codeTextColor = codeTextColor,
                            codeBgColor = codeBgColor,
                            tableMargin = textMargin,
                            selectionTextColor = selectionTextColor,
                            selectionBgColor = selectionBgColor,
                            titleColor = headlineTitleColor,
                            labelColor = headlineLabelColor,
                            titleBold = titleBold.value,
                            titleUpperCase = titleUpperCase.value,
                            titleAlign = titleAlignCss,
                            contentMaxWidthPx = contentMaxWidthPx,
                        ),
                        htmlBaseUrl,
                        headlineHtml,
                        WebViewHtml.allowIframePopups(content),
                        WebViewScript.get(boldCharacters.value),
                    )
                }
            value = HtmlDocument(inputs, html)
            Timber.tag("RYWebViewPerf").d(
                "html build in %d ms (%d chars)",
                SystemClock.elapsedRealtime() - buildStartedAtMs,
                html.length,
            )
        }

    DisposableEffect(Unit) {
        onDispose {
            // Clear the owner's reference first: the pooled WebView may be
            // handed to another slot afterwards, which then owns it.
            onWebViewCreatedState?.invoke(null)
            WebViewLayout.recycle(webView)
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { webView },
        update = { wv ->
            wv.onScrollSnapshotChanged = { snapshot -> onScrollSnapshotChangeState?.invoke(snapshot) }
            wv.onHeadlineMeasured = { px -> onHeadlineMeasuredState?.invoke(px) }
            dynamicWebViewClient.baseUrl = htmlBaseUrl
            if (wv.webViewClient !== dynamicWebViewClient) {
                wv.webViewClient = dynamicWebViewClient
            }
            wv.webChromeClient = webChromeClient
            wv.settings.defaultFontSize = fontSize
            wv.settings.standardFontFamily =
                when (readingFonts) {
                    ReadingFontsPreference.Cursive -> "cursive"
                    ReadingFontsPreference.Monospace -> "monospace"
                    ReadingFontsPreference.SansSerif -> "sans-serif"
                    ReadingFontsPreference.Serif -> "serif"
                    else -> "sans-serif"
                }
            val document = articleHtml ?: return@AndroidView
            // The html still belongs to the previous article / style; the rebuild
            // is on its way. Loading it now would prime this pane with the wrong
            // document and the real one would have to fight it for the surface.
            if (document.inputs != htmlInputs) return@AndroidView
            val html = document.html
            val contentKey = WebViewContentKey(htmlBaseUrl, html, fontSize)
            if (wv.loadedContentKey != contentKey) {
                wv.loadedContentKey = contentKey
                wv.loadDataWithBaseURL(
                    htmlBaseUrl,
                    html,
                    "text/HTML",
                    "UTF-8",
                    null,
                )
                wv.post { wv.emitScrollSnapshot() }
            }
            if (scrollToTopRequest != 0 && scrollToTopRequest != wv.handledScrollToTopRequest) {
                wv.handledScrollToTopRequest = scrollToTopRequest
                wv.post {
                    wv.scrollTo(0, 0)
                    wv.emitScrollSnapshot()
                }
            }
        },
    )
}

private fun String.escapeHtml(): String =
    replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

private fun buildHeadlineHtml(
    title: String,
    feedName: String,
    author: String?,
    dateString: String,
    link: String,
    upperCaseTitle: Boolean,
): String {
    if (title.isBlank() && feedName.isBlank()) return ""
    val displayTitle = (if (upperCaseTitle) title.uppercase() else title).escapeHtml()
    val titleHtml =
        if (displayTitle.isNotBlank() && link.isNotBlank() && link != "about:blank") {
            """<div class="ry-title" dir="auto"><a href="${link.escapeHtml()}">$displayTitle</a></div>"""
        } else if (displayTitle.isNotBlank()) {
            """<div class="ry-title" dir="auto">$displayTitle</div>"""
        } else {
            ""
        }
    val authorHtml =
        if (!author.isNullOrBlank()) {
            """<div class="ry-author" dir="auto">${author.escapeHtml()}</div>"""
        } else {
            ""
        }
    val feedHtml =
        if (feedName.isNotBlank()) {
            """<div class="ry-feed" dir="auto">${feedName.escapeHtml()}</div>"""
        } else {
            ""
        }
    val dateHtml =
        if (dateString.isNotBlank()) {
            """<div class="ry-date" dir="auto">${dateString.escapeHtml()}</div>"""
        } else {
            ""
        }
    return dateHtml + titleHtml + authorHtml + feedHtml
}
