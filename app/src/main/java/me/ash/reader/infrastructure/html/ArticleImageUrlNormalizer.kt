package me.ash.reader.infrastructure.html

import java.net.URI
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

object ArticleImageUrlNormalizer {

    fun normalize(html: String, baseUrl: String? = null): String {
        if (html.isBlank()) return html

        val document = Jsoup.parseBodyFragment(html, baseUrl ?: "")
        document.select("img").forEach { image ->
            normalizeImageElement(image, baseUrl)
        }

        return document.body().html()
    }

    private fun normalizeImageElement(image: Element, baseUrl: String?) {
        val srcset = image.attr("srcset").takeIf { it.isNotBlank() }
        val src = image.attr("src").takeIf { it.isNotBlank() }

        if (!srcset.isNullOrBlank()) {
            val secureCandidates = parseSrcset(srcset)
                .mapNotNull { candidate ->
                    val resolvedUrl = resolveUrl(candidate.url, baseUrl) ?: return@mapNotNull null
                    candidate.copy(url = resolvedUrl)
                }
                .filter { isHttps(it.url) }

            if (secureCandidates.isNotEmpty()) {
                val preferredCandidate = secureCandidates.first()
                image.attr("src", preferredCandidate.url)
                image.attr("srcset", secureCandidates.joinToString(", ") { it.toSrcsetEntry() })
                return
            }
        }

        val upgradedSrc = src
            ?.let { resolveUrl(it, baseUrl) }
            ?.let { upgradeToHttps(it) }

        if (!upgradedSrc.isNullOrBlank()) {
            image.attr("src", upgradedSrc)
        }

        if (!srcset.isNullOrBlank()) {
            image.removeAttr("srcset")
        }
    }

    private fun parseSrcset(srcset: String): List<SrcsetCandidate> {
        return srcset.split(",")
            .mapNotNull { item ->
                val trimmed = item.trim()
                if (trimmed.isEmpty()) return@mapNotNull null

                val parts = trimmed.split(Regex("\\s+"), limit = 2)
                val url = parts.firstOrNull()?.trim().orEmpty()
                if (url.isEmpty()) return@mapNotNull null

                SrcsetCandidate(
                    url = url,
                    descriptor = parts.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() },
                )
            }
    }

    private fun resolveUrl(url: String, baseUrl: String?): String? {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return null
        if (baseUrl.isNullOrBlank()) return trimmed

        return runCatching {
            URI(baseUrl).resolve(trimmed).toString()
        }.getOrNull() ?: trimmed
    }

    private fun upgradeToHttps(url: String): String {
        return if (url.startsWith("http://", ignoreCase = true)) {
            "https://" + url.substringAfter("://")
        } else {
            url
        }
    }

    private fun isHttps(url: String): Boolean {
        return url.startsWith("https://", ignoreCase = true)
    }

    private data class SrcsetCandidate(
        val url: String,
        val descriptor: String? = null,
    ) {
        fun toSrcsetEntry(): String =
            if (descriptor.isNullOrBlank()) url else "$url $descriptor"
    }
}
