package me.ash.reader.ui.component.webview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LinkTitleResolverTest {

    @Test
    fun `extractTitle prefers og title`() {
        val html = """
            <html>
              <head>
                <meta property="og:title" content="Open Graph Title">
                <meta name="twitter:title" content="Twitter Title">
                <title>Document Title</title>
              </head>
            </html>
        """

        assertEquals("Open Graph Title", LinkTitleResolver.extractTitle(html))
    }

    @Test
    fun `extractTitle falls back to twitter title`() {
        val html = """
            <html>
              <head>
                <meta name="twitter:title" content="Twitter Title">
                <title>Document Title</title>
              </head>
            </html>
        """

        assertEquals("Twitter Title", LinkTitleResolver.extractTitle(html))
    }

    @Test
    fun `extractTitle falls back to document title`() {
        val html = "<html><head><title>Document Title</title></head></html>"

        assertEquals("Document Title", LinkTitleResolver.extractTitle(html))
    }

    @Test
    fun `extractTitle normalizes whitespace`() {
        val html = """<meta property="og:title" content="  One
            Two   Three  ">"""

        assertEquals("One Two Three", LinkTitleResolver.extractTitle(html))
    }

    @Test
    fun `extractTitle returns null when no title is available`() {
        assertNull(LinkTitleResolver.extractTitle("<html><head></head><body></body></html>"))
    }
}
