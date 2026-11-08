package me.ash.reader.infrastructure.rss

import me.ash.reader.infrastructure.rss.provider.greader.normalizeFreshRssServerUrl
import org.junit.Assert.assertEquals
import org.junit.Test

class FreshRssServerUrlNormalizationTest {

    @Test
    fun normalizeServerUrl_appendsFreshRssApiPathToBareHost() {
        val normalized = normalizeFreshRssServerUrl("https://demo.freshrss.org")

        assertEquals("https://demo.freshrss.org/api/greader.php/", normalized)
    }

    @Test
    fun normalizeServerUrl_appendsFreshRssApiPathToHostWithTrailingSlash() {
        val normalized = normalizeFreshRssServerUrl("https://demo.freshrss.org/")

        assertEquals("https://demo.freshrss.org/api/greader.php/", normalized)
    }

    @Test
    fun normalizeServerUrl_appendsFreshRssApiPathToSubpathDeployment() {
        val normalized = normalizeFreshRssServerUrl("https://example.com/rss")

        assertEquals("https://example.com/rss/api/greader.php/", normalized)
    }

    @Test
    fun normalizeServerUrl_keepsCompleteUrlWithoutTrailingSlash() {
        val normalized = normalizeFreshRssServerUrl("https://demo.freshrss.org/api/greader.php")

        assertEquals("https://demo.freshrss.org/api/greader.php/", normalized)
    }

    @Test
    fun normalizeServerUrl_keepsCompleteUrlWithTrailingSlash() {
        val normalized = normalizeFreshRssServerUrl("https://demo.freshrss.org/api/greader.php/")

        assertEquals("https://demo.freshrss.org/api/greader.php/", normalized)
    }

    @Test
    fun normalizeServerUrl_trimsWhitespaceBeforeNormalizing() {
        val normalized = normalizeFreshRssServerUrl("  https://demo.freshrss.org  ")

        assertEquals("https://demo.freshrss.org/api/greader.php/", normalized)
    }

    @Test
    fun normalizeServerUrl_returnsEmptyStringForBlankInput() {
        val normalized = normalizeFreshRssServerUrl("   ")

        assertEquals("", normalized)
    }
}
