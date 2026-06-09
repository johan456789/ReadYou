package me.ash.reader.infrastructure.html

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Test

class ArticleImageUrlNormalizerTest {

    @Test
    fun `normalize prefers secure srcset candidate when available`() {
        val html =
            """
            <p>
                <img
                    alt="Microsoft Antitrust"
                    src="http://thisdayintechhistory.com/wp-content/uploads/2012/06/microsoft_antitrust.jpg"
                    srcset="http://thisdayintechhistory.com/wp-content/uploads/2012/06/microsoft_antitrust.jpg 320w, https://thisdayintechhistory.com/wp-content/uploads/2012/06/microsoft_antitrust.jpg 640w"
                    sizes="(max-width: 320px) 100vw, 320px"
                />
            </p>
            """.trimIndent()

        val normalized =
            ArticleImageUrlNormalizer.normalize(
                html = html,
                baseUrl = "https://thisdayintechhistory.com/feed",
            )

        val image = Jsoup.parseBodyFragment(normalized).selectFirst("img")!!

        assertEquals(
            "https://thisdayintechhistory.com/wp-content/uploads/2012/06/microsoft_antitrust.jpg",
            image.attr("src"),
        )
        assertEquals(
            "https://thisdayintechhistory.com/wp-content/uploads/2012/06/microsoft_antitrust.jpg 640w",
            image.attr("srcset"),
        )
    }

    @Test
    fun `normalize upgrades src and srcset to https when no secure candidate exists`() {
        val html =
            """
            <p>
                <img
                    alt="Only HTTP"
                    src="http://example.com/image.jpg"
                    srcset="http://example.com/image-320.jpg 320w, http://example.com/image-640.jpg 640w"
                />
            </p>
            """.trimIndent()

        val normalized =
            ArticleImageUrlNormalizer.normalize(
                html = html,
                baseUrl = "https://example.com/article",
            )

        val image = Jsoup.parseBodyFragment(normalized).selectFirst("img")!!

        assertEquals("https://example.com/image.jpg", image.attr("src"))
        assertEquals(
            "https://example.com/image-320.jpg 320w, https://example.com/image-640.jpg 640w",
            image.attr("srcset"),
        )
    }

    @Test
    fun `normalize promotes upgraded srcset candidate when image has no src`() {
        val html =
            """
            <p>
                <img
                    alt="Only srcset"
                    srcset="http://example.com/image-320.jpg 320w, http://example.com/image-640.jpg 640w"
                />
            </p>
            """.trimIndent()

        val normalized =
            ArticleImageUrlNormalizer.normalize(
                html = html,
                baseUrl = "https://example.com/article",
            )

        val image = Jsoup.parseBodyFragment(normalized).selectFirst("img")!!

        assertEquals("https://example.com/image-320.jpg", image.attr("src"))
        assertEquals(
            "https://example.com/image-320.jpg 320w, https://example.com/image-640.jpg 640w",
            image.attr("srcset"),
        )
    }

    @Test
    fun `normalize preserves http image urls when article base url is http`() {
        val html =
            """
            <p>
                <img
                    alt="HTTP only"
                    src="http://example.com/image.jpg"
                    srcset="http://example.com/image-320.jpg 320w, http://example.com/image-640.jpg 640w"
                />
            </p>
            """.trimIndent()

        val normalized =
            ArticleImageUrlNormalizer.normalize(
                html = html,
                baseUrl = "http://example.com/article",
            )

        val image = Jsoup.parseBodyFragment(normalized).selectFirst("img")!!

        assertEquals("http://example.com/image.jpg", image.attr("src"))
        assertEquals(
            "http://example.com/image-320.jpg 320w, http://example.com/image-640.jpg 640w",
            image.attr("srcset"),
        )
    }

    @Test
    fun `normalize leaves data uri src unchanged`() {
        val html =
            """
            <p>
                <img
                    alt="Inline image"
                    src="data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAUA"
                />
            </p>
            """.trimIndent()

        val normalized =
            ArticleImageUrlNormalizer.normalize(
                html = html,
                baseUrl = "https://example.com/article",
            )

        val image = Jsoup.parseBodyFragment(normalized).selectFirst("img")!!

        assertEquals("data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAUA", image.attr("src"))
    }

    @Test
    fun `normalize leaves data uri srcset unchanged`() {
        val html =
            """
            <p>
                <img
                    alt="Inline responsive image"
                    src="http://example.com/fallback.jpg"
                    srcset="data:image/svg+xml;base64,PHN2Zy8+ 1x, https://example.com/image.jpg 2x"
                />
            </p>
            """.trimIndent()

        val normalized =
            ArticleImageUrlNormalizer.normalize(
                html = html,
                baseUrl = "https://example.com/article",
            )

        val image = Jsoup.parseBodyFragment(normalized).selectFirst("img")!!

        assertEquals("http://example.com/fallback.jpg", image.attr("src"))
        assertEquals(
            "data:image/svg+xml;base64,PHN2Zy8+ 1x, https://example.com/image.jpg 2x",
            image.attr("srcset"),
        )
    }
}
