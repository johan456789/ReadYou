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
}
