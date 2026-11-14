package me.ash.reader.ui.page.home.reading

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.content.ContextCompat
import androidx.core.view.HapticFeedbackConstantsCompat
import coil.compose.AsyncImagePainter
import coil.compose.rememberAsyncImagePainter
import coil.request.ImageRequest
import kotlinx.coroutines.delay
import me.ash.reader.R
import me.ash.reader.ui.component.webview.ImageSnapshot
import me.ash.reader.ui.ext.originReferer
import me.ash.reader.ui.ext.showToast
import me.saket.telephoto.flick.FlickToDismiss
import me.saket.telephoto.flick.FlickToDismissState
import me.saket.telephoto.flick.rememberFlickToDismissState
import me.saket.telephoto.zoomable.ZoomSpec
import me.saket.telephoto.zoomable.ZoomableContentLocation
import me.saket.telephoto.zoomable.rememberZoomableState
import me.saket.telephoto.zoomable.zoomable
import kotlin.math.min
import kotlin.math.roundToInt

data class ImageData(val imageUrl: String = "", val altText: String = "", val caption: String = "")

@Composable
fun ReaderImageViewer(
    imageData: ImageData,
    snapshot: ImageSnapshot?,
    onDownloadImage: (String) -> Unit,
    onDismissRequest: () -> Unit = {},
) {
    Dialog(
        onDismissRequest = onDismissRequest,
        properties =
            DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        val view = LocalView.current
        val context = LocalContext.current

        val dialogWindowProvider = view.parent as? DialogWindowProvider
        // Fully transparent window dim: the content below draws its own fading
        // black background so the article underneath is revealed while dragging.
        dialogWindowProvider?.window?.setDimAmount(0f)
        // No platform window enter/exit animation either: the stock fade blends
        // the article (its inline image included) with the viewer's already
        // rendered image for ~250ms - a stretched double-image flash whenever
        // the inline rect differs from the fitted rect. The dialog's first
        // frame already seats the fly-in slice exactly over the article image,
        // so an instant cut is seamless, and the fly-in carries the motion.
        dialogWindowProvider?.window?.setWindowAnimations(0)

        val zoomableState = rememberZoomableState(zoomSpec = ZoomSpec(maxZoomFactor = 4f))
        val flickState = rememberFlickToDismissState()
        val gestureState = flickState.gestureState

        // The flick animation keeps running while the dialog exits; schedule the
        // actual dismiss slightly before it finishes to hide navigation latency.
        if (gestureState is FlickToDismissState.GestureState.Dismissing) {
            LaunchedEffect(Unit) {
                delay(gestureState.animationDuration / 2)
                onDismissRequest()
            }
        }

        // If the user starts a flick while zoomed in, reset zoom so the image
        // settles back to its unzoomed size while following the finger.
        if (gestureState is FlickToDismissState.GestureState.Dragging) {
            LaunchedEffect(Unit) {
                zoomableState.resetZoom()
            }
        }

        val painter =
            rememberAsyncImagePainter(
                ImageRequest.Builder(LocalContext.current)
                    .apply {
                        imageData.imageUrl.originReferer()?.let { addHeader("Referer", it) }
                    }
                    .data(data = imageData.imageUrl)
                    .build()
            )
        val painterState = painter.state
        val loadedPainter = (painterState as? AsyncImagePainter.State.Success)?.painter

        // The full image fades in over the snapshot instead of cutting to it.
        // Usually it lands during the fly-in, so the never-on-screen remainder
        // of a clipped image grows in while the photo is still moving; a late
        // download fades in over the settled slice.
        val revealAlpha = remember { Animatable(0f) }
        LaunchedEffect(loadedPainter != null) {
            if (loadedPainter != null) {
                revealAlpha.animateTo(1f, tween(durationMillis = 150, easing = LinearEasing))
            }
        }

        // The full image's pixel size. The DOM reports it for any loaded <img>,
        // so it is known before the viewer's own download finishes - which is
        // what lets the fly-in land on the exact rect the full-res image will
        // occupy. The painter's intrinsic size is the fallback (and covers the
        // no-snapshot path).
        val contentSize =
            snapshot?.naturalSize?.takeIf { it[0] > 0 && it[1] > 0 }
                ?.let { Size(it[0].toFloat(), it[1].toFloat()) }
                ?: painter.intrinsicSize.takeIf {
                    it != Size.Unspecified && it.width > 0f && it.height > 0f
                }
        LaunchedEffect(contentSize) {
            contentSize?.let {
                zoomableState.setContentLocation(
                    ZoomableContentLocation.scaledInsideAndCenterAligned(it)
                )
            }
        }

        // Where the captured slice sits inside the full image, as fractions of
        // the element rect. The element lays out the whole image, so these map
        // the on-screen crop onto any rect the full image occupies - the slice
        // flies in at its true position and the swap never shifts framing.
        val slice = snapshot?.let { sliceFractions(it) }

        // Open animation: the scrim fades in while the captured slice flies
        // from its article rect to the fitted fullscreen rect. Without a
        // snapshot (capture failure) there is nothing to fly, so settle
        // immediately and keep the plain async-load behavior.
        var settled by remember {
            mutableStateOf(snapshot == null || contentSize == null || slice == null)
        }
        val openProgress = remember { Animatable(if (settled) 1f else 0f) }
        LaunchedEffect(Unit) {
            if (!settled) {
                openProgress.animateTo(
                    1f,
                    tween(durationMillis = 250, easing = FastOutSlowInEasing),
                )
                settled = true
            }
        }

        // Background fades in during the first ~100ms of the fly-in - much
        // faster than the rect animation. A slower fade would leave the
        // article's own copy of the image visible under the semi-transparent
        // scrim while the slice travels, reading as a double exposure. It then
        // fades out continuously as the photo follows the finger.
        val backgroundAlpha =
            min(openProgress.value / 0.4f, 1f) * (1f - flickState.offsetFraction)

        var expanded by remember { mutableStateOf(false) }

        // Tapping the photo hides the top bar and caption instead of dismissing
        // the viewer; tapping again brings them back.
        var chromeVisible by remember { mutableStateOf(true) }
        val chromeAlpha by
            animateFloatAsState(
                targetValue = if (chromeVisible) 1f else 0f,
                label = "chromeAlpha",
            )

        // Surface the failure once; the snapshot (if any) stays visible.
        var loadErrorToasted by remember { mutableStateOf(false) }
        LaunchedEffect(painterState) {
            if (painterState is AsyncImagePainter.State.Error && !loadErrorToasted) {
                loadErrorToasted = true
                context.showToast(context.getString(R.string.image_load_failed))
            }
        }

        Box(
            modifier =
                Modifier.fillMaxSize().background(Color.Black.copy(alpha = backgroundAlpha))
        ) {
            // The zoomable photo is composed from the very first frame, hidden
            // until the fly-in lands. Mounting it only at the hand-off left its
            // first frame to a fresh FlickToDismiss/zoomable layout whose
            // transform was not yet settled: the photo drew stretched to the
            // screen height for one frame - the "stretched image" flash, most
            // visible when the full-res image had already loaded. Laid out
            // behind the fly-in, it is stable by the time it is revealed.
            // Only the photo lives inside FlickToDismiss so that only it follows
            // the finger. The top bar below stays pinned in place.
            FlickToDismiss(
                state = flickState,
                modifier =
                    Modifier.fillMaxSize().graphicsLayer { alpha = if (settled) 1f else 0f },
            ) {
                Box(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .zoomable(
                                state = zoomableState,
                                clipToBounds = true,
                                onClick = {
                                    chromeVisible = !chromeVisible
                                    if (!chromeVisible) expanded = false
                                },
                                onLongClick = {
                                    chromeVisible = true
                                    expanded = true
                                },
                            )
                ) {
                    val currentSnapshot = snapshot
                    val currentSlice = slice
                    if (currentSnapshot != null && currentSlice != null) {
                        // Until the full-res image has faded in, paint the captured
                        // slice at its position inside the fitted rect; the
                        // never-on-screen remainder stays the dark scrim.
                        Canvas(Modifier.fillMaxSize()) {
                            if (revealAlpha.value >= 1f) return@Canvas
                            val fit = fittedRect(contentSize ?: return@Canvas, size)
                            drawSlice(currentSnapshot, currentSlice.applyTo(fit))
                        }
                    }
                    // ContentScale.Inside + center draws the full image at the
                    // same fitted rect the fly-in computed, so the swap is
                    // pixel-aligned: sharpness changes, framing does not.
                    Image(
                        painter = painter,
                        contentDescription = imageData.altText,
                        modifier =
                            Modifier.fillMaxSize().graphicsLayer { alpha = revealAlpha.value },
                        alignment = Alignment.Center,
                        contentScale = ContentScale.Inside,
                    )
                }
            }
            if (!settled) {
                // Fly-in: the captured slice rides inside the interpolating
                // full-image rect. The element rect (possibly extending past the
                // screen edges) shrinks/moves onto the fitted rect; the slice's
                // viewport-relative crop lands exactly where the article showed it.
                val currentSnapshot = snapshot
                val currentSize = contentSize
                val currentSlice = slice
                if (currentSnapshot != null && currentSize != null && currentSlice != null) {
                    Canvas(Modifier.fillMaxSize()) {
                        val full =
                            lerpRect(
                                currentSnapshot.elementRect.toComposeRect(),
                                fittedRect(currentSize, size),
                                openProgress.value,
                            )
                        drawSlice(currentSnapshot, currentSlice.applyTo(full))
                        // Same layering as the settled photo: the full image fades
                        // in on top, inside the same moving rect.
                        val reveal = revealAlpha.value
                        if (loadedPainter != null && reveal > 0f) {
                            translate(full.left, full.top) {
                                with(loadedPainter) { draw(full.size, alpha = reveal) }
                            }
                        }
                    }
                }
            }

            val launcher =
                rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestPermission(),
                    onResult = { result ->
                        if (result) {
                            onDownloadImage(imageData.imageUrl)
                        } else {
                            context.showToast(context.getString(R.string.permission_denied))
                        }
                    },
                )

            Row(
                modifier =
                    Modifier.fillMaxWidth()
                        .safeDrawingPadding()
                        .align(Alignment.TopCenter)
                        .graphicsLayer { alpha = backgroundAlpha * chromeAlpha },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = {
                        view.performHapticFeedback(HapticFeedbackConstantsCompat.KEYBOARD_TAP)
                        onDismissRequest()
                    },
                    enabled = chromeVisible,
                    colors =
                        IconButtonDefaults.iconButtonColors(
                            containerColor = Color.Black.copy(alpha = 0.5f),
                            contentColor = Color.White,
                        ),
                    modifier = Modifier.padding(4.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Close,
                        contentDescription = stringResource(id = R.string.close),
                    )
                }

                Spacer(Modifier.weight(1f))

                Box(modifier = Modifier.wrapContentSize(Alignment.TopEnd)) {
                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        DropdownMenuItem(
                            text = { Text(text = stringResource(id = R.string.save)) },
                            onClick = {
                                val isStoragePermissionGranted =
                                    ContextCompat.checkSelfPermission(
                                        context,
                                        Manifest.permission.WRITE_EXTERNAL_STORAGE,
                                    ) == PackageManager.PERMISSION_GRANTED

                                if (Build.VERSION.SDK_INT > 28 || isStoragePermissionGranted) {
                                    onDownloadImage(imageData.imageUrl)
                                } else {
                                    launcher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                                }
                                expanded = false
                            },
                        )
                    }
                    IconButton(
                        onClick = { expanded = true },
                        enabled = chromeVisible,
                        colors =
                            IconButtonDefaults.iconButtonColors(
                                containerColor = Color.Black.copy(alpha = 0.5f),
                                contentColor = Color.White,
                            ),
                        modifier = Modifier.padding(4.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.MoreHoriz,
                            contentDescription = stringResource(id = R.string.more),
                        )
                    }
                }
            }

            if (imageData.caption.isNotBlank()) {
                Text(
                    text = imageData.caption,
                    modifier =
                        Modifier.align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .graphicsLayer { alpha = backgroundAlpha * chromeAlpha }
                            .navigationBarsPadding()
                            .padding(horizontal = 24.dp, vertical = 16.dp),
                    color = Color.White,
                    style =
                        MaterialTheme.typography.bodyMedium.copy(
                            shadow =
                                Shadow(
                                    color = Color.Black.copy(alpha = 0.8f),
                                    offset = Offset(0f, 2f),
                                    blurRadius = 8f,
                                )
                        ),
                    textAlign = TextAlign.Center,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private fun DrawScope.drawSlice(snapshot: ImageSnapshot, dst: Rect) {
    drawImage(
        image = snapshot.image,
        dstOffset = IntOffset(dst.left.roundToInt(), dst.top.roundToInt()),
        dstSize = IntSize(dst.width.roundToInt(), dst.height.roundToInt()),
    )
}

/** Where the captured slice sits inside the full image, as fractions of the element rect. */
private data class SliceFractions(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    /** Maps [full] (the rect the full image currently occupies) onto the slice's sub-rect. */
    fun applyTo(full: Rect): Rect =
        Rect(
            full.left + (full.right - full.left) * left,
            full.top + (full.bottom - full.top) * top,
            full.left + (full.right - full.left) * right,
            full.top + (full.bottom - full.top) * bottom,
        )
}

private fun sliceFractions(snapshot: ImageSnapshot): SliceFractions? {
    val element = snapshot.elementRect
    val visible = snapshot.visibleRect
    if (element.width() <= 0 || element.height() <= 0 || visible.width() <= 0 || visible.height() <= 0) {
        return null
    }
    return SliceFractions(
        left = (visible.left - element.left).toFloat() / element.width(),
        top = (visible.top - element.top).toFloat() / element.height(),
        right = (visible.right - element.left).toFloat() / element.width(),
        bottom = (visible.bottom - element.top).toFloat() / element.height(),
    )
}

/** Fit [content] inside [bounds], centered, never upscaling - the same semantics
 *  as ContentScale.Inside and telephoto's scaledInsideAndCenterAligned, so the
 *  fly-in lands exactly where the full-res Image will draw. */
private fun fittedRect(content: Size, bounds: Size): Rect {
    if (bounds.width <= 0f || bounds.height <= 0f || content.width <= 0f || content.height <= 0f) {
        return Rect.Zero
    }
    val scale = min(bounds.width / content.width, bounds.height / content.height).coerceAtMost(1f)
    val width = content.width * scale
    val height = content.height * scale
    val left = (bounds.width - width) / 2f
    val top = (bounds.height - height) / 2f
    return Rect(left, top, left + width, top + height)
}

private fun lerpRect(start: Rect, stop: Rect, fraction: Float): Rect =
    Rect(
        lerp(start.left, stop.left, fraction),
        lerp(start.top, stop.top, fraction),
        lerp(start.right, stop.right, fraction),
        lerp(start.bottom, stop.bottom, fraction),
    )

private fun android.graphics.Rect.toComposeRect(): Rect =
    Rect(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat())
