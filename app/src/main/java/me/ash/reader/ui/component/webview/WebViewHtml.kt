package me.ash.reader.ui.component.webview

object WebViewHtml {

    const val HTML: String = """
<!DOCTYPE html>
<html dir="auto">
<head>
    <meta name="viewport" content="initial-scale=1, minimum-scale=1, maximum-scale=1, user-scalable=no, width=device-width, viewport-fit=cover" />
    <meta content="text/html; charset=utf-8" http-equiv="content-type"/>
    <style type="text/css">
        %s
    </style>
    <base href="%s" />
</head>
<body>
<main>
    <header id="ry-headline" class="ry-headline">
        %s
    </header>
    <article>
        %s
    </article>
</main>
<script>
%s
</script>
</body>
</html>
"""

    private val iframeTag = Regex("""<iframe\b[^>]*>""", RegexOption.IGNORE_CASE)

    private val sandboxAttr =
        Regex(
            """(?<=\s)sandbox\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s"'=<>`]+))""",
            RegexOption.IGNORE_CASE,
        )

    private val whitespace = Regex("""\s+""")

    /**
     * Aggregators such as FreshRSS (via SimplePie) add
     * `sandbox="allow-scripts allow-same-origin"` to every iframe but never
     * `allow-popups`. A sandboxed frame without that token cannot open new
     * windows at all: `window.open` / `target="_blank"` are blocked inside the
     * renderer, so WebChromeClient.onCreateWindow is never called and embed
     * links (e.g. the YouTube watermark) silently do nothing. Append the
     * missing token so the request reaches onCreateWindow, which opens it
     * externally instead of rendering a popup.
     */
    fun allowIframePopups(html: String): String =
        iframeTag.replace(html) { tag ->
            sandboxAttr.replace(tag.value) { attr ->
                val quote = if (attr.groups[2] != null) "'" else "\""
                val value =
                    when {
                        attr.groups[1] != null -> attr.groups[1]!!.value
                        attr.groups[2] != null -> attr.groups[2]!!.value
                        else -> attr.groups[3]!!.value
                    }
                val tokens = value.split(whitespace).filter { it.isNotEmpty() }
                if ("allow-popups" in tokens) {
                    attr.value
                } else {
                    val appended = (tokens + "allow-popups").joinToString(" ")
                    "sandbox=$quote$appended$quote"
                }
            }
        }
}
