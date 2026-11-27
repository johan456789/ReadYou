package me.ash.reader.ui.component.webview

/**
 * Blogs commonly wrap article images in a link to the full-size file ("click
 * to enlarge"). Such a link means "show me this image", which the in-app
 * viewer does better than a browser tab; a link to anything else (a video
 * page, a gallery, a product) is a real link and keeps opening as one.
 */
object LinkedImage {

    private val IMAGE_EXTENSIONS =
        setOf("jpg", "jpeg", "png", "gif", "webp", "avif", "bmp", "svg", "heic", "heif", "jxl", "apng")

    /** True when [href], wrapped around the image [imgSrc], points at an image file. */
    fun targetsImage(href: String, imgSrc: String): Boolean {
        if (!href.startsWith("http://", ignoreCase = true) &&
            !href.startsWith("https://", ignoreCase = true)
        ) {
            return false
        }
        if (href == imgSrc || href == ImageRefererRetry.unmark(imgSrc)) return true
        val afterScheme = href.substringAfter("://").substringBefore('#').substringBefore('?')
        val path = afterScheme.substringAfter('/', missingDelimiterValue = "")
        val lastSegment = path.substringAfterLast('/')
        val extension = lastSegment.substringAfterLast('.', missingDelimiterValue = "")
        return extension.lowercase() in IMAGE_EXTENSIONS
    }
}
