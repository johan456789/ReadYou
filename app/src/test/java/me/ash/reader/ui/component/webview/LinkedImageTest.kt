package me.ash.reader.ui.component.webview

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkedImageTest {

    private val thumb = "https://site.com/wp-content/uploads/photo-600x338.jpg"

    @Test
    fun `link to a larger copy targets the image`() {
        assertTrue(LinkedImage.targetsImage("https://site.com/wp-content/uploads/photo.jpg", thumb))
    }

    @Test
    fun `link to the same file targets the image`() {
        assertTrue(LinkedImage.targetsImage("https://cdn.example.com/pic", "https://cdn.example.com/pic"))
    }

    @Test
    fun `link to the original of a referer-retried image targets the image`() {
        val marked = "https://cdn.example.com/pic?${ImageRefererRetry.MARKER}"
        assertTrue(LinkedImage.targetsImage("https://cdn.example.com/pic", marked))
    }

    @Test
    fun `extension match ignores case, query and fragment`() {
        assertTrue(LinkedImage.targetsImage("https://site.com/a/IMG_01.JPEG?w=1600#top", thumb))
        assertTrue(LinkedImage.targetsImage("https://site.com/a/b.webp?x=1.html", thumb))
    }

    @Test
    fun `percent-encoded image url at the end of the path targets the image`() {
        val substack =
            "https://substackcdn.com/image/fetch/f_auto/https%3A%2F%2Fbucket.s3.amazonaws.com%2Fimages%2Fabc.png"
        assertTrue(LinkedImage.targetsImage(substack, thumb))
    }

    @Test
    fun `link to a web page does not target the image`() {
        assertFalse(LinkedImage.targetsImage("https://www.youtube.com/watch?v=abc", thumb))
        assertFalse(LinkedImage.targetsImage("https://site.com/2026/01/post/", thumb))
        assertFalse(LinkedImage.targetsImage("https://site.com/gallery.html", thumb))
    }

    @Test
    fun `extension in the host or a directory does not count`() {
        assertFalse(LinkedImage.targetsImage("https://images.png", thumb))
        assertFalse(LinkedImage.targetsImage("https://site.com/photo.jpg/comments", thumb))
    }

    @Test
    fun `non-http links never target the image`() {
        assertFalse(LinkedImage.targetsImage("mailto:me@example.com?subject=a.jpg", thumb))
        assertFalse(LinkedImage.targetsImage("javascript:show('a.png')", thumb))
        assertFalse(LinkedImage.targetsImage(thumb.replace("https", "ftp"), thumb))
    }
}
