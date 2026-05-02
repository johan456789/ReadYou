package me.ash.reader.ui.component.webview

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.Buffer
import org.jsoup.Jsoup

object LinkTitleResolver {
    const val MaxHtmlBytes = 256 * 1024L

    fun createClient(): OkHttpClient {
        return OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    suspend fun fetchTitle(url: String, client: OkHttpClient): String? = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url(url)
                .header("Accept", "text/html,application/xhtml+xml")
                .header("Range", "bytes=0-${MaxHtmlBytes - 1}")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val body = response.body ?: return@withContext null
                extractTitle(readPrefix(body.source(), MaxHtmlBytes))
            }
        }.getOrNull()
    }

    fun extractTitle(html: String): String? {
        val document = Jsoup.parse(html)
        return listOf(
            document.selectFirst("meta[property=og:title]")?.attr("content"),
            document.selectFirst("meta[name=twitter:title]")?.attr("content"),
            document.selectFirst("meta[property=twitter:title]")?.attr("content"),
            document.title(),
        ).firstNotNullOfOrNull { it?.normalizedTitle() }
    }

    private fun readPrefix(source: okio.BufferedSource, maxBytes: Long): String {
        val buffer = Buffer()
        var remaining = maxBytes
        while (remaining > 0) {
            val read = source.read(buffer, minOf(remaining, 8 * 1024L))
            if (read == -1L) break
            remaining -= read
        }
        if (buffer.size == 0L) throw IOException("No response body")
        return buffer.readUtf8()
    }

    private fun String.normalizedTitle(): String? {
        return trim()
            .replace(Regex("\\s+"), " ")
            .takeIf { it.isNotBlank() }
    }
}
