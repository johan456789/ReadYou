package me.ash.reader.infrastructure.rss

import me.ash.reader.infrastructure.rss.provider.greader.GoogleReaderAPI
import org.junit.Assert.assertEquals
import org.junit.Test

class GoogleReaderServerUrlNormalizationTest {

    @Test
    fun normalizeServerUrl_appendsTrailingSlashWhenMissing() {
        val normalized = GoogleReaderAPI.normalizeServerUrl("https://demo.freshrss.org/api/greader.php")

        assertEquals("https://demo.freshrss.org/api/greader.php/", normalized)
    }

    @Test
    fun normalizeServerUrl_keepsExistingTrailingSlash() {
        val normalized = GoogleReaderAPI.normalizeServerUrl("https://demo.freshrss.org/api/greader.php/")

        assertEquals("https://demo.freshrss.org/api/greader.php/", normalized)
    }

    @Test
    fun normalizeServerUrl_trimsWhitespaceBeforeNormalizing() {
        val normalized = GoogleReaderAPI.normalizeServerUrl("  https://demo.freshrss.org/api/greader.php  ")

        assertEquals("https://demo.freshrss.org/api/greader.php/", normalized)
    }

    @Test
    fun normalizeServerUrl_returnsEmptyStringForBlankInput() {
        val normalized = GoogleReaderAPI.normalizeServerUrl("   ")

        assertEquals("", normalized)
    }
}
