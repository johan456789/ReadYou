package me.ash.reader.ui.component.webview

import org.junit.Assert.assertEquals
import org.junit.Test

class LinkActionDataTest {

    @Test
    fun `displayUrl returns full url when under maxLength`() {
        val data = LinkActionData("https://example.com")
        assertEquals("https://example.com", data.displayUrl(maxLength = 50))
    }

    @Test
    fun `displayUrl truncates long url with ellipsis`() {
        val data = LinkActionData("https://example.com/very/long/path/to/some/resource/page.html")
        val result = data.displayUrl(maxLength = 30)
        assertEquals(30, result.length)
        assert(result.endsWith("…"))
    }

    @Test
    fun `displayUrl handles exact maxLength`() {
        val data = LinkActionData("https://example.com")
        assertEquals("https://example.com", data.displayUrl(maxLength = 19))
    }

    @Test
    fun `displayUrl handles one over maxLength`() {
        val data = LinkActionData("https://example.com/")
        val result = data.displayUrl(maxLength = 19)
        assertEquals(19, result.length)
        assert(result.endsWith("…"))
    }

    @Test
    fun `displayUrl handles empty url`() {
        val data = LinkActionData("")
        assertEquals("", data.displayUrl(maxLength = 50))
    }

    @Test
    fun `displayUrl handles very short maxLength`() {
        val data = LinkActionData("https://example.com")
        val result = data.displayUrl(maxLength = 5)
        assertEquals(5, result.length)
        assertEquals("http…", result)
    }

    @Test
    fun `displayUrl with linkText shows linkText`() {
        val data = LinkActionData("https://example.com", "Click Here")
        assertEquals("Click Here", data.linkText)
    }

    @Test
    fun `default linkText is null`() {
        val data = LinkActionData("https://example.com")
        assertEquals(null, data.linkText)
    }

    @Test
    fun `fallbackTitle returns hostname without www`() {
        val data = LinkActionData("https://www.example.com/item?id=1")
        assertEquals("example.com", data.fallbackTitle())
    }

    @Test
    fun `fallbackTitle returns url when hostname is unavailable`() {
        val data = LinkActionData("not a url")
        assertEquals("not a url", data.fallbackTitle())
    }
}
