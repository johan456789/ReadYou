package me.ash.reader.ui.component.webview

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.View
import androidx.compose.ui.graphics.ImageBitmap
import timber.log.Timber

/**
 * Screen-density pixels of the tapped article image, captured from the live
 * WebView surface the moment the tap is resolved. Lets the fullscreen viewer
 * paint the exact pixels the user was looking at while the full-resolution
 * copy downloads, instead of a blank page.
 *
 * Rects are in the hosting window's coordinate space (physical pixels) - the
 * space PixelCopy captures in, and the same space the fullscreen viewer dialog
 * draws in.
 */
data class ImageSnapshot(
    val image: ImageBitmap,
    /**
     * The image element's layout rect on screen. May extend past the screen
     * edges when the element is clipped by the viewport; the viewer uses it
     * together with [visibleRect] to place the captured slice at its true
     * position inside the full image.
     */
    val elementRect: Rect,
    /** The portion of [elementRect] that was actually captured (on screen). */
    val visibleRect: Rect,
    /** The full image's intrinsic pixel size, when the DOM could report it. */
    val naturalSize: IntArray?,
) {
    override fun equals(other: Any?): Boolean =
        other is ImageSnapshot &&
            image == other.image &&
            elementRect == other.elementRect &&
            visibleRect == other.visibleRect &&
            naturalSize.contentEquals(other.naturalSize)

    override fun hashCode(): Int =
        31 * (31 * image.hashCode() + elementRect.hashCode()) +
            31 * visibleRect.hashCode() +
            (naturalSize?.contentHashCode() ?: 0)
}

/**
 * Captures [windowRect] of the window hosting this view into a bitmap and
 * hands it to [onCaptured] on the main thread (null on failure). Asynchronous;
 * usually completes within a frame.
 */
fun View.captureWindowRegion(windowRect: Rect, onCaptured: (Bitmap?) -> Unit) {
    val activity = context.findActivity()
    if (activity == null || windowRect.isEmpty || windowRect.width() <= 0 || windowRect.height() <= 0) {
        onCaptured(null)
        return
    }
    val bitmap =
        Bitmap.createBitmap(windowRect.width(), windowRect.height(), Bitmap.Config.ARGB_8888)
    try {
        PixelCopy.request(
            activity.window,
            windowRect,
            bitmap,
            { result ->
                onCaptured(if (result == PixelCopy.SUCCESS) bitmap else null)
            },
            Handler(Looper.getMainLooper()),
        )
    } catch (e: Exception) {
        Timber.tag("RYWebView").e(e, "PixelCopy capture failed")
        onCaptured(null)
    }
}

private tailrec fun Context?.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
