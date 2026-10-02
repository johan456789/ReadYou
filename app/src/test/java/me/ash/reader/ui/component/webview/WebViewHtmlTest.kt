package me.ash.reader.ui.component.webview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WebViewHtmlTest {

    @Test
    fun freshRssSandboxedIframeGainsAllowPopups() {
        val html =
            """<iframe sandbox="allow-scripts allow-same-origin" width="100%" """ +
                """src="https://www.youtube.com/embed/x"></iframe>"""
        val out = WebViewHtml.allowIframePopups(html)
        assertTrue(
            out.contains("""sandbox="allow-scripts allow-same-origin allow-popups""""),
        )
        assertTrue(out.contains("""src="https://www.youtube.com/embed/x""""))
    }

    @Test
    fun existingAllowPopupsIsUntouched() {
        val html = """<iframe sandbox="allow-scripts allow-popups"></iframe>"""
        assertEquals(html, WebViewHtml.allowIframePopups(html))
    }

    @Test
    fun escapeSandboxTokenAloneDoesNotCountAsAllowPopups() {
        val html = """<iframe sandbox="allow-popups-to-escape-sandbox"></iframe>"""
        val out = WebViewHtml.allowIframePopups(html)
        assertTrue(
            out.contains("""sandbox="allow-popups-to-escape-sandbox allow-popups""""),
        )
    }

    @Test
    fun singleQuotedSandboxKeepsQuoteStyle() {
        val html = """<iframe sandbox='allow-scripts' src="x"></iframe>"""
        val out = WebViewHtml.allowIframePopups(html)
        assertTrue(out.contains("""sandbox='allow-scripts allow-popups'"""))
    }

    @Test
    fun unquotedSandboxValueIsQuotedWhenExtended() {
        val html = """<iframe sandbox=allow-scripts src="x"></iframe>"""
        val out = WebViewHtml.allowIframePopups(html)
        assertTrue(out.contains("""sandbox="allow-scripts allow-popups""""))
    }

    @Test
    fun emptySandboxGainsAllowPopups() {
        val html = """<iframe sandbox="" src="x"></iframe>"""
        val out = WebViewHtml.allowIframePopups(html)
        assertTrue(out.contains("""sandbox="allow-popups""""))
    }

    @Test
    fun htmlWithoutSandboxIsUntouched() {
        val html =
            """<p>hi</p><iframe width="100%" src="https://www.youtube.com/embed/x"></iframe>"""
        assertEquals(html, WebViewHtml.allowIframePopups(html))
    }

    @Test
    fun sandboxOutsideIframeTagsIsUntouched() {
        val html = """<div data-sandbox="x">text</div>"""
        assertEquals(html, WebViewHtml.allowIframePopups(html))
    }

    @Test
    fun multipleIframesAllGetAllowPopups() {
        val html =
            """<iframe sandbox="allow-scripts"></iframe><iframe src="a"></iframe>""" +
                """<iframe sandbox='allow-same-origin'></iframe>"""
        val out = WebViewHtml.allowIframePopups(html)
        // The sandbox-less iframe is already unrestricted and stays untouched.
        assertEquals(2, Regex("allow-popups").findAll(out).count())
        assertTrue(out.contains("""<iframe src="a"></iframe>"""))
    }

    @Test
    fun greaterThanInAttributeBeforeSandboxIsKnownEdge() {
        // Documented edge: the tag regex stops at the first ">", so a ">"
        // inside an attribute value before sandbox hides the sandbox
        // attribute and the iframe is left untouched. No real feed emits
        // this markup; pinning the behavior so a future regex change is
        // a deliberate decision.
        val html =
            """<iframe title="a > b" sandbox="allow-scripts" src="x"></iframe>"""
        assertEquals(html, WebViewHtml.allowIframePopups(html))
    }
}
